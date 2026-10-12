package dev.jeddy.physics.crowd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.core.io.ByteReader;
import dev.jeddy.core.io.ByteWriter;
import dev.jeddy.physics.nav.NavMesh;
import dev.jeddy.physics.nav.NavMeshQuery;
import dev.jeddy.physics.nav.QueryFilter;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

/** Party formations: templates, placement, assignment, paced following (TASKS M8-09). */
class FormationTest {
    @BeforeAll
    static void bake() {
        CrowdTest.bake();
    }

    @Test
    void slotsFollowTheFacing() {
        Formation line = Formation.line(3, 1.5f);
        float[] out = new float[9];
        line.place(10, 0, 10, 1, 0, 3, null, null, 0, out);
        // Facing +x, "right" is +z (right-handed, Y up).
        assertEquals(10, out[0], 1e-5f);
        assertEquals(8.5f, out[2], 1e-5f);
        assertEquals(11.5f, out[8], 1e-5f);
        Formation column = Formation.column(3, 2);
        column.place(10, 0, 10, 0, 1, 3, null, null, 0, out);
        assertEquals(10, out[3], 1e-5f);
        assertEquals(8, out[5], 1e-5f, "the second member is behind the first");
        Formation box = Formation.box(6, 3, 1.5f);
        box.place(0, 0, 0, 0, 1, 6, null, null, 0, out = new float[18]);
        float cx = 0;
        float cz = 0;
        for (int i = 0; i < 6; i++) {
            cx += out[i * 3] / 6;
            cz += out[i * 3 + 2] / 6;
        }
        assertEquals(0, cx, 1e-5f, "a box is centred on the click");
        assertEquals(0, cz, 1e-5f);
    }

    @Test
    void slotsBehindWallsArePulledBack() {
        NavMesh mesh = new NavMesh(CrowdTest.room);
        NavMeshQuery q = new NavMeshQuery(mesh, 512);
        QueryFilter f = new QueryFilter();
        Formation column = Formation.column(3, 1.5f);
        float[] out = new float[9];
        // Facing −x right next to the block: the members behind would stand in it.
        int placed = column.place(7, 0, 10, -1, 0, 3, q, f, 0.5f, out);
        assertEquals(3, placed);
        for (int i = 0; i < 3; i++) {
            assertTrue(out[i * 3] < 7.6f, "slot " + i + " outside the block: " + out[i * 3]);
            assertEquals(10, out[i * 3 + 2], 0.05f);
        }
    }

    @Test
    void assignmentMinimizesTotalDistance() {
        SplittableRandom rnd = new SplittableRandom(5);
        Formation f = Formation.box(6, 3, 1.5f);
        float[] members = new float[18];
        float[] slots = new float[18];
        int[] slotOf = new int[6];
        int[] perm = new int[6];
        for (int trial = 0; trial < 50; trial++) {
            for (int i = 0; i < 18; i++) {
                members[i] = (float) rnd.nextDouble(-10, 10);
                slots[i] = (float) rnd.nextDouble(-10, 10);
            }
            f.assign(members, slots, 6, slotOf);
            boolean[] seen = new boolean[6];
            for (int s : slotOf) {
                assertTrue(!seen[s]);
                seen[s] = true;
            }
            float best = bestTotal(members, slots, perm, 0, new boolean[6]);
            assertEquals(best, total(members, slots, slotOf), 1e-3f);
        }
        Allocations.assertNoAllocation(
                "Formation.assign", () -> f.assign(members, slots, 6, slotOf));
    }

    static float total(float[] members, float[] slots, int[] slotOf) {
        float sum = 0;
        for (int i = 0; i < slotOf.length; i++) {
            float dx = members[i * 3] - slots[slotOf[i] * 3];
            float dz = members[i * 3 + 2] - slots[slotOf[i] * 3 + 2];
            sum += (float) Math.sqrt(dx * dx + dz * dz);
        }
        return sum;
    }

    static float bestTotal(float[] members, float[] slots, int[] perm, int k, boolean[] used) {
        if (k == perm.length) {
            return total(members, slots, perm);
        }
        float best = Float.MAX_VALUE;
        for (int s = 0; s < perm.length; s++) {
            if (!used[s]) {
                used[s] = true;
                perm[k] = s;
                best = Math.min(best, bestTotal(members, slots, perm, k + 1, used));
                used[s] = false;
            }
        }
        return best;
    }

    @Test
    void aPartyArrivesTogetherInFormation() {
        NavMesh mesh = new NavMesh(CrowdTest.field);
        Crowd c = new Crowd(mesh, 8, 0, 0, 30, 30, null);
        AgentParams p = new AgentParams();
        int[] party = new int[6];
        for (int k = 0; k < 5; k++) {
            party[k] = c.addAgent(5 + k, 0, 5, p);
        }
        party[5] = c.addAgent(5, 0, 1, p); // lagging behind
        FormationMove move = new FormationMove(c, new NavMeshQuery(mesh, 512), 6);
        Formation box = Formation.box(6, 3, 1.5f);
        move.order(party, 6, box, 15, 0, 20, 0, 1);
        float[] arrivedAt = new float[6];
        float[] slot = new float[3];
        for (int t = 0; t < 30 * 15; t++) {
            move.update();
            c.update(CrowdTest.DT);
            for (int k = 0; k < 6; k++) {
                if (arrivedAt[k] == 0 && c.targetState(party[k]) == Crowd.TARGET_ARRIVED) {
                    arrivedAt[k] = t * CrowdTest.DT;
                }
            }
        }
        float first = Float.MAX_VALUE;
        float last = 0;
        for (int k = 0; k < 6; k++) {
            assertTrue(arrivedAt[k] > 0, "member " + k + " arrived");
            first = Math.min(first, arrivedAt[k]);
            last = Math.max(last, arrivedAt[k]);
            move.slotOf(k, slot);
            assertEquals(slot[0], c.x(party[k]), 0.25f);
            assertEquals(slot[2], c.z(party[k]), 0.25f);
            assertEquals(p.maxSpeed, c.maxSpeed(party[k]), 1e-6f, "speeds restored");
        }
        assertTrue(last - first < 1.6f, "arrivals within " + (last - first) + " s");
    }

    @Test
    void aFormationWalkContinuesAfterASave() {
        NavMesh mesh = new NavMesh(CrowdTest.field);
        Crowd c = new Crowd(mesh, 8, 0, 0, 30, 30, null);
        AgentParams p = new AgentParams();
        int[] party = new int[6];
        for (int k = 0; k < 5; k++) {
            party[k] = c.addAgent(5 + k, 0, 5, p);
        }
        party[5] = c.addAgent(5, 0, 1, p);
        FormationMove move = new FormationMove(c, new NavMeshQuery(mesh, 512), 6);
        move.order(party, 6, Formation.box(6, 3, 1.5f), 15, 0, 20, 0, 1);
        for (int t = 0; t < 45; t++) {
            move.update();
            c.update(CrowdTest.DT);
        }
        ByteWriter w = new ByteWriter();
        c.write(w);
        move.write(w);
        // Another run: the crowd and the walk come back and the party still arrives together.
        Crowd c2 = new Crowd(mesh, 8, 0, 0, 30, 30, null);
        FormationMove move2 = new FormationMove(c2, new NavMeshQuery(mesh, 512), 6);
        ByteReader r = new ByteReader(w.toBytes());
        c2.read(r);
        move2.read(r);
        assertEquals(6, move2.count());
        float[] slot = new float[3];
        float[] slot2 = new float[3];
        for (int k = 0; k < 6; k++) {
            move.slotOf(k, slot);
            move2.slotOf(k, slot2);
            assertEquals(slot[0], slot2[0]);
            assertEquals(slot[2], slot2[2]);
        }
        float[] arrivedAt = new float[6];
        for (int t = 0; t < 30 * 15; t++) {
            move2.update();
            c2.update(CrowdTest.DT);
            for (int k = 0; k < 6; k++) {
                if (arrivedAt[k] == 0 && c2.targetState(party[k]) == Crowd.TARGET_ARRIVED) {
                    arrivedAt[k] = t * CrowdTest.DT;
                }
            }
        }
        float first = Float.MAX_VALUE;
        float last = 0;
        for (int k = 0; k < 6; k++) {
            assertTrue(arrivedAt[k] > 0, "member " + k + " arrived");
            first = Math.min(first, arrivedAt[k]);
            last = Math.max(last, arrivedAt[k]);
            assertEquals(p.maxSpeed, c2.maxSpeed(party[k]), 1e-6f, "speeds restored");
        }
        assertTrue(last - first < 1.6f, "arrivals within " + (last - first) + " s");
    }
}
