package dev.jeddy.script.compile;

import dev.jeddy.asset.format.DialogueData;
import dev.jeddy.script.Bytecode;
import dev.jeddy.script.compile.Tokens.Kind;
import dev.jeddy.script.compile.Tokens.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles a {@code .dlg} script to a {@link DialogueData} (DESIGN §24, D120, D121). The format,
 * one statement per line, {@code #} comments, indentation free:
 *
 * <pre>
 * dialogue ID                      -- once, first
 *   title: TEXT                    -- optional
 * node ID                          -- the conversation starts at 'start'
 *   SPEAKER: TEXT                  -- a line; 'player' is the party
 *   set quest ID N                 -- actions, run when the node is entered
 *   set flag ID [true|false|N]
 *   give ID [N]  |  take ID [N]
 *   gold +N  |  gold -N
 *   do NAME(args)                  -- the game's own hook
 *   choice "TEXT" [if EXPR] [check ABILITY DC] -> TARGET [else TARGET]
 *   -> TARGET [if EXPR]            -- gotos, the first whose condition holds
 *   end                            -- a goto that closes the conversation
 * </pre>
 *
 * A node has choices or gotos (an {@code end} is a goto), not both, and must have one or the other.
 * A choice with a {@code check} needs an {@code else}. Targets may be declared later in the file.
 * Every error is collected with its line and column; the parser recovers at the next line, so a
 * writer sees all of them at once in the editor.
 *
 * <p>Thread ownership: any single thread per call.
 */
public final class DialogueCompiler {
    private static final Pattern SPEAKER_LINE =
            Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*:(.*)$");
    private static final List<String> KEYWORDS =
            List.of(
                    "dialogue",
                    "node",
                    "choice",
                    "set",
                    "give",
                    "take",
                    "gold",
                    "do",
                    "end",
                    "title");

    /** The outcome: the data (complete only when there are no errors) and the errors. */
    public record Result(DialogueData data, List<ScriptError> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    private DialogueCompiler() {}

    /** Compiles {@code source}, or throws with every error. */
    public static DialogueData compile(String source, String file) throws ScriptException {
        Result r = check(source);
        if (!r.ok()) {
            throw new ScriptException(file, r.errors());
        }
        return r.data();
    }

    /**
     * Compiles {@code source} and returns the data and every error found (none when it is fine).
     */
    public static Result check(String source) {
        State s = new State();
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineNumber = i + 1;
            String raw = lines[i];
            if (raw.isBlank() || raw.stripLeading().startsWith("#")) {
                continue;
            }
            try {
                s.statement(raw, lineNumber);
            } catch (Tokens.Bad bad) {
                s.errors.add(bad.toError());
            }
        }
        s.finish();
        return new Result(s.out, s.errors);
    }

    /** One pending target reference: resolved once every node is known. */
    private record Pending(
            int index, boolean isChoice, boolean isElse, String target, int line, int column) {}

    private static final class State {
        final DialogueData out = new DialogueData();
        final List<ScriptError> errors = new ArrayList<>();
        final List<Pending> pending = new ArrayList<>();
        final List<String> nodeNames = new ArrayList<>();
        final List<Integer> nodeLines = new ArrayList<>();
        boolean headerSeen;
        int node = -1;
        boolean nodeHasChoices;
        boolean nodeHasGotos;
        // The current node's actions: appended to the code as statements come, closed at the
        // next node (RETURN) so one program holds them all.
        int actionsStart = -1;

        void statement(String raw, int lineNumber) {
            Matcher m = SPEAKER_LINE.matcher(raw);
            if (m.matches() && !KEYWORDS.contains(m.group(1))) {
                line(m.group(1), m.group(2).strip(), lineNumber);
                return;
            }
            if (m.matches() && m.group(1).equals("title")) {
                if (node >= 0 || !headerSeen) {
                    throw new Tokens.Bad(
                            lineNumber,
                            1,
                            "'title:' belongs in the dialogue header, after 'dialogue ID'");
                }
                out.setTitle(m.group(2).strip());
                return;
            }
            Tokens t = new Tokens(raw, lineNumber);
            Token first = t.peek();
            if (first.kind() == Kind.ARROW) {
                t.next();
                gotoStatement(t, lineNumber);
                return;
            }
            if (first.kind() != Kind.WORD) {
                throw t.error("expected a statement, found " + first);
            }
            switch (first.text()) {
                case "dialogue" -> header(t, lineNumber);
                case "node" -> nodeStatement(t, lineNumber);
                case "choice" -> choiceStatement(t, lineNumber);
                case "set" -> setStatement(t, lineNumber);
                case "give", "take" -> giveStatement(t, lineNumber);
                case "gold" -> goldStatement(t, lineNumber);
                case "do" -> doStatement(t, lineNumber);
                case "end" -> {
                    t.next();
                    t.expectEnd();
                    inNode(t);
                    addGoto(t, lineNumber, "end", DialogueData.NONE, 0);
                }
                default -> throw t.error("unknown statement '" + first.text() + "'");
            }
        }

        void header(Tokens t, int lineNumber) {
            t.next();
            if (headerSeen) {
                throw t.error("a file holds one dialogue");
            }
            Token id = t.expect(Kind.WORD, "the dialogue's id");
            t.expectEnd();
            out.setId(id.text());
            headerSeen = true;
        }

        void inNode(Tokens t) {
            if (node < 0) {
                throw new Tokens.Bad(t.line, 1, "this belongs in a node: write 'node ID' first");
            }
        }

        void closeNode() {
            if (node < 0) {
                return;
            }
            if (actionsStart >= 0) {
                out.emit(Bytecode.word(Bytecode.RETURN, 0));
                out.setNodeActions(node, actionsStart, out.codeLength() - actionsStart);
                actionsStart = -1;
            }
            if (!nodeHasChoices && !nodeHasGotos) {
                errors.add(
                        new ScriptError(
                                nodeLines.get(node),
                                1,
                                "node '"
                                        + nodeNames.get(node)
                                        + "' does not end: add a choice, a goto ('-> ID') or"
                                        + " 'end'"));
            }
        }

        void nodeStatement(Tokens t, int lineNumber) {
            t.next();
            Token id = t.expect(Kind.WORD, "the node's id");
            t.expectEnd();
            if (!headerSeen) {
                throw new Tokens.Bad(lineNumber, 1, "write 'dialogue ID' before the first node");
            }
            closeNode();
            if (nodeNames.contains(id.text())) {
                // Reported, but the node is still opened so that its statements attach to it
                // rather than to the previous node (one error, not a cascade).
                errors.add(
                        new ScriptError(
                                lineNumber,
                                id.column(),
                                "node '" + id.text() + "' is declared twice"));
            }
            node = out.addNode(id.text(), lineNumber);
            nodeNames.add(id.text());
            nodeLines.add(lineNumber);
            nodeHasChoices = false;
            nodeHasGotos = false;
        }

        void line(String speaker, String text, int lineNumber) {
            if (node < 0) {
                throw new Tokens.Bad(
                        lineNumber, 1, "a line belongs in a node: write 'node ID' first");
            }
            if (nodeHasChoices || nodeHasGotos) {
                throw new Tokens.Bad(
                        lineNumber, 1, "lines come before the choices and gotos of a node");
            }
            if (text.isEmpty()) {
                throw new Tokens.Bad(lineNumber, speaker.length() + 2, "the line has no text");
            }
            out.addLine(node, speaker, text, lineNumber);
        }

        void beginAction(Tokens t) {
            inNode(t);
            if (nodeHasChoices || nodeHasGotos) {
                throw new Tokens.Bad(
                        t.line, 1, "actions come before the choices and gotos of a node");
            }
            if (actionsStart < 0) {
                actionsStart = out.codeLength();
            }
        }

        void setStatement(Tokens t, int lineNumber) {
            t.next();
            Token what = t.expect(Kind.WORD, "'quest' or 'flag'");
            beginAction(t);
            switch (what.text()) {
                case "quest" -> {
                    Token id = t.expect(Kind.WORD, "the quest's id");
                    int stage = t.expectInt("the stage number");
                    t.expectEnd();
                    out.emit(Bytecode.word(Bytecode.PUSH_STR, out.intern(id.text())));
                    out.emit(Bytecode.word(Bytecode.PUSH, stage));
                    out.emit(Bytecode.call(Bytecode.SET_QUEST, 2));
                }
                case "flag" -> {
                    Token id = t.expect(Kind.WORD, "the flag's id");
                    int value = 1;
                    if (!t.atEnd()) {
                        Token v = t.next();
                        if (v.kind() == Kind.INT) {
                            try {
                                value = Integer.parseInt(v.text());
                            } catch (NumberFormatException e) {
                                throw new Tokens.Bad(
                                        lineNumber, v.column(), "number too large: " + v.text());
                            }
                        } else if (v.kind() == Kind.WORD && v.text().equals("false")) {
                            value = 0;
                        } else if (v.kind() != Kind.WORD || !v.text().equals("true")) {
                            throw new Tokens.Bad(
                                    lineNumber,
                                    v.column(),
                                    "expected true, false or a number, found " + v);
                        }
                    }
                    t.expectEnd();
                    out.emit(Bytecode.word(Bytecode.PUSH_STR, out.intern(id.text())));
                    out.emit(Bytecode.word(Bytecode.PUSH, value));
                    out.emit(Bytecode.call(Bytecode.SET_FLAG, 2));
                }
                default ->
                        throw new Tokens.Bad(
                                lineNumber, what.column(), "expected 'set quest' or 'set flag'");
            }
        }

        void giveStatement(Tokens t, int lineNumber) {
            boolean give = t.next().text().equals("give");
            beginAction(t);
            Token id = t.expect(Kind.WORD, "the item's id");
            int n = t.atEnd() ? 1 : t.expectInt("a count");
            t.expectEnd();
            out.emit(Bytecode.word(Bytecode.PUSH_STR, out.intern(id.text())));
            out.emit(Bytecode.word(Bytecode.PUSH, n));
            out.emit(Bytecode.call(give ? Bytecode.GIVE : Bytecode.TAKE, 2));
        }

        void goldStatement(Tokens t, int lineNumber) {
            t.next();
            beginAction(t);
            Token sign = t.peek();
            int factor = 1;
            if (sign.kind() == Kind.OP && (sign.text().equals("+") || sign.text().equals("-"))) {
                factor = sign.text().equals("-") ? -1 : 1;
                t.next();
            } else {
                throw t.error("expected 'gold +N' or 'gold -N'");
            }
            int n = t.expectInt("an amount");
            t.expectEnd();
            out.emit(Bytecode.word(Bytecode.PUSH, factor * n));
            out.emit(Bytecode.call(Bytecode.ADD_GOLD, 1));
        }

        void doStatement(Tokens t, int lineNumber) {
            t.next();
            beginAction(t);
            Token name = t.expect(Kind.WORD, "the hook's name");
            t.expect(Kind.LPAREN, "'('");
            out.emit(Bytecode.word(Bytecode.PUSH_STR, out.intern(name.text())));
            int argc = new Expressions(t, out).args(Bytecode.DO);
            t.expect(Kind.RPAREN, "')'");
            t.expectEnd();
            if (argc + 1 > 255) {
                throw new Tokens.Bad(lineNumber, name.column(), "too many arguments");
            }
            out.emit(Bytecode.call(Bytecode.DO, argc + 1));
        }

        void choiceStatement(Tokens t, int lineNumber) {
            t.next();
            inNode(t);
            if (nodeHasGotos) {
                throw new Tokens.Bad(lineNumber, 1, "a node has choices or gotos, not both");
            }
            Token text = t.expect(Kind.STRING, "the choice's text in quotes");
            int condition = DialogueData.NONE;
            int conditionLength = 0;
            if (t.isWord("if")) {
                t.next();
                condition = new Expressions(t, out).compile();
                conditionLength = out.codeLength() - condition;
            }
            int ability = DialogueData.NONE;
            int dc = 0;
            if (t.isWord("check")) {
                t.next();
                Token a = t.expect(Kind.WORD, "an ability (str, dex, con, int, wis, cha)");
                ability = Bytecode.ability(a.text());
                if (ability < 0) {
                    throw new Tokens.Bad(
                            lineNumber,
                            a.column(),
                            "unknown ability '" + a.text() + "' (str, dex, con, int, wis, cha)");
                }
                dc = t.expectInt("the difficulty class");
            }
            t.expect(Kind.ARROW, "'->'");
            Token target = t.expect(Kind.WORD, "a node id or 'end'");
            String elseTarget = null;
            int elseColumn = 0;
            if (t.isWord("else")) {
                t.next();
                Token e = t.expect(Kind.WORD, "a node id or 'end'");
                elseTarget = e.text();
                elseColumn = e.column();
            }
            t.expectEnd();
            if (ability >= 0 && elseTarget == null) {
                throw new Tokens.Bad(
                        lineNumber,
                        target.column(),
                        "a choice with a check needs 'else TARGET' for the failure");
            }
            if (ability < 0 && elseTarget != null) {
                throw new Tokens.Bad(lineNumber, elseColumn, "'else' needs a 'check' to fail");
            }
            int c =
                    out.addChoice(
                            node,
                            text.text(),
                            condition,
                            conditionLength,
                            ability,
                            dc,
                            DialogueData.END,
                            DialogueData.END,
                            lineNumber);
            pending.add(new Pending(c, true, false, target.text(), lineNumber, target.column()));
            if (elseTarget != null) {
                pending.add(new Pending(c, true, true, elseTarget, lineNumber, elseColumn));
            }
            nodeHasChoices = true;
        }

        void gotoStatement(Tokens t, int lineNumber) {
            inNode(t);
            Token target = t.expect(Kind.WORD, "a node id or 'end'");
            int condition = DialogueData.NONE;
            int conditionLength = 0;
            if (t.isWord("if")) {
                t.next();
                condition = new Expressions(t, out).compile();
                conditionLength = out.codeLength() - condition;
            }
            t.expectEnd();
            addGoto(t, lineNumber, target.text(), condition, conditionLength);
            pending.add(
                    new Pending(
                            out.gotoCount() - 1,
                            false,
                            false,
                            target.text(),
                            lineNumber,
                            target.column()));
        }

        /** Adds a goto; a target other than {@code end} is resolved at the end of the file. */
        void addGoto(Tokens t, int lineNumber, String target, int condition, int conditionLength) {
            if (nodeHasChoices) {
                throw new Tokens.Bad(lineNumber, 1, "a node has choices or gotos, not both");
            }
            out.addGoto(node, DialogueData.END, condition, conditionLength, lineNumber);
            nodeHasGotos = true;
        }

        void finish() {
            if (!headerSeen) {
                errors.add(new ScriptError(1, 1, "the file needs 'dialogue ID' first"));
            }
            closeNode();
            if (node < 0) {
                errors.add(new ScriptError(1, 1, "the dialogue has no nodes"));
            } else if (out.findNode("start") < 0) {
                errors.add(
                        new ScriptError(
                                1,
                                1,
                                "the conversation starts at a node named 'start'; there is none"));
            }
            for (Pending p : pending) {
                int target;
                if (p.target().equals("end")) {
                    target = DialogueData.END;
                } else {
                    target = out.findNode(p.target());
                    if (target < 0) {
                        errors.add(
                                new ScriptError(
                                        p.line(),
                                        p.column(),
                                        "no node named '" + p.target() + "'"));
                        continue;
                    }
                }
                if (p.isChoice()) {
                    if (p.isElse()) {
                        out.setChoiceTargets(p.index(), out.choiceTarget(p.index()), target);
                    } else {
                        out.setChoiceTargets(p.index(), target, out.choiceElse(p.index()));
                    }
                } else {
                    out.setGotoTarget(p.index(), target);
                }
            }
            // An unconditional goto that is not the last of its node makes the rest unreachable.
            for (int n = 0; n < out.nodeCount(); n++) {
                int start = out.nodeGotoStart(n);
                int count = out.nodeGotoCount(n);
                for (int g = start; g < start + count - 1; g++) {
                    if (out.gotoCondition(g) == DialogueData.NONE) {
                        errors.add(
                                new ScriptError(
                                        out.gotoSourceLine(g + 1),
                                        1,
                                        "unreachable: the goto above it has no condition"));
                        break;
                    }
                }
            }
            errors.sort(
                    (a, b) ->
                            a.line() != b.line()
                                    ? Integer.compare(a.line(), b.line())
                                    : Integer.compare(a.column(), b.column()));
        }
    }
}
