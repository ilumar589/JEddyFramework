package dev.jeddy.render;

/**
 * The three {@link RenderPacket}s behind the app's frame triple buffer (TASKS M5-06): the game
 * thread fills {@code packet(writeIndex)} in {@code Game.extract}, the main thread draws {@code
 * packet(readIndex)} in {@code FrameRenderer.render}. The triple buffer's atomic index swap is the
 * only synchronization (DESIGN §5).
 *
 * <p>Thread ownership: see {@link RenderPacket}.
 */
public final class RenderPackets {
    private final RenderPacket[] packets = new RenderPacket[3];

    /** Creates three packets for {@code capacity} instances each. */
    public RenderPackets(int capacity) {
        for (int i = 0; i < 3; i++) {
            packets[i] = new RenderPacket(capacity);
        }
    }

    /** Returns the packet at a triple-buffer index (0 to 2). */
    public RenderPacket packet(int index) {
        return packets[index];
    }
}
