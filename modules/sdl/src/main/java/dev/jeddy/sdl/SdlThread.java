package dev.jeddy.sdl;

/**
 * Records the SDL main thread and checks that main-thread-only SDL calls run on it (DESIGN §5).
 *
 * <p>On Windows and Linux the SDL main thread is the thread that called {@code
 * SDL_Init(SDL_INIT_VIDEO)}. {@code Platform.init} marks it; event polling, GL context calls,
 * swaps, cursors, text input and window-mode changes then call {@link #assertMainThread(String)},
 * which costs one field read and one comparison and never allocates unless it fails.
 *
 * <p>Thread ownership: thread-safe. {@link #markMainThread()} is called once at startup.
 */
public final class SdlThread {
    private static volatile Thread mainThread;

    private SdlThread() {}

    /**
     * Marks the calling thread as the SDL main thread.
     *
     * @throws IllegalStateException if another thread was already marked
     */
    public static synchronized void markMainThread() {
        Thread current = Thread.currentThread();
        Thread marked = mainThread;
        if (marked != null && marked != current) {
            throw new IllegalStateException(
                    "SDL main thread is already '"
                            + marked.getName()
                            + "', cannot move it to '"
                            + current.getName()
                            + "'");
        }
        mainThread = current;
    }

    /** Forgets the main thread (after {@code SDL_Quit}). */
    public static synchronized void clearMainThread() {
        mainThread = null;
    }

    /** Returns {@code true} if the calling thread is the SDL main thread. */
    public static boolean isMainThread() {
        return Thread.currentThread() == mainThread;
    }

    /** Returns the SDL main thread, or {@code null} before {@link #markMainThread()}. */
    public static Thread mainThread() {
        return mainThread;
    }

    /**
     * Fails fast if the calling thread is not the SDL main thread.
     *
     * @param call what is being called, for the error message (pass a constant)
     * @throws IllegalStateException off the main thread
     */
    public static void assertMainThread(String call) {
        if (Thread.currentThread() != mainThread) {
            throw wrongThread(call);
        }
    }

    private static IllegalStateException wrongThread(String call) {
        Thread marked = mainThread;
        return new IllegalStateException(
                call
                        + " must run on the SDL main thread ("
                        + (marked == null
                                ? "SDL video is not initialized"
                                : "'" + marked.getName() + "'")
                        + "), called from '"
                        + Thread.currentThread().getName()
                        + "'. Send it through the main-thread command queue (DESIGN §5).");
    }
}
