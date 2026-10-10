package dev.jeddy.asset.format;

import java.io.IOException;

/**
 * A cooked file is malformed, truncated or uses a feature this reader does not support.
 *
 * <p>Thread ownership: an exception; immutable.
 */
public final class AssetFormatException extends IOException {
    private static final long serialVersionUID = 1L;

    /** Creates the exception. */
    public AssetFormatException(String message) {
        super(message);
    }
}
