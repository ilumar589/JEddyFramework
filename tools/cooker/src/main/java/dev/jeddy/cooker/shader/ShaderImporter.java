package dev.jeddy.cooker.shader;

import dev.jeddy.asset.shader.ShaderException;
import dev.jeddy.asset.shader.ShaderPreprocessor;
import dev.jeddy.asset.shader.ShaderSources;
import dev.jeddy.cooker.CookContext;
import dev.jeddy.cooker.CopyImporter;
import dev.jeddy.cooker.ImportSettings;
import dev.jeddy.cooker.Importer;
import dev.jeddy.core.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Checks and copies GLSL sources (TASKS M6-06, DESIGN §10.3). Stage files ({@code .vert}, {@code
 * .frag}, {@code .comp}, {@code .geom}, {@code .tesc}, {@code .tese}) are expanded with the
 * runtime's {@link ShaderPreprocessor} for every permutation in the {@code permutations} setting (a
 * list of define lists; default one permutation without defines), which catches missing includes,
 * include cycles and misplaced {@code #version}s at cook time. When {@code glslangValidator} is on
 * the {@code PATH} each expansion is also compiled for its stage. Included files become
 * dependencies, so editing one re-checks every shader that uses it.
 *
 * <p>The output is the unmodified source at its normalized path: the runtime's {@code ProgramCache}
 * expands includes and permutation defines itself, so one cooked tree serves every permutation.
 * Include files ({@code .glsl}) are copied as they are.
 *
 * <p>Thread ownership: stateless; any thread.
 */
public final class ShaderImporter implements Importer {
    private static final List<String> STAGES =
            List.of("vert", "frag", "comp", "geom", "tesc", "tese");
    private static volatile Path validator;
    private static volatile boolean searched;

    @Override
    public String name() {
        return "shader";
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public boolean accepts(String path, ImportSettings settings) {
        String ext = CopyImporter.extension(path);
        return ext.equals("glsl") || STAGES.contains(ext);
    }

    @Override
    public void cook(CookContext ctx) throws Exception {
        String ext = CopyImporter.extension(ctx.path());
        ctx.output(CookContext.sanitize(ctx.path()), ctx.bytes());
        if (!STAGES.contains(ext)) {
            return;
        }
        List<Path> roots = new ArrayList<>();
        roots.add(ctx.config().sourceRoot());
        roots.addAll(ctx.config().shaderIncludeDirs());
        ShaderSources sources =
                path -> {
                    if (path.equals(ctx.path())) {
                        return new String(ctx.bytes(), StandardCharsets.UTF_8);
                    }
                    for (Path root : roots) {
                        Path file = root.resolve(path).normalize();
                        if (file.startsWith(root) && Files.isRegularFile(file)) {
                            return new String(ctx.readDependency(file), StandardCharsets.UTF_8);
                        }
                    }
                    throw new NoSuchFileException(path + " (searched " + roots + ")");
                };
        ShaderPreprocessor pre = new ShaderPreprocessor(sources);
        List<List<String>> permutations = permutations(ctx.settings());
        Path glslang = ctx.config().validateShaders() ? validator() : null;
        int validated = 0;
        for (List<String> defines : permutations) {
            ShaderPreprocessor.Result r;
            try {
                r = pre.process(ctx.path(), defines);
            } catch (ShaderException | NoSuchFileException e) {
                throw new ShaderException(ctx.path() + " " + defines + ": " + e.getMessage());
            }
            if (glslang != null) {
                String log = compile(glslang, ext, r.source());
                if (log != null) {
                    throw new ShaderException(
                            ctx.path() + " " + defines + " does not compile:\n" + r.annotate(log));
                }
                validated++;
            }
        }
        ctx.stat("permutations", permutations.size());
        ctx.stat("validated", validated);
        if (ctx.config().validateShaders() && glslang == null) {
            ctx.stat("validator", "glslangValidator not found; preprocessing only");
        }
    }

    private static List<List<String>> permutations(ImportSettings s) {
        Object raw = s.raw("permutations");
        List<List<String>> out = new ArrayList<>();
        if (raw == null) {
            out.add(List.of());
            return out;
        }
        for (Object p : Json.asArray(raw, "permutations")) {
            List<String> defines = new ArrayList<>();
            for (Object d : Json.asArray(p, "permutation")) {
                defines.add(String.valueOf(d));
            }
            out.add(defines);
        }
        return out;
    }

    /** Returns {@code glslangValidator} from the {@code PATH}, or {@code null}. */
    static Path validator() {
        if (!searched) {
            String path = System.getenv("PATH");
            Path found = null;
            if (path != null) {
                for (String dir : path.split(java.io.File.pathSeparator)) {
                    for (String name :
                            List.of(
                                    "glslangValidator",
                                    "glslangValidator.exe",
                                    "glslang",
                                    "glslang.exe")) {
                        try {
                            Path p = Path.of(dir, name);
                            if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                                found = p;
                                break;
                            }
                        } catch (RuntimeException ignored) {
                            // malformed PATH entry
                        }
                    }
                    if (found != null) {
                        break;
                    }
                }
            }
            validator = found;
            searched = true;
        }
        return validator;
    }

    /** Compiles {@code source} for {@code stage}; returns the error log, or {@code null}. */
    static String compile(Path glslang, String stage, String source)
            throws IOException, InterruptedException {
        Path tmp = Files.createTempFile("jeddy-shader-", "." + stage);
        try {
            Files.writeString(tmp, source, StandardCharsets.UTF_8);
            Process p =
                    new ProcessBuilder(glslang.toString(), tmp.toString())
                            .redirectErrorStream(true)
                            .start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "glslangValidator timed out";
            }
            if (p.exitValue() == 0) {
                return null;
            }
            String log = new String(out, StandardCharsets.UTF_8);
            // Report "ERROR: 0:12: ..." lines in the "0(12) : error" form the preprocessor maps.
            StringBuilder sb = new StringBuilder();
            for (String line : log.split("\r?\n")) {
                String t = line.strip();
                if (t.startsWith("ERROR: ") && t.length() > 7) {
                    String rest = t.substring(7);
                    int c1 = rest.indexOf(':');
                    int c2 = c1 < 0 ? -1 : rest.indexOf(':', c1 + 1);
                    if (c1 > 0 && c2 > c1) {
                        sb.append(rest, 0, c1)
                                .append('(')
                                .append(rest, c1 + 1, c2)
                                .append(") : error")
                                .append(rest.substring(c2))
                                .append('\n');
                        continue;
                    }
                }
                if (!t.isEmpty()
                        && !t.equals(tmp.toString())
                        && !t.toLowerCase(Locale.ROOT)
                                .contains(tmp.getFileName().toString().toLowerCase(Locale.ROOT))) {
                    sb.append(t).append('\n');
                }
            }
            return sb.toString();
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
