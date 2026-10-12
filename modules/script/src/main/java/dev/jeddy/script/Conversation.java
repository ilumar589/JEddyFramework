package dev.jeddy.script;

import dev.jeddy.asset.format.DialogueData;
import dev.jeddy.core.io.ByteReader;
import dev.jeddy.core.io.ByteWriter;

/**
 * One dialogue in progress (DESIGN §24, D124): the node, the line being shown, and the choices the
 * player can take. Entering a node runs its actions once and evaluates its choices' conditions once
 * into {@link #choiceCount}/{@link #choiceText}; {@link #advance} shows the next line or, past the
 * last, follows the node's gotos (a node with no lines is a router: its gotos are followed on
 * entry); {@link #choose} runs a choice's check and goes to its target or its {@code else}. The
 * conversation ends when a goto reaches {@link DialogueData#END}, or when no goto's condition
 * holds.
 *
 * <p>The window draws from {@link #hasLine}, {@link #speakerId}, {@link #text} and the choice
 * accessors; nothing here is per frame, and nothing allocates. A conversation saves as its node,
 * line and visible choices, so a load resumes it without re-rolling the conditions.
 *
 * <p>Thread ownership: the game thread.
 */
public final class Conversation {
    /** The layout {@link #write} uses (D117). */
    public static final int SAVE_FORMAT = 1;

    private static final int MAX_CHOICES = 32;
    private static final int MAX_HOPS = 64;

    private final Interpreter vm;
    private DialogueData d;
    private int node = -1;
    private int line;
    private final int[] visible = new int[MAX_CHOICES];
    private int visibleCount;
    private int version;

    public Conversation(Interpreter vm) {
        this.vm = vm;
    }

    public Interpreter interpreter() {
        return vm;
    }

    /** The dialogue running, or {@code null}. */
    public DialogueData dialogue() {
        return d;
    }

    /** Whether a dialogue is running. */
    public boolean isActive() {
        return node >= 0;
    }

    /** Bumped whenever what is shown changes. */
    public int version() {
        return version;
    }

    /**
     * Starts {@code dialogue} at its {@code start} node; returns whether it is still active after
     * entry.
     */
    public boolean start(DialogueData dialogue) {
        this.d = dialogue;
        int start = dialogue.findNode("start");
        if (start < 0) {
            throw new IllegalArgumentException("dialogue " + dialogue.id() + " has no start node");
        }
        enter(start, 0);
        return isActive();
    }

    /** Ends the conversation where it is (the player walked away). */
    public void end() {
        node = -1;
        visibleCount = 0;
        version++;
    }

    private void enter(int n, int hops) {
        if (hops > MAX_HOPS) {
            throw new IllegalStateException("dialogue " + d.id() + " loops without a line");
        }
        node = n;
        line = 0;
        version++;
        int actions = d.nodeActions(n);
        if (actions != DialogueData.NONE) {
            vm.execute(d, actions);
        }
        evaluateChoices();
        if (d.nodeLineCount(n) == 0 && visibleCount == 0) {
            followGotos(hops + 1);
        }
    }

    private void evaluateChoices() {
        visibleCount = 0;
        int start = d.nodeChoiceStart(node);
        int count = d.nodeChoiceCount(node);
        for (int c = start; c < start + count && visibleCount < MAX_CHOICES; c++) {
            int condition = d.choiceCondition(c);
            if (condition == DialogueData.NONE || vm.run(d, condition) != 0) {
                visible[visibleCount++] = c;
            }
        }
    }

    private void followGotos(int hops) {
        int start = d.nodeGotoStart(node);
        int count = d.nodeGotoCount(node);
        for (int g = start; g < start + count; g++) {
            int condition = d.gotoCondition(g);
            if (condition == DialogueData.NONE || vm.run(d, condition) != 0) {
                go(d.gotoTarget(g), hops);
                return;
            }
        }
        end(); // nothing to go to
    }

    private void go(int target, int hops) {
        if (target == DialogueData.END) {
            end();
        } else {
            enter(target, hops);
        }
    }

    // ---- What to show ---------------------------------------------------------------------------

    /** The node being shown, or -1. */
    public int node() {
        return node;
    }

    /** Whether a line is being shown (else the choices are, or nothing). */
    public boolean hasLine() {
        return node >= 0 && line < d.nodeLineCount(node);
    }

    private int lineIndex() {
        return d.nodeLineStart(node) + line;
    }

    /** The speaker of the shown line as a {@code StrId} ({@link ScriptHost#speakerName}). */
    public long speakerId() {
        return d.lineSpeakerId(lineIndex());
    }

    public String speaker() {
        return d.lineSpeaker(lineIndex());
    }

    public String text() {
        return d.lineText(lineIndex());
    }

    /** Whether the shown line is the node's last (the choices or the gotos come next). */
    public boolean lastLine() {
        return node >= 0 && line == d.nodeLineCount(node) - 1;
    }

    /** Whether the choices are being shown (every line of the node has been). */
    public boolean atChoices() {
        return node >= 0 && !hasLine() && visibleCount > 0;
    }

    /** The visible choices of the node, whether or not the lines are done. */
    public int choiceCount() {
        return visibleCount;
    }

    public String choiceText(int i) {
        return d.choiceText(visible[i]);
    }

    /** The ability of choice {@code i}'s check, or -1 for none. */
    public int choiceCheckAbility(int i) {
        return d.choiceCheckAbility(visible[i]);
    }

    public int choiceCheckDc(int i) {
        return d.choiceCheckDc(visible[i]);
    }

    /**
     * The {@code StrId} of string index {@code index} of the dialogue (for a host's {@code call}).
     */
    public long stringId(int index) {
        return d.stringId(index);
    }

    // ---- Input ----------------------------------------------------------------------------------

    /**
     * The player read the line: shows the next, or past the last follows the gotos (when the node
     * has no choices). Does nothing while the choices are up or the conversation is over.
     */
    public void advance() {
        if (!hasLine()) {
            return;
        }
        line++;
        version++;
        if (!hasLine() && visibleCount == 0) {
            followGotos(0);
        }
    }

    /** The player took visible choice {@code i}: its check is rolled and the target entered. */
    public void choose(int i) {
        if (node < 0 || i < 0 || i >= visibleCount) {
            return;
        }
        int c = visible[i];
        int target = d.choiceTarget(c);
        int ability = d.choiceCheckAbility(c);
        if (ability >= 0 && !vm.host().check(ability, d.choiceCheckDc(c))) {
            target = d.choiceElse(c);
        }
        go(target, 0);
    }

    // ---- Saves ----------------------------------------------------------------------------------

    /**
     * Writes the state: whether active, the node by id, the line and the visible choices (as
     * indices in the node, so a re-cooked dialogue with the same nodes still resumes). The dialogue
     * itself is the game's to name: write its id before this.
     */
    public void write(ByteWriter w) {
        w.int32(SAVE_FORMAT);
        w.bool(node >= 0);
        if (node < 0) {
            return;
        }
        w.string(d.nodeId(node));
        w.int32(line);
        w.int32(visibleCount);
        for (int i = 0; i < visibleCount; i++) {
            w.int32(visible[i] - d.nodeChoiceStart(node));
        }
    }

    /**
     * Reads what {@link #write} wrote over {@code dialogue} (the one the game saved the id of). A
     * node the dialogue no longer has ends the conversation.
     */
    public void read(ByteReader r, DialogueData dialogue) {
        int format = r.int32();
        if (format != 1) {
            throw new IllegalStateException(
                    "conversation save format " + format + "; this build reads 1");
        }
        boolean active = r.bool();
        if (!active) {
            end();
            return;
        }
        String nodeId = r.string();
        int savedLine = r.int32();
        int n = r.int32();
        d = dialogue;
        node = dialogue == null ? -1 : dialogue.findNode(nodeId);
        visibleCount = 0;
        for (int i = 0; i < n; i++) {
            int local = r.int32();
            if (node >= 0
                    && local >= 0
                    && local < d.nodeChoiceCount(node)
                    && visibleCount < MAX_CHOICES) {
                visible[visibleCount++] = d.nodeChoiceStart(node) + local;
            }
        }
        line = node >= 0 ? Math.min(savedLine, d.nodeLineCount(node)) : 0;
        version++;
    }
}
