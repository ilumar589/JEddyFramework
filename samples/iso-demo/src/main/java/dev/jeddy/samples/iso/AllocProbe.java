package dev.jeddy.samples.iso;

import java.lang.management.ManagementFactory;

/**
 * Attributes the game thread's allocations to the systems of a frame ({@code --alloc-probe}): the
 * thread's allocated-bytes counter is read between systems, so a perf run that fails its allocation
 * budget says where the bytes came from without a profiler.
 *
 * <p>Thread ownership: the game thread. Allocates nothing per frame.
 */
final class AllocProbe {
    static final String[] NAMES = {
        "frame start",
        "tick: beginTick",
        "tick: script",
        "tick: enemies.think",
        "tick: party.tick",
        "tick: crowd.update",
        "tick: sync.apply",
        "tick: collision.update",
        "tick: spells.tick",
        "tick: visibility",
        "tick: transforms",
        "update: assets",
        "update: input",
        "update: camera",
        "update: audio",
        "update: timers",
        "extract: world",
        "extract: lights",
        "extract: effects",
        "extract: fog+debug",
        "extract: hud"
    };

    static final int START = 0;
    static final int BEGIN_TICK = 1;
    static final int SCRIPT = 2;
    static final int THINK = 3;
    static final int PARTY = 4;
    static final int CROWD = 5;
    static final int SYNC = 6;
    static final int COLLISION = 7;
    static final int SPELLS = 8;
    static final int VISIBILITY = 9;
    static final int TRANSFORMS = 10;
    static final int ASSETS = 11;
    static final int INPUT = 12;
    static final int CAMERA = 13;
    static final int AUDIO = 14;
    static final int TIMERS = 15;
    static final int WORLD = 16;
    static final int LIGHTS = 17;
    static final int EFFECTS = 18;
    static final int FOG = 19;
    static final int HUD = 20;

    /** Allocations kept with their frame and system, for the report's second half. */
    static final int MAX_EVENTS = 256;

    final long[] bytes = new long[NAMES.length];
    private final int[] eventFrame = new int[MAX_EVENTS];
    private final int[] eventSystem = new int[MAX_EVENTS];
    private final long[] eventBytes = new long[MAX_EVENTS];
    private int events;
    private int frame;
    private final com.sun.management.ThreadMXBean bean;
    boolean enabled;
    private long last;

    AllocProbe() {
        bean =
                ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean b
                        ? b
                        : null;
    }

    /** Charges the bytes allocated since the previous mark to {@code system}. */
    void mark(int system) {
        if (!enabled || bean == null) {
            return;
        }
        long now = bean.getCurrentThreadAllocatedBytes();
        if (last != 0) {
            long delta = now - last;
            bytes[system] += delta;
            if (delta != 0 && events < MAX_EVENTS) {
                eventFrame[events] = frame;
                eventSystem[events] = system;
                eventBytes[events] = delta;
                events++;
            }
        }
        last = now;
        if (system == HUD) {
            frame++; // the frame's last mark
        }
    }

    /** Starts charging (after the warm-up). */
    void start() {
        enabled = bean != null;
        last = 0;
        frame = 0;
        events = 0;
        mark(START);
    }

    /** The allocations of the second half of the frames since {@link #start}, one per entry. */
    String secondHalfEvents() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < events; i++) {
            if (eventFrame[i] >= frame / 2) {
                sb.append("frame ")
                        .append(eventFrame[i])
                        .append(" of ")
                        .append(frame)
                        .append(": ")
                        .append(NAMES[eventSystem[i]])
                        .append(' ')
                        .append(eventBytes[i])
                        .append(" B; ");
            }
        }
        if (events == MAX_EVENTS) {
            sb.append("(first ").append(MAX_EVENTS).append(" allocations only)");
        }
        return sb.toString();
    }

    String report() {
        StringBuilder sb =
                new StringBuilder(
                        "\n## Game thread allocation by system\n\n"
                                + "| System | Bytes |\n|---|---|\n");
        for (int i = 0; i < NAMES.length; i++) {
            if (bytes[i] != 0) {
                sb.append("| ").append(NAMES[i]).append(" | ").append(bytes[i]).append(" |\n");
            }
        }
        return sb.toString();
    }
}
