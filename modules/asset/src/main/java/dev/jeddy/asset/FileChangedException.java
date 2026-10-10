package dev.jeddy.asset;

import java.io.IOException;

/**
 * A loose file changed size between scheduling a load and reading it (the cooker rewrote it). The
 * asset manager retries such loads.
 *
 * <p>Thread ownership: an exception; immutable.
 */
public final class FileChangedException extends IOException {
    private static final long serialVersionUID = 1L;

    /** Creates the exception. */
    public FileChangedException(String message) {
        super(message);
    }
}
