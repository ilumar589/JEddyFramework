package dev.jeddy.render.gpu;

import static dev.jeddy.gl.ffi.GL46.*;

import java.lang.foreign.MemorySegment;

/**
 * The staging ring for uploads (TASKS M5-04, DESIGN §9.2): a persistently mapped buffer that asset
 * uploads copy into, followed by GPU-side copies ({@code glCopyNamedBufferSubData}, or a texture
 * upload from the bound pixel-unpack buffer). Space is reclaimed in order once the fence of the
 * frame that consumed it signals, and a per-frame byte budget keeps streaming from spiking a frame.
 *
 * <p>Thread ownership: the GL thread.
 */
public final class UploadRing implements AutoCloseable {
    private static final int MAX_FRAMES = 16;

    private final GpuDevice device;
    private final int buffer;
    private final int glName;
    private final MemorySegment memory;
    private final long capacity;
    private long head; // oldest byte still in use (logical)
    private long tail; // next free byte (logical)
    private final long[] frameEnd = new long[MAX_FRAMES];
    private final long[] frameFence = new long[MAX_FRAMES];
    private int pendingFirst;
    private int pendingCount;
    private long budget;
    private long usedThisFrame;
    private long uploadedBytes;

    /**
     * Creates the ring.
     *
     * @param budgetPerFrame bytes that {@link #reserve} hands out per frame before it refuses
     */
    public UploadRing(GpuDevice device, long capacity, long budgetPerFrame) {
        this.device = device;
        this.capacity = capacity;
        this.budget = budgetPerFrame;
        buffer = device.buffers().create(capacity, GpuBuffers.MAPPED, "upload ring");
        glName = device.buffers().glName(buffer);
        memory = device.buffers().mapping(buffer);
    }

    /** Starts a frame: reclaims the space of frames the GPU has finished, resets the budget. */
    public void beginFrame() {
        while (pendingCount > 0) {
            long fence = frameFence[pendingFirst];
            int status = glClientWaitSync(fence, 0, 0);
            if (status == GL_TIMEOUT_EXPIRED) {
                break;
            }
            glDeleteSync(fence);
            head = frameEnd[pendingFirst];
            pendingFirst = (pendingFirst + 1) % MAX_FRAMES;
            pendingCount--;
        }
        usedThisFrame = 0;
    }

    /**
     * Reserves {@code bytes} at {@code alignment} (a power of two). Returns the offset into {@link
     * #memory()} and the buffer, or -1 if the ring is full or the frame's budget is spent (try next
     * frame). A reservation larger than the budget is granted when it is the frame's first, so
     * large assets still make progress.
     */
    public long reserve(long bytes, long alignment) {
        if (bytes > capacity) {
            throw new IllegalArgumentException(bytes + " bytes exceed the upload ring");
        }
        if (usedThisFrame > 0 && usedThisFrame + bytes > budget) {
            return -1;
        }
        long start = (tail + alignment - 1) & -alignment;
        long physical = start % capacity;
        if (physical + bytes > capacity) {
            start += capacity - physical; // skip to the start of the buffer
        }
        if (start + bytes - head > capacity) {
            return -1;
        }
        tail = start + bytes;
        usedThisFrame += bytes;
        uploadedBytes += bytes;
        return start % capacity;
    }

    /** Copies {@code bytes} from {@code ringOffset} into a buffer at {@code dstOffset}. */
    public void copyToBuffer(long ringOffset, int dstBufferName, long dstOffset, long bytes) {
        glCopyNamedBufferSubData(glName, dstBufferName, ringOffset, dstOffset, bytes);
    }

    /**
     * Uploads a texture level from {@code ringOffset} through the pixel-unpack binding. The ring is
     * mapped write-only, so a compressed array's tail cannot be mirrored from here and {@link
     * GpuTextures#copyLayers} falls back to a readback for that texture; the material arrays are
     * uploaded from client memory instead.
     */
    public void copyToTexture(int textureHandle, int level, long ringOffset, int bytes) {
        device.textures().uploadLevelFromBuffer(textureHandle, level, glName, ringOffset, bytes);
    }

    /** Uploads one level of one array layer from {@code ringOffset} through the unpack binding. */
    public void copyToTextureLayer(
            int textureHandle, int level, int layer, long ringOffset, int bytes) {
        device.textures()
                .uploadLayerFromBuffer(textureHandle, level, layer, glName, ringOffset, bytes);
    }

    /** Ends the frame: fences what this frame copied. */
    public void endFrame() {
        if (usedThisFrame == 0) {
            return;
        }
        if (pendingCount == MAX_FRAMES) { // the GPU is far behind: wait for the oldest
            glClientWaitSync(frameFence[pendingFirst], GL_SYNC_FLUSH_COMMANDS_BIT, 5_000_000_000L);
            beginFrameReclaimOnly();
        }
        int slot = (pendingFirst + pendingCount) % MAX_FRAMES;
        frameEnd[slot] = tail;
        frameFence[slot] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        pendingCount++;
    }

    private void beginFrameReclaimOnly() {
        long used = usedThisFrame;
        beginFrame();
        usedThisFrame = used;
    }

    /** Returns the mapped memory (write only). */
    public MemorySegment memory() {
        return memory;
    }

    /** Returns the per-frame budget in bytes. */
    public long budget() {
        return budget;
    }

    /** Sets the per-frame budget in bytes. */
    public void setBudget(long bytesPerFrame) {
        budget = bytesPerFrame;
    }

    /** Returns the bytes reserved this frame. */
    public long usedThisFrame() {
        return usedThisFrame;
    }

    /** Returns the bytes in flight (reserved and not yet reclaimed). */
    public long inFlight() {
        return tail - head;
    }

    /** Returns the total bytes uploaded. */
    public long uploadedBytes() {
        return uploadedBytes;
    }

    /** Waits for the GPU and deletes the buffer. */
    @Override
    public void close() {
        while (pendingCount > 0) {
            glClientWaitSync(frameFence[pendingFirst], GL_SYNC_FLUSH_COMMANDS_BIT, 5_000_000_000L);
            glDeleteSync(frameFence[pendingFirst]);
            pendingFirst = (pendingFirst + 1) % MAX_FRAMES;
            pendingCount--;
        }
        device.buffers().destroy(buffer);
    }
}
