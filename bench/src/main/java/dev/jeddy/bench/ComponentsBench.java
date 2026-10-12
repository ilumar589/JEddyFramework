package dev.jeddy.bench;

import dev.jeddy.bench.components.BenchGame;
import dev.jeddy.bench.components.Health;
import dev.jeddy.bench.components.HealthStore;
import dev.jeddy.scene.EntityPool;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * The cost of one tick over 10,000 entities three ways (DESIGN §22, D105–D107, lesson L03): an
 * object per entity with a virtual {@code update()} (the {@code MonoBehaviour} shape; three
 * subclasses keep the call site megamorphic as a real game's would be), a hand-written loop over
 * SoA columns, and the generated registry calling the static behaviour through the flyweight views.
 * The third should cost what the second does: the views inline away.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ComponentsBench {
    private static final int N = 10_000;

    /** The object-per-entity shape. */
    abstract static class Unit {
        int current;
        int max;

        abstract void update();
    }

    static final class Warrior extends Unit {
        @Override
        void update() {
            if (current < max) {
                current++;
            }
        }
    }

    static final class Archer extends Unit {
        @Override
        void update() {
            if (current < max) {
                current++;
            }
        }
    }

    static final class Mage extends Unit {
        @Override
        void update() {
            if (current < max) {
                current++;
            }
        }
    }

    private final Unit[] objects = new Unit[N];
    private final int[] soaCurrent = new int[N];
    private final int[] soaMax = new int[N];
    private BenchGame game;

    @Setup(Level.Trial)
    public void setup() {
        EntityPool pool = new EntityPool(N + 16);
        game = new BenchGame(pool, null);
        for (int i = 0; i < N; i++) {
            Unit u =
                    switch (i % 3) {
                        case 0 -> new Warrior();
                        case 1 -> new Archer();
                        default -> new Mage();
                    };
            u.current = i % 50;
            u.max = 1_000_000_000; // never reached: every tick does the same work
            objects[i] = u;
            soaCurrent[i] = i % 50;
            soaMax[i] = 1_000_000_000;
            game.health().add(pool.create(), new Health(i % 50, 1_000_000_000));
        }
    }

    /** An object per entity, a virtual call per entity (megamorphic). */
    @Benchmark
    public int objectsWithVirtualUpdate() {
        Unit[] o = objects;
        for (int i = 0; i < N; i++) {
            o[i].update();
        }
        return o[0].current;
    }

    /** The hand-written SoA loop: the lower bound. */
    @Benchmark
    public int soaColumnsByHand() {
        int[] c = soaCurrent;
        int[] m = soaMax;
        for (int i = 0; i < N; i++) {
            if (c[i] < m[i]) {
                c[i]++;
            }
        }
        return c[0];
    }

    /** The generated loop: the sparse-set membership test, the flyweight, the static call. */
    @Benchmark
    public int generatedBehaviour() {
        game.fixedUpdate(1f / 30);
        HealthStore h = game.health();
        return h.current()[0];
    }
}
