package dev.jeddy.window;

/**
 * How a window covers its display (DESIGN §12.1).
 *
 * <p>Thread ownership: immutable.
 */
public enum WindowMode {
    /** A decorated, movable window. */
    WINDOWED,
    /** A borderless window covering the whole display at desktop resolution (no mode switch). */
    BORDERLESS,
    /** Exclusive fullscreen at a chosen display mode (resolution and refresh rate). */
    EXCLUSIVE
}
