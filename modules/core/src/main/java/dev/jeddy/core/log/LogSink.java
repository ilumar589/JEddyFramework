package dev.jeddy.core.log;

/**
 * Destination of formatted log records.
 *
 * <p>Thread ownership: called only from the logging thread, never concurrently.
 */
@FunctionalInterface
public interface LogSink {
    /**
     * Writes one record.
     *
     * @param epochMillis wall-clock time of the call
     * @param level severity
     * @param logger logger name
     * @param thread name of the calling thread
     * @param message formatted message
     * @param error attached exception, or {@code null}
     */
    void write(long epochMillis, LogLevel level, String logger, String thread, String message, Throwable error);
}
