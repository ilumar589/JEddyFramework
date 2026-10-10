package dev.jeddy.math;

/**
 * Frustum culling kernels (DESIGN §9.2/§9.4). Planes are stored as 24 floats: six planes {@code
 * (a, b, c, d)} with {@code a·x + b·y + c·z + d >= 0} inside, normalized so {@code d} is a
 * distance. Order: left, right, bottom, top, near, far.
 *
 * <p>The far plane of an infinite reversed-Z projection is degenerate; it is stored as {@code (0,
 * 0, 0, 1)}, which accepts everything.
 *
 * <p>Thread ownership: stateless, callable from any thread.
 */
public final class CullOps {
    /** Floats in a plane set. */
    public static final int PLANES_FLOATS = 24;

    private CullOps() {}

    /**
     * Extracts the six planes of a column-major view-projection matrix whose clip depth range is
     * {@code [0, w]} (D3D/zero-to-one convention, which Jeddy's reversed-Z projections use).
     */
    public static void extractPlanes(float[] m, int mo, float[] planes) {
        // Row r of the matrix is (m[r], m[4 + r], m[8 + r], m[12 + r]).
        for (int i = 0; i < 6; i++) {
            float a;
            float b;
            float c;
            float d;
            switch (i) {
                case 0 -> { // left: row3 + row0
                    a = m[mo + 3] + m[mo];
                    b = m[mo + 7] + m[mo + 4];
                    c = m[mo + 11] + m[mo + 8];
                    d = m[mo + 15] + m[mo + 12];
                }
                case 1 -> { // right: row3 - row0
                    a = m[mo + 3] - m[mo];
                    b = m[mo + 7] - m[mo + 4];
                    c = m[mo + 11] - m[mo + 8];
                    d = m[mo + 15] - m[mo + 12];
                }
                case 2 -> { // bottom: row3 + row1
                    a = m[mo + 3] + m[mo + 1];
                    b = m[mo + 7] + m[mo + 5];
                    c = m[mo + 11] + m[mo + 9];
                    d = m[mo + 15] + m[mo + 13];
                }
                case 3 -> { // top: row3 - row1
                    a = m[mo + 3] - m[mo + 1];
                    b = m[mo + 7] - m[mo + 5];
                    c = m[mo + 11] - m[mo + 9];
                    d = m[mo + 15] - m[mo + 13];
                }
                case 4 -> { // z <= w (reversed-Z: the near plane)
                    a = m[mo + 3] - m[mo + 2];
                    b = m[mo + 7] - m[mo + 6];
                    c = m[mo + 11] - m[mo + 10];
                    d = m[mo + 15] - m[mo + 14];
                }
                default -> { // z >= 0 (reversed-Z: the far plane)
                    a = m[mo + 2];
                    b = m[mo + 6];
                    c = m[mo + 10];
                    d = m[mo + 14];
                }
            }
            float len = (float) Math.sqrt(a * a + b * b + c * c);
            if (len > 1e-12f) {
                planes[i * 4] = a / len;
                planes[i * 4 + 1] = b / len;
                planes[i * 4 + 2] = c / len;
                planes[i * 4 + 3] = d / len;
            } else {
                planes[i * 4] = 0;
                planes[i * 4 + 1] = 0;
                planes[i * 4 + 2] = 0;
                planes[i * 4 + 3] = 1;
            }
        }
    }

    /** Returns {@code true} if the sphere is at least partly inside the frustum. */
    public static boolean sphereVisible(float[] planes, float cx, float cy, float cz, float r) {
        for (int p = 0; p < 24; p += 4) {
            if (planes[p] * cx + planes[p + 1] * cy + planes[p + 2] * cz + planes[p + 3] < -r) {
                return false;
            }
        }
        return true;
    }

    /** Returns {@code true} if the box is at least partly inside the frustum (conservative). */
    public static boolean aabbVisible(
            float[] planes, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        for (int p = 0; p < 24; p += 4) {
            float a = planes[p];
            float b = planes[p + 1];
            float c = planes[p + 2];
            // Corner furthest along the plane normal.
            float x = a >= 0 ? maxX : minX;
            float y = b >= 0 ? maxY : minY;
            float z = c >= 0 ? maxZ : minZ;
            if (a * x + b * y + c * z + planes[p + 3] < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tests bounding spheres {@code [start, end)} and appends the indices of visible ones to
     * {@code out} starting at {@code outOffset}. Returns the number written. The compaction is
     * branch-free, so {@code out} needs room for {@code end - start} entries.
     */
    public static int spheres(
            float[] planes, float[] cx, float[] cy, float[] cz, float[] r, int start, int end, int[] out, int outOffset) {
        float p0a = planes[0], p0b = planes[1], p0c = planes[2], p0d = planes[3];
        float p1a = planes[4], p1b = planes[5], p1c = planes[6], p1d = planes[7];
        float p2a = planes[8], p2b = planes[9], p2c = planes[10], p2d = planes[11];
        float p3a = planes[12], p3b = planes[13], p3c = planes[14], p3d = planes[15];
        float p4a = planes[16], p4b = planes[17], p4c = planes[18], p4d = planes[19];
        float p5a = planes[20], p5b = planes[21], p5c = planes[22], p5d = planes[23];
        int n = outOffset;
        for (int i = start; i < end; i++) {
            float x = cx[i];
            float y = cy[i];
            float z = cz[i];
            float nr = -r[i];
            boolean visible =
                    p0a * x + p0b * y + p0c * z + p0d >= nr
                            & p1a * x + p1b * y + p1c * z + p1d >= nr
                            & p2a * x + p2b * y + p2c * z + p2d >= nr
                            & p3a * x + p3b * y + p3c * z + p3d >= nr
                            & p4a * x + p4b * y + p4c * z + p4d >= nr
                            & p5a * x + p5b * y + p5c * z + p5d >= nr;
            out[n] = i;
            n += visible ? 1 : 0; // branch-free compaction
        }
        return n - outOffset;
    }

    /**
     * Tests boxes {@code [start, end)} given as min/max struct-of-arrays and appends visible
     * indices to {@code out}. Returns the number written.
     */
    public static int aabbs(
            float[] planes,
            float[] minX, float[] minY, float[] minZ, float[] maxX, float[] maxY, float[] maxZ,
            int start, int end, int[] out, int outOffset) {
        int n = outOffset;
        for (int i = start; i < end; i++) {
            if (aabbVisible(planes, minX[i], minY[i], minZ[i], maxX[i], maxY[i], maxZ[i])) {
                out[n++] = i;
            }
        }
        return n - outOffset;
    }
}
