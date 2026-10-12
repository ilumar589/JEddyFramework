package dev.jeddy.save;

import dev.jeddy.core.io.ByteWriter;

/**
 * The capture half of a save (DESIGN §21, D116, D118): the game writes its meta entries and its
 * chunks into one of these on the game thread, between two ticks, and hands it to a {@link SaveJob}
 * that encodes and writes the file elsewhere. The writer keeps its buffers, so a capture that is no
 * larger than the previous one allocates nothing: the game keeps one {@code SaveWriter} for the
 * life of the level and calls {@link #reset} before each capture.
 *
 * <p>A chunk is {@link #chunk opened} with a tag and a version and written through the {@link
 * ByteWriter} that returns; opening the next chunk (or {@link #finish}) closes it by patching its
 * length into its header and padding it to 8 bytes. The meta block is a list of string pairs the
 * slot list reads without decompressing anything.
 *
 * <p>Thread ownership: the game thread during a capture; a {@link SaveJob} then copies the buffers
 * out before the game thread touches the writer again.
 */
public final class SaveWriter {
    /** The chunk header: tag, version, length, reserved. */
    public static final int CHUNK_HEADER_BYTES = 16;

    private final ByteWriter meta;
    private final ByteWriter payload;
    private int metaCount;
    private int chunkCount;
    private int openLengthAt = -1;
    private int openDataStart;

    /** A writer whose payload buffer starts at {@code payloadCapacity} bytes. */
    public SaveWriter(int payloadCapacity) {
        meta = new ByteWriter(256);
        payload = new ByteWriter(payloadCapacity);
        meta.int32(0); // the pair count, patched by finish
    }

    public SaveWriter() {
        this(64 * 1024);
    }

    /** Rewinds both buffers (keeping them) for a new capture. */
    public void reset() {
        meta.reset();
        meta.int32(0);
        payload.reset();
        metaCount = 0;
        chunkCount = 0;
        openLengthAt = -1;
    }

    /** Adds a meta entry (the title, the level, the play time; what a slot list shows). */
    public void meta(String key, String value) {
        meta.string(key);
        meta.string(value);
        metaCount++;
    }

    /**
     * Opens a chunk with {@code tag} (see {@link SaveFile#tag}) and {@code version} and returns the
     * writer for its bytes; the chunk closes when the next one opens or at {@link #finish}.
     */
    public ByteWriter chunk(int tag, int version) {
        closeOpenChunk();
        payload.int32(tag);
        payload.int32(version);
        openLengthAt = payload.size();
        payload.int32(0); // the length, patched at close
        payload.int32(0); // reserved
        openDataStart = payload.size();
        chunkCount++;
        return payload;
    }

    private void closeOpenChunk() {
        if (openLengthAt < 0) {
            return;
        }
        int length = payload.size() - openDataStart;
        payload.patchInt32(openLengthAt, length);
        int pad = (8 - (length & 7)) & 7;
        payload.zeros(pad);
        openLengthAt = -1;
    }

    /** Closes the open chunk and patches the meta count; idempotent. */
    public void finish() {
        closeOpenChunk();
        meta.patchInt32(0, metaCount);
    }

    /** Chunks opened since the reset. */
    public int chunkCount() {
        return chunkCount;
    }

    /** Meta entries since the reset. */
    public int metaCount() {
        return metaCount;
    }

    /** Payload bytes so far (chunk headers, data and padding). */
    public int payloadSize() {
        finishIfOpen();
        return payload.size();
    }

    private void finishIfOpen() {
        if (openLengthAt >= 0) {
            finish();
        }
    }

    /** The meta block's bytes; valid up to {@link #metaSize}. */
    byte[] metaBuffer() {
        finish();
        return meta.buffer();
    }

    int metaSize() {
        finish();
        return meta.size();
    }

    /** The payload's bytes; valid up to {@link #payloadSize}. */
    byte[] payloadBuffer() {
        finish();
        return payload.buffer();
    }
}
