package dev.jeddy.window;

import java.util.List;

/**
 * A snapshot of one connected display, from {@link Platform#displays()}.
 *
 * <p>Thread ownership: immutable.
 *
 * @param id SDL display ID
 * @param name human-readable name
 * @param primary whether this is the primary display
 * @param x left edge of the display in desktop coordinates
 * @param y top edge of the display in desktop coordinates
 * @param width width in desktop coordinates
 * @param height height in desktop coordinates
 * @param usableWidth width without task bars and docks
 * @param usableHeight height without task bars and docks
 * @param contentScale UI scale the user chose for this display (1.0 = 100 %)
 * @param desktopMode the current desktop mode
 * @param fullscreenModes modes for exclusive fullscreen, best first
 */
public record DisplayInfo(
        int id,
        String name,
        boolean primary,
        int x,
        int y,
        int width,
        int height,
        int usableWidth,
        int usableHeight,
        float contentScale,
        DisplayMode desktopMode,
        List<DisplayMode> fullscreenModes) {
    /** Creates the record with an immutable copy of {@code fullscreenModes}. */
    public DisplayInfo {
        fullscreenModes = List.copyOf(fullscreenModes);
    }
}
