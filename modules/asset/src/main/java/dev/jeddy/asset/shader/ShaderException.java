package dev.jeddy.asset.shader;

/**
 * A shader failed to preprocess, compile or link; the message holds the driver's log with file
 * names.
 *
 * <p>Thread ownership: an exception; immutable.
 */
public final class ShaderException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Creates the exception. */
    public ShaderException(String message) {
        super(message);
    }
}
