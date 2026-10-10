package dev.jeddy.core.time;

/**
 * Monotonic nanosecond clock. Inject it so tests can drive time deterministically.
 *
 * <p>Thread ownership: implementations must be thread-safe.
 */
@FunctionalInterface
public interface TimeSource {
    /** Returns monotonic time in nanoseconds (arbitrary origin). */
    long nanoTime();

    /** Returns the system clock ({@link System#nanoTime()}). */
    static TimeSource system() {
        return System::nanoTime;
    }
}
