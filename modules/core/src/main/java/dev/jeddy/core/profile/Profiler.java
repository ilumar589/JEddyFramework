package dev.jeddy.core.profile;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Low-overhead CPU scope profiler (DESIGN §4.7). {@link #begin(int)} and {@link #end(int)} append a
 * timestamped record to the calling thread's ring buffer: two array stores and a release write, no
 * allocation. A reader (the overlay or a dump) periodically {@linkplain #drain drains} all threads
 * into a {@link ScopeStats}.
 *
 * <pre>{@code
 * static final int PHYSICS = ScopeIds.register("physics");
 * Profiler.begin(PHYSICS);
 * try { step(); } finally { Profiler.end(PHYSICS); }
 * }</pre>
 *
 * Disabled by default; when disabled each call is one volatile read.
 *
 * <p>Thread ownership: {@code begin}/{@code end} from any thread; {@code drain} from one reader
 * thread at a time.
 */
public final class Profiler {
    private static final int BUFFER_SIZE = 1 << 15;
    private static final CopyOnWriteArrayList<ThreadBuffer> BUFFERS = new CopyOnWriteArrayList<>();
    private static final ThreadLocal<ThreadBuffer> LOCAL =
            ThreadLocal.withInitial(Profiler::newBuffer);
    private static volatile boolean enabled;

    private Profiler() {}

    /** Turns recording on or off. */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /** Returns whether recording is on. */
    public static boolean isEnabled() {
        return enabled;
    }

    /** Marks the start of scope {@code scopeId} on this thread. */
    public static void begin(int scopeId) {
        if (enabled) {
            LOCAL.get().push((scopeId << 1) | 1, System.nanoTime());
        }
    }

    /** Marks the end of scope {@code scopeId} on this thread. */
    public static void end(int scopeId) {
        if (enabled) {
            LOCAL.get().push(scopeId << 1, System.nanoTime());
        }
    }

    /**
     * Consumes every record written since the previous drain and adds completed scopes to {@code
     * stats}. Records lost to ring wrap-around (reader too slow) are skipped.
     */
    public static void drain(ScopeStats stats) {
        for (ThreadBuffer b : BUFFERS) {
            b.drainInto(stats);
        }
    }

    private static ThreadBuffer newBuffer() {
        ThreadBuffer b = new ThreadBuffer(Thread.currentThread().getName());
        BUFFERS.add(b);
        return b;
    }

    static final class ThreadBuffer {
        private static final VarHandle WRITTEN;

        static {
            try {
                WRITTEN =
                        MethodHandles.lookup()
                                .findVarHandle(ThreadBuffer.class, "written", long.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        final String threadName;
        final int[] events = new int[BUFFER_SIZE];
        final long[] times = new long[BUFFER_SIZE];
        long written; // total records ever written; published with release
        // Reader-owned state:
        long read;
        final int[] stackScope = new int[64];
        final long[] stackStart = new long[64];
        int depth;

        ThreadBuffer(String threadName) {
            this.threadName = threadName;
        }

        void push(int event, long time) {
            long w = written;
            int i = (int) w & (BUFFER_SIZE - 1);
            events[i] = event;
            times[i] = time;
            WRITTEN.setRelease(this, w + 1);
        }

        void drainInto(ScopeStats stats) {
            long w = (long) WRITTEN.getAcquire(this);
            if (w - read > BUFFER_SIZE) {
                read = w - BUFFER_SIZE; // overwritten; restart matching
                depth = 0;
            }
            for (; read < w; read++) {
                int i = (int) read & (BUFFER_SIZE - 1);
                int e = events[i];
                long t = times[i];
                int scope = e >>> 1;
                if ((e & 1) != 0) {
                    if (depth < stackScope.length) {
                        stackScope[depth] = scope;
                        stackStart[depth] = t;
                    }
                    depth++;
                } else if (depth > 0) {
                    depth--;
                    if (depth < stackScope.length && stackScope[depth] == scope) {
                        stats.add(scope, t - stackStart[depth]);
                    }
                }
            }
        }
    }
}
