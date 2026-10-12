package dev.jeddy.save;

import java.io.IOException;

/**
 * A {@code .jsave} file that is not one, or not one this build can read: a wrong magic, a header or
 * payload whose checksum does not match, a size that runs past the file, a format version newer
 * than {@link SaveFile#FORMAT_VERSION}, a chunk that overruns the payload. The message names the
 * problem and, where it helps, the byte offset, so a player's report can be acted on. A problem
 * inside a chunk's own stream (a system's {@code read} finding what it did not write) is not this
 * exception: it surfaces as the {@link dev.jeddy.core.io.ByteReader.Truncated} or {@code
 * IllegalStateException} the system throws.
 *
 * <p>Thread ownership: any.
 */
public final class SaveFormatException extends IOException {
    private static final long serialVersionUID = 1L;

    public SaveFormatException(String message) {
        super(message);
    }

    public SaveFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
