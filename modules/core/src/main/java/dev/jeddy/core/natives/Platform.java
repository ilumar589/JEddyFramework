package dev.jeddy.core.natives;

import java.util.Locale;

/**
 * Platforms Jeddy runs on (DESIGN §1: Windows and Linux, x64 only).
 *
 * <p>Thread ownership: immutable.
 */
public enum Platform {
    /** 64-bit Linux. */
    LINUX_X64("linux-x64"),
    /** 64-bit Windows. */
    WINDOWS_X64("windows-x64");

    private static final Platform CURRENT = detect();

    private final String id;

    Platform(String id) {
        this.id = id;
    }

    /** Returns the identifier used in native manifests and folder names, e.g. {@code linux-x64}. */
    public String id() {
        return id;
    }

    /** Returns {@code true} for Windows. */
    public boolean isWindows() {
        return this == WINDOWS_X64;
    }

    /** Returns the platform of the running JVM. */
    public static Platform current() {
        if (CURRENT == null) {
            throw new UnsupportedOperationException(
                    "unsupported platform: "
                            + System.getProperty("os.name")
                            + " "
                            + System.getProperty("os.arch")
                            + " (Jeddy runs on Windows and Linux x64)");
        }
        return CURRENT;
    }

    private static Platform detect() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!arch.equals("amd64") && !arch.equals("x86_64")) {
            return null;
        }
        if (os.startsWith("windows")) {
            return WINDOWS_X64;
        }
        if (os.startsWith("linux")) {
            return LINUX_X64;
        }
        return null;
    }
}
