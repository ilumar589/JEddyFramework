package dev.jeddy.physics.crowd;

/**
 * Settings of a crowd agent, copied by {@link Crowd#addAgent} and {@link Crowd#setParams}; reuse
 * one instance to add many agents.
 *
 * <p>Thread ownership: the game thread.
 */
public final class AgentParams {
    /** Steer toward a blend of the next two corners (smoother turns). */
    public static final int ANTICIPATE_TURNS = 1;

    /** Avoid other agents with ORCA. */
    public static final int AVOIDANCE = 2;

    /** Keep a little distance from neighbours (crowds spread out). */
    public static final int SEPARATION = 4;

    /** Shorten the corridor with navmesh raycasts. */
    public static final int OPTIMIZE_VISIBILITY = 8;

    /** Re-plan the corridor's start with small searches now and then. */
    public static final int OPTIMIZE_TOPOLOGY = 16;

    /** Every behaviour. */
    public static final int ALL =
            ANTICIPATE_TURNS | AVOIDANCE | SEPARATION | OPTIMIZE_VISIBILITY | OPTIMIZE_TOPOLOGY;

    /** Body radius in meters (Pathfinder Medium: 0.4). */
    public float radius = 0.4f;

    /** Body height in meters. */
    public float height = 1.8f;

    /** Top speed in m/s. */
    public float maxSpeed = 3.5f;

    /** Acceleration limit in m/s². */
    public float maxAcceleration = 12f;

    /** How far neighbours are considered, in meters (typically 6–12 radii). */
    public float collisionQueryRange = 3f;

    /** How far ahead the corridor is shortened by raycasts, in meters. */
    public float pathOptimizationRange = 12f;

    /** Separation strength (0 off; around 1–3 spreads crowds). */
    public float separationWeight = 1f;

    /** Distance from the target that counts as arrived, in meters. */
    public float arrivalDistance = 0.1f;

    /** Behaviour flags ({@link #ALL} by default). */
    public int flags = ALL;

    /** Index of the crowd's query filter the agent uses. */
    public int filter;

    /** Creates parameters for a Medium humanoid. */
    public AgentParams() {}
}
