package dev.jeddy.gl;

/**
 * An OpenGL failure: an unsupported driver, a shader that does not compile, or a GL error.
 *
 * <p>Thread ownership: immutable.
 */
public final class GLException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Creates the exception with a message. */
    public GLException(String message) {
        super(message);
    }
}
