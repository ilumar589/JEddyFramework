package dev.jeddy.bench;

import dev.jeddy.animation.Animations;
import dev.jeddy.animation.Clip;
import dev.jeddy.animation.Skeleton;
import dev.jeddy.asset.format.SceneData;
import dev.jeddy.core.job.JobSystem;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * One frame of {@link Animations#update} for 300 characters with a 41-joint rig like KayKit's
 * (TASKS M12-03, AC ≤ 1 ms with the job system): every character blends a walk and a run, and a
 * third of them crossfade into a one-shot at any time, so most joints take two to four samples.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(1)
public class AnimationBench {
    private static final int CHARACTERS = 300;
    private static final int JOINTS = 41;

    @Param({"false", "true"})
    private boolean parallel;

    private JobSystem jobs;
    private Animations animations;
    private Clip walk;
    private Clip run;
    private Clip attack;
    private int[] ids;
    private int frame;

    /** A 41-joint tree (spine and four limbs) with three clips animating every joint. */
    @Setup(Level.Trial)
    public void setup() {
        SceneData.Builder b = new SceneData.Builder();
        int[] joints = new int[JOINTS];
        for (int j = 0; j < JOINTS; j++) {
            int parent = j == 0 ? -1 : j < 5 ? j - 1 : (j - 5) % 4 == 0 ? 3 : j - 1;
            joints[j] = b.node("j" + j, parent, -1, -1, -1, trs(0, 0.2f, 0, 0, 0, 0, 1));
        }
        float[] ibm = new float[JOINTS * 16];
        for (int j = 0; j < JOINTS; j++) {
            ibm[j * 16] = ibm[j * 16 + 5] = ibm[j * 16 + 10] = ibm[j * 16 + 15] = 1;
        }
        b.skin(new SceneData.Skin(-1, joints, ibm));
        Random r = new Random(5);
        b.animation(clip("walk", 1.0f, r));
        b.animation(clip("run", 0.7f, r));
        b.animation(clip("attack", 1.2f, r));
        SceneData scene = b.build();
        Skeleton skeleton = Skeleton.of(scene, 0);
        walk = Clip.bake(scene, "walk", skeleton);
        run = Clip.bake(scene, "run", skeleton);
        attack = Clip.bake(scene, "attack", skeleton);
        jobs = parallel ? new JobSystem(JobSystem.defaultWorkerCount()) : null;
        animations =
                new Animations(
                        CHARACTERS, CHARACTERS * JOINTS, jobs == null ? 1 : jobs.threadSlots());
        ids = new int[CHARACTERS];
        for (int i = 0; i < CHARACTERS; i++) {
            ids[i] = animations.add(skeleton);
            animations.playBlend(ids[i], walk, run, r.nextFloat(), 0);
        }
    }

    private static float[] trs(float x, float y, float z, float qx, float qy, float qz, float qw) {
        return new float[] {x, y, z, qx, qy, qz, qw, 1, 1, 1};
    }

    /** Translation, rotation and scale keys at 30 Hz for every joint. */
    private static SceneData.Animation clip(String name, float duration, Random r) {
        int keys = Math.round(duration * 30) + 1;
        float[] times = new float[keys];
        for (int k = 0; k < keys; k++) {
            times[k] = duration * k / (keys - 1);
        }
        List<SceneData.Channel> channels = new ArrayList<>();
        for (int j = 0; j < JOINTS; j++) {
            float[] t = new float[keys * 3];
            float[] q = new float[keys * 4];
            float[] s = new float[keys * 3];
            float phase = r.nextFloat() * 6.28f;
            for (int k = 0; k < keys; k++) {
                float a = (float) Math.sin(phase + 6.28f * k / (keys - 1)) * 0.4f;
                t[k * 3 + 1] = 0.2f;
                q[k * 4] = (float) Math.sin(a / 2);
                q[k * 4 + 3] = (float) Math.cos(a / 2);
                s[k * 3] = s[k * 3 + 1] = s[k * 3 + 2] = 1;
            }
            channels.add(channel(j, SceneData.PATH_TRANSLATION, 3, times, t));
            channels.add(channel(j, SceneData.PATH_ROTATION, 4, times, q));
            channels.add(channel(j, SceneData.PATH_SCALE, 3, times, s));
        }
        return new SceneData.Animation(name, duration, channels);
    }

    private static SceneData.Channel channel(
            int node, int path, int components, float[] times, float[] values) {
        return new SceneData.Channel(
                node, path, SceneData.INTERPOLATION_LINEAR, components, times, values);
    }

    /** Closes the job system. */
    @TearDown(Level.Trial)
    public void tearDown() {
        if (jobs != null) {
            jobs.close();
        }
    }

    /** One 60 Hz frame; one character starts a crossfade into or out of an attack every frame. */
    @Benchmark
    public float update300() {
        int f = frame++;
        int i = ids[f % CHARACTERS];
        if ((f / CHARACTERS & 1) == 0) {
            animations.play(i, attack, null, 0, 1, false, 0.25f);
        } else {
            animations.play(i, walk, run, 0.5f, 1, true, 0.25f);
        }
        animations.update(1 / 60f, jobs);
        return animations.palettes()[f % 1000];
    }
}
