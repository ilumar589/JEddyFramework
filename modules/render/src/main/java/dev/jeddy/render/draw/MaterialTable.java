package dev.jeddy.render.draw;

import dev.jeddy.render.gpu.GpuBuffers;
import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.shader.ProgramCache;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

/**
 * Materials as rows of a shader storage buffer (TASKS M5-08, DESIGN §9.2): 32 floats per material
 * (the layout of {@code engine/material.glsl}: PBR factors, UV transform, texture layers), indexed
 * by the material index in each instance record. Each material also names a program variant ({@link
 * ProgramCache} definition and mask) and a texture binding set through a small program slot, which
 * is what the draw sort keys and batches use: every draw of a batch shares its program and its
 * texture arrays.
 *
 * <p>Thread ownership: the GL thread; draw builders on workers read {@link #programSlot}.
 */
public final class MaterialTable implements AutoCloseable {
    /** Floats per material row. */
    public static final int FLOATS = 32;

    private final GpuDevice device;
    private final ProgramCache programs;
    private final Arena arena = Arena.ofConfined();
    private MemorySegment cpu;
    private int buffer;
    private int capacity;
    private int count;
    private int[] slotOfMaterial;
    private int dirtyFrom = Integer.MAX_VALUE;
    private int dirtyTo = -1;

    // Program slots: (definition, mask, texture set) triples.
    private int slotCount;
    private int[] slotDefinition = new int[16];
    private long[] slotMask = new long[16];
    private int[] slotTextureSet = new int[16];

    /** Creates a table for {@code capacity} materials (grows). */
    public MaterialTable(GpuDevice device, ProgramCache programs, int capacity) {
        this.device = device;
        this.programs = programs;
        this.capacity = Math.max(16, capacity);
        cpu = arena.allocate((long) this.capacity * FLOATS * 4, 16);
        buffer = device.buffers().create(cpu.byteSize(), GpuBuffers.DYNAMIC, "materials");
        slotOfMaterial = new int[this.capacity];
    }

    /**
     * Adds a material drawn with program {@code definition} variant {@code mask}.
     *
     * @param params {@link #FLOATS} floats for the material's row
     * @return the material index
     */
    public int add(int definition, long mask, float[] params) {
        return add(definition, mask, 0, params);
    }

    /**
     * Adds a material drawn with program {@code definition} variant {@code mask} and the texture
     * arrays of binding set {@code textureSet} (a caller-defined id).
     *
     * @return the material index
     */
    public int add(int definition, long mask, int textureSet, float[] params) {
        if (count == capacity) {
            grow();
        }
        int m = count++;
        slotOfMaterial[m] = slot(definition, mask, textureSet);
        set(m, params);
        return m;
    }

    /** Changes the program variant or texture set of a material (textures arrived, settings). */
    public void setSlot(int material, int definition, long mask, int textureSet) {
        slotOfMaterial[material] = slot(definition, mask, textureSet);
    }

    /** Returns the program definition of a slot. */
    public int slotDefinition(int slot) {
        return slotDefinition[slot];
    }

    /** Returns the variant mask of a slot. */
    public long slotMask(int slot) {
        return slotMask[slot];
    }

    /** Returns the texture binding set of a slot. */
    public int slotTextureSet(int slot) {
        return slotTextureSet[slot];
    }

    /** Replaces a material's row. */
    public void set(int material, float[] params) {
        MemorySegment.copy(
                params, 0, cpu, ValueLayout.JAVA_FLOAT, (long) material * FLOATS * 4, FLOATS);
        dirtyFrom = Math.min(dirtyFrom, material);
        dirtyTo = Math.max(dirtyTo, material);
    }

    /** Returns the number of materials. */
    public int count() {
        return count;
    }

    /** Returns the program slot of a material (12 bits fit the sort key). */
    public int programSlot(int material) {
        return slotOfMaterial[material];
    }

    /** Returns the number of program slots. */
    public int programSlots() {
        return slotCount;
    }

    /** Returns the GL program of a slot, or 0 while it compiles. */
    public int glProgram(int slot) {
        return programs.get(slotDefinition[slot], slotMask[slot]);
    }

    /** Uploads changed rows. Call before drawing. */
    public void flush() {
        if (dirtyTo < 0) {
            return;
        }
        long from = (long) dirtyFrom * FLOATS * 4;
        long length = (long) (dirtyTo - dirtyFrom + 1) * FLOATS * 4;
        device.buffers().update(buffer, from, cpu.address() + from, length);
        dirtyFrom = Integer.MAX_VALUE;
        dirtyTo = -1;
    }

    /** Returns the GL buffer name (bind as an SSBO). */
    public int glBuffer() {
        return device.buffers().glName(buffer);
    }

    /** Returns the bytes in use. */
    public long byteSize() {
        return (long) Math.max(1, count) * FLOATS * 4;
    }

    private int slot(int definition, long mask, int textureSet) {
        for (int s = 0; s < slotCount; s++) {
            if (slotDefinition[s] == definition
                    && slotMask[s] == mask
                    && slotTextureSet[s] == textureSet) {
                return s;
            }
        }
        if (slotCount == 4096) {
            throw new IllegalStateException("more than 4096 program variants in use");
        }
        if (slotCount == slotDefinition.length) {
            slotDefinition = Arrays.copyOf(slotDefinition, slotCount * 2);
            slotMask = Arrays.copyOf(slotMask, slotCount * 2);
            slotTextureSet = Arrays.copyOf(slotTextureSet, slotCount * 2);
        }
        slotDefinition[slotCount] = definition;
        slotMask[slotCount] = mask;
        slotTextureSet[slotCount] = textureSet;
        return slotCount++;
    }

    private void grow() {
        int cap = capacity * 2;
        MemorySegment bigger = arena.allocate((long) cap * FLOATS * 4, 16);
        bigger.copyFrom(cpu);
        cpu = bigger;
        device.buffers().destroy(buffer);
        buffer = device.buffers().create(cpu.byteSize(), GpuBuffers.DYNAMIC, "materials");
        slotOfMaterial = Arrays.copyOf(slotOfMaterial, cap);
        capacity = cap;
        dirtyFrom = 0;
        dirtyTo = count - 1;
    }

    @Override
    public void close() {
        device.buffers().destroy(buffer);
        arena.close();
    }
}
