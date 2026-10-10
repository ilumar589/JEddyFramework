package dev.jeddy.physics.crowd;

/**
 * Optimal reciprocal collision avoidance (van den Berg et al., following the RVO2 library): each
 * neighbour turns into a half-plane of velocities that avoid it for a time horizon (each agent
 * taking half the responsibility), and a small linear program picks the velocity closest to the
 * preferred one inside every half-plane and the speed limit, or the least-bad one when they cannot
 * all be met. 2D on the XZ plane. One instance per thread; allocates nothing.
 *
 * <p>Thread ownership: one thread per instance.
 */
public final class Orca {
    private static final float EPSILON = 1e-5f;

    private final int maxLines;
    // Constraint lines: a point and a unit direction; valid velocities lie to the left.
    final float[] px;
    final float[] py;
    final float[] dx;
    final float[] dy;
    int lineCount;
    private final float[] qx;
    private final float[] qy;
    private final float[] ex;
    private final float[] ey;

    /** The velocity found by {@link #solve}. */
    public float resultX;

    /** The velocity found by {@link #solve}. */
    public float resultY;

    /** Creates a solver for up to {@code maxNeighbours} constraints. */
    public Orca(int maxNeighbours) {
        maxLines = maxNeighbours;
        px = new float[maxLines];
        py = new float[maxLines];
        dx = new float[maxLines];
        dy = new float[maxLines];
        qx = new float[maxLines];
        qy = new float[maxLines];
        ex = new float[maxLines];
        ey = new float[maxLines];
    }

    /** Forgets the constraints. */
    public void clear() {
        lineCount = 0;
    }

    /** Returns the number of constraints added. */
    public int lineCount() {
        return lineCount;
    }

    /**
     * Adds the half-plane of velocities for the agent at {@code (ax, ay)} moving at {@code (avx,
     * avy)} that avoid a neighbour at {@code (bx, by)} moving at {@code (bvx, bvy)} for {@code
     * horizon} seconds (combined radius {@code r}; {@code dt} is the step, used when they already
     * overlap). {@code responsibility} is this agent's share of the avoidance (0.5 when both avoid;
     * 1 against a neighbour that will not).
     */
    public void addAgent(
            float ax,
            float ay,
            float avx,
            float avy,
            float bx,
            float by,
            float bvx,
            float bvy,
            float r,
            float horizon,
            float dt,
            float responsibility) {
        if (lineCount == maxLines) {
            return;
        }
        float relPx = bx - ax;
        float relPy = by - ay;
        float relVx = avx - bvx;
        float relVy = avy - bvy;
        float distSq = relPx * relPx + relPy * relPy;
        float rSq = r * r;
        float ldx;
        float ldy;
        float ux;
        float uy;
        if (distSq > rSq) {
            float invH = 1f / horizon;
            // Relative velocity relative to the cut-off circle's centre.
            float wx = relVx - invH * relPx;
            float wy = relVy - invH * relPy;
            float wLenSq = wx * wx + wy * wy;
            float dot1 = wx * relPx + wy * relPy;
            if (dot1 < 0 && dot1 * dot1 > rSq * wLenSq) {
                // Project on the cut-off circle.
                float wLen = (float) Math.sqrt(wLenSq);
                float unitX = wx / wLen;
                float unitY = wy / wLen;
                ldx = unitY;
                ldy = -unitX;
                float s = r * invH - wLen;
                ux = s * unitX;
                uy = s * unitY;
            } else {
                // Project on a leg of the cone.
                float leg = (float) Math.sqrt(distSq - rSq);
                if (relPx * wy - relPy * wx > 0) {
                    ldx = (relPx * leg - relPy * r) / distSq;
                    ldy = (relPx * r + relPy * leg) / distSq;
                } else {
                    ldx = -(relPx * leg + relPy * r) / distSq;
                    ldy = -(-relPx * r + relPy * leg) / distSq;
                }
                float dot2 = relVx * ldx + relVy * ldy;
                ux = dot2 * ldx - relVx;
                uy = dot2 * ldy - relVy;
            }
        } else {
            // Already overlapping: resolve within one step.
            float invT = 1f / dt;
            float wx = relVx - invT * relPx;
            float wy = relVy - invT * relPy;
            float wLen = (float) Math.sqrt(wx * wx + wy * wy);
            if (wLen < EPSILON) {
                // Same place, same velocity: step aside on an arbitrary but fixed side.
                wx = 1;
                wy = 0;
                wLen = 1;
            }
            float unitX = wx / wLen;
            float unitY = wy / wLen;
            ldx = unitY;
            ldy = -unitX;
            float s = r * invT - wLen;
            ux = s * unitX;
            uy = s * unitY;
        }
        int i = lineCount++;
        px[i] = avx + responsibility * ux;
        py[i] = avy + responsibility * uy;
        dx[i] = ldx;
        dy[i] = ldy;
    }

    /**
     * Solves for the velocity nearest to {@code (prefX, prefY)} within {@code maxSpeed} that
     * satisfies the constraints (or violates them least); the answer goes to {@link #resultX} and
     * {@link #resultY}.
     */
    public void solve(float prefX, float prefY, float maxSpeed) {
        int fail = program2(px, py, dx, dy, lineCount, maxSpeed, prefX, prefY, false);
        if (fail < lineCount) {
            program3(fail, maxSpeed);
        }
    }

    private static float det(float ax, float ay, float bx, float by) {
        return ax * by - ay * bx;
    }

    /** Optimizes along line {@code no} subject to lines {@code 0..no); false when infeasible. */
    private boolean program1(
            float[] lpx,
            float[] lpy,
            float[] ldx,
            float[] ldy,
            int no,
            float radius,
            float optX,
            float optY,
            boolean directionOpt) {
        float dot = lpx[no] * ldx[no] + lpy[no] * ldy[no];
        float disc = dot * dot + radius * radius - (lpx[no] * lpx[no] + lpy[no] * lpy[no]);
        if (disc < 0) {
            return false; // the speed circle misses the line
        }
        float sq = (float) Math.sqrt(disc);
        float tLeft = -dot - sq;
        float tRight = -dot + sq;
        for (int i = 0; i < no; i++) {
            float denom = det(ldx[no], ldy[no], ldx[i], ldy[i]);
            float numer = det(ldx[i], ldy[i], lpx[no] - lpx[i], lpy[no] - lpy[i]);
            if (Math.abs(denom) <= EPSILON) {
                if (numer < 0) {
                    return false; // parallel and on the wrong side
                }
                continue;
            }
            float t = numer / denom;
            if (denom >= 0) {
                tRight = Math.min(tRight, t);
            } else {
                tLeft = Math.max(tLeft, t);
            }
            if (tLeft > tRight) {
                return false;
            }
        }
        float t;
        if (directionOpt) {
            t = optX * ldx[no] + optY * ldy[no] > 0 ? tRight : tLeft;
        } else {
            t = ldx[no] * (optX - lpx[no]) + ldy[no] * (optY - lpy[no]);
            t = Math.clamp(t, tLeft, tRight);
        }
        resultX = lpx[no] + t * ldx[no];
        resultY = lpy[no] + t * ldy[no];
        return true;
    }

    /** Returns the count when every line is met, else the line that failed. */
    private int program2(
            float[] lpx,
            float[] lpy,
            float[] ldx,
            float[] ldy,
            int count,
            float radius,
            float optX,
            float optY,
            boolean directionOpt) {
        if (directionOpt) {
            resultX = optX * radius;
            resultY = optY * radius;
        } else {
            float lenSq = optX * optX + optY * optY;
            if (lenSq > radius * radius) {
                float s = radius / (float) Math.sqrt(lenSq);
                resultX = optX * s;
                resultY = optY * s;
            } else {
                resultX = optX;
                resultY = optY;
            }
        }
        for (int i = 0; i < count; i++) {
            if (det(ldx[i], ldy[i], lpx[i] - resultX, lpy[i] - resultY) > 0) {
                float keepX = resultX;
                float keepY = resultY;
                if (!program1(lpx, lpy, ldx, ldy, i, radius, optX, optY, directionOpt)) {
                    resultX = keepX;
                    resultY = keepY;
                    return i;
                }
            }
        }
        return count;
    }

    /** Minimizes the largest violation when the constraints cannot all be met. */
    private void program3(int begin, float radius) {
        float distance = 0;
        for (int i = begin; i < lineCount; i++) {
            if (det(dx[i], dy[i], px[i] - resultX, py[i] - resultY) <= distance) {
                continue;
            }
            int n = 0;
            for (int j = 0; j < i; j++) {
                float d = det(dx[i], dy[i], dx[j], dy[j]);
                float lx;
                float ly;
                if (Math.abs(d) <= EPSILON) {
                    if (dx[i] * dx[j] + dy[i] * dy[j] > 0) {
                        continue; // same direction
                    }
                    lx = 0.5f * (px[i] + px[j]);
                    ly = 0.5f * (py[i] + py[j]);
                } else {
                    float s = det(dx[j], dy[j], px[i] - px[j], py[i] - py[j]) / d;
                    lx = px[i] + s * dx[i];
                    ly = py[i] + s * dy[i];
                }
                float ddx = dx[j] - dx[i];
                float ddy = dy[j] - dy[i];
                float len = (float) Math.sqrt(ddx * ddx + ddy * ddy);
                if (len < EPSILON) {
                    continue;
                }
                qx[n] = lx;
                qy[n] = ly;
                ex[n] = ddx / len;
                ey[n] = ddy / len;
                n++;
            }
            float keepX = resultX;
            float keepY = resultY;
            if (program2(qx, qy, ex, ey, n, radius, -dy[i], dx[i], true) < n) {
                resultX = keepX;
                resultY = keepY;
            }
            distance = det(dx[i], dy[i], px[i] - resultX, py[i] - resultY);
        }
    }
}
