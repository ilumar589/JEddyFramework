package dev.jeddy.gradle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Rewrites jextract output so one generated source tree works on every x64 target (DESIGN §6.4,
 * decision D22). Runs as the last step of {@code regenerate<Lib>Bindings}; the {@code main} method
 * runs the same code by hand ({@code java BindingsPostProcessor.java <package dir>}).
 *
 * <ul>
 *   <li>{@code C_LONG} becomes {@code C_LONG_LONG}. On Linux jextract maps {@code int64_t},
 *       {@code uint64_t} and {@code size_t} to C {@code long}, which is 32 bits on Windows, where
 *       the cast in the {@code C_LONG} initializer would throw. Every 64-bit integer is {@code long
 *       long} on both x64 ABIs.
 *   <li>Platform-dependent C types ({@code long}, {@code wchar_t}, {@code va_list}, {@code long
 *       double}) in any bound declaration fail the build, because the rewrite above would be wrong
 *       for them. The unused {@code C_LONG_DOUBLE} layout that jextract emits only on Windows is
 *       removed, so both platforms produce identical files.
 *   <li>{@code Arena.ofAuto()} becomes {@code Arena.global()}: the arena only holds string
 *       constants for the lifetime of the class, and engine code never uses automatic arenas.
 *   <li>Indexed accessors of struct array fields pass the field offset to the element var
 *       handle. jextract 22 passes {@code 0L}, so {@code s.field(i, v)} wrote at the start of the
 *       struct instead of inside the array (it clobbered {@code ktxBasisParams.structSize}).
 *   <li>Line endings become {@code \n}.
 * </ul>
 */
public final class BindingsPostProcessor {
    private static final Pattern SNIPPET =
            Pattern.compile("\\{@snippet lang=c :\\R(.*?)\\R\\s*\\*\\s*}", Pattern.DOTALL);
    private static final Pattern NON_PORTABLE =
            Pattern.compile("(?<!long )\\blong\\b(?! long)|\\bwchar_t\\b|\\bva_list\\b");
    private static final Pattern C_LONG_DEFINITION =
            Pattern.compile(
                    "(?m)^\\s*public static final ValueLayout\\.Of\\w+ C_LONG(_DOUBLE)? = .*\\R");
    private static final Pattern C_LONG_USE = Pattern.compile("\\bC_LONG\\b");
    private static final Pattern ELEMENT_ACCESS =
            Pattern.compile("(\\w+)\\$ELEM_HANDLE\\.(get|set)\\(struct, 0L, ");

    private BindingsPostProcessor() {}

    /** Processes every {@code .java} file directly inside {@code packageDir}. */
    public static void process(Path packageDir) {
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.list(packageDir)) {
            for (Path file : (Iterable<Path>) files.sorted()::iterator) {
                if (file.toString().endsWith(".java")) {
                    processFile(file, problems);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Bindings use platform-dependent C types; exclude these symbols from the"
                            + " include list or bind them by hand:\n  "
                            + String.join("\n  ", problems));
        }
    }

    private static void processFile(Path file, List<String> problems) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Matcher snippet = SNIPPET.matcher(text);
            while (snippet.find()) {
                String declaration = snippet.group(1).replaceAll("(?m)^\\s*\\*\\s?", "").trim();
                // The C declaration of a typedef'd 64-bit integer from the C library is fine; we
                // only reject declarations we bind directly.
                if (NON_PORTABLE.matcher(declaration).find()) {
                    String line = declaration.lines().findFirst().orElse(declaration);
                    String problem = file.getFileName() + ": " + line;
                    if (!problems.contains(problem)) {
                        problems.add(problem);
                    }
                }
            }
            // Line endings are normalized so Windows and Linux runs produce identical files.
            String out = text.replace("\r\n", "\n").replace("Arena.ofAuto()", "Arena.global()");
            out = C_LONG_DEFINITION.matcher(out).replaceAll("");
            out = C_LONG_USE.matcher(out).replaceAll("C_LONG_LONG");
            out = ELEMENT_ACCESS.matcher(out).replaceAll("$1\\$ELEM_HANDLE.$2(struct, $1\\$OFFSET, ");
            if (!out.equals(text)) {
                Files.writeString(file, out, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Command-line entry point: {@code BindingsPostProcessor <package dir>...}. */
    public static void main(String[] args) {
        for (String dir : args) {
            process(Path.of(dir));
        }
    }
}
