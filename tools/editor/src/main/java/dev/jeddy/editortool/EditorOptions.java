package dev.jeddy.editortool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Command line of the standalone editor: a project is an asset source tree ({@code --project}) and
 * the cooked output the runtime reads ({@code --cooked}); the level to open is a {@code .jlevel}
 * under the source tree.
 *
 * <p>Thread ownership: parsed on the main thread before anything starts; read-only afterwards.
 */
final class EditorOptions {
    Path project = Path.of(".");
    Path cooked;
    Path cache;
    String level;
    List<Path> shaderIncludes = new ArrayList<>();
    boolean cook = true;
    int width = 1600;
    int height = 900;
    int frames; // quit after this many frames (CI), 0 for never
    Path screenshot;
    List<String> commands = new ArrayList<>();
    Path components; // the game's components.json; default: the sibling build's class output

    static EditorOptions parse(String[] args) {
        EditorOptions o = new EditorOptions();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--project" -> o.project = Path.of(value(args, ++i, a));
                case "--cooked" -> o.cooked = Path.of(value(args, ++i, a));
                case "--cache" -> o.cache = Path.of(value(args, ++i, a));
                case "--level" -> o.level = value(args, ++i, a).replace('\\', '/');
                case "--shader-include" -> o.shaderIncludes.add(Path.of(value(args, ++i, a)));
                case "--no-cook" -> o.cook = false;
                case "--size" -> {
                    String[] wh = value(args, ++i, a).toLowerCase().split("x");
                    o.width = Integer.parseInt(wh[0]);
                    o.height = Integer.parseInt(wh[1]);
                }
                case "--frames" -> o.frames = Integer.parseInt(value(args, ++i, a));
                case "--screenshot" -> o.screenshot = Path.of(value(args, ++i, a));
                case "--command" -> o.commands.add(value(args, ++i, a));
                case "--components" -> o.components = Path.of(value(args, ++i, a));
                case "--help", "-h" -> {
                    System.out.println(usage());
                    System.exit(0);
                }
                default ->
                        throw new IllegalArgumentException("unknown option " + a + "\n" + usage());
            }
        }
        if (!Files.isDirectory(o.project)) {
            throw new IllegalArgumentException(
                    "project directory " + o.project + " does not exist");
        }
        if (o.cooked == null) {
            o.cooked = o.project.resolveSibling("build").resolve("cooked");
        }
        if (o.cache == null) {
            o.cache = o.cooked.resolveSibling("cook-cache");
        }
        if (o.level == null) {
            o.level = findLevel(o.project);
        }
        if (o.components == null) {
            // A game project keeps its assets beside build/: the schema the last compile wrote.
            o.components =
                    o.project
                            .resolveSibling("build")
                            .resolve("classes/java/main/META-INF/jeddy/components.json");
        }
        return o;
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[i];
    }

    /** The first {@code .jlevel} under the project, or {@code null}. */
    private static String findLevel(Path project) {
        try (var walk = Files.walk(project)) {
            return walk.filter(p -> p.toString().endsWith(".jlevel"))
                    .map(p -> project.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .findFirst()
                    .orElse(null);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    static String usage() {
        return """
        usage: jeddy-editor [options]
          --project DIR         the asset source tree (default .)
          --cooked DIR          the cooked output the runtime reads (default <project>/../build/cooked)
          --cache DIR           the cooker's cache (default next to the cooked output)
          --level PATH          the .jlevel to open, relative to the project (default: the first found)
          --shader-include DIR  extra shader include directory for the cooker (repeatable)
          --no-cook             do not run the cooker (use the cooked output as it is)
          --components FILE     the game's components.json for the inspector (default: the
                                sibling build/classes/java/main/META-INF/jeddy/components.json)
          --size WxH            window size (default 1600x900)
          --frames N            quit after N frames (CI smoke runs)
          --screenshot FILE     write a PNG of the last frame before quitting
          --command CMD         run an editor console command once the level is open (repeatable)
        controls: see the editor's View menu; F10 is not needed here, the editor is always shown
        """;
    }
}
