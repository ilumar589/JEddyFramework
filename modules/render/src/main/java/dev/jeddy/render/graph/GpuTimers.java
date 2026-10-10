package dev.jeddy.render.graph;

import static dev.jeddy.gl.ffi.GL46.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * GPU time per pass (TASKS M5-10, DESIGN §9.6): a {@code GL_TIMESTAMP} query before and after each
 * pass, in a ring of frames, read back {@code LATENCY} frames later without waiting. Results are
 * smoothed over a few frames.
 *
 * <p>Thread ownership: the GL thread.
 */
public final class GpuTimers implements AutoCloseable {
    private static final int FRAMES = 4;
    private static final int LATENCY = 3;

    private final int scopes;
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment names;
    private final MemorySegment result = arena.allocate(ValueLayout.JAVA_LONG);
    private final boolean[] issued;
    private final double[] smoothedNanos;
    private long frame;

    /** Creates queries for {@code scopes} timed scopes. */
    public GpuTimers(int scopes) {
        this.scopes = scopes;
        int n = scopes * 2 * FRAMES;
        names = arena.allocate(ValueLayout.JAVA_INT, n);
        glCreateQueries(GL_TIMESTAMP, n, names);
        issued = new boolean[n];
        smoothedNanos = new double[scopes];
    }

    /** Starts a frame: harvests the results of {@code LATENCY} frames ago. */
    public void beginFrame() {
        frame++;
        if (frame <= LATENCY) {
            return;
        }
        int slot = (int) ((frame - LATENCY) % FRAMES);
        for (int s = 0; s < scopes; s++) {
            int begin = index(slot, s, 0);
            int end = index(slot, s, 1);
            if (!issued[begin] || !issued[end]) {
                continue;
            }
            glGetQueryObjectui64v(name(end), GL_QUERY_RESULT_AVAILABLE, result);
            if (result.get(ValueLayout.JAVA_LONG, 0) == 0) {
                continue; // not there yet: skip rather than stall
            }
            glGetQueryObjectui64v(name(end), GL_QUERY_RESULT, result);
            long t1 = result.get(ValueLayout.JAVA_LONG, 0);
            glGetQueryObjectui64v(name(begin), GL_QUERY_RESULT, result);
            long t0 = result.get(ValueLayout.JAVA_LONG, 0);
            double ns = Math.max(0, t1 - t0);
            smoothedNanos[s] = smoothedNanos[s] == 0 ? ns : smoothedNanos[s] * 0.9 + ns * 0.1;
            issued[begin] = false;
            issued[end] = false;
        }
    }

    /** Marks the start of scope {@code s} in this frame. */
    public void begin(int s) {
        stamp(s, 0);
    }

    /** Marks the end of scope {@code s}. */
    public void end(int s) {
        stamp(s, 1);
    }

    private void stamp(int s, int which) {
        int i = index((int) (frame % FRAMES), s, which);
        glQueryCounter(name(i), GL_TIMESTAMP);
        issued[i] = true;
    }

    /** Returns the smoothed GPU time of scope {@code s} in milliseconds. */
    public double millis(int s) {
        return smoothedNanos[s] / 1e6;
    }

    private int index(int slot, int scope, int which) {
        return (slot * scopes + scope) * 2 + which;
    }

    private int name(int i) {
        return names.getAtIndex(ValueLayout.JAVA_INT, i);
    }

    @Override
    public void close() {
        glDeleteQueries(scopes * 2 * FRAMES, names);
        arena.close();
    }
}
