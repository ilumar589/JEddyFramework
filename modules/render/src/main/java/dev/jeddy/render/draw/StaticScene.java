package dev.jeddy.render.draw;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.asset.format.MaterialData;
import dev.jeddy.core.handle.Handles;
import dev.jeddy.math.CullOps;
import dev.jeddy.render.RenderPacket;
import dev.jeddy.render.gpu.FrameRing;
import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.gpu.StateCache;
import dev.jeddy.render.mesh.MeshRegistry;
import dev.jeddy.render.shader.ProgramCache;
import dev.jeddy.render.shader.ShaderFeatures;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * GPU-driven culling of the static scene (TASKS M7-11, DESIGN §9.2): instances flagged {@link
 * RenderPacket#FLAG_STATIC} live on the GPU, grouped into bins of (program slot, mesh, submesh),
 * each with one indirect command per LOD. Per view, a compute pass culls every (instance, submesh)
 * item against the view's planes, picks its LOD, appends its instance record to its command, and a
 * second pass packs each slot's non-empty commands; the draws are one {@code
 * glMultiDrawElementsIndirectCount} per slot. The main thread's per-frame cost for static instances
 * drops to two bulk copies (flags and camera fade, which may change every frame).
 *
 * <p>Contract: while {@link RenderPacket#staticVersion} stays the same, static instances keep their
 * packet index, transform, mesh and material. The scene rebuilds when the version changes or a
 * material moves to another program slot. Static instances with blended materials stay on the CPU
 * path ({@link #cpuInstances}), which sorts them back to front.
 *
 * <p>Thread ownership: the render thread.
 */
public final class StaticScene implements AutoCloseable {
    /** View index of the main camera. */
    public static final int VIEW_MAIN = 0;

    /** First view index of the sun cascades ({@code VIEW_CASCADES + c}). */
    public static final int VIEW_CASCADES = 1;

    /** Number of views. */
    public static final int VIEWS = 5;

    private static final int ITEM_BYTES = 96;
    private static final int BIN_BYTES = 48;
    private static final int COMMAND_BYTES = 20;
    private static final int MAX_LODS = 8;
    private static final int GROUP = 64;

    private final GpuDevice device;
    private final MeshRegistry meshes;
    private final MaterialTable materials;
    private final ProgramCache programs;
    private final int cullProgram;
    private final int compactProgram;
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment planes = arena.allocate(CullOps.PLANES_FLOATS * 4L, 16);

    private int builtVersion = Integer.MIN_VALUE;
    private int builtSlotVersion = Integer.MIN_VALUE;
    private boolean built;

    // Scene (rebuilt on change).
    private int itemCount;
    private int commandCount;
    private int batchCount;
    private int recordCount;
    private int[] batchSlot = new int[0];
    private int[] batchFirst = new int[0];
    private int[] batchSize = new int[0];
    private int[] cpuInstances = new int[0];
    private int cpuCount;
    private int staticCount;
    private int itemBuffer = Handles.NULL;
    private int binBuffer = Handles.NULL;
    private int templateBuffer = Handles.NULL;
    private int batchBuffer = Handles.NULL;
    private final int[] viewCommands = new int[VIEWS];
    private final int[] viewRecords = new int[VIEWS];
    private long packedOffsetBytes;
    private long countsOffsetBytes;
    private long commandsBytes;

    // Frame.
    private long flagsOffset = -1;
    private long fadesOffset;
    private long frameBytes;
    private FrameRing ring;

    /** Creates an empty scene and defines its compute programs. */
    public StaticScene(
            GpuDevice device, ProgramCache programs, MeshRegistry meshes, MaterialTable materials) {
        this.device = device;
        this.programs = programs;
        this.meshes = meshes;
        this.materials = materials;
        cullProgram =
                programs.defineCompute("static cull", "cull/static_cull.comp", ShaderFeatures.NONE);
        compactProgram =
                programs.defineCompute(
                        "static compact", "cull/static_compact.comp", ShaderFeatures.NONE);
        Arrays.fill(viewCommands, Handles.NULL);
        Arrays.fill(viewRecords, Handles.NULL);
    }

    /** Returns whether a scene is built and has GPU-culled items. */
    public boolean active() {
        return built && itemCount > 0;
    }

    /** Returns the number of static instances in the scene (GPU and CPU path). */
    public int staticCount() {
        return staticCount;
    }

    /** Returns the number of (instance, submesh) items culled on the GPU. */
    public int itemCount() {
        return itemCount;
    }

    /** Returns the number of indirect commands (bins × LODs). */
    public int commandCount() {
        return commandCount;
    }

    /** Returns the number of multi-draws per view and pass. */
    public int batchCount() {
        return batchCount;
    }

    /**
     * Returns the static instances drawn by the CPU path (blended materials), {@link #cpuCount} of
     * them. Callers cull them with the dynamic instances.
     */
    public int[] cpuInstances() {
        return cpuInstances;
    }

    /** Returns the number of entries in {@link #cpuInstances}. */
    public int cpuCount() {
        return cpuCount;
    }

    /**
     * Rebuilds the scene if the packet's static version or the material slots changed, then copies
     * this frame's flags and camera fades into {@code frameRing}. Allocates only when rebuilding.
     */
    public void update(RenderPacket p, FrameRing frameRing) {
        ring = frameRing;
        if (!built
                || p.staticVersion != builtVersion
                || materials.slotVersion() != builtSlotVersion) {
            rebuild(p);
        }
        flagsOffset = -1;
        if (!active()) {
            return;
        }
        int n = p.count();
        frameBytes = Math.max(16, (long) n * 4);
        int align = device.caps().shaderStorageBufferOffsetAlignment();
        flagsOffset = frameRing.allocate(frameBytes, align);
        fadesOffset = frameRing.allocate(frameBytes, align);
        if (flagsOffset < 0 || fadesOffset < 0) {
            flagsOffset = -1;
            return;
        }
        MemorySegment m = frameRing.memory();
        MemorySegment.copy(p.flags, 0, m, ValueLayout.JAVA_INT, flagsOffset, n);
        MemorySegment.copy(p.fade, 0, m, ValueLayout.JAVA_FLOAT, fadesOffset, n);
    }

    private void rebuild(RenderPacket p) {
        release();
        built = true;
        builtVersion = p.staticVersion;
        builtSlotVersion = materials.slotVersion();
        int n = p.count();
        // Classify: GPU items or CPU-path instances.
        int[] gpu = new int[n];
        int gpuCount = 0;
        int[] cpu = new int[16];
        int cpus = 0;
        int statics = 0;
        Map<Long, Integer> binOfKey = new HashMap<>();
        long[] binKeys = new long[64];
        int[] binMesh = new int[64];
        int bins = 0;
        int items = 0;
        for (int i = 0; i < n; i++) {
            if ((p.flags[i] & RenderPacket.FLAG_STATIC) == 0) {
                continue;
            }
            statics++;
            int mesh = p.mesh[i];
            boolean blended = (p.flags[i] & RenderPacket.FLAG_TRANSLUCENT) != 0;
            if (meshes.isValid(mesh)) {
                for (int s = 0; s < meshes.submeshCount(mesh) && !blended; s++) {
                    long mask = materials.slotMask(materials.programSlot(materialOf(p, i, s)));
                    blended = (mask & MaterialData.FEATURE_ALPHA_BLEND) != 0;
                }
            }
            if (blended || !meshes.isValid(mesh)) {
                if (cpus == cpu.length) {
                    cpu = Arrays.copyOf(cpu, cpus * 2);
                }
                cpu[cpus++] = i;
                continue;
            }
            gpu[gpuCount++] = i;
            for (int s = 0; s < meshes.submeshCount(mesh); s++) {
                long key = binKey(p, i, s);
                if (!binOfKey.containsKey(key)) {
                    if (bins == binKeys.length) {
                        binKeys = Arrays.copyOf(binKeys, bins * 2);
                        binMesh = Arrays.copyOf(binMesh, bins * 2);
                    }
                    binKeys[bins] = key;
                    binMesh[bins] = mesh;
                    binOfKey.put(key, bins++);
                }
                items++;
            }
        }
        staticCount = statics;
        cpuInstances = cpu;
        cpuCount = cpus;
        itemCount = items;
        if (items == 0) {
            return;
        }
        // Bins in key order (slot first), so each slot's commands are contiguous.
        long[] sorted = Arrays.copyOf(binKeys, bins);
        Arrays.sort(sorted);
        int[] binIndex = new int[bins]; // original bin id -> sorted position
        int[] sortedMesh = new int[bins];
        for (int b = 0; b < bins; b++) {
            int original = binOfKey.get(sorted[b]);
            binIndex[original] = b;
            sortedMesh[b] = binMesh[original];
        }
        int[] binCount = new int[bins];
        int[] itemBin = new int[items];
        int k = 0;
        for (int g = 0; g < gpuCount; g++) {
            int i = gpu[g];
            for (int s = 0; s < meshes.submeshCount(p.mesh[i]); s++) {
                int b = binIndex[binOfKey.get(binKey(p, i, s))];
                itemBin[k++] = b;
                binCount[b]++;
            }
        }
        // Commands and records: per bin, one command per LOD and count records per LOD.
        int[] binLods = new int[bins];
        int[] binFirstCommand = new int[bins];
        int[] binFirstRecord = new int[bins];
        int commands = 0;
        int records = 0;
        int batches = 0;
        int[] slots = new int[bins];
        int[] firsts = new int[bins];
        int[] sizes = new int[bins];
        int[] commandBatch = new int[bins * MAX_LODS];
        for (int b = 0; b < bins; b++) {
            int mesh = sortedMesh[b];
            int lods = Math.min(MAX_LODS, meshes.lodCount(mesh));
            int slot = (int) (sorted[b] >>> 40);
            if (batches == 0 || slots[batches - 1] != slot) {
                slots[batches] = slot;
                firsts[batches] = commands;
                batches++;
            }
            binLods[b] = lods;
            binFirstCommand[b] = commands;
            binFirstRecord[b] = records;
            for (int l = 0; l < lods; l++) {
                commandBatch[commands + l] = batches - 1;
            }
            sizes[batches - 1] += lods;
            commands += lods;
            records += lods * binCount[b];
        }
        commandCount = commands;
        recordCount = records;
        batchCount = batches;
        batchSlot = Arrays.copyOf(slots, batches);
        batchFirst = Arrays.copyOf(firsts, batches);
        batchSize = Arrays.copyOf(sizes, batches);
        try (Arena a = Arena.ofConfined()) {
            // Items.
            MemorySegment it = a.allocate((long) items * ITEM_BYTES, 16);
            k = 0;
            for (int g = 0; g < gpuCount; g++) {
                int i = gpu[g];
                int mesh = p.mesh[i];
                int w = i * 12;
                float[] world = p.currWorld;
                float scale =
                        (float)
                                Math.sqrt(
                                        world[w] * world[w]
                                                + world[w + 4] * world[w + 4]
                                                + world[w + 8] * world[w + 8]);
                for (int s = 0; s < meshes.submeshCount(mesh); s++) {
                    long o = (long) k * ITEM_BYTES;
                    MemorySegment.copy(world, w, it, ValueLayout.JAVA_FLOAT, o, 12);
                    MemorySegment.copy(p.bounds, i * 4, it, ValueLayout.JAVA_FLOAT, o + 48, 4);
                    it.set(ValueLayout.JAVA_INT, o + 64, i);
                    it.set(ValueLayout.JAVA_INT, o + 68, itemBin[k]);
                    it.set(ValueLayout.JAVA_INT, o + 72, materialOf(p, i, s));
                    it.set(ValueLayout.JAVA_INT, o + 76, p.entity[i]);
                    it.set(ValueLayout.JAVA_FLOAT, o + 80, scale);
                    k++;
                }
            }
            itemBuffer = device.buffers().create(it, 0, "static items");
            // Bins.
            MemorySegment bn = a.allocate((long) bins * BIN_BYTES, 16);
            MemorySegment tpl = a.allocate((long) commands * COMMAND_BYTES, 16);
            for (int b = 0; b < bins; b++) {
                long o = (long) b * BIN_BYTES;
                int mesh = sortedMesh[b];
                int submesh = (int) (sorted[b] & 0xFF);
                bn.set(ValueLayout.JAVA_INT, o, binLods[b]);
                bn.set(ValueLayout.JAVA_INT, o + 4, binCount[b]);
                bn.set(ValueLayout.JAVA_INT, o + 8, binFirstRecord[b]);
                bn.set(ValueLayout.JAVA_INT, o + 12, binFirstCommand[b]);
                for (int l = 0; l < MAX_LODS; l++) {
                    float e = l < binLods[b] ? meshes.lodError(mesh, l) : Float.MAX_VALUE;
                    bn.set(ValueLayout.JAVA_FLOAT, o + 16 + l * 4L, e);
                }
                for (int l = 0; l < binLods[b]; l++) {
                    long c = (long) (binFirstCommand[b] + l) * COMMAND_BYTES;
                    tpl.set(ValueLayout.JAVA_INT, c, meshes.indexCount(mesh, l, submesh));
                    tpl.set(ValueLayout.JAVA_INT, c + 4, 0);
                    tpl.set(ValueLayout.JAVA_INT, c + 8, meshes.firstIndex(mesh, l, submesh));
                    tpl.set(ValueLayout.JAVA_INT, c + 12, meshes.baseVertex(mesh, submesh));
                    tpl.set(ValueLayout.JAVA_INT, c + 16, binFirstRecord[b] + l * binCount[b]);
                }
            }
            binBuffer = device.buffers().create(bn, 0, "static bins");
            templateBuffer = device.buffers().create(tpl, 0, "static command template");
            // Batch table: command -> batch, then each batch's first packed slot.
            MemorySegment bt = a.allocate((long) (commands + batches) * 4, 16);
            for (int c = 0; c < commands; c++) {
                bt.set(ValueLayout.JAVA_INT, c * 4L, commandBatch[c]);
            }
            for (int b = 0; b < batches; b++) {
                bt.set(ValueLayout.JAVA_INT, (long) (commands + b) * 4, batchFirst[b]);
            }
            batchBuffer = device.buffers().create(bt, 0, "static batches");
        }
        // Per view: commands, packed commands, counts; records.
        commandsBytes = (long) commands * COMMAND_BYTES;
        packedOffsetBytes = commandsBytes;
        countsOffsetBytes = 2 * commandsBytes;
        long viewBytes = countsOffsetBytes + (long) batches * 4;
        for (int v = 0; v < VIEWS; v++) {
            viewCommands[v] = device.buffers().create(viewBytes, 0, "static commands " + v);
            viewRecords[v] =
                    device.buffers()
                            .create(
                                    (long) records * DrawBuilder.INSTANCE_BYTES,
                                    0,
                                    "static records " + v);
        }
    }

    private int materialOf(RenderPacket p, int i, int submesh) {
        int m = p.material[i];
        return m >= 0 ? m : meshes.submeshMaterialIndex(p.mesh[i], submesh);
    }

    /** Bin key: program slot (bits 40+), mesh handle index (8..39), submesh (0..7). */
    private long binKey(RenderPacket p, int i, int submesh) {
        int slot = materials.programSlot(materialOf(p, i, submesh));
        int mesh = p.mesh[i];
        return (long) slot << 40 | (long) Handles.index(mesh) << 8 | submesh;
    }

    /**
     * Culls the static scene for view {@code view} (a compute pass; call inside a frame graph pass
     * before drawing it): {@code frustumPlanes} as from {@link CullOps#extractPlanes}, LOD from the
     * distance to {@code eye} with {@code lodPixels} pixels per unit, only items whose instance has
     * all {@code requiredFlags}.
     */
    public void cull(
            StateCache state,
            int view,
            float[] frustumPlanes,
            float[] eye,
            float lodPixels,
            boolean orthographic,
            int requiredFlags) {
        if (!active() || flagsOffset < 0) {
            return;
        }
        int cull = programs.getBlocking(cullProgram, 0);
        int compact = programs.getBlocking(compactProgram, 0);
        int commands = device.buffers().glName(viewCommands[view]);
        // Reset the counts: the template has no instances; batches have no packed commands.
        glCopyNamedBufferSubData(
                device.buffers().glName(templateBuffer), commands, 0, 0, commandsBytes);
        glClearNamedBufferSubData(
                commands,
                GL_R32UI,
                countsOffsetBytes,
                (long) batchCount * 4,
                GL_RED_INTEGER,
                GL_UNSIGNED_INT,
                MemorySegment.NULL);
        state.useProgram(cull);
        MemorySegment.copy(frustumPlanes, 0, planes, ValueLayout.JAVA_FLOAT, 0, 24);
        glProgramUniform4fv(cull, 0, 6, planes);
        glProgramUniform4f(cull, 6, eye[0], eye[1], eye[2], lodPixels);
        glProgramUniform4ui(
                cull, 7, itemCount, orthographic ? 1 : 0, requiredFlags, Float.floatToIntBits(1f));
        state.bindStorage(
                DrawBuilder.INSTANCE_BINDING,
                device.buffers().glName(viewRecords[view]),
                0,
                (long) recordCount * DrawBuilder.INSTANCE_BYTES);
        state.bindStorage(2, device.buffers().glName(itemBuffer), 0, (long) itemCount * ITEM_BYTES);
        state.bindStorage(
                3, device.buffers().glName(binBuffer), 0, device.buffers().size(binBuffer));
        state.bindStorage(4, commands, 0, device.buffers().size(viewCommands[view]));
        state.bindStorage(5, ring.glName(), flagsOffset, frameBytes);
        state.bindStorage(6, ring.glName(), fadesOffset, frameBytes);
        glDispatchCompute((itemCount + GROUP - 1) / GROUP, 1, 1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        state.useProgram(compact);
        glProgramUniform4ui(
                compact,
                0,
                commandCount,
                (int) (packedOffsetBytes / 4),
                (int) (countsOffsetBytes / 4),
                commandCount);
        state.bindStorage(
                3, device.buffers().glName(batchBuffer), 0, device.buffers().size(batchBuffer));
        glDispatchCompute((commandCount + GROUP - 1) / GROUP, 1, 1);
        glMemoryBarrier(GL_COMMAND_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
    }

    /**
     * Draws view {@code view}'s culled items, one {@code glMultiDrawElementsIndirectCount} per
     * program slot the binder accepts. Allocates nothing.
     */
    public void submit(StateCache state, int view, DrawBuilder.BatchBinder binder) {
        if (!active() || flagsOffset < 0) {
            return;
        }
        int commands = device.buffers().glName(viewCommands[view]);
        state.bindVertexArray(meshes.vertexArray());
        state.bindStorage(
                DrawBuilder.INSTANCE_BINDING,
                device.buffers().glName(viewRecords[view]),
                0,
                (long) recordCount * DrawBuilder.INSTANCE_BYTES);
        state.bindStorage(
                DrawBuilder.MATERIAL_BINDING, materials.glBuffer(), 0, materials.bufferBytes());
        state.bindDrawIndirect(commands);
        glBindBuffer(GL_PARAMETER_BUFFER, commands);
        for (int b = 0; b < batchCount; b++) {
            if (!binder.bind(state, batchSlot[b], false)) {
                continue;
            }
            glMultiDrawElementsIndirectCount(
                    GL_TRIANGLES,
                    GL_UNSIGNED_INT,
                    packedOffsetBytes + (long) batchFirst[b] * COMMAND_BYTES,
                    countsOffsetBytes + b * 4L,
                    batchSize[b],
                    COMMAND_BYTES);
        }
        glBindBuffer(GL_PARAMETER_BUFFER, 0);
    }

    /**
     * Reads back the instance count of every command of view {@code view} after its last cull
     * (tests and debug views: stalls and allocates). Commands are ordered by bin, then LOD.
     */
    public int[] readInstanceCounts(int view) {
        int[] out = new int[commandCount];
        if (commandCount == 0) {
            return out;
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment m = a.allocate(commandsBytes, 16);
            glGetNamedBufferSubData(
                    device.buffers().glName(viewCommands[view]), 0, commandsBytes, m);
            for (int c = 0; c < commandCount; c++) {
                out[c] = m.get(ValueLayout.JAVA_INT, c * (long) COMMAND_BYTES + 4);
            }
        }
        return out;
    }

    private void release() {
        int[] single = {itemBuffer, binBuffer, templateBuffer, batchBuffer};
        for (int h : single) {
            if (h != Handles.NULL) {
                device.buffers().destroy(h);
            }
        }
        itemBuffer = binBuffer = templateBuffer = batchBuffer = Handles.NULL;
        for (int v = 0; v < VIEWS; v++) {
            if (viewCommands[v] != Handles.NULL) {
                device.buffers().destroy(viewCommands[v]);
                device.buffers().destroy(viewRecords[v]);
                viewCommands[v] = Handles.NULL;
                viewRecords[v] = Handles.NULL;
            }
        }
        itemCount = 0;
        commandCount = 0;
        batchCount = 0;
    }

    @Override
    public void close() {
        release();
        arena.close();
    }
}
