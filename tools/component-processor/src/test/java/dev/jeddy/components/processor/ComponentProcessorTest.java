package dev.jeddy.components.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * The processor's diagnostics and outputs, through the Compiler API (TASKS M22-03): every rejected
 * shape names its reason at the element, and a valid game yields the stores, the registry and the
 * schema. The generated code's behaviour is tested in the {@code components} module, which compiles
 * its sample game with this processor.
 */
class ComponentProcessorTest {
    @TempDir Path dir;

    private record Result(boolean ok, List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        String errors() {
            StringBuilder b = new StringBuilder();
            for (Diagnostic<? extends JavaFileObject> d : diagnostics) {
                if (d.getKind() == Diagnostic.Kind.ERROR) {
                    b.append(d.getMessage(null)).append('\n');
                }
            }
            return b.toString();
        }
    }

    private static final String PKG = "package t;\nimport dev.jeddy.components.*;\n";

    private Result compile(String... sources) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Path out = Files.createDirectories(dir.resolve("out"));
        Path gen = Files.createDirectories(dir.resolve("gen"));
        try (StandardJavaFileManager fm =
                compiler.getStandardFileManager(diagnostics, null, null)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(out.toFile()));
            fm.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(gen.toFile()));
            List<JavaFileObject> files = new ArrayList<>();
            int i = 0;
            for (String s : sources) {
                String name = className(s);
                files.add(new Source(name == null ? "Source" + i++ : name, s));
            }
            JavaCompiler.CompilationTask task =
                    compiler.getTask(
                            null,
                            fm,
                            diagnostics,
                            List.of(
                                    "-classpath",
                                    System.getProperty("java.class.path"),
                                    "-Xlint:all,-processing",
                                    "-Werror",
                                    "--release",
                                    "27"),
                            null,
                            files);
            task.setProcessors(List.of(new ComponentProcessor()));
            boolean ok = task.call();
            return new Result(ok, diagnostics.getDiagnostics());
        }
    }

    private static String className(String source) {
        if (source.contains("@Components(")) {
            return "package-info";
        }
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(?:record|class|enum|interface)\\s+(\\w+)")
                        .matcher(source);
        return m.find() ? m.group(1) : null;
    }

    private static final class Source extends SimpleJavaFileObject {
        private final String text;

        Source(String name, String text) {
            super(URI.create("string:///t/" + name + ".java"), Kind.SOURCE);
            this.text = text;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return text;
        }
    }

    private static final String REGISTRY =
            "@Components(name = \"TestGame\")\n"
                    + "package t;\n"
                    + "import dev.jeddy.components.Components;\n";

    @Test
    void aValidGameYieldsStoresViewsTheRegistryAndTheSchema() throws IOException {
        Result r =
                compile(
                        REGISTRY,
                        PKG + "@Component public record Health(@Default(\"3\") int current) {}",
                        PKG
                                + "@Behaviour public final class Regen { @OnFixedUpdate public"
                                + " static void regen(HealthMut h, float dt) {"
                                + " h.setCurrent(h.current() + 1); } @OnSpawn public static void"
                                + " init(HealthView h, int entity) {} }");
        assertTrue(r.ok, r.errors());
        assertTrue(Files.exists(dir.resolve("gen/t/HealthStore.java")));
        assertTrue(Files.exists(dir.resolve("gen/t/HealthView.java")));
        assertTrue(Files.exists(dir.resolve("gen/t/HealthMut.java")));
        assertTrue(Files.exists(dir.resolve("gen/t/TestGame.java")));
        assertTrue(Files.exists(dir.resolve("out/t/TestGame.class")));
        String schema = Files.readString(dir.resolve("out/META-INF/jeddy/components.json"));
        assertTrue(schema.contains("\"name\": \"Health\""), schema);
        assertTrue(schema.contains("\"default\": 3"), schema);
        assertTrue(schema.contains("\"name\": \"Regen.regen\""), schema);
        assertTrue(schema.contains("\"phase\": \"spawn\""), schema);
        String registry = Files.readString(dir.resolve("gen/t/TestGame.java"));
        assertTrue(registry.contains("public void fixedUpdate(float dt)"));
        assertTrue(registry.contains("t.Regen.regen(Regen_regen_v0, dt);"));
        assertTrue(registry.contains("for (int i = drive.size() - 1; i >= 0; i--)"), "backwards");
    }

    @Test
    void componentsWithoutBehavioursNeedNoRegistry() throws IOException {
        Result r = compile(PKG + "@Component public record Tag() {}");
        assertTrue(r.ok, r.errors());
        assertTrue(Files.exists(dir.resolve("gen/t/TagStore.java")));
        assertFalse(Files.exists(dir.resolve("out/META-INF/jeddy/components.json")));
    }

    @Test
    void aComponentMustBeAPublicTopLevelRecord() throws IOException {
        Result r = compile(PKG + "@Component public class Health { int hp; }");
        assertFalse(r.ok);
        assertTrue(r.errors().contains("@Component must be on a record"), r.errors());
        r = compile(PKG + "@Component record Health(int hp) {}");
        assertFalse(r.ok);
        assertTrue(r.errors().contains("must be public"), r.errors());
    }

    @Test
    void fieldTypesAreChecked() throws IOException {
        Result r = compile(PKG + "@Component public record Named(String name) {}");
        assertFalse(r.ok);
        assertTrue(
                r.errors().contains("unsupported component field type java.lang.String"),
                r.errors());
        r = compile(PKG + "@Component public record Bad(@Default(\"x\") int hp) {}");
        assertFalse(r.ok);
        assertTrue(r.errors().contains("@Default \"x\" is not a int"), r.errors());
        r = compile(PKG + "@Component public record Bad(@EntityRef float hp) {}");
        assertFalse(r.ok);
        assertTrue(r.errors().contains("go on int fields"), r.errors());
        r = compile(PKG + "@Component public record Bad(@Angle int hp) {}");
        assertFalse(r.ok);
        assertTrue(r.errors().contains("@Angle goes on a float field"), r.errors());
        r =
                compile(
                        PKG
                                + "@Component public record Bad(@Default(\"1, 2\")"
                                + " dev.jeddy.math.Vec3f v) {}");
        assertFalse(r.ok);
        assertTrue(r.errors().contains("needs 3 comma-separated numbers"), r.errors());
    }

    @Test
    void behaviourShapesAreChecked() throws IOException {
        String health = PKG + "@Component public record Health(int current) {}";
        Result r =
                compile(
                        REGISTRY,
                        health,
                        PKG
                                + "@Behaviour public final class B { @OnFixedUpdate public void"
                                + " f(HealthMut h) {} }");
        assertTrue(r.errors().contains("non-private static method"), r.errors());
        r =
                compile(
                        REGISTRY,
                        health,
                        PKG
                                + "@Behaviour public final class B { @OnFixedUpdate public static"
                                + " void f(HealthMut h, float speed) {} }");
        assertTrue(
                r.errors().contains("a float parameter of a behaviour is dt or alpha"), r.errors());
        r =
                compile(
                        REGISTRY,
                        health,
                        PKG
                                + "@Behaviour public final class B { @OnFixedUpdate public static"
                                + " void f(HealthMut h, float alpha) {} }");
        assertTrue(
                r.errors().contains("alpha is not a parameter of an OnFixedUpdate behaviour"),
                r.errors());
        r =
                compile(
                        REGISTRY,
                        health,
                        PKG
                                + "@Behaviour public final class B { @OnFixedUpdate public static"
                                + " void f(float dt) {} }");
        assertTrue(r.errors().contains("at least one required"), r.errors());
        r =
                compile(
                        REGISTRY,
                        health,
                        PKG
                                + "@Behaviour public final class B { @OnFixedUpdate public static"
                                + " void f(GhostView g) {} }");
        assertTrue(r.errors().contains("unknown parameter type GhostView"), r.errors());
        r =
                compile(
                        REGISTRY,
                        health,
                        PKG
                                + "@Behaviour public final class B { @OnFixedUpdate @OnUpdate"
                                + " public static void f(HealthView h) {} }");
        assertTrue(r.errors().contains("one phase annotation"), r.errors());
        r =
                compile(
                        REGISTRY,
                        health,
                        PKG
                                + "@Behaviour public final class B { public static void"
                                + " f(HealthView h) {} }");
        assertTrue(r.errors().contains("at least one phase-annotated"), r.errors());
        r =
                compile(
                        REGISTRY,
                        health,
                        PKG
                                + "@Behaviour public final class B { @OnSpawn @Parallel public"
                                + " static void f(HealthView h) {} }");
        assertTrue(r.errors().contains("@Parallel applies to"), r.errors());
        r =
                compile(
                        REGISTRY,
                        health,
                        PKG
                                + "@Behaviour public final class B { @OnFixedUpdate @Parallel"
                                + " public static void f(HealthMut h, StringBuilder log) {} }");
        assertTrue(r.errors().contains("@ThreadSafe"), r.errors());
        r =
                compile(
                        REGISTRY,
                        health,
                        PKG
                                + "@Behaviour public final class B { @OnFixedUpdate @Parallel"
                                + " public static void f(HealthMut h, TestGame game) {} }");
        assertTrue(r.errors().contains("may not take the registry"), r.errors());
        r =
                compile(
                        REGISTRY,
                        health,
                        PKG
                                + "@Behaviour public final class B { @OnFixedUpdate"
                                + " @Without({String.class}) public static void f(HealthMut h) {}"
                                + " }");
        assertTrue(r.errors().contains("@Without names a component"), r.errors());
        r =
                compile(
                        health,
                        PKG
                                + "@Behaviour public final class B { @OnFixedUpdate public static"
                                + " void f(HealthMut h) {} }");
        assertTrue(r.errors().contains("behaviours need a registry"), r.errors());
        r =
                compile(
                        REGISTRY,
                        health,
                        PKG + "@Component public record Health2(int current) {}",
                        PKG
                                + "@Behaviour public final class B { @OnFixedUpdate @Parallel"
                                + " public static void f(HealthMut h, @ThreadSafe StringBuilder"
                                + " log) {} }");
        assertTrue(r.ok, r.errors());
    }

    @Test
    void componentNamesAreUniqueAcrossPackages() throws IOException {
        Result r =
                compile(
                        PKG + "@Component public record Health(int current) {}",
                        "package u;\n"
                                + "import dev.jeddy.components.*;\n"
                                + "@Component public record Health(int max) {}");
        assertFalse(r.ok);
        assertTrue(r.errors().contains("component names must be unique"), r.errors());
        assertEquals(1, r.errors().lines().count(), r.errors());
    }
}
