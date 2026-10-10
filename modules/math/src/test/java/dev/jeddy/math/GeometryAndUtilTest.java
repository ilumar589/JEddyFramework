package dev.jeddy.math;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class GeometryAndUtilTest {
    private static final float MISS = Intersect.MISS;

    @Test
    void rayAabb() {
        assertEquals(4f, Intersect.rayAabb(-5, 0, 0, 1, 0, 0, -1, -1, -1, 1, 1, 1, 100), 1e-6f);
        assertEquals(0f, Intersect.rayAabb(0, 0, 0, 0, 1, 0, -1, -1, -1, 1, 1, 1, 100));
        assertEquals(MISS, Intersect.rayAabb(-5, 2, 0, 1, 0, 0, -1, -1, -1, 1, 1, 1, 100));
        assertEquals(MISS, Intersect.rayAabb(-5, 0, 0, -1, 0, 0, -1, -1, -1, 1, 1, 1, 100));
        assertEquals(MISS, Intersect.rayAabb(-5, 0, 0, 1, 0, 0, -1, -1, -1, 1, 1, 1, 3));
    }

    @Test
    void rayTriangleHitsAndBarycentrics() {
        float[] bary = new float[3];
        float t = Intersect.rayTriangle(0.2f, 5, 0.2f, 0, -1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 100, bary);
        assertEquals(5f, t, 1e-5f);
        assertEquals(0.6f, bary[0], 1e-5f);
        assertEquals(0.2f, bary[1], 1e-5f);
        assertEquals(0.2f, bary[2], 1e-5f);
        // Back side is hit too (two-sided).
        assertEquals(5f, Intersect.rayTriangle(0.2f, -5, 0.2f, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 100, null), 1e-5f);
        assertEquals(MISS, Intersect.rayTriangle(2, 5, 2, 0, -1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 100, null));
        assertEquals(MISS, Intersect.rayTriangle(0.2f, 5, 0.2f, 0, -1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 4, null));
    }

    @Test
    void rayTriangleIsWatertightOnSharedEdges() {
        // Two triangles share the diagonal of a unit quad; rays exactly on the diagonal must hit one.
        Random r = new Random(3);
        for (int i = 0; i < 10_000; i++) {
            float s = r.nextFloat();
            float ox = s;
            float oz = s; // on the diagonal x == z
            float t1 = Intersect.rayTriangle(ox, 3, oz, 0, -1, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1, 10, null);
            float t2 = Intersect.rayTriangle(ox, 3, oz, 0, -1, 0, 0, 0, 0, 1, 0, 1, 0, 0, 1, 10, null);
            assertTrue(t1 != MISS || t2 != MISS, "gap at " + s);
        }
    }

    @Test
    void raySphereCylinderCapsulePlane() {
        assertEquals(4f, Intersect.raySphere(-5, 0, 0, 1, 0, 0, 0, 0, 0, 1), 1e-5f);
        assertEquals(0f, Intersect.raySphere(0, 0, 0, 1, 0, 0, 0, 0, 0, 1));
        assertEquals(MISS, Intersect.raySphere(-5, 2, 0, 1, 0, 0, 0, 0, 0, 1));
        // Vertical cylinder radius 0.5 at (3, 0), height 0..2.
        assertEquals(2.5f, Intersect.rayVerticalCylinder(0, 1, 0, 1, 0, 0, 3, 0, 0.5f, 0, 2), 1e-5f);
        assertEquals(MISS, Intersect.rayVerticalCylinder(0, 3, 0, 1, 0, 0, 3, 0, 0.5f, 0, 2));
        assertEquals(2f, Intersect.rayVerticalCylinder(3, 4, 0, 0, -1, 0, 3, 0, 0.5f, 0, 2), 1e-5f); // top cap
        // Capsule from (0,0,0) to (0,2,0), radius 0.5.
        assertEquals(4.5f, Intersect.rayCapsule(-5, 1, 0, 2, 0, 0, 0, 0, 0, 0, 2, 0, 0.5f) * 2, 1e-4f);
        assertEquals(4.5f, Intersect.rayCapsule(0, 7, 0, 0, -1, 0, 0, 0, 0, 0, 2, 0, 0.5f), 1e-4f); // cap
        assertEquals(MISS, Intersect.rayCapsule(-5, 5, 0, 1, 0, 0, 0, 0, 0, 0, 2, 0, 0.5f));
        assertEquals(3f, Intersect.rayPlane(0, 3, 0, 0, -1, 0, 0, 1, 0, 0), 1e-6f);
        assertEquals(MISS, Intersect.rayPlane(0, 3, 0, 1, 0, 0, 0, 1, 0, 0));
    }

    @Test
    void aoeShapes() {
        // 90° cone along +X, range 10.
        float c = (float) Math.cos(Math.PI / 4);
        float s = (float) Math.sin(Math.PI / 4);
        assertTrue(Intersect.sectorCircle(0, 0, 1, 0, c, s, 10, 5, 0, 0.5f));
        assertTrue(Intersect.sectorCircle(0, 0, 1, 0, c, s, 10, 5, 5.3f, 0.5f)); // grazes the edge
        assertFalse(Intersect.sectorCircle(0, 0, 1, 0, c, s, 10, 5, 7, 0.5f));
        assertFalse(Intersect.sectorCircle(0, 0, 1, 0, c, s, 10, 11, 0, 0.5f));
        assertFalse(Intersect.sectorCircle(0, 0, 1, 0, c, s, 10, -2, 0, 0.5f));
        // Line 20 long, 1 wide each side.
        assertTrue(Intersect.rectCircle(0, 0, 0, 1, 20, 1, 1.4f, 10, 0.5f));
        assertFalse(Intersect.rectCircle(0, 0, 0, 1, 20, 1, 1.6f, 10, 0.5f));
        assertFalse(Intersect.rectCircle(0, 0, 0, 1, 20, 1, 0, 21, 0.5f));
        assertTrue(Intersect.segmentCircle(0, 0, 10, 0, 5, 0.9f, 1));
        assertFalse(Intersect.circleCircle(0, 0, 1, 3, 0, 1));
        assertEquals(0.5f, Intersect.segmentSegment2(0, 0, 2, 0, 1, -1, 1, 1), 1e-6f);
        assertTrue(Float.isNaN(Intersect.segmentSegment2(0, 0, 2, 0, 3, -1, 3, 1)));
        assertTrue(Intersect.sphereAabb(1.5f, 0, 0, 0.6f, -1, -1, -1, 1, 1, 1));
        assertTrue(Intersect.coneSphere(0, 0, 0, 0, -1, 0, c, s, 5, 0, -3, 0, 0.1f));
        assertEquals(1f, Intersect.pointSegmentDistanceSquared(0, 1, 0, -1, 0, 0, 1, 0, 0), 1e-6f);
    }

    @Test
    void polygons() {
        float[] square = {0, 0, 2, 0, 2, 2, 0, 2};
        assertEquals(4f, Poly2.signedArea(square, 4), 1e-6f);
        assertTrue(Poly2.isConvex(square, 4));
        assertTrue(Poly2.contains(square, 4, 1, 1));
        assertFalse(Poly2.contains(square, 4, 3, 1));
        float[] l = {0, 0, 3, 0, 3, 1, 1, 1, 1, 3, 0, 3}; // concave L, area 5
        assertFalse(Poly2.isConvex(l, 6));
        int[] tris = new int[12];
        int count = Poly2.triangulate(l, 6, tris, new int[6]);
        assertEquals(4, count);
        float area = 0;
        for (int t = 0; t < count; t++) {
            float[] tri = {
                l[2 * tris[3 * t]], l[2 * tris[3 * t] + 1],
                l[2 * tris[3 * t + 1]], l[2 * tris[3 * t + 1] + 1],
                l[2 * tris[3 * t + 2]], l[2 * tris[3 * t + 2] + 1]
            };
            float a = Poly2.signedArea(tri, 3);
            assertTrue(a > 0, "triangle " + t + " is not CCW");
            area += a;
        }
        assertEquals(5f, area, 1e-5f);
        float[] grown = new float[8];
        Poly2.offsetConvex(square, 4, 1, grown);
        assertArrayEquals(new float[] {-1, -1, 3, -1, 3, 3, -1, 3}, grown, 1e-5f);
    }

    @Test
    void packing() {
        Random r = new Random(9);
        float[] n = new float[3];
        for (int i = 0; i < 5000; i++) {
            Vec3f v = new Vec3f(r.nextFloat() * 2 - 1, r.nextFloat() * 2 - 1, r.nextFloat() * 2 - 1).normalize();
            Packing.unpackOctahedral16(Packing.packOctahedral16(v.x(), v.y(), v.z()), n, 0);
            assertTrue(new Vec3f(n[0], n[1], n[2]).dot(v) > 0.99999f, "oct error at " + v);
        }
        assertEquals(1.5f, Packing.fromHalf(Packing.toHalf(1.5f)));
        assertEquals(-1f, Packing.unpackSnorm16(Packing.packSnorm16(-2f)));
        assertEquals(0.5f, Packing.unpackSnorm8(Packing.packSnorm8(0.5f)), 1f / 127);
        int rgba = Packing.packRgba8(1, 0.5f, 0, 1);
        assertEquals(0xFF0080FF, rgba);
        assertEquals(0.5f, Packing.unpackRgba8(rgba, 1), 1f / 255);
        for (float c = 0; c <= 1; c += 0.01f) {
            assertEquals(c, Packing.srgbToLinear(Packing.linearToSrgb(c)), 1e-5f);
        }
        assertEquals(0.2140f, Packing.srgbToLinear(0.5f), 1e-4f);
    }

    @Test
    void rngIsDeterministicAndUniform() {
        Rng a = new Rng(42);
        Rng b = new Rng(42);
        for (int i = 0; i < 100; i++) {
            assertEquals(a.nextLong(), b.nextLong());
        }
        long[] state = new long[4];
        a.saveState(state);
        long next = a.nextLong();
        b.restoreState(state);
        assertEquals(next, b.nextLong());
        int[] counts = new int[6];
        Rng r = new Rng(7);
        for (int i = 0; i < 60_000; i++) {
            int v = r.d(6);
            assertTrue(v >= 1 && v <= 6);
            counts[v - 1]++;
        }
        for (int c : counts) {
            assertTrue(Math.abs(c - 10_000) < 500, "die face count " + c);
        }
        for (int i = 0; i < 1000; i++) {
            int roll = r.roll(2, 6, 3);
            assertTrue(roll >= 5 && roll <= 15);
            float f = r.nextFloat();
            assertTrue(f >= 0 && f < 1);
            int x = r.nextInt(-3, 3);
            assertTrue(x >= -3 && x <= 3);
        }
        Rng parent = new Rng(1);
        Rng child = parent.split();
        assertNotEquals(parent.nextLong(), child.nextLong());
        assertEquals(1, r.pickWeighted(new float[] {0, 5, 0}, 3));
        assertEquals(-1, r.pickWeighted(new float[] {0, 0}, 2));
    }

    @Test
    void noiseIsBoundedSmoothAndSeeded() {
        Noise n1 = new Noise(5);
        Noise n2 = new Noise(5);
        Noise n3 = new Noise(6);
        boolean differs = false;
        for (int i = 0; i < 1000; i++) {
            float x = i * 0.37f;
            float y = i * 0.11f;
            float v = n1.noise(x, y);
            assertTrue(v >= -1.01f && v <= 1.01f);
            assertEquals(v, n2.noise(x, y));
            differs |= v != n3.noise(x, y);
            assertTrue(Math.abs(n1.noise(x, y, 1.5f) - n1.noise(x + 0.001f, y, 1.5f)) < 0.05f);
        }
        assertTrue(differs);
        float f = n1.fbm(1.3f, 2.7f, 5, 2, 0.5f);
        assertTrue(f >= -1 && f <= 1);
    }

    @Test
    void scalarHelpers() {
        assertEquals(-MathF.PI + 0.5f, MathF.wrapAngle(MathF.PI + 0.5f), 1e-5f);
        assertEquals(MathF.PI, MathF.wrapAngle(MathF.PI), 1e-6f);
        assertEquals(MathF.PI - 0.05f, Math.abs(MathF.lerpAngle(MathF.PI - 0.1f, -MathF.PI + 0.1f, 0.25f)), 1e-4f);
        assertEquals(10f, MathF.damp(0, 10, 100, 10), 1e-3f);
        assertEquals(3f, MathF.moveTowards(0, 10, 3));
        assertEquals(10f, MathF.moveTowards(9, 10, 3));
        assertEquals(-2, MathF.floorToInt(-1.5f));
        assertEquals(64, MathF.nextPowerOfTwo(33));
        assertEquals(0.5f, MathF.smoothstep(0, 1, 0.5f), 1e-6f);
        assertEquals(1f, Ease.outBounce(1), 1e-5f);
        assertEquals(0.5f, Ease.inOutCubic(0.5f), 1e-6f);
        assertEquals(1f, Ease.outBack(1), 1e-5f);
    }

    @Test
    void valueRecords() {
        Aabb3f box = Aabb3f.ofCenter(Vec3f.ZERO, Vec3f.ONE);
        assertTrue(box.contains(new Vec3f(0.5f, -0.5f, 1)));
        assertEquals(24f, box.surfaceArea(), 1e-6f);
        assertTrue(Aabb3f.EMPTY.union(box).equals(box));
        assertTrue(box.intersects(Aabb3f.ofCenter(new Vec3f(1.5f, 0, 0), Vec3f.ONE)));
        Planef p = Planef.fromPoints(Vec3f.ZERO, new Vec3f(0, 0, 1), new Vec3f(1, 0, 0));
        assertEquals(2f, p.distance(new Vec3f(5, 2, 5)), 1e-6f);
        assertEquals(3f, p.intersect(new Ray3f(new Vec3f(0, 3, 0), new Vec3f(0, -1, 0))), 1e-6f);
        assertTrue(new Capsulef(Vec3f.ZERO, Vec3f.UNIT_Y, 0.5f).contains(new Vec3f(0.4f, 0.5f, 0)));
        assertTrue(new Spheref(Vec3f.ZERO, 1).intersects(new Spheref(new Vec3f(1.9f, 0, 0), 1)));
        Vec2f perp = new Vec2f(1, 0).perpendicular(); // (-0.0, 1): records compare -0.0 != 0.0
        assertEquals(0f, perp.x(), 0f);
        assertEquals(1f, perp.y());
        assertEquals(1f, new Vec4f(0, 0, 0, 1).length());
        assertEquals(new Vec3f(0, 0, 1), Vec3f.UNIT_X.cross(Vec3f.UNIT_Y).negate().negate());
    }
}
