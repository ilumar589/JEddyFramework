package dev.jeddy.app.audio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.audio.Ambience;
import dev.jeddy.audio.Audio;
import dev.jeddy.audio.AudioConfig;
import dev.jeddy.physics.grid.Bodies;
import dev.jeddy.physics.grid.UniformGrid;
import dev.jeddy.physics.trigger.Triggers;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;

/** Ambient zones follow the listener through trigger volumes (TASKS M9-05). */
class AmbienceTriggersTest {
    @Test
    void zonesStartAndStopWithTriggerEvents() {
        try (Audio audio = Audio.open(new AudioConfig().offline().voices(8, 16))) {
            Ambience amb = new Ambience(audio, 1);
            amb.defineZone(42, audio.sine(120, 0.2f, 10), 0.5f, 0.1f);
            Triggers triggers = new Triggers();
            triggers.addBox(42, 10, 0, 10, 3, 3, 4, 0, -1);
            triggers.addBox(99, 30, 0, 30, 3, 3, 4, 0, -1); // a trigger with no zone
            Bodies bodies = new Bodies(4);
            int party = bodies.addCylinder(7, 0, 0, 0, 0.4f, 1.8f, 1);
            bodies.addCylinder(8, 10, 0, 10, 0.4f, 1.8f, 1); // someone else, inside
            UniformGrid grid = new UniformGrid(0, 0, 40, 40, 2, null);
            AmbienceTriggers feed = new AmbienceTriggers(amb, 7);
            grid.rebuild(bodies);
            triggers.update(bodies, grid);
            feed.consume(triggers);
            assertFalse(amb.isInside(42), "another entity's events do not count");
            bodies.setPosition(party, 10, 0, 11);
            grid.rebuild(bodies);
            triggers.update(bodies, grid);
            feed.consume(triggers);
            assertTrue(amb.isInside(42));
            bodies.setPosition(party, 30, 0, 30);
            grid.rebuild(bodies);
            triggers.update(bodies, grid);
            feed.consume(triggers);
            assertFalse(amb.isInside(42));
            Allocations.assertNoAllocation("ambience feed", () -> feed.consume(triggers));
        }
    }
}
