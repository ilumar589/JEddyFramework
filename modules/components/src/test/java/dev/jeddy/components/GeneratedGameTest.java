package dev.jeddy.components;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.asset.format.ComponentSchema;
import dev.jeddy.components.sample.Counts;
import dev.jeddy.components.sample.Dead;
import dev.jeddy.components.sample.Health;
import dev.jeddy.components.sample.HealthStore;
import dev.jeddy.components.sample.Lifecycle;
import dev.jeddy.components.sample.Position;
import dev.jeddy.components.sample.PositionStore;
import dev.jeddy.components.sample.SampleGame;
import dev.jeddy.components.sample.Tagged;
import dev.jeddy.components.sample.TaggedStore;
import dev.jeddy.components.sample.Team;
import dev.jeddy.core.job.JobSystem;
import dev.jeddy.math.Vec3f;
import dev.jeddy.scene.EntityPool;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

/**
 * The code the processor generated for the sample package (DESIGN §22): stores, views, the
 * registry's loops and phases, parallel groups, snapshots and the schema. The generated sources are
 * under the test build's generated directory; read them next to this test.
 */
class GeneratedGameTest {
    private static SampleGame game(EntityPool pool, JobSystem jobs, Counts counts) {
        return new SampleGame(pool, jobs, counts);
    }

    @Test
    void storesAddRemoveAndSwapRowsLikeSparseSets() {
        EntityPool pool = new EntityPool(64);
        SampleGame g = game(pool, null, new Counts());
        HealthStore health = g.health();
        int a = pool.create();
        int b = pool.create();
        int c = pool.create();
        assertEquals(0, health.add(a));
        assertEquals(1, health.add(b, new Health(3, 5)));
        assertEquals(2, health.add(c));
        assertEquals(0, health.add(a), "adding again keeps the row");
        assertEquals(3, health.size());
        assertEquals(10, health.current()[0], "the @Default");
        assertEquals(3, health.current()[1]);
        assertEquals(5, health.max()[1]);
        assertTrue(health.has(b));
        assertEquals(new Health(3, 5), health.get(b));
        health.remove(a);
        assertEquals(2, health.size());
        assertEquals(c, health.entityAt(0), "the last row filled the hole");
        assertEquals(0, health.indexOf(c));
        assertEquals(-1, health.indexOf(a));
        assertNull(health.get(a));
        pool.destroy(b);
        assertEquals(1, health.size(), "destroying the entity drops its row");
        assertEquals(-1, health.indexOf(b));
        assertThrows(IllegalArgumentException.class, () -> health.add(b), "a stale handle");
        // Growth past the capacity.
        for (int i = 0; i < 20; i++) {
            health.add(pool.create(), new Health(i, i));
        }
        assertEquals(21, health.size());
        assertTrue(health.capacity() >= 21);
        assertEquals(19, health.current()[20]);
    }

    @Test
    void everyFieldTypeRoundTripsThroughTheRecordAndTheColumns() {
        EntityPool pool = new EntityPool(64);
        SampleGame g = game(pool, null, new Counts());
        int e = pool.create();
        int target = pool.create();
        TaggedStore tagged = g.tagged();
        int row = tagged.add(e);
        assertEquals(new Tagged(Team.UNDEAD, 0, 0, true, 1234567890123L), tagged.at(row));
        tagged.add(e, new Tagged(Team.NEUTRAL, target, 42, false, -1L));
        assertEquals(new Tagged(Team.NEUTRAL, target, 42, false, -1L), tagged.get(e));
        // The generic column API, by name.
        assertEquals(5, tagged.columnCount());
        assertEquals("team", tagged.columnName(0));
        assertEquals(Store.INT, tagged.columnType(0));
        assertEquals(Store.LONG, tagged.columnType(4));
        assertEquals(Store.BOOL, tagged.columnType(3));
        assertEquals(1, tagged.columnIndex("target"));
        assertEquals(-1, tagged.columnIndex("nope"));
        assertEquals(2, tagged.enumOrdinal(0, "NEUTRAL"));
        assertEquals(-1, tagged.enumOrdinal(0, "GHOST"));
        assertEquals(-1, tagged.enumOrdinal(1, "NEUTRAL"), "not an enum column");
        assertEquals("PARTY", tagged.enumName(0, 0));
        assertNull(tagged.enumName(1, 0));
        tagged.setInt(row, 0, Team.PARTY.ordinal());
        tagged.setBool(row, 3, true);
        tagged.setLong(row, 4, 7L);
        assertEquals(Team.PARTY, tagged.at(row).team());
        assertTrue(tagged.getBool(row, 3));
        assertEquals(7L, tagged.getLong(row, 4));
        assertThrows(IllegalArgumentException.class, () -> tagged.getFloat(row, 0));
        // Vectors: three float columns.
        PositionStore position = g.position();
        int p = position.add(e);
        assertEquals(new Position(new Vec3f(1, 2, 3), 0), position.at(p), "the vector default");
        assertEquals(List3.of("pos.x", "pos.y", "pos.z", "yaw"), List3.columns(position));
        position.setFloat(p, 2, 9f);
        assertEquals(9f, position.posZ()[p]);
        assertEquals(new Position(new Vec3f(1, 2, 9), 0), position.get(e));
        // A tag component has no columns and still works as a set.
        g.dead().add(e);
        assertEquals(0, g.dead().columnCount());
        assertTrue(g.dead().has(e));
        assertEquals(new Dead(), g.dead().get(e));
    }

    /** Small helper: column names as a list. */
    private static final class List3 {
        static java.util.List<String> of(String... s) {
            return java.util.List.of(s);
        }

        static java.util.List<String> columns(Store s) {
            java.util.List<String> out = new java.util.ArrayList<>();
            for (int c = 0; c < s.columnCount(); c++) {
                out.add(s.columnName(c));
            }
            return out;
        }
    }

    @Test
    void fixedUpdateRunsTheQueriesWithWithoutAndOptional() {
        EntityPool pool = new EntityPool(64);
        Counts counts = new Counts();
        SampleGame g = game(pool, null, counts);
        int alive = pool.create();
        int dead = pool.create();
        int tagged = pool.create();
        int untracked = pool.create();
        g.health().add(alive, new Health(1, 10));
        g.health().add(dead, new Health(1, 10));
        g.dead().add(dead);
        g.health().add(tagged, new Health(1, 10));
        g.tagged().add(tagged);
        g.position().add(untracked); // no health: not in the regen query
        g.fixedUpdate(0.5f);
        assertEquals(2, g.health().get(alive).current(), "regenerated");
        assertEquals(1, g.health().get(dead).current(), "@Without(Dead)");
        assertEquals(2, g.health().get(tagged).current());
        assertEquals(1, counts.healedWithTag, "the optional view was present once");
        assertEquals(2, counts.healedWithoutTag);
    }

    @Test
    void aBehaviourMayDespawnTheEntityItVisits() {
        EntityPool pool = new EntityPool(64);
        Counts counts = new Counts();
        SampleGame g = game(pool, null, counts);
        int[] e = new int[10];
        for (int i = 0; i < e.length; i++) {
            e[i] = pool.create();
            // Health 0 for every other entity: the reaper removes them during its loop, and the
            // regen that runs before it does not bring them above 0 (it is ordered first but
            // adds a point; so give them -5).
            g.health().add(e[i], new Health(i % 2 == 0 ? -5 : 5, 10));
            g.position().add(e[i]);
        }
        g.fixedUpdateStage(0, 0.1f);
        assertEquals(10, g.health().size(), "the reaper is in stage 1");
        g.fixedUpdateStage(1, 0.1f);
        assertEquals(5, g.health().size(), "every other entity was despawned");
        g.fixedUpdate(0.1f); // both stages: nothing else dies (regen keeps the living above 0)
        assertEquals(5, g.position().size(), "despawn removes every component");
        assertEquals(5, counts.despawned);
        for (int i = 0; i < e.length; i++) {
            assertEquals(i % 2 != 0, g.health().has(e[i]), "entity " + i);
            assertTrue(pool.isAlive(e[i]), "despawn leaves the entity to the game");
        }
    }

    @Test
    void parallelBehavioursGiveTheSameResultAsInlineOnes() {
        int n = 37;
        float[] inline = runMovers(null, n);
        try (JobSystem jobs = new JobSystem(3)) {
            float[] parallel = runMovers(jobs, n);
            assertArrayEquals(inline, parallel, 1e-6f);
        }
    }

    private static float[] runMovers(JobSystem jobs, int n) {
        EntityPool pool = new EntityPool(256);
        SampleGame g = game(pool, jobs, new Counts());
        int[] e = new int[n];
        for (int i = 0; i < n; i++) {
            e[i] = pool.create();
            g.position().add(e[i], new Position(new Vec3f(i, 0, 0), 0));
            if (i % 3 != 0) {
                g.health().add(e[i], new Health(i, 100));
            }
        }
        for (int tick = 0; tick < 5; tick++) {
            g.fixedUpdate(0.25f);
            g.update(0.1f, 0.5f);
        }
        float[] out = new float[n * 4];
        for (int i = 0; i < n; i++) {
            Position p = g.position().get(e[i]);
            out[i * 4] = p.pos().x();
            out[i * 4 + 1] = p.pos().y();
            out[i * 4 + 2] = p.yaw();
            out[i * 4 + 3] = g.health().has(e[i]) ? g.health().get(e[i]).max() : -1;
        }
        // Sanity on the values themselves: turnLeft added 5 * 0.25 of yaw, drift 5 * 0.1 * 0.5.
        assertEquals(1.25f, out[2], 1e-6f);
        assertEquals(0.25f, out[0] - 0, 1e-6f);
        return out;
    }

    @Test
    void lifecyclePhasesReachTheMatchingEntities() {
        EntityPool pool = new EntityPool(64);
        Counts counts = new Counts();
        SampleGame g = game(pool, null, counts);
        int e = pool.create();
        g.health().add(e, new Health(3, 9));
        g.position().add(e);
        int other = pool.create(); // no components: nothing runs for it
        g.spawn(e);
        g.spawn(other);
        assertEquals(1, counts.spawned);
        assertEquals(9, g.health().get(e).current(), "spawned() filled it up");
        g.turn(e);
        g.turn(other);
        assertEquals(1, counts.turns);
        g.trigger(5, e, OnTrigger.ENTER);
        g.trigger(5, other, OnTrigger.ENTER);
        g.trigger(6, e, OnTrigger.EXIT);
        g.trigger(6, e, 99); // an unknown kind is ignored
        assertEquals(1, counts.entered);
        assertEquals(51, counts.lastTrigger);
        assertEquals(1, counts.exited);
        g.dispatch(new int[] {7, e, OnTrigger.ENTER, 8, e, OnTrigger.EXIT, 9, other, 1}, 3);
        assertEquals(2, counts.entered);
        assertEquals(2, counts.exited);
        g.signal(Lifecycle.HURT, e, 4, 2);
        g.signal(Lifecycle.HURT, other, 4, 2);
        g.signal(12345, e, 1, 1);
        assertEquals(1, counts.signalled);
        assertEquals(4, counts.lastA);
        assertEquals(2, counts.lastB);
        assertEquals(5, g.health().get(e).current());
        g.despawn(e);
        assertEquals(1, counts.despawned);
        assertFalse(g.hasAny(e));
        assertTrue(pool.isAlive(e));
        g.despawn(e);
        assertEquals(1, counts.despawned, "nothing to run the second time");
    }

    @Test
    void snapshotsRoundTripAndTolerateChangedStores() {
        EntityPool pool = new EntityPool(64);
        SampleGame g = game(pool, null, new Counts());
        int a = pool.create();
        int b = pool.create();
        g.health().add(a, new Health(4, 8));
        g.position().add(b, new Position(new Vec3f(1, 2, 3), 0.5f));
        g.tagged().add(b, new Tagged(Team.NEUTRAL, a, 3, false, 99L));
        g.dead().add(a);
        SnapshotWriter w = new SnapshotWriter();
        g.write(w);
        byte[] bytes = w.toBytes();
        // Change everything, then restore.
        g.health().add(a, new Health(0, 0));
        g.health().add(b);
        g.position().remove(b);
        g.dead().remove(a);
        g.read(new SnapshotReader(bytes));
        assertEquals(new Health(4, 8), g.health().get(a));
        assertEquals(1, g.health().size());
        assertEquals(new Position(new Vec3f(1, 2, 3), 0.5f), g.position().get(b));
        assertEquals(new Tagged(Team.NEUTRAL, a, 3, false, 99L), g.tagged().get(b));
        assertTrue(g.dead().has(a));
        assertFalse(g.dead().has(b));
        // A snapshot of a store this build does not have is skipped; a column it lacks is
        // defaulted; a column the file lacks keeps its default.
        SnapshotWriter w2 = new SnapshotWriter();
        w2.int32(2);
        w2.string("Ghost");
        w2.int32(1);
        w2.string("boo");
        w2.int32(Store.FLOAT);
        w2.int32(1);
        w2.int32(a);
        w2.float32(1.5f);
        w2.string("Health");
        w2.int32(2);
        w2.string("current");
        w2.int32(Store.INT);
        w2.string("armor");
        w2.int32(Store.INT);
        w2.int32(1);
        w2.int32(b);
        w2.int32(6);
        w2.int32(77);
        g.read(new SnapshotReader(w2.toBytes()));
        assertEquals(new Health(6, 10), g.health().get(b), "max kept its default");
        assertFalse(g.health().has(a));
        assertThrows(IllegalStateException.class, () -> g.read(new SnapshotReader(new byte[2])));
    }

    @Test
    void theSchemaDescribesEveryComponentAndBehaviour() throws IOException {
        ComponentSchema schema;
        try (InputStream in =
                SampleGame.class.getResourceAsStream("/" + ComponentSchema.RESOURCE)) {
            assertNotNull(in, "the processor wrote the schema into the class output");
            schema = ComponentSchema.parse(in.readAllBytes());
        }
        assertEquals("dev.jeddy.components.sample.SampleGame", schema.registry());
        assertEquals(4, schema.components().size());
        ComponentSchema.Component health = schema.component("Health");
        assertNotNull(health);
        assertEquals("Hit points", health.doc());
        ComponentSchema.Field current = health.field("current");
        assertEquals(ComponentSchema.Type.INT, current.type());
        assertEquals(10, current.defaults()[0]);
        assertTrue(current.hasRange());
        assertEquals(100, current.max());
        assertEquals("Current", current.doc());
        ComponentSchema.Field pos = schema.component("Position").field("pos");
        assertEquals(ComponentSchema.Type.VEC3, pos.type());
        assertArrayEquals(new double[] {1, 2, 3}, pos.defaults());
        assertEquals(java.util.List.of("pos.x", "pos.y", "pos.z"), pos.columns());
        assertTrue(schema.component("Position").field("yaw").angle());
        assertEquals(pos, schema.component("Position").fieldOfColumn("pos.y"));
        ComponentSchema.Field team = schema.component("Tagged").field("team");
        assertEquals(ComponentSchema.Type.ENUM, team.type());
        assertEquals(java.util.List.of("PARTY", "UNDEAD", "NEUTRAL"), team.enumValues());
        assertEquals(1, team.defaults()[0], "the default UNDEAD as its ordinal");
        assertEquals(
                ComponentSchema.Type.ENTITY, schema.component("Tagged").field("target").type());
        assertEquals("mesh", schema.component("Tagged").field("mesh").assetKind());
        assertEquals(0, schema.component("Dead").fields().size());
        // Behaviours: phases and access.
        ComponentSchema.Behaviour regen = null;
        ComponentSchema.Behaviour rise = null;
        for (ComponentSchema.Behaviour b : schema.behaviours()) {
            if (b.name().equals("Regen.regen")) {
                regen = b;
            }
            if (b.name().equals("Movers.rise")) {
                rise = b;
            }
        }
        assertNotNull(regen);
        assertEquals("fixedUpdate", regen.phase());
        assertFalse(regen.parallel());
        assertEquals(java.util.List.of("Health"), regen.writes());
        assertEquals(java.util.List.of("Dead"), regen.without());
        assertNotNull(rise);
        assertTrue(rise.parallel());
        assertEquals(java.util.List.of("Position"), rise.writes());
        assertEquals(java.util.List.of("Health"), rise.reads());
        assertEquals(13, schema.behaviours().size());
    }

    @Test
    void levelRowsLoadIntoTheStoresByName() {
        dev.jeddy.asset.format.LevelData level = new dev.jeddy.asset.format.LevelData();
        int prefab = level.prefab("kit.gltf", "chest");
        int chest =
                level.placementId(
                        level.addPlacement(
                                prefab, "chest", "prop", 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1));
        int door =
                level.placementId(
                        level.addPlacement(
                                prefab, "door", "prop", 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1));
        int row = level.addComponentRow(chest, "Health");
        level.setComponentInt(row, "current", 3);
        row = level.addComponentRow(chest, "Tagged");
        level.setComponentEnum(row, "team", "NEUTRAL");
        level.setComponentEntity(row, "target", door);
        level.setComponentAsset(row, "mesh", "meshes/chest.jmesh");
        level.setComponentBool(row, "hostile", false);
        level.setComponentInt(row, "ghost", 1); // an unknown column: skipped
        level.setComponentEnum(row, "team", "NEUTRAL");
        row = level.addComponentRow(chest, "Position");
        level.setComponentFloat(row, "pos.y", 7f);
        level.addComponentRow(door, "Dead");
        level.addComponentRow(door, "Ghost"); // an unknown component: skipped
        level.addComponentRow(99, "Dead"); // an object with no entity: skipped

        EntityPool pool = new EntityPool(64);
        Counts counts = new Counts();
        SampleGame g = game(pool, null, counts);
        int chestEntity = pool.create();
        int doorEntity = pool.create();
        Levels.Binding binding =
                new Levels.Binding() {
                    @Override
                    public int entityOf(int objectId) {
                        return objectId == chest ? chestEntity : objectId == door ? doorEntity : 0;
                    }

                    @Override
                    public int assetOf(String kind, String path) {
                        assertEquals("mesh", kind);
                        return path.equals("meshes/chest.jmesh") ? 42 : 0;
                    }
                };
        assertEquals(4, g.load(level, binding), "rows applied");
        assertEquals(new Health(10, 10), g.health().get(chestEntity), "spawned() refilled it");
        assertEquals(1, counts.spawned, "spawn ran once per loaded entity with health");
        assertEquals(
                new Tagged(Team.NEUTRAL, doorEntity, 42, false, 1234567890123L),
                g.tagged().get(chestEntity));
        assertEquals(new Position(new Vec3f(1, 7, 3), 0), g.position().get(chestEntity));
        assertTrue(g.dead().has(doorEntity));
        assertFalse(g.health().has(doorEntity));
        g.despawn(chestEntity);
        assertEquals(3, g.loadObject(level, chest, chestEntity, binding));
        assertEquals(
                new Health(3, 10), g.health().get(chestEntity), "no spawn behaviour this time");
    }

    @Test
    void aTickAllocatesNothingInlineAndOnTheJobSystem() {
        try (JobSystem jobs = new JobSystem(2)) {
            for (JobSystem j : new JobSystem[] {null, jobs}) {
                EntityPool pool = new EntityPool(1024);
                Counts counts = new Counts();
                SampleGame g = game(pool, j, counts);
                for (int i = 0; i < 300; i++) {
                    int e = pool.create();
                    g.health().add(e, new Health(5, 1_000_000));
                    g.position().add(e);
                    if (i % 4 == 0) {
                        g.tagged().add(e);
                    }
                    if (i % 7 == 0) {
                        g.dead().add(e);
                    }
                }
                int e = pool.create();
                g.health().add(e, new Health(5, 1_000_000));
                g.position().add(e);
                Allocations.assertNoAllocation(
                        j == null ? "components tick, inline" : "components tick, jobs",
                        10,
                        200,
                        () -> {
                            g.fixedUpdate(0.01f);
                            g.update(0.01f, 0.5f);
                            g.turn(e);
                            g.trigger(1, e, OnTrigger.ENTER);
                            g.signal(Lifecycle.HURT, e, 0, 0);
                        });
            }
        }
    }
}
