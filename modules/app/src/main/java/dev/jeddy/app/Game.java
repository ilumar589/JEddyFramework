package dev.jeddy.app;

/**
 * What a game plugs into {@link JeddyApp} (DESIGN §5, §13.2). Every callback runs on the game
 * thread; per-frame callbacks must not allocate (DESIGN §4.2).
 *
 * <p>Thread ownership: game thread.
 */
public interface Game {
    /** Called once on the game thread before the first frame: register actions, load settings. */
    default void init(AppContext ctx) {}

    /**
     * One fixed simulation tick (30 Hz by default): gameplay, physics, navigation, fog of war.
     *
     * @param dt the tick length in seconds (constant)
     */
    void fixedUpdate(AppContext ctx, float dt);

    /**
     * Once per frame after the ticks: camera, animation sampling, UI.
     *
     * @param dt real time since the previous frame in seconds
     * @param alpha how far this frame is between the last two ticks, for interpolation
     */
    default void update(AppContext ctx, float dt, float alpha) {}

    /**
     * Fills frame buffer {@code index} (0 to 2) for the main thread to render, for example the
     * render packet of that index. Called last in each frame, after {@link #update}.
     */
    default void extract(AppContext ctx, int index) {}

    /**
     * The user closed the window or the OS asked the app to quit.
     *
     * @return {@code true} to quit now, {@code false} to keep running (for example to show an "are
     *     you sure?" dialog, then call {@link AppContext#quit()})
     */
    default boolean onQuitRequested(AppContext ctx) {
        return true;
    }

    /** Called once on the game thread after the last frame. */
    default void shutdown(AppContext ctx) {}
}
