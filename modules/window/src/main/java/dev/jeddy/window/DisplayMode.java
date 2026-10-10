package dev.jeddy.window;

/**
 * One display mode (resolution and refresh rate).
 *
 * <p>Thread ownership: immutable.
 *
 * @param width width in pixels
 * @param height height in pixels
 * @param pixelDensity pixels per window coordinate (1 on most Windows and X11 setups)
 * @param refreshRate refresh rate in Hz, or 0 if unknown
 */
public record DisplayMode(int width, int height, float pixelDensity, float refreshRate) {}
