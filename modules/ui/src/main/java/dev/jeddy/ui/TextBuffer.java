package dev.jeddy.ui;

import java.util.Arrays;

/**
 * Editable text for text fields (TASKS M13-04): UTF-16 chars with a caret and a selection, owned by
 * the game and edited by {@link Ui#textField}. It is also a {@link CharSequence}, so it can be
 * drawn directly. Editing allocates nothing until the text outgrows the buffer.
 *
 * <p>Thread ownership: the thread that drives the {@link Ui}.
 */
public final class TextBuffer implements CharSequence {
    private char[] chars;
    private int length;
    private int caret;
    private int anchor; // selection anchor; == caret when nothing is selected
    private int version;

    /** Creates an empty buffer with room for {@code capacity} chars. */
    public TextBuffer(int capacity) {
        chars = new char[Math.max(16, capacity)];
    }

    /** Creates a buffer holding {@code initial}. */
    public TextBuffer(CharSequence initial) {
        this(initial.length() + 32);
        set(initial);
    }

    /** Replaces the text; the caret goes to the end. */
    public void set(CharSequence s) {
        int n = s.length();
        ensure(n);
        for (int i = 0; i < n; i++) {
            chars[i] = s.charAt(i);
        }
        length = n;
        caret = n;
        anchor = n;
        version++;
    }

    /** Empties the buffer. */
    public void clear() {
        length = 0;
        caret = 0;
        anchor = 0;
        version++;
    }

    @Override
    public int length() {
        return length;
    }

    @Override
    public char charAt(int index) {
        if (index < 0 || index >= length) {
            throw new IndexOutOfBoundsException(index);
        }
        return chars[index];
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return new String(chars, start, end - start);
    }

    /** Returns the text as a new string (cold path). */
    @Override
    public String toString() {
        return new String(chars, 0, length);
    }

    /** Returns the backing array (valid up to {@link #length()}). */
    public char[] array() {
        return chars;
    }

    /** Returns the caret index (0..length). */
    public int caret() {
        return caret;
    }

    /** Returns the selection start (the lower of caret and anchor). */
    public int selectionStart() {
        return Math.min(caret, anchor);
    }

    /** Returns the selection end (the higher of caret and anchor). */
    public int selectionEnd() {
        return Math.max(caret, anchor);
    }

    /** Returns whether a range is selected. */
    public boolean hasSelection() {
        return caret != anchor;
    }

    /** Returns a counter that changes with every edit. */
    public int version() {
        return version;
    }

    /** Moves the caret, extending the selection if {@code select}, else collapsing it. */
    public void moveTo(int index, boolean select) {
        caret = Math.clamp(index, 0, length);
        if (!select) {
            anchor = caret;
        }
    }

    /** Moves the caret by {@code delta} chars (surrogate pairs count as one). */
    public void move(int delta, boolean select) {
        int i = caret;
        if (delta < 0) {
            for (int k = 0; k < -delta && i > 0; k++) {
                i--;
                if (i > 0
                        && Character.isLowSurrogate(chars[i])
                        && Character.isHighSurrogate(chars[i - 1])) {
                    i--;
                }
            }
        } else {
            for (int k = 0; k < delta && i < length; k++) {
                i++;
                if (i < length
                        && Character.isLowSurrogate(chars[i])
                        && Character.isHighSurrogate(chars[i - 1])) {
                    i++;
                }
            }
        }
        moveTo(i, select);
    }

    /** Selects everything. */
    public void selectAll() {
        anchor = 0;
        caret = length;
    }

    /** Inserts a code point at the caret, replacing the selection. */
    public void insert(int codePoint) {
        deleteSelection();
        int n = Character.charCount(codePoint);
        ensure(length + n);
        System.arraycopy(chars, caret, chars, caret + n, length - caret);
        Character.toChars(codePoint, chars, caret);
        length += n;
        caret += n;
        anchor = caret;
        version++;
    }

    /** Inserts a string at the caret, replacing the selection. */
    public void insert(CharSequence s) {
        deleteSelection();
        int n = s.length();
        ensure(length + n);
        System.arraycopy(chars, caret, chars, caret + n, length - caret);
        for (int i = 0; i < n; i++) {
            chars[caret + i] = s.charAt(i);
        }
        length += n;
        caret += n;
        anchor = caret;
        version++;
    }

    /** Deletes the selection if any, else the char before the caret. Returns whether it did. */
    public boolean deleteBackward() {
        if (hasSelection()) {
            deleteSelection();
            return true;
        }
        if (caret == 0) {
            return false;
        }
        int from = caret;
        move(-1, false);
        remove(caret, from);
        return true;
    }

    /** Deletes the selection if any, else the char after the caret. Returns whether it did. */
    public boolean deleteForward() {
        if (hasSelection()) {
            deleteSelection();
            return true;
        }
        if (caret == length) {
            return false;
        }
        int to = caret + 1;
        if (to < length
                && Character.isHighSurrogate(chars[caret])
                && Character.isLowSurrogate(chars[to])) {
            to++;
        }
        remove(caret, to);
        return true;
    }

    /** Deletes the selected range; nothing without a selection. */
    public void deleteSelection() {
        if (!hasSelection()) {
            return;
        }
        int a = selectionStart();
        int b = selectionEnd();
        caret = a;
        anchor = a;
        remove(a, b);
    }

    /** Copies the selection into {@code out}; returns its length (0 without a selection). */
    public int selection(StringBuilder out) {
        int a = selectionStart();
        int b = selectionEnd();
        out.append(chars, a, b - a);
        return b - a;
    }

    private void remove(int from, int to) {
        System.arraycopy(chars, to, chars, from, length - to);
        length -= to - from;
        caret = Math.min(caret, length);
        anchor = caret;
        version++;
    }

    private void ensure(int n) {
        if (n > chars.length) {
            chars = Arrays.copyOf(chars, Math.max(n, chars.length * 2));
        }
    }
}
