package dev.jeddy.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.AssetManager;
import dev.jeddy.asset.AssetState;
import dev.jeddy.asset.AssetType;
import dev.jeddy.asset.Vfs;
import dev.jeddy.asset.pak.PakWriter;
import dev.jeddy.asset.pak.PakWriter.Compression;
import dev.jeddy.core.job.JobSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

/** Loading from paks: predecoded effects, zero-copy streamed music (TASKS M9-01). */
class SoundLoaderTest {
    @TempDir Path dir;

    /** A 16-bit mono WAV of a sine tone. */
    static byte[] wav(int hz, double seconds, int rate) {
        int frames = (int) (seconds * rate);
        ByteBuffer b = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46464952).putInt(36 + frames * 2).putInt(0x45564157); // RIFF, size, WAVE
        b.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 1); // fmt, PCM, mono
        b.putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16);
        b.putInt(0x61746164).putInt(frames * 2); // data
        for (int i = 0; i < frames; i++) {
            b.putShort((short) (Math.sin(2 * Math.PI * hz * i / rate) * 12000));
        }
        return b.array();
    }

    @Test
    void effectsPredecodeAndMusicStreamsFromThePakMapping() throws Exception {
        Path pak = dir.resolve("audio.jpak");
        try (PakWriter w = new PakWriter(3)) {
            w.add("sfx/blip.wav", wav(800, 0.1, 48_000), Compression.STORE);
            w.add("music/theme.wav", wav(220, 3, 48_000), Compression.STORE);
            w.write(pak);
        }
        try (Audio audio = Audio.open(new AudioConfig().offline().format(48_000, 2));
                JobSystem jobs = new JobSystem(2);
                Vfs vfs = new Vfs();
                Arena arena = Arena.ofConfined()) {
            int mount = vfs.mountPak(pak);
            try (AssetManager assets = new AssetManager(vfs, jobs)) {
                AssetType<SoundClip> sounds =
                        assets.register("sound", new SoundLoader(audio, 64 * 1024));
                int blip = sounds.load("sfx/blip.wav");
                int theme = sounds.load("music/theme.wav");
                long deadline = System.nanoTime() + 10_000_000_000L;
                while (!sounds.isComplete(blip) || !sounds.isComplete(theme)) {
                    assets.update();
                    assertTrue(System.nanoTime() < deadline, "load timed out");
                    Thread.onSpinWait();
                }
                assertEquals(
                        AssetState.READY, sounds.state(blip), String.valueOf(sounds.error(blip)));
                assertEquals(
                        AssetState.READY, sounds.state(theme), String.valueOf(sounds.error(theme)));
                SoundClip fx = sounds.get(blip);
                SoundClip music = sounds.get(theme);
                assertFalse(fx.isStreamed());
                assertTrue(music.isStreamed(), "3 s of PCM is over the threshold");
                assertEquals(3, music.seconds(), 0.01);
                int v = audio.play(music, Audio.BUS_MUSIC, 1, 0, -1, 0, 0);
                audio.update(1 / 60f);
                MemorySegment buf = arena.allocate(4800 * 8, 16);
                assertTrue(audio.generate(buf, 4800 * 8) > 0);
                // The pak goes away; the stream keeps reading its pinned mapping.
                vfs.unmount(mount);
                assertTrue(audio.generate(buf, 4800 * 8) > 0);
                double sum = 0;
                for (int i = 0; i < 9600; i++) {
                    float s = buf.getAtIndex(ValueLayout.JAVA_FLOAT, i);
                    sum += s * s;
                }
                assertTrue(Math.sqrt(sum / 9600) > 0.1, "still playing the theme");
                sounds.release(theme);
                sounds.release(blip);
                assets.update();
                assets.update();
                assertTrue(music.isDestroyed(), "unloaded: voices stopped, pin released");
                assertEquals(Audio.VOICE_NONE, audio.voiceState(v));
            }
        }
    }
}
