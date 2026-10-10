package dev.jeddy.render.mesh;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.asset.format.MeshData;
import dev.jeddy.core.handle.HandlePool;
import dev.jeddy.core.handle.Handles;
import dev.jeddy.core.memory.RangeAllocator;
import dev.jeddy.gl.GLDebug;
import dev.jeddy.render.gpu.GpuBuffers;
import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.gpu.UploadRing;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

/**
 * Every loaded mesh in three mega buffers (TASKS M5-07, DESIGN §9.2): positions (12 B per vertex),
 * attributes (16 B: QTangent and two half-float UV sets) and {@code u32} indices, sub-allocated
 * with free lists and grown by doubling. One vertex array describes them all, so drawing any mesh
 * is a base vertex and an index range; multi-draw-indirect batches whole passes.
 *
 * <p>Per-mesh data is SoA: vertex and index ranges, bounding sphere, and flat tables of submeshes
 * (base vertex, material slot) and LOD ranges (first index and count per LOD and submesh).
 *
 * <p>Thread ownership: the GL thread. Draw builders on workers may read the query methods while no
 * mesh is added or removed.
 */
public final class MeshRegistry implements AutoCloseable {
    /** Vertex buffer binding of the position stream. */
    public static final int BINDING_POSITIONS = 0;

    /** Vertex buffer binding of the attribute stream. */
    public static final int BINDING_ATTRIBUTES = 1;

    private final GpuDevice device;
    private final UploadRing ring;
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment idScratch = arena.allocate(16);
    private final int vao;
    private int positions;
    private int attributes;
    private int indices;
    private final RangeAllocator vertexSpace;
    private final RangeAllocator indexSpace;
    private final RangeAllocator submeshSpace = new RangeAllocator(1024);
    private final RangeAllocator drawSpace = new RangeAllocator(4096);
    private final RangeAllocator lodSpace = new RangeAllocator(1024);
    private final HandlePool handles = new HandlePool(256, Handles.MAX_INDEX + 1);

    // Per mesh.
    private int[] vertexOffset = new int[256];
    private int[] vertexCount = new int[256];
    private int[] indexOffset = new int[256];
    private int[] indexCount = new int[256];
    private int[] submeshCount = new int[256];
    private int[] lodCount = new int[256];
    private int[] submeshBase = new int[256];
    private int[] drawBase = new int[256];
    private int[] lodBase = new int[256];
    private float[] spheres = new float[256 * 4];

    // Flat tables.
    private int[] submeshBaseVertex = new int[1024]; // absolute
    private int[] submeshMaterial = new int[1024];
    private int[] drawFirstIndex = new int[4096]; // absolute
    private int[] drawIndexCount = new int[4096];
    private float[] lodError = new float[1024];

    private long uploadedBytes;

    /**
     * Creates the mega buffers.
     *
     * @param ring staging for uploads, or {@code null} to upload directly
     * @param vertices initial vertex capacity (grows)
     * @param indexCapacity initial index capacity (grows)
     */
    public MeshRegistry(GpuDevice device, UploadRing ring, int vertices, int indexCapacity) {
        this.device = device;
        this.ring = ring;
        vertexSpace = new RangeAllocator(vertices);
        indexSpace = new RangeAllocator(indexCapacity);
        GpuBuffers b = device.buffers();
        positions =
                b.create(
                        (long) vertices * MeshData.POSITION_STRIDE,
                        GpuBuffers.DYNAMIC,
                        "mesh positions");
        attributes =
                b.create(
                        (long) vertices * MeshData.ATTRIBUTE_STRIDE,
                        GpuBuffers.DYNAMIC,
                        "mesh attributes");
        indices = b.create(4L * indexCapacity, GpuBuffers.DYNAMIC, "mesh indices");
        MemorySegment id = idScratch;
        glCreateVertexArrays(1, id);
        vao = id.get(ValueLayout.JAVA_INT, 0);
        GLDebug.label(GL_VERTEX_ARRAY, vao, "mesh vertex array");
        glEnableVertexArrayAttrib(vao, 0);
        glVertexArrayAttribFormat(vao, 0, 3, GL_FLOAT, false, 0);
        glVertexArrayAttribBinding(vao, 0, BINDING_POSITIONS);
        glEnableVertexArrayAttrib(vao, 1);
        glVertexArrayAttribFormat(vao, 1, 4, GL_SHORT, true, 0);
        glVertexArrayAttribBinding(vao, 1, BINDING_ATTRIBUTES);
        glEnableVertexArrayAttrib(vao, 2);
        glVertexArrayAttribFormat(vao, 2, 2, GL_HALF_FLOAT, false, 8);
        glVertexArrayAttribBinding(vao, 2, BINDING_ATTRIBUTES);
        glEnableVertexArrayAttrib(vao, 3);
        glVertexArrayAttribFormat(vao, 3, 2, GL_HALF_FLOAT, false, 12);
        glVertexArrayAttribBinding(vao, 3, BINDING_ATTRIBUTES);
        bindBuffers();
    }

    private void bindBuffers() {
        GpuBuffers b = device.buffers();
        glVertexArrayVertexBuffer(
                vao, BINDING_POSITIONS, b.glName(positions), 0, MeshData.POSITION_STRIDE);
        glVertexArrayVertexBuffer(
                vao, BINDING_ATTRIBUTES, b.glName(attributes), 0, MeshData.ATTRIBUTE_STRIDE);
        glVertexArrayElementBuffer(vao, b.glName(indices));
    }

    /** Returns the vertex array to bind for every mesh draw. */
    public int vertexArray() {
        return vao;
    }

    /**
     * Uploads a mesh and returns its handle. Grows the mega buffers when they are full.
     *
     * @param data a validated mesh whose streams are native memory
     */
    public int add(MeshData data) {
        int vc = data.vertexCount();
        int ic = data.indexCount();
        int sc = data.submeshCount();
        int lc = data.lodCount();
        long vOff = allocate(vertexSpace, Math.max(1, vc), true);
        long iOff = allocate(indexSpace, Math.max(1, ic), false);
        int sBase = (int) allocateTable(submeshSpace, Math.max(1, sc), 0);
        int dBase = (int) allocateTable(drawSpace, Math.max(1, sc * lc), 1);
        int lBase = (int) allocateTable(lodSpace, lc, 2);
        GpuBuffers b = device.buffers();
        upload(b.glName(positions), vOff * MeshData.POSITION_STRIDE, data.positions());
        upload(b.glName(attributes), vOff * MeshData.ATTRIBUTE_STRIDE, data.attributes());
        upload(b.glName(indices), iOff * 4, data.indices());

        int h = handles.allocate();
        int m = Handles.index(h);
        ensureMeshes(m);
        vertexOffset[m] = (int) vOff;
        vertexCount[m] = vc;
        indexOffset[m] = (int) iOff;
        indexCount[m] = ic;
        submeshCount[m] = sc;
        lodCount[m] = lc;
        submeshBase[m] = sBase;
        drawBase[m] = dBase;
        lodBase[m] = lBase;
        for (int i = 0; i < 4; i++) {
            spheres[m * 4 + i] = data.sphere(i);
        }
        for (int s = 0; s < sc; s++) {
            submeshBaseVertex[sBase + s] = (int) vOff + data.submeshBaseVertex(s);
            submeshMaterial[sBase + s] = data.submeshMaterial(s);
        }
        for (int l = 0; l < lc; l++) {
            lodError[lBase + l] = data.lodError(l);
            for (int s = 0; s < sc; s++) {
                drawFirstIndex[dBase + l * sc + s] = (int) iOff + data.lodFirstIndex(l, s);
                drawIndexCount[dBase + l * sc + s] = data.lodIndexCount(l, s);
            }
        }
        return h;
    }

    /**
     * Frees a mesh's ranges (the GPU may still be drawing it this frame; ranges are reused later).
     */
    public void remove(int mesh) {
        int m = check(mesh);
        vertexSpace.free(vertexOffset[m], Math.max(1, vertexCount[m]));
        indexSpace.free(indexOffset[m], Math.max(1, indexCount[m]));
        submeshSpace.free(submeshBase[m], Math.max(1, submeshCount[m]));
        drawSpace.free(drawBase[m], Math.max(1, submeshCount[m] * lodCount[m]));
        lodSpace.free(lodBase[m], lodCount[m]);
        handles.free(mesh);
    }

    /** Returns the number of meshes. */
    public int count() {
        return handles.size();
    }

    /** Returns {@code true} if {@code mesh} is a live handle. */
    public boolean isValid(int mesh) {
        return handles.isValid(mesh);
    }

    /** Returns the number of submeshes. */
    public int submeshCount(int mesh) {
        return submeshCount[Handles.index(mesh)];
    }

    /** Returns the number of LODs. */
    public int lodCount(int mesh) {
        return lodCount[Handles.index(mesh)];
    }

    /** Returns the absolute base vertex of a submesh (for {@code DrawElementsIndirectCommand}). */
    public int baseVertex(int mesh, int submesh) {
        return submeshBaseVertex[submeshBase[Handles.index(mesh)] + submesh];
    }

    /** Returns a submesh's material slot. */
    public int materialSlot(int mesh, int submesh) {
        return submeshMaterial[submeshBase[Handles.index(mesh)] + submesh];
    }

    /** Returns the absolute first index of a submesh at a LOD. */
    public int firstIndex(int mesh, int lod, int submesh) {
        int m = Handles.index(mesh);
        return drawFirstIndex[drawBase[m] + lod * submeshCount[m] + submesh];
    }

    /** Returns the index count of a submesh at a LOD. */
    public int indexCount(int mesh, int lod, int submesh) {
        int m = Handles.index(mesh);
        return drawIndexCount[drawBase[m] + lod * submeshCount[m] + submesh];
    }

    /** Returns a LOD's geometric error. */
    public float lodError(int mesh, int lod) {
        return lodError[lodBase[Handles.index(mesh)] + lod];
    }

    /** Returns the bounding sphere in mesh space: 0-2 center, 3 radius. */
    public float sphere(int mesh, int i) {
        return spheres[Handles.index(mesh) * 4 + i];
    }

    /** Returns the bytes uploaded so far. */
    public long uploadedBytes() {
        return uploadedBytes;
    }

    /** Returns the vertex capacity of the mega buffers. */
    public long vertexCapacity() {
        return vertexSpace.capacity();
    }

    /** Returns the GL name of the index buffer (tests, debugging). */
    public int indexBufferName() {
        return device.buffers().glName(indices);
    }

    /** Returns the GL name of the position buffer (tests, debugging). */
    public int positionBufferName() {
        return device.buffers().glName(positions);
    }

    private void upload(int buffer, long dstOffset, MemorySegment src) {
        long n = src.byteSize();
        if (n == 0) {
            return;
        }
        uploadedBytes += n;
        if (ring != null) {
            long off = ring.reserve(n, 16);
            if (off >= 0) {
                MemorySegment.copy(src, 0, ring.memory(), off, n);
                ring.copyToBuffer(off, buffer, dstOffset, n);
                return;
            }
        }
        glNamedBufferSubData(buffer, dstOffset, n, src.address()); // the driver copies now
    }

    private long allocate(RangeAllocator space, long n, boolean vertices) {
        long off = space.allocate(n, 1);
        while (off < 0) {
            long old = space.capacity();
            long cap = Math.max(old * 2, old + n);
            if (vertices) {
                positions =
                        regrow(
                                positions,
                                old * MeshData.POSITION_STRIDE,
                                cap * MeshData.POSITION_STRIDE,
                                "mesh positions");
                attributes =
                        regrow(
                                attributes,
                                old * MeshData.ATTRIBUTE_STRIDE,
                                cap * MeshData.ATTRIBUTE_STRIDE,
                                "mesh attributes");
            } else {
                indices = regrow(indices, old * 4, cap * 4, "mesh indices");
            }
            space.grow(cap);
            bindBuffers();
            off = space.allocate(n, 1);
        }
        return off;
    }

    private int regrow(int buffer, long oldBytes, long newBytes, String label) {
        GpuBuffers b = device.buffers();
        int bigger = b.create(newBytes, GpuBuffers.DYNAMIC, label);
        glCopyNamedBufferSubData(b.glName(buffer), b.glName(bigger), 0, 0, oldBytes);
        b.destroy(buffer); // GL defers the delete until pending draws are done
        return bigger;
    }

    private long allocateTable(RangeAllocator space, long n, int table) {
        long off = space.allocate(n, 1);
        while (off < 0) {
            long cap = Math.max(space.capacity() * 2, space.capacity() + n);
            space.grow(cap);
            int c = (int) cap;
            switch (table) {
                case 0 -> {
                    submeshBaseVertex = Arrays.copyOf(submeshBaseVertex, c);
                    submeshMaterial = Arrays.copyOf(submeshMaterial, c);
                }
                case 1 -> {
                    drawFirstIndex = Arrays.copyOf(drawFirstIndex, c);
                    drawIndexCount = Arrays.copyOf(drawIndexCount, c);
                }
                default -> lodError = Arrays.copyOf(lodError, c);
            }
            off = space.allocate(n, 1);
        }
        return off;
    }

    private void ensureMeshes(int m) {
        if (m < vertexOffset.length) {
            return;
        }
        int c = Math.max(m + 1, vertexOffset.length * 2);
        vertexOffset = Arrays.copyOf(vertexOffset, c);
        vertexCount = Arrays.copyOf(vertexCount, c);
        indexOffset = Arrays.copyOf(indexOffset, c);
        indexCount = Arrays.copyOf(indexCount, c);
        submeshCount = Arrays.copyOf(submeshCount, c);
        lodCount = Arrays.copyOf(lodCount, c);
        submeshBase = Arrays.copyOf(submeshBase, c);
        drawBase = Arrays.copyOf(drawBase, c);
        lodBase = Arrays.copyOf(lodBase, c);
        spheres = Arrays.copyOf(spheres, c * 4);
    }

    private int check(int mesh) {
        if (!handles.isValid(mesh)) {
            throw new IllegalArgumentException("stale mesh handle " + Handles.toString(mesh));
        }
        return Handles.index(mesh);
    }

    /** Deletes the vertex array and the mega buffers. */
    @Override
    public void close() {
        idScratch.set(ValueLayout.JAVA_INT, 0, vao);
        glDeleteVertexArrays(1, idScratch);
        GpuBuffers b = device.buffers();
        b.destroy(positions);
        b.destroy(attributes);
        b.destroy(indices);
        arena.close();
    }
}
