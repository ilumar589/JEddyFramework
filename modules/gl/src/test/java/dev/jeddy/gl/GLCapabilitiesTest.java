package dev.jeddy.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.jeddy.gl.GLCapabilities.Driver;

import org.junit.jupiter.api.Test;

/** Driver classification from the GL strings (no context needed). */
class GLCapabilitiesTest {
    @Test
    void vendorsAreClassifiedByWordNotBySubstring() {
        // "Corporation" contains "ati": an Intel or Microsoft driver must not read as AMD.
        assertEquals(Driver.INTEL, GLCapabilities.classify("Intel", "Intel(R) Graphics", "4.6.0"));
        assertEquals(
                Driver.INTEL,
                GLCapabilities.classify("Intel Corporation", "Intel(R) UHD Graphics", "4.6.0"));
        assertEquals(
                Driver.OTHER,
                GLCapabilities.classify("Microsoft Corporation", "GDI Generic", "1.1"));
        assertEquals(
                Driver.AMD, GLCapabilities.classify("ATI Technologies Inc.", "AMD Radeon", "4.6"));
        assertEquals(Driver.AMD, GLCapabilities.classify("AMD", "AMD Radeon RX 6600", "4.6"));
        assertEquals(
                Driver.NVIDIA, GLCapabilities.classify("NVIDIA Corporation", "GeForce", "4.6.0"));
        assertEquals(
                Driver.MESA,
                GLCapabilities.classify("AMD", "AMD Radeon (radeonsi)", "4.6 (Core) Mesa 24.0"));
        assertEquals(
                Driver.SOFTWARE,
                GLCapabilities.classify("Mesa", "llvmpipe (LLVM 17.0.6, 256 bits)", "4.5 Mesa"));
    }
}
