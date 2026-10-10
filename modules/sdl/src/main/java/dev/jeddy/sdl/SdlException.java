package dev.jeddy.sdl;

/**
 * An SDL call reported failure. The message holds the call name and {@code SDL_GetError()}.
 *
 * <p>Thread ownership: immutable.
 */
public final class SdlException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** The SDL function that failed, for example {@code SDL_CreateWindow}. */
    private final String call;

    /** The SDL error text at the time of failure. */
    private final String sdlError;

    /**
     * Creates the exception.
     *
     * @param call the SDL function that failed
     * @param sdlError the text of {@code SDL_GetError()}
     */
    public SdlException(String call, String sdlError) {
        super(
                call
                        + " failed: "
                        + (sdlError == null || sdlError.isEmpty() ? "(no SDL error)" : sdlError));
        this.call = call;
        this.sdlError = sdlError == null ? "" : sdlError;
    }

    /** Returns the SDL function that failed. */
    public String call() {
        return call;
    }

    /** Returns the text of {@code SDL_GetError()} at the time of failure (may be empty). */
    public String sdlError() {
        return sdlError;
    }
}
