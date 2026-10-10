package dev.jeddy.compress;

/**
 * A zstd call failed: corrupt input, a destination that is too small, a bad parameter.
 *
 * <p>Thread ownership: immutable.
 */
public final class ZstdException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** The zstd error code ({@code ZSTD_ErrorCode}). */
    private final int code;

    /**
     * Creates the exception.
     *
     * @param operation what failed
     * @param code the {@code ZSTD_ErrorCode}
     * @param name zstd's description of the error
     */
    public ZstdException(String operation, int code, String name) {
        super(operation + " failed: " + name + " (zstd error " + code + ")");
        this.code = code;
    }

    /** Returns the {@code ZSTD_ErrorCode}. */
    public int code() {
        return code;
    }
}
