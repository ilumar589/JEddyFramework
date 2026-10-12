package dev.jeddy.app.world;

import dev.jeddy.core.io.ByteReader;
import dev.jeddy.core.io.ByteWriter;
import dev.jeddy.physics.crowd.Crowd;

import java.util.Arrays;

/**
 * Physics → scene sync (TASKS M10-02): after each {@link Crowd#update}, moves the entity bound to
 * each crowd agent to the agent's position and turns it toward its motion (models face +Z), at a
 * limited turn rate so characters do not snap around. Standing agents turn toward the facing last
 * asked with {@link #face} (an attack's target).
 *
 * <p>Thread ownership: the game thread, after the crowd update and before the transform update.
 * Allocates nothing per tick.
 */
public final class AgentSync {
    private final Crowd crowd;
    private final World world;
    private int[] entity;
    private float[] yaw;
    private float[] wantYaw;
    private boolean[] wants;
    private float turnRate = 10;

    /** Syncs {@code crowd}'s agents into {@code world}. */
    public AgentSync(Crowd crowd, World world) {
        this.crowd = crowd;
        this.world = world;
        entity = new int[crowd.capacity()];
        yaw = new float[crowd.capacity()];
        wantYaw = new float[crowd.capacity()];
        wants = new boolean[crowd.capacity()];
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
            wantYaw = Arrays.copyOf(wantYaw, agent + 1);
            wants = Arrays.copyOf(wants, agent + 1);
        }
        entity[agent] = e;
        yaw[agent] = initialYaw;
        wants[agent] = false;
    }

    /**
     * Turns {@code agent}'s entity toward {@code targetYaw} (radians about +Y, 0 = +Z) at the turn
     * rate while it stands still; moving again cancels it.
     */
    public void face(int agent, float targetYaw) {
        wantYaw[agent] = targetYaw;
        wants[agent] = true;
    }

    /** Like {@link #face}, toward the point {@code (x, z)}. */
    public void faceToward(int agent, float x, float z) {
        float dx = x - crowd.x(agent);
        float dz = z - crowd.z(agent);
        if (dx * dx + dz * dz > 1e-6f) {
            face(agent, (float) Math.atan2(dx, dz));
        }
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
            float target;
            if (vx * vx + vz * vz > 0.04f) {
                target = (float) Math.atan2(vx, vz);
                wants[a] = false;
            } else if (wants[a]) {
                target = wantYaw[a];
            } else {
                continue;
            }
            float d = target - yaw[a];
            d -= (float) (Math.PI * 2) * (float) Math.floor((d + Math.PI) / (Math.PI * 2));
            if (Math.abs(d) <= maxTurn) {
                wants[a] = false; // facing reached: stop touching the transform
            }
            yaw[a] += Math.clamp(d, -maxTurn, maxTurn);
            world.setYaw(e, yaw[a]);
        }
    }

    // ---- Saves (DESIGN §21) -------------------------------------------------------------------

    /**
     * Writes the facing of every bound agent (saves): the binding itself is the game's (it rebinds
     * its agents and entities before reading). Allocates nothing.
     */
    public void write(ByteWriter w) {
        w.int32(entity.length);
        for (int a = 0; a < entity.length; a++) {
            w.float32(yaw[a]);
            w.float32(wantYaw[a]);
            w.bool(wants[a]);
        }
    }

    /**
     * Reads what {@link #write} wrote onto the current bindings and applies the facings to the
     * bound entities at once (so a loaded character faces as it did without waiting for a tick).
     * Slots past the current bindings are skipped.
     */
    public void read(ByteReader r) {
        int n = r.int32();
        for (int a = 0; a < n; a++) {
            float y = r.float32();
            float want = r.float32();
            boolean w = r.bool();
            if (a >= entity.length) {
                continue;
            }
            yaw[a] = y;
            wantYaw[a] = want;
            wants[a] = w;
            int e = entity[a];
            if (e != 0 && crowd.isActive(a)) {
                world.transforms().setPosition(e, crowd.x(a), crowd.y(a), crowd.z(a));
                world.setYaw(e, y);
            }
        }
    }
}
