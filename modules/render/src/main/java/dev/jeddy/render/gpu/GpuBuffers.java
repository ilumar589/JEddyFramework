package dev.jeddy.render.gpu;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.core.handle.HandlePool;
import dev.jeddy.core.handle.Handles;
import dev.jeddy.gl.GLDebug;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

/**
 * GPU buffers behind generational handles (TASKS M5-03): immutable storage ({@code
 * glNamedBufferStorage}), optionally persistently mapped for writing (DESIGN §9.2). SoA arrays hold
 * the GL name, size and mapping per handle.
 *
 * <p>Thread ownership: the GL context thread (main thread).
 */
public final class GpuBuffers {
    /** Flag: {@link #update} may write the buffer ({@code GL_DYNAMIC_STORAGE_BIT}). */
    public static final int DYNAMIC = 1;

    /**
     * Flag: persistently and coherently mapped for writing; {@link #mapping} returns the memory.
     * Writers must not read it back (write-combined).
     */
    public static final int MAPPED = 2;

    private final GpuDevice device;
    private final HandlePool handles = new HandlePool(64, Handles.MAX_INDEX + 1);
    private int[] names = new int[64];
    private long[] sizes = new long[64];
    private int[] flags = new int[64];
    private MemorySegment[] mappings = new MemorySegment[64];

    GpuBuffers(GpuDevice device) {
        this.device = device;
    }

    /** Creates a buffer of {@code size} bytes, zero-filled or with undefined content. */
    public int create(long size, int bufferFlags, String label) {
        return create(size, MemorySegment.NULL, bufferFlags, label);
    }

    /** Creates a buffer holding {@code data} (native memory). */
    public int create(MemorySegment data, int bufferFlags, String label) {
        return create(data.byteSize(), data, bufferFlags, label);
    }

    private int create(long size, MemorySegment data, int bufferFlags, String label) {
        if (size <= 0) {
            throw new IllegalArgumentException("buffer size must be positive: " + size);
        }
        MemorySegment id = device.scratch();
        glCreateBuffers(1, id);
        int name = id.get(ValueLayout.JAVA_INT, 0);
        int storage = 0;
        if ((bufferFlags & DYNAMIC) != 0) {
            storage |= GL_DYNAMIC_STORAGE_BIT;
        }
        if ((bufferFlags & MAPPED) != 0) {
            storage |= GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT;
        }
        glNamedBufferStorage(name, size, data, storage);
        MemorySegment mapping = MemorySegment.NULL;
        if ((bufferFlags & MAPPED) != 0) {
            mapping =
                    glMapNamedBufferRange(
                                    name,
                                    0,
                                    size,
                                    GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT)
                            .reinterpret(size);
            if (mapping.address() == 0) {
                glDeleteBuffers(1, id);
                throw new IllegalStateException("glMapNamedBufferRange failed for " + label);
            }
        }
        if (label != null) {
            GLDebug.label(GL_BUFFER, name, label);
        }
        int h = handles.allocate();
        int i = Handles.index(h);
        if (i >= names.length) {
            int cap = Math.max(i + 1, names.length * 2);
            names = Arrays.copyOf(names, cap);
            sizes = Arrays.copyOf(sizes, cap);
            flags = Arrays.copyOf(flags, cap);
            mappings = Arrays.copyOf(mappings, cap);
        }
        names[i] = name;
        sizes[i] = size;
        flags[i] = bufferFlags;
        mappings[i] = mapping;
        return h;
    }

    /** Deletes the buffer (unmapping it first). */
    public void destroy(int handle) {
        int i = check(handle);
        if (mappings[i].address() != 0) {
            glUnmapNamedBuffer(names[i]);
        }
        device.state().forgetBuffer(names[i]);
        MemorySegment id = device.scratch();
        id.set(ValueLayout.JAVA_INT, 0, names[i]);
        glDeleteBuffers(1, id);
        mappings[i] = null;
        names[i] = 0;
        handles.free(handle);
    }

    /** Returns the GL buffer name. */
    public int glName(int handle) {
        return names[check(handle)];
    }

    /** Returns the size in bytes. */
    public long size(int handle) {
        return sizes[check(handle)];
    }

    /** Returns the persistent write mapping ({@link #MAPPED} buffers), else {@code NULL}. */
    public MemorySegment mapping(int handle) {
        return mappings[check(handle)];
    }

    /**
     * Writes {@code length} bytes at native address {@code address} into the buffer at {@code
     * offset} ({@link #DYNAMIC} buffers). Allocates nothing.
     */
    public void update(int handle, long offset, long address, long length) {
        int i = check(handle);
        if (offset < 0 || length < 0 || offset + length > sizes[i]) {
            throw new IndexOutOfBoundsException("update outside the buffer");
        }
        glNamedBufferSubData(names[i], offset, length, address);
    }

    /** Writes {@code data} (native memory) into the buffer at {@code offset}. */
    public void update(int handle, long offset, MemorySegment data) {
        update(handle, offset, data.address(), data.byteSize());
    }

    /** Returns the number of live buffers. */
    public int count() {
        return handles.size();
    }

    void destroyAll() {
        for (int i = 0; i < handles.highWaterMark(); i++) {
            int h = handles.handleAt(i);
            if (h != Handles.NULL) {
                destroy(h);
            }
        }
    }

    private int check(int handle) {
        if (!handles.isValid(handle)) {
            throw new IllegalArgumentException("stale buffer handle " + Handles.toString(handle));
        }
        return Handles.index(handle);
    }
}
