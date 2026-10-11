package dev.jeddy.samples.iso;

/**
 * What the main thread needs from the game thread per frame index, handed over like the render
 * packets: the game fills slot {@code index} in {@code extract}, the renderer reads the same slot
 * when it draws that packet. Since M13 the HUD itself is built by the game thread with the UI
 * module ({@link DemoUi}) and travels in the packet; what remains here is the profiler toggle, the
 * perf capture state and the screenshot request.
 *
 * <p>Thread ownership: slot {@code i} belongs to whichever thread owns frame {@code i} (the app's
 * triple buffer hands it over with the packet).
 */
final class DemoHud {
    static final int FRAMES = 3;

    final boolean[] profiler = new boolean[FRAMES];
    final long[] gameNanos = new long[FRAMES];
    final boolean[] measuring = new boolean[FRAMES];
    final boolean[] screenshot = new boolean[FRAMES];
}
