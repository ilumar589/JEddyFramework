package dev.jeddy.asset.format;

import static dev.jeddy.asset.format.Bytes.F32;
import static dev.jeddy.asset.format.Bytes.U16;
import static dev.jeddy.asset.format.Bytes.U32;
import static dev.jeddy.asset.format.Bytes.U64;
import static dev.jeddy.asset.format.Bytes.checkRange;

import java.lang.foreign.MemorySegment;

/**
 * Reads a cooked {@code .jmesh} ({@code docs/formats/jmesh.md}, TASKS M4-06) as views over the file
 * memory: the vertex streams and the index buffer are slices that a GPU upload copies straight
 * from. Submesh, LOD and material tables are read on demand from the same memory.
 *
 * <p>Thread ownership: immutable after {@link #read}; any thread, while the file memory is alive.
 */
public final class MeshData {
    /** {@code "JMSH"} in file order. */
    public static final int MAGIC = 0x48534D4A;

    /** The format version this code reads and writes. */
    public static final int VERSION = 1;

    /** Header size. */
    public static final int HEADER_SIZE = 128;

    /** Flag: the skin stream is present. */
    public static final int FLAG_SKIN = 1;

    /** Bytes per vertex in the position stream ({@code f32 x3}). */
    public static final int POSITION_STRIDE = 12;

    /** Bytes per vertex in the attribute stream (QTangent, uv0, uv1). */
    public static final int ATTRIBUTE_STRIDE = 16;

    /** Bytes per vertex in the skin stream (4 {@code u16} joints, 4 {@code unorm16} weights). */
    public static final int SKIN_STRIDE = 16;

    /** Bytes per submesh record. */
    public static final int SUBMESH_SIZE = 48;

    /** Alignment of every section. */
    public static final int SECTION_ALIGNMENT = 64;

    private final MemorySegment file;
    private final int flags;
    private final int vertexCount;
    private final int indexCount;
    private final int submeshCount;
    private final int lodCount;
    private final int materialCount;
    private final MemorySegment positions;
    private final MemorySegment attributes;
    private final MemorySegment skin;
    private final MemorySegment indices;
    private final long submeshes;
    private final long lods;
    private final long materials;

    private MeshData(MemorySegment file) throws AssetFormatException {
        this.file = file;
        checkRange(file, 0, HEADER_SIZE, ".jmesh header");
        if (file.get(U32, 0) != MAGIC) {
            throw new AssetFormatException("not a .jmesh file");
        }
        int version = Short.toUnsignedInt(file.get(U16, 4));
        if (version != VERSION) {
            throw new AssetFormatException(".jmesh version " + version + ", expected " + VERSION);
        }
        flags = Short.toUnsignedInt(file.get(U16, 6));
        vertexCount = file.get(U32, 8);
        indexCount = file.get(U32, 12);
        submeshCount = Short.toUnsignedInt(file.get(U16, 16));
        lodCount = Short.toUnsignedInt(file.get(U16, 18));
        materialCount = Short.toUnsignedInt(file.get(U16, 20));
        if (vertexCount < 0 || indexCount < 0 || lodCount < 1) {
            throw new AssetFormatException(".jmesh has invalid counts");
        }
        if (file.get(U64, 120) != file.byteSize()) {
            throw new AssetFormatException(".jmesh is truncated");
        }
        positions = section(64, (long) vertexCount * POSITION_STRIDE, "positions");
        attributes = section(72, (long) vertexCount * ATTRIBUTE_STRIDE, "attributes");
        skin =
                hasSkin()
                        ? section(80, (long) vertexCount * SKIN_STRIDE, "skin")
                        : MemorySegment.NULL;
        indices = section(88, (long) indexCount * 4, "indices");
        submeshes = file.get(U64, 96);
        checkRange(file, submeshes, (long) submeshCount * SUBMESH_SIZE, ".jmesh submeshes");
        lods = file.get(U64, 104);
        checkRange(file, lods, 8L * lodCount * (1 + submeshCount), ".jmesh LODs");
        materials = file.get(U64, 112);
        checkRange(file, materials, 8L * materialCount, ".jmesh materials");
        validate();
    }

    private MemorySegment section(long directoryOffset, long length, String what)
            throws AssetFormatException {
        long offset = file.get(U64, directoryOffset);
        checkRange(file, offset, length, ".jmesh " + what);
        return file.asSlice(offset, length);
    }

    /** Checks submesh ranges, LOD index ranges and every index against its submesh. */
    private void validate() throws AssetFormatException {
        for (int s = 0; s < submeshCount; s++) {
            long base = Integer.toUnsignedLong(submeshBaseVertex(s));
            long count = Integer.toUnsignedLong(submeshVertexCount(s));
            if (base + count > vertexCount) {
                throw new AssetFormatException(".jmesh submesh " + s + " exceeds the vertices");
            }
            if (Integer.compareUnsigned(submeshMaterial(s), Math.max(1, materialCount)) >= 0) {
                throw new AssetFormatException(".jmesh submesh " + s + " has no material slot");
            }
            for (int l = 0; l < lodCount; l++) {
                long first = Integer.toUnsignedLong(lodFirstIndex(l, s));
                long n = Integer.toUnsignedLong(lodIndexCount(l, s));
                if (first + n > indexCount) {
                    throw new AssetFormatException(
                            ".jmesh LOD " + l + " of submesh " + s + " exceeds the indices");
                }
                for (long i = first; i < first + n; i++) {
                    if (Integer.toUnsignedLong(indices.getAtIndex(U32, i)) >= count) {
                        throw new AssetFormatException(
                                ".jmesh index " + i + " is outside submesh " + s);
                    }
                }
            }
        }
    }

    /**
     * Parses a {@code .jmesh} held in {@code file}, validating every range and index.
     *
     * @throws AssetFormatException if the file is malformed
     */
    public static MeshData read(MemorySegment file) throws AssetFormatException {
        return new MeshData(file);
    }

    /** Returns the number of vertices. */
    public int vertexCount() {
        return vertexCount;
    }

    /** Returns the number of indices (all LODs). */
    public int indexCount() {
        return indexCount;
    }

    /** Returns the number of submeshes (one draw each per LOD). */
    public int submeshCount() {
        return submeshCount;
    }

    /** Returns the number of LODs (at least 1; LOD 0 is the most detailed). */
    public int lodCount() {
        return lodCount;
    }

    /** Returns the number of material slots. */
    public int materialSlotCount() {
        return materialCount;
    }

    /** Returns {@code true} if the mesh has the skin stream. */
    public boolean hasSkin() {
        return (flags & FLAG_SKIN) != 0;
    }

    /** Returns component {@code axis} (0 to 2) of the bounding box minimum. */
    public float boundsMin(int axis) {
        return file.get(F32, 24 + 4L * axis);
    }

    /** Returns component {@code axis} (0 to 2) of the bounding box maximum. */
    public float boundsMax(int axis) {
        return file.get(F32, 36 + 4L * axis);
    }

    /** Returns the bounding sphere: {@code i} 0 to 2 is the center, 3 the radius. */
    public float sphere(int i) {
        return file.get(F32, 48 + 4L * i);
    }

    /** Returns the position stream ({@code f32 x3} per vertex). */
    public MemorySegment positions() {
        return positions;
    }

    /** Returns the attribute stream (QTangent {@code snorm16 x4}, uv0 and uv1 {@code f16 x2}). */
    public MemorySegment attributes() {
        return attributes;
    }

    /** Returns the skin stream, or {@code MemorySegment.NULL}. */
    public MemorySegment skin() {
        return skin;
    }

    /** Returns the index buffer ({@code u32}, relative to each submesh's base vertex). */
    public MemorySegment indices() {
        return indices;
    }

    /** Returns the material slot of submesh {@code s}. */
    public int submeshMaterial(int s) {
        return file.get(U32, submeshes + (long) s * SUBMESH_SIZE);
    }

    /** Returns the first vertex of submesh {@code s} (the draw's base vertex). */
    public int submeshBaseVertex(int s) {
        return file.get(U32, submeshes + (long) s * SUBMESH_SIZE + 4);
    }

    /** Returns the number of vertices of submesh {@code s}. */
    public int submeshVertexCount(int s) {
        return file.get(U32, submeshes + (long) s * SUBMESH_SIZE + 8);
    }

    /** Returns component {@code axis} of submesh {@code s}'s bounding box minimum. */
    public float submeshMin(int s, int axis) {
        return file.get(F32, submeshes + (long) s * SUBMESH_SIZE + 16 + 4L * axis);
    }

    /** Returns component {@code axis} of submesh {@code s}'s bounding box maximum. */
    public float submeshMax(int s, int axis) {
        return file.get(F32, submeshes + (long) s * SUBMESH_SIZE + 28 + 4L * axis);
    }

    /**
     * Returns LOD {@code l}'s geometric error (in mesh units; LOD selection compares its projected
     * size with a pixel threshold). 0 for LOD 0.
     */
    public float lodError(int l) {
        return file.get(F32, lods + 8L * l);
    }

    /** Returns the first index of submesh {@code s} in LOD {@code l}. */
    public int lodFirstIndex(int l, int s) {
        return file.get(U32, lods + 8L * lodCount + 8L * ((long) l * submeshCount + s));
    }

    /** Returns the index count of submesh {@code s} in LOD {@code l}. */
    public int lodIndexCount(int l, int s) {
        return file.get(U32, lods + 8L * lodCount + 8L * ((long) l * submeshCount + s) + 4);
    }

    /** Returns the default material ({@code StrId} of its asset path) of slot {@code slot}. */
    public long materialId(int slot) {
        return file.get(U64, materials + 8L * slot);
    }
}
