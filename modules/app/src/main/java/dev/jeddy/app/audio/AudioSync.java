package dev.jeddy.app.audio;

import dev.jeddy.app.world.World;
import dev.jeddy.audio.Audio;
import dev.jeddy.math.IsoCamera;

import java.util.Arrays;

/**
 * Scene → audio sync (TASKS M10-02): the listener follows the camera's focus point and facing, and
 * emitters attached to entities follow their world positions, once per frame.
 *
 * <p>Thread ownership: the game thread, before {@link Audio#update}. Allocates nothing per frame.
 */
public final class AudioSync {
    private final Audio audio;
    private final World world;
    private int[] entities = new int[32];
    private int[] emitters = new int[32];
    private int count;
    private final float[] p = new float[3];

    /** Syncs {@code world} into {@code audio}. */
    public AudioSync(Audio audio, World world) {
        this.audio = audio;
        this.world = world;
    }

    /** Attaches a new emitter to {@code entity} and returns it (for {@code Audio.play}). */
    public int attach(int entity, float minDistance, float maxDistance) {
        world.position(entity, p);
        int e = audio.addEmitter(p[0], p[1], p[2], minDistance, maxDistance);
        if (count == entities.length) {
            entities = Arrays.copyOf(entities, count * 2);
            emitters = Arrays.copyOf(emitters, count * 2);
        }
        entities[count] = entity;
        emitters[count] = e;
        count++;
        return e;
    }

    /** Detaches and removes the emitters of {@code entity} (its sounds fade out). */
    public void detach(int entity) {
        for (int i = count - 1; i >= 0; i--) {
            if (entities[i] == entity) {
                audio.removeEmitter(emitters[i]);
                count--;
                entities[i] = entities[count];
                emitters[i] = emitters[count];
            }
        }
    }

    /**
     * Places the listener at the camera's focus, facing where the camera looks, and moves emitters.
     */
    public void update(IsoCamera camera) {
        float fx = camera.target.x - camera.eye().x;
        float fz = camera.target.z - camera.eye().z;
        audio.setListener(camera.target.x, camera.target.y, camera.target.z, fx, fz);
        for (int i = 0; i < count; i++) {
            world.position(entities[i], p);
            audio.setEmitter(emitters[i], p[0], p[1], p[2]);
        }
    }
}
