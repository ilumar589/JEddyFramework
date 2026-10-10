package dev.jeddy.app;

import dev.jeddy.input.EventPump;
import dev.jeddy.window.Cursors;
import dev.jeddy.window.MainThreadQueue;
import dev.jeddy.window.Window;

/**
 * The main thread's view of a running app: window, cursors, event pump and command queue.
 *
 * <p>Thread ownership: main thread only.
 */
public final class MainContext {
    final Window window;
    final Cursors cursors;
    final EventPump pump;
    final MainThreadQueue queue;
    long frame;

    MainContext(Window window, Cursors cursors, EventPump pump, MainThreadQueue queue) {
        this.window = window;
        this.cursors = cursors;
        this.pump = pump;
        this.queue = queue;
    }

    /** Returns the window. */
    public Window window() {
        return window;
    }

    /** Returns the cursor pool; register cursors here during {@link FrameRenderer#init}. */
    public Cursors cursors() {
        return cursors;
    }

    /** Returns the event pump (key labels, gamepad slots). */
    public EventPump pump() {
        return pump;
    }

    /** Returns the command queue, to add handlers for game-defined opcodes (128 and up). */
    public MainThreadQueue queue() {
        return queue;
    }

    /** Returns the number of main-thread frames completed. */
    public long frame() {
        return frame;
    }
}
