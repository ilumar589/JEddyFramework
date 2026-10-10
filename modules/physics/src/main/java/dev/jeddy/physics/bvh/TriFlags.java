package dev.jeddy.physics.bvh;

/**
 * Per-triangle flags of the static collision geometry (DESIGN §11.1): what a triangle is for
 * (walking, blocking sight or projectiles, fading for the iso camera), plus a surface type (bits
 * 8..15, footstep sounds and decals) and a navigation area (bits 16..21, cost classes).
 *
 * <p>Thread ownership: constants only.
 */
public final class TriFlags {
    /** Characters can stand on it (if the slope allows). */
    public static final int WALKABLE = 1;

    /** Blocks line of sight and the fog-of-war occlusion grid. */
    public static final int BLOCKS_LOS = 2;

    /** Stops arrows and ranged spells. */
    public static final int BLOCKS_PROJECTILE = 4;

    /** Roofs and walls that fade for the iso camera. */
    public static final int CAMERA_FADE = 8;

    /** Collides with moving characters (walls, furniture). */
    public static final int BLOCKS_MOVEMENT = 16;

    /** Every flag that makes a triangle solid for queries. */
    public static final int SOLID = WALKABLE | BLOCKS_LOS | BLOCKS_PROJECTILE | BLOCKS_MOVEMENT;

    /** First bit of the surface type. */
    public static final int SURFACE_SHIFT = 8;

    /** First bit of the navigation area. */
    public static final int AREA_SHIFT = 16;

    private TriFlags() {}

    /** Returns the surface type (0..255) of {@code flags}. */
    public static int surface(int flags) {
        return flags >>> SURFACE_SHIFT & 0xFF;
    }

    /** Returns the navigation area (0..63) of {@code flags}. */
    public static int area(int flags) {
        return flags >>> AREA_SHIFT & 0x3F;
    }

    /** Returns {@code flags} with surface type {@code surface}. */
    public static int withSurface(int flags, int surface) {
        return flags & ~(0xFF << SURFACE_SHIFT) | (surface & 0xFF) << SURFACE_SHIFT;
    }

    /** Returns {@code flags} with navigation area {@code area}. */
    public static int withArea(int flags, int area) {
        return flags & ~(0x3F << AREA_SHIFT) | (area & 0x3F) << AREA_SHIFT;
    }
}
