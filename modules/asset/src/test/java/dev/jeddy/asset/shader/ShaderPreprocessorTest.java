package dev.jeddy.asset.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Map;

/** {@code #include}, {@code #pragma once}, defines and line mapping (TASKS M5-05). */
class ShaderPreprocessorTest {
    private static ShaderSources map(Map<String, String> files) {
        return path -> {
            String s = files.get(path);
            if (s == null) {
                throw new NoSuchFileException(path);
            }
            return s;
        };
    }

    @Test
    void expandsIncludesOnceAndInjectsDefines() throws Exception {
        ShaderSources src =
                map(
                        Map.of(
                                "pbr/lit.frag",
                                "#version 460 core\n"
                                        + "#include \"../common/math.glsl\"\n"
                                        + "#include \"brdf.glsl\"\n"
                                        + "void main() {}\n",
                                "pbr/brdf.glsl",
                                "#pragma once\n"
                                        + "#include \"/common/math.glsl\"\n"
                                        + "float D() { return PI; }\n",
                                "common/math.glsl",
                                "#pragma once\nconst float PI = 3.14159;\n"));
        ShaderPreprocessor.Result r =
                new ShaderPreprocessor(src)
                        .process("pbr/lit.frag", List.of("NORMAL_MAP", "LIGHTS 64"));
        String s = r.source();
        assertTrue(
                s.startsWith(
                        "#version 460 core\n#define NORMAL_MAP 1\n#define LIGHTS 64\n#line 2 0\n"),
                s);
        assertEquals(1, s.split("const float PI", -1).length - 1, "math.glsl included once");
        assertTrue(s.contains("float D()"));
        assertEquals(List.of("pbr/lit.frag", "common/math.glsl", "pbr/brdf.glsl"), r.files());
        assertTrue(s.contains("#line 3 0\n"), "back in the main file after the first include");
        String log = r.annotate("2(3) : error C0000: syntax error\n");
        assertTrue(log.contains("<- pbr/brdf.glsl"), log);
    }

    @Test
    void reportsCyclesMissingFilesAndBadVersions() {
        ShaderSources src =
                map(
                        Map.of(
                                "a.glsl", "#include \"b.glsl\"\n",
                                "b.glsl", "#include \"a.glsl\"\n",
                                "main.vert", "#version 460\n#include \"a.glsl\"\n",
                                "noversion.vert", "void main() {}\n",
                                "nested.vert", "#version 460\n#include \"v.glsl\"\n",
                                "v.glsl", "#version 450\n",
                                "escape.vert", "#version 460\n#include \"../x.glsl\"\n"));
        ShaderPreprocessor p = new ShaderPreprocessor(src);
        ShaderException cycle =
                assertThrows(ShaderException.class, () -> p.process("main.vert", List.of()));
        assertTrue(cycle.getMessage().contains("a.glsl -> b.glsl -> a.glsl"), cycle.getMessage());
        assertThrows(ShaderException.class, () -> p.process("noversion.vert", List.of()));
        assertThrows(ShaderException.class, () -> p.process("nested.vert", List.of()));
        assertThrows(ShaderException.class, () -> p.process("escape.vert", List.of()));
        assertThrows(NoSuchFileException.class, () -> p.process("missing.vert", List.of()));
    }
}
