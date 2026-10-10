package dev.jeddy.window;

import dev.jeddy.core.concurrent.SpscIntRing;
import dev.jeddy.sdl.SdlThread;

/**
 * The game thread's way to ask for main-thread-only SDL work (DESIGN §5, TASKS M2-10): cursor
 * changes, text input, window mode, relative mouse, rumble. Requests are fixed records of an opcode
 * and five {@code int} arguments in a lock-free {@link SpscIntRing}; the main thread drains them at
 * the start of each frame and hands each record to the registered {@link Handler}s.
 *
 * <p>Opcode ranges: {@code window} uses 1–63 ({@link WindowCommands}), {@code input} 64–127, games
 * and later modules 128 and up. Posting and draining allocate nothing.
 *
 * <p>Thread ownership: one producer thread (the game thread) calls {@code post}; the main thread
 * calls {@link #drain()} and registers handlers during startup.
 */
public final class MainThreadQueue {
    /** {@code int}s per record: opcode plus five arguments. */
    public static final int RECORD_INTS = 6;

    /** Executes one kind of request on the main thread. */
    @FunctionalInterface
    public interface Handler {
        /**
         * Executes {@code opcode} if this handler owns it.
         *
         * @return {@code true} if handled; the queue then stops asking other handlers
         */
        boolean handle(int opcode, int a, int b, int c, int d, int e);
    }

    private final SpscIntRing ring;
    private final int[] producerScratch = new int[RECORD_INTS];
    private final int[] consumerScratch = new int[RECORD_INTS];
    private Handler[] handlers = new Handler[0];
    private long dropped;
    private long unhandled;

    /** Creates a queue holding at least {@code capacityRecords} pending requests. */
    public MainThreadQueue(int capacityRecords) {
        int ints = Math.max(2, capacityRecords * RECORD_INTS);
        this.ring = new SpscIntRing(Integer.highestOneBit(ints - 1) << 1); // next power of two
    }

    /** Adds a handler; handlers are asked in registration order. Main thread, during startup. */
    public synchronized void addHandler(Handler handler) {
        Handler[] next = new Handler[handlers.length + 1];
        System.arraycopy(handlers, 0, next, 0, handlers.length);
        next[handlers.length] = handler;
        handlers = next;
    }

    /**
     * Queues a request (producer thread). Returns {@code false} and counts a drop if the queue is
     * full, which means the main thread has stalled for a long time.
     */
    public boolean post(int opcode, int a, int b, int c, int d, int e) {
        int[] r = producerScratch;
        r[0] = opcode;
        r[1] = a;
        r[2] = b;
        r[3] = c;
        r[4] = d;
        r[5] = e;
        if (ring.offer(r, 0, RECORD_INTS)) {
            return true;
        }
        dropped++;
        return false;
    }

    /** Queues a request with no arguments. */
    public boolean post(int opcode) {
        return post(opcode, 0, 0, 0, 0, 0);
    }

    /** Queues a request with one argument. */
    public boolean post(int opcode, int a) {
        return post(opcode, a, 0, 0, 0, 0);
    }

    /**
     * Executes every pending request on the main thread.
     *
     * @return the number of requests executed
     */
    public int drain() {
        SdlThread.assertMainThread("MainThreadQueue.drain");
        int[] r = consumerScratch;
        Handler[] hs = handlers;
        int count = 0;
        while (ring.poll(r, 0, RECORD_INTS)) {
            boolean handled = false;
            for (int i = 0; i < hs.length && !handled; i++) {
                handled = hs[i].handle(r[0], r[1], r[2], r[3], r[4], r[5]);
            }
            if (!handled) {
                unhandled++;
            }
            count++;
        }
        return count;
    }

    /** Returns how many requests were dropped because the queue was full (producer's view). */
    public long dropped() {
        return dropped;
    }

    /** Returns how many requests no handler accepted (main thread's view). */
    public long unhandled() {
        return unhandled;
    }

    /** Returns the number of pending requests. */
    public int pending() {
        return ring.size() / RECORD_INTS;
    }
}
