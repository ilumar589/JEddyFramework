package dev.jeddy.ui;

/**
 * A rectangle of a UI texture (TASKS M13-04): the texture reference the renderer understands, the
 * rectangle in normalized coordinates, its size in pixels, and optional nine-slice insets (the
 * border widths, in pixels of the source, that keep their size when the sprite stretches). Create
 * sprites once when a skin loads; boxes refer to them by reference.
 *
 * @param texture the texture reference (opaque to {@code ui}; see {@link UiDrawList})
 * @param u0 left edge in normalized texture coordinates
 * @param v0 top edge
 * @param u1 right edge
 * @param v1 bottom edge
 * @param pixelWidth width of the rectangle in texels
 * @param pixelHeight height of the rectangle in texels
 * @param insetLeft nine-slice left border in texels (0 for a plain sprite)
 * @param insetTop nine-slice top border
 * @param insetRight nine-slice right border
 * @param insetBottom nine-slice bottom border
 */
public record Sprite(
        int texture,
        float u0,
        float v0,
        float u1,
        float v1,
        int pixelWidth,
        int pixelHeight,
        float insetLeft,
        float insetTop,
        float insetRight,
        float insetBottom) {

    /** A whole {@code width × height} texture as a plain sprite. */
    public static Sprite whole(int texture, int width, int height) {
        return new Sprite(texture, 0, 0, 1, 1, width, height, 0, 0, 0, 0);
    }

    /** A sub-rectangle {@code [x, x + w) × [y, y + h)} of a {@code width × height} texture. */
    public static Sprite region(int texture, int width, int height, int x, int y, int w, int h) {
        return new Sprite(
                texture,
                (float) x / width,
                (float) y / height,
                (float) (x + w) / width,
                (float) (y + h) / height,
                w,
                h,
                0,
                0,
                0,
                0);
    }

    /** Returns this sprite with nine-slice insets. */
    public Sprite nineSlice(float left, float top, float right, float bottom) {
        return new Sprite(
                texture, u0, v0, u1, v1, pixelWidth, pixelHeight, left, top, right, bottom);
    }

    /** Returns this sprite with the same inset on every side. */
    public Sprite nineSlice(float inset) {
        return nineSlice(inset, inset, inset, inset);
    }

    /** Returns whether the sprite is nine-sliced. */
    public boolean isNineSlice() {
        return insetLeft > 0 || insetTop > 0 || insetRight > 0 || insetBottom > 0;
    }
}
