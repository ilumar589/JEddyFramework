package dev.jeddy.physics.nav.bake;

/**
 * Navmesh bake settings for one agent size class (DESIGN §11.4), in the spirit of Recast's {@code
 * rcConfig}. Distances are in meters unless noted.
 *
 * @param cellSize horizontal voxel size (about a third of the agent radius)
 * @param cellHeight vertical voxel size
 * @param agentHeight clearance an agent needs
 * @param agentRadius agent radius; walkable areas are eroded by it
 * @param agentMaxClimb highest ledge an agent steps up
 * @param maxSlopeDegrees steepest walkable slope
 * @param tileSize tile size in cells (0: one tile for the whole bounds)
 * @param minRegionArea regions smaller than this many cells are dropped
 * @param mergeRegionArea regions smaller than this many cells merge into neighbours
 * @param maxEdgeLength longest polygon edge along borders (0: unlimited)
 * @param maxSimplificationError how far simplified contours may stray from the voxel border, in
 *     cells (voxel widths, as in Recast)
 * @param maxVertsPerPoly vertices per polygon (3..6)
 */
public record NavBakeConfig(
        float cellSize,
        float cellHeight,
        float agentHeight,
        float agentRadius,
        float agentMaxClimb,
        float maxSlopeDegrees,
        int tileSize,
        int minRegionArea,
        int mergeRegionArea,
        float maxEdgeLength,
        float maxSimplificationError,
        int maxVertsPerPoly) {

    /** Medium-sized humanoids (Pathfinder "Medium"): radius 0.4 m, height 1.8 m, 32 m tiles. */
    public static final NavBakeConfig MEDIUM =
            new NavBakeConfig(0.15f, 0.1f, 1.8f, 0.4f, 0.4f, 45f, 213, 8, 20, 12f, 1.3f, 6);

    /** Checks the ranges. */
    public NavBakeConfig {
        if (cellSize <= 0 || cellHeight <= 0 || agentHeight <= 0 || agentRadius < 0) {
            throw new IllegalArgumentException("invalid navmesh voxel or agent settings");
        }
        if (maxVertsPerPoly < 3 || maxVertsPerPoly > 6) {
            throw new IllegalArgumentException("maxVertsPerPoly must be 3..6");
        }
    }

    /** Returns a copy for another agent size (radius, height, climb). */
    public NavBakeConfig forAgent(float radius, float height, float climb) {
        return new NavBakeConfig(
                cellSize,
                cellHeight,
                height,
                radius,
                climb,
                maxSlopeDegrees,
                tileSize,
                minRegionArea,
                mergeRegionArea,
                maxEdgeLength,
                maxSimplificationError,
                maxVertsPerPoly);
    }

    /** Returns a copy with another tile size in cells. */
    public NavBakeConfig withTileSize(int cells) {
        return new NavBakeConfig(
                cellSize,
                cellHeight,
                agentHeight,
                agentRadius,
                agentMaxClimb,
                maxSlopeDegrees,
                cells,
                minRegionArea,
                mergeRegionArea,
                maxEdgeLength,
                maxSimplificationError,
                maxVertsPerPoly);
    }

    /** Agent height in voxels (rounded up). */
    int walkableHeight() {
        return (int) Math.ceil(agentHeight / cellHeight);
    }

    /** Max climb in voxels (rounded down). */
    int walkableClimb() {
        return (int) Math.floor(agentMaxClimb / cellHeight);
    }

    /** Agent radius in cells (rounded up). */
    int walkableRadius() {
        return (int) Math.ceil(agentRadius / cellSize);
    }

    /** Border around each tile in cells, so tiles match at their edges. */
    int borderSize() {
        return walkableRadius() + 3;
    }

    /** Max edge length in cells. */
    int maxEdgeCells() {
        return (int) (maxEdgeLength / cellSize);
    }
}
