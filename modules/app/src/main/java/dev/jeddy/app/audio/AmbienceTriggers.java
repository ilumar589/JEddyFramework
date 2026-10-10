package dev.jeddy.app.audio;

import dev.jeddy.audio.Ambience;
import dev.jeddy.physics.trigger.Triggers;

/**
 * Drives ambient zones from trigger volumes (TASKS M9-05, M8-13): when the listener's entity (the
 * camera focus or the party leader) enters or leaves a trigger, the {@link Ambience} zone with the
 * trigger's id starts or stops. Triggers without a zone are ignored.
 *
 * <p>Thread ownership: the game thread, after {@code Triggers.update}. Allocates nothing.
 */
public final class AmbienceTriggers {
    private final Ambience ambience;
    private int listener;

    /** Feeds {@code ambience} with the trigger events of entity {@code listenerEntity}. */
    public AmbienceTriggers(Ambience ambience, int listenerEntity) {
        this.ambience = ambience;
        this.listener = listenerEntity;
    }

    /** Changes the entity whose trigger events count (a new party leader). */
    public void setListenerEntity(int entity) {
        listener = entity;
    }

    /** Applies the events of the last trigger update. */
    public void consume(Triggers triggers) {
        int[] ev = triggers.events();
        for (int i = 0, n = triggers.eventCount(); i < n; i++) {
            int o = i * 3;
            if (ev[o + 1] != listener) {
                continue;
            }
            if (ev[o + 2] == Triggers.ENTER) {
                ambience.enter(ev[o]);
            } else {
                ambience.exit(ev[o]);
            }
        }
    }
}
