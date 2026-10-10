package dev.jeddy.core.memory;

/**
 * What off-heap memory is used for, for {@link MemoryStats} accounting.
 *
 * <p>Thread ownership: immutable.
 */
public enum MemoryCategory {
    /** Per-frame temporaries ({@link FrameAllocator}). */
    TRANSIENT,
    /** GPU-mapped buffers and render data. */
    RENDER,
    /** Audio buffers. */
    AUDIO,
    /** Asset data, archives and staging. */
    ASSET,
    /** Physics and navigation. */
    PHYSICS,
    /** Native library interop (events, structs). */
    NATIVE,
    /** Anything else. */
    OTHER
}
