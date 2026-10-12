package dev.jeddy.bench.components;

import dev.jeddy.components.Behaviour;
import dev.jeddy.components.OnFixedUpdate;

/** One point a tick up to the maximum: the loop body of every variant in the benchmark. */
@Behaviour
public final class Regen {
    private Regen() {}

    @OnFixedUpdate
    public static void regen(HealthMut h) {
        int c = h.current();
        if (c < h.max()) {
            h.setCurrent(c + 1);
        }
    }
}
