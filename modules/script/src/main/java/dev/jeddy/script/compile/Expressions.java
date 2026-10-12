package dev.jeddy.script.compile;

import dev.jeddy.asset.format.DialogueData;
import dev.jeddy.script.Bytecode;
import dev.jeddy.script.compile.Tokens.Kind;
import dev.jeddy.script.compile.Tokens.Token;

/**
 * Compiles an expression to stack-machine bytecode by recursive descent (DESIGN §24, D121). The
 * grammar, lowest precedence first:
 *
 * <pre>
 * expr    := or
 * or      := and ('or' and)*
 * and     := not ('and' not)*
 * not     := 'not' not | cmp
 * cmp     := sum (('==' | '!=' | '&lt;' | '&lt;=' | '&gt;' | '&gt;=') sum)?
 * sum     := term (('+' | '-') term)*
 * term    := unary (('*' | '/' | '%') unary)*
 * unary   := '-' unary | primary
 * primary := INT | 'true' | 'false' | STRING | WORD '(' args ')' | '(' expr ')'
 * args    := (arg (',' arg)*)?
 * arg     := expr | WORD                    -- a bare word is a name (or an ability for stat/check)
 * </pre>
 *
 * Each rule emits the code of its operands then the operator, so the stack holds exactly the values
 * an operator needs; {@code and}/{@code or} short-circuit with a jump that keeps the deciding
 * value. The result is one program in the dialogue's code blob, ended by {@link Bytecode#RETURN}.
 * Chapter 17 §3 derives the stack depth bound.
 *
 * <p>Thread ownership: the compiler's thread.
 */
final class Expressions {
    private final Tokens t;
    private final DialogueData out;

    Expressions(Tokens tokens, DialogueData out) {
        this.t = tokens;
        this.out = out;
    }

    /** Compiles one expression starting at the current token and returns its code offset. */
    int compile() {
        int start = out.codeLength();
        or();
        out.emit(Bytecode.word(Bytecode.RETURN, 0));
        return start;
    }

    private void or() {
        and();
        while (t.isWord("or")) {
            t.next();
            int jump = out.emit(0); // patched: JTK to the end
            out.emit(Bytecode.word(Bytecode.POP, 0));
            and();
            out.patch(jump, Bytecode.word(Bytecode.JUMP_IF_TRUE_KEEP, out.codeLength()));
        }
    }

    private void and() {
        not();
        while (t.isWord("and")) {
            t.next();
            int jump = out.emit(0); // patched: JFK to the end
            out.emit(Bytecode.word(Bytecode.POP, 0));
            not();
            out.patch(jump, Bytecode.word(Bytecode.JUMP_IF_FALSE_KEEP, out.codeLength()));
        }
    }

    private void not() {
        if (t.isWord("not")) {
            t.next();
            not();
            out.emit(Bytecode.word(Bytecode.NOT, 0));
            return;
        }
        cmp();
    }

    private void cmp() {
        sum();
        Token op = t.peek();
        if (op.kind() == Kind.OP) {
            int code =
                    switch (op.text()) {
                        case "==" -> Bytecode.EQ;
                        case "!=" -> Bytecode.NE;
                        case "<" -> Bytecode.LT;
                        case "<=" -> Bytecode.LE;
                        case ">" -> Bytecode.GT;
                        case ">=" -> Bytecode.GE;
                        default -> -1;
                    };
            if (code >= 0) {
                t.next();
                sum();
                out.emit(Bytecode.word(code, 0));
            }
        }
    }

    private void sum() {
        term();
        while (t.isOp("+") || t.isOp("-")) {
            int code = t.next().text().equals("+") ? Bytecode.ADD : Bytecode.SUB;
            term();
            out.emit(Bytecode.word(code, 0));
        }
    }

    private void term() {
        unary();
        while (t.isOp("*") || t.isOp("/") || t.isOp("%")) {
            String op = t.next().text();
            unary();
            out.emit(
                    Bytecode.word(
                            op.equals("*")
                                    ? Bytecode.MUL
                                    : op.equals("/") ? Bytecode.DIV : Bytecode.MOD,
                            0));
        }
    }

    private void unary() {
        if (t.isOp("-")) {
            t.next();
            unary();
            out.emit(Bytecode.word(Bytecode.NEG, 0));
            return;
        }
        primary();
    }

    private void primary() {
        Token tok = t.peek();
        switch (tok.kind()) {
            case INT -> {
                t.next();
                pushInt(parseInt(tok));
            }
            case STRING -> {
                t.next();
                out.emit(Bytecode.word(Bytecode.PUSH_STR, out.intern(tok.text())));
            }
            case LPAREN -> {
                t.next();
                or();
                t.expect(Kind.RPAREN, "')'");
            }
            case WORD -> {
                switch (tok.text()) {
                    case "true" -> {
                        t.next();
                        pushInt(1);
                    }
                    case "false" -> {
                        t.next();
                        pushInt(0);
                    }
                    default -> call();
                }
            }
            default -> throw t.error("expected a value, found " + tok);
        }
    }

    /** {@code name(args)}: a function of the script's fixed set; actions are not allowed here. */
    private void call() {
        Token name = t.next();
        int fn = Bytecode.function(name.text());
        if (fn < 0) {
            throw new Tokens.Bad(t.line, name.column(), "unknown function '" + name.text() + "'");
        }
        if (fn >= Bytecode.FIRST_ACTION) {
            throw new Tokens.Bad(
                    t.line,
                    name.column(),
                    "'" + name.text() + "' is an action, not a value; use it in a statement");
        }
        if (t.peek().kind() != Kind.LPAREN) {
            throw new Tokens.Bad(
                    t.line,
                    t.peek().column(),
                    "'" + name.text() + "' needs parentheses: " + name.text() + "(...)");
        }
        t.next();
        int argc = args(fn);
        t.expect(Kind.RPAREN, "')'");
        int arity = Bytecode.FUNCTION_ARITY[fn];
        if (arity >= 0 && argc != arity) {
            throw new Tokens.Bad(
                    t.line,
                    name.column(),
                    "'"
                            + name.text()
                            + "' takes "
                            + arity
                            + " argument"
                            + (arity == 1 ? "" : "s")
                            + ", not "
                            + argc);
        }
        out.emit(Bytecode.call(fn, argc));
    }

    /** The arguments of {@code fn}; returns how many. The first of stat/check is an ability. */
    int args(int fn) {
        int argc = 0;
        if (t.peek().kind() == Kind.RPAREN) {
            return 0;
        }
        while (true) {
            boolean abilityHere = (fn == Bytecode.STAT || fn == Bytecode.CHECK) && argc == 0;
            arg(abilityHere);
            argc++;
            if (t.peek().kind() != Kind.COMMA) {
                return argc;
            }
            t.next();
        }
    }

    /** One argument: an expression, or a bare word (a name, or an ability where one is due). */
    private void arg(boolean ability) {
        Token tok = t.peek();
        if (tok.kind() == Kind.WORD
                && t.peek(1).kind() != Kind.LPAREN
                && !isValueWord(tok.text())) {
            t.next();
            if (ability) {
                int a = Bytecode.ability(tok.text());
                if (a < 0) {
                    throw new Tokens.Bad(
                            t.line,
                            tok.column(),
                            "unknown ability '" + tok.text() + "' (str, dex, con, int, wis, cha)");
                }
                pushInt(a);
            } else {
                out.emit(Bytecode.word(Bytecode.PUSH_STR, out.intern(tok.text())));
            }
            return;
        }
        or();
    }

    private static boolean isValueWord(String w) {
        return w.equals("true") || w.equals("false") || w.equals("not");
    }

    private void pushInt(int v) {
        if (v >= Bytecode.MIN_OPERAND && v <= Bytecode.MAX_OPERAND) {
            out.emit(Bytecode.word(Bytecode.PUSH, v));
        } else {
            out.emit(Bytecode.word(Bytecode.PUSH_WIDE, 0));
            out.emit(v);
        }
    }

    private int parseInt(Token tok) {
        try {
            return Integer.parseInt(tok.text());
        } catch (NumberFormatException e) {
            throw new Tokens.Bad(t.line, tok.column(), "number too large: " + tok.text());
        }
    }
}
