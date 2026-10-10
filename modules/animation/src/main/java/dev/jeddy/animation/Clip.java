package dev.jeddy.animation;

import dev.jeddy.asset.format.SceneData;
import dev.jeddy.core.id.StrId;

import java.util.List;

/**
 * An animation clip baked for a skeleton layout (TASKS M12-02, DESIGN §13.4, D79): the clip's glTF
 * channels resampled at evenly spaced frames from 0 to its duration, each frame a block of
 * joint-local transforms (10 floats per joint: translation, rotation quaternion {@code xyzw},
 * scale) in skin order. Joints the clip does not animate keep the bind pose. Sampling at any time
 * is two frames and a lerp per joint.
 *
 * <p>Channels bind to joints by node name, so the library may be another scene than the skeleton's:
 * an animation library holding a rig and its clips serves every character with that rig. Unnamed
 * nodes bind by node index (a clip baked from the skeleton's own scene).
 *
 * <p>Thread ownership: immutable after {@link #bake}; any thread.
 */
public final class Clip {
    /** The default sampling rate in frames per second. */
    public static final float DEFAULT_RATE = 30;

    private final String name;
    private final float duration;
    private final float step;
    private final int frameCount;
    private final int jointCount;
    private final long layout;
    final float[] frames;

    private Clip(String name, float duration, int frameCount, int jointCount, long layout) {
        this.name = name;
        this.duration = duration;
        this.frameCount = frameCount;
        this.jointCount = jointCount;
        this.layout = layout;
        this.step = duration > 0 ? duration / (frameCount - 1) : 1;
        this.frames = new float[frameCount * jointCount * Skeleton.TRS];
    }

    /** Bakes the animation called {@code name} of {@code library} at {@link #DEFAULT_RATE}. */
    public static Clip bake(SceneData library, String name, Skeleton skeleton) {
        List<SceneData.Animation> all = library.animations();
        for (int a = 0; a < all.size(); a++) {
            if (all.get(a).name().equals(name)) {
                return bake(library, a, skeleton, DEFAULT_RATE, -1);
            }
        }
        throw new IllegalArgumentException("no animation called " + name);
    }

    /**
     * Bakes animation {@code animation} of {@code library} for {@code skeleton}.
     *
     * @param rate frames per second (the frames are spread evenly over the duration)
     * @param lockJoint a joint whose local X and Z translation stay at the first frame's (in-place
     *     playback of a clip that walks its root; Y-up rigs), or -1
     */
    public static Clip bake(
            SceneData library, int animation, Skeleton skeleton, float rate, int lockJoint) {
        if (!(rate > 0)) {
            throw new IllegalArgumentException("rate must be positive: " + rate);
        }
        SceneData.Animation anim = library.animations().get(animation);
        int joints = skeleton.jointCount();
        float duration = Math.max(0, anim.duration());
        int frameCount = Math.max(2, Math.round(duration * rate) + 1);
        Clip clip = new Clip(anim.name(), duration, frameCount, joints, skeleton.layout());
        float[] f = clip.frames;
        float[] bind = skeleton.bindPose();
        int frameFloats = joints * Skeleton.TRS;
        for (int i = 0; i < frameCount; i++) {
            System.arraycopy(bind, 0, f, i * frameFloats, frameFloats);
        }
        float[] value = new float[4];
        for (SceneData.Channel c : anim.channels()) {
            int joint = bindJoint(library, c.node(), skeleton);
            if (joint < 0 || c.path() == SceneData.PATH_WEIGHTS || c.times().length == 0) {
                continue;
            }
            int offset =
                    switch (c.path()) {
                        case SceneData.PATH_TRANSLATION -> 0;
                        case SceneData.PATH_ROTATION -> 3;
                        default -> 7;
                    };
            int components = c.path() == SceneData.PATH_ROTATION ? 4 : 3;
            if (c.components() != components) {
                throw new IllegalArgumentException(
                        anim.name() + ": channel with " + c.components() + " components");
            }
            for (int i = 0; i < frameCount; i++) {
                float t = i == frameCount - 1 ? duration : i * clip.step;
                evaluate(c, t, value);
                if (components == 4) {
                    normalize(value);
                }
                System.arraycopy(
                        value, 0, f, i * frameFloats + joint * Skeleton.TRS + offset, components);
            }
        }
        for (int j = 0; j < joints; j++) {
            for (int i = 1; i < frameCount; i++) {
                int q = i * frameFloats + j * Skeleton.TRS + 3;
                int prev = q - frameFloats;
                // Keep consecutive frames in one hemisphere, so frame lerps take the short way.
                if (f[q] * f[prev]
                                + f[q + 1] * f[prev + 1]
                                + f[q + 2] * f[prev + 2]
                                + f[q + 3] * f[prev + 3]
                        < 0) {
                    for (int k = 0; k < 4; k++) {
                        f[q + k] = -f[q + k];
                    }
                }
            }
        }
        if (lockJoint >= 0) {
            for (int i = 1; i < frameCount; i++) {
                int o = i * frameFloats + lockJoint * Skeleton.TRS;
                f[o] = f[lockJoint * Skeleton.TRS];
                f[o + 2] = f[lockJoint * Skeleton.TRS + 2];
            }
        }
        return clip;
    }

    private static int bindJoint(SceneData library, int node, Skeleton skeleton) {
        String name = library.nodeName(node);
        if (!name.isEmpty()) {
            return skeleton.find(StrId.of(name));
        }
        int j = skeleton.jointOfNode(node);
        return j >= 0 && skeleton.name(j) == StrId.NONE ? j : -1;
    }

    /** The channel's value at time {@code t} (glTF semantics: clamped at both ends). */
    private static void evaluate(SceneData.Channel c, float t, float[] out) {
        float[] times = c.times();
        float[] v = c.values();
        int n = c.components();
        boolean cubic = c.interpolation() == SceneData.INTERPOLATION_CUBIC;
        int stride = cubic ? n * 3 : n;
        int valueAt = cubic ? n : 0; // cubic keys are in-tangent, value, out-tangent
        int last = times.length - 1;
        if (t <= times[0] || last == 0) {
            System.arraycopy(v, valueAt, out, 0, n);
            return;
        }
        if (t >= times[last]) {
            System.arraycopy(v, last * stride + valueAt, out, 0, n);
            return;
        }
        int lo = 0;
        int hi = last;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (times[mid] <= t) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        float span = times[hi] - times[lo];
        float s = span > 0 ? (t - times[lo]) / span : 0;
        switch (c.interpolation()) {
            case SceneData.INTERPOLATION_STEP -> System.arraycopy(v, lo * stride, out, 0, n);
            case SceneData.INTERPOLATION_CUBIC -> {
                float s2 = s * s;
                float s3 = s2 * s;
                float h00 = 2 * s3 - 3 * s2 + 1;
                float h10 = s3 - 2 * s2 + s;
                float h01 = -2 * s3 + 3 * s2;
                float h11 = s3 - s2;
                int a = lo * stride;
                int b = hi * stride;
                for (int k = 0; k < n; k++) {
                    out[k] =
                            h00 * v[a + n + k]
                                    + h10 * span * v[a + 2 * n + k]
                                    + h01 * v[b + n + k]
                                    + h11 * span * v[b + k];
                }
            }
            default -> {
                int a = lo * stride;
                int b = hi * stride;
                if (n == 4 && c.path() == SceneData.PATH_ROTATION) {
                    slerp(v, a, v, b, s, out);
                } else {
                    for (int k = 0; k < n; k++) {
                        out[k] = v[a + k] + (v[b + k] - v[a + k]) * s;
                    }
                }
            }
        }
    }

    private static void slerp(float[] p, int po, float[] q, int qo, float s, float[] out) {
        float x0 = p[po], y0 = p[po + 1], z0 = p[po + 2], w0 = p[po + 3];
        float x1 = q[qo], y1 = q[qo + 1], z1 = q[qo + 2], w1 = q[qo + 3];
        float d = x0 * x1 + y0 * y1 + z0 * z1 + w0 * w1;
        if (d < 0) {
            d = -d;
            x1 = -x1;
            y1 = -y1;
            z1 = -z1;
            w1 = -w1;
        }
        float a;
        float b;
        if (d > 0.9995f) {
            a = 1 - s;
            b = s;
        } else {
            double theta = Math.acos(Math.min(1, d));
            double sin = Math.sin(theta);
            a = (float) (Math.sin((1 - s) * theta) / sin);
            b = (float) (Math.sin(s * theta) / sin);
        }
        out[0] = a * x0 + b * x1;
        out[1] = a * y0 + b * y1;
        out[2] = a * z0 + b * z1;
        out[3] = a * w0 + b * w1;
        normalize(out);
    }

    private static void normalize(float[] q) {
        float len = (float) Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        if (len > 0) {
            for (int k = 0; k < 4; k++) {
                q[k] /= len;
            }
        } else {
            q[0] = 0;
            q[1] = 0;
            q[2] = 0;
            q[3] = 1;
        }
    }

    /** Returns the clip's name. */
    public String name() {
        return name;
    }

    /** Returns the duration in seconds (0 for a single pose). */
    public float duration() {
        return duration;
    }

    /** Returns the number of baked frames (at least 2). */
    public int frameCount() {
        return frameCount;
    }

    /** Returns the time between frames in seconds. */
    public float frameStep() {
        return step;
    }

    /** Returns the number of joints of each frame. */
    public int jointCount() {
        return jointCount;
    }

    /** Returns the {@link Skeleton#layout} the clip was baked for. */
    public long layout() {
        return layout;
    }

    /**
     * Writes joint {@code joint}'s local transform at time {@code time} (clamped to the clip) into
     * {@code out[o..o + 10)}: frames lerped, the rotation normalized. Allocates nothing.
     */
    public void sample(int joint, float time, float[] out, int o) {
        float x = time / step;
        if (!(x > 0)) {
            x = 0;
        }
        int f0 = (int) x;
        float a = x - f0;
        if (f0 >= frameCount - 1) {
            f0 = frameCount - 2;
            a = 1;
        }
        int p = (f0 * jointCount + joint) * Skeleton.TRS;
        int q = p + jointCount * Skeleton.TRS;
        float[] f = frames;
        for (int k = 0; k < Skeleton.TRS; k++) {
            out[o + k] = f[p + k] + (f[q + k] - f[p + k]) * a;
        }
        float len =
                (float)
                        Math.sqrt(
                                out[o + 3] * out[o + 3]
                                        + out[o + 4] * out[o + 4]
                                        + out[o + 5] * out[o + 5]
                                        + out[o + 6] * out[o + 6]);
        for (int k = 3; k < 7; k++) {
            out[o + k] /= len;
        }
    }
}
