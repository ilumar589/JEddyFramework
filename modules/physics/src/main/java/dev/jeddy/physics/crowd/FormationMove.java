package dev.jeddy.physics.crowd;

import dev.jeddy.physics.nav.NavMeshQuery;
import dev.jeddy.physics.nav.QueryFilter;

/**
 * Moves a group of crowd agents into a {@link Formation} (DESIGN §11.4): places the slots on the
 * navmesh at the click point and facing, assigns members to slots by minimum total distance, sends
 * each to its slot, and paces them every tick so they arrive together: the member with furthest to
 * go runs at up to {@code catchUp} times its speed, the others slow down (to no less than {@code
 * minPace} times) to match. Speeds go back to normal on arrival.
 *
 * <p>Thread ownership: the game thread, between crowd updates. Allocates nothing per call.
 */
public final class FormationMove {
    private final Crowd crowd;
    private final NavMeshQuery query;
    private final int[] members;
    private final float[] baseSpeed;
    private final float[] positions;
    private final float[] slots;
    private final int[] slotOf;
    private final float[] eta;
    private int count;
    private float catchUp = 1.3f;
    private float minPace = 0.7f;
    private float margin = 0.5f;

    /** Creates a mover for groups of up to {@code maxMembers} agents of {@code crowd}. */
    public FormationMove(Crowd crowd, NavMeshQuery query, int maxMembers) {
        this.crowd = crowd;
        this.query = query;
        members = new int[maxMembers];
        baseSpeed = new float[maxMembers];
        positions = new float[maxMembers * 3];
        slots = new float[maxMembers * 3];
        slotOf = new int[maxMembers];
        eta = new float[maxMembers];
    }

    /** Sets the pacing limits (fastest catch-up and slowest pace, as factors of normal speed). */
    public void setPacing(float catchUpFactor, float minPaceFactor) {
        catchUp = catchUpFactor;
        minPace = minPaceFactor;
    }

    /** Sets how far short of walls slots stop when pulled back (meters). */
    public void setWallMargin(float meters) {
        margin = meters;
    }

    /**
     * Orders agents {@code agents[0..n)} into {@code formation} at {@code (x, y, z)} facing {@code
     * (dirX, dirZ)}; a zero facing means "from the group's centre toward the click". Ends any
     * previous order of this mover (restoring speeds).
     */
    public void order(
            int[] agents,
            int n,
            Formation formation,
            float x,
            float y,
            float z,
            float dirX,
            float dirZ) {
        release();
        n = Math.min(n, Math.min(members.length, formation.size()));
        count = n;
        float cx = 0;
        float cz = 0;
        for (int k = 0; k < n; k++) {
            int a = agents[k];
            members[k] = a;
            baseSpeed[k] = crowd.maxSpeed(a);
            positions[k * 3] = crowd.x(a);
            positions[k * 3 + 1] = crowd.y(a);
            positions[k * 3 + 2] = crowd.z(a);
            cx += crowd.x(a) / n;
            cz += crowd.z(a) / n;
        }
        if (dirX * dirX + dirZ * dirZ < 1e-8f) {
            dirX = x - cx;
            dirZ = z - cz;
        }
        QueryFilter filter = crowd.filter(0);
        formation.place(x, y, z, dirX, dirZ, n, query, filter, margin, slots);
        formation.assign(positions, slots, n, slotOf);
        for (int k = 0; k < n; k++) {
            int s = slotOf[k] * 3;
            crowd.requestMoveTarget(members[k], slots[s], slots[s + 1], slots[s + 2]);
        }
    }

    /**
     * Returns the slot (world x, y, z in {@code out}) member {@code k} of the order is heading to.
     */
    public void slotOf(int k, float[] out) {
        int s = slotOf[k] * 3;
        out[0] = slots[s];
        out[1] = slots[s + 1];
        out[2] = slots[s + 2];
    }

    /** Returns the number of members in the current order. */
    public int count() {
        return count;
    }

    /** Re-paces the members (call once per tick before {@link Crowd#update}). */
    public void update() {
        if (count == 0) {
            return;
        }
        // Each moving member's time to arrive at normal speed (straight-line estimate).
        int moving = 0;
        float slowest = 0;
        for (int k = 0; k < count; k++) {
            int a = members[k];
            int ts = crowd.targetState(a);
            if (ts != Crowd.TARGET_VALID && ts != Crowd.TARGET_REQUESTING) {
                crowd.setMaxSpeed(a, baseSpeed[k]);
                continue;
            }
            int s = slotOf[k] * 3;
            float dx = slots[s] - crowd.x(a);
            float dz = slots[s + 2] - crowd.z(a);
            float t = (float) Math.sqrt(dx * dx + dz * dz) / baseSpeed[k];
            eta[moving++] = t;
            slowest = Math.max(slowest, t);
        }
        if (moving == 0) {
            release();
            return;
        }
        // The group's arrival time: the median member at normal speed, unless the last one cannot
        // make that even at catch-up speed.
        sortPrefix(eta, moving);
        float median = eta[moving / 2];
        float groupTime = Math.max(slowest / catchUp, median);
        for (int k = 0; k < count; k++) {
            int a = members[k];
            int ts = crowd.targetState(a);
            if (ts != Crowd.TARGET_VALID && ts != Crowd.TARGET_REQUESTING) {
                continue;
            }
            int s = slotOf[k] * 3;
            float dx = slots[s] - crowd.x(a);
            float dz = slots[s + 2] - crowd.z(a);
            float t = (float) Math.sqrt(dx * dx + dz * dz) / baseSpeed[k];
            float pace = groupTime > 1e-4f ? t / groupTime : 1;
            crowd.setMaxSpeed(a, baseSpeed[k] * Math.clamp(pace, minPace, catchUp));
        }
    }

    /** Ends the order: members keep their targets but get their normal speeds back. */
    public void release() {
        for (int k = 0; k < count; k++) {
            if (crowd.isActive(members[k])) {
                crowd.setMaxSpeed(members[k], baseSpeed[k]);
            }
        }
        count = 0;
    }

    private static void sortPrefix(float[] a, int n) {
        for (int i = 1; i < n; i++) {
            float key = a[i];
            int j = i - 1;
            while (j >= 0 && a[j] > key) {
                a[j + 1] = a[j];
                j--;
            }
            a[j + 1] = key;
        }
    }
}
