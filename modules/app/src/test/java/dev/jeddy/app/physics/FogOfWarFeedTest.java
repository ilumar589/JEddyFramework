package dev.jeddy.app.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeddy.physics.vis.OcclusionGrid;
import dev.jeddy.physics.vis.Visibility;
import dev.jeddy.render.RenderPacket;
import dev.jeddy.testing.Allocations;

import org.junit.jupiter.api.Test;

/** The fog-of-war feed from physics visibility into render packets (TASKS M7-10, M8-10). */
class FogOfWarFeedTest {
    @Test
    void packetsGetTheFogOnlyWhenItChanges() {
        OcclusionGrid grid = new OcclusionGrid(0, 0, 0.5f, 40, 40);
        for (int z = 0; z < 40; z++) {
            grid.setBlocked(20, z, true); // a wall down the middle
        }
        Visibility vis = new Visibility(grid, null);
        vis.setViewer(1, 5, 10, 6);
        FogOfWarFeed feed = new FogOfWarFeed(vis);
        assertTrue(feed.update());
        RenderPacket a = new RenderPacket(16);
        RenderPacket b = new RenderPacket(16);
        feed.extract(a);
        assertEquals(40, a.fogWidth);
        assertEquals(20f, a.fogSizeX);
        assertEquals((byte) 255, a.fogOfWar[10 + 20 * 40], "the viewer's cell is visible");
        assertEquals(0, a.fogOfWar[30 + 20 * 40], "behind the wall is unexplored");
        // Nothing moved: no repack, no copy.
        assertFalse(feed.update());
        byte[] before = a.fogOfWar;
        int version = a.fogVersion;
        feed.extract(a);
        assertSame(before, a.fogOfWar);
        assertEquals(version, a.fogVersion);
        // The viewer walks: the old spot becomes explored, and both packets catch up.
        vis.setViewer(1, 5, 2, 6);
        assertTrue(feed.update());
        feed.extract(a);
        feed.extract(b);
        assertEquals(a.fogVersion, b.fogVersion);
        assertEquals((byte) 128, a.fogOfWar[10 + 30 * 40], "explored, no longer visible");
        Allocations.assertNoAllocation(
                "fog feed",
                () -> {
                    feed.update();
                    feed.extract(a);
                });
    }
}
