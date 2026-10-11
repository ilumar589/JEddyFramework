package dev.jeddy.asset.shader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Expands {@code #include "path"} (relative to the including file), honours {@code #pragma once},
 * and injects {@code #define}s after the {@code #version} line (TASKS M5-05, DESIGN §9.2). {@code
 * #line} directives keep compiler messages pointing at the right file and line: the second number
 * is an index into {@link Result#files()}. Shared by the runtime ({@code render}, dev builds) and
 * the cooker, which flattens shaders at cook time.
 *
 * <p>Thread ownership: one thread per instance; cold path.
 */
public final class ShaderPreprocessor {
    private static final int MAX_DEPTH = 32;

    private final ShaderSources sources;

    /** The expanded source and the files that went into it (index = {@code #line} file number). */
    public record Result(String source, List<String> files) {
        /** Rewrites {@code "0(12) : error"}-style file numbers in a compiler log to file names. */
        public String annotate(String log) {
            StringBuilder out = new StringBuilder(log.length() + 64);
            for (String line : log.split("\n", -1)) {
                out.append(line);
                int n = leadingNumber(line);
                if (n >= 0 && n < files.size()) {
                    out.append("   <- ").append(files.get(n));
                }
                out.append('\n');
            }
            return out.toString();
        }

        private static int leadingNumber(String line) {
            int i = 0;
            while (i < line.length() && line.charAt(i) == ' ') {
                i++;
            }
            int start = i;
            while (i < line.length() && Character.isDigit(line.charAt(i))) {
                i++;
            }
            if (i == start
                    || i >= line.length()
                    || (line.charAt(i) != '(' && line.charAt(i) != ':')) {
                return -1;
            }
            return Integer.parseInt(line.substring(start, i));
        }
    }

    /** Creates a preprocessor reading from {@code sources}. */
    public ShaderPreprocessor(ShaderSources sources) {
        this.sources = sources;
    }

    /**
     * Expands {@code path}.
     *
     * @param defines {@code NAME} or {@code NAME VALUE} entries, inserted as {@code #define}s
     * @throws IOException if a file is missing
     * @throws ShaderException on include cycles, a missing {@code #version} or bad directives
     */
    public Result process(String path, List<String> defines) throws IOException {
        List<String> files = new ArrayList<>();
        StringBuilder out = new StringBuilder(4096);
        Set<String> once = new HashSet<>();
        List<String> stack = new ArrayList<>();
        expand(path, defines, true, out, files, once, stack);
        return new Result(out.toString(), List.copyOf(files));
    }

    private void expand(
            String path,
            List<String> defines,
            boolean main,
            StringBuilder out,
            List<String> files,
            Set<String> once,
            List<String> stack)
            throws IOException {
        if (stack.contains(path)) {
            throw new ShaderException(
                    "#include cycle: " + String.join(" -> ", stack) + " -> " + path);
        }
        if (stack.size() > MAX_DEPTH) {
            throw new ShaderException("#include nesting deeper than " + MAX_DEPTH + " at " + path);
        }
        if (once.contains(path)) {
            return;
        }
        String text = sources.load(path);
        int fileIndex = files.size();
        files.add(path);
        stack.add(path);
        if (!main) {
            out.append("#line 1 ").append(fileIndex).append('\n'); // errors point into this file
        }
        String[] lines = text.split("\r?\n", -1);
        boolean versionSeen = !main;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String t = line.strip();
            if (t.startsWith("#version")) {
                if (!main) {
                    throw new ShaderException(
                            path + ":" + (i + 1) + ": #version in an included file");
                }
                out.append(line).append('\n');
                for (String d : defines) {
                    out.append("#define ").append(d.indexOf(' ') < 0 ? d + " 1" : d).append('\n');
                }
                out.append("#line ").append(i + 2).append(' ').append(fileIndex).append('\n');
                versionSeen = true;
            } else if (t.startsWith("#pragma") && t.substring(7).strip().equals("once")) {
                once.add(path);
                out.append('\n');
            } else if (t.startsWith("#include")) {
                String rest = t.substring(8).strip();
                if (rest.length() < 2 || rest.charAt(0) != '"' || rest.indexOf('"', 1) < 0) {
                    throw new ShaderException(
                            path + ":" + (i + 1) + ": expected #include \"file\"");
                }
                String target = resolve(path, rest.substring(1, rest.indexOf('"', 1)));
                expand(target, defines, false, out, files, once, stack);
                out.append("#line ").append(i + 2).append(' ').append(fileIndex).append('\n');
            } else {
                if (!versionSeen && !t.isEmpty() && !t.startsWith("//")) {
                    throw new ShaderException(path + ": the first statement must be #version");
                }
                out.append(line).append('\n');
            }
        }
        stack.remove(stack.size() - 1);
    }

    /** Resolves {@code include} relative to the directory of {@code from}; handles {@code ..}. */
    static String resolve(String from, String include) {
        if (include.startsWith("/")) {
            return include.substring(1);
        }
        int slash = from.lastIndexOf('/');
        String base = slash < 0 ? "" : from.substring(0, slash + 1);
        List<String> parts = new ArrayList<>();
        for (String p : (base + include).split("/")) {
            if (p.isEmpty() || p.equals(".")) {
                continue;
            }
            if (p.equals("..")) {
                if (parts.isEmpty()) {
                    throw new ShaderException("#include escapes the shader root: " + include);
                }
                parts.remove(parts.size() - 1);
            } else {
                parts.add(p);
            }
        }
        return String.join("/", parts);
    }
}
