package dev.jeddy.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;

import java.util.Random;

class MatrixAndQuatTest {
    private static final float EPS = 1e-4f;
    private final Random random = new Random(1234);

    private Quatf randomQuat() {
        return new Quatf(
                        random.nextFloat() * 2 - 1,
                        random.nextFloat() * 2 - 1,
                        random.nextFloat() * 2 - 1,
                        random.nextFloat() * 2 - 1)
                .normalize();
    }

    private Vec3f randomVec(float scale) {
        return new Vec3f(
                (random.nextFloat() * 2 - 1) * scale,
                (random.nextFloat() * 2 - 1) * scale,
                (random.nextFloat() * 2 - 1) * scale);
    }

    /** Reference 4×4 multiply in double precision. */
    private static double[] mulRef(float[] a, float[] b) {
        double[] r = new double[16];
        for (int c = 0; c < 4; c++) {
            for (int row = 0; row < 4; row++) {
                double s = 0;
                for (int k = 0; k < 4; k++) {
                    s += (double) a[k * 4 + row] * b[c * 4 + k];
                }
                r[c * 4 + row] = s;
            }
        }
        return r;
    }

    @Test
    void mat4MultiplyMatchesDoubleReference() {
        for (int n = 0; n < 200; n++) {
            float[] a = new float[16];
            float[] b = new float[16];
            for (int i = 0; i < 16; i++) {
                a[i] = random.nextFloat() * 4 - 2;
                b[i] = random.nextFloat() * 4 - 2;
            }
            float[] out = new float[16];
            Mat4Ops.mul(a, 0, b, 0, out, 0);
            double[] ref = mulRef(a, b);
            for (int i = 0; i < 16; i++) {
                assertEquals(ref[i], out[i], 1e-4);
            }
        }
    }

    @Test
    void inverseTimesMatrixIsIdentity() {
        for (int n = 0; n < 200; n++) {
            Mat4f m =
                    Mat4f.trs(
                            randomVec(50),
                            randomQuat(),
                            new Vec3f(
                                    0.5f + random.nextFloat(),
                                    0.5f + random.nextFloat(),
                                    0.5f + random.nextFloat()));
            assertTrue(m.mul(m.invert()).approxEquals(Mat4f.IDENTITY, 1e-4f));
        }
        assertThrows(ArithmeticException.class, () -> Mat4f.scaling(1, 0, 1).invert());
        assertEquals(6f, Mat4f.scaling(1, 2, 3).determinant(), EPS);
    }

    @Test
    void trsComposition() {
        Quatf q = Quatf.fromAxisAngle(Vec3f.UNIT_Y, MathF.HALF_PI);
        Mat4f m = Mat4f.trs(new Vec3f(10, 0, 0), q, new Vec3f(2, 2, 2));
        // (1,0,0) scaled to (2,0,0), rotated 90° about Y to (0,0,-2), translated to (10,0,-2).
        assertTrue(m.transformPoint(Vec3f.UNIT_X).approxEquals(new Vec3f(10, 0, -2), EPS));
        Mat4f composed =
                Mat4f.translation(10, 0, 0).mul(Mat4f.rotation(q)).mul(Mat4f.scaling(2, 2, 2));
        assertTrue(composed.approxEquals(m, EPS));
    }

    @Test
    void reversedZPerspectiveMapsNearToOneAndFarTowardsZero() {
        Mat4f p = Mat4f.perspectiveReversedZ(MathF.toRadians(60), 16f / 9f, 0.5f);
        assertEquals(1f, p.project(new Vec3f(0, 0, -0.5f)).z(), EPS);
        assertEquals(0.5f, p.project(new Vec3f(0, 0, -1f)).z(), EPS);
        assertTrue(p.project(new Vec3f(0, 0, -1e6f)).z() < 1e-6f);
        Mat4f o = Mat4f.orthoReversedZ(-10, 10, -5, 5, 1, 101);
        assertEquals(1f, o.project(new Vec3f(10, 5, -1)).z(), EPS);
        assertEquals(0f, o.project(new Vec3f(-10, -5, -101)).z(), EPS);
        assertEquals(1f, o.project(new Vec3f(10, 5, -1)).x(), EPS);
        float[] arr = new float[16];
        Mat4Ops.perspectiveReversedZ(MathF.toRadians(60), 16f / 9f, 0.5f, arr, 0);
        assertTrue(Mat4f.fromArray(arr, 0).approxEquals(p, 0));
    }

    @Test
    void lookAtPutsEyeAtOriginLookingDownMinusZ() {
        Vec3f eye = new Vec3f(3, 10, 7);
        Vec3f target = new Vec3f(-2, 0, 1);
        Mat4f v = Mat4f.lookAt(eye, target, Vec3f.UNIT_Y);
        assertTrue(v.transformPoint(eye).approxEquals(Vec3f.ZERO, EPS));
        Vec3f t = v.transformPoint(target);
        assertEquals(0, t.x(), EPS);
        assertEquals(0, t.y(), EPS);
        assertEquals(-eye.distance(target), t.z(), EPS);
        float[] arr = new float[16];
        Mat4Ops.lookAt(3, 10, 7, -2, 0, 1, 0, 1, 0, arr, 0);
        assertTrue(Mat4f.fromArray(arr, 0).approxEquals(v, EPS));
    }

    @Test
    void affineOpsAgreeWithMat4() {
        float[] a = new float[12];
        float[] b = new float[12];
        float[] ab = new float[12];
        float[] inv = new float[12];
        float[] m4 = new float[16];
        for (int n = 0; n < 100; n++) {
            Vec3f ta = randomVec(20);
            Quatf qa = randomQuat();
            Vec3f sa = new Vec3f(1, 2, 0.5f);
            Vec3f tb = randomVec(20);
            Quatf qb = randomQuat();
            AffineOps.composeTRS(
                    ta.x(), ta.y(), ta.z(), qa.x(), qa.y(), qa.z(), qa.w(), sa.x(), sa.y(), sa.z(),
                    a, 0);
            AffineOps.composeTRS(
                    tb.x(), tb.y(), tb.z(), qb.x(), qb.y(), qb.z(), qb.w(), 1, 1, 1, b, 0);
            Mat4f ma = Mat4f.trs(ta, qa, sa);
            Mat4f mb = Mat4f.trs(tb, qb, Vec3f.ONE);
            AffineOps.toMat4(a, 0, m4, 0);
            assertTrue(Mat4f.fromArray(m4, 0).approxEquals(ma, EPS));
            AffineOps.mul(a, 0, b, 0, ab, 0);
            AffineOps.toMat4(ab, 0, m4, 0);
            assertTrue(Mat4f.fromArray(m4, 0).approxEquals(ma.mul(mb), 1e-3f));
            assertTrue(AffineOps.invert(a, 0, inv, 0));
            AffineOps.toMat4(inv, 0, m4, 0);
            assertTrue(Mat4f.fromArray(m4, 0).approxEquals(ma.invert(), 1e-3f));
            float[] p = new float[3];
            AffineOps.transformPoint(a, 0, 1, 2, 3, p, 0);
            assertTrue(
                    new Vec3f(p[0], p[1], p[2])
                            .approxEquals(ma.transformPoint(new Vec3f(1, 2, 3)), 1e-3f));
        }
    }

    @Test
    void hierarchyConcatenation() {
        int[] parent = {-1, 0, 1};
        float[] local = new float[36];
        for (int i = 0; i < 3; i++) {
            AffineOps.composeTRS(1, 0, 0, 0, 0, 0, 1, 1, 1, 1, local, i * 12);
        }
        float[] world = new float[36];
        AffineOps.concatenate(parent, local, world, 0, 3);
        assertEquals(3f, world[2 * 12 + 3], EPS); // three chained +1 translations along x
    }

    @Test
    void aabbTransformContainsCorners() {
        float[] m = new float[12];
        Quatf q = randomQuat();
        AffineOps.composeTRS(5, -2, 1, q.x(), q.y(), q.z(), q.w(), 2, 1, 3, m, 0);
        float[] box = new float[6];
        AffineOps.transformAabb(m, 0, -1, -2, -3, 1, 2, 3, box, 0);
        float[] p = new float[3];
        for (int c = 0; c < 8; c++) {
            AffineOps.transformPoint(
                    m,
                    0,
                    (c & 1) == 0 ? -1 : 1,
                    (c & 2) == 0 ? -2 : 2,
                    (c & 4) == 0 ? -3 : 3,
                    p,
                    0);
            for (int k = 0; k < 3; k++) {
                assertTrue(p[k] >= box[k] - EPS && p[k] <= box[3 + k] + EPS);
            }
        }
    }

    @Test
    void quaternionsAgreeWithMatrices() {
        for (int n = 0; n < 200; n++) {
            Quatf q = randomQuat();
            Vec3f v = randomVec(3);
            Vec3f byQuat = q.rotate(v);
            Vec3f byMat = Mat4f.rotation(q).transformDirection(v);
            assertTrue(byQuat.approxEquals(byMat, EPS));
            Mat4f r = Mat4f.rotation(q);
            Quatf back =
                    Quatf.fromMatrix(
                            r.m00(), r.m10(), r.m20(), r.m01(), r.m11(), r.m21(), r.m02(), r.m12(),
                            r.m22());
            assertTrue(back.approxEquals(q, 1e-5f));
            Quatf p = randomQuat();
            assertTrue(q.mul(p).rotate(v).approxEquals(q.rotate(p.rotate(v)), 1e-3f));
            assertTrue(q.mul(q.inverse()).approxEquals(Quatf.IDENTITY, 1e-5f));
        }
    }

    @Test
    void slerpAndLookRotation() {
        Quatf a = Quatf.IDENTITY;
        Quatf b = Quatf.fromYaw(MathF.HALF_PI);
        assertTrue(a.slerp(b, 0).approxEquals(a, 1e-6f));
        assertTrue(a.slerp(b, 1).approxEquals(b, 1e-6f));
        assertEquals(MathF.PI / 4, a.slerp(b, 0.5f).angle(), 1e-4f);
        assertEquals(MathF.PI / 4, a.nlerp(b, 0.5f).angle(), 1e-3f);
        Vec3f dir = new Vec3f(1, 0, 1).normalize();
        Quatf look = Quatf.lookRotation(dir, Vec3f.UNIT_Y);
        assertTrue(look.forward().approxEquals(dir, EPS));
        Quatf ypr = Quatf.fromYawPitchRoll(0.3f, 0.2f, 0.1f);
        assertEquals(1f, ypr.lengthSquared(), 1e-5f);
    }

    @Test
    void quatOpsMatchQuatf() {
        int n = 64;
        float[] qx = new float[n], qy = new float[n], qz = new float[n], qw = new float[n];
        float[] vx = new float[n], vy = new float[n], vz = new float[n];
        Quatf[] qs = new Quatf[n];
        Vec3f[] vs = new Vec3f[n];
        for (int i = 0; i < n; i++) {
            qs[i] = randomQuat();
            vs[i] = randomVec(2);
            qx[i] = qs[i].x() * 3;
            qy[i] = qs[i].y() * 3;
            qz[i] = qs[i].z() * 3;
            qw[i] = qs[i].w() * 3;
            vx[i] = vs[i].x();
            vy[i] = vs[i].y();
            vz[i] = vs[i].z();
        }
        QuatOps.normalize(qx, qy, qz, qw, 0, n);
        QuatOps.rotate(qx, qy, qz, qw, vx, vy, vz, 0, n);
        for (int i = 0; i < n; i++) {
            assertTrue(new Vec3f(vx[i], vy[i], vz[i]).approxEquals(qs[i].rotate(vs[i]), 1e-4f));
        }
    }

    @Test
    void mat3NormalMatrixAndMutableTypes() {
        Mat4f model = Mat4f.scaling(2, 1, 1);
        Vec3f n = Mat3f.normalMatrix(model).transform(new Vec3f(1, 1, 0).normalize());
        assertEquals(n.x() * 2, n.y(), EPS); // normals shrink along the stretched axis
        assertEquals(1f, Mat3f.rotation(randomQuat()).determinant(), 1e-4f);
        MMat4f a = new MMat4f().set(Mat4f.translation(1, 2, 3));
        MMat4f b = new MMat4f().set(a).invert();
        assertTrue(new MMat4f().mul(a, b).toMat4f().approxEquals(Mat4f.IDENTITY, EPS));
        MVec3f v = new MVec3f(1, 0, 0);
        new MQuatf().fromAxisAngle(0, 1, 0, MathF.HALF_PI).rotate(v);
        assertTrue(v.toVec3f().approxEquals(new Vec3f(0, 0, -1), EPS));
        assertEquals(5f, new MVec3f(3, 4, 0).length(), EPS);
    }

    @Test
    void cameraCullingAndPicking() {
        IsoCamera cam = new IsoCamera();
        cam.target.set(10, 0, -5);
        cam.update(16f / 9f);
        assertTrue(CullOps.sphereVisible(cam.frustumPlanes, 10, 0, -5, 0.5f));
        assertFalse(
                CullOps.sphereVisible(cam.frustumPlanes, 10, 0, 500, 1f)); // far behind the camera
        assertFalse(CullOps.sphereVisible(cam.frustumPlanes, 500, 0, -5, 1f)); // far to the side
        assertTrue(CullOps.aabbVisible(cam.frustumPlanes, 9, -1, -6, 11, 1, -4));
        float[] origin = new float[3];
        float[] dir = new float[3];
        cam.screenRay(0, 0, origin, dir);
        float t =
                Intersect.rayPlane(
                        origin[0], origin[1], origin[2], dir[0], dir[1], dir[2], 0, 1, 0, 0);
        assertEquals(10f, origin[0] + dir[0] * t, 1e-2f);
        assertEquals(-5f, origin[2] + dir[2] * t, 1e-2f);
        cam.orthographic = true;
        cam.update(1);
        cam.screenRay(0, 0, origin, dir);
        t = Intersect.rayPlane(origin[0], origin[1], origin[2], dir[0], dir[1], dir[2], 0, 1, 0, 0);
        assertEquals(10f, origin[0] + dir[0] * t, 1e-2f);

        int n = 1000;
        float[] cx = new float[n], cy = new float[n], cz = new float[n], r = new float[n];
        for (int i = 0; i < n; i++) {
            cx[i] = 10 + (i % 2 == 0 ? 0 : 1000);
            cz[i] = -5;
            r[i] = 1;
        }
        int[] visible = new int[n];
        assertEquals(n / 2, CullOps.spheres(cam.frustumPlanes, cx, cy, cz, r, 0, n, visible, 0));
        assertEquals(2, visible[1]);
    }

    private float sink;

    @Test
    void kernelsDoNotAllocate() {
        IsoCamera cam = new IsoCamera();
        int n = 256;
        int[] parent = new int[n];
        float[] local = new float[n * 12];
        float[] world = new float[n * 12];
        float[] cx = new float[n], cy = new float[n], cz = new float[n], r = new float[n];
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i - 1;
            AffineOps.identity(local, i * 12);
            r[i] = 1;
        }
        Allocations.assertNoAllocation(
                "math kernels",
                () -> {
                    cam.yaw += 0.01f;
                    cam.update(1.5f);
                    AffineOps.concatenate(parent, local, world, 0, n);
                    sink += CullOps.spheres(cam.frustumPlanes, cx, cy, cz, r, 0, n, out, 0);
                    sink +=
                            Intersect.rayTriangle(
                                    0, 1, 0, 0, -1, 0, -1, 0, -1, 1, 0, -1, 0, 0, 1, 10, null);
                });
    }
}
