package dev.jeddy.script.compile;

import java.util.List;

/**
 * A script that did not compile: every error the compiler collected, in source order. The message
 * is the first error prefixed with the file name, which is what a build log shows.
 *
 * <p>Thread ownership: immutable.
 */
public final class ScriptException extends Exception {
    private static final long serialVersionUID = 1L;
    private final transient List<ScriptError> errors;

    public ScriptException(String file, List<ScriptError> errors) {
        super(
                file
                        + ":"
                        + errors.get(0)
                        + (errors.size() > 1 ? " (+" + (errors.size() - 1) + " more)" : ""));
        this.errors = List.copyOf(errors);
    }

    public List<ScriptError> errors() {
        return errors;
    }
}
