package dev.jeddy.ui;

import dev.jeddy.asset.format.FontData;

/**
 * The built-in widgets (TASKS M13-04, DESIGN §18): compositions of {@link Ui} boxes plus the
 * context's interaction state, styled by a {@link Theme}. Each widget is a method that declares its
 * boxes for this frame and returns what happened ({@code button} returns whether it was clicked) or
 * the new value ({@code slider} returns the value after this frame's input), so the game keeps its
 * own data and never mirrors it in widget objects:
 *
 * <pre>{@code
 * Widgets w = new Widgets(ui, Theme.dark());
 * w.panel().width(300).open();
 *     w.heading("Options");
 *     volume = w.slider(VOLUME, volume, 0, 1);
 *     muted = w.checkbox(MUTED, "Mute", muted);
 *     if (w.button(APPLY, "Apply")) apply();
 * ui.close();
 * }</pre>
 *
 * Containers ({@link #panel}, {@link #scrollColumn}) return a configured {@link Box} the caller
 * sizes and opens; leaf widgets open and close their own boxes. Ids follow the {@link Ui} rules
 * ({@link Ui#pushId} for lists).
 *
 * <p>Thread ownership: the thread that drives the {@link Ui}. Allocates nothing per frame.
 */
public final class Widgets {
    /** Where text fields copy to and paste from. */
    public interface Clipboard {
        /** Replaces the clipboard text. */
        void set(CharSequence text);

        /** Returns the clipboard text (empty when there is none). */
        CharSequence get();
    }

    /** The context the widgets declare into. */
    public final Ui ui;

    /** The colors and metrics. */
    public final Theme theme;

    /** The clipboard; the default keeps text inside the process until the app wires SDL's. */
    public Clipboard clipboard =
            new Clipboard() {
                private final StringBuilder text = new StringBuilder();

                @Override
                public void set(CharSequence s) {
                    text.setLength(0);
                    text.append(s);
                }

                @Override
                public CharSequence get() {
                    return text;
                }
            };

    private static final String CHECK = "✓";
    private static final String TRIANGLE_RIGHT = "▶";
    private static final String TRIANGLE_DOWN = "▼";
    private static final long BAR = 0xBA7L;
    private static final long POPUP = 0x9099L;
    private static final long OPTION = 0x0971L;
    private static final long TAB = 0x7AB1L;

    private static final long FIELD = 0xF1E1DL;
    private static final long SWATCH = 0x5A7CL;
    private static final long ROW = 0x0A0L;
    private long openMenu; // the menu button whose popup is open, for hover switching

    private final TextBuffer numberEdit = new TextBuffer(32);
    private int lastFrame = -1;
    private float prevMouseX;
    private float prevMouseY;
    private float mouseDX;
    private float mouseDY;
    private long submitted;
    private int frontZ = 1;
    private final StringBuilder scratch = new StringBuilder();

    /** Creates the widget set; the theme's text defaults are copied into the context. */
    public Widgets(Ui ui, Theme theme) {
        this.ui = ui;
        this.theme = theme;
        applyTheme();
    }

    /** Copies the theme's text and scrollbar settings into the context (after editing it). */
    public void applyTheme() {
        ui.defaultFontSize = theme.fontSize;
        ui.defaultTextColor = theme.text;
        ui.scrollbarWidth = theme.scrollbarWidth;
        ui.scrollTrackColor = theme.scrollTrack;
        ui.scrollThumbColor = theme.scrollThumb;
    }

    /** Once per frame: the pointer delta for dragging widgets, and the per-frame edges. */
    private void sync() {
        int f = ui.frame();
        if (f != lastFrame) {
            UiInput in = ui.input();
            mouseDX = lastFrame < 0 ? 0 : in.mouseX - prevMouseX;
            mouseDY = lastFrame < 0 ? 0 : in.mouseY - prevMouseY;
            prevMouseX = in.mouseX;
            prevMouseY = in.mouseY;
            lastFrame = f;
            submitted = 0;
        }
    }

    // ---- Containers -------------------------------------------------------------------------

    /** A panel: a column with the theme's background, border, padding and gap. Open it. */
    public Box panel() {
        return panel(0);
    }

    /** A panel with an id (needed when it scrolls). Open it. */
    public Box panel(long id) {
        return ui.box(id)
                .column()
                .padding(theme.padding)
                .gap(theme.gap)
                .background(theme.panel)
                .border(theme.panelBorder, 1)
                .radius(theme.radius);
    }

    /** A row with the theme's gap, children centered vertically. Open it. */
    public Box row() {
        return ui.box().row().gap(theme.gap).align(Ui.START, Ui.CENTER);
    }

    /** A column with the theme's gap. Open it. */
    public Box column() {
        return ui.box().column().gap(theme.gap);
    }

    /** A vertical scroll box with an id (the offset lives in the context's state). Open it. */
    public Box scrollColumn(long id) {
        return ui.box(id).column().gap(theme.gap).scroll(false, true);
    }

    /** A box that takes the free space of its parent (pushes what follows to the end). */
    public void spacer() {
        ui.box().grow();
    }

    /** A one-pixel line across the parent. */
    public void separator() {
        ui.box().widthGrow().height(1).background(theme.separator).noHit();
    }

    /** A one-pixel vertical line in a row. */
    public void separatorVertical() {
        ui.box().width(1).heightGrow().background(theme.separator).noHit();
    }

    // ---- Text and images --------------------------------------------------------------------

    /** A line of text in the theme's text color. Returns the text box for more appends. */
    public Box label(CharSequence text) {
        return ui.text(text);
    }

    /** An empty text element to {@link Box#append append} to. */
    public Box label() {
        return ui.text();
    }

    /** Dimmed text (hints, secondary values). */
    public Box labelDim(CharSequence text) {
        return ui.text(text).color(theme.textDim);
    }

    /** Larger text. */
    public Box heading(CharSequence text) {
        return ui.text(text).fontSize(theme.fontSizeLarge);
    }

    /** Smaller text. */
    public Box small(CharSequence text) {
        return ui.text(text).fontSize(theme.fontSizeSmall);
    }

    /** Wrapping paragraph text that shrinks to its container. */
    public Box paragraph(CharSequence text) {
        return ui.text(text).wrap();
    }

    /** A sprite at its pixel size. */
    public Box image(Sprite sprite) {
        return ui.box().image(sprite);
    }

    /** A sprite stretched (or nine-sliced) to {@code w × h}. */
    public Box image(Sprite sprite, float w, float h) {
        return ui.box().sprite(sprite).size(w, h);
    }

    // ---- Buttons ----------------------------------------------------------------------------

    /** A push button; returns whether it was clicked this frame. */
    public boolean button(long id, CharSequence label) {
        return button(id, label, true);
    }

    /** A push button that can be disabled. */
    public boolean button(long id, CharSequence label, boolean enabled) {
        Box b =
                ui.box(id)
                        .height(theme.widgetHeight)
                        .padding(theme.padding, 0)
                        .align(Ui.CENTER, Ui.CENTER)
                        .radius(theme.radius)
                        .background(widgetColor(id, enabled));
        if (enabled) {
            b.focusable().activateWithKeys();
        } else {
            b.disabled();
        }
        focusRing(b, id);
        b.open();
        ui.text(label).color(enabled ? theme.text : theme.textDim);
        ui.close();
        return enabled && ui.clicked(id);
    }

    /** A button drawn in the accent color (the default action). */
    public boolean primaryButton(long id, CharSequence label) {
        Box b =
                ui.box(id)
                        .height(theme.widgetHeight)
                        .padding(theme.padding, 0)
                        .align(Ui.CENTER, Ui.CENTER)
                        .radius(theme.radius)
                        .background(
                                ui.active(id) || ui.hovered(id) ? theme.accentHover : theme.accent)
                        .focusable()
                        .activateWithKeys();
        focusRing(b, id);
        b.open();
        ui.text(label).color(0xFFFFFFFF);
        ui.close();
        return ui.clicked(id);
    }

    /** A square button showing a sprite. */
    public boolean iconButton(long id, Sprite icon) {
        Box b =
                ui.box(id)
                        .size(theme.widgetHeight, theme.widgetHeight)
                        .align(Ui.CENTER, Ui.CENTER)
                        .radius(theme.radius)
                        .background(widgetColor(id, true))
                        .focusable()
                        .activateWithKeys();
        focusRing(b, id);
        b.open();
        ui.box().image(icon).noHit();
        ui.close();
        return ui.clicked(id);
    }

    private int widgetColor(long id, boolean enabled) {
        if (!enabled) {
            return theme.widget;
        }
        return ui.active(id)
                ? theme.widgetActive
                : ui.hovered(id) ? theme.widgetHover : theme.widget;
    }

    private void focusRing(Box b, long id) {
        if (ui.showFocus() && ui.focused(id)) {
            b.border(theme.focus, 1);
        }
    }

    // ---- Toggles ----------------------------------------------------------------------------

    /** A check box with a label; returns the new value. */
    public boolean checkbox(long id, CharSequence label, boolean checked) {
        if (ui.clicked(id)) {
            checked = !checked;
        }
        Box b =
                ui.box(id)
                        .row()
                        .gap(theme.gap)
                        .height(theme.widgetHeight)
                        .align(Ui.START, Ui.CENTER)
                        .hit()
                        .focusable()
                        .activateWithKeys();
        b.open();
        Box mark =
                ui.box()
                        .size(16, 16)
                        .radius(3)
                        .align(Ui.CENTER, Ui.CENTER)
                        .background(checked ? theme.accent : theme.inset)
                        .border(
                                ui.showFocus() && ui.focused(id)
                                        ? theme.focus
                                        : ui.hovered(id) ? theme.accentHover : theme.panelBorder,
                                1)
                        .noHit();
        if (checked) {
            mark.open();
            ui.text(CHECK).fontSize(theme.fontSizeSmall).color(0xFFFFFFFF);
            ui.close();
        }
        ui.text(label);
        ui.close();
        return checked;
    }

    /** A radio button; returns whether it was selected this frame. */
    public boolean radio(long id, CharSequence label, boolean selected) {
        Box b =
                ui.box(id)
                        .row()
                        .gap(theme.gap)
                        .height(theme.widgetHeight)
                        .align(Ui.START, Ui.CENTER)
                        .hit()
                        .focusable()
                        .activateWithKeys();
        b.open();
        Box ring =
                ui.box()
                        .size(16, 16)
                        .radius(8)
                        .align(Ui.CENTER, Ui.CENTER)
                        .background(theme.inset)
                        .border(
                                ui.showFocus() && ui.focused(id)
                                        ? theme.focus
                                        : ui.hovered(id) ? theme.accentHover : theme.panelBorder,
                                1)
                        .noHit();
        if (selected) {
            ring.open();
            ui.box().size(8, 8).radius(4).background(theme.accent).noHit();
            ui.close();
        }
        ui.text(label);
        ui.close();
        return ui.clicked(id);
    }

    // ---- Values -----------------------------------------------------------------------------

    /**
     * A horizontal slider over {@code [min, max]}; returns the value after this frame's input
     * (dragging anywhere on the track, or the arrow keys while focused). It grows to its parent's
     * width.
     */
    public float slider(long id, float value, float min, float max) {
        float range = max - min;
        if (ui.active(id)) {
            int r = ui.lastRecord(id);
            if (r >= 0) {
                float x0 = ui.recordX(r) + 7;
                float w = ui.recordWidth(r) - 14;
                float t = w > 0 ? (ui.input().mouseX - x0) / w : 0;
                value = min + Math.clamp(t, 0, 1) * range;
            }
        }
        if (ui.focused(id)) {
            UiInput in = ui.input();
            float step = range / 100;
            if (in.pressed(UiKey.LEFT) || in.pressed(UiKey.DOWN)) {
                value -= step;
            }
            if (in.pressed(UiKey.RIGHT) || in.pressed(UiKey.UP)) {
                value += step;
            }
        }
        value = Math.clamp(value, Math.min(min, max), Math.max(min, max));
        float t = range != 0 ? (value - min) / range : 0;
        Box b =
                ui.box(id)
                        .widthGrow()
                        .height(theme.widgetHeight)
                        .align(Ui.START, Ui.CENTER)
                        .hit()
                        .focusable()
                        .captureKeys();
        b.open();
        ui.box().widthGrow().height(4).radius(2).background(theme.inset).noHit().open();
        ui.box().widthPercent(t).heightGrow().radius(2).background(theme.accent).noHit();
        ui.close();
        int r = ui.lastRecord(id);
        float travel = r >= 0 ? ui.recordWidth(r) - 14 : 0;
        ui.box()
                .size(14, 14)
                .radius(7)
                .floating()
                .clippedByParent()
                .anchor(Ui.LEFT, Ui.LEFT)
                .offset(t * travel, 0)
                .background(ui.active(id) || ui.hovered(id) ? theme.accentHover : theme.accent)
                .border(ui.showFocus() && ui.focused(id) ? theme.focus : 0, 1)
                .noHit();
        ui.close();
        return value;
    }

    /**
     * A number that changes by {@code step} per pixel of horizontal drag (or per arrow key while
     * focused), clamped to {@code [min, max]} and shown with {@code decimals} places.
     */
    public float dragValue(long id, float value, float step, float min, float max, int decimals) {
        sync();
        if (ui.active(id)) {
            value += mouseDX * step;
        }
        if (ui.focused(id)) {
            UiInput in = ui.input();
            if (in.pressed(UiKey.LEFT) || in.pressed(UiKey.DOWN)) {
                value -= step;
            }
            if (in.pressed(UiKey.RIGHT) || in.pressed(UiKey.UP)) {
                value += step;
            }
        }
        value = Math.clamp(value, min, max);
        Box b =
                ui.box(id)
                        .height(theme.widgetHeight)
                        .minWidth(60)
                        .padding(theme.padding, 0)
                        .align(Ui.CENTER, Ui.CENTER)
                        .radius(theme.radius)
                        .background(widgetColor(id, true))
                        .hit()
                        .focusable()
                        .captureKeys();
        focusRing(b, id);
        b.open();
        ui.text().append(value, decimals);
        ui.close();
        return value;
    }

    /** An integer that changes by one per {@code pixelsPerStep} pixels of drag. */
    public int dragInt(long id, int value, int min, int max, float pixelsPerStep) {
        sync();
        int slot = ui.state(id);
        float acc = ui.stateF(slot, 0);
        if (ui.active(id)) {
            acc += mouseDX / pixelsPerStep;
            int whole = (int) acc;
            value += whole;
            acc -= whole;
        } else {
            acc = 0;
        }
        ui.setStateF(slot, 0, acc);
        if (ui.focused(id)) {
            UiInput in = ui.input();
            if (in.pressed(UiKey.LEFT) || in.pressed(UiKey.DOWN)) {
                value--;
            }
            if (in.pressed(UiKey.RIGHT) || in.pressed(UiKey.UP)) {
                value++;
            }
        }
        value = Math.clamp(value, min, max);
        Box b =
                ui.box(id)
                        .height(theme.widgetHeight)
                        .minWidth(60)
                        .padding(theme.padding, 0)
                        .align(Ui.CENTER, Ui.CENTER)
                        .radius(theme.radius)
                        .background(widgetColor(id, true))
                        .hit()
                        .focusable()
                        .captureKeys();
        focusRing(b, id);
        b.open();
        ui.text().append(value);
        ui.close();
        return value;
    }

    /** A progress bar filled to {@code fraction} (0..1) in the accent color. */
    public void progress(float fraction) {
        progress(fraction, theme.accent, 8);
    }

    /** A progress bar in {@code color}, {@code height} pixels tall (health bars). */
    public void progress(float fraction, int color, float height) {
        ui.box()
                .widthGrow()
                .height(height)
                .radius(height * 0.5f)
                .background(theme.inset)
                .noHit()
                .open();
        ui.box()
                .widthPercent(Math.clamp(fraction, 0, 1))
                .heightGrow()
                .radius(height * 0.5f)
                .background(color)
                .noHit();
        ui.close();
    }

    // ---- Text fields ------------------------------------------------------------------------

    /** A single-line text field editing {@code text}; returns whether the text changed. */
    public boolean textField(long id, TextBuffer text) {
        return textField(id, text, null);
    }

    /**
     * A single-line text field with a placeholder shown while empty. The field takes focus on
     * click; while focused it edits {@code text} from the frame's typed characters and keys
     * (arrows, Home, End, Backspace, Delete, Shift selection, Ctrl+A/C/X/V), scrolls to keep the
     * caret visible and reports Enter through {@link #submitted}. Returns whether the text changed
     * this frame. It grows to its parent's width.
     */
    public boolean textField(long id, TextBuffer text, CharSequence placeholder) {
        sync();
        boolean focused = ui.focused(id);
        int before = text.version();
        int font = ui.defaultFont;
        float size = theme.fontSize;
        float lineHeight = ui.lineHeight(font, size);
        float pad = theme.padding;
        int slot = ui.state(id);
        int rec = ui.lastRecord(id);
        float scrollX = ui.scroll(id, 0);

        // Mouse: a press places the caret, dragging extends the selection, double click selects.
        if (rec >= 0
                && (ui.active(id) || (ui.pressedThisFrame() && ui.pressedId() == ui.resolve(id)))) {
            float textX = ui.recordX(rec) + pad - scrollX;
            int at = indexAt(font, size, text, ui.input().mouseX - textX);
            boolean shift = (ui.input().modifiers & UiKey.MOD_SHIFT) != 0;
            if (ui.pressedThisFrame() && ui.pressedId() == ui.resolve(id)) {
                text.moveTo(at, shift);
                ui.setStateF(slot, 2, ui.time()); // restart the blink
            } else {
                text.moveTo(at, true);
            }
            if (ui.doubleClicked(id)) {
                text.selectAll();
            }
        }

        // Keys and typed text.
        if (focused) {
            UiInput in = ui.input();
            int n = in.keyCount();
            for (int i = 0; i < n; i++) {
                if (!in.keyDown(i)) {
                    continue;
                }
                int key = in.keyScancode(i);
                int mods = in.keyModifiers(i);
                boolean shift = (mods & UiKey.MOD_SHIFT) != 0;
                boolean ctrl = (mods & (UiKey.MOD_CTRL | UiKey.MOD_GUI)) != 0;
                switch (key) {
                    case UiKey.LEFT -> {
                        if (text.hasSelection() && !shift) {
                            text.moveTo(text.selectionStart(), false);
                        } else {
                            text.move(ctrl ? -wordLength(text, -1) : -1, shift);
                        }
                    }
                    case UiKey.RIGHT -> {
                        if (text.hasSelection() && !shift) {
                            text.moveTo(text.selectionEnd(), false);
                        } else {
                            text.move(ctrl ? wordLength(text, 1) : 1, shift);
                        }
                    }
                    case UiKey.HOME -> text.moveTo(0, shift);
                    case UiKey.END -> text.moveTo(text.length(), shift);
                    case UiKey.BACKSPACE -> text.deleteBackward();
                    case UiKey.DELETE -> text.deleteForward();
                    case UiKey.RETURN -> submitted = ui.resolve(id);
                    case UiKey.A -> {
                        if (ctrl) {
                            text.selectAll();
                        }
                    }
                    case UiKey.C, UiKey.X -> {
                        if (ctrl && text.hasSelection()) {
                            scratch.setLength(0);
                            text.selection(scratch);
                            clipboard.set(scratch);
                            if (key == UiKey.X) {
                                text.deleteSelection();
                            }
                        }
                    }
                    case UiKey.V -> {
                        if (ctrl) {
                            text.insert(clipboard.get());
                        }
                    }
                    default -> {}
                }
                ui.setStateF(slot, 2, ui.time());
            }
            int t = in.textCount();
            for (int i = 0; i < t; i++) {
                int cp = in.textCodePoint(i);
                if (cp >= 0x20 && cp != 0x7F) {
                    text.insert(cp);
                }
            }
            if (t > 0) {
                ui.setStateF(slot, 2, ui.time());
            }
        }

        // Boxes: the field scrolls horizontally (no bars) so long text follows the caret.
        Box field =
                ui.box(id)
                        .widthGrow()
                        .height(theme.widgetHeight)
                        .padding(pad, 0)
                        .align(Ui.START, Ui.CENTER)
                        .background(theme.inset)
                        .border(focused ? theme.accent : theme.panelBorder, 1)
                        .radius(theme.radius)
                        .hit()
                        .focusable()
                        .captureKeys()
                        .scroll(true, false)
                        .hideScrollbars();
        field.open();
        if (text.length() == 0 && placeholder != null && !focused) {
            ui.text(placeholder).color(theme.textDim).noKerning();
        } else {
            ui.text(text).noKerning();
        }
        if (focused) {
            float caretX = ui.measure(font, size, text, 0, text.caret());
            float top = (theme.widgetHeight - lineHeight) * 0.5f;
            if (text.hasSelection()) {
                float sx = ui.measure(font, size, text, 0, text.selectionStart());
                float ex = ui.measure(font, size, text, 0, text.selectionEnd());
                ui.box()
                        .floating()
                        .clippedByParent()
                        .unclamped()
                        .anchor(Ui.TOP_LEFT, Ui.TOP_LEFT)
                        .offset(pad + sx - scrollX, top)
                        .size(ex - sx, lineHeight)
                        .background(theme.selection)
                        .noHit();
            }
            float blink = ui.time() - ui.stateF(slot, 2);
            if (blink % 1 < 0.5f) {
                ui.box()
                        .floating()
                        .clippedByParent()
                        .unclamped()
                        .anchor(Ui.TOP_LEFT, Ui.TOP_LEFT)
                        .offset(pad + caretX - scrollX, top)
                        .size(1, lineHeight)
                        .background(theme.text)
                        .noHit();
            }
            // Keep the caret inside the visible span (the layout clamps the offset).
            if (rec >= 0) {
                float inner = ui.recordWidth(rec) - 2 * pad;
                if (caretX - scrollX > inner) {
                    ui.setScroll(id, 0, caretX - inner);
                } else if (caretX < scrollX) {
                    ui.setScroll(id, 0, caretX);
                }
            }
        }
        ui.close();
        return text.version() != before;
    }

    // ---- Text area ----------------------------------------------------------------------------

    private static final long GUTTER = 0x6077E2L;
    private static final long BODY = 0xB0D1L;

    /**
     * A multi-line text editor of {@code heightPx} pixels editing {@code text} (M17-05, DESIGN §24
     * D125): lines with a line-number gutter, a caret that moves by character, word, line (Up/Down
     * keep their column), Home/End within the line and Ctrl+Home/End over the whole, Enter inserts
     * a newline and Tab two spaces, Shift selects, Ctrl+A/C/X/V, click to place the caret and drag
     * to select, scrolling both ways to keep the caret visible. Returns whether the text changed
     * this frame. Grows to its parent's width.
     */
    public boolean textArea(long id, TextBuffer text, float heightPx) {
        sync();
        boolean focused = ui.focused(id);
        int before = text.version();
        int font = ui.defaultFont;
        float size = theme.fontSize;
        float lineHeight = ui.lineHeight(font, size);
        float pad = theme.padding;
        int slot = ui.state(id);
        int rec = ui.lastRecord(id);
        float scrollX = ui.scroll(id, 0);
        float scrollY = ui.scroll(id, 1);
        int lines = text.lineCount();
        int digits = lines < 10 ? 1 : lines < 100 ? 2 : lines < 1000 ? 3 : lines < 10000 ? 4 : 6;
        float gutter = ui.measure(font, size, "0") * digits + pad;

        // Mouse: a press places the caret on the line under it, dragging extends the selection.
        if (rec >= 0
                && (ui.active(id) || (ui.pressedThisFrame() && ui.pressedId() == ui.resolve(id)))) {
            float textX = ui.recordX(rec) + pad + gutter - scrollX; // the gutter includes its gap
            float textY = ui.recordY(rec) + pad - scrollY;
            int line =
                    Math.clamp(
                            (int) Math.floor((ui.input().mouseY - textY) / lineHeight),
                            0,
                            lines - 1);
            int start = text.indexOfLine(line);
            int end = text.lineEnd(start);
            int at = start + indexAt(font, size, text, start, end, ui.input().mouseX - textX);
            boolean shift = (ui.input().modifiers & UiKey.MOD_SHIFT) != 0;
            if (ui.pressedThisFrame() && ui.pressedId() == ui.resolve(id)) {
                text.moveTo(at, shift);
                ui.setStateF(slot, 2, ui.time());
                ui.setStateF(slot, 3, -1); // the preferred column follows the click
            } else {
                text.moveTo(at, true);
            }
            if (ui.doubleClicked(id)) {
                text.moveTo(start, false);
                text.moveTo(end, true);
            }
        }

        // Keys and typed text.
        if (focused) {
            UiInput in = ui.input();
            int n = in.keyCount();
            for (int i = 0; i < n; i++) {
                if (!in.keyDown(i)) {
                    continue;
                }
                int key = in.keyScancode(i);
                int mods = in.keyModifiers(i);
                boolean shift = (mods & UiKey.MOD_SHIFT) != 0;
                boolean ctrl = (mods & (UiKey.MOD_CTRL | UiKey.MOD_GUI)) != 0;
                boolean vertical = false;
                switch (key) {
                    case UiKey.LEFT -> {
                        if (text.hasSelection() && !shift) {
                            text.moveTo(text.selectionStart(), false);
                        } else {
                            text.move(ctrl ? -wordLength(text, -1) : -1, shift);
                        }
                    }
                    case UiKey.RIGHT -> {
                        if (text.hasSelection() && !shift) {
                            text.moveTo(text.selectionEnd(), false);
                        } else {
                            text.move(ctrl ? wordLength(text, 1) : 1, shift);
                        }
                    }
                    case UiKey.UP, UiKey.DOWN, UiKey.PAGE_UP, UiKey.PAGE_DOWN -> {
                        vertical = true;
                        int delta =
                                key == UiKey.UP
                                        ? -1
                                        : key == UiKey.DOWN
                                                ? 1
                                                : key == UiKey.PAGE_UP
                                                        ? -pageLines(heightPx, lineHeight)
                                                        : pageLines(heightPx, lineHeight);
                        int line = text.lineOf(text.caret());
                        int start = text.lineStart(text.caret());
                        float x = ui.stateF(slot, 3);
                        if (x < 0) {
                            x = ui.measure(font, size, text, start, text.caret());
                            ui.setStateF(slot, 3, x);
                        }
                        int target = Math.clamp(line + delta, 0, lines - 1);
                        int ts = text.indexOfLine(target);
                        int te = text.lineEnd(ts);
                        text.moveTo(ts + indexAt(font, size, text, ts, te, x), shift);
                    }
                    case UiKey.HOME -> text.moveTo(ctrl ? 0 : text.lineStart(text.caret()), shift);
                    case UiKey.END ->
                            text.moveTo(ctrl ? text.length() : text.lineEnd(text.caret()), shift);
                    case UiKey.BACKSPACE -> text.deleteBackward();
                    case UiKey.DELETE -> text.deleteForward();
                    case UiKey.RETURN -> text.insert('\n');
                    case UiKey.TAB -> text.insert("  ");
                    case UiKey.A -> {
                        if (ctrl) {
                            text.selectAll();
                        }
                    }
                    case UiKey.C, UiKey.X -> {
                        if (ctrl && text.hasSelection()) {
                            scratch.setLength(0);
                            text.selection(scratch);
                            clipboard.set(scratch);
                            if (key == UiKey.X) {
                                text.deleteSelection();
                            }
                        }
                    }
                    case UiKey.V -> {
                        if (ctrl) {
                            text.insert(clipboard.get());
                        }
                    }
                    default -> {}
                }
                if (!vertical) {
                    ui.setStateF(slot, 3, -1);
                }
                ui.setStateF(slot, 2, ui.time());
            }
            int t = in.textCount();
            for (int i = 0; i < t; i++) {
                int cp = in.textCodePoint(i);
                if (cp >= 0x20 && cp != 0x7F) {
                    text.insert(cp);
                }
            }
            if (t > 0) {
                ui.setStateF(slot, 2, ui.time());
                ui.setStateF(slot, 3, -1);
            }
            // Keep the caret inside the visible area (both axes).
            if (rec >= 0) {
                int caretLine = text.lineOf(text.caret());
                float caretX =
                        ui.measure(font, size, text, text.lineStart(text.caret()), text.caret());
                float caretY = caretLine * lineHeight;
                float innerW = ui.recordWidth(rec) - 2 * pad - gutter;
                float innerH = ui.recordHeight(rec) - 2 * pad;
                if (caretX - scrollX > innerW) {
                    ui.setScroll(id, 0, caretX - innerW);
                } else if (caretX < scrollX) {
                    ui.setScroll(id, 0, caretX);
                }
                if (caretY + lineHeight - scrollY > innerH) {
                    ui.setScroll(id, 1, caretY + lineHeight - innerH);
                } else if (caretY < scrollY) {
                    ui.setScroll(id, 1, caretY);
                }
                scrollX = ui.scroll(id, 0);
                scrollY = ui.scroll(id, 1);
            }
        }

        // Boxes: a scroll box with a row of the gutter and the text, each one element.
        ui.box(id)
                .widthGrow()
                .height(heightPx)
                .padding(pad)
                .background(theme.inset)
                .border(focused ? theme.accent : theme.panelBorder, 1)
                .radius(theme.radius)
                .hit()
                .focusable()
                .captureKeys()
                .scroll(true, true)
                .open();
        ui.box().row().gap(pad).open();
        Box g = ui.text(GUTTER).color(theme.textDim).noKerning();
        g.minWidth(gutter - pad);
        for (int i = 1; i <= lines; i++) {
            if (i > 1) {
                g.append('\n');
            }
            g.append(i);
        }
        ui.text(BODY).append(text).noKerning();
        ui.close();
        if (focused) {
            float x0 = gutter; // the gutter's digits plus the gap
            int caretLine = text.lineOf(text.caret());
            float caretX = ui.measure(font, size, text, text.lineStart(text.caret()), text.caret());
            if (text.hasSelection()) {
                int from = text.selectionStart();
                int to = text.selectionEnd();
                int line = text.lineOf(from);
                int at = from;
                while (at < to) {
                    int ls = text.lineStart(at);
                    int le = Math.min(text.lineEnd(at), to);
                    float sx = ui.measure(font, size, text, ls, at);
                    float ex = ui.measure(font, size, text, ls, le);
                    boolean toNextLine = le < to; // the newline is selected too: show it
                    ui.box()
                            .floating()
                            .clippedByParent()
                            .unclamped()
                            .anchor(Ui.TOP_LEFT, Ui.TOP_LEFT)
                            .offset(pad + x0 + sx - scrollX, pad + line * lineHeight - scrollY)
                            .size(Math.max(ex - sx, 0) + (toNextLine ? size * 0.3f : 0), lineHeight)
                            .background(theme.selection)
                            .noHit();
                    at = le + 1;
                    line++;
                }
            }
            float blink = ui.time() - ui.stateF(slot, 2);
            if (blink % 1 < 0.5f) {
                ui.box()
                        .floating()
                        .clippedByParent()
                        .unclamped()
                        .anchor(Ui.TOP_LEFT, Ui.TOP_LEFT)
                        .offset(pad + x0 + caretX - scrollX, pad + caretLine * lineHeight - scrollY)
                        .size(1, lineHeight)
                        .background(theme.text)
                        .noHit();
            }
        }
        ui.close();
        return text.version() != before;
    }

    private static int pageLines(float heightPx, float lineHeight) {
        return Math.max(1, (int) (heightPx / lineHeight) - 1);
    }

    /** The char index in {@code s[from, to)} nearest to {@code x} pixels from {@code from}. */
    private int indexAt(int font, float size, CharSequence s, int from, int to, float x) {
        FontData fd = ui.font(font);
        float scale = size / fd.emSize();
        float pen = 0;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            int cp = c;
            int len = 1;
            if (Character.isHighSurrogate(c)
                    && i + 1 < to
                    && Character.isLowSurrogate(s.charAt(i + 1))) {
                cp = Character.toCodePoint(c, s.charAt(i + 1));
                len = 2;
            }
            float adv = fd.advance(fd.glyphOrFallback(cp)) * scale;
            if (x < pen + adv * 0.5f) {
                return i - from;
            }
            pen += adv;
            i += len - 1;
        }
        return to - from;
    }

    /** Returns whether Enter was pressed in text field {@code id} this frame. */
    public boolean submitted(long id) {
        sync();
        return submitted != 0 && submitted == ui.resolve(id);
    }

    /** The character index nearest to {@code x} pixels from the start of {@code s}. */
    private int indexAt(int font, float size, CharSequence s, float x) {
        FontData fd = ui.font(font);
        float scale = size / fd.emSize();
        float pen = 0;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            int cp = c;
            int len = 1;
            if (Character.isHighSurrogate(c)
                    && i + 1 < n
                    && Character.isLowSurrogate(s.charAt(i + 1))) {
                cp = Character.toCodePoint(c, s.charAt(i + 1));
                len = 2;
            }
            float adv = fd.advance(fd.glyphOrFallback(cp)) * scale;
            if (x < pen + adv * 0.5f) {
                return i;
            }
            pen += adv;
            i += len - 1;
        }
        return n;
    }

    /** The number of chars to the next (or previous, {@code dir < 0}) word boundary. */
    private static int wordLength(TextBuffer t, int dir) {
        int i = t.caret();
        int n = 0;
        if (dir < 0) {
            while (i > 0 && t.charAt(i - 1) == ' ') {
                i--;
                n++;
            }
            while (i > 0 && t.charAt(i - 1) != ' ') {
                i--;
                n++;
            }
        } else {
            int len = t.length();
            while (i < len && t.charAt(i) != ' ') {
                i++;
                n++;
            }
            while (i < len && t.charAt(i) == ' ') {
                i++;
                n++;
            }
        }
        return Math.max(n, 1);
    }

    // ---- Choice -----------------------------------------------------------------------------

    /**
     * A drop-down list; returns the selected index after this frame. The list opens as a popup
     * under the button and closes on a choice or a press elsewhere.
     */
    public int dropdown(long id, int selected, CharSequence[] options) {
        int slot = ui.state(id);
        boolean open = ui.stateI(slot, 0) != 0;
        long popupId = Ui.combine(id, POPUP);
        if (ui.clicked(id)) {
            open = !open;
        } else if (open
                && ui.pressedThisFrame()
                && ui.pressedFloating() != ui.resolve(popupId)
                && ui.pressedId() != ui.resolve(id)) {
            open = false;
        }
        Box b =
                ui.box(id)
                        .row()
                        .gap(theme.gap)
                        .widthGrow()
                        .height(theme.widgetHeight)
                        .padding(theme.padding, 0)
                        .align(Ui.START, Ui.CENTER)
                        .radius(theme.radius)
                        .background(widgetColor(id, true))
                        .focusable()
                        .activateWithKeys();
        focusRing(b, id);
        b.open();
        if (selected >= 0 && selected < options.length) {
            ui.text(options[selected]);
        } else {
            ui.text("").color(theme.textDim);
        }
        spacer();
        ui.text(TRIANGLE_DOWN).fontSize(theme.fontSizeSmall).color(theme.textDim);
        ui.close();
        if (open) {
            int rec = ui.lastRecord(id);
            ui.box(popupId)
                    .floatingOn(id)
                    .anchor(Ui.BOTTOM_LEFT, Ui.TOP_LEFT)
                    .offset(0, 2)
                    .z(100)
                    .column()
                    .minWidth(rec >= 0 ? ui.recordWidth(rec) : 0)
                    .padding(2)
                    .background(theme.panel)
                    .border(theme.panelBorder, 1)
                    .radius(theme.radius)
                    .open();
            for (int i = 0; i < options.length; i++) {
                ui.pushId(i);
                boolean hover = ui.hovered(OPTION);
                ui.box(OPTION)
                        .widthGrow()
                        .height(theme.widgetHeight)
                        .padding(theme.padding, 0)
                        .align(Ui.START, Ui.CENTER)
                        .radius(theme.radius)
                        .background(
                                hover
                                        ? theme.widgetHover
                                        : i == selected ? theme.widget : theme.panel)
                        .hit()
                        .open();
                ui.text(options[i]);
                ui.close();
                if (ui.clicked(OPTION)) {
                    selected = i;
                    open = false;
                }
                ui.popId();
            }
            ui.close();
        }
        ui.setStateI(slot, 0, open ? 1 : 0);
        return selected;
    }

    /** A row of tabs; returns the selected index after this frame. */
    public int tabs(long id, int selected, CharSequence[] labels) {
        ui.box(id).row().gap(2).open();
        for (int i = 0; i < labels.length; i++) {
            ui.pushId(i);
            if (ui.clicked(TAB)) {
                selected = i;
            }
            boolean on = i == selected;
            ui.box(TAB)
                    .column()
                    .gap(4)
                    .padding(theme.padding, 4, theme.padding, 0)
                    .align(Ui.CENTER, Ui.START)
                    .background(on ? theme.widget : ui.hovered(TAB) ? theme.widgetHover : 0)
                    .radius(theme.radius)
                    .hit()
                    .focusable()
                    .activateWithKeys()
                    .open();
            ui.text(labels[i]).color(on ? theme.text : theme.textDim);
            ui.box().widthGrow().height(2).background(on ? theme.accent : 0).noHit();
            ui.close();
            ui.popId();
        }
        ui.close();
        return selected;
    }

    // ---- Trees ------------------------------------------------------------------------------

    /**
     * A collapsible tree node; returns whether it is open. When it is, declare the children and
     * call {@link #treeEnd()}:
     *
     * <pre>{@code
     * if (w.treeNode(FOLDER, "Textures")) {
     *     for (...) w.treeLeaf(ITEM, name, selected == i);
     *     w.treeEnd();
     * }
     * }</pre>
     */
    public boolean treeNode(long id, CharSequence label) {
        return treeNode(id, label, false);
    }

    /** A tree node that starts open when {@code defaultOpen}. */
    public boolean treeNode(long id, CharSequence label, boolean defaultOpen) {
        int slot = ui.state(id);
        if (ui.stateI(slot, 1) == 0) {
            ui.setStateI(slot, 1, 1);
            ui.setStateI(slot, 0, defaultOpen ? 1 : 0);
        }
        boolean open = ui.stateI(slot, 0) != 0;
        if (ui.clicked(id)) {
            open = !open;
            ui.setStateI(slot, 0, open ? 1 : 0);
        }
        ui.box(id)
                .row()
                .gap(4)
                .widthGrow()
                .height(theme.widgetHeight - 4)
                .padding(4, 0)
                .align(Ui.START, Ui.CENTER)
                .radius(theme.radius)
                .background(ui.hovered(id) ? theme.widgetHover : 0)
                .hit()
                .focusable()
                .activateWithKeys()
                .open();
        ui.box().size(12, 12).align(Ui.CENTER, Ui.CENTER).open();
        ui.text(open ? TRIANGLE_DOWN : TRIANGLE_RIGHT).fontSize(9).color(theme.textDim);
        ui.close();
        ui.text(label);
        ui.close();
        if (open) {
            ui.box().column().widthGrow().padding(16, 0, 0, 0).open();
        }
        return open;
    }

    /** Closes an open tree node's children. */
    public void treeEnd() {
        ui.close();
    }

    /** A selectable tree leaf; returns whether it was clicked. */
    public boolean treeLeaf(long id, CharSequence label, boolean selected) {
        ui.box(id)
                .row()
                .gap(4)
                .widthGrow()
                .height(theme.widgetHeight - 4)
                .padding(20, 0, 4, 0)
                .align(Ui.START, Ui.CENTER)
                .radius(theme.radius)
                .background(selected ? theme.accent : ui.hovered(id) ? theme.widgetHover : 0)
                .hit()
                .focusable()
                .activateWithKeys()
                .open();
        ui.text(label).color(selected ? 0xFFFFFFFF : theme.text);
        ui.close();
        return ui.clicked(id);
    }

    // ---- Floating ---------------------------------------------------------------------------

    /**
     * Shows {@code text} under element {@code target} once the pointer has rested on it for the
     * theme's delay. Call it after declaring the target (anywhere in the frame).
     */
    public void tooltip(long target, CharSequence text) {
        if (!ui.hovered(target) || ui.hoverTime() < theme.tooltipDelay || ui.dragging()) {
            return;
        }
        ui.box()
                .floatingOn(target)
                .anchor(Ui.BOTTOM_LEFT, Ui.TOP_LEFT)
                .offset(0, 4)
                .z(1000)
                .padding(theme.padding, 4)
                .background(theme.tooltip)
                .border(theme.panelBorder, 1)
                .radius(theme.radius)
                .maxWidth(320)
                .noHit()
                .open();
        ui.text(text).wrap().fontSize(theme.fontSizeSmall);
        ui.close();
    }

    /** Opens popup {@code id} (see {@link #beginPopup}). */
    public void openPopup(long id) {
        ui.setStateI(ui.state(id), 0, 1);
    }

    /** Closes popup {@code id}. */
    public void closePopup(long id) {
        ui.setStateI(ui.state(id), 0, 0);
    }

    /** Returns whether popup {@code id} is open. */
    public boolean popupOpen(long id) {
        return ui.hasState(id) && ui.stateI(ui.state(id), 0) != 0;
    }

    /**
     * Declares popup {@code id} if it is open: a floating panel attached to element {@code
     * anchor}'s rectangle ({@code targetAnchor} of it meets {@code ownAnchor} of the popup), on top
     * of everything, closed by a press outside it. Returns whether it is open; when it is, declare
     * its contents and call {@link #endPopup()}.
     */
    public boolean beginPopup(long id, long anchor, int targetAnchor, int ownAnchor) {
        int slot = ui.state(id);
        if (ui.stateI(slot, 0) == 0) {
            return false;
        }
        if (ui.pressedThisFrame()
                && ui.pressedFloating() != ui.resolve(id)
                && ui.pressedId() != ui.resolve(anchor)) {
            ui.setStateI(slot, 0, 0);
            return false;
        }
        ui.box(id)
                .floatingOn(anchor)
                .anchor(targetAnchor, ownAnchor)
                .offset(0, 2)
                .z(100)
                .column()
                .gap(2)
                .padding(theme.padding * 0.5f)
                .background(theme.panel)
                .border(theme.panelBorder, 1)
                .radius(theme.radius)
                .hit()
                .open();
        return true;
    }

    /** Closes a popup's contents. */
    public void endPopup() {
        ui.close();
    }

    /** A menu entry inside a popup; returns whether it was clicked (and closes the popup). */
    public boolean menuItem(long popup, long id, CharSequence label) {
        ui.box(id)
                .widthGrow()
                .minWidth(120)
                .height(theme.widgetHeight - 4)
                .padding(theme.padding, 0)
                .align(Ui.START, Ui.CENTER)
                .radius(theme.radius)
                .background(ui.hovered(id) ? theme.widgetHover : 0)
                .hit()
                .open();
        ui.text(label);
        ui.close();
        if (ui.clicked(id)) {
            closePopup(popup);
            return true;
        }
        return false;
    }

    /**
     * A movable window: a floating panel with a title bar that drags it, brought to the front when
     * pressed. {@code x, y} are its first position; {@code w, h} its size (0 fits the content).
     * Declare the contents and call {@link #endWindow()}.
     */
    public void beginWindow(long id, CharSequence title, float x, float y, float w, float h) {
        sync();
        int slot = ui.state(id);
        if (ui.stateI(slot, 1) == 0) {
            ui.setStateI(slot, 1, 1);
            ui.setStateF(slot, 0, x);
            ui.setStateF(slot, 1, y);
            ui.setStateI(slot, 0, ++frontZ);
        }
        long bar = Ui.combine(id, BAR);
        float fx = ui.stateF(slot, 0);
        float fy = ui.stateF(slot, 1);
        if (ui.active(bar)) {
            fx += mouseDX;
            fy += mouseDY;
            ui.setStateF(slot, 0, fx);
            ui.setStateF(slot, 1, fy);
        }
        if (ui.pressedThisFrame() && ui.pressedFloating() == ui.resolve(id)) {
            ui.setStateI(slot, 0, ++frontZ);
        }
        Box win =
                ui.box(id)
                        .floatingOnScreen()
                        .anchor(Ui.TOP_LEFT, Ui.TOP_LEFT)
                        .offset(fx, fy)
                        .z(ui.stateI(slot, 0))
                        .column()
                        .background(theme.panel)
                        .border(theme.panelBorder, 1)
                        .radius(theme.radius)
                        .clip()
                        .hit();
        if (w > 0) {
            win.width(w);
        }
        if (h > 0) {
            win.height(h);
        }
        win.open();
        ui.box(bar)
                .widthGrow()
                .height(theme.widgetHeight)
                .padding(theme.padding, 0)
                .align(Ui.START, Ui.CENTER)
                .background(ui.active(bar) ? theme.widgetActive : theme.widget)
                .hit()
                .open();
        ui.text(title);
        ui.close();
        ui.box().column().widthGrow().heightGrow().padding(theme.padding).gap(theme.gap).open();
    }

    /** Closes a window's contents. */
    public void endWindow() {
        ui.close();
        ui.close();
    }

    // ---- Menus ------------------------------------------------------------------------------

    /**
     * A menu bar across the parent's width; declare {@link #menu}s inside and call {@link
     * #endMenuBar()}.
     */
    public void beginMenuBar(long id) {
        ui.box(id)
                .row()
                .widthGrow()
                .height(theme.widgetHeight)
                .padding(4, 0)
                .gap(2)
                .align(Ui.START, Ui.CENTER)
                .background(theme.panel)
                .border(theme.panelBorder, 1)
                .hit()
                .open();
    }

    /** Closes a menu bar. */
    public void endMenuBar() {
        ui.close();
    }

    /**
     * A menu button in a menu bar whose popup opens on click and switches to a neighbour when the
     * pointer moves onto it while one is open. Returns whether the popup is open; when it is,
     * declare {@link #menuItem(long, CharSequence)} entries and call {@link #endMenu()}.
     */
    public boolean menu(long id, CharSequence label) {
        long popup = Ui.combine(id, POPUP);
        long me = ui.resolve(id);
        if (ui.clicked(id)) {
            if (popupOpen(popup)) {
                closePopup(popup);
                openMenu = 0;
            } else {
                openPopup(popup);
                openMenu = me;
            }
        } else if (openMenu != 0 && openMenu != me && ui.hovered(id)) {
            closePopup(Ui.combine(openMenu, POPUP));
            openPopup(popup);
            openMenu = me;
        }
        boolean on = popupOpen(popup);
        ui.box(id)
                .height(theme.widgetHeight - 4)
                .padding(theme.padding, 0)
                .align(Ui.CENTER, Ui.CENTER)
                .radius(theme.radius)
                .background(on ? theme.widgetActive : ui.hovered(id) ? theme.widgetHover : 0)
                .hit()
                .open();
        ui.text(label);
        ui.close();
        if (!beginPopup(popup, id, Ui.BOTTOM_LEFT, Ui.TOP_LEFT)) {
            if (openMenu == me) {
                openMenu = 0;
            }
            return false;
        }
        return true;
    }

    /** Closes a menu's popup contents. */
    public void endMenu() {
        endPopup();
    }

    /** A menu entry in the open {@link #menu}; returns whether it was clicked (closes the menu). */
    public boolean menuItem(long id, CharSequence label) {
        return menuItem(id, label, null, true);
    }

    /**
     * A menu entry with a shortcut hint at its right ({@code "Ctrl+S"}) that can be disabled;
     * returns whether it was clicked.
     */
    public boolean menuItem(long id, CharSequence label, CharSequence shortcut, boolean enabled) {
        ui.box(id)
                .row()
                .gap(theme.gap * 3)
                .widthGrow()
                .minWidth(160)
                .height(theme.widgetHeight - 4)
                .padding(theme.padding, 0)
                .align(Ui.START, Ui.CENTER)
                .radius(theme.radius)
                .background(enabled && ui.hovered(id) ? theme.widgetHover : 0)
                .hit()
                .open();
        ui.text(label).color(enabled ? theme.text : theme.textDim);
        if (shortcut != null) {
            spacer();
            ui.text(shortcut).fontSize(theme.fontSizeSmall).color(theme.textDim);
        }
        ui.close();
        if (enabled && ui.clicked(id)) {
            closeOpenMenu();
            return true;
        }
        return false;
    }

    /** A menu entry showing a check mark when {@code checked}; returns whether it was clicked. */
    public boolean menuCheck(long id, CharSequence label, boolean checked) {
        ui.box(id)
                .row()
                .gap(theme.gap)
                .widthGrow()
                .minWidth(160)
                .height(theme.widgetHeight - 4)
                .padding(theme.padding * 0.5f, 0, theme.padding, 0)
                .align(Ui.START, Ui.CENTER)
                .radius(theme.radius)
                .background(ui.hovered(id) ? theme.widgetHover : 0)
                .hit()
                .open();
        ui.box().size(14, 14).align(Ui.CENTER, Ui.CENTER).noHit().open();
        if (checked) {
            ui.text(CHECK).fontSize(theme.fontSizeSmall);
        }
        ui.close();
        ui.text(label);
        ui.close();
        if (ui.clicked(id)) {
            closeOpenMenu();
            return true;
        }
        return false;
    }

    private void closeOpenMenu() {
        if (openMenu != 0) {
            closePopup(Ui.combine(openMenu, POPUP));
            openMenu = 0;
        }
    }

    // ---- Docking ----------------------------------------------------------------------------

    /**
     * A splitter bar between two regions: a thin strip the pointer can drag. {@code vertical} bars
     * stand between side-by-side regions and are dragged left and right; horizontal ones lie
     * between stacked regions. Returns how far it was dragged this frame along its axis, in pixels
     * (0 when idle); the caller applies it to the size it controls.
     */
    public float splitter(long id, boolean vertical) {
        sync();
        Box b =
                ui.box(id)
                        .background(
                                ui.active(id)
                                        ? theme.accent
                                        : ui.hovered(id) ? theme.accentHover : theme.separator)
                        .hit();
        if (vertical) {
            b.width(theme.splitterWidth).heightGrow();
        } else {
            b.widthGrow().height(theme.splitterWidth);
        }
        return ui.active(id) ? (vertical ? mouseDX : mouseDY) : 0;
    }

    // ---- Fields -----------------------------------------------------------------------------

    /**
     * A number that drags like {@link #dragValue} and, on a double click, turns into a text field
     * to type into (Enter or a click elsewhere commits, Escape cancels). Returns the value after
     * this frame's input. It grows to its parent's width.
     */
    public float numberField(long id, float value, float step, float min, float max, int decimals) {
        sync();
        int slot = ui.state(id);
        boolean editing = ui.stateI(slot, 1) != 0;
        if (editing) {
            boolean cancel = ui.input().pressed(UiKey.ESCAPE);
            ui.pushId(id);
            textField(FIELD, numberEdit);
            boolean enter = submitted(FIELD);
            boolean focused = ui.focused(FIELD);
            ui.popId();
            if (cancel) {
                ui.setStateI(slot, 1, 0);
            } else if (enter || !focused) {
                float parsed = parseFloat(numberEdit);
                if (!Float.isNaN(parsed)) {
                    value = Math.clamp(parsed, min, max);
                }
                ui.setStateI(slot, 1, 0);
                if (enter) {
                    ui.focus(0);
                }
            }
            return value;
        }
        if (ui.doubleClicked(id)) {
            numberEdit.clear();
            appendFloat(numberEdit, value, decimals);
            numberEdit.selectAll();
            ui.setStateI(slot, 1, 1);
            ui.pushId(id);
            ui.focus(FIELD);
            ui.popId();
        }
        if (ui.active(id)) {
            value += mouseDX * step;
        }
        if (ui.focused(id)) {
            UiInput in = ui.input();
            if (in.pressed(UiKey.LEFT) || in.pressed(UiKey.DOWN)) {
                value -= step;
            }
            if (in.pressed(UiKey.RIGHT) || in.pressed(UiKey.UP)) {
                value += step;
            }
        }
        value = Math.clamp(value, min, max);
        Box b =
                ui.box(id)
                        .widthGrow()
                        .height(theme.widgetHeight)
                        .minWidth(48)
                        .padding(theme.padding * 0.5f, 0)
                        .align(Ui.END, Ui.CENTER)
                        .radius(theme.radius)
                        .background(widgetColor(id, true))
                        .hit()
                        .focusable()
                        .captureKeys();
        focusRing(b, id);
        b.open();
        ui.text().append(value, decimals).noKerning();
        ui.close();
        return value;
    }

    /** Appends {@code v} with {@code decimals} places to {@code out} without allocating. */
    public static void appendFloat(TextBuffer out, float v, int decimals) {
        if (Float.isNaN(v) || Float.isInfinite(v)) {
            out.insert('0');
            return;
        }
        if (v < 0) {
            out.insert('-');
            v = -v;
        }
        long scale = 1;
        for (int i = 0; i < decimals; i++) {
            scale *= 10;
        }
        long r = Math.round((double) v * scale);
        long whole = r / scale;
        if (whole == 0) {
            out.insert('0');
        } else {
            long p = 1;
            while (p <= whole / 10) {
                p *= 10;
            }
            for (; p > 0; p /= 10) {
                out.insert((char) ('0' + whole / p % 10));
            }
        }
        if (decimals > 0) {
            out.insert('.');
            long frac = r % scale;
            for (long p = scale / 10; p > 0; p /= 10) {
                out.insert((char) ('0' + frac / p % 10));
            }
        }
    }

    /**
     * Parses a decimal number ({@code -12.5}, {@code .75}, {@code 3e2}) without allocating; returns
     * {@code NaN} when {@code s} is not one.
     */
    public static float parseFloat(CharSequence s) {
        int n = s.length();
        int i = 0;
        while (i < n && s.charAt(i) == ' ') {
            i++;
        }
        boolean negative = false;
        if (i < n && (s.charAt(i) == '-' || s.charAt(i) == '+')) {
            negative = s.charAt(i) == '-';
            i++;
        }
        double value = 0;
        int digits = 0;
        while (i < n && Character.isDigit(s.charAt(i))) {
            value = value * 10 + (s.charAt(i) - '0');
            i++;
            digits++;
        }
        if (i < n && s.charAt(i) == '.') {
            i++;
            double place = 0.1;
            while (i < n && Character.isDigit(s.charAt(i))) {
                value += (s.charAt(i) - '0') * place;
                place *= 0.1;
                i++;
                digits++;
            }
        }
        if (digits == 0) {
            return Float.NaN;
        }
        if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            i++;
            boolean expNegative = false;
            if (i < n && (s.charAt(i) == '-' || s.charAt(i) == '+')) {
                expNegative = s.charAt(i) == '-';
                i++;
            }
            int exp = 0;
            int expDigits = 0;
            while (i < n && Character.isDigit(s.charAt(i))) {
                exp = exp * 10 + (s.charAt(i) - '0');
                i++;
                expDigits++;
            }
            if (expDigits == 0) {
                return Float.NaN;
            }
            value *= Math.pow(10, expNegative ? -exp : exp);
        }
        while (i < n && s.charAt(i) == ' ') {
            i++;
        }
        if (i != n) {
            return Float.NaN;
        }
        return (float) (negative ? -value : value);
    }

    /**
     * Three {@link #numberField}s in a row labelled X, Y and Z, editing {@code v[o..o+2]} in place.
     * Returns a mask of the components that changed this frame (bit 0 x, 1 y, 2 z).
     */
    public int vector3Field(long id, float[] v, int o, float step, int decimals) {
        return vectorField(id, v, o, 3, step, -1e9f, 1e9f, decimals);
    }

    /**
     * {@code n} (1 to 4) number fields in a row labelled X, Y, Z and W, editing {@code v[o..]} in
     * place within {@code [min, max]}. Returns a mask of the components that changed.
     */
    public int vectorField(
            long id, float[] v, int o, int n, float step, float min, float max, int decimals) {
        int changed = 0;
        ui.box(id).row().gap(4).widthGrow().align(Ui.START, Ui.CENTER).open();
        ui.pushId(id); // the component fields live in the row's scope
        for (int k = 0; k < n; k++) {
            ui.pushId(k);
            ui.text(AXIS_LABELS[k]).fontSize(theme.fontSizeSmall).color(AXIS_COLORS[k]);
            float before = v[o + k];
            float after = numberField(FIELD, before, step, min, max, decimals);
            if (after != before) {
                v[o + k] = after;
                changed |= 1 << k;
            }
            ui.popId();
        }
        ui.popId();
        ui.close();
        return changed;
    }

    private static final String[] AXIS_LABELS = {"X", "Y", "Z", "W"};
    private static final int[] AXIS_COLORS = {0xFFE06060, 0xFF70D070, 0xFF6090FF, 0xFFC0C0C0};

    /**
     * A color: a swatch showing {@code rgb[o..o+2]} (linear, clamped for display) next to three
     * number fields for the channels (0 to {@code max}; sun colors go above 1). Returns a mask of
     * the channels that changed.
     */
    public int colorField(long id, float[] rgb, int o, float max) {
        int changed = 0;
        ui.box(id).row().gap(4).widthGrow().align(Ui.START, Ui.CENTER).open();
        int packed =
                0xFF000000 | channel(rgb[o]) << 16 | channel(rgb[o + 1]) << 8 | channel(rgb[o + 2]);
        ui.box(SWATCH)
                .size(theme.widgetHeight, theme.widgetHeight)
                .radius(theme.radius)
                .background(packed)
                .border(theme.panelBorder, 1)
                .noHit();
        ui.pushId(id);
        for (int k = 0; k < 3; k++) {
            ui.pushId(k);
            float before = rgb[o + k];
            float after = numberField(FIELD, before, 0.005f, 0, max, 2);
            if (after != before) {
                rgb[o + k] = after;
                changed |= 1 << k;
            }
            ui.popId();
        }
        ui.popId();
        ui.close();
        return changed;
    }

    /** Linear 0..1 to an sRGB byte (the swatch is drawn in the UI's sRGB space). */
    private static int channel(float linear) {
        float c = Math.clamp(linear, 0, 1);
        float s = c <= 0.0031308f ? c * 12.92f : 1.055f * (float) Math.pow(c, 1 / 2.4) - 0.055f;
        return Math.round(s * 255);
    }

    // ---- Lists ------------------------------------------------------------------------------

    /**
     * A scrolling list of {@code count} rows of {@code items}, {@code height} pixels tall, with one
     * selected; returns the selected index after this frame's clicks.
     */
    public int listBox(long id, int selected, CharSequence[] items, int count, float height) {
        ui.box(id)
                .column()
                .widthGrow()
                .height(height)
                .padding(2)
                .gap(1)
                .background(theme.inset)
                .border(theme.panelBorder, 1)
                .radius(theme.radius)
                .scroll(false, true)
                .open();
        ui.pushId(id);
        for (int i = 0; i < count; i++) {
            ui.pushId(i);
            if (selectableRow(ROW, items[i], i == selected, 0)) {
                selected = i;
            }
            ui.popId();
        }
        ui.popId();
        ui.close();
        return selected;
    }

    /**
     * A selectable row (an Outliner entry, a list item): a label with a selected or hovered
     * background, indented by {@code indent} pixels; returns whether it was clicked.
     */
    public boolean selectableRow(long id, CharSequence label, boolean selected, float indent) {
        ui.box(id)
                .widthGrow()
                .height(theme.widgetHeight - 6)
                .padding(theme.padding * 0.5f + indent, 0, theme.padding * 0.5f, 0)
                .align(Ui.START, Ui.CENTER)
                .radius(theme.radius)
                .background(selected ? theme.accent : ui.hovered(id) ? theme.widgetHover : 0)
                .hit()
                .open();
        ui.text(label).color(selected ? 0xFFFFFFFF : theme.text);
        ui.close();
        return ui.clicked(id);
    }

    /**
     * Opens a selectable row whose label the caller declares (several texts, numbers appended
     * without allocating); close it with {@link #endSelectableRow}, which returns whether it was
     * clicked.
     */
    public void beginSelectableRow(long id, boolean selected, float indent) {
        ui.box(id)
                .row()
                .gap(4)
                .widthGrow()
                .height(theme.widgetHeight - 6)
                .padding(theme.padding * 0.5f + indent, 0, theme.padding * 0.5f, 0)
                .align(Ui.START, Ui.CENTER)
                .radius(theme.radius)
                .background(selected ? theme.accent : ui.hovered(id) ? theme.widgetHover : 0)
                .hit()
                .open();
    }

    /** Closes a selectable row; returns whether it was clicked this frame. */
    public boolean endSelectableRow(long id) {
        ui.close();
        return ui.clicked(id);
    }

    // ---- Property rows ----------------------------------------------------------------------

    /**
     * Opens a property row: a dim label of the theme's label width, then whatever value widgets the
     * caller declares; close it with {@link #endProperty()}.
     */
    public void beginProperty(CharSequence label) {
        ui.box().row().gap(theme.gap).widthGrow().align(Ui.START, Ui.CENTER).open();
        ui.box().width(theme.labelWidth).align(Ui.START, Ui.CENTER).noHit().open();
        ui.text(label).color(theme.textDim).fontSize(theme.fontSizeSmall);
        ui.close();
    }

    /** Closes a property row. */
    public void endProperty() {
        ui.close();
    }
}
