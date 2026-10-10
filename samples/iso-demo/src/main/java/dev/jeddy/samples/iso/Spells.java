package dev.jeddy.samples.iso;

import dev.jeddy.audio.Audio;
import dev.jeddy.audio.SoundClip;
import dev.jeddy.core.collections.IntList;
import dev.jeddy.physics.query.CollisionWorld;
import dev.jeddy.physics.query.QueryContext;
import dev.jeddy.render.RenderPacket;

/**
 * Fireball (TASKS M10-04): an area-of-effect template decal while aiming, a glowing projectile with
 * a particle trail, then an explosion that damages every unit in a vertical cylinder (one overlap
 * query), flashes a decaying point light, bursts particles, leaves a fading scorch ring and plays a
 * positional sound.
 *
 * <p>Thread ownership: the game thread. Allocates nothing after construction.
 */
final class Spells {
    static final float RADIUS = 4f;
    static final float HEIGHT = 3f;
    static final float CENTER_DAMAGE = 40;
    static final float EDGE_DAMAGE = 18;
    static final float TRAVEL = 0.5f;
    static final float EXPLOSION = 1.1f;
    static final int MAX = 16;

    boolean targeting;
    boolean aimValid;
    float aimX;
    float aimY;
    float aimZ;
    int casts;

    private final float[] fromX = new float[MAX];
    private final float[] fromY = new float[MAX];
    private final float[] fromZ = new float[MAX];
    private final float[] toX = new float[MAX];
    private final float[] toY = new float[MAX];
    private final float[] toZ = new float[MAX];
    private final float[] age = new float[MAX];
    private final boolean[] active = new boolean[MAX];
    private final boolean[] exploded = new boolean[MAX];
    private final boolean[] burstPending = new boolean[MAX];
    private final IntList hits;
    private final QueryContext query;
    private final Audio audio;
    private final int[] emitters;
    private int nextEmitter;
    private SoundClip boom;

    /** {@code maxBodies}: the most units one explosion can catch (all of them). */
    Spells(Audio audio, int maxBodies) {
        this.audio = audio;
        hits = new IntList(maxBodies);
        query = new QueryContext(maxBodies);
        emitters = new int[4];
        if (audio != null) {
            for (int i = 0; i < emitters.length; i++) {
                emitters[i] = audio.addEmitter(0, 0, 0, 4, 45);
            }
        }
    }

    void setSound(SoundClip clip) {
        boom = clip;
    }

    /** Launches a fireball; returns false when too many are in flight. */
    boolean cast(float sx, float sy, float sz, float tx, float ty, float tz) {
        for (int i = 0; i < MAX; i++) {
            if (!active[i]) {
                fromX[i] = sx;
                fromY[i] = sy + 1.6f;
                fromZ[i] = sz;
                toX[i] = tx;
                toY[i] = ty;
                toZ[i] = tz;
                age[i] = 0;
                active[i] = true;
                exploded[i] = false;
                burstPending[i] = false;
                casts++;
                return true;
            }
        }
        return false;
    }

    /** Advances flights and explosions; explosions damage {@code enemies} through {@code world}. */
    void tick(float dt, CollisionWorld world, Enemies enemies) {
        for (int i = 0; i < MAX; i++) {
            if (!active[i]) {
                continue;
            }
            age[i] += dt;
            if (!exploded[i] && age[i] >= TRAVEL) {
                exploded[i] = true;
                burstPending[i] = true;
                explode(i, world, enemies);
            }
            if (age[i] >= TRAVEL + EXPLOSION) {
                active[i] = false;
            }
        }
    }

    private void explode(int i, CollisionWorld world, Enemies enemies) {
        hits.clear();
        world.overlapCylinder(
                toX[i], toY[i] - 0.5f, toZ[i], RADIUS, HEIGHT, Level.UNITS, hits, query);
        for (int k = 0; k < hits.size(); k++) {
            int a = hits.get(k);
            float dx = world.bodies().x[world.bodies().indexOf(a)] - toX[i];
            float dz = world.bodies().z[world.bodies().indexOf(a)] - toZ[i];
            float t = Math.min(1, (float) Math.sqrt(dx * dx + dz * dz) / RADIUS);
            enemies.damage(a, CENTER_DAMAGE + (EDGE_DAMAGE - CENTER_DAMAGE) * t);
        }
        if (audio != null && boom != null) {
            int e = emitters[nextEmitter];
            nextEmitter = (nextEmitter + 1) % emitters.length;
            audio.setEmitter(e, toX[i], toY[i] + 1, toZ[i]);
            audio.play(boom, Audio.BUS_SFX, 1f, 5, 0, 0, e);
        }
    }

    /** Adds the aiming template, projectiles, explosions and scorch rings to the packet. */
    void extract(RenderPacket p) {
        if (targeting && aimValid) {
            p.addAoeCircle(aimX, aimY, aimZ, RADIUS, 1f, 0.45f, 0.1f, 0.85f);
        }
        for (int i = 0; i < MAX; i++) {
            if (!active[i]) {
                continue;
            }
            if (!exploded[i]) {
                float t = age[i] / TRAVEL;
                float x = fromX[i] + (toX[i] - fromX[i]) * t;
                float z = fromZ[i] + (toZ[i] - fromZ[i]) * t;
                float y = fromY[i] + (toY[i] + 0.5f - fromY[i]) * t + 2.5f * t * (1 - t);
                p.addPointLight(x, y, z, 7, 1f, 0.55f, 0.2f, 14);
                int e = p.addEmitter(x, y, z, 450, 0);
                p.setEmitterRadius(e, 0.15f);
                p.setEmitterVelocity(e, 0, 0.4f, 0, 0.8f);
                p.setEmitterColors(e, 3f, 1.2f, 0.3f, 0f, 0.6f, 0.1f, 0.02f, 0f);
                p.setEmitterLifetime(e, 0.35f, 0.3f);
                p.setEmitterSize(e, 0.18f, 0.02f);
                continue;
            }
            float t = (age[i] - TRAVEL) / EXPLOSION;
            float fade = (1 - t) * (1 - t);
            p.addPointLight(toX[i], toY[i] + 1.2f, toZ[i], 13, 1f, 0.5f, 0.15f, 70 * fade);
            if (burstPending[i]) {
                burstPending[i] = false;
                int e = p.addEmitter(toX[i], toY[i] + 0.4f, toZ[i], 0, 700);
                p.setEmitterRadius(e, 0.6f);
                p.setEmitterVelocity(e, 0, 2.5f, 0, 5.5f);
                p.setEmitterColors(e, 4f, 1.6f, 0.4f, 0f, 0.5f, 0.08f, 0.02f, 0f);
                p.setEmitterLifetime(e, 0.8f, 0.4f);
                p.setEmitterSize(e, 0.3f, 0.05f);
                p.setEmitterForces(e, -0.15f, 1.8f);
                int smoke = p.addEmitter(toX[i], toY[i] + 0.6f, toZ[i], 0, 160);
                p.setEmitterRadius(smoke, 1.2f);
                p.setEmitterVelocity(smoke, 0, 1.2f, 0, 0.8f);
                p.setEmitterColors(smoke, 0.08f, 0.07f, 0.06f, 0.5f, 0f, 0f, 0f, 0f);
                p.setEmitterLifetime(smoke, 2.2f, 0.3f);
                p.setEmitterSize(smoke, 0.4f, 1.1f);
                p.setEmitterForces(smoke, -0.05f, 0.6f);
            }
            float ring = RADIUS * (0.55f + 0.45f * Math.min(1, t * 3));
            p.addAoeCircle(toX[i], toY[i], toZ[i], ring, 1f, 0.3f, 0.05f, 0.9f * (1 - t));
        }
    }

    /** Number of fireballs in flight or exploding. */
    int activeCount() {
        int n = 0;
        for (int i = 0; i < MAX; i++) {
            if (active[i]) {
                n++;
            }
        }
        return n;
    }
}
