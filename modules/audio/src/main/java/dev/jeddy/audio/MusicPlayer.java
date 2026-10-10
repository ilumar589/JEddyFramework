package dev.jeddy.audio;

import java.util.Arrays;

/**
 * The music state machine (DESIGN §12.3, TASKS M9-05): states (exploration, combat, dialogue, area
 * themes) each with a looping track; switching states crossfades (the old voice fades out while the
 * new one fades in); <b>stingers</b> (level up, quest done) play once over the music, which dips to
 * a duck level for their duration and ramps back.
 *
 * <p>Thread ownership: the game thread; call {@link #update} once per frame before {@link
 * Audio#update}. Allocates nothing after the states are defined.
 */
public final class MusicPlayer {
    /** Voice priority of music (survives voice stealing). */
    public static final int PRIORITY = 1_000_000;

    private final Audio audio;
    private SoundClip[] clips = new SoundClip[8];
    private float[] gains = new float[8];
    private int current = -1;
    private int voice;
    private int stinger;
    private float stingerDuck = 1;
    private float level = 1;
    private float rampSeconds = 0.3f;

    /** Creates a player on {@code audio}'s music bus. */
    public MusicPlayer(Audio audio) {
        this.audio = audio;
    }

    /** Defines state {@code s} (0, 1, 2 ...) as looping {@code clip} at {@code gain}. */
    public void define(int s, SoundClip clip, float gain) {
        if (s >= clips.length) {
            clips = Arrays.copyOf(clips, Math.max(s + 1, clips.length * 2));
            gains = Arrays.copyOf(gains, clips.length);
        }
        clips[s] = clip;
        gains[s] = gain;
    }

    /** Returns the current state, or -1. */
    public int state() {
        return current;
    }

    /** Returns the current music voice (0 when silent). */
    public int voice() {
        return voice;
    }

    /** Switches to state {@code s}, crossfading over {@code seconds}; same state: nothing. */
    public void play(int s, float seconds) {
        if (s == current && audio.voiceState(voice) != Audio.VOICE_NONE) {
            return;
        }
        if (voice != 0) {
            audio.stop(voice, seconds);
        }
        current = s;
        voice = audio.play(clips[s], Audio.BUS_MUSIC, gains[s] * level, PRIORITY, -1, seconds, 0);
    }

    /** Fades the music out over {@code seconds}. */
    public void stop(float seconds) {
        if (voice != 0) {
            audio.stop(voice, seconds);
        }
        voice = 0;
        current = -1;
    }

    /**
     * Plays {@code clip} once over the music at {@code gain}, ducking the music to {@code duck}
     * while it plays; returns its voice.
     */
    public int stinger(SoundClip clip, float gain, float duck) {
        if (stinger != 0) {
            audio.stop(stinger, 0.05f);
        }
        stingerDuck = duck;
        stinger = audio.play(clip, Audio.BUS_MUSIC, gain, PRIORITY + 1, 0, 0, 0);
        return stinger;
    }

    /** Sets how fast the music dips and recovers around stingers. */
    public void setDuckRamp(float seconds) {
        rampSeconds = Math.max(1e-3f, seconds);
    }

    /** Returns the music's current duck level (1 when no stinger plays). */
    public float level() {
        return level;
    }

    /** Ramps the stinger ducking; call once per frame with the frame time. */
    public void update(float dt) {
        boolean ducking = stinger != 0 && audio.voiceState(stinger) != Audio.VOICE_NONE;
        if (!ducking) {
            stinger = 0;
        }
        float target = ducking ? stingerDuck : 1;
        float step = dt / rampSeconds;
        level = level > target ? Math.max(target, level - step) : Math.min(target, level + step);
        if (voice != 0 && current >= 0) {
            audio.setVoiceGain(voice, gains[current] * level);
        }
    }
}
