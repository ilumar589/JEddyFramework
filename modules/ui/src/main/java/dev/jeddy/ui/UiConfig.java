package dev.jeddy.ui;

/**
 * Capacities of a {@link Ui} context (TASKS M13-03). Everything is allocated up front; a frame that
 * exceeds a capacity grows the arrays once (a cold path worth sizing away).
 *
 * @param maxElements elements per frame, the root included
 * @param maxChars characters of text per frame
 * @param maxLines wrapped text lines per frame
 * @param maxStates widgets with persistent state (scroll offsets, open nodes, carets)
 * @param maxFonts registered fonts
 */
public record UiConfig(int maxElements, int maxChars, int maxLines, int maxStates, int maxFonts) {
    /** 4,096 elements, 64 Ki chars, 8,192 lines, 1,024 states, 8 fonts. */
    public static final UiConfig DEFAULT = new UiConfig(4096, 65536, 8192, 1024, 8);

    /** Checks the capacities. */
    public UiConfig {
        if (maxElements < 16 || maxChars < 256 || maxLines < 16 || maxStates < 16 || maxFonts < 1) {
            throw new IllegalArgumentException("capacities too small");
        }
    }
}
