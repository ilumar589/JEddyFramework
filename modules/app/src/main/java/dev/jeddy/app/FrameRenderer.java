package dev.jeddy.app;

/**
 * Main-thread rendering of the frames the game thread extracts (DESIGN §5). The GL context is
 * current while these methods run.
 *
 * <p>Thread ownership: main thread.
 */
public interface FrameRenderer {
    /** Called once on the main thread after the window and GL context exist. */
    default void init(MainContext ctx) {}

    /**
     * Renders frame buffer {@code index} (0 to 2) as filled by {@link Game#extract}.
     *
     * @param fresh {@code false} if the game thread has not published a newer frame since the last
     *     call, so the same buffer is rendered again
     */
    void render(MainContext ctx, int index, boolean fresh);

    /** Called once on the main thread before the window is destroyed. */
    default void shutdown(MainContext ctx) {}
}
