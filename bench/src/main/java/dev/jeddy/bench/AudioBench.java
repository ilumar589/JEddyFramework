package dev.jeddy.bench;

import dev.jeddy.audio.Audio;
import dev.jeddy.audio.AudioConfig;
import dev.jeddy.audio.SoundClip;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * The audio frame (TASKS M9-04): {@code Audio.update} with 64 playing 3D voices whose emitters and
 * listener move every frame (gain and direction thresholds, one mixer lock), on an offline mixer so
 * it runs headless. Mixing itself is native and not measured.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class AudioBench {
    private Audio audio;
    private final int[] emitters = new int[64];
    private float t;

    /** Opens an offline mixer with 64 looping positional voices. */
    @Setup(Level.Trial)
    public void setup() {
        audio = Audio.open(new AudioConfig().offline().voices(64, 128));
        SoundClip tone = audio.sine(440, 0.01f, 60);
        for (int i = 0; i < 64; i++) {
            emitters[i] = audio.addEmitter(i % 8 * 3, 0, i / 8 * 3, 1, 100);
            audio.play(tone, Audio.BUS_SFX, 1, 0, -1, 0, emitters[i]);
        }
        audio.update(1 / 60f);
    }

    /** Closes the mixer. */
    @TearDown(Level.Trial)
    public void tearDown() {
        audio.close();
    }

    /** One frame: move every emitter and the listener, then update. */
    @Benchmark
    public int update64Moving() {
        t += 1 / 60f;
        for (int i = 0; i < 64; i++) {
            audio.setEmitter(emitters[i], i % 8 * 3 + (float) Math.sin(t + i), 0, i / 8 * 3);
        }
        audio.setListener((float) Math.cos(t) * 5, 0, 10, 0, -1);
        audio.update(1 / 60f);
        return audio.busyTracks();
    }

    /** One frame with nothing moving (everything below the thresholds). */
    @Benchmark
    public int update64Still() {
        audio.update(1 / 60f);
        return audio.busyTracks();
    }
}
