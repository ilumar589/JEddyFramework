package dev.jeddy.samples.iso;

/**
 * A ring of combat messages for the UI (TASKS M13-07): fixed char lines written in place, so a
 * message costs no allocation. Build a line with {@link #start()} and the {@code add} methods, then
 * {@link #end()}:
 *
 * <pre>{@code
 * log.start().add("Skeleton hits ").add(name).add(" for ").add(7).end();
 * }</pre>
 *
 * <p>Thread ownership: the game thread.
 */
final class CombatLog {
    private final char[][] lines;
    private final int[] lengths;
    private final float[] times;
    private final int lineChars;
    private int head;
    private int count;
    private int version;
    private float now;

    /** Creates a log keeping the last {@code lines} messages of up to {@code lineChars} chars. */
    CombatLog(int lineCount, int lineChars) {
        lines = new char[lineCount][lineChars];
        lengths = new int[lineCount];
        times = new float[lineCount];
        this.lineChars = lineChars;
    }

    /** Sets the time stamped on new messages. */
    void setTime(float seconds) {
        now = seconds;
    }

    /** Begins a message (replacing the oldest when full). */
    CombatLog start() {
        head = (head + 1) % lines.length;
        if (count < lines.length) {
            count++;
        }
        lengths[head] = 0;
        times[head] = now;
        return this;
    }

    /** Appends text to the message being built. */
    CombatLog add(CharSequence s) {
        char[] line = lines[head];
        int n = lengths[head];
        for (int i = 0; i < s.length() && n < lineChars; i++) {
            line[n++] = s.charAt(i);
        }
        lengths[head] = n;
        return this;
    }

    /** Appends an integer. */
    CombatLog add(int v) {
        if (v < 0) {
            add('-');
            v = -v;
        }
        int p = 1;
        while (v / p >= 10) {
            p *= 10;
        }
        while (p > 0) {
            add((char) ('0' + v / p % 10));
            p /= 10;
        }
        return this;
    }

    /** Appends a char. */
    CombatLog add(char c) {
        if (lengths[head] < lineChars) {
            lines[head][lengths[head]++] = c;
        }
        return this;
    }

    /** Ends the message. */
    void end() {
        version++;
    }

    /** Returns the number of messages kept. */
    int count() {
        return count;
    }

    /** Returns a counter that changes with every message (scroll-to-bottom detection). */
    int version() {
        return version;
    }

    /** Returns the chars of message {@code i} (0 is the oldest kept). */
    char[] chars(int i) {
        return lines[index(i)];
    }

    /** Returns the length of message {@code i}. */
    int length(int i) {
        return lengths[index(i)];
    }

    /** Returns the time of message {@code i}. */
    float time(int i) {
        return times[index(i)];
    }

    private int index(int i) {
        return (head - count + 1 + i + lines.length) % lines.length;
    }
}
