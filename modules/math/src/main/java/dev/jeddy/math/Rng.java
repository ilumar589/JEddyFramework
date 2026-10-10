package dev.jeddy.math;

/**
 * Deterministic random numbers: xoshiro256** seeded through SplitMix64 (DESIGN §8.3). The whole
 * state is four {@code long}s, so save games and replays store it exactly; {@link #jump()} gives
 * independent streams per subsystem (combat, loot, AI). Includes dice helpers for d20 rules.
 *
 * <p>Thread ownership: single owner thread; give each thread or subsystem its own stream.
 */
public final class Rng {
    private long s0;
    private long s1;
    private long s2;
    private long s3;

    /** Creates a generator from a seed. */
    public Rng(long seed) {
        setSeed(seed);
    }

    /** Re-seeds the generator. */
    public void setSeed(long seed) {
        long x = seed;
        x += 0x9E3779B97F4A7C15L;
        s0 = mix(x);
        x += 0x9E3779B97F4A7C15L;
        s1 = mix(x);
        x += 0x9E3779B97F4A7C15L;
        s2 = mix(x);
        x += 0x9E3779B97F4A7C15L;
        s3 = mix(x);
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Copies the state into {@code out[0..3]}. */
    public void saveState(long[] out) {
        out[0] = s0;
        out[1] = s1;
        out[2] = s2;
        out[3] = s3;
    }

    /** Restores a state written by {@link #saveState(long[])}. */
    public void restoreState(long[] in) {
        if ((in[0] | in[1] | in[2] | in[3]) == 0) {
            throw new IllegalArgumentException("all-zero state");
        }
        s0 = in[0];
        s1 = in[1];
        s2 = in[2];
        s3 = in[3];
    }

    /** Returns 64 random bits. */
    public long nextLong() {
        long result = Long.rotateLeft(s1 * 5, 7) * 9;
        long t = s1 << 17;
        s2 ^= s0;
        s3 ^= s1;
        s1 ^= s2;
        s0 ^= s3;
        s2 ^= t;
        s3 = Long.rotateLeft(s3, 45);
        return result;
    }

    /** Returns 32 random bits. */
    public int nextInt() {
        return (int) (nextLong() >>> 32);
    }

    /** Returns a uniform int in {@code [0, bound)} without modulo bias (Lemire's method). */
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive: " + bound);
        }
        long m = (nextLong() >>> 32) * bound;
        long low = m & 0xFFFFFFFFL;
        if (low < bound) {
            long threshold = (0x1_0000_0000L - bound) % bound;
            while (low < threshold) {
                m = (nextLong() >>> 32) * bound;
                low = m & 0xFFFFFFFFL;
            }
        }
        return (int) (m >>> 32);
    }

    /** Returns a uniform int in {@code [min, max]} (inclusive). */
    public int nextInt(int min, int max) {
        return min + nextInt(max - min + 1);
    }

    /** Returns a uniform float in {@code [0, 1)}. */
    public float nextFloat() {
        return (nextLong() >>> 40) * 0x1.0p-24f;
    }

    /** Returns a uniform double in {@code [0, 1)}. */
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    /** Returns {@code true} with probability 0.5. */
    public boolean nextBoolean() {
        return nextLong() < 0;
    }

    /** Returns a uniform float in {@code [min, max)}. */
    public float range(float min, float max) {
        return min + (max - min) * nextFloat();
    }

    /** Returns a standard normal sample (Marsaglia polar method; deterministic). */
    public double nextGaussian() {
        double u;
        double v;
        double s;
        do {
            u = nextDouble() * 2 - 1;
            v = nextDouble() * 2 - 1;
            s = u * u + v * v;
        } while (s >= 1 || s == 0);
        return u * Math.sqrt(-2 * Math.log(s) / s);
    }

    /** Rolls one die with {@code sides} faces: 1..sides. */
    public int d(int sides) {
        return 1 + nextInt(sides);
    }

    /** Rolls {@code count} dice with {@code sides} faces and adds {@code modifier} (e.g. 2d6+3). */
    public int roll(int count, int sides, int modifier) {
        int sum = modifier;
        for (int i = 0; i < count; i++) {
            sum += d(sides);
        }
        return sum;
    }

    /** Rolls a d20. */
    public int d20() {
        return d(20);
    }

    /**
     * Picks an index with probability proportional to {@code weights[i]} among the first {@code n}.
     * Returns -1 if all weights are zero.
     */
    public int pickWeighted(float[] weights, int n) {
        double total = 0;
        for (int i = 0; i < n; i++) {
            total += Math.max(0, weights[i]);
        }
        if (total <= 0) {
            return -1;
        }
        double r = nextDouble() * total;
        for (int i = 0; i < n; i++) {
            r -= Math.max(0, weights[i]);
            if (r < 0) {
                return i;
            }
        }
        return n - 1;
    }

    /**
     * Advances the state by 2^128 steps: the jumped generator and this one produce non-overlapping
     * sequences. Returns a copy at the old position, then jumps this one.
     */
    public Rng split() {
        Rng copy = new Rng(0);
        copy.s0 = s0;
        copy.s1 = s1;
        copy.s2 = s2;
        copy.s3 = s3;
        jump();
        return copy;
    }

    /** Advances the state by 2^128 calls of {@link #nextLong()}. */
    public void jump() {
        long[] jump = {
            0x180ec6d33cfd0abaL, 0xd5a61266f0c9392cL, 0xa9582618e03fc9aaL, 0x39abdc4529b1661cL
        };
        long t0 = 0;
        long t1 = 0;
        long t2 = 0;
        long t3 = 0;
        for (long j : jump) {
            for (int b = 0; b < 64; b++) {
                if ((j & (1L << b)) != 0) {
                    t0 ^= s0;
                    t1 ^= s1;
                    t2 ^= s2;
                    t3 ^= s3;
                }
                nextLong();
            }
        }
        s0 = t0;
        s1 = t1;
        s2 = t2;
        s3 = t3;
    }
}
