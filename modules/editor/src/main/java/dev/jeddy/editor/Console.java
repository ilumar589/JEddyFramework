package dev.jeddy.editor;

import dev.jeddy.core.log.Log;
import dev.jeddy.core.log.Logger;

/**
 * The editor's message log: the last {@code capacity} lines (commands applied, files saved, cooker
 * reports, errors), shown by the Console panel and mirrored to the {@code editor} logger. Lines are
 * strings made when logged (a cold path); reading them back each frame allocates nothing.
 *
 * <p>Thread ownership: the game thread.
 */
public final class Console {
    /** Line severities. */
    public static final int INFO = 0;

    public static final int WARN = 1;
    public static final int ERROR = 2;

    private static final Logger LOG = Log.logger("editor");

    private final String[] lines;
    private final int[] levels;
    private int start;
    private int count;
    private int version;

    /** Creates a log keeping the last {@code capacity} lines. */
    public Console(int capacity) {
        lines = new String[capacity];
        levels = new int[capacity];
    }

    /** Appends an informational line. */
    public void info(String line) {
        add(INFO, line);
        LOG.info(line);
    }

    /** Appends a warning. */
    public void warn(String line) {
        add(WARN, line);
        LOG.warn(line);
    }

    /** Appends an error. */
    public void error(String line) {
        add(ERROR, line);
        LOG.error(line);
    }

    private void add(int level, String line) {
        int at = (start + count) % lines.length;
        if (count == lines.length) {
            start = (start + 1) % lines.length;
        } else {
            count++;
        }
        lines[at] = line;
        levels[at] = level;
        version++;
    }

    /** Returns the number of lines kept. */
    public int size() {
        return count;
    }

    /** Returns line {@code i}, oldest first. */
    public String line(int i) {
        return lines[(start + i) % lines.length];
    }

    /** Returns the severity of line {@code i}. */
    public int level(int i) {
        return levels[(start + i) % lines.length];
    }

    /** Returns a counter that changes with every line (panels scroll to the end on change). */
    public int version() {
        return version;
    }

    /** Forgets every line. */
    public void clear() {
        start = 0;
        count = 0;
        version++;
    }
}
