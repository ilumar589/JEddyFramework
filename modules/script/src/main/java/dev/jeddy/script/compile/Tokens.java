package dev.jeddy.script.compile;

import java.util.ArrayList;
import java.util.List;

/**
 * The tokens of one source line (DESIGN §24, D120): words, integers, quoted strings, the arrow
 * {@code ->}, punctuation and operators, each with its 1-based column. A {@code #} outside a string
 * starts a comment. Lines are the unit of the format — every statement fits on one — so the lexer
 * never sees a newline, and the parser recovers from an error by moving to the next line.
 *
 * <p>Thread ownership: the compiler's thread.
 */
final class Tokens {
    enum Kind {
        WORD,
        INT,
        STRING,
        ARROW,
        COLON,
        LPAREN,
        RPAREN,
        COMMA,
        OP, // == != < <= > >= + - * / %
        END
    }

    record Token(Kind kind, String text, int column) {
        @Override
        public String toString() {
            return kind == Kind.END ? "end of line" : "'" + text + "'";
        }
    }

    private final List<Token> tokens = new ArrayList<>();
    private int at;
    final int line;

    /** Tokenizes {@code text} (line number {@code line}); a lexical error throws a {@link Bad}. */
    Tokens(String text, int line) {
        this.line = line;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r') {
                i++;
            } else if (c == '#') {
                break;
            } else if (Character.isLetter(c) || c == '_') {
                int s = i;
                while (i < n
                        && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) {
                    i++;
                }
                tokens.add(new Token(Kind.WORD, text.substring(s, i), s + 1));
            } else if (Character.isDigit(c)) {
                int s = i;
                while (i < n && Character.isDigit(text.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(Kind.INT, text.substring(s, i), s + 1));
            } else if (c == '"') {
                int s = i++;
                StringBuilder b = new StringBuilder();
                boolean closed = false;
                while (i < n) {
                    char d = text.charAt(i++);
                    if (d == '"') {
                        closed = true;
                        break;
                    }
                    if (d == '\\' && i < n) {
                        char e = text.charAt(i++);
                        b.append(e == 'n' ? '\n' : e);
                    } else {
                        b.append(d);
                    }
                }
                if (!closed) {
                    throw new Bad(line, s + 1, "unterminated string");
                }
                tokens.add(new Token(Kind.STRING, b.toString(), s + 1));
            } else if (c == '-' && i + 1 < n && text.charAt(i + 1) == '>') {
                tokens.add(new Token(Kind.ARROW, "->", i + 1));
                i += 2;
            } else if (c == ':') {
                tokens.add(new Token(Kind.COLON, ":", ++i));
            } else if (c == '(') {
                tokens.add(new Token(Kind.LPAREN, "(", ++i));
            } else if (c == ')') {
                tokens.add(new Token(Kind.RPAREN, ")", ++i));
            } else if (c == ',') {
                tokens.add(new Token(Kind.COMMA, ",", ++i));
            } else if ((c == '=' || c == '!' || c == '<' || c == '>')
                    && i + 1 < n
                    && text.charAt(i + 1) == '=') {
                tokens.add(new Token(Kind.OP, text.substring(i, i + 2), i + 1));
                i += 2;
            } else if (c == '<' || c == '>' || c == '+' || c == '-' || c == '*' || c == '/'
                    || c == '%') {
                tokens.add(new Token(Kind.OP, String.valueOf(c), ++i));
            } else {
                throw new Bad(line, i + 1, "unexpected character '" + c + "'");
            }
        }
        tokens.add(new Token(Kind.END, "", n + 1));
    }

    /** A lexical or syntax error at a position. */
    static final class Bad extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int line;
        final int column;

        Bad(int line, int column, String message) {
            super(message);
            this.line = line;
            this.column = column;
        }

        ScriptError toError() {
            return new ScriptError(line, column, getMessage());
        }
    }

    Token peek() {
        return tokens.get(at);
    }

    Token peek(int ahead) {
        return tokens.get(Math.min(at + ahead, tokens.size() - 1));
    }

    Token next() {
        Token t = tokens.get(at);
        if (at < tokens.size() - 1) {
            at++;
        }
        return t;
    }

    boolean atEnd() {
        return peek().kind == Kind.END;
    }

    boolean isWord(String word) {
        Token t = peek();
        return t.kind == Kind.WORD && t.text.equals(word);
    }

    boolean isOp(String op) {
        Token t = peek();
        return t.kind == Kind.OP && t.text.equals(op);
    }

    /** Consumes a token of {@code kind} or fails with {@code what}. */
    Token expect(Kind kind, String what) {
        Token t = peek();
        if (t.kind != kind) {
            throw new Bad(line, t.column, "expected " + what + ", found " + t);
        }
        return next();
    }

    /** Consumes the word {@code word} or fails. */
    Token expectWord(String word) {
        Token t = peek();
        if (t.kind != Kind.WORD || !t.text.equals(word)) {
            throw new Bad(line, t.column, "expected '" + word + "', found " + t);
        }
        return next();
    }

    int expectInt(String what) {
        Token t = expect(Kind.INT, what);
        try {
            return Integer.parseInt(t.text);
        } catch (NumberFormatException e) {
            throw new Bad(line, t.column, "number too large: " + t.text);
        }
    }

    void expectEnd() {
        Token t = peek();
        if (t.kind != Kind.END) {
            throw new Bad(line, t.column, "unexpected " + t + " at the end of the line");
        }
    }

    Bad error(String message) {
        return new Bad(line, peek().column, message);
    }
}
