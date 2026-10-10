package dev.jeddy.core.log;

/**
 * Log severity, from most to least verbose.
 *
 * <p>Thread ownership: immutable.
 */
public enum LogLevel {
    /** Fine-grained tracing. */
    TRACE,
    /** Debugging detail. */
    DEBUG,
    /** Normal operation. */
    INFO,
    /** Something unexpected that the program handled. */
    WARN,
    /** A failure. */
    ERROR,
    /** Logs nothing; only valid as a threshold. */
    OFF
}
