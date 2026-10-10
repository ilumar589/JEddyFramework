package dev.jeddy.core.json;

import java.io.Serial;

/**
 * Malformed JSON, or JSON of an unexpected shape.
 *
 * <p>Thread ownership: immutable.
 */
public final class JsonException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;

    /** Creates an exception with a message. */
    public JsonException(String message) {
        super(message);
    }
}
