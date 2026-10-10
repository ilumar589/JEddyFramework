package dev.jeddy.animation;

import static dev.jeddy.animation.TestRig.*;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.SceneData;
import dev.jeddy.core.job.JobSystem;
import dev.jeddy.math.AffineOps;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

/** States, blends, crossfades, evaluation and allocation of animated instances (TASKS M12-03). */
class AnimationsTest {
    private final SceneData scene =
            TestRig.scene(
                    // spine x: 0 → 1 over 1 s
                    animation(
                            "slide",
                            1,
                            move(SPINE, 1, new float[] {0, 0.5f, 0}, new float[] {1, 0.5f, 0})),
                    // spine x: 0 → 4 over 2 s
                    animation(
                            "slide2",
                            2,
                            move(SPINE, 2, new float[] {0, 0.5f, 0}, new float[] {4, 0.5f, 0})),
                    // spine x stays at 10
                    animation(
                            "hold",
                            1,
                            move(SPINE, 1, new float[] {10, 0.5f, 0}, new float[] {10, 0.5f, 0})));
    private final Skeleton skeleton = Skeleton.of(scene, 0);
    private final Clip slide = Clip.bake(scene, "slide", skeleton);
    private final Clip slide2 = Clip.bake(scene, "slide2", skeleton);
    private final Clip hold = Clip.bake(scene, "hold", skeleton);

    /** The spine's local x, read back from its model transform relative to the hips. */
    private static float spineX(Animations a, int i) {
        float[] m = a.models();
        int hips = (a.jointBase(i) + J_HIPS) * 12;
        int spine = (a.jointBase(i) + J_SPINE) * 12;
        float[] inv = new float[12];
        float[] rel = new float[12];
        AffineOps.invert(m, hips, inv, 0);
        AffineOps.mul(inv, 0, m, spine, rel, 0);
        return rel[3];
    }

    @Test
    void loopsAClipAndWrapsItsTime() {
        Animations a = new Animations(4, 64, 1);
        int i = a.add(skeleton);
        a.play(i, slide, 0);
        a.update(0.25f, null);
        assertEquals(0.25f, spineX(a, i), 1e-4f);
        assertEquals(0.25f, a.phase(i), 1e-6f);
        a.update(1.0f, null);
        assertEquals(0.25f, spineX(a, i), 1e-4f, "wrapped after a cycle");
        assertFalse(a.isFinished(i));
        assertEquals(slide, a.current(i));
    }

    @Test
    void holdsTheLastFrameOfAOneShot() {
        Animations a = new Animations(4, 64, 1);
        int i = a.add(skeleton);
        a.playOnce(i, slide, 0);
        a.update(0.6f, null);
        assertFalse(a.isFinished(i));
        a.update(0.6f, null);
        assertTrue(a.isFinished(i));
        assertEquals(1, spineX(a, i), 1e-4f);
        a.update(5, null);
        assertEquals(1, spineX(a, i), 1e-4f);
    }

    @Test
    void blendsTwoClipsWithSynchronizedTimes() {
        Animations a = new Animations(4, 64, 1);
        int i = a.add(skeleton);
        a.playBlend(i, slide, slide2, 0.5f, 0);
        // Cycle length 1.5 s: after 0.75 s both clips are halfway: x = 0.5 and 2 → 1.25.
        a.update(0.75f, null);
        assertEquals(0.5f, a.phase(i), 1e-5f);
        assertEquals(1.25f, spineX(a, i), 1e-3f);
        a.setBlendWeight(i, 1);
        a.update(0, null);
        assertEquals(2, spineX(a, i), 1e-3f);
        a.setSpeed(i, 2);
        a.update(0.25f, null); // a quarter of the 2 s cycle at double speed
        assertEquals(0.75f, a.phase(i), 1e-5f);
    }

    @Test
    void crossfadesBetweenStates() {
        Animations a = new Animations(4, 64, 1);
        int i = a.add(skeleton);
        a.play(i, hold, 0);
        a.update(0.1f, null);
        assertEquals(10, spineX(a, i), 1e-3f);
        a.play(i, slide, 0.2f);
        assertTrue(a.isFading(i));
        a.update(0.1f, null);
        // Half faded: 0.5 * 10 + 0.5 * slide(0.1) = 5.05
        assertEquals(5.05f, spineX(a, i), 1e-3f);
        a.update(0.15f, null);
        assertFalse(a.isFading(i));
        assertEquals(0.25f, spineX(a, i), 1e-3f);
    }

    @Test
    void inactiveInstancesKeepTheirPose() {
        Animations a = new Animations(4, 64, 1);
        int i = a.add(skeleton);
        a.play(i, slide, 0);
        a.update(0.3f, null);
        a.setActive(i, false);
        a.update(0.3f, null);
        assertEquals(0.3f, spineX(a, i), 1e-4f);
        assertEquals(0.3f, a.phase(i), 1e-6f);
        a.setActive(i, true);
        a.update(0.3f, null);
        assertEquals(0.6f, spineX(a, i), 1e-4f);
    }

    @Test
    void reusesSlotsAndRejectsForeignClips() {
        Animations a = new Animations(2, 6, 1);
        int i = a.add(skeleton);
        int j = a.add(skeleton);
        assertThrows(IllegalStateException.class, () -> a.add(skeleton));
        a.remove(i);
        assertEquals(1, a.count());
        assertFalse(a.isAlive(i));
        int k = a.add(skeleton);
        assertEquals(i, k, "the freed slot and joints come back");
        assertNull(a.current(k));
        assertTrue(a.isAlive(j));
        SceneData.Builder other = new SceneData.Builder();
        other.node("bone", -1, -1, -1, -1, trs(0, 0, 0, 0, 0, 0, 1, 1));
        float[] ibm = new float[16];
        ibm[0] = ibm[5] = ibm[10] = ibm[15] = 1;
        other.skin(new SceneData.Skin(-1, new int[] {0}, ibm));
        other.animation(animation("x", 1, move(0, 1, new float[3], new float[3])));
        SceneData o = other.build();
        Clip foreign = Clip.bake(o, "x", Skeleton.of(o, 0));
        assertThrows(IllegalArgumentException.class, () -> a.play(k, foreign, 0));
    }

    @Test
    void parallelUpdatesMatchTheCallingThread() {
        int n = 64;
        Animations serial = new Animations(n, n * 3, 1);
        try (JobSystem jobs = new JobSystem(2)) {
            Animations parallel = new Animations(n, n * 3, jobs.threadSlots());
            for (int k = 0; k < n; k++) {
                for (Animations a : new Animations[] {serial, parallel}) {
                    int i = a.add(skeleton);
                    a.playBlend(i, slide, slide2, (k % 5) / 4f, 0);
                    a.setSpeed(i, 0.5f + k / 64f);
                }
            }
            for (int f = 0; f < 10; f++) {
                serial.update(1 / 30f, null);
                parallel.update(1 / 30f, jobs);
            }
            assertArrayEquals(serial.palettes(), parallel.palettes(), 1e-6f);
            assertArrayEquals(serial.bounds(), parallel.bounds(), 1e-6f);
        }
    }

    @Test
    void updatesAndStateChangesAllocateNothing() {
        Animations a = new Animations(16, 64, 1);
        int[] ids = new int[8];
        for (int k = 0; k < ids.length; k++) {
            ids[k] = a.add(skeleton);
            a.playBlend(ids[k], slide, slide2, 0.3f, 0);
        }
        int[] frame = {0};
        Allocations.assertNoAllocation(
                "Animations.update",
                () -> {
                    int f = frame[0]++;
                    if (f % 50 == 0) {
                        a.play(ids[f % ids.length], (f & 64) == 0 ? hold : slide, 0.2f);
                    }
                    a.update(1 / 60f, null);
                });
        assertEquals(8, Arrays.stream(ids).filter(a::isAlive).count());
    }
}
