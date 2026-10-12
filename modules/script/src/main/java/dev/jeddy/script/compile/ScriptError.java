package dev.jeddy.script.compile;

/**
 * One problem the compiler found: where (1-based line and column) and what. The editor lists them
 * by line; the cooker prints them as {@code file:line:column: message}.
 *
 * <p>Thread ownership: immutable.
 */
public record ScriptError(int line, int column, String message) {
    @Override
    public String toString() {
        return line + ":" + column + ": " + message;
    }
}
