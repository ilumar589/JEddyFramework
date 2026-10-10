package dev.jeddy.core.time;

/**
 * Fixed-timestep accumulator for the simulation (DESIGN §5: 30 Hz by default). Each frame, {@link
 * #advance(long)} returns how many ticks to simulate; {@link #alpha()} is how far the frame is
 * between the last two ticks, for interpolating transforms when rendering.
 *
 * <p>The number of ticks per frame is capped, so a long stall (loading, debugger) slows the game
 * down instead of making it simulate a burst of catch-up ticks ("spiral of death").
 *
 * <p>Thread ownership: single owner thread (the game thread).
 */
public final class FixedStepper {
    private final long stepNanos;
    private final int maxStepsPerFrame;
    private long last = Long.MIN_VALUE;
    private long accumulator;
    private long ticks;
    private double timeScale = 1.0;
    private boolean paused;

    /**
     * Creates a stepper.
     *
     * @param ticksPerSecond simulation rate, for example 30
     * @param maxStepsPerFrame most ticks returned by one {@link #advance(long)}
     */
    public FixedStepper(double ticksPerSecond, int maxStepsPerFrame) {
        if (!(ticksPerSecond > 0) || maxStepsPerFrame < 1) {
            throw new IllegalArgumentException("invalid rate or step cap");
        }
        this.stepNanos = Math.round(1e9 / ticksPerSecond);
        this.maxStepsPerFrame = maxStepsPerFrame;
    }

    /** Returns the duration of one tick in seconds. */
    public float stepSeconds() {
        return (float) (stepNanos / 1e9);
    }

    /** Returns the duration of one tick in nanoseconds. */
    public long stepNanos() {
        return stepNanos;
    }

    /** Returns the number of ticks simulated so far. */
    public long ticks() {
        return ticks;
    }

    /**
     * Accounts for the time since the previous call and returns the number of ticks to simulate now
     * (0 while paused).
     */
    public int advance(long nowNanos) {
        if (last == Long.MIN_VALUE) {
            last = nowNanos;
            return 0;
        }
        long elapsed = Math.max(0, nowNanos - last);
        last = nowNanos;
        if (paused) {
            return 0;
        }
        long scaled = timeScale == 1.0 ? elapsed : (long) (elapsed * timeScale);
        accumulator = Math.min(accumulator + scaled, stepNanos * (long) maxStepsPerFrame);
        int steps = (int) (accumulator / stepNanos);
        accumulator -= steps * stepNanos;
        ticks += steps;
        return steps;
    }

    /** Returns the fraction of the next tick already elapsed, in {@code [0, 1)}. */
    public float alpha() {
        return (float) accumulator / stepNanos;
    }

    /** Pauses or resumes simulation time. While paused, {@link #advance(long)} returns 0. */
    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    /** Returns {@code true} while paused. */
    public boolean isPaused() {
        return paused;
    }

    /** Sets the speed multiplier (1 = real time, 0.5 = slow motion). */
    public void setTimeScale(double scale) {
        if (!(scale >= 0)) {
            throw new IllegalArgumentException("negative time scale");
        }
        timeScale = scale;
    }

    /** Drops accumulated time and restarts timing at {@code nowNanos}. */
    public void reset(long nowNanos) {
        last = nowNanos;
        accumulator = 0;
    }
}
