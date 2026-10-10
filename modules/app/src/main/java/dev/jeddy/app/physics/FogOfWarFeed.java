package dev.jeddy.app.physics;

import dev.jeddy.physics.vis.OcclusionGrid;
import dev.jeddy.physics.vis.Visibility;
import dev.jeddy.render.RenderPacket;

/**
 * Feeds the physics fog of war to the renderer (DESIGN §9.3, §11.5; TASKS M7-10): runs the {@link
 * Visibility} update each tick, packs the visible and explored cells into bytes when they changed,
 * and copies them into render packets that have not seen this version yet, so the renderer
 * re-uploads its fog texture only on change.
 *
 * <p>Thread ownership: the game thread ({@link #update} in the simulation, {@link #extract} at
 * extract time). Allocates nothing per tick.
 */
public final class FogOfWarFeed {
    private final Visibility visibility;
    private final byte[] cells;
    private int version;
    private boolean invalid = true;

    /** Creates a feed for {@code visibility}. */
    public FogOfWarFeed(Visibility visibility) {
        this.visibility = visibility;
        OcclusionGrid g = visibility.grid();
        cells = new byte[g.width() * g.height()];
    }

    /** Returns the visibility it feeds from. */
    public Visibility visibility() {
        return visibility;
    }

    /** Forces a repack (after {@code exploreAll}, {@code clearExplored} or removed viewers). */
    public void invalidate() {
        invalid = true;
    }

    /**
     * Updates the visibility (viewers that moved) and repacks when anything changed; returns
     * whether it did.
     */
    public boolean update() {
        int recomputed = visibility.update();
        if (recomputed == 0 && !invalid) {
            return false;
        }
        invalid = false;
        visibility.pack(cells);
        version++;
        return true;
    }

    /** Returns the version of the packed cells (bumped on every repack). */
    public int version() {
        return version;
    }

    /** Returns the packed cells (0 unexplored, 128 explored, 255 visible; do not modify). */
    public byte[] cells() {
        return cells;
    }

    /** Copies the fog into {@code packet} unless it already holds this version. */
    public void extract(RenderPacket packet) {
        if (version == 0 || (packet.fogOfWar != null && packet.fogVersion == version)) {
            return;
        }
        OcclusionGrid g = visibility.grid();
        packet.setFogOfWar(
                cells,
                g.width(),
                g.height(),
                g.minX(),
                g.minZ(),
                g.width() * g.cellSize(),
                g.height() * g.cellSize(),
                version);
    }
}
