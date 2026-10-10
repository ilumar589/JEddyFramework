package dev.jeddy.compress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.compress.ffi.ZSTD_inBuffer;
import dev.jeddy.compress.ffi.ZSTD_outBuffer;
import dev.jeddy.compress.ffi.ZstdLib;
import dev.jeddy.testing.GeneratedBindings;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Every zstd binding resolves and matches the ABI on this platform (TASKS M4-01, D22). */
class ZstdBindingsTest {
    @BeforeAll
    static void load() {
        Zstd.loadNatives();
    }

    @Test
    void everyBindingResolves() {
        int n = GeneratedBindings.initializeAll(ZstdBindingsTest.class, "dev.jeddy.compress.ffi");
        assertTrue(n > 60, "classes: " + n);
    }

    @Test
    void headersMatchTheLibraryAndAbi() {
        assertEquals(ZstdLib.ZSTD_VERSION_NUMBER(), ZstdLib.ZSTD_versionNumber());
        assertEquals(24, ZSTD_inBuffer.layout().byteSize());
        assertEquals(24, ZSTD_outBuffer.layout().byteSize());
        assertEquals(8, ZSTD_inBuffer.pos$offset() - ZSTD_inBuffer.size$offset());
    }
}
