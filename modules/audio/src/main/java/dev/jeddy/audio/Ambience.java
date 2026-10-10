package dev.jeddy.audio;

import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * Ambient zones (DESIGN §12.3, TASKS M9-05): a looping bed per zone that fades in when the listener
 * enters (trigger volumes call {@link #enter} and {@link #exit}; overlapping entries are counted)
 * and out when it leaves, plus scheduled <b>random one-shots</b> (birds, creaks, distant howls)
 * placed around the listener at a random angle and distance, each on a temporary emitter.
 *
 * <p>Thread ownership: the game thread; call {@link #update} once per frame before {@link
 * Audio#update}. Allocates nothing after zones are defined.
 */
public final class Ambience {
    private static final int MAX_SHOTS = 16;

    private final Audio audio;
    private final SplittableRandom random;
    private int count;
    private int[] ids = new int[8];
    private SoundClip[] loops = new SoundClip[8];
    private float[] loopGain = new float[8];
    private float[] fade = new float[8];
    private SoundClip[][] shots = new SoundClip[8][];
    private float[] shotGain = new float[8];
    private float[] minInterval = new float[8];
    private float[] maxInterval = new float[8];
    private float[] minDistance = new float[8];
    private float[] maxDistance = new float[8];
    private int[] inside = new int[8];
    private int[] loopVoice = new int[8];
    private double[] nextShot = new double[8];
    private final int[] shotVoice = new int[MAX_SHOTS];
    private final int[] shotEmitter = new int[MAX_SHOTS];

    /** Creates zones on {@code audio}'s ambient bus; {@code seed} makes one-shots reproducible. */
    public Ambience(Audio audio, long seed) {
        this.audio = audio;
        this.random = new SplittableRandom(seed);
    }

    /**
     * Defines zone {@code zoneId} with a looping bed ({@code null} for none) at {@code gain},
     * fading over {@code fadeSeconds}.
     */
    public void defineZone(int zoneId, SoundClip loop, float gain, float fadeSeconds) {
        int z = find(zoneId);
        if (z < 0) {
            if (count == ids.length) {
                int cap = count * 2;
                ids = Arrays.copyOf(ids, cap);
                loops = Arrays.copyOf(loops, cap);
                loopGain = Arrays.copyOf(loopGain, cap);
                fade = Arrays.copyOf(fade, cap);
                shots = Arrays.copyOf(shots, cap);
                shotGain = Arrays.copyOf(shotGain, cap);
                minInterval = Arrays.copyOf(minInterval, cap);
                maxInterval = Arrays.copyOf(maxInterval, cap);
                minDistance = Arrays.copyOf(minDistance, cap);
                maxDistance = Arrays.copyOf(maxDistance, cap);
                inside = Arrays.copyOf(inside, cap);
                loopVoice = Arrays.copyOf(loopVoice, cap);
                nextShot = Arrays.copyOf(nextShot, cap);
            }
            z = count++;
            ids[z] = zoneId;
        }
        loops[z] = loop;
        loopGain[z] = gain;
        fade[z] = fadeSeconds;
    }

    /**
     * Gives zone {@code zoneId} random one-shots: one of {@code clips} every {@code minInterval} to
     * {@code maxInterval} seconds at {@code minDistance} to {@code maxDistance} meters from the
     * listener.
     */
    public void setOneShots(
            int zoneId,
            SoundClip[] clips,
            float gain,
            float intervalMin,
            float intervalMax,
            float distanceMin,
            float distanceMax) {
        int z = find(zoneId);
        if (z < 0) {
            throw new IllegalArgumentException("define zone " + zoneId + " first");
        }
        shots[z] = clips.clone();
        shotGain[z] = gain;
        minInterval[z] = intervalMin;
        maxInterval[z] = Math.max(intervalMin, intervalMax);
        minDistance[z] = distanceMin;
        maxDistance[z] = Math.max(distanceMin, distanceMax);
    }

    private int find(int zoneId) {
        for (int z = 0; z < count; z++) {
            if (ids[z] == zoneId) {
                return z;
            }
        }
        return -1;
    }

    /** The listener entered zone {@code zoneId} (counted: overlapping triggers are fine). */
    public void enter(int zoneId) {
        int z = find(zoneId);
        if (z < 0 || inside[z]++ > 0) {
            return;
        }
        if (loops[z] != null) {
            loopVoice[z] = audio.play(loops[z], Audio.BUS_AMBIENT, loopGain[z], 10, -1, fade[z], 0);
        }
        nextShot[z] = audio.time() + interval(z);
    }

    /** The listener left zone {@code zoneId}. */
    public void exit(int zoneId) {
        int z = find(zoneId);
        if (z < 0 || inside[z] == 0 || --inside[z] > 0) {
            return;
        }
        if (loopVoice[z] != 0) {
            audio.stop(loopVoice[z], fade[z]);
            loopVoice[z] = 0;
        }
    }

    /** Returns whether the listener is inside zone {@code zoneId}. */
    public boolean isInside(int zoneId) {
        int z = find(zoneId);
        return z >= 0 && inside[z] > 0;
    }

    private double interval(int z) {
        return minInterval[z] + random.nextDouble() * (maxInterval[z] - minInterval[z]);
    }

    /** Fires due one-shots and frees the emitters of finished ones. */
    public void update() {
        for (int i = 0; i < MAX_SHOTS; i++) {
            if (shotVoice[i] != 0 && audio.voiceState(shotVoice[i]) == Audio.VOICE_NONE) {
                audio.removeEmitter(shotEmitter[i]);
                shotVoice[i] = 0;
            }
        }
        double now = audio.time();
        for (int z = 0; z < count; z++) {
            if (inside[z] == 0 || shots[z] == null || shots[z].length == 0 || now < nextShot[z]) {
                continue;
            }
            nextShot[z] = now + interval(z);
            int slot = -1;
            for (int i = 0; i < MAX_SHOTS; i++) {
                if (shotVoice[i] == 0) {
                    slot = i;
                    break;
                }
            }
            if (slot < 0) {
                continue; // plenty of one-shots already
            }
            SoundClip c = shots[z][random.nextInt(shots[z].length)];
            double angle = random.nextDouble() * Math.PI * 2;
            float d =
                    minDistance[z]
                            + (float) random.nextDouble() * (maxDistance[z] - minDistance[z]);
            float x = audio.listenerX() + (float) Math.cos(angle) * d;
            float zz = audio.listenerZ() + (float) Math.sin(angle) * d;
            int e = audio.addEmitter(x, audio.listenerY(), zz, Math.max(1, d * 0.5f), d * 3);
            int v = audio.play(c, Audio.BUS_AMBIENT, shotGain[z], 0, 0, 0, e);
            if (v == 0) {
                audio.removeEmitter(e);
                continue;
            }
            shotVoice[slot] = v;
            shotEmitter[slot] = e;
        }
    }
}
