package dev.jeddy.ui;

/**
 * The builder for the element {@link Ui#box} or {@link Ui#text} began last (TASKS M13-03). There is
 * one per context, reused for every element, so declaring allocates nothing; every setter returns
 * it for chaining and writes straight into the context's SoA arrays. A box becomes a parent with
 * {@link #open()}; the text methods apply to elements begun with {@code text()}.
 *
 * <p>Sizes default to {@link Ui#FIT} on both axes, direction to {@link Ui#ROW}, no padding, no gap,
 * children aligned at the start, no background. A box with a background or sprite blocks the
 * pointer (see {@link #noHit()}); text never does.
 *
 * <p>Thread ownership: the thread that drives the {@link Ui}.
 */
public final class Box {
    private final Ui ui;
    int cur;

    Box(Ui ui) {
        this.ui = ui;
    }

    /** Returns the element's index this frame (for {@link Ui#x(int)} and friends after layout). */
    public int index() {
        return cur;
    }

    // ---- Sizing -----------------------------------------------------------------------------

    /** Fixed width in pixels. */
    public Box width(float px) {
        ui.mode[0][cur] = (byte) Ui.FIXED;
        ui.value[0][cur] = px;
        return this;
    }

    /** Fixed height in pixels. */
    public Box height(float px) {
        ui.mode[1][cur] = (byte) Ui.FIXED;
        ui.value[1][cur] = px;
        return this;
    }

    /** Fixed width and height. */
    public Box size(float w, float h) {
        return width(w).height(h);
    }

    /** Width fits the content (the default). */
    public Box widthFit() {
        ui.mode[0][cur] = (byte) Ui.FIT;
        return this;
    }

    /** Width fits the content between {@code min} and {@code max}. */
    public Box widthFit(float min, float max) {
        ui.mode[0][cur] = (byte) Ui.FIT;
        ui.min[0][cur] = min;
        ui.max[0][cur] = max;
        return this;
    }

    /** Height fits the content (the default). */
    public Box heightFit() {
        ui.mode[1][cur] = (byte) Ui.FIT;
        return this;
    }

    /** Height fits the content between {@code min} and {@code max}. */
    public Box heightFit(float min, float max) {
        ui.mode[1][cur] = (byte) Ui.FIT;
        ui.min[1][cur] = min;
        ui.max[1][cur] = max;
        return this;
    }

    /** Width takes a share of the parent's free space (weight 1). */
    public Box widthGrow() {
        return widthGrow(1);
    }

    /** Width takes a share of the parent's free space proportional to {@code weight}. */
    public Box widthGrow(float weight) {
        ui.mode[0][cur] = (byte) Ui.GROW;
        ui.value[0][cur] = weight;
        return this;
    }

    /** Height takes a share of the parent's free space (weight 1). */
    public Box heightGrow() {
        return heightGrow(1);
    }

    /** Height takes a share of the parent's free space proportional to {@code weight}. */
    public Box heightGrow(float weight) {
        ui.mode[1][cur] = (byte) Ui.GROW;
        ui.value[1][cur] = weight;
        return this;
    }

    /** Both axes grow. */
    public Box grow() {
        return widthGrow(1).heightGrow(1);
    }

    /** Width is {@code fraction} (0..1) of the parent's inner width. */
    public Box widthPercent(float fraction) {
        ui.mode[0][cur] = (byte) Ui.PERCENT;
        ui.value[0][cur] = fraction;
        return this;
    }

    /** Height is {@code fraction} (0..1) of the parent's inner height. */
    public Box heightPercent(float fraction) {
        ui.mode[1][cur] = (byte) Ui.PERCENT;
        ui.value[1][cur] = fraction;
        return this;
    }

    /** Minimum width (for fit and grow). */
    public Box minWidth(float px) {
        ui.min[0][cur] = px;
        return this;
    }

    /** Maximum width (for fit and grow). */
    public Box maxWidth(float px) {
        ui.max[0][cur] = px;
        return this;
    }

    /** Minimum height (for fit and grow). */
    public Box minHeight(float px) {
        ui.min[1][cur] = px;
        return this;
    }

    /** Maximum height (for fit and grow). */
    public Box maxHeight(float px) {
        ui.max[1][cur] = px;
        return this;
    }

    // ---- Layout of children -----------------------------------------------------------------

    /** Children side by side (the default). */
    public Box row() {
        ui.direction[cur] = (byte) Ui.ROW;
        return this;
    }

    /** Children stacked. */
    public Box column() {
        ui.direction[cur] = (byte) Ui.COLUMN;
        return this;
    }

    /** Same padding on every side. */
    public Box padding(float all) {
        return padding(all, all, all, all);
    }

    /** Horizontal and vertical padding. */
    public Box padding(float x, float y) {
        return padding(x, y, x, y);
    }

    /** Padding per side. */
    public Box padding(float left, float top, float right, float bottom) {
        ui.pad0[0][cur] = left;
        ui.pad1[0][cur] = right;
        ui.pad0[1][cur] = top;
        ui.pad1[1][cur] = bottom;
        return this;
    }

    /** Space between children. */
    public Box gap(float px) {
        ui.gap[cur] = px;
        return this;
    }

    /** Alignment of the children when they do not fill the box ({@link Ui#START} and friends). */
    public Box align(int x, int y) {
        ui.align[0][cur] = (byte) x;
        ui.align[1][cur] = (byte) y;
        return this;
    }

    /** Children centered on both axes. */
    public Box center() {
        return align(Ui.CENTER, Ui.CENTER);
    }

    // ---- Appearance -------------------------------------------------------------------------

    /** Background color ({@code 0xAARRGGBB}; 0 for none). */
    public Box background(int color) {
        ui.background[cur] = color;
        return this;
    }

    /** A border {@code width} pixels wide inside the box. */
    public Box border(int color, float width) {
        ui.borderColor[cur] = color;
        ui.borderWidth[cur] = width;
        return this;
    }

    /** Corner radius for the background and border. */
    public Box radius(float px) {
        ui.radius[cur] = px;
        return this;
    }

    /** Draws {@code sprite} stretched (or nine-sliced) over the box instead of a background. */
    public Box sprite(Sprite sprite) {
        return sprite(sprite, 0xFFFFFFFF);
    }

    /** Draws {@code sprite} tinted by {@code color}. */
    public Box sprite(Sprite sprite, int color) {
        ui.sprite[cur] = sprite;
        ui.tint[cur] = color;
        return this;
    }

    /** Sizes the box to the sprite's pixels and draws it. */
    public Box image(Sprite sprite) {
        return sprite(sprite).size(sprite.pixelWidth(), sprite.pixelHeight());
    }

    /** Clips the children to the box. */
    public Box clip() {
        ui.flags[cur] |= Ui.F_CLIP;
        return this;
    }

    /**
     * Makes the box scroll its content along the given axes (implies {@link #clip()}). The box
     * needs an id to keep its offset; the wheel, the scrollbars and {@link Ui#setScroll} move it.
     */
    public Box scroll(boolean x, boolean y) {
        ui.flags[cur] |= Ui.F_CLIP | (x ? Ui.F_SCROLL_X : 0) | (y ? Ui.F_SCROLL_Y : 0);
        return this;
    }

    /** A scroll box without scrollbars (text fields scroll their one line this way). */
    public Box hideScrollbars() {
        ui.flags[cur] |= Ui.F_NO_BARS;
        return this;
    }

    // ---- Floating ---------------------------------------------------------------------------

    /**
     * Takes the box out of the flow: it is sized on its own, attached to its parent's rectangle at
     * {@link #anchor} plus {@link #offset}, drawn after the main tree in {@link #z} order and kept
     * on screen. Popups, tooltips and drag previews are floating.
     */
    public Box floating() {
        ui.flags[cur] |= Ui.F_FLOATING | Ui.F_CLAMP_SCREEN;
        ui.floatTarget[cur] = Ui.TARGET_PARENT;
        return this;
    }

    /** Floating, attached to the screen. */
    public Box floatingOnScreen() {
        floating();
        ui.floatTarget[cur] = Ui.TARGET_SCREEN;
        return this;
    }

    /** Floating, attached to the previous frame's rectangle of element {@code id}. */
    public Box floatingOn(long id) {
        floating();
        ui.floatTarget[cur] = Ui.TARGET_ID;
        ui.floatId[cur] = ui.resolve(id);
        return this;
    }

    /** Floating at screen position {@code (x, y)} (its top left corner). */
    public Box floatingAt(float x, float y) {
        return floatingOnScreen().anchor(Ui.TOP_LEFT, Ui.TOP_LEFT).offset(x, y);
    }

    /** Which point of the target meets which point of this box ({@link Ui#TOP_LEFT} ...). */
    public Box anchor(int target, int own) {
        ui.anchor[cur] = target & 0xF | (own & 0xF) << 4;
        return this;
    }

    /** Offset from the anchor point. */
    public Box offset(float x, float y) {
        ui.offsetX[cur] = x;
        ui.offsetY[cur] = y;
        return this;
    }

    /** Draw order among floating elements (higher is on top). */
    public Box z(int z) {
        ui.z[cur] = z;
        return this;
    }

    /** Lets a floating box leave the screen. */
    public Box unclamped() {
        ui.flags[cur] &= ~Ui.F_CLAMP_SCREEN;
        return this;
    }

    /**
     * Clips a floating box like its non-floating siblings (by the enclosing clip boxes) instead of
     * letting it escape them as popups do. Carets and selection highlights use this.
     */
    public Box clippedByParent() {
        ui.flags[cur] |= Ui.F_FLOAT_CLIP;
        return this;
    }

    // ---- Interaction ------------------------------------------------------------------------

    /** The box blocks the pointer and reports hover, press and click (widgets). */
    public Box hit() {
        ui.flags[cur] |= Ui.F_HIT;
        return this;
    }

    /** The box lets the pointer through even though it draws something. */
    public Box noHit() {
        ui.flags[cur] |= Ui.F_NO_HIT;
        ui.flags[cur] &= ~Ui.F_HIT;
        return this;
    }

    /** The box takes keyboard focus when pressed or reached with Tab. */
    public Box focusable() {
        ui.flags[cur] |= Ui.F_HIT | Ui.F_FOCUSABLE;
        return this;
    }

    /** The box handles arrow keys itself while focused (text fields, sliders). */
    public Box captureKeys() {
        ui.flags[cur] |= Ui.F_CAPTURE_KEYS;
        return this;
    }

    /** Enter or Space while focused counts as a click (buttons). */
    public Box activateWithKeys() {
        ui.flags[cur] |= Ui.F_ACTIVATE;
        return this;
    }

    /** The box blocks the pointer but does not react to it. */
    public Box disabled() {
        ui.flags[cur] |= Ui.F_HIT | Ui.F_DISABLED;
        return this;
    }

    /** Pressing and moving the pointer starts a drag carrying {@code (kind, value)}. */
    public Box dragSource(int kind, int value) {
        ui.flags[cur] |= Ui.F_HIT | Ui.F_DRAG_SOURCE;
        ui.dragKind[cur] = kind;
        ui.dragValue[cur] = value;
        return this;
    }

    /** A drag released over the box is reported by {@link Ui#dropped}. */
    public Box dropTarget() {
        ui.flags[cur] |= Ui.F_HIT | Ui.F_DROP_TARGET;
        return this;
    }

    // ---- Text -------------------------------------------------------------------------------

    /** Appends text to a text element. */
    public Box append(CharSequence s) {
        ui.appendChars(cur, s, 0, s.length());
        return this;
    }

    /** Appends {@code s[from, to)}. */
    public Box append(CharSequence s, int from, int to) {
        ui.appendChars(cur, s, from, to);
        return this;
    }

    /** Appends {@code a[from, to)} from a char array. */
    public Box append(char[] a, int from, int to) {
        ui.appendChars(cur, a, from, to);
        return this;
    }

    /** Appends a char. */
    public Box append(char c) {
        ui.appendChar(cur, c);
        return this;
    }

    /** Appends an integer in decimal, without allocating. */
    public Box append(int v) {
        return append((long) v);
    }

    /** Appends a long in decimal, without allocating. */
    public Box append(long v) {
        if (v < 0) {
            if (v == Long.MIN_VALUE) {
                return append("-9223372036854775808");
            }
            ui.appendChar(cur, '-');
            v = -v;
        }
        long p = 1;
        while (v / p >= 10) {
            p *= 10;
        }
        while (p > 0) {
            ui.appendChar(cur, (char) ('0' + v / p % 10));
            p /= 10;
        }
        return this;
    }

    /** Appends a float rounded to {@code decimals} (0..6) places, without allocating. */
    public Box append(float v, int decimals) {
        if (Float.isNaN(v)) {
            return append("NaN");
        }
        if (Float.isInfinite(v)) {
            return append(v > 0 ? "Inf" : "-Inf");
        }
        if (v < 0 || (v == 0 && 1 / v < 0)) {
            ui.appendChar(cur, '-');
            v = -v;
        }
        long scale = 1;
        for (int i = 0; i < decimals; i++) {
            scale *= 10;
        }
        long r = Math.round((double) v * scale);
        append(r / scale);
        if (decimals > 0) {
            ui.appendChar(cur, '.');
            long frac = r % scale;
            for (long p = scale / 10; p > 0; p /= 10) {
                ui.appendChar(cur, (char) ('0' + frac / p % 10));
            }
        }
        return this;
    }

    /** Font index (see {@link Ui#addFont}). */
    public Box font(int font) {
        ui.font[cur] = font;
        return this;
    }

    /** Font size in pixels. */
    public Box fontSize(float px) {
        ui.fontSize[cur] = px;
        return this;
    }

    /** Text color. */
    public Box color(int color) {
        ui.textColor[cur] = color;
        return this;
    }

    /** Wraps at spaces (and mid-word when a word is wider than the element). */
    public Box wrap() {
        ui.flags[cur] |= Ui.F_WRAP;
        return this;
    }

    /** Horizontal alignment of the lines inside the element. */
    public Box textAlign(int align) {
        ui.textAlign[cur] = (byte) align;
        return this;
    }

    /** Turns kerning off for this text. */
    public Box noKerning() {
        ui.flags[cur] |= Ui.F_NO_KERNING;
        return this;
    }

    // ---- Tree -------------------------------------------------------------------------------

    /** Makes the box the parent of the elements declared until {@link Ui#close()}. */
    public Box open() {
        ui.openElement(cur);
        return this;
    }
}
