package dev.jeddy.core.log;

import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Logging entry point: loggers, threshold and sink. Records are queued and written by one daemon
 * thread ({@code jeddy-log}), so callers never block on I/O.
 *
 * <pre>{@code
 * private static final Logger LOG = Log.logger("render");
 * LOG.info("GL {} on {}", version, renderer);
 * }</pre>
 *
 * The threshold defaults to INFO and can be set with {@code -Djeddy.log.level=DEBUG}.
 *
 * <p>Thread ownership: thread-safe.
 */
public final class Log {
    private static final ConcurrentHashMap<String, Logger> LOGGERS = new ConcurrentHashMap<>();
    private static final LinkedBlockingQueue<Record> QUEUE = new LinkedBlockingQueue<>(65_536);
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private static volatile LogLevel threshold = initialLevel();
    private static volatile LogSink sink = consoleSink(System.err);
    private static volatile boolean writerStarted;
    private static final Object FLUSH_LOCK = new Object();
    private static long submitted;
    private static long written;

    private Log() {}

    private record Record(
            long time, LogLevel level, String logger, String thread, String message, Throwable error) {}

    /** Returns the logger called {@code name}. */
    public static Logger logger(String name) {
        return LOGGERS.computeIfAbsent(name, Logger::new);
    }

    /** Returns the current threshold. */
    public static LogLevel threshold() {
        return threshold;
    }

    /** Sets the threshold below which records are dropped. */
    public static void setThreshold(LogLevel level) {
        threshold = level;
    }

    /** Replaces the sink. */
    public static void setSink(LogSink newSink) {
        sink = newSink;
    }

    /** Returns a sink that prints one line per record to {@code out}. */
    public static LogSink consoleSink(PrintStream out) {
        return (time, level, logger, thread, message, error) -> {
            out.println(TIME.format(Instant.ofEpochMilli(time)) + " " + pad(level) + " [" + thread + "] " + logger + ": " + message);
            if (error != null) {
                error.printStackTrace(out);
            }
        };
    }

    /** Blocks until every record submitted so far has been written, or 2 seconds passed. */
    public static void flush() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        synchronized (FLUSH_LOCK) {
            long target = submitted;
            while (written < target && System.nanoTime() < deadline) {
                try {
                    FLUSH_LOCK.wait(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    static void submit(LogLevel level, String logger, String message, Throwable error) {
        ensureWriter();
        Record r = new Record(System.currentTimeMillis(), level, logger, Thread.currentThread().getName(), message, error);
        synchronized (FLUSH_LOCK) {
            submitted++;
        }
        if (!QUEUE.offer(r)) {
            synchronized (FLUSH_LOCK) {
                written++; // dropped: the queue is full; never block the caller
            }
        }
    }

    private static void ensureWriter() {
        if (writerStarted) {
            return;
        }
        synchronized (Log.class) {
            if (writerStarted) {
                return;
            }
            Thread t = new Thread(Log::writeLoop, "jeddy-log");
            t.setDaemon(true);
            t.start();
            writerStarted = true;
        }
    }

    private static void writeLoop() {
        while (true) {
            try {
                Record r = QUEUE.take();
                try {
                    sink.write(r.time(), r.level(), r.logger(), r.thread(), r.message(), r.error());
                } catch (RuntimeException e) {
                    e.printStackTrace(System.err);
                }
                synchronized (FLUSH_LOCK) {
                    written++;
                    FLUSH_LOCK.notifyAll();
                }
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static String pad(LogLevel level) {
        String n = level.name();
        return n.length() == 5 ? n : n + " ";
    }

    private static LogLevel initialLevel() {
        String p = System.getProperty("jeddy.log.level");
        if (p != null) {
            try {
                return LogLevel.valueOf(p.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // fall through to the default
            }
        }
        return LogLevel.INFO;
    }
}
