package dev.jeddy.core;

/**
 * Framework-wide constants and runtime checks.
 *
 * <p>Thread ownership: stateless, callable from any thread.
 */
public final class Jeddy {
    /** The Java feature release the framework is built and tested against. */
    public static final int REQUIRED_JAVA_FEATURE = 27;

    private Jeddy() {}

    /**
     * Fails fast when the running JVM is older than {@link #REQUIRED_JAVA_FEATURE}. Call it first
     * thing at startup, before any native library is touched.
     *
     * @throws IllegalStateException if the runtime is too old
     */
    public static void checkRuntime() {
        int feature = Runtime.version().feature();
        if (feature < REQUIRED_JAVA_FEATURE) {
            throw new IllegalStateException(
                    "Jeddy needs Java " + REQUIRED_JAVA_FEATURE + " or newer, but is running on " + Runtime.version());
        }
    }
}
