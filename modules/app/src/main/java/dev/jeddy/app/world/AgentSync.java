package dev.jeddy.app.world;

import dev.jeddy.physics.crowd.Crowd;

import java.util.Arrays;

/**
 * Physics → scene sync (TASKS M10-02): after each {@link Crowd#update}, moves the entity bound to
 * each crowd agent to the agent's position and turns it toward its motion (models face +Z), at a
 * limited turn rate so characters do not snap around.
 *
 * <p>Thread ownership: the game thread, after the crowd update and before the transform update.
 * Allocates nothing per tick.
 */
public final class AgentSync {
    private final Crowd crowd;
    private final World world;
    private int[] entity;
    private float[] yaw;
    private float turnRate = 10;

    /** Syncs {@code crowd}'s agents into {@code world}. */
    public AgentSync(Crowd crowd, World world) {
        this.crowd = crowd;
        this.world = world;
        entity = new int[crowd.capacity()];
        yaw = new float[crowd.capacity()];
    }

    /** Sets the turn rate in radians per second. */
    public void setTurnRate(float radiansPerSecond) {
        turnRate = radiansPerSecond;
    }

    /** Binds agent {@code agent} to {@code e}, starting from facing {@code initialYaw}. */
    public void bind(int agent, int e, float initialYaw) {
        if (agent >= entity.length) {
            entity = Arrays.copyOf(entity, agent + 1);
            yaw = Arrays.copyOf(yaw, agent + 1);
        }
        entity[agent] = e;
        yaw[agent] = initialYaw;
    }

    /** Unbinds agent {@code agent}. */
    public void unbind(int agent) {
        entity[agent] = 0;
    }

    /** Returns the entity bound to {@code agent}, or 0. */
    public int entity(int agent) {
        return entity[agent];
    }

    /** Returns the facing of {@code agent}'s entity. */
    public float yaw(int agent) {
        return yaw[agent];
    }

    /** Copies positions and facings for one tick of {@code dt} seconds. */
    public void apply(float dt) {
        float maxTurn = turnRate * dt;
        for (int a = 0, n = Math.min(entity.length, crowd.capacity()); a < n; a++) {
            int e = entity[a];
            if (e == 0 || !crowd.isActive(a)) {
                continue;
            }
            world.transforms().setPosition(e, crowd.x(a), crowd.y(a), crowd.z(a));
            float vx = crowd.velocityX(a);
            float vz = crowd.velocityZ(a);
            if (vx * vx + vz * vz > 0.04f) {
                float target = (float) Math.atan2(vx, vz);
                float d = target - yaw[a];
                d -= (float) (Math.PI * 2) * (float) Math.floor((d + Math.PI) / (Math.PI * 2));
                yaw[a] += Math.clamp(d, -maxTurn, maxTurn);
                world.setYaw(e, yaw[a]);
            }
        }
    }
}
