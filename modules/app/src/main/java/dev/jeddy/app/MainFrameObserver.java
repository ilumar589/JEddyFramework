package dev.jeddy.app;

/**
 * Diagnostics hook at the end of each main-thread frame. Must not allocate.
 *
 * <p>Thread ownership: main thread.
 */
@FunctionalInterface
public interface MainFrameObserver {
    /** Called after the frame was presented. */
    void onMainFrame(MainContext ctx);
}
