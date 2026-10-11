package dev.jeddy.ui;

import dev.jeddy.asset.format.FontData;
import dev.jeddy.asset.format.FontWriter;
import dev.jeddy.core.collections.LongIntMap;
import dev.jeddy.core.id.StrId;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;

/**
 * The immediate-mode UI context (TASKS M13-03, DESIGN §18, D82): every frame the game declares its
 * interface again as a tree of boxes and text, the context lays the tree out at the end of the
 * frame with a flex-like algorithm and emits a {@link UiDrawList}, and it keeps between frames only
 * what interaction needs (the hovered, active and focused element, scroll offsets and other small
 * per-widget state).
 *
 * <p>A frame looks like this:
 *
 * <pre>{@code
 * ui.beginFrame(input);                       // resolves hover, clicks, focus from last frame's rects
 * ui.box(PANEL).column().padding(8).gap(6).background(0xF0202228).open();
 *     ui.text().append("Hit points: ").append(hp);
 *     ui.box(BAR).width(200).height(12).background(0xFF4C8DFF);
 * ui.close();
 * ui.endFrame(packet.ui);                     // six layout passes, then the draw list
 * }</pre>
 *
 * {@link #box} appends an element and returns the context's single reusable {@link Box} builder;
 * {@link Box#open()} makes the element the parent of what follows until {@link #close()}. Elements
 * without {@code open()} are leaves. Text elements are declared with {@link #text()} and filled
 * with {@link Box#append} calls that copy characters (and format numbers) into the frame's
 * character buffer, so no strings are created per frame.
 *
 * <p><b>Sizing.</b> Each axis of an element is {@link #FIXED} (a size in pixels), {@link #FIT} (the
 * content, between a minimum and a maximum), {@link #GROW} (a share of the parent's free space by
 * weight) or {@link #PERCENT} (a fraction of the parent's inner size). Boxes lay out their children
 * in a {@link #ROW} or a {@link #COLUMN} with padding, a gap and an alignment per axis; a {@link
 * Box#floating floating} element leaves the flow and attaches to its parent's rectangle (or the
 * screen) at an anchor. See DESIGN §18 for the six passes.
 *
 * <p><b>Interaction.</b> Hit testing uses the rectangles of the previous frame (the layout of this
 * frame does not exist yet while the game declares it), so {@link #hovered}, {@link #active},
 * {@link #clicked} and the drag and drop queries answer about the element with that id as it was
 * drawn last frame. One frame of latency is the price of laying out after declaring.
 *
 * <p><b>Ids.</b> Elements that take part in interaction or keep state carry a 64-bit id, usually
 * {@link StrId#of} of a name computed once. Inside lists, {@link #pushId(int)} scopes the ids
 * declared until {@link #popId()}, so a row's buttons can reuse the same constants.
 *
 * <p>Thread ownership: the game thread (one thread at a time). Allocates nothing per frame once the
 * capacities in {@link UiConfig} are not exceeded.
 */
public final class Ui {
    // ---- Sizing modes -----------------------------------------------------------------------

    /** Size mode: a fixed size in pixels. */
    public static final int FIXED = 0;

    /** Size mode: the size of the content, clamped to a minimum and a maximum. */
    public static final int FIT = 1;

    /** Size mode: a share of the parent's remaining space, by weight. */
    public static final int GROW = 2;

    /** Size mode: a fraction of the parent's inner size. */
    public static final int PERCENT = 3;

    // ---- Directions and alignment ----------------------------------------------------------

    /** Layout direction: children side by side. */
    public static final int ROW = 0;

    /** Layout direction: children stacked. */
    public static final int COLUMN = 1;

    /** Alignment: at the start (left or top). */
    public static final int START = 0;

    /** Alignment: centered. */
    public static final int CENTER = 1;

    /** Alignment: at the end (right or bottom). */
    public static final int END = 2;

    // ---- Anchors (floating elements): x in bits 0-1, y in bits 2-3, as START/CENTER/END ----

    /** Anchor: top left corner. */
    public static final int TOP_LEFT = START | START << 2;

    /** Anchor: middle of the top edge. */
    public static final int TOP = CENTER | START << 2;

    /** Anchor: top right corner. */
    public static final int TOP_RIGHT = END | START << 2;

    /** Anchor: middle of the left edge. */
    public static final int LEFT = START | CENTER << 2;

    /** Anchor: the center. */
    public static final int MIDDLE = CENTER | CENTER << 2;

    /** Anchor: middle of the right edge. */
    public static final int RIGHT = END | CENTER << 2;

    /** Anchor: bottom left corner. */
    public static final int BOTTOM_LEFT = START | END << 2;

    /** Anchor: middle of the bottom edge. */
    public static final int BOTTOM = CENTER | END << 2;

    /** Anchor: bottom right corner. */
    public static final int BOTTOM_RIGHT = END | END << 2;

    // ---- Element flags (package-private; the Box builder sets them) ------------------------

    static final int F_TEXT = 1;
    static final int F_CLIP = 1 << 1;
    static final int F_SCROLL_X = 1 << 2;
    static final int F_SCROLL_Y = 1 << 3;
    static final int F_FLOATING = 1 << 4;
    static final int F_HIT = 1 << 5;
    static final int F_FOCUSABLE = 1 << 6;
    static final int F_DRAG_SOURCE = 1 << 7;
    static final int F_DROP_TARGET = 1 << 8;
    static final int F_CAPTURE_KEYS = 1 << 9;
    static final int F_ACTIVATE = 1 << 10;
    static final int F_WRAP = 1 << 11;
    static final int F_CLAMP_SCREEN = 1 << 12;
    static final int F_DISABLED = 1 << 13;
    static final int F_NO_KERNING = 1 << 14;
    static final int F_SCROLLBAR = 1 << 15; // records only: a scrollbar track of a scroll box
    static final int F_OPENED = 1 << 16;
    static final int F_NO_HIT = 1 << 17; // explicitly transparent to the pointer
    static final int F_NO_BARS = 1 << 18; // a scroll box without visible scrollbars
    static final int F_FLOAT_CLIP = 1 << 19; // a floating element clipped like its siblings

    /** Float target: the parent element. */
    static final int TARGET_PARENT = -1;

    /** Float target: the screen. */
    static final int TARGET_SCREEN = -2;

    /** Float target: the previous frame's rectangle of the id in {@code floatId}. */
    static final int TARGET_ID = -3;

    /** Pixels the pointer must travel while pressed before a drag source starts a drag. */
    public static final float DRAG_THRESHOLD = 4;

    /** Pixels scrolled per wheel line. */
    public static final float WHEEL_LINE = 40;

    /** Seconds between two clicks for a double click. */
    public static final float DOUBLE_CLICK_TIME = 0.35f;

    private static final float EPS = 1e-3f;
    private static final float INF = Float.MAX_VALUE;
    private static final long NONE = 0;

    // ---- Elements (SoA) ---------------------------------------------------------------------

    int capacity;
    int count;
    long[] ids;
    int[] parent;
    int[] firstChild;
    int[] lastChild;
    int[] nextSibling;
    int[] flags;
    byte[] direction;

    /** Per axis (0 = x, 1 = y): size mode, mode value (fixed size, weight, fraction), bounds. */
    final byte[][] mode = new byte[2][];

    final float[][] value = new float[2][];
    final float[][] min = new float[2][];
    final float[][] max = new float[2][];

    /** Per axis: padding before (left/top) and after (right/bottom), alignment of children. */
    final float[][] pad0 = new float[2][];

    final float[][] pad1 = new float[2][];
    final byte[][] align = new byte[2][];

    /** Per axis: computed size, shrink floor, position, content extent (with padding). */
    final float[][] size = new float[2][];

    final float[][] floor = new float[2][];
    final float[][] pos = new float[2][];
    final float[][] content = new float[2][];
    float[] gap;
    int[] background;
    int[] borderColor;
    float[] borderWidth;
    float[] radius;
    Sprite[] sprite;
    int[] tint;
    int[] anchor; // target anchor in bits 0-3, own anchor in bits 4-7
    int[] floatTarget;
    long[] floatId;
    float[] offsetX;
    float[] offsetY;
    int[] z;
    int[] dragKind;
    int[] dragValue;
    int[] stateSlot;
    int[] floatClip; // F_FLOAT_CLIP: the clip index in force where the element was declared
    float[] floatClipRect; // and its rectangle, 4 per element
    // Text
    int[] textStart;
    int[] textLength;
    int[] font;
    float[] fontSize;
    int[] textColor;
    byte[] textAlign;
    int[] lineStart;
    int[] lineCount;
    float[] naturalWidth;
    float[] longestWord;

    // ---- Characters and lines ---------------------------------------------------------------

    char[] chars;
    float[] advances;
    int[] glyphs; // glyph index, -1 newline, -2 low surrogate of a pair
    int charCount;
    int[] lineFrom;
    int[] lineTo;
    float[] lineWidth;
    int lines;

    // ---- Fonts ------------------------------------------------------------------------------

    private FontData[] fontData;
    private int[] fontTexture;
    private int fontCount;

    /** Default font index for new text elements. */
    public int defaultFont;

    /** Default font size for new text elements. */
    public float defaultFontSize = 15;

    /** Default color for new text elements. */
    public int defaultTextColor = 0xFFE6E6E6;

    /** Scrollbar width in pixels. */
    public float scrollbarWidth = 10;

    /** Scrollbar track color. */
    public int scrollTrackColor = 0x40000000;

    /** Scrollbar thumb color. */
    public int scrollThumbColor = 0x80FFFFFF;

    // ---- Declaration state ------------------------------------------------------------------

    private final Box box = new Box(this);
    private int[] parentStack = new int[64];
    private int parentDepth;
    private long[] idStack = new long[64];
    private int idDepth;
    private int lastText = -1;

    // ---- Records of the previous frame (hit testing) ----------------------------------------

    private int recCapacity;
    private int recCount;
    private long[] recId;
    private long[] recFloat;
    private float[] recRect; // x, y, w, h, clip x, y, w, h
    private int[] recFlags;
    private int[] recI0;
    private int[] recI1;
    private float[] recF; // 4 per record
    private LongIntMap recIndex;
    // The set being written this frame (swapped with the above in endFrame).
    private int nextRecCount;
    private long[] nextRecId;
    private long[] nextRecFloat;
    private float[] nextRecRect;
    private int[] nextRecFlags;
    private int[] nextRecI0;
    private int[] nextRecI1;
    private float[] nextRecF;
    private LongIntMap nextRecIndex;

    // ---- Persistent widget state ------------------------------------------------------------

    private final LongIntMap stateIndex;
    private long[] stateId;
    private float[] stateF; // 4 per slot
    private int[] stateI; // 2 per slot
    private int[] stateFrame;
    private int stateCount;

    // ---- Interaction ------------------------------------------------------------------------

    private UiInput input = new UiInput();
    private int frame;
    private float width;
    private float height;
    private boolean pointerOverUi;
    private long hovered;
    private long hoveredDrop;
    private long active;
    private int activeButton;
    private int activeFlags;
    private int activeKind;
    private int activeValue;
    private long activeFloat;
    private float pressX;
    private float pressY;
    private boolean pressed;
    private long pressedId;
    private long pressedFloat;
    private long clicked;
    private int clickButton;
    private int clickCount;
    private long lastClickId;
    private float lastClickTime;
    private float time;
    private long focused;
    private boolean showFocus;
    private boolean dragging;
    private long dragSource;
    private int dragKindNow;
    private int dragValueNow;
    private long dropTarget;
    private boolean dropped;
    private long barDrag;
    private int barAxis;
    private float barGrab;
    private float hoverTime;
    private long hoverPrev;

    // ---- Scratch for layout and emission ----------------------------------------------------

    private int[] stackEl = new int[64];
    private int[] stackChild = new int[64];
    private int[] stackClip = new int[64];
    private float[] stackRect = new float[64 * 4];
    private int[] floats = new int[64];
    private int floatCount;
    private final float[] rectOut = new float[4];

    /** Creates a context with {@link UiConfig#DEFAULT} capacities and the built-in metrics font. */
    public Ui() {
        this(UiConfig.DEFAULT);
    }

    /**
     * Creates a context. Font 0 is a built-in <em>metrics-only</em> font (a monospace of 0.5 em
     * advance, 0.8 em ascent, 1.2 em line height) that draws nothing, so layouts are stable and
     * tests are deterministic before a real font is {@linkplain #addFont added}; the first real
     * font added becomes the default.
     */
    public Ui(UiConfig config) {
        allocateElements(config.maxElements());
        chars = new char[config.maxChars()];
        advances = new float[config.maxChars()];
        glyphs = new int[config.maxChars()];
        lineFrom = new int[config.maxLines()];
        lineTo = new int[config.maxLines()];
        lineWidth = new float[config.maxLines()];
        fontData = new FontData[config.maxFonts() + 1];
        fontTexture = new int[config.maxFonts() + 1];
        allocateRecords(config.maxElements() + 64);
        recIndex = new LongIntMap(config.maxElements() + 64, -1);
        nextRecIndex = new LongIntMap(config.maxElements() + 64, -1);
        stateIndex = new LongIntMap(config.maxStates(), -1);
        stateId = new long[config.maxStates()];
        stateF = new float[config.maxStates() * 4];
        stateI = new int[config.maxStates() * 2];
        stateFrame = new int[config.maxStates()];
        fontData[0] = metricsFont();
        fontTexture[0] = UiDrawList.NO_TEXTURE;
        fontCount = 1;
        width = 0;
        height = 0;
        beginFrame(input);
    }

    private static FontData metricsFont() {
        FontWriter w = new FontWriter().metrics(100, 80, 20, 120, 8).atlas(1, 1);
        for (int cp = 0x20; cp < 0x7F; cp++) {
            w.glyph(cp, 50, 0, 0, 0, 0, 0, 0);
        }
        w.glyph(0xFFFD, 50, 0, 0, 0, 0, 0, 0);
        try {
            return FontData.read(MemorySegment.ofArray(w.toBytes()));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void allocateElements(int n) {
        capacity = n;
        ids = new long[n];
        parent = new int[n];
        firstChild = new int[n];
        lastChild = new int[n];
        nextSibling = new int[n];
        flags = new int[n];
        direction = new byte[n];
        for (int a = 0; a < 2; a++) {
            mode[a] = new byte[n];
            value[a] = new float[n];
            min[a] = new float[n];
            max[a] = new float[n];
            pad0[a] = new float[n];
            pad1[a] = new float[n];
            align[a] = new byte[n];
            size[a] = new float[n];
            floor[a] = new float[n];
            pos[a] = new float[n];
            content[a] = new float[n];
        }
        gap = new float[n];
        background = new int[n];
        borderColor = new int[n];
        borderWidth = new float[n];
        radius = new float[n];
        sprite = new Sprite[n];
        tint = new int[n];
        anchor = new int[n];
        floatTarget = new int[n];
        floatId = new long[n];
        offsetX = new float[n];
        offsetY = new float[n];
        z = new int[n];
        dragKind = new int[n];
        dragValue = new int[n];
        stateSlot = new int[n];
        floatClip = new int[n];
        floatClipRect = new float[n * 4];
        textStart = new int[n];
        textLength = new int[n];
        font = new int[n];
        fontSize = new float[n];
        textColor = new int[n];
        textAlign = new byte[n];
        lineStart = new int[n];
        lineCount = new int[n];
        naturalWidth = new float[n];
        longestWord = new float[n];
    }

    private void growElements() {
        int n = capacity * 2;
        ids = Arrays.copyOf(ids, n);
        parent = Arrays.copyOf(parent, n);
        firstChild = Arrays.copyOf(firstChild, n);
        lastChild = Arrays.copyOf(lastChild, n);
        nextSibling = Arrays.copyOf(nextSibling, n);
        flags = Arrays.copyOf(flags, n);
        direction = Arrays.copyOf(direction, n);
        for (int a = 0; a < 2; a++) {
            mode[a] = Arrays.copyOf(mode[a], n);
            value[a] = Arrays.copyOf(value[a], n);
            min[a] = Arrays.copyOf(min[a], n);
            max[a] = Arrays.copyOf(max[a], n);
            pad0[a] = Arrays.copyOf(pad0[a], n);
            pad1[a] = Arrays.copyOf(pad1[a], n);
            align[a] = Arrays.copyOf(align[a], n);
            size[a] = Arrays.copyOf(size[a], n);
            floor[a] = Arrays.copyOf(floor[a], n);
            pos[a] = Arrays.copyOf(pos[a], n);
            content[a] = Arrays.copyOf(content[a], n);
        }
        gap = Arrays.copyOf(gap, n);
        background = Arrays.copyOf(background, n);
        borderColor = Arrays.copyOf(borderColor, n);
        borderWidth = Arrays.copyOf(borderWidth, n);
        radius = Arrays.copyOf(radius, n);
        sprite = Arrays.copyOf(sprite, n);
        tint = Arrays.copyOf(tint, n);
        anchor = Arrays.copyOf(anchor, n);
        floatTarget = Arrays.copyOf(floatTarget, n);
        floatId = Arrays.copyOf(floatId, n);
        offsetX = Arrays.copyOf(offsetX, n);
        offsetY = Arrays.copyOf(offsetY, n);
        z = Arrays.copyOf(z, n);
        dragKind = Arrays.copyOf(dragKind, n);
        dragValue = Arrays.copyOf(dragValue, n);
        stateSlot = Arrays.copyOf(stateSlot, n);
        floatClip = Arrays.copyOf(floatClip, n);
        floatClipRect = Arrays.copyOf(floatClipRect, n * 4);
        textStart = Arrays.copyOf(textStart, n);
        textLength = Arrays.copyOf(textLength, n);
        font = Arrays.copyOf(font, n);
        fontSize = Arrays.copyOf(fontSize, n);
        textColor = Arrays.copyOf(textColor, n);
        textAlign = Arrays.copyOf(textAlign, n);
        lineStart = Arrays.copyOf(lineStart, n);
        lineCount = Arrays.copyOf(lineCount, n);
        naturalWidth = Arrays.copyOf(naturalWidth, n);
        longestWord = Arrays.copyOf(longestWord, n);
        capacity = n;
    }

    private void allocateRecords(int n) {
        recCapacity = n;
        recId = new long[n];
        recFloat = new long[n];
        recRect = new float[n * 8];
        recFlags = new int[n];
        recI0 = new int[n];
        recI1 = new int[n];
        recF = new float[n * 4];
        nextRecId = new long[n];
        nextRecFloat = new long[n];
        nextRecRect = new float[n * 8];
        nextRecFlags = new int[n];
        nextRecI0 = new int[n];
        nextRecI1 = new int[n];
        nextRecF = new float[n * 4];
    }

    private void growRecords() {
        int n = recCapacity * 2;
        nextRecId = Arrays.copyOf(nextRecId, n);
        nextRecFloat = Arrays.copyOf(nextRecFloat, n);
        nextRecRect = Arrays.copyOf(nextRecRect, n * 8);
        nextRecFlags = Arrays.copyOf(nextRecFlags, n);
        nextRecI0 = Arrays.copyOf(nextRecI0, n);
        nextRecI1 = Arrays.copyOf(nextRecI1, n);
        nextRecF = Arrays.copyOf(nextRecF, n * 4);
        recId = Arrays.copyOf(recId, n);
        recFloat = Arrays.copyOf(recFloat, n);
        recRect = Arrays.copyOf(recRect, n * 8);
        recFlags = Arrays.copyOf(recFlags, n);
        recI0 = Arrays.copyOf(recI0, n);
        recI1 = Arrays.copyOf(recI1, n);
        recF = Arrays.copyOf(recF, n * 4);
        recCapacity = n;
    }

    // ---- Fonts ------------------------------------------------------------------------------

    /**
     * Registers a font and returns its index. {@code atlasTexture} is the renderer's reference for
     * the atlas ({@link UiDrawList#NO_TEXTURE} while it is still loading: text then measures but
     * does not draw). The first font registered becomes {@link #defaultFont}.
     */
    public int addFont(FontData data, int atlasTexture) {
        if (fontCount == fontData.length) {
            fontData = Arrays.copyOf(fontData, fontCount * 2);
            fontTexture = Arrays.copyOf(fontTexture, fontCount * 2);
        }
        int f = fontCount++;
        fontData[f] = data;
        fontTexture[f] = atlasTexture;
        if (f == 1) {
            defaultFont = 1;
        }
        return f;
    }

    /** Sets the atlas texture of a registered font (when its atlas finishes loading). */
    public void setFontTexture(int font, int atlasTexture) {
        fontTexture[font] = atlasTexture;
    }

    /**
     * Replaces a registered font's metrics and atlas (a font registered as a placeholder while its
     * files load).
     */
    public void setFont(int font, FontData data, int atlasTexture) {
        fontData[font] = data;
        fontTexture[font] = atlasTexture;
    }

    /** Returns the atlas texture of a registered font. */
    public int fontTexture(int font) {
        return fontTexture[font];
    }

    /** Returns the metrics of a registered font. */
    public FontData font(int font) {
        return fontData[font];
    }

    /** Returns the line height of {@code font} at {@code size} pixels. */
    public float lineHeight(int font, float size) {
        FontData f = fontData[font];
        return f.lineHeight() * size / f.emSize();
    }

    /** Returns the width of {@code s} in {@code font} at {@code size} pixels (no kerning). */
    public float measure(int font, float size, CharSequence s) {
        return measure(font, size, s, 0, s.length());
    }

    /** Returns the width of {@code s[from, to)} in {@code font} at {@code size} (no kerning). */
    public float measure(int font, float size, CharSequence s, int from, int to) {
        FontData f = fontData[font];
        float scale = size / f.emSize();
        float w = 0;
        int n = to;
        for (int i = from; i < n; i++) {
            char c = s.charAt(i);
            int cp = c;
            if (Character.isHighSurrogate(c)
                    && i + 1 < n
                    && Character.isLowSurrogate(s.charAt(i + 1))) {
                cp = Character.toCodePoint(c, s.charAt(++i));
            }
            w += f.advance(f.glyphOrFallback(cp)) * scale;
        }
        return w;
    }

    // ---- Frame ------------------------------------------------------------------------------

    /**
     * Starts a frame: resets the element tree to the screen root sized {@code in.width ×
     * in.height}, then resolves this frame's interaction state (hovered, active, clicked, drags,
     * focus, wheel scrolling) from {@code in} and the rectangles recorded by the previous {@link
     * #endFrame}. {@code in} is kept by reference until the next call.
     */
    public void beginFrame(UiInput in) {
        input = in;
        frame++;
        time += in.dt;
        width = in.width;
        height = in.height;

        // The screen root.
        count = 0;
        charCount = 0;
        lines = 0;
        parentDepth = 0;
        idDepth = 0;
        lastText = -1;
        int r = newElement(NONE);
        parent[r] = -1;
        mode[0][r] = (byte) FIXED;
        mode[1][r] = (byte) FIXED;
        value[0][r] = width;
        value[1][r] = height;
        direction[r] = (byte) ROW;
        parentStack[0] = r;
        parentDepth = 1;

        resolveInteraction(in);
    }

    private void resolveInteraction(UiInput in) {
        float mx = in.mouseX;
        float my = in.mouseY;
        clicked = NONE;
        dropped = false;
        dropTarget = NONE;
        pressed = false;
        pressedId = NONE;
        pressedFloat = NONE;

        // 1. Hit test the pointer against last frame's records, in draw order: the last hit wins.
        pointerOverUi = false;
        hovered = NONE;
        hoveredDrop = NONE;
        int hoveredRec = -1;
        int barRec = -1;
        int wheelRec = -1;
        long hoveredFloat = NONE;
        int hoveredFlags = 0;
        if (in.mouseInWindow) {
            for (int i = 0; i < recCount; i++) {
                if (!contains(i, mx, my)) {
                    continue;
                }
                int f = recFlags[i];
                if ((f & F_SCROLLBAR) != 0) {
                    barRec = i;
                    pointerOverUi = true;
                    continue;
                }
                if ((f & F_HIT) != 0) {
                    pointerOverUi = true;
                    hovered = recId[i];
                    hoveredRec = i;
                    hoveredFloat = recFloat[i];
                    hoveredFlags = f;
                    barRec = -1;
                }
                if ((f & F_DROP_TARGET) != 0) {
                    hoveredDrop = recId[i];
                }
                if ((f & (F_SCROLL_X | F_SCROLL_Y)) != 0) {
                    wheelRec = i;
                }
            }
        }
        if (barRec >= 0) {
            hovered = NONE;
            hoveredRec = -1;
        }
        if (hovered != hoverPrev) {
            hoverTime = 0;
            hoverPrev = hovered;
        } else {
            hoverTime += in.dt;
        }

        // 2. Buttons.
        int pressedButtons = in.buttonsPressed;
        if (pressedButtons != 0) {
            pressed = true;
            pressedId = hovered;
            pressedFloat = hoveredFloat;
            if (barRec >= 0) {
                pressedFloat = recFloat[barRec];
            }
            showFocus = false;
            if ((pressedButtons & UiInput.LEFT) != 0 && barRec >= 0) {
                pressScrollbar(barRec, mx, my);
            } else if (hoveredRec >= 0 && (hoveredFlags & F_DISABLED) == 0) {
                active = hovered;
                activeButton =
                        (pressedButtons & UiInput.LEFT) != 0
                                ? UiInput.LEFT
                                : (pressedButtons & UiInput.RIGHT) != 0
                                        ? UiInput.RIGHT
                                        : UiInput.MIDDLE;
                activeFlags = hoveredFlags;
                activeKind = recI0[hoveredRec];
                activeValue = recI1[hoveredRec];
                activeFloat = hoveredFloat;
                pressX = mx;
                pressY = my;
                focused = (hoveredFlags & F_FOCUSABLE) != 0 ? hovered : NONE;
            } else {
                active = NONE;
                focused = NONE;
            }
        }
        if (barDrag != NONE) {
            if ((in.buttonsDown & UiInput.LEFT) == 0 && (in.buttonsReleased & UiInput.LEFT) == 0) {
                barDrag = NONE;
            } else {
                dragScrollbar(mx, my);
                if ((in.buttonsReleased & UiInput.LEFT) != 0) {
                    barDrag = NONE;
                }
            }
        }
        if (active != NONE) {
            if (!dragging
                    && activeButton == UiInput.LEFT
                    && (activeFlags & F_DRAG_SOURCE) != 0
                    && (Math.abs(mx - pressX) > DRAG_THRESHOLD
                            || Math.abs(my - pressY) > DRAG_THRESHOLD)) {
                dragging = true;
                dragSource = active;
                dragKindNow = activeKind;
                dragValueNow = activeValue;
            }
            if ((in.buttonsReleased & activeButton) != 0) {
                if (dragging) {
                    dropTarget = hoveredDrop;
                    dropped = dropTarget != NONE;
                    dragging = false;
                } else if (hovered == active) {
                    clicked = active;
                    clickButton = activeButton;
                    if (clicked == lastClickId && time - lastClickTime <= DOUBLE_CLICK_TIME) {
                        clickCount++;
                    } else {
                        clickCount = 1;
                    }
                    lastClickId = clicked;
                    lastClickTime = time;
                }
                active = NONE;
            } else if ((in.buttonsDown & activeButton) == 0) {
                // A release the input missed (window focus lost): cancel.
                active = NONE;
                dragging = false;
            }
        }

        // 3. Wheel.
        if (wheelRec >= 0 && (in.wheelX != 0 || in.wheelY != 0)) {
            int slot = state(recId[wheelRec]);
            int f = recFlags[wheelRec];
            float wx = in.wheelX;
            float wy = in.wheelY;
            if ((f & F_SCROLL_Y) == 0 && (f & F_SCROLL_X) != 0 && wx == 0) {
                wx = wy; // a vertical wheel scrolls a horizontal-only box
                wy = 0;
            }
            if ((f & F_SCROLL_X) != 0) {
                stateF[slot * 4] -= wx * WHEEL_LINE;
            }
            if ((f & F_SCROLL_Y) != 0) {
                stateF[slot * 4 + 1] -= wy * WHEEL_LINE;
            }
        }

        // 4. Keyboard focus navigation.
        int n = in.keyCount();
        for (int i = 0; i < n; i++) {
            if (!in.keyDown(i)) {
                continue;
            }
            int key = in.keyScancode(i);
            int mods = in.keyModifiers(i);
            if (key == UiKey.TAB) {
                moveFocus((mods & UiKey.MOD_SHIFT) != 0 ? -1 : 1);
                showFocus = true;
            } else if (focused != NONE) {
                int fr = recIndex.get(focused);
                int ff = fr >= 0 ? recFlags[fr] : 0;
                if (key == UiKey.ESCAPE) {
                    focused = NONE;
                } else if ((ff & F_CAPTURE_KEYS) == 0) {
                    if (key == UiKey.DOWN || key == UiKey.RIGHT) {
                        moveFocus(1);
                        showFocus = true;
                    } else if (key == UiKey.UP || key == UiKey.LEFT) {
                        moveFocus(-1);
                        showFocus = true;
                    } else if ((key == UiKey.RETURN || key == UiKey.SPACE)
                            && (ff & F_ACTIVATE) != 0
                            && !in.keyRepeat(i)) {
                        clicked = focused;
                        clickButton = UiInput.LEFT;
                        clickCount = 1;
                    }
                }
            }
        }
        if (in.navigation) {
            showFocus = true;
        }
    }

    private boolean contains(int rec, float x, float y) {
        float[] r = recRect;
        int o = rec * 8;
        return x >= r[o]
                && y >= r[o + 1]
                && x < r[o] + r[o + 2]
                && y < r[o + 1] + r[o + 3]
                && x >= r[o + 4]
                && y >= r[o + 5]
                && x < r[o + 4] + r[o + 6]
                && y < r[o + 5] + r[o + 7];
    }

    private void moveFocus(int dir) {
        int n = recCount;
        if (n == 0) {
            return;
        }
        int i = focused == NONE ? -1 : recIndex.get(focused);
        for (int k = 0; k < n; k++) {
            i = dir > 0 ? (i + 1) % n : (i <= 0 ? n - 1 : i - 1);
            if ((recFlags[i] & F_FOCUSABLE) != 0 && (recFlags[i] & F_DISABLED) == 0) {
                focused = recId[i];
                return;
            }
        }
    }

    private void pressScrollbar(int rec, float mx, float my) {
        int axis = recI0[rec];
        float m = axis == 0 ? mx : my;
        float thumbStart = recF[rec * 4];
        float thumbLen = recF[rec * 4 + 1];
        long id = recId[rec];
        int slot = state(id);
        if (m >= thumbStart && m < thumbStart + thumbLen) {
            barDrag = id;
            barAxis = axis;
            barGrab = m - thumbStart;
        } else {
            float page = recF[rec * 4 + 3];
            stateF[slot * 4 + axis] += m < thumbStart ? -page : page;
        }
    }

    private void dragScrollbar(float mx, float my) {
        for (int i = 0; i < recCount; i++) {
            if ((recFlags[i] & F_SCROLLBAR) != 0 && recId[i] == barDrag && recI0[i] == barAxis) {
                float m = barAxis == 0 ? mx : my;
                float trackStart = recRect[i * 8 + barAxis];
                float trackLen = recRect[i * 8 + 2 + barAxis];
                float thumbLen = recF[i * 4 + 1];
                float maxScroll = recF[i * 4 + 2];
                float room = trackLen - thumbLen;
                float t = room > 0 ? (m - barGrab - trackStart) / room : 0;
                int slot = state(barDrag);
                stateF[slot * 4 + barAxis] = Math.clamp(t, 0, 1) * maxScroll;
                return;
            }
        }
    }

    // ---- Declaration ------------------------------------------------------------------------

    /** Returns the input of this frame. */
    public UiInput input() {
        return input;
    }

    /** Returns the screen width this frame. */
    public float width() {
        return width;
    }

    /** Returns the screen height this frame. */
    public float height() {
        return height;
    }

    /** Returns the frame counter. */
    public int frame() {
        return frame;
    }

    /** Returns the time in seconds accumulated from the inputs' {@code dt}. */
    public float time() {
        return time;
    }

    /** Begins an anonymous box (no interaction, no state). */
    public Box box() {
        return box(NONE);
    }

    /**
     * Begins a box with an id (scoped by {@link #pushId}) and returns the builder. The box is a
     * leaf unless {@link Box#open()} is called.
     */
    public Box box(long id) {
        int e = newElement(resolve(id));
        lastText = -1;
        box.cur = e;
        return box;
    }

    /** Begins an anonymous text element; fill it with {@link Box#append}. */
    public Box text() {
        return text(NONE);
    }

    /** Begins a text element with an id. */
    public Box text(long id) {
        int e = newElement(resolve(id));
        flags[e] = F_TEXT;
        textStart[e] = charCount;
        textLength[e] = 0;
        font[e] = defaultFont;
        fontSize[e] = defaultFontSize;
        textColor[e] = defaultTextColor;
        textAlign[e] = (byte) START;
        lastText = e;
        box.cur = e;
        return box;
    }

    /** Begins a text element holding {@code s}. */
    public Box text(CharSequence s) {
        return text().append(s);
    }

    /** Closes the box opened last. */
    public void close() {
        if (parentDepth <= 1) {
            throw new IllegalStateException("close() without an open box");
        }
        parentDepth--;
        lastText = -1;
    }

    /** Returns the index of the box opened last (the current parent); 0 is the screen. */
    public int currentParent() {
        return parentStack[parentDepth - 1];
    }

    /** Scopes the ids declared until {@link #popId()} by {@code scope} (a list index, say). */
    public void pushId(int scope) {
        pushId(scope + 0x100000001L);
    }

    /** Scopes the ids declared until {@link #popId()} by {@code scope}. */
    public void pushId(long scope) {
        if (idDepth == idStack.length) {
            idStack = Arrays.copyOf(idStack, idDepth * 2);
        }
        idStack[idDepth] = combine(idDepth == 0 ? 0 : idStack[idDepth - 1], scope);
        idDepth++;
    }

    /** Ends the innermost id scope. */
    public void popId() {
        if (idDepth == 0) {
            throw new IllegalStateException("popId() without pushId()");
        }
        idDepth--;
    }

    /** Returns {@code id} resolved in the current id scope (what the queries compare). */
    public long resolve(long id) {
        if (id == NONE || idDepth == 0) {
            return id;
        }
        return combine(idStack[idDepth - 1], id);
    }

    /** Returns the id of {@code name} in the current scope. */
    public long id(CharSequence name) {
        return resolve(StrId.of(name));
    }

    /** Combines two ids (order matters). */
    public static long combine(long scope, long id) {
        long h = scope ^ (id + 0x9E3779B97F4A7C15L + (scope << 6) + (scope >>> 2));
        h ^= h >>> 32;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return h == NONE ? 1 : h;
    }

    int newElement(long id) {
        if (count == capacity) {
            growElements();
        }
        int e = count++;
        int p = parentDepth > 0 ? parentStack[parentDepth - 1] : -1;
        ids[e] = id;
        parent[e] = p;
        firstChild[e] = -1;
        lastChild[e] = -1;
        nextSibling[e] = -1;
        if (p >= 0) {
            if (firstChild[p] < 0) {
                firstChild[p] = e;
            } else {
                nextSibling[lastChild[p]] = e;
            }
            lastChild[p] = e;
        }
        flags[e] = 0;
        direction[e] = (byte) ROW;
        for (int a = 0; a < 2; a++) {
            mode[a][e] = (byte) FIT;
            value[a][e] = 0;
            min[a][e] = 0;
            max[a][e] = INF;
            pad0[a][e] = 0;
            pad1[a][e] = 0;
            align[a][e] = (byte) START;
        }
        gap[e] = 0;
        background[e] = 0;
        borderColor[e] = 0;
        borderWidth[e] = 0;
        radius[e] = 0;
        sprite[e] = null;
        tint[e] = 0xFFFFFFFF;
        anchor[e] = TOP_LEFT | TOP_LEFT << 4;
        floatTarget[e] = TARGET_PARENT;
        offsetX[e] = 0;
        offsetY[e] = 0;
        z[e] = 0;
        dragKind[e] = 0;
        dragValue[e] = 0;
        stateSlot[e] = -1;
        textLength[e] = 0;
        lineCount[e] = 0;
        return e;
    }

    void openElement(int e) {
        if ((flags[e] & F_TEXT) != 0) {
            throw new IllegalStateException("text elements have no children");
        }
        if (parentDepth == parentStack.length) {
            parentStack = Arrays.copyOf(parentStack, parentDepth * 2);
        }
        flags[e] |= F_OPENED;
        parentStack[parentDepth++] = e;
        lastText = -1;
    }

    /** Appends a char to the current text element (package-private; {@link Box#append}). */
    void appendChar(int e, char c) {
        if (e != lastText) {
            throw new IllegalStateException("append() after another element was begun");
        }
        if (charCount == chars.length) {
            growChars(charCount + 1);
        }
        chars[charCount++] = c;
        textLength[e]++;
    }

    /** Appends {@code s[from, to)} to the current text element. */
    void appendChars(int e, CharSequence s, int from, int to) {
        if (e != lastText) {
            throw new IllegalStateException("append() after another element was begun");
        }
        int n = to - from;
        if (n <= 0) {
            return;
        }
        if (charCount + n > chars.length) {
            growChars(charCount + n);
        }
        char[] c = chars;
        int o = charCount;
        if (s instanceof String str) {
            str.getChars(from, to, c, o);
        } else {
            for (int i = from; i < to; i++) {
                c[o + i - from] = s.charAt(i);
            }
        }
        charCount += n;
        textLength[e] += n;
    }

    private void growChars(int needed) {
        int n = Math.max(needed, chars.length * 2);
        chars = Arrays.copyOf(chars, n);
        advances = Arrays.copyOf(advances, n);
        glyphs = Arrays.copyOf(glyphs, n);
    }

    // ---- Interaction queries ----------------------------------------------------------------

    /** Returns whether the element {@code id} was under the pointer (as of last frame's layout). */
    public boolean hovered(long id) {
        return hovered != NONE && hovered == resolve(id);
    }

    /** Returns the id of the hovered element, or 0. */
    public long hoveredId() {
        return hovered;
    }

    /**
     * Returns whether the pointer is over any element that blocks it (the game should ignore it).
     */
    public boolean wantsMouse() {
        return pointerOverUi || active != NONE || dragging || barDrag != NONE;
    }

    /** Returns whether an element has keyboard focus (the game should ignore keys). */
    public boolean wantsKeyboard() {
        return focused != NONE;
    }

    /** Returns whether a button is held on element {@code id}. */
    public boolean active(long id) {
        return active != NONE && active == resolve(id);
    }

    /** Returns the id of the active (pressed) element, or 0. */
    public long activeId() {
        return active;
    }

    /** Returns whether element {@code id} was clicked this frame (press and release on it). */
    public boolean clicked(long id) {
        return clicked != NONE && clicked == resolve(id) && clickButton == UiInput.LEFT;
    }

    /** Returns whether element {@code id} was clicked with the right button this frame. */
    public boolean rightClicked(long id) {
        return clicked != NONE && clicked == resolve(id) && clickButton == UiInput.RIGHT;
    }

    /** Returns whether this frame's click on {@code id} was a double click. */
    public boolean doubleClicked(long id) {
        return clicked(id) && clickCount == 2;
    }

    /** Returns whether a button was pressed this frame (anywhere). */
    public boolean pressedThisFrame() {
        return pressed;
    }

    /** Returns the id of the element pressed this frame, or 0 (empty space or no press). */
    public long pressedId() {
        return pressedId;
    }

    /**
     * Returns the id of the floating element (popup, tooltip) that contains this frame's press, or
     * 0: a popup closes when a press lands outside it.
     */
    public long pressedFloating() {
        return pressedFloat;
    }

    /** Returns whether element {@code id} has keyboard focus. */
    public boolean focused(long id) {
        return focused != NONE && focused == resolve(id);
    }

    /** Returns the focused id, or 0. */
    public long focusedId() {
        return focused;
    }

    /** Gives keyboard focus to {@code id} (0 clears it). */
    public void focus(long id) {
        focused = resolve(id);
    }

    /** Returns whether the focus ring should be drawn (keyboard or gamepad navigation). */
    public boolean showFocus() {
        return showFocus;
    }

    /** Returns how long the pointer has rested on the hovered element, in seconds. */
    public float hoverTime() {
        return hoverTime;
    }

    /** Returns whether a drag is in progress. */
    public boolean dragging() {
        return dragging;
    }

    /** Returns the id of the element being dragged, or 0. */
    public long dragSource() {
        return dragSource;
    }

    /** Returns the kind of the drag payload (as declared by {@link Box#dragSource}). */
    public int dragKind() {
        return dragKindNow;
    }

    /** Returns the value of the drag payload. */
    public int dragValue() {
        return dragValueNow;
    }

    /** Returns whether a drag is over drop target {@code id}. */
    public boolean dropHover(long id) {
        return dragging && hoveredDrop != NONE && hoveredDrop == resolve(id);
    }

    /** Returns whether a drag ended on drop target {@code id} this frame. */
    public boolean dropped(long id) {
        return dropped && dropTarget == resolve(id);
    }

    // ---- Records of the previous frame ------------------------------------------------------

    /** Returns the previous frame's record of element {@code id}, or -1 if it was not drawn. */
    public int lastRecord(long id) {
        return recIndex.get(resolve(id));
    }

    /** Returns the x of record {@code r} (see {@link #lastRecord}). */
    public float recordX(int r) {
        return recRect[r * 8];
    }

    /** Returns the y of record {@code r}. */
    public float recordY(int r) {
        return recRect[r * 8 + 1];
    }

    /** Returns the width of record {@code r}. */
    public float recordWidth(int r) {
        return recRect[r * 8 + 2];
    }

    /** Returns the height of record {@code r}. */
    public float recordHeight(int r) {
        return recRect[r * 8 + 3];
    }

    /** Returns the number of records (elements with an id, in draw order). */
    public int recordCount() {
        return recCount;
    }

    /** Returns the id of record {@code r}. */
    public long recordId(int r) {
        return recId[r];
    }

    // ---- Persistent state -------------------------------------------------------------------

    /**
     * Returns the state slot of {@code id} (creating it), for {@link #stateF} and {@link #stateI}:
     * four floats and two ints a widget keeps between frames (scroll offsets, a tree node's open
     * flag, a caret blink timer). Slots unused for two frames are recycled when the store is full.
     */
    public int state(long id) {
        id = resolve(id);
        int slot = stateIndex.get(id);
        if (slot < 0) {
            slot = allocateState(id);
        }
        stateFrame[slot] = frame;
        return slot;
    }

    /** Returns whether {@code id} has a state slot. */
    public boolean hasState(long id) {
        return stateIndex.containsKey(resolve(id));
    }

    private int allocateState(long id) {
        int slot;
        if (stateCount < stateId.length) {
            slot = stateCount++;
        } else {
            slot = -1;
            for (int i = 0; i < stateCount; i++) {
                if (stateFrame[i] < frame - 1) {
                    slot = i;
                    break;
                }
            }
            if (slot < 0) {
                int n = stateCount * 2;
                stateId = Arrays.copyOf(stateId, n);
                stateF = Arrays.copyOf(stateF, n * 4);
                stateI = Arrays.copyOf(stateI, n * 2);
                stateFrame = Arrays.copyOf(stateFrame, n);
                slot = stateCount++;
            } else {
                stateIndex.remove(stateId[slot]);
            }
        }
        stateId[slot] = id;
        stateIndex.put(id, slot);
        stateF[slot * 4] = 0;
        stateF[slot * 4 + 1] = 0;
        stateF[slot * 4 + 2] = 0;
        stateF[slot * 4 + 3] = 0;
        stateI[slot * 2] = 0;
        stateI[slot * 2 + 1] = 0;
        return slot;
    }

    /** Returns float {@code k} (0-3) of state slot {@code slot}. */
    public float stateF(int slot, int k) {
        return stateF[slot * 4 + k];
    }

    /** Sets float {@code k} (0-3) of state slot {@code slot}. */
    public void setStateF(int slot, int k, float v) {
        stateF[slot * 4 + k] = v;
    }

    /** Returns int {@code k} (0-1) of state slot {@code slot}. */
    public int stateI(int slot, int k) {
        return stateI[slot * 2 + k];
    }

    /** Sets int {@code k} (0-1) of state slot {@code slot}. */
    public void setStateI(int slot, int k, int v) {
        stateI[slot * 2 + k] = v;
    }

    /** Returns the scroll offset of scroll box {@code id} along {@code axis} (0 x, 1 y). */
    public float scroll(long id, int axis) {
        int slot = stateIndex.get(resolve(id));
        return slot < 0 ? 0 : stateF[slot * 4 + axis];
    }

    /** Sets the scroll offset of scroll box {@code id} (clamped at layout). */
    public void setScroll(long id, int axis, float offset) {
        stateF[state(id) * 4 + axis] = offset;
    }

    // ---- Layout -----------------------------------------------------------------------------

    /**
     * Ends the frame: lays the tree out (DESIGN §18: fit widths, grow and shrink widths, wrap text,
     * fit heights, grow heights, positions), emits the draw list into {@code out} (reset first) and
     * records the rectangles the next {@link #beginFrame} hit-tests.
     *
     * @throws IllegalStateException if a box is still open
     */
    public void endFrame(UiDrawList out) {
        if (parentDepth != 1) {
            throw new IllegalStateException(
                    "endFrame() with " + (parentDepth - 1) + " open box(es)");
        }
        if (idDepth != 0) {
            throw new IllegalStateException("endFrame() with " + idDepth + " id scope(s) open");
        }
        bindState();
        fitPass(0);
        growPass(0);
        wrapPass();
        fitPass(1);
        growPass(1);
        positionPass();
        emit(out);
    }

    /** Lays the tree out without emitting (tests). */
    void layoutOnly() {
        bindState();
        fitPass(0);
        growPass(0);
        wrapPass();
        fitPass(1);
        growPass(1);
        positionPass();
    }

    private void bindState() {
        for (int e = 0; e < count; e++) {
            if ((flags[e] & (F_SCROLL_X | F_SCROLL_Y)) != 0 && ids[e] != NONE) {
                stateSlot[e] = state(ids[e]);
            }
        }
    }

    /** Pass 1 and 4: sizes that depend on content, children before parents. */
    private void fitPass(int a) {
        byte[] md = mode[a];
        float[] sz = size[a];
        float[] fl = floor[a];
        float[] mn = min[a];
        float[] mx = max[a];
        float[] p0 = pad0[a];
        float[] p1 = pad1[a];
        for (int e = count - 1; e >= 0; e--) {
            float natural;
            float floorSize;
            if ((flags[e] & F_TEXT) != 0) {
                if (a == 0) {
                    measureText(e);
                    natural = naturalWidth[e];
                    floorSize = (flags[e] & F_WRAP) != 0 ? longestWord[e] : natural;
                } else {
                    natural = lineCount[e] * lineHeight(font[e], fontSize[e]);
                    floorSize = natural;
                }
            } else {
                boolean main = direction[e] == a;
                float sum = 0;
                float big = 0;
                float floorSum = 0;
                float floorBig = 0;
                int n = 0;
                for (int c = firstChild[e]; c >= 0; c = nextSibling[c]) {
                    if ((flags[c] & F_FLOATING) != 0) {
                        continue;
                    }
                    float s = sz[c];
                    float f = fl[c];
                    if (main) {
                        sum += s;
                        floorSum += f;
                        n++;
                    } else {
                        big = Math.max(big, s);
                        floorBig = Math.max(floorBig, f);
                    }
                }
                float gaps = n > 1 ? gap[e] * (n - 1) : 0;
                float padding = p0[e] + p1[e];
                natural = (main ? sum + gaps : big) + padding;
                floorSize = (main ? floorSum + gaps : floorBig) + padding;
                boolean scrollable = (flags[e] & (a == 0 ? F_SCROLL_X : F_SCROLL_Y)) != 0;
                if (scrollable) {
                    floorSize = padding;
                }
            }
            switch (md[e]) {
                case FIXED -> {
                    sz[e] = value[a][e];
                    fl[e] = sz[e];
                }
                case FIT -> {
                    sz[e] = Math.clamp(natural, mn[e], mx[e]);
                    fl[e] = Math.min(Math.max(floorSize, mn[e]), sz[e]);
                }
                case GROW -> {
                    fl[e] = Math.min(Math.max(floorSize, mn[e]), mx[e]);
                    sz[e] = fl[e];
                }
                default -> { // PERCENT
                    fl[e] = Math.max(floorSize, mn[e]);
                    sz[e] = fl[e];
                }
            }
        }
    }

    /** Pass 2 and 5: distribute the parent's space, parents before children. */
    private void growPass(int a) {
        byte[] md = mode[a];
        float[] sz = size[a];
        float[] fl = floor[a];
        float[] mn = min[a];
        float[] mx = max[a];
        float[] p0 = pad0[a];
        float[] p1 = pad1[a];
        float[] ct = content[a];
        int scrollFlag = a == 0 ? F_SCROLL_X : F_SCROLL_Y;
        for (int e = 0; e < count; e++) {
            if (firstChild[e] < 0) {
                ct[e] = sz[e];
                continue;
            }
            float inner = sz[e] - p0[e] - p1[e];
            boolean main = direction[e] == a;
            boolean scrollable = (flags[e] & scrollFlag) != 0;
            float sum = 0;
            float big = 0;
            int n = 0;
            for (int c = firstChild[e]; c >= 0; c = nextSibling[c]) {
                boolean floating = (flags[c] & F_FLOATING) != 0;
                if (md[c] == PERCENT) {
                    sz[c] = Math.clamp(value[a][c] * inner, mn[c], mx[c]);
                } else if (md[c] == GROW && (!main || floating)) {
                    sz[c] = Math.clamp(Math.max(inner, fl[c]), mn[c], mx[c]);
                }
                if (floating) {
                    continue;
                }
                if (main) {
                    sum += sz[c];
                    n++;
                } else {
                    if (sz[c] > inner && !scrollable && md[c] != FIXED) {
                        sz[c] = Math.max(inner, fl[c]);
                    }
                    big = Math.max(big, sz[c]);
                }
            }
            if (main) {
                float gaps = n > 1 ? gap[e] * (n - 1) : 0;
                float remaining = inner - sum - gaps;
                if (remaining > EPS) {
                    distribute(e, a, remaining);
                } else if (remaining < -EPS && !scrollable) {
                    shrink(e, a, -remaining);
                }
                sum = 0;
                for (int c = firstChild[e]; c >= 0; c = nextSibling[c]) {
                    if ((flags[c] & F_FLOATING) == 0) {
                        sum += sz[c];
                    }
                }
                ct[e] = sum + gaps + p0[e] + p1[e];
            } else {
                ct[e] = big + p0[e] + p1[e];
            }
        }
    }

    /**
     * Gives {@code remaining} pixels to the {@code GROW} children of {@code e}, smallest
     * size-per-weight first so equal-weight children end up equal, each capped by its maximum.
     */
    private void distribute(int e, int a, float remaining) {
        byte[] md = mode[a];
        float[] sz = size[a];
        float[] mx = max[a];
        float[] wt = value[a];
        for (int guard = 0; guard < 1024 && remaining > EPS; guard++) {
            float r0 = INF;
            float r1 = INF;
            float w0 = 0;
            for (int c = firstChild[e]; c >= 0; c = nextSibling[c]) {
                if ((flags[c] & F_FLOATING) != 0 || md[c] != GROW || sz[c] >= mx[c] - EPS) {
                    continue;
                }
                float w = weight(wt[c]);
                float r = sz[c] / w;
                if (r < r0 - EPS) {
                    r1 = r0;
                    r0 = r;
                    w0 = w;
                } else if (r <= r0 + EPS) {
                    w0 += w;
                } else if (r < r1) {
                    r1 = r;
                }
            }
            if (w0 == 0) {
                return;
            }
            float target;
            if (r1 == INF || (r1 - r0) * w0 > remaining) {
                target = r0 + remaining / w0;
            } else {
                target = r1;
            }
            for (int c = firstChild[e]; c >= 0; c = nextSibling[c]) {
                if ((flags[c] & F_FLOATING) != 0 || md[c] != GROW || sz[c] >= mx[c] - EPS) {
                    continue;
                }
                float w = weight(wt[c]);
                if (sz[c] / w <= r0 + EPS) {
                    float ns = Math.min(target * w, mx[c]);
                    remaining -= ns - sz[c];
                    sz[c] = ns;
                }
            }
        }
    }

    private static float weight(float w) {
        return w > 0 ? w : 1;
    }

    /**
     * Takes {@code overflow} pixels from the children of {@code e} that can shrink (not fixed,
     * above their floor), largest first, so the biggest give first and children equalize.
     */
    private void shrink(int e, int a, float overflow) {
        byte[] md = mode[a];
        float[] sz = size[a];
        float[] fl = floor[a];
        for (int guard = 0; guard < 1024 && overflow > EPS; guard++) {
            float s0 = -INF;
            float s1 = -INF;
            int n0 = 0;
            for (int c = firstChild[e]; c >= 0; c = nextSibling[c]) {
                if ((flags[c] & F_FLOATING) != 0 || md[c] == FIXED || sz[c] <= fl[c] + EPS) {
                    continue;
                }
                float s = sz[c];
                if (s > s0 + EPS) {
                    s1 = s0;
                    s0 = s;
                    n0 = 1;
                } else if (s >= s0 - EPS) {
                    n0++;
                } else if (s > s1) {
                    s1 = s;
                }
            }
            if (n0 == 0) {
                return;
            }
            float target = s0 - overflow / n0;
            if (s1 > target) {
                target = s1;
            }
            for (int c = firstChild[e]; c >= 0; c = nextSibling[c]) {
                if ((flags[c] & F_FLOATING) != 0 || md[c] == FIXED || sz[c] <= fl[c] + EPS) {
                    continue;
                }
                if (sz[c] >= s0 - EPS) {
                    float ns = Math.max(target, fl[c]);
                    overflow -= sz[c] - ns;
                    sz[c] = ns;
                }
            }
        }
    }

    /** Measures a text element: per-char glyphs and advances, longest line and longest word. */
    private void measureText(int e) {
        FontData fd = fontData[font[e]];
        float s = fontSize[e] / fd.emSize();
        boolean kern = fd.kerningCount() > 0 && (flags[e] & F_NO_KERNING) == 0;
        int from = textStart[e];
        int to = from + textLength[e];
        float lineW = 0;
        float maxW = 0;
        float word = 0;
        float maxWord = 0;
        int prevCp = -1;
        int prevIndex = -1;
        for (int i = from; i < to; i++) {
            char c = chars[i];
            int cp = c;
            boolean pair = false;
            if (Character.isHighSurrogate(c)
                    && i + 1 < to
                    && Character.isLowSurrogate(chars[i + 1])) {
                cp = Character.toCodePoint(c, chars[i + 1]);
                pair = true;
            }
            if (cp == '\n') {
                glyphs[i] = -1;
                advances[i] = 0;
                maxW = Math.max(maxW, lineW);
                maxWord = Math.max(maxWord, word);
                lineW = 0;
                word = 0;
                prevCp = -1;
            } else {
                boolean space = cp == ' ' || cp == '\t';
                int g = fd.glyphOrFallback(space ? ' ' : cp);
                float adv = fd.advance(g) * s;
                if (kern && prevCp >= 0 && !space) {
                    float k = fd.kerning(prevCp, cp) * s;
                    if (k != 0) {
                        advances[prevIndex] += k;
                        lineW += k;
                        word += k;
                    }
                }
                glyphs[i] = g;
                advances[i] = adv;
                lineW += adv;
                if (space) {
                    maxWord = Math.max(maxWord, word);
                    word = 0;
                    prevCp = -1;
                } else {
                    word += adv;
                    prevCp = cp;
                    prevIndex = i;
                }
            }
            if (pair) {
                i++;
                glyphs[i] = -2;
                advances[i] = 0;
            }
        }
        maxW = Math.max(maxW, lineW);
        maxWord = Math.max(maxWord, word);
        naturalWidth[e] = maxW;
        longestWord[e] = maxWord;
    }

    /** Pass 3: break text into lines within the final widths. */
    private void wrapPass() {
        lines = 0;
        for (int e = 0; e < count; e++) {
            if ((flags[e] & F_TEXT) == 0) {
                continue;
            }
            boolean wrap = (flags[e] & F_WRAP) != 0;
            float maxWidth = size[0][e];
            int from = textStart[e];
            int to = from + textLength[e];
            lineStart[e] = lines;
            int i = from;
            while (i < to) {
                float w = 0;
                int lastBreak = -1;
                float widthAtBreak = 0;
                int j = i;
                boolean broke = false;
                while (j < to) {
                    int g = glyphs[j];
                    if (g == -1) {
                        addLine(i, j, w);
                        i = j + 1;
                        broke = true;
                        break;
                    }
                    if (g == -2) {
                        j++;
                        continue;
                    }
                    char c = chars[j];
                    if (c == ' ' || c == '\t') {
                        lastBreak = j;
                        widthAtBreak = w;
                    }
                    float adv = advances[j];
                    if (wrap && w + adv > maxWidth + EPS && j > i) {
                        if (lastBreak >= 0) {
                            addLine(i, lastBreak, widthAtBreak);
                            i = lastBreak + 1;
                        } else {
                            addLine(i, j, w);
                            i = j;
                        }
                        broke = true;
                        break;
                    }
                    w += adv;
                    j++;
                }
                if (!broke) {
                    addLine(i, to, w);
                    i = to;
                }
            }
            if (lines == lineStart[e] || (to > from && glyphs[to - 1] == -1)) {
                addLine(to, to, 0); // an empty text, or one ending in a newline, still has a line
            }
            lineCount[e] = lines - lineStart[e];
        }
    }

    private void addLine(int from, int to, float w) {
        if (lines == lineFrom.length) {
            lineFrom = Arrays.copyOf(lineFrom, lines * 2);
            lineTo = Arrays.copyOf(lineTo, lines * 2);
            lineWidth = Arrays.copyOf(lineWidth, lines * 2);
        }
        lineFrom[lines] = from;
        lineTo[lines] = to;
        lineWidth[lines] = w;
        lines++;
    }

    /** Pass 6: positions, parents before children; floating children attach to their target. */
    private void positionPass() {
        pos[0][0] = 0;
        pos[1][0] = 0;
        for (int e = 0; e < count; e++) {
            if (firstChild[e] < 0) {
                continue;
            }
            for (int a = 0; a < 2; a++) {
                float[] ps = pos[a];
                float[] sz = size[a];
                float inner = sz[e] - pad0[a][e] - pad1[a][e];
                float scroll = 0;
                int slot = stateSlot[e];
                if (slot >= 0 && (flags[e] & (a == 0 ? F_SCROLL_X : F_SCROLL_Y)) != 0) {
                    float maxScroll = Math.max(0, content[a][e] - sz[e]);
                    scroll = Math.clamp(stateF[slot * 4 + a], 0, maxScroll);
                    stateF[slot * 4 + a] = scroll;
                }
                float start = ps[e] + pad0[a][e] - scroll;
                if (direction[e] == a) {
                    float extent = content[a][e] - pad0[a][e] - pad1[a][e];
                    float cursor = start + alignOffset(align[a][e], inner - extent);
                    for (int c = firstChild[e]; c >= 0; c = nextSibling[c]) {
                        if ((flags[c] & F_FLOATING) != 0) {
                            continue;
                        }
                        ps[c] = cursor;
                        cursor += sz[c] + gap[e];
                    }
                } else {
                    for (int c = firstChild[e]; c >= 0; c = nextSibling[c]) {
                        if ((flags[c] & F_FLOATING) != 0) {
                            continue;
                        }
                        ps[c] = start + alignOffset(align[a][e], inner - sz[c]);
                    }
                }
            }
            for (int c = firstChild[e]; c >= 0; c = nextSibling[c]) {
                if ((flags[c] & F_FLOATING) != 0) {
                    placeFloating(c, e);
                }
            }
        }
    }

    private static float alignOffset(int alignment, float free) {
        if (free <= 0) {
            return 0;
        }
        return alignment == CENTER ? free * 0.5f : alignment == END ? free : 0;
    }

    private void placeFloating(int c, int p) {
        float tx;
        float ty;
        float tw;
        float th;
        int target = floatTarget[c];
        if (target == TARGET_SCREEN) {
            tx = 0;
            ty = 0;
            tw = width;
            th = height;
        } else if (target == TARGET_ID) {
            int r = recIndex.get(floatId[c]);
            if (r >= 0) {
                tx = recRect[r * 8];
                ty = recRect[r * 8 + 1];
                tw = recRect[r * 8 + 2];
                th = recRect[r * 8 + 3];
            } else {
                tx = 0;
                ty = 0;
                tw = width;
                th = height;
            }
        } else {
            tx = pos[0][p];
            ty = pos[1][p];
            tw = size[0][p];
            th = size[1][p];
        }
        int an = anchor[c];
        float x =
                tx
                        + anchorFraction(an & 3) * tw
                        - anchorFraction(an >> 4 & 3) * size[0][c]
                        + offsetX[c];
        float y =
                ty
                        + anchorFraction(an >> 2 & 3) * th
                        - anchorFraction(an >> 6 & 3) * size[1][c]
                        + offsetY[c];
        if ((flags[c] & F_CLAMP_SCREEN) != 0) {
            x = Math.clamp(x, 0, Math.max(0, width - size[0][c]));
            y = Math.clamp(y, 0, Math.max(0, height - size[1][c]));
        }
        pos[0][c] = x;
        pos[1][c] = y;
    }

    private static float anchorFraction(int a) {
        return a == CENTER ? 0.5f : a == END ? 1 : 0;
    }

    // ---- Emission ---------------------------------------------------------------------------

    private void emit(UiDrawList out) {
        out.reset();
        nextRecCount = 0;
        nextRecIndex.clear();
        floatCount = 0;
        emitSubtree(out, 0, NONE);
        // Floating subtrees after everything else, lowest z first (declaration order on ties).
        for (int i = 1; i < floatCount; i++) {
            int v = floats[i];
            int j = i - 1;
            while (j >= 0 && z[floats[j]] > z[v]) {
                floats[j + 1] = floats[j];
                j--;
            }
            floats[j + 1] = v;
        }
        for (int i = 0; i < floatCount; i++) {
            int e = floats[i];
            emitSubtree(out, e, ids[e] != NONE ? ids[e] : combine(0xF10A7L, e));
        }
        // Swap the record sets: what was emitted becomes what the next frame hit-tests.
        long[] tl = recId;
        recId = nextRecId;
        nextRecId = tl;
        tl = recFloat;
        recFloat = nextRecFloat;
        nextRecFloat = tl;
        float[] tf = recRect;
        recRect = nextRecRect;
        nextRecRect = tf;
        int[] ti = recFlags;
        recFlags = nextRecFlags;
        nextRecFlags = ti;
        ti = recI0;
        recI0 = nextRecI0;
        nextRecI0 = ti;
        ti = recI1;
        recI1 = nextRecI1;
        nextRecI1 = ti;
        tf = recF;
        recF = nextRecF;
        nextRecF = tf;
        recCount = nextRecCount;
        LongIntMap tm = recIndex;
        recIndex = nextRecIndex;
        nextRecIndex = tm;
    }

    /** Emits one subtree (the main tree, or a floating element and its descendants). */
    private void emitSubtree(UiDrawList out, int root, long floatRoot) {
        int sp = 0;
        stackEl[0] = root;
        stackChild[0] = -2;
        if ((flags[root] & F_FLOAT_CLIP) != 0) {
            stackClip[0] = floatClip[root];
            stackRect[0] = floatClipRect[root * 4];
            stackRect[1] = floatClipRect[root * 4 + 1];
            stackRect[2] = floatClipRect[root * 4 + 2];
            stackRect[3] = floatClipRect[root * 4 + 3];
        } else {
            stackClip[0] = 0;
            stackRect[0] = 0;
            stackRect[1] = 0;
            stackRect[2] = width;
            stackRect[3] = height;
        }
        while (sp >= 0) {
            int e = stackEl[sp];
            if (stackChild[sp] == -2) {
                // First visit: background, border, sprite, text; then push a clip for children.
                int clip = stackClip[sp];
                float x = pos[0][e];
                float y = pos[1][e];
                float w = size[0][e];
                float h = size[1][e];
                if (background[e] != 0 && sprite[e] == null) {
                    out.rect(x, y, w, h, radius[e], 0, background[e], clip);
                }
                if (sprite[e] != null) {
                    drawSprite(out, e, x, y, w, h, clip);
                }
                if (borderWidth[e] > 0 && borderColor[e] != 0) {
                    out.rect(x, y, w, h, radius[e], borderWidth[e], borderColor[e], clip);
                }
                if ((flags[e] & F_TEXT) != 0) {
                    drawText(out, e, x, y, w, h, clip);
                }
                int f = flags[e] & ~F_SCROLLBAR;
                if ((f & F_NO_HIT) == 0 && (background[e] != 0 || sprite[e] != null)) {
                    f |= F_HIT; // anything drawn blocks the pointer unless told otherwise
                }
                if (ids[e] != NONE || (f & F_HIT) != 0) {
                    record(
                            ids[e],
                            floatRoot,
                            x,
                            y,
                            w,
                            h,
                            stackRect[sp * 4],
                            stackRect[sp * 4 + 1],
                            stackRect[sp * 4 + 2],
                            stackRect[sp * 4 + 3],
                            f,
                            dragKind[e],
                            dragValue[e],
                            content[0][e] - w,
                            content[1][e] - h,
                            0,
                            0);
                }
                stackChild[sp] = firstChild[e];
                if ((flags[e] & F_CLIP) != 0 && firstChild[e] >= 0) {
                    // Children are clipped to the intersection with the enclosing clip.
                    float cx = Math.max(x, stackRect[sp * 4]);
                    float cy = Math.max(y, stackRect[sp * 4 + 1]);
                    float cr = Math.min(x + w, stackRect[sp * 4] + stackRect[sp * 4 + 2]);
                    float cb = Math.min(y + h, stackRect[sp * 4 + 1] + stackRect[sp * 4 + 3]);
                    float cw = Math.max(0, cr - cx);
                    float ch = Math.max(0, cb - cy);
                    int ci = out.addClip(cx, cy, cw, ch);
                    stackClip[sp] = ci << 16 | clip; // own clip high, enclosing clip low
                    stackRect[sp * 4] = cx;
                    stackRect[sp * 4 + 1] = cy;
                    stackRect[sp * 4 + 2] = cw;
                    stackRect[sp * 4 + 3] = ch;
                }
            }
            int c = stackChild[sp];
            if (c >= 0) {
                stackChild[sp] = nextSibling[c];
                if ((flags[c] & F_FLOATING) != 0) {
                    if (floatCount == floats.length) {
                        floats = Arrays.copyOf(floats, floatCount * 2);
                    }
                    floats[floatCount++] = c;
                    int pc = stackClip[sp];
                    floatClip[c] = pc >>> 16 != 0 ? pc >>> 16 : pc & 0xFFFF;
                    floatClipRect[c * 4] = stackRect[sp * 4];
                    floatClipRect[c * 4 + 1] = stackRect[sp * 4 + 1];
                    floatClipRect[c * 4 + 2] = stackRect[sp * 4 + 2];
                    floatClipRect[c * 4 + 3] = stackRect[sp * 4 + 3];
                    continue;
                }
                sp++;
                if (sp == stackEl.length) {
                    stackEl = Arrays.copyOf(stackEl, sp * 2);
                    stackChild = Arrays.copyOf(stackChild, sp * 2);
                    stackClip = Arrays.copyOf(stackClip, sp * 2);
                    stackRect = Arrays.copyOf(stackRect, sp * 8);
                }
                stackEl[sp] = c;
                stackChild[sp] = -2;
                int parentClip = stackClip[sp - 1];
                stackClip[sp] = parentClip >>> 16 != 0 ? parentClip >>> 16 : parentClip & 0xFFFF;
                stackRect[sp * 4] = stackRect[(sp - 1) * 4];
                stackRect[sp * 4 + 1] = stackRect[(sp - 1) * 4 + 1];
                stackRect[sp * 4 + 2] = stackRect[(sp - 1) * 4 + 2];
                stackRect[sp * 4 + 3] = stackRect[(sp - 1) * 4 + 3];
            } else {
                // All children done: scrollbars on top, with the enclosing clip.
                if ((flags[e] & (F_SCROLL_X | F_SCROLL_Y)) != 0
                        && (flags[e] & F_NO_BARS) == 0
                        && ids[e] != NONE) {
                    int enclosing = stackClip[sp] & 0xFFFF;
                    int o = sp * 4;
                    float ex = stackRect[o];
                    float ey = stackRect[o + 1];
                    float ew = stackRect[o + 2];
                    float eh = stackRect[o + 3];
                    if (sp > 0) {
                        ex = stackRect[o - 4];
                        ey = stackRect[o - 3];
                        ew = stackRect[o - 2];
                        eh = stackRect[o - 1];
                    }
                    drawScrollbars(out, e, floatRoot, enclosing, ex, ey, ew, eh);
                }
                sp--;
            }
        }
    }

    private void drawScrollbars(
            UiDrawList out,
            int e,
            long floatRoot,
            int clip,
            float cx,
            float cy,
            float cw,
            float ch) {
        float x = pos[0][e];
        float y = pos[1][e];
        float w = size[0][e];
        float h = size[1][e];
        float bw = scrollbarWidth;
        int slot = stateSlot[e];
        boolean vert = (flags[e] & F_SCROLL_Y) != 0 && content[1][e] > h + EPS;
        boolean horz = (flags[e] & F_SCROLL_X) != 0 && content[0][e] > w + EPS;
        if (vert) {
            float trackLen = h - (horz ? bw : 0);
            float maxScroll = content[1][e] - h;
            float thumbLen = Math.max(16, trackLen * h / content[1][e]);
            thumbLen = Math.min(thumbLen, trackLen);
            float scroll = slot >= 0 ? stateF[slot * 4 + 1] : 0;
            float thumbStart = y + (trackLen - thumbLen) * (maxScroll > 0 ? scroll / maxScroll : 0);
            out.rect(x + w - bw, y, bw, trackLen, bw * 0.5f, 0, scrollTrackColor, clip);
            out.rect(
                    x + w - bw + 2,
                    thumbStart,
                    bw - 4,
                    thumbLen,
                    (bw - 4) * 0.5f,
                    0,
                    scrollThumbColor,
                    clip);
            record(
                    ids[e],
                    floatRoot,
                    x + w - bw,
                    y,
                    bw,
                    trackLen,
                    cx,
                    cy,
                    cw,
                    ch,
                    F_SCROLLBAR,
                    1,
                    0,
                    thumbStart,
                    thumbLen,
                    maxScroll,
                    h);
        }
        if (horz) {
            float trackLen = w - (vert ? bw : 0);
            float maxScroll = content[0][e] - w;
            float thumbLen = Math.max(16, trackLen * w / content[0][e]);
            thumbLen = Math.min(thumbLen, trackLen);
            float scroll = slot >= 0 ? stateF[slot * 4] : 0;
            float thumbStart = x + (trackLen - thumbLen) * (maxScroll > 0 ? scroll / maxScroll : 0);
            out.rect(x, y + h - bw, trackLen, bw, bw * 0.5f, 0, scrollTrackColor, clip);
            out.rect(
                    thumbStart,
                    y + h - bw + 2,
                    thumbLen,
                    bw - 4,
                    (bw - 4) * 0.5f,
                    0,
                    scrollThumbColor,
                    clip);
            record(
                    ids[e],
                    floatRoot,
                    x,
                    y + h - bw,
                    trackLen,
                    bw,
                    cx,
                    cy,
                    cw,
                    ch,
                    F_SCROLLBAR,
                    0,
                    0,
                    thumbStart,
                    thumbLen,
                    maxScroll,
                    w);
        }
    }

    private void record(
            long id,
            long floatRoot,
            float x,
            float y,
            float w,
            float h,
            float cx,
            float cy,
            float cw,
            float ch,
            int f,
            int i0,
            int i1,
            float f0,
            float f1,
            float f2,
            float f3) {
        if (nextRecCount == recCapacity) {
            growRecords();
        }
        int r = nextRecCount++;
        nextRecId[r] = id;
        nextRecFloat[r] = floatRoot;
        int o = r * 8;
        nextRecRect[o] = x;
        nextRecRect[o + 1] = y;
        nextRecRect[o + 2] = w;
        nextRecRect[o + 3] = h;
        nextRecRect[o + 4] = cx;
        nextRecRect[o + 5] = cy;
        nextRecRect[o + 6] = cw;
        nextRecRect[o + 7] = ch;
        nextRecFlags[r] = f;
        nextRecI0[r] = i0;
        nextRecI1[r] = i1;
        nextRecF[r * 4] = f0;
        nextRecF[r * 4 + 1] = f1;
        nextRecF[r * 4 + 2] = f2;
        nextRecF[r * 4 + 3] = f3;
        if (id != NONE && (f & F_SCROLLBAR) == 0) {
            nextRecIndex.put(id, r);
        }
    }

    private void drawSprite(UiDrawList out, int e, float x, float y, float w, float h, int clip) {
        Sprite s = sprite[e];
        int color = tint[e];
        if (!s.isNineSlice()) {
            out.image(x, y, w, h, s.u0(), s.v0(), s.u1(), s.v1(), color, s.texture(), clip);
            return;
        }
        // Nine slices: corners keep their size, edges stretch along one axis, the center both.
        float l = Math.min(s.insetLeft(), w * 0.5f);
        float r = Math.min(s.insetRight(), w * 0.5f);
        float t = Math.min(s.insetTop(), h * 0.5f);
        float b = Math.min(s.insetBottom(), h * 0.5f);
        float du = (s.u1() - s.u0()) / s.pixelWidth();
        float dv = (s.v1() - s.v0()) / s.pixelHeight();
        float ul = s.u0() + s.insetLeft() * du;
        float ur = s.u1() - s.insetRight() * du;
        float vt = s.v0() + s.insetTop() * dv;
        float vb = s.v1() - s.insetBottom() * dv;
        float xm = x + l;
        float wm = w - l - r;
        float ym = y + t;
        float hm = h - t - b;
        int tex = s.texture();
        out.image(x, y, l, t, s.u0(), s.v0(), ul, vt, color, tex, clip);
        out.image(xm, y, wm, t, ul, s.v0(), ur, vt, color, tex, clip);
        out.image(x + w - r, y, r, t, ur, s.v0(), s.u1(), vt, color, tex, clip);
        out.image(x, ym, l, hm, s.u0(), vt, ul, vb, color, tex, clip);
        out.image(xm, ym, wm, hm, ul, vt, ur, vb, color, tex, clip);
        out.image(x + w - r, ym, r, hm, ur, vt, s.u1(), vb, color, tex, clip);
        out.image(x, y + h - b, l, b, s.u0(), vb, ul, s.v1(), color, tex, clip);
        out.image(xm, y + h - b, wm, b, ul, vb, ur, s.v1(), color, tex, clip);
        out.image(x + w - r, y + h - b, r, b, ur, vb, s.u1(), s.v1(), color, tex, clip);
    }

    private void drawText(UiDrawList out, int e, float x, float y, float w, float h, int clip) {
        int f = font[e];
        int tex = fontTexture[f];
        if (tex == UiDrawList.NO_TEXTURE || textLength[e] == 0) {
            return;
        }
        FontData fd = fontData[f];
        float s = fontSize[e] / fd.emSize();
        float lh = fd.lineHeight() * s;
        float ascent = fd.ascent() * s;
        float pxRange = 2 * fd.sdfRange() * s;
        int color = textColor[e];
        int n = lineCount[e];
        float y0 = y + alignOffset(align[1][e], h - n * lh);
        float invW = 1f / fd.atlasWidth();
        float invH = 1f / fd.atlasHeight();
        int al = textAlign[e];
        int[] gl = glyphs;
        float[] adv = advances;
        for (int l = 0; l < n; l++) {
            int line = lineStart[e] + l;
            float pen = Math.round(x + alignOffset(al, w - lineWidth[line]));
            float baseline = Math.round(y0 + l * lh + ascent);
            int end = lineTo[line];
            for (int j = lineFrom[line]; j < end; j++) {
                int g = gl[j];
                if (g < 0) {
                    continue;
                }
                int gw = fd.width(g);
                int gh = fd.height(g);
                if (gw > 0 && gh > 0) {
                    float gx = pen + fd.bearingX(g) * s;
                    float gy = baseline - fd.bearingY(g) * s;
                    float u0 = fd.atlasX(g) * invW;
                    float v0 = fd.atlasY(g) * invH;
                    out.glyph(
                            gx,
                            gy,
                            gw * s,
                            gh * s,
                            u0,
                            v0,
                            u0 + gw * invW,
                            v0 + gh * invH,
                            color,
                            tex,
                            pxRange,
                            clip);
                }
                pen += adv[j];
            }
        }
    }

    // ---- Inspection (tests, tools) ----------------------------------------------------------

    /** Returns the number of elements declared this frame (the root included). */
    public int elementCount() {
        return count;
    }

    /** Returns the laid-out x of element {@code e} (valid after {@link #endFrame}). */
    public float x(int e) {
        return pos[0][e];
    }

    /** Returns the laid-out y of element {@code e}. */
    public float y(int e) {
        return pos[1][e];
    }

    /** Returns the laid-out width of element {@code e}. */
    public float w(int e) {
        return size[0][e];
    }

    /** Returns the laid-out height of element {@code e}. */
    public float h(int e) {
        return size[1][e];
    }

    /** Returns the content width of element {@code e} (children, gaps and padding). */
    public float contentWidth(int e) {
        return content[0][e];
    }

    /** Returns the content height of element {@code e}. */
    public float contentHeight(int e) {
        return content[1][e];
    }

    /** Returns the number of wrapped lines of text element {@code e}. */
    public int lineCount(int e) {
        return lineCount[e];
    }

    /** Returns the id of element {@code e}. */
    public long elementId(int e) {
        return ids[e];
    }

    /** Returns the number of characters appended this frame. */
    public int charCount() {
        return charCount;
    }

    /** Copies the laid-out rectangle of element {@code e} into {@code out} (x, y, w, h). */
    public void rect(int e, float[] out) {
        out[0] = pos[0][e];
        out[1] = pos[1][e];
        out[2] = size[0][e];
        out[3] = size[1][e];
    }

    /** Returns a scratch 4-float array (not thread-safe; for {@link #rect}). */
    public float[] scratchRect() {
        return rectOut;
    }
}
