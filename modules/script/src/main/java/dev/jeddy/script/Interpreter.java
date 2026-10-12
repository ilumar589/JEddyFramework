package dev.jeddy.script;

import dev.jeddy.asset.format.DialogueData;

/**
 * The stack machine that runs a condition or action program of a dialogue (DESIGN §24, D121; {@link
 * Bytecode} for the instruction set). A program is {@code code[offset ...]} up to its {@code
 * RETURN}; the result is the top of the stack (0 when empty). Reads go to the {@link ScriptHost},
 * {@link Quests} and {@link Flags} the interpreter was given; a string operand is the dialogue's
 * string index, resolved to its {@code StrId} here. Allocates nothing: the stack and the argument
 * buffer are fixed arrays, and a program that would overflow them is a compiler bug reported as an
 * {@link IllegalStateException}.
 *
 * <p>Thread ownership: the game thread.
 */
public final class Interpreter {
    private static final int STACK = 64;
    private static final int ARGS = 16;
    private static final int MAX_STEPS = 100_000;

    private final int[] stack = new int[STACK];
    private final int[] args = new int[ARGS];
    private final ScriptHost host;
    private final Quests quests;
    private final Flags flags;

    public Interpreter(ScriptHost host, Quests quests, Flags flags) {
        this.host = host;
        this.quests = quests;
        this.flags = flags;
    }

    public ScriptHost host() {
        return host;
    }

    public Quests quests() {
        return quests;
    }

    public Flags flags() {
        return flags;
    }

    /** Runs the program at {@code offset} of {@code d}'s code and returns its result. */
    public int run(DialogueData d, int offset) {
        int[] code = d.code();
        int end = d.codeLength();
        int pc = offset;
        int sp = 0;
        int steps = 0;
        while (true) {
            if (pc < 0 || pc >= end) {
                throw new IllegalStateException("program ran off the code at " + pc);
            }
            if (++steps > MAX_STEPS) {
                throw new IllegalStateException(
                        "program did not finish in " + MAX_STEPS + " steps");
            }
            int word = code[pc++];
            switch (Bytecode.op(word)) {
                case Bytecode.PUSH -> stack[sp++] = Bytecode.operand(word);
                case Bytecode.PUSH_WIDE -> stack[sp++] = code[pc++];
                case Bytecode.PUSH_STR -> stack[sp++] = Bytecode.operand(word);
                case Bytecode.CALL -> {
                    int fn = Bytecode.callFunction(word);
                    int argc = Bytecode.callArgc(word);
                    if (argc > ARGS || sp < argc) {
                        throw new IllegalStateException("bad call: " + Bytecode.toString(word));
                    }
                    sp -= argc;
                    System.arraycopy(stack, sp, args, 0, argc);
                    int result = 0;
                    boolean value = true;
                    switch (fn) {
                        case Bytecode.STAT -> result = host.stat(args[0]);
                        case Bytecode.LEVEL -> result = host.level();
                        case Bytecode.QUEST -> result = quests.stage(d.stringId(args[0]));
                        case Bytecode.FLAG -> result = flags.get(d.stringId(args[0]));
                        case Bytecode.ITEM -> result = host.item(d.stringId(args[0]));
                        case Bytecode.GOLD -> result = host.gold();
                        case Bytecode.RANDOM -> result = host.random(args[0]);
                        case Bytecode.CHECK -> result = host.check(args[0], args[1]) ? 1 : 0;
                        case Bytecode.SET_QUEST -> {
                            quests.set(d.stringId(args[0]), args[1]);
                            value = false;
                        }
                        case Bytecode.SET_FLAG -> {
                            flags.set(d.stringId(args[0]), args[1]);
                            value = false;
                        }
                        case Bytecode.GIVE -> {
                            host.give(d.stringId(args[0]), args[1]);
                            value = false;
                        }
                        case Bytecode.TAKE -> {
                            host.take(d.stringId(args[0]), args[1]);
                            value = false;
                        }
                        case Bytecode.ADD_GOLD -> {
                            host.addGold(args[0]);
                            value = false;
                        }
                        case Bytecode.DO -> {
                            host.call(d.stringId(args[0]), args, 1, argc - 1);
                            value = false;
                        }
                        default -> throw new IllegalStateException("unknown function " + fn);
                    }
                    if (value) {
                        stack[sp++] = result;
                    }
                }
                case Bytecode.NEG -> stack[sp - 1] = -stack[sp - 1];
                case Bytecode.NOT -> stack[sp - 1] = stack[sp - 1] == 0 ? 1 : 0;
                case Bytecode.ADD -> {
                    sp--;
                    stack[sp - 1] += stack[sp];
                }
                case Bytecode.SUB -> {
                    sp--;
                    stack[sp - 1] -= stack[sp];
                }
                case Bytecode.MUL -> {
                    sp--;
                    stack[sp - 1] *= stack[sp];
                }
                case Bytecode.DIV -> {
                    sp--;
                    stack[sp - 1] = stack[sp] == 0 ? 0 : stack[sp - 1] / stack[sp];
                }
                case Bytecode.MOD -> {
                    sp--;
                    stack[sp - 1] = stack[sp] == 0 ? 0 : stack[sp - 1] % stack[sp];
                }
                case Bytecode.EQ -> {
                    sp--;
                    stack[sp - 1] = stack[sp - 1] == stack[sp] ? 1 : 0;
                }
                case Bytecode.NE -> {
                    sp--;
                    stack[sp - 1] = stack[sp - 1] != stack[sp] ? 1 : 0;
                }
                case Bytecode.LT -> {
                    sp--;
                    stack[sp - 1] = stack[sp - 1] < stack[sp] ? 1 : 0;
                }
                case Bytecode.LE -> {
                    sp--;
                    stack[sp - 1] = stack[sp - 1] <= stack[sp] ? 1 : 0;
                }
                case Bytecode.GT -> {
                    sp--;
                    stack[sp - 1] = stack[sp - 1] > stack[sp] ? 1 : 0;
                }
                case Bytecode.GE -> {
                    sp--;
                    stack[sp - 1] = stack[sp - 1] >= stack[sp] ? 1 : 0;
                }
                case Bytecode.JUMP_IF_FALSE_KEEP -> {
                    if (stack[sp - 1] == 0) {
                        pc = Bytecode.operand(word);
                    }
                }
                case Bytecode.JUMP_IF_TRUE_KEEP -> {
                    if (stack[sp - 1] != 0) {
                        pc = Bytecode.operand(word);
                    }
                }
                case Bytecode.POP -> sp--;
                case Bytecode.RETURN -> {
                    return sp > 0 ? stack[sp - 1] : 0;
                }
                default -> throw new IllegalStateException("unknown opcode " + Bytecode.op(word));
            }
            if (sp >= STACK) {
                throw new IllegalStateException("stack overflow at " + (pc - 1));
            }
        }
    }

    /** Runs a program for its effect (an action list); the result is ignored. */
    public void execute(DialogueData d, int offset) {
        run(d, offset);
    }
}
