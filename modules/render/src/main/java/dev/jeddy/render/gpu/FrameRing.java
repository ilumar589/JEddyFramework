package dev.jeddy.render.gpu;

import static dev.jeddy.gl.ffi.GL46.*;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Per-frame dynamic GPU data (TASKS M5-04, DESIGN §5, §9.2): one persistently mapped buffer split
 * into {@code frames} regions (3 by default). Each frame writes only its region, and a fence placed
 * at {@link #endFrame} guards it; {@link #beginFrame} waits on that fence only if the GPU is a
 * whole ring behind.
 *
 * <p>{@link #allocate} is lock-free, so job workers can carve their own ranges (instance data,
 * indirect commands) and write them in parallel. They must write disjoint ranges and never read the
 * mapping back (write-combined memory), and their completion must be published to the main thread
 * through the job system before it draws (§5).
 *
 * <p>Thread ownership: {@link #beginFrame} and {@link #endFrame} on the GL thread; {@link
 * #allocate} and writes to {@link #memory()} on any thread between them.
 */
public final class FrameRing implements AutoCloseable {
    private static final VarHandle CURSOR;

    static {
        try {
            CURSOR = MethodHandles.lookup().findVarHandle(FrameRing.class, "cursor", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final GpuBuffers buffers;
    private final int buffer;
    private final int glName;
    private final MemorySegment memory;
    private final long regionSize;
    private final int frames;
    private final long[] fences;
    private int frame = -1;
    private long regionBase;
    private volatile long cursor;
    private long stalls;
    private long stallNanos;

    /**
     * Creates the ring.
     *
     * @param bytesPerFrame the most a frame may allocate
     * @param frames regions in flight (3 = triple buffering)
     */
    public FrameRing(GpuDevice device, long bytesPerFrame, int frames, String label) {
        this.buffers = device.buffers();
        this.frames = frames;
        long alignment = Math.max(256, device.caps().uniformBufferOffsetAlignment());
        this.regionSize = (bytesPerFrame + alignment - 1) / alignment * alignment;
        buffer = buffers.create(regionSize * frames, GpuBuffers.MAPPED, label);
        glName = buffers.glName(buffer);
        memory = buffers.mapping(buffer);
        fences = new long[frames];
    }

    /**
     * Starts a frame: waits until the GPU has finished with this frame's region (normally it has),
     * then resets the region's allocator. Allocates nothing.
     */
    public void beginFrame() {
        frame = (frame + 1) % frames;
        long fence = fences[frame];
        if (fence != 0) {
            int status = glClientWaitSync(fence, 0, 0);
            if (status == GL_TIMEOUT_EXPIRED) {
                long t0 = System.nanoTime();
                stalls++;
                do {
                    status = glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L);
                } while (status == GL_TIMEOUT_EXPIRED);
                stallNanos += System.nanoTime() - t0;
            }
            glDeleteSync(fence);
            fences[frame] = 0;
        }
        regionBase = frame * regionSize;
        CURSOR.setVolatile(this, 0L);
    }

    /**
     * Reserves {@code bytes} at {@code alignment} (a power of two) in this frame's region. Returns
     * the offset in the buffer (for binding and indirect offsets; the same offset into {@link
     * #memory()}), or -1 if the region is full. Lock-free.
     */
    public long allocate(long bytes, long alignment) {
        while (true) {
            long c = cursor;
            long start = (c + alignment - 1) & -alignment;
            long end = start + bytes;
            if (end > regionSize) {
                return -1;
            }
            if (CURSOR.compareAndSet(this, c, end)) {
                return regionBase + start;
            }
        }
    }

    /**
     * Uploads {@code bytes} of {@code src} from {@code srcOffset} into the GL buffer {@code
     * dstGlName} at {@code dstOffset} through this frame's region: the bytes are staged in the ring
     * and copied by the GPU ({@code glCopyNamedBufferSubData}), so the write is ordered in the
     * command stream after the frames still reading the buffer. This is how a buffer the GPU has
     * already used must be changed: Intel's Windows driver ignored {@code glNamedBufferSubData} on
     * such buffers (new material rows read as zeros, D103). Allocates nothing.
     *
     * @return {@code false} if the region is full; the caller keeps the data dirty for the next
     *     frame
     */
    public boolean upload(
            int dstGlName, long dstOffset, MemorySegment src, long srcOffset, long bytes) {
        long at = allocate(bytes, 16);
        if (at < 0) {
            return false;
        }
        MemorySegment.copy(src, srcOffset, memory, at, bytes);
        glCopyNamedBufferSubData(glName, dstGlName, at, dstOffset, bytes);
        return true;
    }

    /** Ends the frame: fences the region after the commands that read it. */
    public void endFrame() {
        fences[frame] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }

    /** Returns the mapped memory of the whole buffer (write only). */
    public MemorySegment memory() {
        return memory;
    }

    /** Returns the GL buffer name (bind ranges of it as SSBO, UBO or indirect buffer). */
    public int glName() {
        return glName;
    }

    /** Returns the bytes allocated in the current frame. */
    public long used() {
        return cursor;
    }

    /** Returns the size of one frame's region. */
    public long regionSize() {
        return regionSize;
    }

    /** Returns how often {@link #beginFrame} had to wait for the GPU. */
    public long stalls() {
        return stalls;
    }

    /** Returns the total time spent waiting in {@link #beginFrame}. */
    public long stallNanos() {
        return stallNanos;
    }

    /** Waits for the GPU and deletes the buffer. */
    @Override
    public void close() {
        for (int i = 0; i < frames; i++) {
            if (fences[i] != 0) {
                glClientWaitSync(fences[i], GL_SYNC_FLUSH_COMMANDS_BIT, 5_000_000_000L);
                glDeleteSync(fences[i]);
                fences[i] = 0;
            }
        }
        buffers.destroy(buffer);
    }
}
