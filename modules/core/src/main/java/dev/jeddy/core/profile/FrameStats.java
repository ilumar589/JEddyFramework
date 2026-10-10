package dev.jeddy.core.profile;

import java.util.Arrays;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Timespan;

/**
 * Rolling frame-time statistics over the last N frames (average, minimum, maximum, percentiles).
 * Also emits a {@code dev.jeddy.Frame} JFR event per frame while a JFR recording has it enabled,
 * so frame times line up with GC and allocation data in JDK Mission Control.
 *
 * <p>Thread ownership: single owner thread (normally the main loop).
 */
public final class FrameStats {
    private static final EventType FRAME_EVENT = EventType.getEventType(FrameEvent.class);

    private final long[] frameNanos;
    private final long[] sortScratch;
    private int count;
    private int next;
    private long frameIndex;

    /** Creates stats over the last {@code window} frames. */
    public FrameStats(int window) {
        frameNanos = new long[Math.max(1, window)];
        sortScratch = new long[frameNanos.length];
    }

    /** Records one frame's duration. */
    public void record(long nanos) {
        frameNanos[next] = nanos;
        next = (next + 1) % frameNanos.length;
        if (count < frameNanos.length) {
            count++;
        }
        frameIndex++;
        if (FRAME_EVENT.isEnabled()) {
            FrameEvent e = new FrameEvent();
            e.frame = frameIndex;
            e.frameNanos = nanos;
            e.commit();
        }
    }

    /** Returns the number of frames in the window. */
    public int count() {
        return count;
    }

    /** Returns the average frame time in milliseconds. */
    public double averageMs() {
        if (count == 0) {
            return 0;
        }
        long sum = 0;
        for (int i = 0; i < count; i++) {
            sum += frameNanos[i];
        }
        return sum / (count * 1e6);
    }

    /** Returns the longest frame time in milliseconds. */
    public double maxMs() {
        long max = 0;
        for (int i = 0; i < count; i++) {
            max = Math.max(max, frameNanos[i]);
        }
        return max / 1e6;
    }

    /** Returns the shortest frame time in milliseconds. */
    public double minMs() {
        if (count == 0) {
            return 0;
        }
        long min = Long.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            min = Math.min(min, frameNanos[i]);
        }
        return min / 1e6;
    }

    /** Returns the {@code p}-th percentile frame time in milliseconds, {@code p} in [0, 100]. */
    public double percentileMs(double p) {
        if (count == 0) {
            return 0;
        }
        System.arraycopy(frameNanos, 0, sortScratch, 0, count);
        Arrays.sort(sortScratch, 0, count);
        int index = (int) Math.min(count - 1, Math.max(0, Math.ceil(p / 100.0 * count) - 1));
        return sortScratch[index] / 1e6;
    }

    @Name("dev.jeddy.Frame")
    @Label("Frame")
    @Category("Jeddy")
    @Description("One rendered frame")
    static final class FrameEvent extends Event {
        @Label("Frame index")
        long frame;

        @Label("Duration")
        @Timespan(Timespan.NANOSECONDS)
        long frameNanos;
    }
}
