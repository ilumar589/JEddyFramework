package dev.jeddy.render.debug;

import dev.jeddy.core.profile.FrameStats;
import dev.jeddy.render.graph.FrameGraph;

/**
 * The in-game profiler overlay (TASKS M5-10, DESIGN §4.7): CPU frame times of the main and game
 * threads and the GPU time of every live frame-graph pass, as numbers and bars against a frame
 * budget. Draws into an {@link Overlay}; allocates nothing per frame.
 *
 * <p>Thread ownership: the GL thread.
 */
public final class ProfilerOverlay {
    private static final int TEXT = 0xFFE0E0E0;
    private static final int BACKGROUND = 0xA0101018;
    private static final int GOOD = 0xFF40C060;
    private static final int OVER = 0xFFE04040;

    private final Overlay overlay;
    private float budgetMs = 16.6f;

    /** Draws into {@code overlay}. */
    public ProfilerOverlay(Overlay overlay) {
        this.overlay = overlay;
    }

    /** Sets the frame budget the bars are scaled to (default 16.6 ms). */
    public void setBudget(float ms) {
        budgetMs = ms;
    }

    /** Queues the overlay at the top-left corner. */
    public void queue(
            FrameStats mainThread, FrameStats gameThread, FrameGraph graph, int drawCalls) {
        float x = 8;
        float y = 8;
        float scale = 2;
        float line = 9 * scale;
        int rows = 4 + graph.passCount();
        overlay.rect(x - 4, y - 4, 360, rows * line + 8, BACKGROUND);
        y = row(x, y, scale, "MAIN MS", mainThread == null ? 0 : mainThread.averageMs());
        y = row(x, y, scale, "GAME MS", gameThread == null ? 0 : gameThread.averageMs());
        float cx = overlay.text(x, y, scale, TEXT, "DRAWS ");
        overlay.number(cx, y, scale, TEXT, drawCalls, 0);
        y += line;
        overlay.text(x, y, scale, TEXT, "GPU PASSES (MS)");
        y += line;
        for (int p = 0; p < graph.passCount(); p++) {
            if (graph.isLive(p)) {
                y = row(x, y, scale, graph.passName(p), graph.passMillis(p));
            }
        }
    }

    private float row(float x, float y, float scale, CharSequence label, double ms) {
        overlay.text(x, y, scale, TEXT, label);
        float nx = overlay.number(x + 130, y, scale, TEXT, ms, 2);
        float bar = (float) Math.min(1, ms / budgetMs) * 140;
        overlay.rect(
                Math.max(nx + 8, x + 210),
                y + scale,
                Math.max(1, bar),
                6 * scale,
                ms > budgetMs ? OVER : GOOD);
        return y + 9 * scale;
    }
}
