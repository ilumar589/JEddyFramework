package dev.jeddy.audio;

import dev.jeddy.asset.Vfs;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * A loaded sound ({@code MIX_Audio}): predecoded PCM for short effects, or encoded data decoded
 * while it plays for long music, ambience and voice. Streamed clips read their bytes in place: a
 * pinned pak entry ({@link Vfs.Pin}) or, for loose development files, a native copy owned by the
 * clip.
 *
 * <p>Thread ownership: created on any thread (asset workers); played and destroyed through {@link
 * Audio} on the game thread.
 */
public final class SoundClip {
    final MemorySegment audio;
    final boolean streamed;
    final double seconds;
    private Vfs.Pin pin;
    private Arena owned;
    boolean destroyed;

    SoundClip(MemorySegment audio, boolean streamed, double seconds, Vfs.Pin pin, Arena owned) {
        this.audio = audio;
        this.streamed = streamed;
        this.seconds = seconds;
        this.pin = pin;
        this.owned = owned;
    }

    /** Returns whether the clip decodes while it plays. */
    public boolean isStreamed() {
        return streamed;
    }

    /** Returns the duration in seconds, or {@code +∞} when unknown (some streams). */
    public double seconds() {
        return seconds;
    }

    /** Returns whether {@link Audio#destroy} released it. */
    public boolean isDestroyed() {
        return destroyed;
    }

    /** Releases the bytes a streamed clip reads (after the {@code MIX_Audio} is gone). */
    void releaseData() {
        if (pin != null) {
            pin.close();
            pin = null;
        }
        if (owned != null) {
            owned.close();
            owned = null;
        }
    }
}
