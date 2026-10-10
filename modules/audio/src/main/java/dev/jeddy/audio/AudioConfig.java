package dev.jeddy.audio;

/**
 * Settings for {@link Audio#open}: the voice pool, the output format and loading policy.
 *
 * <p>Thread ownership: the thread that opens the audio system.
 */
public final class AudioConfig {
    int tracks = 64;
    int voices = 256;
    int frequency = 48_000;
    int channels = 2;
    boolean device = true;
    String driver;
    long streamThreshold = 192 * 1024;
    float positionThreshold = 0.01f;
    float gainThreshold = 0.002f;

    /** Creates the defaults: 64 tracks, 256 voices, 48 kHz stereo on the default device. */
    public AudioConfig() {}

    /**
     * Sets how many {@code MIX_Track}s (sounds actually mixed) and logical voices (playing or
     * virtual) exist.
     */
    public AudioConfig voices(int mixedTracks, int logicalVoices) {
        if (mixedTracks < 1 || logicalVoices < mixedTracks || logicalVoices > 1 << 16) {
            throw new IllegalArgumentException("need 1 ≤ tracks ≤ voices ≤ 65536");
        }
        tracks = mixedTracks;
        voices = logicalVoices;
        return this;
    }

    /** Sets the output format (the device's request, or the offline mixer's format). */
    public AudioConfig format(int hz, int outputChannels) {
        frequency = hz;
        channels = outputChannels;
        return this;
    }

    /**
     * Mixes without an audio device (tools and tests): the caller pulls samples with {@link
     * Audio#generate}.
     */
    public AudioConfig offline() {
        device = false;
        return this;
    }

    /**
     * Picks SDL's audio driver by name ({@code SDL_AUDIO_DRIVER}), for example {@code "dummy"} on
     * machines without sound hardware; {@code null} lets SDL choose.
     */
    public AudioConfig driver(String name) {
        driver = name;
        return this;
    }

    /**
     * Sets the encoded size from which the asset loader streams a sound (decoding while it plays,
     * from the pak mapping) instead of decoding it once at load. Music, ambience and voice are
     * long; effects are short.
     */
    public AudioConfig streamThreshold(long bytes) {
        streamThreshold = bytes;
        return this;
    }

    /**
     * Sets the change thresholds below which per-frame updates skip their native calls: emitter
     * direction (in listener space, unit length) and gain.
     */
    public AudioConfig thresholds(float direction, float gain) {
        positionThreshold = direction;
        gainThreshold = gain;
        return this;
    }
}
