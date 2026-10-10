package dev.jeddy.core.log;

/**
 * A named logger. Messages use {@code {}} placeholders: {@code log.info("loaded {} in {} ms",
 * name, millis)}.
 *
 * <p>Logging is for cold paths: arguments are boxed and the message is formatted on the calling
 * thread. In code that runs every frame, guard with {@link #isEnabled(LogLevel)}. Writing to the
 * sink happens on a background thread, so the caller never waits for I/O.
 *
 * <p>Thread ownership: thread-safe.
 */
public final class Logger {
    private final String name;

    Logger(String name) {
        this.name = name;
    }

    /** Returns the logger's name. */
    public String name() {
        return name;
    }

    /** Returns {@code true} if records at {@code level} are currently written. */
    public boolean isEnabled(LogLevel level) {
        return level.ordinal() >= Log.threshold().ordinal() && level != LogLevel.OFF;
    }

    /** Logs at TRACE. */
    public void trace(String message, Object... args) {
        log(LogLevel.TRACE, null, message, args);
    }

    /** Logs at DEBUG. */
    public void debug(String message, Object... args) {
        log(LogLevel.DEBUG, null, message, args);
    }

    /** Logs at INFO. */
    public void info(String message, Object... args) {
        log(LogLevel.INFO, null, message, args);
    }

    /** Logs at WARN. */
    public void warn(String message, Object... args) {
        log(LogLevel.WARN, null, message, args);
    }

    /** Logs at ERROR. */
    public void error(String message, Object... args) {
        log(LogLevel.ERROR, null, message, args);
    }

    /** Logs at ERROR with an exception. */
    public void error(String message, Throwable error) {
        log(LogLevel.ERROR, error, message);
    }

    /** Logs at WARN with an exception. */
    public void warn(String message, Throwable error) {
        log(LogLevel.WARN, error, message);
    }

    /** Logs at {@code level}. */
    public void log(LogLevel level, Throwable error, String message, Object... args) {
        if (!isEnabled(level)) {
            return;
        }
        Log.submit(level, name, format(message, args), error);
    }

    static String format(String message, Object[] args) {
        if (args == null || args.length == 0) {
            return message;
        }
        StringBuilder sb = new StringBuilder(message.length() + 16 * args.length);
        int argIndex = 0;
        int i = 0;
        while (i < message.length()) {
            int open = message.indexOf("{}", i);
            if (open < 0 || argIndex >= args.length) {
                sb.append(message, i, message.length());
                break;
            }
            sb.append(message, i, open).append(args[argIndex++]);
            i = open + 2;
        }
        return sb.toString();
    }
}
