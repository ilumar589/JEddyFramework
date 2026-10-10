package dev.jeddy.audio;

import dev.jeddy.asset.AssetLoader;
import dev.jeddy.asset.DecodeContext;
import dev.jeddy.asset.Vfs;

import java.lang.foreign.MemorySegment;

/**
 * Loads sounds through the asset manager (TASKS M9-01): files from the configured stream threshold
 * up (music, ambience, voice) are <b>streamed</b>, decoding while they play, straight from the pak
 * mapping when the entry is stored uncompressed ({@code MIX_LoadAudioNoCopy} over a {@link Vfs.Pin}
 * that keeps the pak mapped while the clip lives), else from a native copy; smaller ones (effects)
 * are <b>predecoded</b> to PCM once ({@code SDL_IOFromConstMem} plus {@code MIX_LoadAudio_IO}).
 * Cooked paks keep audio stored, since it does not compress further.
 *
 * <p>Thread ownership: {@link #decode} on asset workers (SDL_mixer loading is thread-safe); {@link
 * #unload} on the asset manager's owner thread, which must be the audio game thread.
 */
public final class SoundLoader implements AssetLoader<SoundClip> {
    private final Audio audio;
    private final long streamThreshold;

    /**
     * Creates a loader for {@code audio}, streaming files of at least {@code streamThreshold}
     * bytes.
     */
    public SoundLoader(Audio audio, long streamThreshold) {
        this.audio = audio;
        this.streamThreshold = streamThreshold;
    }

    @Override
    public SoundClip decode(long id, MemorySegment data, DecodeContext context) {
        if (data.byteSize() < streamThreshold) {
            return audio.loadPredecoded(data);
        }
        Vfs.Pin pin = context.pinStored();
        if (pin == null) {
            return audio.loadStreamedCopy(data);
        }
        try {
            return audio.loadStreamed(pin);
        } catch (RuntimeException e) {
            pin.close();
            throw e;
        }
    }

    @Override
    public void unload(SoundClip value) {
        audio.destroy(value);
    }
}
