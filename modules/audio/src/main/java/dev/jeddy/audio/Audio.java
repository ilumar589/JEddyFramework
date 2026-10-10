package dev.jeddy.audio;

import static dev.jeddy.sdl.mixer.ffi.SDL3Mixer.*;

import dev.jeddy.asset.Vfs;
import dev.jeddy.core.config.Settings;
import dev.jeddy.sdl.Sdl;
import dev.jeddy.sdl.ffi.SDL3;
import dev.jeddy.sdl.ffi.SDL_AudioSpec;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * The audio system on SDL3_mixer (DESIGN §12.3). Mixing and decoding are native; no Java runs on
 * the audio thread. The game thread plays sounds into <b>logical voices</b> (handles); {@link
 * #update} maps the most important audible ones onto a fixed pool of {@code MIX_Track}s (priority,
 * then loudness, then age decide who is stolen) and keeps the rest <b>virtual</b>: tracked by time,
 * silent, and resumed at the right offset when they become audible again (emitters that come back
 * in range). Each update applies the frame's changes inside {@code MIX_LockMixer}/{@code
 * MIX_UnlockMixer}, so they take effect together, and skips gains and positions that did not change
 * past a threshold.
 *
 * <p><b>Buses</b> ({@link #BUS_MUSIC} ...) tag their tracks (pause, resume and stop by bus) and
 * scale voice gains; their gains are multiplied in Java because {@code MIX_SetTagGain} overwrites
 * each track's own gain (D59). One bus can be <b>ducked</b> while another plays (music under
 * voice).
 *
 * <p><b>3D</b>: emitters are SoA; the listener is the camera's focus point and facing. Playing
 * emitters are transformed into the listener's space (SDL_mixer's listener sits at the origin,
 * facing −z, right-handed) and passed as a <em>unit</em> direction, so SDL's own distance
 * attenuation ({@code 1 / max(d, 1)}) is exactly 1 and the rolloff is ours: inverse distance from
 * each emitter's minimum distance, faded to silence at its maximum.
 *
 * <p>Thread ownership: {@link #open} and {@link #close} of a device mixer on the main thread
 * (SDL_mixer's rule for devices; offline mixers on any thread); loading clips on any thread; every
 * other call on the game thread. {@link #update} allocates nothing.
 */
public final class Audio implements AutoCloseable {
    /** Music bus. */
    public static final int BUS_MUSIC = 0;

    /** Sound effects bus. */
    public static final int BUS_SFX = 1;

    /** Voice-over and barks bus. */
    public static final int BUS_VOICE = 2;

    /** Ambience bus. */
    public static final int BUS_AMBIENT = 3;

    /** Interface sounds bus. */
    public static final int BUS_UI = 4;

    /** Number of buses. */
    public static final int BUSES = 5;

    /** Voice state: no such voice (finished, stopped or stale handle). */
    public static final int VOICE_NONE = 0;

    /** Voice state: requested, starts at the next update. */
    public static final int VOICE_PENDING = 1;

    /** Voice state: mixed on a track. */
    public static final int VOICE_PLAYING = 2;

    /** Voice state: tracked but silent (out of range or out of tracks). */
    public static final int VOICE_VIRTUAL = 3;

    /** Voice state: fading out. */
    public static final int VOICE_STOPPING = 4;

    private static final String[] BUS_TAGS = {"music", "sfx", "voice", "ambient", "ui"};
    private static final float AUDIBLE = 1e-4f;
    private static final float REVIVE_FADE_MS = 40;

    private final Arena arena;
    private final MemorySegment mixer;
    private final boolean device;
    private final int props;
    private final MemorySegment point;
    private final MemorySegment[] tags = new MemorySegment[BUSES];
    private final float dirThreshold;
    private final float gainThreshold;
    private final long streamThreshold;
    private boolean closed;

    // Tracks.
    private final MemorySegment[] tracks;
    private final int[] trackVoice;
    private final int[] trackBus;
    private final boolean[] trackSpatial;
    private int freeTracks;

    // Logical voices (SoA).
    private final byte[] state;
    private final int[] gen;
    private final SoundClip[] clip;
    private final int[] bus;
    private final float[] gain;
    private final int[] priority;
    private final int[] loops;
    private final int[] emitterOf;
    private final double[] start;
    private final float[] fadeIn;
    private final int[] track;
    private final float[] applied;
    private final float[] dirX;
    private final float[] dirY;
    private final float[] dirZ;
    private final float[] loudness;
    private final float[] dirTargetX;
    private final float[] dirTargetY;
    private final float[] dirTargetZ;
    private final int[] freeVoices;
    private int freeVoiceCount;
    private final int[] active;
    private final int[] activeAt;
    private int activeCount;
    private final int[] picked;
    private final int[] toStop;
    private final int[] toStart;
    private int stopCount;
    private int startCount;

    // Emitters (SoA).
    private float[] ex = new float[64];
    private float[] ey = new float[64];
    private float[] ez = new float[64];
    private float[] eMin = new float[64];
    private float[] eMax = new float[64];
    private int[] eGen = new int[64];
    private boolean[] eAlive = new boolean[64];
    private int emitterSlots;

    // Buses and ducking.
    private final float[] busGain = {1, 1, 1, 1, 1};
    private float master = 1;
    private float appliedMaster = -1;
    private int duckedBus = -1;
    private int duckTrigger = -1;
    private float duckGain = 1;
    private float duckAttack = 0.1f;
    private float duckRelease = 0.5f;
    private float duckLevel = 1;

    // Listener.
    private float lx;
    private float ly;
    private float lz;
    private float fx;
    private float fz = -1;
    private double time;

    private Audio(AudioConfig cfg) {
        Sdl.loadNatives();
        Sdl.loadMixerNatives();
        Sdl.check(MIX_Init(), "MIX_Init");
        arena = Arena.ofShared();
        MemorySegment spec = SDL_AudioSpec.allocate(arena);
        SDL_AudioSpec.format(spec, SDL3.SDL_AUDIO_F32());
        SDL_AudioSpec.channels(spec, cfg.channels);
        SDL_AudioSpec.freq(spec, cfg.frequency);
        device = cfg.device;
        if (device && cfg.driver != null) {
            Sdl.setHint("SDL_AUDIO_DRIVER", cfg.driver);
        }
        mixer =
                Sdl.check(
                        device
                                ? MIX_CreateMixerDevice(
                                        SDL3.SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK(), spec)
                                : MIX_CreateMixer(spec),
                        device ? "MIX_CreateMixerDevice" : "MIX_CreateMixer");
        props = Sdl.checkId(SDL3.SDL_CreateProperties(), "SDL_CreateProperties");
        point = arena.allocate(16, 16);
        for (int b = 0; b < BUSES; b++) {
            tags[b] = arena.allocateFrom(BUS_TAGS[b]);
        }
        dirThreshold = cfg.positionThreshold;
        gainThreshold = cfg.gainThreshold;
        streamThreshold = cfg.streamThreshold;
        int n = cfg.tracks;
        tracks = new MemorySegment[n];
        trackVoice = new int[n];
        trackBus = new int[n];
        trackSpatial = new boolean[n];
        for (int t = 0; t < n; t++) {
            tracks[t] = Sdl.check(MIX_CreateTrack(mixer), "MIX_CreateTrack");
            trackVoice[t] = -1;
            trackBus[t] = -1;
        }
        freeTracks = n;
        int v = cfg.voices;
        state = new byte[v];
        gen = new int[v];
        clip = new SoundClip[v];
        bus = new int[v];
        gain = new float[v];
        priority = new int[v];
        loops = new int[v];
        emitterOf = new int[v];
        start = new double[v];
        fadeIn = new float[v];
        track = new int[v];
        applied = new float[v];
        dirX = new float[v];
        dirY = new float[v];
        dirZ = new float[v];
        loudness = new float[v];
        dirTargetX = new float[v];
        dirTargetY = new float[v];
        dirTargetZ = new float[v];
        freeVoices = new int[v];
        active = new int[v];
        activeAt = new int[v];
        picked = new int[v];
        toStop = new int[n];
        toStart = new int[n];
        for (int i = 0; i < v; i++) {
            freeVoices[i] = v - 1 - i;
        }
        freeVoiceCount = v;
        prelink();
    }

    /**
     * Creates the downcall handles (lazy jextract holders) and links the memory accessors that
     * {@link #play} and {@link #update} use, so the game's first positional sound or first finished
     * voice does not allocate mid-frame (D65).
     */
    private void prelink() {
        MethodHandle[] handles = {
            MIX_SetTrack3DPosition$handle(),
            MIX_TrackPaused$handle(),
            MIX_TrackPlaying$handle(),
            MIX_SetTrackGain$handle(),
            MIX_StopTrack$handle(),
            MIX_TagTrack$handle(),
            MIX_UntagTrack$handle(),
            MIX_SetTrackAudio$handle(),
            MIX_PlayTrack$handle(),
            MIX_LockMixer$handle(),
            MIX_UnlockMixer$handle(),
            MIX_PauseTag$handle(),
            MIX_ResumeTag$handle(),
            MIX_TrackMSToFrames$handle(),
            MIX_AudioFramesToMS$handle(),
            MIX_SetMixerGain$handle(),
            SDL3.SDL_SetNumberProperty$handle()
        };
        if (handles.length == 0
                || MIX_PROP_PLAY_LOOPS_NUMBER() == null
                || MIX_PROP_PLAY_START_MILLISECOND_NUMBER() == null
                || MIX_PROP_PLAY_FADE_IN_MILLISECONDS_NUMBER() == null) {
            throw new IllegalStateException("SDL_mixer bindings missing");
        }
        writePoint(0, 0, 0);
        MIX_TrackPlaying(tracks[0]);
        MIX_TrackPaused(tracks[0]);
    }

    /** Opens the audio system (see the class comment for the thread). */
    public static Audio open(AudioConfig cfg) {
        return new Audio(cfg);
    }

    /** Returns the number of mixed tracks. */
    public int trackCount() {
        return tracks.length;
    }

    /** Returns the number of tracks in use. */
    public int busyTracks() {
        return tracks.length - freeTracks;
    }

    /** Returns the number of live logical voices. */
    public int voiceCount() {
        return activeCount;
    }

    /** Returns the audio clock (seconds of {@link #update} time). */
    public double time() {
        return time;
    }

    // Clips ---------------------------------------------------------------------------------------

    /** Returns an asset loader for sounds with the configured stream threshold. */
    public SoundLoader loader() {
        return new SoundLoader(this, streamThreshold);
    }

    /** Decodes {@code encoded} (WAV, OGG, FLAC, MP3) to PCM now; the bytes may go away after. */
    public SoundClip loadPredecoded(MemorySegment encoded) {
        MemorySegment io =
                Sdl.check(
                        SDL3.SDL_IOFromConstMem(encoded, encoded.byteSize()), "SDL_IOFromConstMem");
        MemorySegment a = Sdl.check(MIX_LoadAudio_IO(mixer, io, true, true), "MIX_LoadAudio_IO");
        return clip(a, false, null, null);
    }

    /**
     * Streams from the pinned pak bytes (zero copy): decoded while playing; the pin is released
     * with the clip.
     */
    public SoundClip loadStreamed(Vfs.Pin pin) {
        MemorySegment data = pin.data();
        MemorySegment a =
                Sdl.check(
                        MIX_LoadAudioNoCopy(mixer, data, data.byteSize(), false),
                        "MIX_LoadAudioNoCopy");
        return clip(a, true, pin, null);
    }

    /**
     * Streams from a native copy of {@code encoded} owned by the clip (loose files in development).
     */
    public SoundClip loadStreamedCopy(MemorySegment encoded) {
        Arena owned = Arena.ofShared();
        MemorySegment copy = owned.allocate(Math.max(1, encoded.byteSize()), 16);
        MemorySegment.copy(encoded, 0, copy, 0, encoded.byteSize());
        MemorySegment a = MIX_LoadAudioNoCopy(mixer, copy, encoded.byteSize(), false);
        if (a.address() == 0) {
            owned.close();
            Sdl.check(a, "MIX_LoadAudioNoCopy");
        }
        return clip(a, true, null, owned);
    }

    /** Creates a sine tone (tests, placeholders). */
    public SoundClip sine(int hz, float amplitude, double seconds) {
        MemorySegment a =
                Sdl.check(
                        MIX_CreateSineWaveAudio(mixer, hz, amplitude, (long) (seconds * 1000)),
                        "MIX_CreateSineWaveAudio");
        return clip(a, false, null, null);
    }

    private SoundClip clip(MemorySegment a, boolean streamed, Vfs.Pin pin, Arena owned) {
        long frames = MIX_GetAudioDuration(a);
        double seconds =
                frames < 0 ? Double.POSITIVE_INFINITY : MIX_AudioFramesToMS(a, frames) / 1000.0;
        return new SoundClip(a, streamed, seconds, pin, owned);
    }

    /** Stops every voice playing {@code c} and frees it (game thread). */
    public void destroy(SoundClip c) {
        if (c.destroyed) {
            return;
        }
        for (int k = activeCount - 1; k >= 0; k--) {
            int v = active[k];
            if (clip[v] == c) {
                release(v, true);
            }
        }
        MIX_DestroyAudio(c.audio);
        c.destroyed = true;
        c.releaseData();
    }

    // Voices --------------------------------------------------------------------------------------

    /** Plays {@code c} on {@code bus} (2D) once; returns its voice handle, or 0 if dropped. */
    public int play(SoundClip c, int busIndex, float volume) {
        return play(c, busIndex, volume, 0, 0, 0, 0);
    }

    /**
     * Plays {@code c}.
     *
     * @param busIndex {@code BUS_*}
     * @param volume linear gain
     * @param importance higher survives voice stealing
     * @param loopCount extra plays after the first ({@code -1} forever)
     * @param fadeInSeconds fade-in length
     * @param emitter emitter handle for a positional sound, 0 for 2D
     * @return the voice handle, or 0 when every voice is busy with more important sounds
     */
    public int play(
            SoundClip c,
            int busIndex,
            float volume,
            int importance,
            int loopCount,
            float fadeInSeconds,
            int emitter) {
        if (c.destroyed) {
            throw new IllegalArgumentException("the clip was destroyed");
        }
        if (freeVoiceCount == 0 && !dropLeastImportantVirtual(importance)) {
            return 0;
        }
        int v = freeVoices[--freeVoiceCount];
        int g = (gen[v] + 1) & 0x7fff;
        gen[v] = g == 0 ? 1 : g;
        state[v] = VOICE_PENDING;
        clip[v] = c;
        bus[v] = busIndex;
        gain[v] = volume;
        priority[v] = importance;
        loops[v] = loopCount;
        emitterOf[v] = emitter == 0 ? -1 : emitterIndex(emitter);
        start[v] = time;
        fadeIn[v] = fadeInSeconds;
        track[v] = -1;
        applied[v] = -1;
        dirX[v] = Float.NaN;
        activeAt[v] = activeCount;
        active[activeCount++] = v;
        return gen[v] << 16 | v;
    }

    private boolean dropLeastImportantVirtual(int importance) {
        int victim = -1;
        for (int k = 0; k < activeCount; k++) {
            int v = active[k];
            if (state[v] == VOICE_VIRTUAL
                    && priority[v] <= importance
                    && (victim < 0 || less(v, victim))) {
                victim = v;
            }
        }
        if (victim < 0) {
            return false;
        }
        release(victim, false);
        return true;
    }

    /** Whether voice a matters less than voice b: lower priority, then quieter, then older. */
    private boolean less(int a, int b) {
        if (priority[a] != priority[b]) {
            return priority[a] < priority[b];
        }
        if (loudness[a] != loudness[b]) {
            return loudness[a] < loudness[b];
        }
        return start[a] < start[b];
    }

    private int voiceIndex(int handle) {
        int v = handle & 0xffff;
        if (handle == 0 || v >= state.length || state[v] == VOICE_NONE || gen[v] != handle >>> 16) {
            return -1;
        }
        return v;
    }

    /** Returns the state of a voice ({@code VOICE_*}); {@link #VOICE_NONE} once it is gone. */
    public int voiceState(int voice) {
        int v = voiceIndex(voice);
        return v < 0 ? VOICE_NONE : state[v];
    }

    /** Changes a voice's gain. */
    public void setVoiceGain(int voice, float volume) {
        int v = voiceIndex(voice);
        if (v >= 0) {
            gain[v] = volume;
        }
    }

    /** Stops a voice, fading out over {@code fadeSeconds}. */
    public void stop(int voice, float fadeSeconds) {
        int v = voiceIndex(voice);
        if (v < 0) {
            return;
        }
        if (state[v] == VOICE_PLAYING && fadeSeconds > 0) {
            MemorySegment t = tracks[track[v]];
            MIX_StopTrack(t, MIX_TrackMSToFrames(t, (long) (fadeSeconds * 1000)));
            state[v] = VOICE_STOPPING;
        } else if (state[v] != VOICE_STOPPING) {
            release(v, true);
        }
    }

    /** Frees voice v (stopping its track if it has one). */
    private void release(int v, boolean stopTrack) {
        int t = track[v];
        if (t >= 0) {
            if (stopTrack) {
                MIX_StopTrack(tracks[t], 0);
            }
            trackVoice[t] = -1;
            freeTracks++;
            track[v] = -1;
        }
        state[v] = VOICE_NONE;
        clip[v] = null;
        int at = activeAt[v];
        int last = active[--activeCount];
        active[at] = last;
        activeAt[last] = at;
        freeVoices[freeVoiceCount++] = v;
    }

    // Emitters ------------------------------------------------------------------------------------

    /**
     * Adds a 3D emitter heard at full volume within {@code minDistance} and fading to silence at
     * {@code maxDistance}; returns its handle.
     */
    public int addEmitter(float x, float y, float z, float minDistance, float maxDistance) {
        int e = -1;
        for (int i = 0; i < emitterSlots; i++) {
            if (!eAlive[i]) {
                e = i;
                break;
            }
        }
        if (e < 0) {
            if (emitterSlots == ex.length) {
                int cap = emitterSlots * 2;
                ex = java.util.Arrays.copyOf(ex, cap);
                ey = java.util.Arrays.copyOf(ey, cap);
                ez = java.util.Arrays.copyOf(ez, cap);
                eMin = java.util.Arrays.copyOf(eMin, cap);
                eMax = java.util.Arrays.copyOf(eMax, cap);
                eGen = java.util.Arrays.copyOf(eGen, cap);
                eAlive = java.util.Arrays.copyOf(eAlive, cap);
            }
            e = emitterSlots++;
        }
        eAlive[e] = true;
        int g = (eGen[e] + 1) & 0x7fff;
        eGen[e] = g == 0 ? 1 : g;
        ex[e] = x;
        ey[e] = y;
        ez[e] = z;
        eMin[e] = Math.max(0.01f, minDistance);
        eMax[e] = Math.max(eMin[e] * 1.01f, maxDistance);
        return eGen[e] << 16 | e;
    }

    private int emitterIndex(int handle) {
        int e = handle & 0xffff;
        if (e >= emitterSlots || !eAlive[e] || eGen[e] != handle >>> 16) {
            throw new IllegalArgumentException("stale emitter handle");
        }
        return e;
    }

    /** Moves an emitter. */
    public void setEmitter(int emitter, float x, float y, float z) {
        int e = emitterIndex(emitter);
        ex[e] = x;
        ey[e] = y;
        ez[e] = z;
    }

    /** Removes an emitter, fading out the voices attached to it. */
    public void removeEmitter(int emitter) {
        int e = emitterIndex(emitter);
        for (int k = activeCount - 1; k >= 0; k--) {
            int v = active[k];
            if (emitterOf[v] == e) {
                stop(gen[v] << 16 | v, 0.05f);
                emitterOf[v] = -2; // detached: silent from now on
            }
        }
        eAlive[e] = false;
    }

    // Listener and buses --------------------------------------------------------------------------

    /** Places the listener (the camera's focus point) facing {@code (forwardX, forwardZ)}. */
    public void setListener(float x, float y, float z, float forwardX, float forwardZ) {
        lx = x;
        ly = y;
        lz = z;
        float len = (float) Math.sqrt(forwardX * forwardX + forwardZ * forwardZ);
        if (len > 1e-6f) {
            fx = forwardX / len;
            fz = forwardZ / len;
        }
    }

    /** Returns the listener position. */
    public float listenerX() {
        return lx;
    }

    /** Returns the listener position. */
    public float listenerY() {
        return ly;
    }

    /** Returns the listener position. */
    public float listenerZ() {
        return lz;
    }

    /**
     * Applies the volume settings ({@code audio.master}, {@code audio.music}, {@code audio.sfx},
     * {@code audio.voice}, {@code audio.ambient}, {@code audio.ui}; 0..1, default 1).
     */
    public void applySettings(Settings settings) {
        setMasterGain(Math.clamp(settings.getFloat("audio.master", 1), 0, 4));
        for (int b = 0; b < BUSES; b++) {
            setBusGain(b, Math.clamp(settings.getFloat("audio." + BUS_TAGS[b], 1), 0, 4));
        }
    }

    /** Writes the current volumes to {@code settings} (see {@link #applySettings}). */
    public void saveSettings(Settings settings) {
        settings.set("audio.master", (double) master);
        for (int b = 0; b < BUSES; b++) {
            settings.set("audio." + BUS_TAGS[b], (double) busGain[b]);
        }
    }

    /** Sets a bus's gain. */
    public void setBusGain(int b, float value) {
        busGain[b] = value;
    }

    /** Returns a bus's gain. */
    public float busGain(int b) {
        return busGain[b];
    }

    /** Sets the master gain. */
    public void setMasterGain(float value) {
        master = value;
    }

    /**
     * Ducks bus {@code ducked} to {@code gain} while anything plays on bus {@code trigger} (music
     * under voice), ramping down over {@code attackSeconds} and back over {@code releaseSeconds};
     * {@code ducked = -1} turns ducking off.
     */
    public void setDucking(
            int ducked,
            int trigger,
            float gainWhileDucked,
            float attackSeconds,
            float releaseSeconds) {
        duckedBus = ducked;
        duckTrigger = trigger;
        duckGain = gainWhileDucked;
        duckAttack = Math.max(1e-3f, attackSeconds);
        duckRelease = Math.max(1e-3f, releaseSeconds);
    }

    /** Returns the current duck factor (1 when not ducked). */
    public float duckLevel() {
        return duckLevel;
    }

    /** Pauses every track on a bus (virtual voices keep their clock). */
    public void pauseBus(int b) {
        MIX_PauseTag(mixer, tags[b]);
    }

    /** Resumes a paused bus. */
    public void resumeBus(int b) {
        MIX_ResumeTag(mixer, tags[b]);
    }

    // Update --------------------------------------------------------------------------------------

    /** Advances the audio clock by {@code dt} seconds and applies the frame's changes. */
    public void update(float dt) {
        time += dt;
        retireFinished();
        updateDucking(dt);
        for (int k = 0; k < activeCount; k++) {
            computeLoudness(active[k]);
        }
        stopCount = 0;
        startCount = 0;
        // Silence playing voices that went out of range.
        for (int k = activeCount - 1; k >= 0; k--) {
            int v = active[k];
            if (state[v] == VOICE_PLAYING && loudness[v] < AUDIBLE && emitterOf[v] >= 0) {
                virtualize(v);
            }
        }
        assignTracks();
        MIX_LockMixer(mixer);
        try {
            if (master != appliedMaster) {
                MIX_SetMixerGain(mixer, master);
                appliedMaster = master;
            }
            for (int i = 0; i < stopCount; i++) {
                MIX_StopTrack(tracks[toStop[i]], 0);
            }
            for (int i = 0; i < startCount; i++) {
                startOnTrack(toStart[i]);
            }
            for (int k = 0; k < activeCount; k++) {
                int v = active[k];
                if (state[v] == VOICE_PLAYING || state[v] == VOICE_STOPPING) {
                    applyMix(v);
                }
            }
        } finally {
            MIX_UnlockMixer(mixer);
        }
    }

    private void retireFinished() {
        for (int k = activeCount - 1; k >= 0; k--) {
            int v = active[k];
            byte s = state[v];
            if (s == VOICE_PLAYING || s == VOICE_STOPPING) {
                MemorySegment t = tracks[track[v]];
                if (!MIX_TrackPlaying(t) && !MIX_TrackPaused(t)) {
                    release(v, false);
                }
            } else if (s == VOICE_VIRTUAL
                    && (emitterOf[v] == -2
                            || loops[v] >= 0
                                    && time - start[v] >= clip[v].seconds * (loops[v] + 1))) {
                release(v, false);
            }
        }
    }

    private void updateDucking(float dt) {
        float target = 1;
        if (duckedBus >= 0) {
            for (int k = 0; k < activeCount; k++) {
                int v = active[k];
                if (bus[v] == duckTrigger && state[v] != VOICE_VIRTUAL) {
                    target = duckGain;
                    break;
                }
            }
        }
        if (duckLevel > target) {
            duckLevel = Math.max(target, duckLevel - (1 - duckGain) * dt / duckAttack);
        } else if (duckLevel < target) {
            duckLevel = Math.min(target, duckLevel + (1 - duckGain) * dt / duckRelease);
        }
    }

    /** Effective gain and listener-space direction of voice v. */
    private void computeLoudness(int v) {
        float g = gain[v] * busGain[bus[v]];
        if (bus[v] == duckedBus) {
            g *= duckLevel;
        }
        int e = emitterOf[v];
        if (e == -2) {
            loudness[v] = 0;
            return;
        }
        if (e >= 0) {
            float rx = ex[e] - lx;
            float ry = ey[e] - ly;
            float rz = ez[e] - lz;
            float d = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
            g *= rolloff(d, eMin[e], eMax[e]);
            // Listener space: x right, y up, z back (SDL_mixer's fixed listener faces -z).
            float sx = -fz * rx + fx * rz;
            float sz = -(fx * rx + fz * rz);
            if (d > 1e-4f) {
                dirTargetX[v] = sx / d;
                dirTargetY[v] = ry / d;
                dirTargetZ[v] = sz / d;
            } else {
                dirTargetX[v] = 0;
                dirTargetY[v] = 0;
                dirTargetZ[v] = -1;
            }
        }
        loudness[v] = g;
    }

    /**
     * Inverse-distance rolloff from {@code min}, faded to 0 over the last fifth before {@code max}.
     */
    static float rolloff(float d, float min, float max) {
        if (d >= max) {
            return 0;
        }
        float g = d <= min ? 1 : min / d;
        float fadeStart = Math.max(min, max * 0.8f);
        if (d > fadeStart) {
            g *= (max - d) / (max - fadeStart);
        }
        return g;
    }

    private void virtualize(int v) {
        int t = track[v];
        toStop[stopCount++] = t;
        trackVoice[t] = -1;
        freeTracks++;
        track[v] = -1;
        state[v] = VOICE_VIRTUAL;
    }

    /** Gives tracks to the most important audible voices waiting for one, stealing if needed. */
    private void assignTracks() {
        int n = 0;
        for (int k = 0; k < activeCount; k++) {
            int v = active[k];
            if ((state[v] == VOICE_PENDING || state[v] == VOICE_VIRTUAL)
                    && loudness[v] >= AUDIBLE) {
                picked[n++] = v;
            } else if (state[v] == VOICE_PENDING) {
                state[v] = VOICE_VIRTUAL; // started out of range: tracked silently
            }
        }
        // Most important first (insertion sort: few candidates per frame).
        for (int i = 1; i < n; i++) {
            int v = picked[i];
            int j = i - 1;
            while (j >= 0 && less(picked[j], v)) {
                picked[j + 1] = picked[j];
                j--;
            }
            picked[j + 1] = v;
        }
        for (int i = 0; i < n; i++) {
            int v = picked[i];
            int t = -1;
            if (freeTracks > 0) {
                for (int k = 0; k < tracks.length; k++) {
                    if (trackVoice[k] < 0) {
                        t = k; // a track freed this frame is stopped before it restarts
                        break;
                    }
                }
            }
            if (t < 0) {
                // Steal from the least important playing voice, if it matters less.
                int victim = -1;
                for (int k = 0; k < tracks.length; k++) {
                    int w = trackVoice[k];
                    if (w >= 0
                            && state[w] == VOICE_PLAYING
                            && less(w, v)
                            && (victim < 0 || less(w, victim))) {
                        victim = w;
                    }
                }
                if (victim < 0) {
                    if (state[v] == VOICE_PENDING) {
                        state[v] = VOICE_VIRTUAL;
                    }
                    continue;
                }
                t = track[victim];
                virtualize(victim);
            }
            trackVoice[t] = v;
            freeTracks--;
            track[v] = t;
            toStart[startCount++] = v;
        }
    }

    /** Starts voice v on its track at the right offset (inside the mixer lock). */
    private void startOnTrack(int v) {
        int t = track[v];
        MemorySegment tr = tracks[t];
        SoundClip c = clip[v];
        boolean revived = state[v] == VOICE_VIRTUAL;
        double elapsed = time - start[v];
        long offsetMs = 0;
        int remainingLoops = loops[v];
        if (revived && c.seconds < Double.POSITIVE_INFINITY && c.seconds > 0) {
            long done = (long) (elapsed / c.seconds);
            offsetMs = (long) ((elapsed - done * c.seconds) * 1000);
            if (remainingLoops > 0) {
                remainingLoops = (int) Math.max(0, remainingLoops - done);
            }
        } else if (revived) {
            offsetMs = (long) (elapsed * 1000);
        }
        MIX_SetTrackAudio(tr, c.audio);
        if (trackBus[t] != bus[v]) {
            if (trackBus[t] >= 0) {
                MIX_UntagTrack(tr, tags[trackBus[t]]);
            }
            MIX_TagTrack(tr, tags[bus[v]]);
            trackBus[t] = bus[v];
        }
        boolean spatial = emitterOf[v] >= 0;
        if (spatial) {
            writePoint(dirTargetX[v], dirTargetY[v], dirTargetZ[v]);
            MIX_SetTrack3DPosition(tr, point);
            dirX[v] = dirTargetX[v];
            dirY[v] = dirTargetY[v];
            dirZ[v] = dirTargetZ[v];
        } else if (trackSpatial[t]) {
            MIX_SetTrack3DPosition(tr, MemorySegment.NULL);
        }
        trackSpatial[t] = spatial;
        MIX_SetTrackGain(tr, loudness[v]);
        applied[v] = loudness[v];
        SDL3.SDL_SetNumberProperty(props, MIX_PROP_PLAY_LOOPS_NUMBER(), remainingLoops);
        SDL3.SDL_SetNumberProperty(props, MIX_PROP_PLAY_START_MILLISECOND_NUMBER(), offsetMs);
        long fade = revived ? (long) REVIVE_FADE_MS : (long) (fadeIn[v] * 1000);
        SDL3.SDL_SetNumberProperty(props, MIX_PROP_PLAY_FADE_IN_MILLISECONDS_NUMBER(), fade);
        state[v] = VOICE_PLAYING;
        if (!MIX_PlayTrack(tr, props)) {
            state[v] = VOICE_STOPPING; // retired at the next update
        }
    }

    private void writePoint(float x, float y, float z) {
        point.set(ValueLayout.JAVA_FLOAT, 0, x);
        point.set(ValueLayout.JAVA_FLOAT, 4, y);
        point.set(ValueLayout.JAVA_FLOAT, 8, z);
    }

    /** Sends a playing voice's gain and direction when they changed past the thresholds. */
    private void applyMix(int v) {
        MemorySegment tr = tracks[track[v]];
        float g = loudness[v];
        if (Math.abs(g - applied[v]) > gainThreshold || (g == 0) != (applied[v] == 0)) {
            MIX_SetTrackGain(tr, g);
            applied[v] = g;
        }
        if (emitterOf[v] >= 0) {
            float dx = dirTargetX[v] - dirX[v];
            float dy = dirTargetY[v] - dirY[v];
            float dz = dirTargetZ[v] - dirZ[v];
            if (!(dx * dx + dy * dy + dz * dz <= dirThreshold * dirThreshold)) {
                writePoint(dirTargetX[v], dirTargetY[v], dirTargetZ[v]);
                MIX_SetTrack3DPosition(tr, point);
                dirX[v] = dirTargetX[v];
                dirY[v] = dirTargetY[v];
                dirZ[v] = dirTargetZ[v];
            }
        }
    }

    // Offline mixing
    // --------------------------------------------------------------------------------

    /**
     * Mixes the next {@code bytes} of output (float samples, interleaved) into {@code buffer}
     * (offline mixers only); returns the bytes written.
     */
    public int generate(MemorySegment buffer, int bytes) {
        if (device) {
            throw new IllegalStateException("generate is for offline mixers");
        }
        return MIX_Generate(mixer, buffer, bytes);
    }

    /** Stops everything and closes the mixer (see the class comment for the thread). */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (MemorySegment t : tracks) {
            MIX_DestroyTrack(t);
        }
        SDL3.SDL_DestroyProperties(props);
        MIX_DestroyMixer(mixer);
        MIX_Quit();
        arena.close();
    }
}
