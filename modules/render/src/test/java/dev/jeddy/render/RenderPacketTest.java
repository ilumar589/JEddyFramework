package dev.jeddy.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.jeddy.core.concurrent.TripleBuffer;
import dev.jeddy.math.AffineOps;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

/** Packets through the triple buffer, interpolation, no allocation (TASKS M5-06). */
class RenderPacketTest {
    @Test
    void interpolatesBetweenTicks() {
        RenderPacket p = new RenderPacket(4);
        float[] prev = new float[12];
        float[] curr = new float[12];
        AffineOps.identity(prev, 0);
        AffineOps.identity(curr, 0);
        curr[3] = 10; // x translation
        p.add(prev, 0, curr, 0, 7, 3, RenderPacket.FLAG_CASTS_SHADOW, 99);
        p.alpha = 0.25f;
        float[] out = new float[12];
        p.interpolatedWorld(0, out, 0);
        assertEquals(2.5f, out[3]);
        assertEquals(1, p.count());
        assertEquals(7, p.mesh[0]);
        for (int i = 0; i < 40; i++) {
            p.add(prev, 0, curr, 0, i, 0, 0, i);
        }
        assertEquals(41, p.count());
        assertEquals(39, p.entity[40]);
    }

    @Test
    void palettesAppendAndTagInstancesInTheHighFlagBits() {
        RenderPacket p = new RenderPacket(4);
        float[] world = new float[12];
        AffineOps.identity(world, 0);
        int a = p.add(world, 0, world, 0, 1, 0, RenderPacket.FLAG_STATIC | 2, 0);
        p.setHighlight(a, RenderPacket.HIGHLIGHT_SELECTED);
        float[] joints = new float[12 * 300];
        for (int k = 0; k < joints.length; k++) {
            joints[k] = k;
        }
        assertEquals(0, p.addPalette(joints, 0, 41));
        int second = p.addPalette(joints, 12, 299); // grows the array
        assertEquals(41, second);
        assertEquals(340, p.paletteCount);
        assertEquals(12, p.palettes[41 * 12]);
        assertEquals(-1, p.palette(a));
        p.setPalette(a, second);
        assertEquals(second, p.palette(a));
        assertEquals(0, p.flags[a] & RenderPacket.FLAG_STATIC, "skinned is never static");
        assertEquals(2, p.flags[a] & 2);
        assertEquals(
                RenderPacket.HIGHLIGHT_SELECTED,
                p.flags[a] >>> RenderPacket.HIGHLIGHT_SHIFT & 0xF,
                "the highlight survives");
        p.setPalette(a, -1);
        assertEquals(-1, p.palette(a));
        assertEquals(RenderPacket.FLAG_HIGHLIGHT, p.flags[a] & RenderPacket.FLAG_HIGHLIGHT);
        p.reset();
        assertEquals(0, p.paletteCount);
        p.reserve(4, 0, 0, 0, 0, 2000);
        Allocations.assertNoAllocation(
                "palettes",
                () -> {
                    p.paletteCount = 0;
                    p.addPalette(joints, 0, 300);
                });
    }

    @Test
    void gameAndMainThreadsExchangePacketsWithoutLocks() throws Exception {
        RenderPackets packets = new RenderPackets(1024);
        TripleBuffer frames = new TripleBuffer();
        float[] world = new float[12];
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread game =
                new Thread(
                        () -> {
                            for (int f = 1; f <= 20_000; f++) {
                                RenderPacket w = packets.packet(frames.writeIndex());
                                w.reset();
                                int n = 1 + f % 500;
                                for (int i = 0; i < n; i++) {
                                    world[3] = f;
                                    w.add(world, 0, world, 0, f, n, 0, i);
                                }
                                w.frame = f;
                                frames.publish();
                            }
                        });
        game.start();
        long last = 0;
        while (game.isAlive() || frames.hasUnconsumed()) {
            if (!frames.acquire()) {
                Thread.onSpinWait();
                continue;
            }
            RenderPacket r = packets.packet(frames.readIndex());
            try {
                // A torn packet would mix frames: every instance must carry the packet's frame.
                assertEquals(1 + r.frame % 500, r.count());
                for (int i = 0; i < r.count(); i++) {
                    assertEquals(r.frame, r.mesh[i]);
                    assertEquals(r.frame, r.currWorld[i * 12 + 3]);
                }
                assertEquals(true, r.frame > last);
                last = r.frame;
            } catch (Throwable t) {
                error.set(t);
                break;
            }
        }
        game.join();
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        assertEquals(20_000, last);
    }

    @Test
    void fillingAPacketAllocatesNothing() {
        RenderPacket p = new RenderPacket(2048);
        float[] world = new float[12];
        Allocations.assertNoAllocation(
                "packet fill",
                () -> {
                    p.reset();
                    for (int i = 0; i < 2000; i++) {
                        p.add(world, 0, world, 0, i, i, 0, i);
                        p.setBounds(i, 0, 0, 0, 1);
                    }
                });
    }
}
