package dev.jeddy.window;

/**
 * Swap interval of the GL context.
 *
 * <p>Thread ownership: immutable.
 */
public enum VSync {
    /** Swap immediately (interval 0); pair with a {@link FrameLimiter}. */
    OFF,
    /** Wait for vertical blank (interval 1). */
    ON,
    /**
     * Wait for vertical blank unless the frame is late, then swap immediately (interval -1, "late
     * swap tearing"). Falls back to {@link #ON} where the driver does not support it.
     */
    ADAPTIVE
}
