package dev.jeddy.physics.nav;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * A baked navmesh for one agent size class ({@code .jnav}, docs/formats/jnav.md): the tile grid's
 * origin and tile size, the agent the mesh was baked for, and its tiles.
 *
 * @param originX world x of tile (0, 0)'s corner
 * @param originZ world z of tile (0, 0)'s corner
 * @param tileSize tile edge in meters
 * @param agentRadius agent radius the walkable area was eroded by
 * @param agentHeight agent clearance
 * @param agentClimb highest step
 * @param tiles the tiles
 */
public record NavMeshData(
        float originX,
        float originZ,
        float tileSize,
        float agentRadius,
        float agentHeight,
        float agentClimb,
        List<NavTile> tiles) {

    /** Format magic. */
    public static final int MAGIC = 0x56414E4A; // "JNAV"

    /** Format version. */
    public static final int VERSION = 1;

    /** Serializes (little-endian). */
    public byte[] toBytes() {
        int size = 4 * 4 + 6 * 4;
        for (NavTile t : tiles) {
            size += t.byteSize();
        }
        ByteBuffer b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(MAGIC).putInt(VERSION).putInt(tiles.size()).putInt(0);
        b.putFloat(originX).putFloat(originZ).putFloat(tileSize);
        b.putFloat(agentRadius).putFloat(agentHeight).putFloat(agentClimb);
        for (NavTile t : tiles) {
            t.write(b);
        }
        return b.array();
    }

    /** Reads a serialized navmesh. */
    public static NavMeshData read(ByteBuffer data) {
        ByteBuffer b = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        if (b.remaining() < 40 || b.getInt() != MAGIC) {
            throw new IllegalArgumentException("not a .jnav navmesh");
        }
        int version = b.getInt();
        if (version != VERSION) {
            throw new IllegalArgumentException(".jnav version " + version + " is not supported");
        }
        int n = b.getInt();
        b.getInt();
        float ox = b.getFloat();
        float oz = b.getFloat();
        float ts = b.getFloat();
        float r = b.getFloat();
        float h = b.getFloat();
        float c = b.getFloat();
        List<NavTile> tiles = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            tiles.add(NavTile.read(b));
        }
        return new NavMeshData(ox, oz, ts, r, h, c, tiles);
    }
}
