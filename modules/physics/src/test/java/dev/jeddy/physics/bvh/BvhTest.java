package dev.jeddy.physics.bvh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.core.job.JobSystem;
import dev.jeddy.math.Intersect;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Random;

/** The static collision BVH: SAH build, ray queries, box queries, serialization (TASKS M8-01). */
class BvhTest {
    /** Random triangles in a 100 m cube, plus a ground grid. */
    static TriangleMesh randomMesh(int triangles, long seed) {
        Random r = new Random(seed);
        TriangleMesh m = new TriangleMesh();
        for (int t = 0; t < triangles; t++) {
            float cx = r.nextFloat() * 100;
            float cy = r.nextFloat() * 20;
            float cz = r.nextFloat() * 100;
            int a = m.addVertex(cx, cy, cz);
            int b =
                    m.addVertex(
                            cx + r.nextFloat() * 2 - 1,
                            cy + r.nextFloat() * 2 - 1,
                            cz + r.nextFloat() * 2 - 1);
            int c =
                    m.addVertex(
                            cx + r.nextFloat() * 2 - 1,
                            cy + r.nextFloat() * 2 - 1,
                            cz + r.nextFloat() * 2 - 1);
            m.addTriangle(a, b, c, t % 3 == 0 ? TriFlags.BLOCKS_LOS : TriFlags.WALKABLE);
        }
        return m;
    }

    /** Closest hit by testing every triangle. */
    static float bruteForce(TriangleMesh m, float[] o, float[] d, int mask, int[] which) {
        float best = Float.POSITIVE_INFINITY;
        float[] v = m.vertices();
        int[] idx = m.indices();
        for (int t = 0; t < m.triangleCount(); t++) {
            if ((m.flags()[t] & mask) == 0) {
                continue;
            }
            int a = idx[t * 3] * 3, b = idx[t * 3 + 1] * 3, c = idx[t * 3 + 2] * 3;
            float h =
                    Intersect.rayTriangle(
                            o[0], o[1], o[2], d[0], d[1], d[2], v[a], v[a + 1], v[a + 2], v[b],
                            v[b + 1], v[b + 2], v[c], v[c + 1], v[c + 2], 1000, null);
            if (h < best) {
                best = h;
                which[0] = t;
            }
        }
        return best;
    }

    @Test
    void raycastsMatchBruteForce() {
        TriangleMesh m = randomMesh(3000, 1);
        Bvh bvh = BvhBuilder.build(m, null);
        assertEquals(3000, bvh.triangleCount());
        RayHit hit = new RayHit();
        Random r = new Random(2);
        int hits = 0;
        int[] which = new int[1];
        for (int i = 0; i < 2000; i++) {
            float[] o = {
                r.nextFloat() * 120 - 10, r.nextFloat() * 30 - 5, r.nextFloat() * 120 - 10
            };
            float[] d = {r.nextFloat() * 2 - 1, r.nextFloat() * 2 - 1, r.nextFloat() * 2 - 1};
            int mask = i % 2 == 0 ? -1 : TriFlags.BLOCKS_LOS;
            float expected = bruteForce(m, o, d, mask, which);
            boolean got = bvh.raycast(o[0], o[1], o[2], d[0], d[1], d[2], 1000, mask, hit);
            assertEquals(expected != Float.POSITIVE_INFINITY, got, "ray " + i);
            if (got) {
                hits++;
                assertEquals(expected, hit.t, 1e-3f * Math.max(1, expected), "ray " + i);
                assertTrue((hit.flags & mask) != 0);
                assertEquals(1f, hit.nx * hit.nx + hit.ny * hit.ny + hit.nz * hit.nz, 1e-4f);
                // Any-hit agrees that something is there, no farther than the limit.
                assertTrue(bvh.raycastAny(o[0], o[1], o[2], d[0], d[1], d[2], 1000, mask, hit));
            }
        }
        assertTrue(hits > 30, "some rays hit: " + hits);
    }

    @Test
    void maxDistanceLimitsHits() {
        TriangleMesh m = new TriangleMesh();
        m.addGround(-10, -10, 10, 10, 0, TriFlags.WALKABLE);
        Bvh bvh = BvhBuilder.build(m, null);
        RayHit hit = new RayHit();
        assertTrue(bvh.raycast(0, 5, 0, 0, -1, 0, 10, -1, hit));
        assertEquals(5f, hit.t, 1e-5f);
        assertEquals(1f, Math.abs(hit.ny), 1e-6f);
        assertFalse(bvh.raycast(0, 5, 0, 0, -1, 0, 4, -1, hit), "too short");
        assertFalse(bvh.raycast(0, 5, 0, 0, -1, 0, 10, TriFlags.BLOCKS_LOS, hit), "masked out");
        // Grazing and axis-parallel rays (infinite inverse direction components).
        assertFalse(bvh.raycast(-20, 1, 0, 1, 0, 0, 100, -1, hit));
        assertTrue(bvh.raycast(3, 2, 4, 0, -2, 0, 10, -1, hit));
        assertEquals(1f, hit.t, 1e-5f, "t in units of |d|");
    }

    @Test
    void parallelBuildsMatchSequentialOnes() {
        TriangleMesh m = randomMesh(50_000, 3);
        Bvh seq = BvhBuilder.build(m, null);
        Bvh par;
        try (JobSystem jobs = new JobSystem(3)) {
            par = BvhBuilder.build(m, jobs);
        }
        assertEquals(seq.triangleCount(), par.triangleCount());
        RayHit a = new RayHit();
        RayHit b = new RayHit();
        Random r = new Random(4);
        for (int i = 0; i < 2000; i++) {
            float ox = r.nextFloat() * 100, oy = 30, oz = r.nextFloat() * 100;
            float dx = r.nextFloat() - 0.5f, dy = -1, dz = r.nextFloat() - 0.5f;
            boolean ha = seq.raycast(ox, oy, oz, dx, dy, dz, 100, -1, a);
            boolean hb = par.raycast(ox, oy, oz, dx, dy, dz, 100, -1, b);
            assertEquals(ha, hb);
            if (ha) {
                assertEquals(a.t, b.t, 1e-5f);
                assertEquals(seq.triangleId(a.triangle), par.triangleId(b.triangle));
            }
        }
    }

    @Test
    void boxQueriesFindOverlappingTriangles() {
        TriangleMesh m = randomMesh(5000, 5);
        Bvh bvh = BvhBuilder.build(m, null);
        int[] stack = new int[Bvh.MAX_DEPTH * 2];
        int[] count = {0};
        int found =
                bvh.overlapBox(
                        40,
                        0,
                        40,
                        50,
                        20,
                        50,
                        -1,
                        stack,
                        t -> {
                            count[0]++;
                            return true;
                        });
        assertEquals(count[0], found);
        // Brute force over triangle boxes.
        int expected = 0;
        float[] v = m.vertices();
        int[] idx = m.indices();
        for (int t = 0; t < m.triangleCount(); t++) {
            float lx = Float.MAX_VALUE,
                    hx = -Float.MAX_VALUE,
                    lz = Float.MAX_VALUE,
                    hz = -Float.MAX_VALUE;
            float ly = Float.MAX_VALUE, hy = -Float.MAX_VALUE;
            for (int k = 0; k < 3; k++) {
                int o = idx[t * 3 + k] * 3;
                lx = Math.min(lx, v[o]);
                hx = Math.max(hx, v[o]);
                ly = Math.min(ly, v[o + 1]);
                hy = Math.max(hy, v[o + 1]);
                lz = Math.min(lz, v[o + 2]);
                hz = Math.max(hz, v[o + 2]);
            }
            if (lx <= 50 && hx >= 40 && ly <= 20 && hy >= 0 && lz <= 50 && hz >= 40) {
                expected++;
            }
        }
        assertEquals(expected, found);
    }

    @Test
    void serializationRoundTrips() {
        TriangleMesh m = randomMesh(1000, 6);
        Bvh bvh = BvhBuilder.build(m, null);
        byte[] bytes = bvh.toBytes();
        assertEquals(bvh.byteSize(), bytes.length);
        Bvh back = Bvh.read(ByteBuffer.wrap(bytes));
        assertEquals(bvh.nodeCount(), back.nodeCount());
        RayHit a = new RayHit();
        RayHit b = new RayHit();
        assertEquals(
                bvh.raycast(50, 30, 50, 0.1f, -1, 0.2f, 100, -1, a),
                back.raycast(50, 30, 50, 0.1f, -1, 0.2f, 100, -1, b));
        assertEquals(a.t, b.t);
    }

    @Test
    void raycastsAllocateNothingAndAreFast() {
        // A 300×300 m terrain of 1 m quads: 180k triangles.
        TriangleMesh m = new TriangleMesh(90_601, 180_000);
        int n = 300;
        Random r = new Random(7);
        for (int z = 0; z <= n; z++) {
            for (int x = 0; x <= n; x++) {
                m.addVertex(x, r.nextFloat() * 0.3f, z);
            }
        }
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                int a = z * (n + 1) + x;
                m.addTriangle(a, a + n + 1, a + n + 2, TriFlags.WALKABLE);
                m.addTriangle(a, a + n + 2, a + 1, TriFlags.WALKABLE);
            }
        }
        Bvh bvh;
        try (JobSystem jobs = new JobSystem(2)) {
            long t0 = System.nanoTime();
            bvh = BvhBuilder.build(m, jobs);
            System.out.printf(
                    "BVH build, 180k triangles: %.1f ms%n", (System.nanoTime() - t0) / 1e6);
        }
        RayHit hit = new RayHit();
        float[] rays = new float[6 * 4096];
        for (int i = 0; i < 4096; i++) {
            rays[i * 6] = r.nextFloat() * 300;
            rays[i * 6 + 1] = 20;
            rays[i * 6 + 2] = r.nextFloat() * 300;
            rays[i * 6 + 3] = r.nextFloat() - 0.5f;
            rays[i * 6 + 4] = -1;
            rays[i * 6 + 5] = r.nextFloat() - 0.5f;
        }
        int[] k = {0};
        Runnable one =
                () -> {
                    int i = (k[0]++ & 4095) * 6;
                    bvh.raycast(
                            rays[i],
                            rays[i + 1],
                            rays[i + 2],
                            rays[i + 3],
                            rays[i + 4],
                            rays[i + 5],
                            100,
                            -1,
                            hit);
                };
        Allocations.assertNoAllocation("raycast", one);
        long t0 = System.nanoTime();
        int count = 1_000_000;
        for (int i = 0; i < count; i++) {
            one.run();
        }
        double perSecond = count / ((System.nanoTime() - t0) / 1e9);
        System.out.printf(
                "raycast (closest, terrain): %.2f M rays/s on one thread%n", perSecond / 1e6);
        assertTrue(perSecond > 2e5, "rays per second: " + perSecond);
    }
}
