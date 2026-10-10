package dev.jeddy.render.draw;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.core.collections.LongSort;
import dev.jeddy.core.handle.Handles;
import dev.jeddy.core.job.JobKernel;
import dev.jeddy.core.job.JobSystem;
import dev.jeddy.core.job.ParallelSort;
import dev.jeddy.render.RenderPacket;
import dev.jeddy.render.gpu.FrameRing;
import dev.jeddy.render.gpu.PipelineState;
import dev.jeddy.render.gpu.StateCache;
import dev.jeddy.render.mesh.MeshRegistry;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Turns the visible instances of a {@link RenderPacket} into a few multi-draw-indirect calls (TASKS
 * M5-08, DESIGN §9.2):
 *
 * <ol>
 *   <li>Every visible instance expands into one draw item per submesh, at a LOD chosen from its
 *       projected geometric error, with a 64-bit sort key: pass, program slot, mesh, LOD, submesh
 *       and depth (front to back for opaque items, back to front for translucent ones). Workers
 *       build the keys in parallel.
 *   <li>A parallel radix sort orders the items, so identical draws become runs.
 *   <li>Workers write one 64-byte instance record per item (interpolated 3×4 transform, material,
 *       flags, entity) into the {@link FrameRing}, in sorted order; each run becomes one {@code
 *       DrawElementsIndirectCommand} (instance count = run length, base instance = the run's first
 *       record).
 *   <li>{@link #submit} binds the records and materials as storage buffers and issues one {@code
 *       glMultiDrawElementsIndirect} per (pass, program) batch.
 * </ol>
 *
 * The vertex shader finds its record at {@code gl_BaseInstance + gl_InstanceID}. Everything is
 * preallocated: a steady-state frame allocates nothing.
 *
 * <p>Thread ownership: the render (main) thread; {@link #build} borrows job workers.
 */
public final class DrawBuilder {
    /** Bytes per instance record (std430: three {@code vec4} rows, one {@code uvec4}). */
    public static final int INSTANCE_BYTES = 64;

    /** Bytes per indirect command. */
    public static final int COMMAND_BYTES = 20;

    /** Storage binding of the instance records. */
    public static final int INSTANCE_BINDING = 0;

    /** Storage binding of the material table. */
    public static final int MATERIAL_BINDING = 1;

    private static final ValueLayout.OfFloat F32 =
            ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.nativeOrder());
    private static final ValueLayout.OfInt I32 =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.nativeOrder());
    private static final int PASS_OPAQUE = 0;
    private static final int PASS_TRANSLUCENT = 1;
    private static final int GRAIN = 512;
    private static final int PARALLEL_FROM = 2048;

    private final MeshRegistry meshes;
    private final MaterialTable materials;
    private final JobSystem jobs;
    private final ParallelSort sorter;

    // Items (one per visible instance and submesh).
    private int itemCount;
    private long[] keys = new long[0];
    private int[] order = new int[0]; // sorted item → item index
    private int[] itemInstance = new int[0];
    private int[] itemMaterial = new int[0];
    private int[] itemFirst = new int[0]; // first item of each visible instance (prefix sum)
    private byte[] itemLod = new byte[0];

    // Batches.
    private int batchCount;
    private int[] batchSlot = new int[16];
    private int[] batchPass = new int[16];
    private long[] batchOffset = new long[16];
    private int[] batchCommands = new int[16];
    private int commandCount;
    private int[] runStart = new int[0];
    private long[] scratchKeys = new long[0]; // sequential sort only
    private int[] scratchOrder = new int[0];
    private final int[] histogram = new int[2048];

    // Per-frame inputs for the kernels.
    private RenderPacket packet;
    private int[] visible;
    private int visibleCount;
    private MemorySegment memory;
    private long instanceBase;
    private float pixelsPerUnit = 1000;
    private boolean orthographic;
    private float thresholdPixels = 1;
    private long instanceOffset;
    private long instanceBytes;

    private final JobKernel itemKernel = this::buildItems;
    private final JobKernel recordKernel = this::writeRecords;

    /** Creates a builder; {@code jobs} may be {@code null} to build on the calling thread. */
    public DrawBuilder(MeshRegistry meshes, MaterialTable materials, JobSystem jobs) {
        this.meshes = meshes;
        this.materials = materials;
        this.jobs = jobs;
        this.sorter = jobs == null ? null : new ParallelSort(jobs);
    }

    /**
     * Sets LOD selection: a LOD is used while its geometric error projects to at most {@code
     * threshold} pixels.
     *
     * @param pixels for perspective, pixels per world unit at distance 1 ({@code viewportHeight /
     *     (2 tan(fovY / 2))}); for orthographic, pixels per world unit
     */
    public void setLod(float pixels, boolean ortho, float threshold) {
        pixelsPerUnit = pixels;
        orthographic = ortho;
        thresholdPixels = threshold;
    }

    /**
     * Builds the draws for {@code visibleCount} instances of {@code p} (indices from {@link
     * Culling}) into {@code ring}, which must be in its frame.
     *
     * @return {@code false} if the ring had no room (nothing will be drawn)
     */
    public boolean build(RenderPacket p, int[] visibleIndices, int visibleCount, FrameRing ring) {
        packet = p;
        visible = visibleIndices;
        this.visibleCount = visibleCount;
        batchCount = 0;
        commandCount = 0;
        // Item offsets: each visible instance has submeshCount items.
        if (itemFirst.length < visibleCount + 1) {
            itemFirst = new int[Math.max(visibleCount + 1, itemFirst.length * 2)];
        }
        int n = 0;
        for (int v = 0; v < visibleCount; v++) {
            itemFirst[v] = n;
            int mesh = p.mesh[visibleIndices[v]];
            n += meshes.isValid(mesh) ? meshes.submeshCount(mesh) : 0;
        }
        itemFirst[visibleCount] = n;
        itemCount = n;
        ensureItems(n);
        if (n == 0) {
            return true;
        }
        run(itemKernel, visibleCount);
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        if (sorter != null) {
            sorter.sort(keys, order, n);
        } else {
            LongSort.radixSort(keys, order, n, scratchKeys, scratchOrder, histogram);
        }
        // Runs of identical draws (same key above the depth bits) become commands.
        int runs = 0;
        for (int i = 0; i < n; i++) {
            if (i == 0 || !sameDraw(keys[i - 1], keys[i])) {
                runStart[runs++] = i;
            }
        }
        runStart[runs] = n;
        instanceBytes = (long) n * INSTANCE_BYTES;
        instanceOffset = ring.allocate(instanceBytes, 256);
        long commandOffset = ring.allocate((long) runs * COMMAND_BYTES, 16);
        if (instanceOffset < 0 || commandOffset < 0) {
            itemCount = 0;
            return false;
        }
        memory = ring.memory();
        instanceBase = instanceOffset;
        run(recordKernel, n);
        // Commands and batches (sequential: one pass over the runs).
        long c = commandOffset;
        for (int r = 0; r < runs; r++) {
            int first = runStart[r];
            int item = order[first];
            long key = keys[first];
            int v = instanceOfItem(item);
            int instance = visible[v];
            int mesh = packet.mesh[instance];
            int submesh = item - itemFirst[v];
            int lod = itemLod[item];
            memory.set(I32, c, meshes.indexCount(mesh, lod, submesh));
            memory.set(I32, c + 4, runStart[r + 1] - first);
            memory.set(I32, c + 8, meshes.firstIndex(mesh, lod, submesh));
            memory.set(I32, c + 12, meshes.baseVertex(mesh, submesh));
            memory.set(I32, c + 16, first);
            int pass = (int) (key >>> 60);
            int slot = slotOf(key);
            if (batchCount == 0
                    || batchPass[batchCount - 1] != pass
                    || batchSlot[batchCount - 1] != slot) {
                ensureBatches(batchCount + 1);
                batchPass[batchCount] = pass;
                batchSlot[batchCount] = slot;
                batchOffset[batchCount] = c;
                batchCommands[batchCount] = 0;
                batchCount++;
            }
            batchCommands[batchCount - 1]++;
            c += COMMAND_BYTES;
        }
        commandCount = runs;
        memory = null;
        packet = null;
        visible = null;
        return true;
    }

    /**
     * Chooses the program, textures and pipeline state of each batch in one pass (depth prepass,
     * shadows, lit opaque, transparent). Called for every batch in submission order; returning
     * {@code false} skips the batch.
     *
     * <p>Thread ownership: the render thread, inside {@link #submit}.
     */
    @FunctionalInterface
    public interface BatchBinder {
        /**
         * Prepares batch drawing for program {@code slot} of the material table.
         *
         * @param translucent whether the batch is in the translucent pass
         * @return {@code false} to skip the batch
         */
        boolean bind(StateCache state, int slot, boolean translucent);
    }

    /**
     * Issues the draws built by {@link #build}. Binds the mesh vertex array, the instance records
     * and the materials; programs still compiling are skipped this frame.
     */
    public void submit(StateCache state, FrameRing ring) {
        submit(state, ring, defaultBinder);
    }

    private final BatchBinder defaultBinder = this::bindDefault;

    private boolean bindDefault(StateCache state, int slot, boolean translucent) {
        int program = materials.glProgram(slot);
        if (program == 0) {
            return false;
        }
        state.apply(translucent ? PipelineState.TRANSPARENT : PipelineState.OPAQUE);
        state.useProgram(program);
        return true;
    }

    /**
     * Issues the draws built by {@link #build}, letting {@code binder} set up each batch. Binds the
     * mesh vertex array, the instance records (binding {@value #INSTANCE_BINDING}), the materials
     * (binding {@value #MATERIAL_BINDING}) and the indirect buffer. Allocates nothing.
     */
    public void submit(StateCache state, FrameRing ring, BatchBinder binder) {
        if (itemCount == 0) {
            return;
        }
        state.bindVertexArray(meshes.vertexArray());
        state.bindStorage(INSTANCE_BINDING, ring.glName(), instanceOffset, instanceBytes);
        state.bindStorage(MATERIAL_BINDING, materials.glBuffer(), 0, materials.byteSize());
        state.bindDrawIndirect(ring.glName());
        for (int b = 0; b < batchCount; b++) {
            if (!binder.bind(state, batchSlot[b], batchPass[b] == PASS_TRANSLUCENT)) {
                continue;
            }
            glMultiDrawElementsIndirect(
                    GL_TRIANGLES, GL_UNSIGNED_INT, batchOffset[b], batchCommands[b], COMMAND_BYTES);
        }
    }

    /** Returns the number of draw items (instances × submeshes) last built. */
    public int itemCount() {
        return itemCount;
    }

    /** Returns the number of indirect commands last built. */
    public int commandCount() {
        return commandCount;
    }

    /** Returns the number of multi-draw calls {@link #submit} issues. */
    public int batchCount() {
        return batchCount;
    }

    // ---------------------------------------------------------------------------------------

    private void run(JobKernel kernel, int count) {
        if (jobs != null && count >= PARALLEL_FROM) {
            jobs.parallelFor(kernel, count, GRAIN);
        } else {
            kernel.run(0, count, 0);
        }
    }

    /** Keys for visible instances {@code [start, end)}. */
    private void buildItems(int start, int end, int thread) {
        RenderPacket p = packet;
        float[] eye = p.eye;
        float[] world = p.currWorld;
        for (int v = start; v < end; v++) {
            int i = visible[v];
            int mesh = p.mesh[i];
            int first = itemFirst[v];
            int submeshes = itemFirst[v + 1] - first;
            if (submeshes == 0) {
                continue;
            }
            int w = i * 12;
            float dx = world[w + 3] - eye[0];
            float dy = world[w + 7] - eye[1];
            float dz = world[w + 11] - eye[2];
            float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            float scale =
                    (float)
                            Math.sqrt(
                                    world[w] * world[w]
                                            + world[w + 4] * world[w + 4]
                                            + world[w + 8] * world[w + 8]);
            int lod = selectLod(mesh, scale, distance);
            boolean translucent = (p.flags[i] & RenderPacket.FLAG_TRANSLUCENT) != 0;
            int depth = (int) Math.min(65535f, distance * 64f);
            long meshBits = (long) (Handles.index(mesh) & 0xFFFFF) << 28 | (long) lod << 24;
            int instanceMaterial = p.material[i];
            for (int s = 0; s < submeshes; s++) {
                int item = first + s;
                int material =
                        instanceMaterial >= 0
                                ? instanceMaterial
                                : meshes.submeshMaterialIndex(mesh, s);
                int slot = materials.programSlot(material);
                itemInstance[item] = v;
                itemMaterial[item] = material;
                itemLod[item] = (byte) lod;
                long key;
                if (translucent) {
                    // pass | depth, far first (32) | program slot (12) | submesh (8) | 0
                    long backToFront =
                            0xFFFFFFFFL - Math.min(0xFFFFFFFFL, (long) (distance * 4096f));
                    key =
                            (long) PASS_TRANSLUCENT << 60
                                    | backToFront << 28
                                    | (long) slot << 16
                                    | (long) s << 8;
                } else {
                    // pass | program slot (12) | mesh (20) | LOD (4) | submesh (8) | depth, near
                    // first (16)
                    key =
                            (long) PASS_OPAQUE << 60
                                    | (long) slot << 48
                                    | meshBits
                                    | (long) s << 16
                                    | depth;
                }
                keys[item] = key;
            }
        }
    }

    private int selectLod(int mesh, float scale, float distance) {
        int lods = meshes.lodCount(mesh);
        float k =
                orthographic
                        ? pixelsPerUnit * scale
                        : pixelsPerUnit * scale / Math.max(distance, 1e-3f);
        int lod = 0;
        for (int l = 1; l < lods; l++) {
            if (meshes.lodError(mesh, l) * k <= thresholdPixels) {
                lod = l;
            } else {
                break;
            }
        }
        return lod;
    }

    /** Opaque items merge when everything above the depth bits matches; translucent never do. */
    private static boolean sameDraw(long a, long b) {
        return (a >>> 60) == PASS_OPAQUE && (b >>> 60) == PASS_OPAQUE && (a >>> 16) == (b >>> 16);
    }

    private static int slotOf(long key) {
        return (int)
                ((key >>> 60) == PASS_TRANSLUCENT ? (key >>> 16) & 0xFFF : (key >>> 48) & 0xFFF);
    }

    /** Instance records for sorted items {@code [start, end)}. */
    private void writeRecords(int start, int end, int thread) {
        RenderPacket p = packet;
        MemorySegment m = memory;
        float[] prev = p.prevWorld;
        float[] curr = p.currWorld;
        float a = p.alpha;
        for (int k = start; k < end; k++) {
            int item = order[k];
            int instance = visible[itemInstance[item]];
            int w = instance * 12;
            long o = instanceBase + (long) k * INSTANCE_BYTES;
            for (int j = 0; j < 12; j++) {
                float x = prev[w + j];
                m.set(F32, o + 4L * j, x + (curr[w + j] - x) * a);
            }
            m.set(I32, o + 48, itemMaterial[item]);
            m.set(I32, o + 52, p.flags[instance]);
            m.set(I32, o + 56, p.entity[instance]);
            m.set(I32, o + 60, 0);
        }
    }

    private int instanceOfItem(int item) {
        return itemInstance[item];
    }

    private void ensureItems(int n) {
        if (keys.length >= n) {
            return;
        }
        int cap = Math.max(n, keys.length * 2);
        keys = new long[cap];
        order = new int[cap];
        if (sorter == null) {
            scratchKeys = new long[cap];
            scratchOrder = new int[cap];
        }
        itemInstance = new int[cap];
        itemMaterial = new int[cap];
        itemLod = new byte[cap];
        runStart = new int[cap + 1];
    }

    private void ensureBatches(int n) {
        if (batchSlot.length >= n) {
            return;
        }
        int cap = n * 2;
        batchSlot = Arrays.copyOf(batchSlot, cap);
        batchPass = Arrays.copyOf(batchPass, cap);
        batchOffset = Arrays.copyOf(batchOffset, cap);
        batchCommands = Arrays.copyOf(batchCommands, cap);
    }
}
