package dev.jeddy.animation;

import dev.jeddy.core.job.JobKernel;
import dev.jeddy.core.job.JobSystem;
import dev.jeddy.math.AffineOps;

/**
 * Animated skeleton instances (TASKS M12-03, DESIGN §13.4): what each plays, and the poses that
 * {@link #update} evaluates for them every frame.
 *
 * <p>An instance plays a <b>state</b>: one clip, or a blend of two clips whose normalized times
 * stay in sync (a walk and a run, weighted by speed), at a playback speed, looping or holding its
 * last frame. Switching state crossfades from the previous one over a given time. Per frame and in
 * parallel over instances, {@link #update} advances the states, samples and blends up to four clip
 * samples per joint, and writes each joint's model transform (relative to the skeleton's scene
 * root), its skinning matrix ({@code model × inverseBind}, the GPU palette) and a model-space
 * bounding sphere of the joints. Inactive instances keep their last pose and cost nothing.
 *
 * <p>Instances are slot indices. Their joints occupy a range of the flat {@link #models()} and
 * {@link #palettes()} arrays (12 floats per joint, row-major 3×4) starting at joint {@link
 * #jointBase}. Everything is allocated up front: adding and removing instances of skeletons no
 * larger than the first ones, playing and updating allocate nothing.
 *
 * <p>Thread ownership: the game thread; {@link #update} borrows job workers.
 */
public final class Animations {
    private static final int GRAIN = 8;

    private final int capacity;
    private final int jointCapacity;
    private final Skeleton[] skeleton;
    private final int[] jointBase;
    private final int[] jointRange;
    private final boolean[] alive;
    private final boolean[] active;
    private int highWater;
    private int jointsUsed;
    private int liveCount;

    // The playing state (cur) and the one fading out (prev).
    private final Clip[] curA;
    private final Clip[] curB;
    private final float[] curPhase;
    private final float[] curWeight;
    private final float[] curSpeed;
    private final boolean[] curLoop;
    private final Clip[] prevA;
    private final Clip[] prevB;
    private final float[] prevPhase;
    private final float[] prevWeight;
    private final float[] prevSpeed;
    private final boolean[] prevLoop;
    private final float[] fadeElapsed;
    private final float[] fadeDuration;

    private final float[] models;
    private final float[] palettes;
    private final float[] bounds;
    private final int[] version;

    private float[][] pose;
    private final Contributions[] contributions;
    private final float[][] local;
    private float dt;
    private final JobKernel kernel = this::updateRange;

    /**
     * Creates room for {@code maxInstances} instances with {@code maxJoints} joints in all.
     *
     * @param threadSlots per-thread scratch slots: {@code JobSystem.threadSlots()} of the job
     *     system passed to {@link #update}, or 1 to update on the calling thread only
     */
    public Animations(int maxInstances, int maxJoints, int threadSlots) {
        capacity = maxInstances;
        jointCapacity = maxJoints;
        skeleton = new Skeleton[maxInstances];
        jointBase = new int[maxInstances];
        jointRange = new int[maxInstances];
        alive = new boolean[maxInstances];
        active = new boolean[maxInstances];
        curA = new Clip[maxInstances];
        curB = new Clip[maxInstances];
        curPhase = new float[maxInstances];
        curWeight = new float[maxInstances];
        curSpeed = new float[maxInstances];
        curLoop = new boolean[maxInstances];
        prevA = new Clip[maxInstances];
        prevB = new Clip[maxInstances];
        prevPhase = new float[maxInstances];
        prevWeight = new float[maxInstances];
        prevSpeed = new float[maxInstances];
        prevLoop = new boolean[maxInstances];
        fadeElapsed = new float[maxInstances];
        fadeDuration = new float[maxInstances];
        models = new float[maxJoints * AffineOps.STRIDE];
        palettes = new float[maxJoints * AffineOps.STRIDE];
        bounds = new float[maxInstances * 4];
        version = new int[maxInstances];
        int slots = Math.max(1, threadSlots);
        pose = new float[slots][0];
        contributions = new Contributions[slots];
        for (int t = 0; t < slots; t++) {
            contributions[t] = new Contributions();
        }
        local = new float[slots][AffineOps.STRIDE * 2];
    }

    // Instances ----------------------------------------------------------------------------------

    /**
     * Adds an instance of {@code s} in its bind pose (no state plays until {@link #play}). A slot
     * freed by {@link #remove} is reused when its joint range fits.
     *
     * @return the instance
     * @throws IllegalStateException when instances or joints run out
     */
    public int add(Skeleton s) {
        int n = s.jointCount();
        int slot = -1;
        for (int i = 0; i < highWater; i++) {
            if (!alive[i] && jointRange[i] >= n) {
                slot = i; // reuse the freed joint range
                break;
            }
        }
        if (slot < 0) {
            if (highWater == capacity) {
                throw new IllegalStateException("more than " + capacity + " animated instances");
            }
            if (jointsUsed + n > jointCapacity) {
                throw new IllegalStateException("more than " + jointCapacity + " animated joints");
            }
            slot = highWater++;
            jointBase[slot] = jointsUsed;
            jointRange[slot] = n;
            jointsUsed += n;
        }
        for (int t = 0; t < pose.length; t++) {
            if (pose[t].length < n * Skeleton.TRS) {
                pose[t] = new float[n * Skeleton.TRS];
            }
        }
        skeleton[slot] = s;
        alive[slot] = true;
        active[slot] = true;
        curA[slot] = null;
        curB[slot] = null;
        prevA[slot] = null;
        prevB[slot] = null;
        fadeDuration[slot] = 0;
        liveCount++;
        version[slot]++;
        evaluate(slot, 0);
        return slot;
    }

    /** Removes an instance; its slot and joint range are reused by a later {@link #add}. */
    public void remove(int instance) {
        check(instance);
        alive[instance] = false;
        curA[instance] = null;
        curB[instance] = null;
        prevA[instance] = null;
        prevB[instance] = null;
        liveCount--;
        version[instance]++;
    }

    /** Returns the most instances this set holds ({@code maxInstances}). */
    public int capacity() {
        return capacity;
    }

    /** Returns the number of live instances. */
    public int count() {
        return liveCount;
    }

    /** Returns whether {@code instance} is live. */
    public boolean isAlive(int instance) {
        return instance >= 0 && instance < highWater && alive[instance];
    }

    /** Returns the skeleton of an instance. */
    public Skeleton skeleton(int instance) {
        return skeleton[instance];
    }

    /**
     * Freezes ({@code false}) or resumes an instance: inactive instances (hidden, far away) keep
     * their last pose and cost nothing in {@link #update}.
     */
    public void setActive(int instance, boolean on) {
        check(instance);
        active[instance] = on;
    }

    /** Returns whether an instance is active (see {@link #setActive}). */
    public boolean isActive(int instance) {
        return active[instance];
    }

    // States -------------------------------------------------------------------------------------

    /** Plays {@code clip} looping at normal speed, crossfading over {@code fadeSeconds}. */
    public void play(int instance, Clip clip, float fadeSeconds) {
        play(instance, clip, null, 0, 1, true, fadeSeconds);
    }

    /**
     * Plays {@code clip} once and holds its last frame (attacks, hit reactions, death), crossfading
     * over {@code fadeSeconds}; {@link #isFinished} turns true at the end.
     */
    public void playOnce(int instance, Clip clip, float fadeSeconds) {
        play(instance, clip, null, 0, 1, false, fadeSeconds);
    }

    /**
     * Starts a new state, crossfading from the current one over {@code fadeSeconds} (0 cuts). If a
     * crossfade is still running, the state it fades out is dropped.
     *
     * @param a the clip, or the first clip of a blend
     * @param b the second clip of a blend (its normalized time follows {@code a}'s), or {@code
     *     null}
     * @param weight the blend weight of {@code b} in [0, 1]
     * @param speed playback speed (1 = authored)
     * @param loop whether the state loops; otherwise it holds its last frame
     */
    public void play(
            int instance,
            Clip a,
            Clip b,
            float weight,
            float speed,
            boolean loop,
            float fadeSeconds) {
        check(instance);
        long layout = skeleton[instance].layout();
        if (a.layout() != layout || (b != null && b.layout() != layout)) {
            throw new IllegalArgumentException("clip baked for another skeleton layout");
        }
        if (fadeSeconds > 0 && curA[instance] != null) {
            prevA[instance] = curA[instance];
            prevB[instance] = curB[instance];
            prevPhase[instance] = curPhase[instance];
            prevWeight[instance] = curWeight[instance];
            prevSpeed[instance] = curSpeed[instance];
            prevLoop[instance] = curLoop[instance];
            fadeElapsed[instance] = 0;
            fadeDuration[instance] = fadeSeconds;
        } else {
            prevA[instance] = null;
            prevB[instance] = null;
            fadeDuration[instance] = 0;
        }
        curA[instance] = a;
        curB[instance] = b;
        curPhase[instance] = 0;
        curWeight[instance] = clamp01(weight);
        curSpeed[instance] = speed;
        curLoop[instance] = loop;
    }

    /** Plays a looping blend of {@code a} and {@code b} (weight of {@code b}), times in sync. */
    public void playBlend(int instance, Clip a, Clip b, float weight, float fadeSeconds) {
        play(instance, a, b, weight, 1, true, fadeSeconds);
    }

    /** Changes the blend weight of the current state's second clip, in [0, 1]. */
    public void setBlendWeight(int instance, float weight) {
        check(instance);
        curWeight[instance] = clamp01(weight);
    }

    /** Changes the playback speed of the current state. */
    public void setSpeed(int instance, float speed) {
        check(instance);
        curSpeed[instance] = speed;
    }

    /** Returns the current state's first clip, or {@code null} before any {@link #play}. */
    public Clip current(int instance) {
        return curA[instance];
    }

    /** Returns the current state's second clip, or {@code null}. */
    public Clip currentBlend(int instance) {
        return curB[instance];
    }

    /** Returns the current state's normalized time: 0 at the start, 1 at the end of a cycle. */
    public float phase(int instance) {
        return curPhase[instance];
    }

    /** Returns {@code true} once a non-looping state has reached its end. */
    public boolean isFinished(int instance) {
        return !curLoop[instance] && curA[instance] != null && curPhase[instance] >= 1;
    }

    /** Returns whether a crossfade is running. */
    public boolean isFading(int instance) {
        return prevA[instance] != null;
    }

    // Outputs ------------------------------------------------------------------------------------

    /** Returns the first joint of an instance in {@link #models()} and {@link #palettes()}. */
    public int jointBase(int instance) {
        return jointBase[instance];
    }

    /** Returns the number of joints of an instance. */
    public int jointCount(int instance) {
        return skeleton[instance].jointCount();
    }

    /**
     * Joint model transforms relative to the skeleton's scene root: 12 floats (row-major 3×4) per
     * joint, joint {@code j} of instance {@code i} at {@code (jointBase(i) + j) * 12}.
     */
    public float[] models() {
        return models;
    }

    /**
     * Skinning matrices ({@code model × inverseBind}), laid out like {@link #models()}: what the
     * GPU blends for a skinned vertex.
     */
    public float[] palettes() {
        return palettes;
    }

    /**
     * Model-space bounding spheres of the joints: center and radius at {@code instance * 4}. Skin
     * reaches beyond its joints, so callers add a margin (a mesh's own radius).
     */
    public float[] bounds() {
        return bounds;
    }

    /** Returns a counter bumped whenever slot {@code instance} is added or removed. */
    public int version(int instance) {
        return version[instance];
    }

    // Evaluation ---------------------------------------------------------------------------------

    /**
     * Advances every active instance by {@code dt} seconds and evaluates its pose, in parallel on
     * {@code jobs} (or the calling thread if {@code null}). Allocates nothing.
     */
    public void update(float dt, JobSystem jobs) {
        this.dt = dt;
        if (jobs != null && highWater > GRAIN) {
            jobs.parallelFor(kernel, highWater, GRAIN);
        } else {
            updateRange(0, highWater, 0);
        }
    }

    private void updateRange(int start, int end, int thread) {
        float step = dt;
        for (int i = start; i < end; i++) {
            if (!alive[i] || !active[i]) {
                continue;
            }
            if (curA[i] != null) {
                curPhase[i] =
                        advance(
                                curA[i],
                                curB[i],
                                curWeight[i],
                                curSpeed[i],
                                curLoop[i],
                                curPhase[i],
                                step);
            }
            if (prevA[i] != null) {
                prevPhase[i] =
                        advance(
                                prevA[i],
                                prevB[i],
                                prevWeight[i],
                                prevSpeed[i],
                                prevLoop[i],
                                prevPhase[i],
                                step);
                fadeElapsed[i] += step;
                if (fadeElapsed[i] >= fadeDuration[i]) {
                    prevA[i] = null;
                    prevB[i] = null;
                }
            }
            evaluate(i, thread);
        }
    }

    /** The next normalized time of a state after {@code dt} seconds. */
    private static float advance(
            Clip a, Clip b, float weight, float speed, boolean loop, float phase, float dt) {
        float length = a.duration();
        if (b != null) {
            length += (b.duration() - length) * weight;
        }
        if (length <= 0) {
            return 1;
        }
        float p = phase + dt * speed / length;
        if (loop) {
            p -= (float) Math.floor(p);
        } else if (p > 1) {
            p = 1;
        } else if (p < 0) {
            p = 0;
        }
        return p;
    }

    /** Samples and blends the instance's states, then writes models, palettes and bounds. */
    private void evaluate(int i, int thread) {
        Skeleton s = skeleton[i];
        int n = s.jointCount();
        float[] p = pose[thread];
        Clip ca = curA[i];
        if (ca == null) {
            System.arraycopy(s.bindPose(), 0, p, 0, n * Skeleton.TRS);
        } else {
            Clip cb = curB[i];
            Clip pa = prevA[i];
            Clip pb = prevB[i];
            float fade = pa == null ? 1 : Math.min(1, fadeElapsed[i] / fadeDuration[i]);
            float cw = cb == null ? 0 : curWeight[i];
            float pw = pb == null ? 0 : prevWeight[i];
            // Up to four weighted clip samples; frame positions are the same for every joint.
            Contributions c = contributions[thread];
            c.count = 0;
            c.add(ca, curPhase[i] * ca.duration(), (1 - cw) * fade);
            if (cb != null) {
                c.add(cb, curPhase[i] * cb.duration(), cw * fade);
            }
            if (pa != null) {
                c.add(pa, prevPhase[i] * pa.duration(), (1 - pw) * (1 - fade));
                if (pb != null) {
                    c.add(pb, prevPhase[i] * pb.duration(), pw * (1 - fade));
                }
            }
            blend(c, n, p, s.bindPose());
        }
        writeModels(i, s, p, thread);
    }

    /** Clip samples of one instance: frames, the two frame offsets, the lerp factor, a weight. */
    private static final class Contributions {
        final float[][] frames = new float[4][];
        final int[] first = new int[4];
        final int[] second = new int[4];
        final float[] alpha = new float[4];
        final float[] weight = new float[4];
        int count;

        void add(Clip clip, float time, float w) {
            if (w <= 0) {
                return;
            }
            int frameCount = clip.frameCount();
            float x = time / clip.frameStep();
            if (!(x > 0)) {
                x = 0;
            }
            int f0 = (int) x;
            float a = x - f0;
            if (f0 >= frameCount - 1) {
                f0 = frameCount - 2;
                a = 1;
            }
            int k = count++;
            int frameFloats = clip.jointCount() * Skeleton.TRS;
            frames[k] = clip.frames;
            first[k] = f0 * frameFloats;
            second[k] = first[k] + frameFloats;
            alpha[k] = a;
            weight[k] = w;
        }
    }

    /** The weighted sum of the contributions per joint (nlerp for rotations) into {@code p}. */
    private static void blend(Contributions c, int n, float[] p, float[] bind) {
        float[] f0 = c.frames[0];
        int a0 = c.first[0];
        int b0 = c.second[0];
        float t0 = c.alpha[0];
        float w0 = c.weight[0];
        int count = c.count;
        for (int j = 0; j < n; j++) {
            int o = j * Skeleton.TRS;
            int x = a0 + o;
            int y = b0 + o;
            for (int k = 0; k < Skeleton.TRS; k++) {
                float v = f0[x + k];
                p[o + k] = (v + (f0[y + k] - v) * t0) * w0;
            }
            for (int m = 1; m < count; m++) {
                float[] f = c.frames[m];
                int xm = c.first[m] + o;
                int ym = c.second[m] + o;
                float t = c.alpha[m];
                float w = c.weight[m];
                float qx = f[xm + 3] + (f[ym + 3] - f[xm + 3]) * t;
                float qy = f[xm + 4] + (f[ym + 4] - f[xm + 4]) * t;
                float qz = f[xm + 5] + (f[ym + 5] - f[xm + 5]) * t;
                float qw = f[xm + 6] + (f[ym + 6] - f[xm + 6]) * t;
                float d = p[o + 3] * qx + p[o + 4] * qy + p[o + 5] * qz + p[o + 6] * qw;
                float wq = d < 0 ? -w : w;
                p[o + 3] += qx * wq;
                p[o + 4] += qy * wq;
                p[o + 5] += qz * wq;
                p[o + 6] += qw * wq;
                for (int k = 0; k < 3; k++) {
                    float v = f[xm + k];
                    p[o + k] += (v + (f[ym + k] - v) * t) * w;
                    float u = f[xm + 7 + k];
                    p[o + 7 + k] += (u + (f[ym + 7 + k] - u) * t) * w;
                }
            }
            float len2 =
                    p[o + 3] * p[o + 3]
                            + p[o + 4] * p[o + 4]
                            + p[o + 5] * p[o + 5]
                            + p[o + 6] * p[o + 6];
            if (len2 > 1e-24f) {
                float inv = (float) (1 / Math.sqrt(len2));
                p[o + 3] *= inv;
                p[o + 4] *= inv;
                p[o + 5] *= inv;
                p[o + 6] *= inv;
            } else {
                System.arraycopy(bind, o + 3, p, o + 3, 4);
            }
        }
    }

    private void writeModels(int i, Skeleton s, float[] p, int thread) {
        int n = s.jointCount();
        int[] order = s.order();
        int[] parents = s.parents();
        float[] pre = s.preTransforms();
        float[] ibm = s.inverseBinds();
        float[] m = models;
        float[] pal = palettes;
        float[] tmp = local[thread];
        int base = jointBase[i];
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        for (int k = 0; k < n; k++) {
            int j = order[k];
            int o = j * Skeleton.TRS;
            int mo = (base + j) * AffineOps.STRIDE;
            int parent = parents[j];
            if (parent < 0 || s.hasPre(j)) {
                // Rare: root joints and joints below non-joint nodes take the general path.
                AffineOps.composeTRS(
                        p[o], p[o + 1], p[o + 2], p[o + 3], p[o + 4], p[o + 5], p[o + 6], p[o + 7],
                        p[o + 8], p[o + 9], tmp, 0);
                if (s.hasPre(j)) {
                    AffineOps.mul(pre, j * AffineOps.STRIDE, tmp, 0, tmp, AffineOps.STRIDE);
                    System.arraycopy(tmp, AffineOps.STRIDE, tmp, 0, AffineOps.STRIDE);
                }
                if (parent < 0) {
                    System.arraycopy(tmp, 0, m, mo, AffineOps.STRIDE);
                } else {
                    AffineOps.mul(m, (base + parent) * AffineOps.STRIDE, tmp, 0, m, mo);
                }
            } else {
                // model = parent × translation × rotation × scale, with the local matrix in
                // registers.
                float qx = p[o + 3], qy = p[o + 4], qz = p[o + 5], qw = p[o + 6];
                float sx = p[o + 7], sy = p[o + 8], sz = p[o + 9];
                float xx = qx * qx, yy = qy * qy, zz = qz * qz;
                float xy = qx * qy, xz = qx * qz, yz = qy * qz;
                float wx = qw * qx, wy = qw * qy, wz = qw * qz;
                float l00 = (1 - 2 * (yy + zz)) * sx;
                float l01 = 2 * (xy - wz) * sy;
                float l02 = 2 * (xz + wy) * sz;
                float l10 = 2 * (xy + wz) * sx;
                float l11 = (1 - 2 * (xx + zz)) * sy;
                float l12 = 2 * (yz - wx) * sz;
                float l20 = 2 * (xz - wy) * sx;
                float l21 = 2 * (yz + wx) * sy;
                float l22 = (1 - 2 * (xx + yy)) * sz;
                float tx = p[o], ty = p[o + 1], tz = p[o + 2];
                int po = (base + parent) * AffineOps.STRIDE;
                for (int r = 0; r < 3; r++) {
                    float a0 = m[po + r * 4];
                    float a1 = m[po + r * 4 + 1];
                    float a2 = m[po + r * 4 + 2];
                    float a3 = m[po + r * 4 + 3];
                    m[mo + r * 4] = a0 * l00 + a1 * l10 + a2 * l20;
                    m[mo + r * 4 + 1] = a0 * l01 + a1 * l11 + a2 * l21;
                    m[mo + r * 4 + 2] = a0 * l02 + a1 * l12 + a2 * l22;
                    m[mo + r * 4 + 3] = a0 * tx + a1 * ty + a2 * tz + a3;
                }
            }
            int io = j * AffineOps.STRIDE;
            float i00 = ibm[io], i01 = ibm[io + 1], i02 = ibm[io + 2], i03 = ibm[io + 3];
            float i10 = ibm[io + 4], i11 = ibm[io + 5], i12 = ibm[io + 6], i13 = ibm[io + 7];
            float i20 = ibm[io + 8], i21 = ibm[io + 9], i22 = ibm[io + 10], i23 = ibm[io + 11];
            for (int r = 0; r < 3; r++) {
                float a0 = m[mo + r * 4];
                float a1 = m[mo + r * 4 + 1];
                float a2 = m[mo + r * 4 + 2];
                float a3 = m[mo + r * 4 + 3];
                pal[mo + r * 4] = a0 * i00 + a1 * i10 + a2 * i20;
                pal[mo + r * 4 + 1] = a0 * i01 + a1 * i11 + a2 * i21;
                pal[mo + r * 4 + 2] = a0 * i02 + a1 * i12 + a2 * i22;
                pal[mo + r * 4 + 3] = a0 * i03 + a1 * i13 + a2 * i23 + a3;
            }
            float x = m[mo + 3];
            float y = m[mo + 7];
            float z = m[mo + 11];
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        float hx = (maxX - minX) * 0.5f;
        float hy = (maxY - minY) * 0.5f;
        float hz = (maxZ - minZ) * 0.5f;
        int b = i * 4;
        bounds[b] = minX + hx;
        bounds[b + 1] = minY + hy;
        bounds[b + 2] = minZ + hz;
        bounds[b + 3] = (float) Math.sqrt(hx * hx + hy * hy + hz * hz);
    }

    private void check(int instance) {
        if (instance < 0 || instance >= highWater || !alive[instance]) {
            throw new IllegalArgumentException("no animated instance " + instance);
        }
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    /** Returns the joints in use (the high-water mark of the joint ranges). */
    public int jointsUsed() {
        return jointsUsed;
    }
}
