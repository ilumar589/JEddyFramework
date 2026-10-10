package dev.jeddy.samples.iso;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.management.ObjectName;

/**
 * The JIT's compilations during a perf run, so the report can tell JIT warm-up from engine
 * allocation (DESIGN D70). A thread that requests a C2 compilation resolves every string literal of
 * the method's class on the spot (HotSpot's {@code CompileBroker::compile_method}): the first C2
 * compile of a class allocates a few hundred bytes to a few KB on whichever thread triggered it,
 * and methods called once per frame reach C2 after about 5000 calls, 80 s into a 60 fps run, with a
 * tail of rarely called classes minutes later. An allocating frame within a moment of a C2 compile
 * is attributed to the JIT.
 *
 * <p>{@link #start} turns on unified logging of {@code jit+compilation} into a file through the
 * DiagnosticCommand MBean ({@code jcmd VM.log}); every line carries the JVM uptime in ms, the clock
 * of {@link java.lang.management.RuntimeMXBean#getUptime}.
 *
 * <p>Thread ownership: stateless; any thread (cold path).
 */
final class JitLog {
    /**
     * One compilation: when it started (JVM uptime ms), its tier and method, and whether it was the
     * first C2 compile of the method's class.
     */
    record Compile(long uptimeMillis, int tier, String method, boolean firstC2OfClass) {}

    private static final Pattern LINE =
            Pattern.compile("^\\[(\\d+)ms\\]\\s+\\d+\\s+([%sbn!]*)\\s*([0-4])\\s+(\\S+)");

    private JitLog() {}

    /**
     * Starts logging compilations to {@code file}; returns {@code false} (and logs nothing) if the
     * JVM does not offer the diagnostic command.
     */
    static boolean start(Path file) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.deleteIfExists(file);
            ManagementFactory.getPlatformMBeanServer()
                    .invoke(
                            new ObjectName("com.sun.management:type=DiagnosticCommand"),
                            "vmLog",
                            new Object[] {
                                new String[] {
                                    "output=" + file.toAbsolutePath(),
                                    "what=jit+compilation=debug",
                                    "decorators=uptimemillis"
                                }
                            },
                            new String[] {String[].class.getName()});
            return true;
        } catch (Exception | LinkageError e) {
            System.err.println("perf: no JIT log (" + e + "); allocation is judged strictly");
            return false;
        }
    }

    /** Reads the C2 (tier 4) compilations of {@code file}, in log order. */
    static List<Compile> read(Path file) throws IOException {
        List<Compile> out = new ArrayList<>();
        Set<String> compiledClasses = new HashSet<>();
        for (String line : Files.readAllLines(file)) {
            if (line.contains("made not entrant") || line.contains("made zombie")) {
                continue;
            }
            Matcher m = LINE.matcher(line);
            if (!m.find()) {
                continue;
            }
            int tier = Integer.parseInt(m.group(3));
            if (tier != 4) {
                continue;
            }
            String method = m.group(4);
            int sep = method.indexOf("::");
            String owner = sep < 0 ? method : method.substring(0, sep);
            out.add(
                    new Compile(
                            Long.parseLong(m.group(1)), tier, method, compiledClasses.add(owner)));
        }
        return out;
    }

    /**
     * The C2 compilation that explains an allocation between JVM uptimes {@code fromMillis} and
     * {@code toMillis}, or {@code null}: one that started in that interval or shortly after it (the
     * request, where the allocation happens, precedes the start by the compile queue's wait). A
     * class's first C2 compile is preferred.
     */
    static Compile explain(List<Compile> compiles, long fromMillis, long toMillis) {
        Compile any = null;
        for (Compile c : compiles) {
            if (c.uptimeMillis() < fromMillis - SLACK_BEFORE_MILLIS
                    || c.uptimeMillis() > toMillis + QUEUE_SLACK_MILLIS) {
                continue;
            }
            if (c.firstC2OfClass()) {
                return c;
            }
            if (any == null) {
                any = c;
            }
        }
        return any;
    }

    /** Clock slack between the frame stamps and the log's uptime decorations. */
    static final long SLACK_BEFORE_MILLIS = 2;

    /** How long a requested C2 compile may wait in the queue before it starts and is logged. */
    static final long QUEUE_SLACK_MILLIS = 500;
}
