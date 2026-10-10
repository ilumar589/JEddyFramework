package dev.jeddy.animation;

import static dev.jeddy.animation.TestRig.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.jeddy.asset.format.SceneData;

import org.junit.jupiter.api.Test;

import java.util.List;

/** Clip baking: resampling, interpolation modes, binding by name, root lock (TASKS M12-02). */
class ClipTest {
    private final float[] out = new float[10];

    @Test
    void resamplesLinearChannelsAndKeepsUntouchedJointsInBindPose() {
        SceneData scene =
                TestRig.scene(
                        animation(
                                "move",
                                1,
                                move(SPINE, 1, new float[] {0, 0.5f, 0}, new float[] {1, 0.5f, 0}),
                                turn(HAND, 1, (float) Math.PI / 2)));
        Skeleton s = Skeleton.of(scene, 0);
        Clip c = Clip.bake(scene, 0, s, 10, -1);
        assertEquals("move", c.name());
        assertEquals(1, c.duration());
        assertEquals(11, c.frameCount());
        assertEquals(0.1f, c.frameStep(), 1e-6f);
        c.sample(J_SPINE, 0.25f, out, 0);
        assertEquals(0.25f, out[0], 1e-5f);
        assertEquals(0.5f, out[1], 1e-5f);
        // Halfway through a 90° turn: 45° about Y (slerped frames, then lerped and normalized).
        c.sample(J_HAND, 0.5f, out, 0);
        float h = (float) Math.sin(Math.PI / 8);
        assertEquals(h, out[4], 1e-4f);
        assertEquals((float) Math.cos(Math.PI / 8), out[6], 1e-4f);
        assertEquals(0.3f, out[0], 1e-6f, "bind translation kept");
        // The hips are not animated: bind pose throughout; past the end clamps to the last frame.
        c.sample(J_HIPS, 0.7f, out, 0);
        assertEquals(1, out[1], 1e-6f);
        c.sample(J_SPINE, 5, out, 0);
        assertEquals(1, out[0], 1e-6f);
    }

    @Test
    void evaluatesStepAndCubicSplineChannels() {
        SceneData.Channel step =
                new SceneData.Channel(
                        SPINE,
                        SceneData.PATH_TRANSLATION,
                        SceneData.INTERPOLATION_STEP,
                        3,
                        new float[] {0, 0.5f, 1},
                        new float[] {0, 0, 0, 2, 0, 0, 4, 0, 0});
        // Zero tangents: a smoothstep from 0 to 1 between the keys.
        SceneData.Channel cubic =
                new SceneData.Channel(
                        HIPS,
                        SceneData.PATH_TRANSLATION,
                        SceneData.INTERPOLATION_CUBIC,
                        3,
                        new float[] {0, 1},
                        new float[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0});
        SceneData scene = TestRig.scene(animation("mixed", 1, step, cubic));
        Clip c = Clip.bake(scene, 0, Skeleton.of(scene, 0), 100, -1);
        c.sample(J_SPINE, 0.49f, out, 0);
        assertEquals(0, out[0], 1e-5f);
        c.sample(J_SPINE, 0.75f, out, 0);
        assertEquals(2, out[0], 1e-5f);
        c.sample(J_HIPS, 0.25f, out, 0);
        assertEquals(3 * 0.0625f - 2 * 0.015625f, out[1], 1e-4f);
    }

    @Test
    void bindsChannelsByNameAcrossScenes() {
        // A library with the joints in another order and nothing else.
        SceneData.Builder lib = new SceneData.Builder();
        lib.node("hand", -1, -1, -1, -1, trs(0, 0, 0, 0, 0, 0, 1, 1));
        lib.node("unrelated", -1, -1, -1, -1, trs(0, 0, 0, 0, 0, 0, 1, 1));
        lib.node("spine", -1, -1, -1, -1, trs(0, 0, 0, 0, 0, 0, 1, 1));
        lib.animation(
                animation(
                        "lib",
                        2,
                        move(2, 2, new float[] {0, 0, 0}, new float[] {0, 4, 0}),
                        move(1, 2, new float[] {0, 0, 0}, new float[] {9, 9, 9})));
        Skeleton s = Skeleton.of(TestRig.scene(), 0);
        Clip c = Clip.bake(lib.build(), "lib", s);
        assertEquals(Clip.DEFAULT_RATE * 2 + 1, c.frameCount());
        c.sample(J_SPINE, 1, out, 0);
        assertEquals(2, out[1], 1e-5f);
        c.sample(J_HAND, 1, out, 0);
        assertEquals(0.3f, out[0], 1e-6f, "the unrelated channel bound nowhere");
        assertThrows(IllegalArgumentException.class, () -> Clip.bake(lib.build(), "nope", s));
    }

    @Test
    void locksARootJointsHorizontalTranslation() {
        SceneData scene =
                TestRig.scene(
                        animation(
                                "walk",
                                1,
                                move(HIPS, 1, new float[] {0, 1, 0}, new float[] {3, 2, 5})));
        Skeleton s = Skeleton.of(scene, 0);
        Clip c = Clip.bake(scene, 0, s, 10, J_HIPS);
        c.sample(J_HIPS, 1, out, 0);
        assertEquals(0, out[0], 1e-6f);
        assertEquals(2, out[1], 1e-6f, "vertical motion stays");
        assertEquals(0, out[2], 1e-6f);
    }

    @Test
    void handlesSinglePoses() {
        SceneData.Channel pose =
                new SceneData.Channel(
                        SPINE,
                        SceneData.PATH_SCALE,
                        SceneData.INTERPOLATION_LINEAR,
                        3,
                        new float[] {0},
                        new float[] {2, 2, 2});
        SceneData scene = TestRig.scene(new SceneData.Animation("pose", 0, List.of(pose)));
        Clip c = Clip.bake(scene, "pose", Skeleton.of(scene, 0));
        assertEquals(2, c.frameCount());
        c.sample(J_SPINE, 0.3f, out, 0);
        assertEquals(2, out[7], 1e-6f);
        assertEquals(1, out[6], 1e-6f);
    }
}
