package dev.jeddy.physics.bvh;

import java.util.Arrays;

/**
 * A triangle soup for collision and navigation baking: SoA vertices ({@code x, y, z}), three
 * indices and one {@link TriFlags} word per triangle. Grows as triangles are appended.
 *
 * <p>Thread ownership: one thread writes; read-only afterwards.
 */
public final class TriangleMesh {
    private float[] vertices;
    private int[] indices;
    private int[] flags;
    private int vertexCount;
    private int triangleCount;

    /** Creates an empty mesh. */
    public TriangleMesh() {
        this(64, 64);
    }

    /** Creates an empty mesh with room for the given counts. */
    public TriangleMesh(int vertexCapacity, int triangleCapacity) {
        vertices = new float[Math.max(3, vertexCapacity * 3)];
        indices = new int[Math.max(3, triangleCapacity * 3)];
        flags = new int[Math.max(1, triangleCapacity)];
    }

    /** Wraps existing arrays (not copied). */
    public TriangleMesh(
            float[] vertices, int vertexCount, int[] indices, int[] flags, int triangles) {
        this.vertices = vertices;
        this.vertexCount = vertexCount;
        this.indices = indices;
        this.flags = flags;
        this.triangleCount = triangles;
    }

    /** Appends a vertex and returns its index. */
    public int addVertex(float x, float y, float z) {
        if ((vertexCount + 1) * 3 > vertices.length) {
            vertices = Arrays.copyOf(vertices, vertices.length * 2);
        }
        int v = vertexCount++;
        vertices[v * 3] = x;
        vertices[v * 3 + 1] = y;
        vertices[v * 3 + 2] = z;
        return v;
    }

    /** Appends a triangle and returns its index. */
    public int addTriangle(int a, int b, int c, int triangleFlags) {
        if ((triangleCount + 1) * 3 > indices.length) {
            indices = Arrays.copyOf(indices, indices.length * 2);
        }
        if (triangleCount == flags.length) {
            flags = Arrays.copyOf(flags, flags.length * 2);
        }
        int t = triangleCount++;
        indices[t * 3] = a;
        indices[t * 3 + 1] = b;
        indices[t * 3 + 2] = c;
        flags[t] = triangleFlags;
        return t;
    }

    /** Appends an axis-aligned box as 12 outward-facing triangles. */
    public void addBox(
            float minX, float minY, float minZ, float maxX, float maxY, float maxZ, int boxFlags) {
        int b = vertexCount;
        for (int i = 0; i < 8; i++) {
            addVertex(
                    (i & 1) == 0 ? minX : maxX,
                    (i & 2) == 0 ? minY : maxY,
                    (i & 4) == 0 ? minZ : maxZ);
        }
        int[] q = {
            0, 2, 3, 1, // -z
            4, 5, 7, 6, // +z
            0, 4, 6, 2, // -x
            1, 3, 7, 5, // +x
            0, 1, 5, 4, // -y
            2, 6, 7, 3 // +y
        };
        for (int f = 0; f < 6; f++) {
            int o = f * 4;
            addTriangle(b + q[o], b + q[o + 1], b + q[o + 2], boxFlags);
            addTriangle(b + q[o], b + q[o + 2], b + q[o + 3], boxFlags);
        }
    }

    /** Appends a flat quad on the XZ plane at height {@code y}, facing up. */
    public void addGround(float minX, float minZ, float maxX, float maxZ, float y, int quadFlags) {
        int a = addVertex(minX, y, minZ);
        int b = addVertex(maxX, y, minZ);
        int c = addVertex(maxX, y, maxZ);
        int d = addVertex(minX, y, maxZ);
        addTriangle(a, d, c, quadFlags);
        addTriangle(a, c, b, quadFlags);
    }

    /** Returns the vertex array (3 floats per vertex; may be longer than needed). */
    public float[] vertices() {
        return vertices;
    }

    /** Returns the index array (3 per triangle; may be longer than needed). */
    public int[] indices() {
        return indices;
    }

    /** Returns the flags array (one per triangle; may be longer than needed). */
    public int[] flags() {
        return flags;
    }

    /** Returns the number of vertices. */
    public int vertexCount() {
        return vertexCount;
    }

    /** Returns the number of triangles. */
    public int triangleCount() {
        return triangleCount;
    }
}
