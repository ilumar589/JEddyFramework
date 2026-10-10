package dev.jeddy.samples.iso;

/**
 * What the HUD shows, handed from the game thread to the main thread per frame index like the
 * render packets: the game fills slot {@code index} in {@code extract}, the renderer reads the same
 * slot when it draws that packet.
 *
 * <p>Thread ownership: slot {@code i} belongs to whichever thread owns frame {@code i} (the app's
 * triple buffer hands it over with the packet).
 */
final class DemoHud {
    static final int FRAMES = 3;

    final boolean[] loading = new boolean[FRAMES];
    final float[] loadProgress = new float[FRAMES];
    final boolean[] paused = new boolean[FRAMES];
    final boolean[] targeting = new boolean[FRAMES];
    final boolean[] turnBased = new boolean[FRAMES];
    final float[] moveCost = new float[FRAMES];
    final float[] moveBudget = new float[FRAMES];
    final boolean[] profiler = new boolean[FRAMES];
    final int[] selected = new int[FRAMES];
    final int[] enemiesAlive = new int[FRAMES];
    final int[] enemiesVisible = new int[FRAMES];
    final int[] formation = new int[FRAMES];
    final int[] instances = new int[FRAMES];
    final int[] lights = new int[FRAMES];
    final long[] gameNanos = new long[FRAMES];
    final boolean[] perf = new boolean[FRAMES];
    final boolean[] measuring = new boolean[FRAMES];
    final float[] perfRemaining = new float[FRAMES];
    final boolean[] box = new boolean[FRAMES];
    final float[] boxRect = new float[FRAMES * 4];

    /** Display scale: pixels per window coordinate (the box is in window coordinates). */
    final float[] pixelScale = new float[FRAMES];

    final boolean[] screenshot = new boolean[FRAMES];
}
