package dev.jeddy.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.List;

/** Permutation masks to defines (TASKS M5-05). */
class ShaderFeaturesTest {
    @Test
    void featuresMapBitsToDefines() {
        ShaderFeatures f = new ShaderFeatures("NORMAL_MAP", "ALPHA_TEST", "SKINNED");
        assertEquals(List.of("NORMAL_MAP", "SKINNED"), f.defines(0b101));
        assertEquals(2L, f.bit("ALPHA_TEST"));
        assertThrows(IllegalArgumentException.class, () -> f.defines(0b1000));
        assertThrows(IllegalArgumentException.class, () -> f.bit("NOPE"));
    }
}
