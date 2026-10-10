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
    private float scale = 2;
    private float labelWidth;

    /** Draws into {@code overlay}. */
    public ProfilerOverlay(Overlay overlay) {
        this.overlay = overlay;
    }

    /** Sets the frame budget the bars are scaled to (default 16.6 ms). */
    public void setBudget(float ms) {
        budgetMs = ms;
    }

    /** Sets the text scale (default 2: 12×16-pixel glyph cells). */
    public void setScale(float value) {
        scale = value;
    }

    /** Queues the overlay at the top-left corner. */
    public void queue(
            FrameStats mainThread, FrameStats gameThread, FrameGraph graph, int drawCalls) {
        float x = 8;
        float y = 8;
        float line = 9 * scale;
        int longest = 8;
        int rows = 4;
        for (int p = 0; p < graph.passCount(); p++) {
            if (graph.isLive(p)) {
                longest = Math.max(longest, graph.passName(p).length());
                rows++;
            }
        }
        labelWidth = (longest + 1) * 6 * scale;
        float width = labelWidth + 6 * 6 * scale + 8 + 70 * scale;
        overlay.rect(x - 4, y - 4, width, rows * line + 8, BACKGROUND);
        y = row(x, y, "MAIN MS", mainThread == null ? 0 : mainThread.averageMs());
        y = row(x, y, "GAME MS", gameThread == null ? 0 : gameThread.averageMs());
        float cx = overlay.text(x, y, scale, TEXT, "DRAWS ");
        overlay.number(cx, y, scale, TEXT, drawCalls, 0);
        y += line;
        overlay.text(x, y, scale, TEXT, "GPU PASSES (MS)");
        y += line;
        for (int p = 0; p < graph.passCount(); p++) {
            if (graph.isLive(p)) {
                y = row(x, y, graph.passName(p), graph.passMillis(p));
            }
        }
    }

    private float row(float x, float y, CharSequence label, double ms) {
        overlay.text(x, y, scale, TEXT, label);
        float nx = overlay.number(x + labelWidth, y, scale, TEXT, ms, 2);
        float bar = (float) Math.min(1, ms / budgetMs) * 70 * scale;
        overlay.rect(
                Math.max(nx + 8, x + labelWidth + 6 * 6 * scale + 8),
                y + scale,
                Math.max(1, bar),
                6 * scale,
                ms > budgetMs ? OVER : GOOD);
        return y + 9 * scale;
    }
}
