package dev.jeddy.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/** Voices, buses, 3D, virtual voices, stealing and ducking on an offline mixer (M9-02..M9-04). */
class AudioTest {
    static final int RATE = 48_000;
    static final float DT = 1 / 60f;
    final Arena arena = Arena.ofConfined();
    final MemorySegment buffer = arena.allocate(RATE * 2 * 4, 16);
    Audio audio;

    Audio open(int tracks, int voices) {
        audio = Audio.open(new AudioConfig().offline().voices(tracks, voices).format(RATE, 2));
        return audio;
    }

    @AfterEach
    void close() {
        if (audio != null) {
            audio.close();
        }
        arena.close();
    }

    /** Mixes {@code seconds} of output; returns interleaved stereo samples (0 bytes: silence). */
    float[] mix(double seconds) {
        int frames = (int) (seconds * RATE);
        int bytes = audio.generate(buffer, frames * 8);
        assertTrue(bytes >= 0, "generate");
        if (bytes == 0) {
            return new float[frames * 2]; // nothing playing: silence
        }
        float[] out = new float[bytes / 4];
        MemorySegment.copy(buffer, ValueLayout.JAVA_FLOAT, 0, out, 0, out.length);
        return out;
    }

    /** Runs updates and mixing for {@code seconds}. */
    void run(double seconds) {
        for (double t = 0; t < seconds; t += DT) {
            audio.update(DT);
            mix(DT);
        }
    }

    static double rms(float[] s, int channel) {
        double sum = 0;
        int n = 0;
        for (int i = channel; i < s.length; i += 2) {
            sum += s[i] * s[i];
            n++;
        }
        return Math.sqrt(sum / n);
    }

    @Test
    void busAndMasterGainsScaleVoices() {
        open(8, 16);
        SoundClip tone = audio.sine(440, 0.5f, 2);
        int v = audio.play(tone, Audio.BUS_SFX, 1);
        assertNotEquals(0, v);
        assertEquals(Audio.VOICE_PENDING, audio.voiceState(v));
        audio.update(DT);
        assertEquals(Audio.VOICE_PLAYING, audio.voiceState(v));
        float[] s = mix(0.1);
        double full = rms(s, 0);
        assertTrue(full > 0.2, "audible: " + full);
        assertEquals(full, rms(s, 1), full * 0.02, "2D: both channels");
        audio.setBusGain(Audio.BUS_SFX, 0.5f);
        audio.update(DT);
        mix(0.02); // gain changes may ramp
        assertEquals(full * 0.5, rms(mix(0.1), 0), full * 0.05);
        audio.setMasterGain(0.5f);
        audio.update(DT);
        mix(0.02);
        assertEquals(full * 0.25, rms(mix(0.1), 0), full * 0.05);
    }

    @Test
    void deviceMixersOpenOnTheDummyDriver() {
        try (Audio device = Audio.open(new AudioConfig().driver("dummy").voices(4, 8))) {
            int v = device.play(device.sine(440, 0.2f, 1), Audio.BUS_UI, 1);
            device.update(DT);
            assertEquals(Audio.VOICE_PLAYING, device.voiceState(v));
            device.stop(v, 0);
            assertEquals(Audio.VOICE_NONE, device.voiceState(v));
        }
    }

    @Test
    void volumesComeFromSettings() {
        open(2, 4);
        dev.jeddy.core.config.Settings s =
                dev.jeddy.core.config.Settings.parse(
                        "[audio]\nmaster = 0.8\nmusic = 0.5\nui = 0.25\n");
        audio.applySettings(s);
        assertEquals(0.5f, audio.busGain(Audio.BUS_MUSIC));
        assertEquals(0.25f, audio.busGain(Audio.BUS_UI));
        assertEquals(1f, audio.busGain(Audio.BUS_SFX), "default");
        audio.setBusGain(Audio.BUS_SFX, 0.7f);
        dev.jeddy.core.config.Settings out = new dev.jeddy.core.config.Settings();
        audio.saveSettings(out);
        assertEquals(0.7f, out.getFloat("audio.sfx", 0), 1e-6);
        assertEquals(0.8f, out.getFloat("audio.master", 0), 1e-6);
    }

    @Test
    void emittersPanAndAttenuateWithOurRolloff() {
        open(8, 16);
        SoundClip tone = audio.sine(440, 0.5f, 5);
        int ref = audio.play(tone, Audio.BUS_SFX, 1);
        audio.update(DT);
        mix(0.02);
        double full = rms(mix(0.1), 0);
        audio.stop(ref, 0);
        // Listener at the origin facing -z: an emitter 5 m to the right (+x).
        audio.setListener(0, 0, 0, 0, -1);
        int e = audio.addEmitter(5, 0, 0, 1, 50);
        int v = audio.play(tone, Audio.BUS_SFX, 1, 0, -1, 0, e);
        audio.update(DT);
        mix(0.02);
        float[] s = mix(0.1);
        double left = rms(s, 0);
        double right = rms(s, 1);
        assertTrue(right > 10 * left, "panned right: L " + left + " R " + right);
        // Rolloff min/d = 0.2 at 5 m; constant-power panning puts the whole mono signal right.
        double mono = full; // 2D duplicates the mono tone on both channels at unit gain
        assertEquals(0.2 * mono, right, mono * 0.03, "attenuated by our curve only");
        // Turn to face +x: the emitter is straight ahead, centered.
        audio.setListener(0, 0, 0, 1, 0);
        audio.update(DT);
        mix(0.02);
        s = mix(0.1);
        assertEquals(rms(s, 0), rms(s, 1), rms(s, 1) * 0.05, "centered");
        // Past the maximum distance it goes virtual and frees its track.
        audio.setEmitter(e, 60, 0, 0);
        audio.update(DT);
        assertEquals(Audio.VOICE_VIRTUAL, audio.voiceState(v));
        assertEquals(0, audio.busyTracks());
        mix(0.05);
        audio.setEmitter(e, 3, 0, 0);
        audio.update(DT);
        assertEquals(Audio.VOICE_PLAYING, audio.voiceState(v), "back in range");
        assertEquals(1, audio.busyTracks());
    }

    @Test
    void importantVoicesStealTracks() {
        open(2, 8);
        SoundClip tone = audio.sine(300, 0.2f, 5);
        int a = audio.play(tone, Audio.BUS_SFX, 1, 1, -1, 0, 0);
        int b = audio.play(tone, Audio.BUS_SFX, 1, 2, -1, 0, 0);
        audio.update(DT);
        assertEquals(Audio.VOICE_PLAYING, audio.voiceState(a));
        assertEquals(Audio.VOICE_PLAYING, audio.voiceState(b));
        int c = audio.play(tone, Audio.BUS_SFX, 1, 3, -1, 0, 0);
        audio.update(DT);
        assertEquals(Audio.VOICE_PLAYING, audio.voiceState(c));
        assertEquals(Audio.VOICE_VIRTUAL, audio.voiceState(a), "the least important was stolen");
        int d = audio.play(tone, Audio.BUS_SFX, 1, 0, -1, 0, 0);
        audio.update(DT);
        assertEquals(Audio.VOICE_VIRTUAL, audio.voiceState(d), "less important: no steal");
        // Equal priority: the quieter loses.
        int loud = audio.play(tone, Audio.BUS_SFX, 1, 2, -1, 0, 0);
        audio.setVoiceGain(b, 0.1f);
        audio.update(DT);
        assertEquals(Audio.VOICE_PLAYING, audio.voiceState(loud));
        assertEquals(Audio.VOICE_VIRTUAL, audio.voiceState(b));
        // Stopping a playing voice lets the best virtual one in.
        audio.stop(c, 0);
        audio.update(DT);
        assertEquals(Audio.VOICE_NONE, audio.voiceState(c));
        assertEquals(Audio.VOICE_PLAYING, audio.voiceState(b), "priority 2 before 1 and 0");
    }

    @Test
    void oneShotsFinishPlayingOrVirtual() {
        open(1, 4);
        SoundClip blip = audio.sine(600, 0.3f, 0.2);
        assertEquals(0.2, blip.seconds(), 1e-3);
        int a = audio.play(blip, Audio.BUS_UI, 1, 5, 0, 0, 0);
        int b = audio.play(blip, Audio.BUS_UI, 1, 1, 0, 0, 0);
        audio.update(DT);
        assertEquals(Audio.VOICE_PLAYING, audio.voiceState(a));
        assertEquals(Audio.VOICE_VIRTUAL, audio.voiceState(b));
        run(0.35);
        assertEquals(Audio.VOICE_NONE, audio.voiceState(a), "finished on its track");
        assertEquals(Audio.VOICE_NONE, audio.voiceState(b), "finished while virtual");
        assertEquals(0, audio.voiceCount());
        // A fade-out stops after the fade.
        int c = audio.play(blip, Audio.BUS_UI, 1, 0, -1, 0, 0);
        audio.update(DT);
        audio.stop(c, 0.1f);
        assertEquals(Audio.VOICE_STOPPING, audio.voiceState(c));
        run(0.2);
        assertEquals(Audio.VOICE_NONE, audio.voiceState(c));
    }

    @Test
    void voiceDucksMusic() {
        open(8, 16);
        audio.setDucking(Audio.BUS_MUSIC, Audio.BUS_VOICE, 0.25f, 0.1f, 0.2f);
        SoundClip music = audio.sine(220, 0.4f, 10);
        SoundClip line = audio.sine(800, 0.3f, 0.3);
        audio.play(music, Audio.BUS_MUSIC, 1, 0, -1, 0, 0);
        run(0.1);
        assertEquals(1, audio.duckLevel(), 1e-6);
        audio.play(line, Audio.BUS_VOICE, 1);
        run(0.15);
        assertEquals(0.25f, audio.duckLevel(), 1e-6, "ducked within the attack");
        run(0.6);
        assertEquals(1, audio.duckLevel(), 1e-6, "released after the line");
    }

    @Test
    void musicCrossfadesAndStingersDuck() {
        open(8, 16);
        MusicPlayer music = new MusicPlayer(audio);
        music.define(0, audio.sine(220, 0.4f, 30), 1);
        music.define(1, audio.sine(330, 0.4f, 30), 0.8f);
        music.play(0, 0);
        int first = music.voice();
        music.update(DT);
        run(0.1);
        assertEquals(Audio.VOICE_PLAYING, audio.voiceState(first));
        music.play(1, 0.3f);
        int second = music.voice();
        music.update(DT);
        audio.update(DT);
        assertEquals(Audio.VOICE_STOPPING, audio.voiceState(first), "fading out");
        assertEquals(Audio.VOICE_PLAYING, audio.voiceState(second), "fading in");
        run(0.5);
        assertEquals(Audio.VOICE_NONE, audio.voiceState(first));
        music.play(1, 0.3f);
        assertEquals(second, music.voice(), "same state: nothing");
        int st = music.stinger(audio.sine(990, 0.3f, 0.4), 1, 0.3f);
        for (int i = 0; i < 20; i++) {
            music.update(DT);
            audio.update(DT);
            mix(DT);
        }
        assertEquals(0.3f, music.level(), 1e-6, "ducked under the stinger");
        for (int i = 0; i < 60; i++) {
            music.update(DT);
            audio.update(DT);
            mix(DT);
        }
        assertEquals(Audio.VOICE_NONE, audio.voiceState(st));
        assertEquals(1, music.level(), 1e-6, "back up");
    }

    @Test
    void ambientZonesLoopAndScatterOneShots() {
        open(16, 64);
        Ambience amb = new Ambience(audio, 7);
        SoundClip bed = audio.sine(110, 0.2f, 30);
        SoundClip bird = audio.sine(1500, 0.2f, 0.1);
        amb.defineZone(3, bed, 0.5f, 0.2f);
        amb.setOneShots(3, new SoundClip[] {bird}, 1, 0.1f, 0.2f, 5, 15);
        audio.setListener(10, 0, 10, 0, -1);
        amb.enter(3);
        amb.enter(3); // overlapping trigger
        int maxVoices = 0;
        for (int i = 0; i < 90; i++) {
            amb.update();
            audio.update(DT);
            mix(DT);
            maxVoices = Math.max(maxVoices, audio.voiceCount());
        }
        assertTrue(amb.isInside(3));
        assertTrue(maxVoices >= 2, "the bed and one-shots: " + maxVoices);
        amb.exit(3);
        assertTrue(amb.isInside(3), "still inside the other trigger");
        amb.exit(3);
        for (int i = 0; i < 60; i++) {
            amb.update();
            audio.update(DT);
            mix(DT);
        }
        assertEquals(0, audio.voiceCount(), "the bed faded and the one-shots finished");
    }

    @Test
    void sixtyFourActiveVoicesAllocateNothing() {
        open(64, 128);
        SoundClip tone = audio.sine(440, 0.01f, 60);
        int[] emitters = new int[64];
        for (int i = 0; i < 64; i++) {
            emitters[i] = audio.addEmitter(i % 8 * 3, 0, i / 8 * 3, 1, 100);
            audio.play(tone, Audio.BUS_SFX, 1, 0, -1, 0, emitters[i]);
        }
        audio.update(DT);
        assertEquals(64, audio.busyTracks());
        float[] t = {0};
        Allocations.assertNoAllocation(
                "audio update, 64 voices",
                10,
                200,
                () -> {
                    t[0] += DT;
                    for (int i = 0; i < 64; i++) {
                        audio.setEmitter(
                                emitters[i], i % 8 * 3 + (float) Math.sin(t[0] + i), 0, i / 8 * 3);
                    }
                    audio.setListener((float) Math.cos(t[0]) * 5, 0, 10, 0, -1);
                    audio.update(DT);
                    audio.generate(buffer, 256 * 8);
                });
        assertEquals(64, audio.busyTracks());
    }
}
