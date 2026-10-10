package dev.jeddy.physics.nav;

/**
 * Polygon flag bits ({@link NavTile#flags}, tested by {@link QueryFilter}). Bits from {@code 1 <<
 * 8} up are free for games (for example "requires swimming").
 *
 * <p>Thread ownership: constants; any thread.
 */
public final class NavFlags {
    /** Walkable ground (every baked polygon has it). */
    public static final int WALK = 1;

    /** A door polygon (see {@link NavDoors}). */
    public static final int DOOR = 1 << 1;

    /** Temporarily impassable (a closed door, a blocked bridge); default filters exclude it. */
    public static final int DISABLED = 1 << 2;

    private NavFlags() {}
}
