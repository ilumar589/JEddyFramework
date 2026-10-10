package dev.jeddy.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.core.config.Settings;
import dev.jeddy.core.id.NameTable;
import dev.jeddy.core.id.StrId;
import dev.jeddy.core.json.Json;
import dev.jeddy.core.json.JsonException;
import dev.jeddy.core.log.Log;
import dev.jeddy.core.log.LogLevel;
import dev.jeddy.core.log.Logger;
import dev.jeddy.core.memory.CStrings;
import dev.jeddy.core.memory.FrameAllocator;
import dev.jeddy.core.memory.MemoryCategory;
import dev.jeddy.core.memory.MemoryStats;
import dev.jeddy.core.memory.OffHeap;
import dev.jeddy.core.memory.Segments;
import dev.jeddy.core.profile.FrameStats;
import dev.jeddy.core.profile.Profiler;
import dev.jeddy.core.profile.ScopeIds;
import dev.jeddy.core.profile.ScopeStats;
import dev.jeddy.core.time.FixedStepper;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class UtilitiesTest {

    @Test
    void frameAllocatorBumpsAlignsAndResets() {
        long before = MemoryStats.current(MemoryCategory.TRANSIENT);
        try (FrameAllocator fa = new FrameAllocator(1024)) {
            assertEquals(before + 1024, MemoryStats.current(MemoryCategory.TRANSIENT));
            long a = fa.allocateOffset(3, 1);
            long b = fa.allocateOffset(8, 8);
            assertEquals(0, a);
            assertEquals(8, b);
            MemorySegment s = fa.allocate(ValueLayout.JAVA_INT, 4);
            assertEquals(16, s.byteSize());
            s.setAtIndex(ValueLayout.JAVA_INT, 3, 99);
            assertEquals(99, s.getAtIndex(ValueLayout.JAVA_INT, 3));
            assertThrows(IllegalStateException.class, () -> fa.allocateOffset(2000, 1));
            fa.reset();
            assertEquals(0, fa.used());
            assertEquals(32, fa.highWaterMark());
        }
        assertEquals(before, MemoryStats.current(MemoryCategory.TRANSIENT));
    }

    @Test
    void segmentCopiesAndCStrings() {
        try (OffHeap block = OffHeap.allocate(64, 16, MemoryCategory.OTHER)) {
            float[] src = {1, 2, 3, 4};
            Segments.copy(src, 1, block.segment(), 4, 3);
            float[] back = new float[4];
            Segments.copy(block.segment(), 0, back, 0, 4);
            assertEquals(0f, back[0]);
            assertEquals(4f, back[3]);
            assertEquals(48, Segments.alignUp(33, 16));
        }
        MemorySegment c = CStrings.of("jeddy");
        assertSame(c, CStrings.of("jeddy"));
        assertEquals(6, c.byteSize());
        assertEquals("jeddy", CStrings.read(c));
        assertEquals(null, CStrings.read(MemorySegment.NULL));
        assertTrue(MemoryStats.summary().startsWith("off-heap:"));
    }

    @Test
    void strIdMatchesUtf8Bytes() {
        assertEquals(0xcbf29ce484222325L, StrId.of(""));
        assertEquals(0xaf63dc4c8601ec8cL, StrId.of("a")); // FNV-1a 64 reference value
        String[] samples = {
            "textures/hero_albedo.ktx2", "ünïcødé", "日本語", "emoji 🧙 wizard", "bad \uD800 surrogate"
        };
        for (String s : samples) {
            byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
            assertEquals(StrId.of(utf8, 0, utf8.length), StrId.of(s), s);
        }
        assertNotEquals(StrId.of("a"), StrId.of("b"));
        assertEquals(16, StrId.hex(StrId.of("x")).length());
        NameTable names = new NameTable();
        long id = names.register("music/combat");
        assertEquals(id, names.register("music/combat"));
        assertEquals("music/combat", names.name(id));
    }

    @Test
    void settingsRoundTrip() {
        Settings s =
                Settings.parse(
                        """
                        # user settings
                        language = "en"
                        [graphics]
                        vsync = true   # adaptive later
                        fps_limit = 144
                        render_scale = 0.75
                        [audio]
                        volumes = [1.0, 0.8, 0.5]
                        devices = ["a, b", "c"]
                        """);
        assertEquals("en", s.getString("language", null));
        assertTrue(s.getBoolean("graphics.vsync", false));
        assertEquals(144, s.getInt("graphics.fps_limit", 0));
        assertEquals(0.75f, s.getFloat("graphics.render_scale", 0));
        assertEquals(List.of("1.0", "0.8", "0.5"), s.getStringList("audio.volumes"));
        assertEquals(List.of("a, b", "c"), s.getStringList("audio.devices"));
        assertEquals(7, s.getInt("missing", 7));
        s.set("graphics.fps_limit", 60);
        Settings again = Settings.parse(s.toToml());
        assertEquals(60, again.getInt("graphics.fps_limit", 0));
        assertEquals(s.keys(), again.keys());
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> Settings.parse("ok = 1\nbroken line"));
        assertTrue(e.getMessage().startsWith("line 2"));
    }

    @Test
    void fixedStepperCapsAndInterpolates() {
        FixedStepper st = new FixedStepper(30, 4);
        long step = st.stepNanos();
        assertEquals(0, st.advance(0));
        assertEquals(1, st.advance(step + step / 2));
        assertEquals(0.5f, st.alpha(), 1e-3f);
        assertEquals(4, st.advance(step * 100)); // stall: capped
        st.setPaused(true);
        assertEquals(0, st.advance(step * 200));
        st.setPaused(false);
        st.setTimeScale(0.5);
        assertEquals(1, st.advance(step * 202));
        assertEquals(6, st.ticks());
        Allocations.assertNoAllocation(
                "FixedStepper",
                new Runnable() {
                    long now = step * 300;

                    @Override
                    public void run() {
                        now += step / 3;
                        st.advance(now);
                        st.alpha();
                    }
                });
    }

    @Test
    void jsonParsesAndWrites() {
        Object v = Json.parse("{\"a\": [1, 2.5, -3e2, true, null, \"x\\n\\u0041\"], \"b\": {}}");
        Map<String, Object> o = Json.asObject(v, "root");
        List<Object> a = Json.asArray(o.get("a"), "a");
        assertEquals(1.0, a.get(0));
        assertEquals(-300.0, a.get(2));
        assertEquals("x\nA", a.get(5));
        assertEquals(null, a.get(4));
        String written = Json.write(v);
        assertEquals(v, Json.parse(written));
        assertThrows(JsonException.class, () -> Json.parse("{\"a\": }"));
        assertThrows(JsonException.class, () -> Json.parse("[1, 2] x"));
        assertEquals(3, Json.integer(Json.asObject(Json.parse("{\"n\": 3}"), "o"), "n", 0));
    }

    @Test
    void loggerFormatsAndFilters() {
        List<String> lines = new ArrayList<>();
        Log.setSink(
                (t, level, logger, thread, message, error) -> {
                    synchronized (lines) {
                        lines.add(level + " " + logger + ": " + message);
                    }
                });
        try {
            Logger log = Log.logger("test");
            Log.setThreshold(LogLevel.INFO);
            log.debug("hidden {}", 1);
            log.info("loaded {} in {} ms", "level1", 12);
            log.warn("extra {} {}", "only-one");
            Log.flush();
            synchronized (lines) {
                assertEquals(
                        List.of(
                                "INFO test: loaded level1 in 12 ms",
                                "WARN test: extra only-one {}"),
                        lines);
            }
            assertFalse(log.isEnabled(LogLevel.DEBUG));
        } finally {
            Log.setSink(Log.consoleSink(System.err));
        }
    }

    @Test
    void profilerMatchesNestedScopes() throws InterruptedException {
        int outer = ScopeIds.register("test.outer");
        int inner = ScopeIds.register("test.inner");
        assertEquals(outer, ScopeIds.register("test.outer"));
        ScopeStats stats = new ScopeStats();
        Profiler.setEnabled(true);
        try {
            for (int i = 0; i < 3; i++) {
                Profiler.begin(outer);
                Profiler.begin(inner);
                Thread.sleep(1);
                Profiler.end(inner);
                Profiler.end(outer);
            }
            Profiler.drain(stats);
            assertEquals(3, stats.count(outer));
            assertEquals(3, stats.count(inner));
            assertTrue(stats.totalNanos(outer) >= stats.totalNanos(inner));
            assertTrue(stats.maxNanos(inner) >= 1_000_000);
            assertTrue(stats.format().contains("test.inner"));
            Allocations.assertNoAllocation(
                    "Profiler.begin/end",
                    () -> {
                        Profiler.begin(outer);
                        Profiler.end(outer);
                    });
        } finally {
            Profiler.setEnabled(false);
        }
    }

    @Test
    void frameStatsPercentiles() {
        FrameStats fs = new FrameStats(100);
        for (int i = 1; i <= 100; i++) {
            fs.record(i * 1_000_000L);
        }
        assertEquals(50.5, fs.averageMs(), 1e-9);
        assertEquals(100.0, fs.maxMs(), 1e-9);
        assertEquals(1.0, fs.minMs(), 1e-9);
        assertEquals(99.0, fs.percentileMs(99), 1e-9);
    }
}
