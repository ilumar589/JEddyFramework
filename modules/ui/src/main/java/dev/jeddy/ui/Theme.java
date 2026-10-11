package dev.jeddy.ui;

/**
 * The colors and metrics the built-in widgets use (TASKS M13-04). Colors are {@code 0xAARRGGBB} in
 * sRGB. Games change fields directly or start from {@link #dark()}; a skinned game UI sets sprites
 * on its own boxes and only uses the theme for text and metrics.
 *
 * <p>Thread ownership: the thread that drives the {@link Ui}.
 */
public final class Theme {
    /** Text color. */
    public int text = 0xFFE6E6E6;

    /** Dimmed text (hints, disabled). */
    public int textDim = 0xFF8C8C8C;

    /** Panel background. */
    public int panel = 0xF0202228;

    /** Panel border. */
    public int panelBorder = 0xFF3A3D46;

    /** A darker inset background (fields, lists). */
    public int inset = 0xFF15161A;

    /** Widget background. */
    public int widget = 0xFF34373F;

    /** Widget background while hovered. */
    public int widgetHover = 0xFF444852;

    /** Widget background while pressed. */
    public int widgetActive = 0xFF5A5F6B;

    /** Accent (checks, slider fills, selection). */
    public int accent = 0xFF4C8DFF;

    /** Accent while hovered. */
    public int accentHover = 0xFF6BA0FF;

    /** Keyboard focus ring. */
    public int focus = 0xFFFFC14C;

    /** Separator lines. */
    public int separator = 0xFF3A3D46;

    /** Tooltip background. */
    public int tooltip = 0xF8101114;

    /** Scrollbar track. */
    public int scrollTrack = 0x40000000;

    /** Scrollbar thumb. */
    public int scrollThumb = 0x80FFFFFF;

    /** Text selection highlight. */
    public int selection = 0x804C8DFF;

    /** Default font size in pixels. */
    public float fontSize = 15;

    /** Small font size. */
    public float fontSizeSmall = 12;

    /** Large font size. */
    public float fontSizeLarge = 20;

    /** Padding inside widgets. */
    public float padding = 8;

    /** Gap between elements in rows and columns. */
    public float gap = 6;

    /** Corner radius of widgets. */
    public float radius = 4;

    /** Height of buttons and fields. */
    public float widgetHeight = 28;

    /** Scrollbar width. */
    public float scrollbarWidth = 10;

    /** Seconds before a tooltip appears. */
    public float tooltipDelay = 0.5f;

    /** Creates the default dark theme. */
    public Theme() {}

    /** Returns a new dark theme (the defaults). */
    public static Theme dark() {
        return new Theme();
    }
}
