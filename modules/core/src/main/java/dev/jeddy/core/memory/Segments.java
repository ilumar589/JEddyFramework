package dev.jeddy.core.memory;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Bulk copies between primitive arrays and native memory, built on the intrinsified {@code
 * MemorySegment.copy} (no element loops in Java, no allocation). Native byte order, unaligned
 * layouts, so any offset works.
 *
 * <p>Thread ownership: stateless, callable from any thread.
 */
public final class Segments {
    /** Native-order unaligned {@code float}. */
    public static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED;

    /** Native-order unaligned {@code int}. */
    public static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED;

    /** Native-order unaligned {@code long}. */
    public static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED;

    /** Native-order unaligned {@code short}. */
    public static final ValueLayout.OfShort SHORT = ValueLayout.JAVA_SHORT_UNALIGNED;

    private Segments() {}

    /** Copies {@code count} floats from {@code src[srcIndex..]} to {@code dst} at byte offset. */
    public static void copy(float[] src, int srcIndex, MemorySegment dst, long dstOffset, int count) {
        MemorySegment.copy(src, srcIndex, dst, FLOAT, dstOffset, count);
    }

    /** Copies {@code count} floats from {@code src} at byte offset into {@code dst[dstIndex..]}. */
    public static void copy(MemorySegment src, long srcOffset, float[] dst, int dstIndex, int count) {
        MemorySegment.copy(src, FLOAT, srcOffset, dst, dstIndex, count);
    }

    /** Copies {@code count} ints from {@code src[srcIndex..]} to {@code dst} at byte offset. */
    public static void copy(int[] src, int srcIndex, MemorySegment dst, long dstOffset, int count) {
        MemorySegment.copy(src, srcIndex, dst, INT, dstOffset, count);
    }

    /** Copies {@code count} ints from {@code src} at byte offset into {@code dst[dstIndex..]}. */
    public static void copy(MemorySegment src, long srcOffset, int[] dst, int dstIndex, int count) {
        MemorySegment.copy(src, INT, srcOffset, dst, dstIndex, count);
    }

    /** Copies {@code count} longs from {@code src[srcIndex..]} to {@code dst} at byte offset. */
    public static void copy(long[] src, int srcIndex, MemorySegment dst, long dstOffset, int count) {
        MemorySegment.copy(src, srcIndex, dst, LONG, dstOffset, count);
    }

    /** Copies {@code count} shorts from {@code src[srcIndex..]} to {@code dst} at byte offset. */
    public static void copy(short[] src, int srcIndex, MemorySegment dst, long dstOffset, int count) {
        MemorySegment.copy(src, srcIndex, dst, SHORT, dstOffset, count);
    }

    /** Copies {@code count} bytes from {@code src[srcIndex..]} to {@code dst} at byte offset. */
    public static void copy(byte[] src, int srcIndex, MemorySegment dst, long dstOffset, int count) {
        MemorySegment.copy(src, srcIndex, dst, ValueLayout.JAVA_BYTE, dstOffset, count);
    }

    /** Copies {@code count} bytes from {@code src} at byte offset into {@code dst[dstIndex..]}. */
    public static void copy(MemorySegment src, long srcOffset, byte[] dst, int dstIndex, int count) {
        MemorySegment.copy(src, ValueLayout.JAVA_BYTE, srcOffset, dst, dstIndex, count);
    }

    /** Rounds {@code value} up to a multiple of the power-of-two {@code alignment}. */
    public static long alignUp(long value, long alignment) {
        return (value + alignment - 1) & -alignment;
    }
}
