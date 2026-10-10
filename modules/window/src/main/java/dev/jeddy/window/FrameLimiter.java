package dev.jeddy.window;

import dev.jeddy.sdl.ffi.SDL3;

/**
 * Caps the frame rate when VSync is off, or while minimized (DESIGN §12.1).
 *
 * <p>Frames are scheduled on a fixed grid ({@code next += period}), so short sleeps average out
 * instead of drifting; after a hitch longer than one period the grid restarts from now rather than
 * running a burst of catch-up frames. Waiting uses {@code SDL_DelayPrecise}, which sleeps until
 * shortly before the deadline and spins for the rest. Allocates nothing.
 *
 * <p>Thread ownership: one thread (the main thread in the app loop).
 */
public final class FrameLimiter {
    private long periodNanos;
    private long next;

    /** Creates an unlimited limiter. */
    public FrameLimiter() {}

    /** Creates a limiter at {@code fps} frames per second (0 = unlimited). */
    public FrameLimiter(int fps) {
        setTargetFps(fps);
    }

    /** Sets the cap in frames per second; 0 or less removes it. */
    public void setTargetFps(int fps) {
        periodNanos = fps > 0 ? 1_000_000_000L / fps : 0;
        next = 0;
    }

    /** Returns the frame period in nanoseconds, or 0 if unlimited. */
    public long periodNanos() {
        return periodNanos;
    }

    /**
     * Waits until the next frame is due. Call once per frame, after presenting.
     *
     * @return the nanoseconds spent waiting
     */
    public long waitForNextFrame() {
        if (periodNanos == 0) {
            return 0;
        }
        long now = SDL3.SDL_GetTicksNS();
        if (next == 0 || now - next > periodNanos) {
            next = now + periodNanos;
            return 0;
        }
        long wait = next - now;
        if (wait > 0) {
            SDL3.SDL_DelayPrecise(wait);
        }
        next += periodNanos;
        return Math.max(wait, 0);
    }
}
