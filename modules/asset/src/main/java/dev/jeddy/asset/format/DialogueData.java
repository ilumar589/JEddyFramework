package dev.jeddy.asset.format;

import static dev.jeddy.asset.format.Bytes.U32;
import static dev.jeddy.asset.format.Bytes.U64;
import static dev.jeddy.asset.format.Bytes.align;
import static dev.jeddy.asset.format.Bytes.checkRange;

import dev.jeddy.core.id.StrId;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A cooked conversation, {@code .jdlg} (DESIGN §24, D123; docs/formats/jdlg.md): the nodes with
 * their speaker lines, actions, choices and gotos; the choices with their condition programs,
 * checks and targets; one bytecode blob the condition and action programs index into; and a string
 * table (ids, speakers, texts). Everything is parallel {@code int} arrays, so the runtime reads a
 * node without allocating; the builder methods ({@link #addNode}, {@link #addChoice}, ...) are what
 * the compiler in {@code dev.jeddy.script.compile} fills.
 *
 * <p>A <em>program</em> is {@code (offset, length)} into {@link #code}; {@code -1} is "none". A
 * <em>target</em> is a node index, or {@link #END} to close the conversation. Strings are indices
 * into the table; {@link #stringId} gives the {@code StrId} hash of each, computed once, so quests,
 * flags and the host are keyed by a {@code long} at run time.
 *
 * <p>Thread ownership: built on the cooker's thread or the editor's; read on the loader's thread;
 * then immutable and read by the game thread.
 */
public final class DialogueData {
    /** {@code "JDLG"} in file order. */
    public static final int MAGIC = 0x474C444A;

    public static final int VERSION = 1;

    /** A target that ends the conversation. */
    public static final int END = -1;

    /** No program, no check. */
    public static final int NONE = -1;

    private static final int HEADER_SIZE = 96;
    private static final int NODE_INTS = 11;
    private static final int LINE_INTS = 3;
    private static final int CHOICE_INTS = 9;
    private static final int GOTO_INTS = 4;

    private int id = NONE;
    private int title = NONE;

    // Nodes.
    private int nodeCount;
    private int[] nodeId = new int[8];
    private int[] nodeSourceLine = new int[8];
    private int[] nodeLineStart = new int[8];
    private int[] nodeLineCount = new int[8];
    private int[] nodeActions = new int[8];
    private int[] nodeActionsLength = new int[8];
    private int[] nodeChoiceStart = new int[8];
    private int[] nodeChoiceCount = new int[8];
    private int[] nodeGotoStart = new int[8];
    private int[] nodeGotoCount = new int[8];
    private int[] nodeFlags = new int[8];

    // Lines: speaker and text per line, in node order.
    private int lineCount;
    private int[] lineSpeaker = new int[16];
    private int[] lineText = new int[16];
    private int[] lineSourceLine = new int[16];

    // Choices.
    private int choiceCount;
    private int[] choiceText = new int[8];
    private int[] choiceCondition = new int[8];
    private int[] choiceConditionLength = new int[8];
    private int[] choiceCheckAbility = new int[8];
    private int[] choiceCheckDc = new int[8];
    private int[] choiceTarget = new int[8];
    private int[] choiceElse = new int[8];
    private int[] choiceSourceLine = new int[8];
    private int[] choiceFlags = new int[8];

    // Gotos.
    private int gotoCount;
    private int[] gotoTarget = new int[8];
    private int[] gotoCondition = new int[8];
    private int[] gotoConditionLength = new int[8];
    private int[] gotoSourceLine = new int[8];

    // Code.
    private int codeLength;
    private int[] code = new int[64];

    // Strings.
    private final List<String> strings = new ArrayList<>();
    private final Map<String, Integer> stringIndex = new HashMap<>();
    private long[] stringIds = new long[16];

    /** An empty dialogue to build. */
    public DialogueData() {}

    // ---- Strings --------------------------------------------------------------------------------

    /** The index of {@code s} in the table, adding it if new. */
    public int intern(String s) {
        Integer i = stringIndex.get(s);
        if (i != null) {
            return i;
        }
        int index = strings.size();
        strings.add(s);
        stringIndex.put(s, index);
        if (index >= stringIds.length) {
            stringIds = Arrays.copyOf(stringIds, stringIds.length * 2);
        }
        stringIds[index] = StrId.of(s);
        return index;
    }

    public int stringCount() {
        return strings.size();
    }

    public String string(int index) {
        return strings.get(index);
    }

    /** The {@code StrId} of string {@code index} (precomputed). */
    public long stringId(int index) {
        return stringIds[index];
    }

    /** The index of {@code s}, or -1. */
    public int indexOf(String s) {
        Integer i = stringIndex.get(s);
        return i == null ? -1 : i;
    }

    // ---- Header -------------------------------------------------------------------------------

    public void setId(String id) {
        this.id = intern(id);
    }

    public void setTitle(String title) {
        this.title = intern(title);
    }

    /** The dialogue's id, or {@code null} before {@link #setId}. */
    public String id() {
        return id < 0 ? null : strings.get(id);
    }

    public String title() {
        return title < 0 ? "" : strings.get(title);
    }

    // ---- Nodes --------------------------------------------------------------------------------

    /**
     * Adds a node named {@code id} (interned) declared at {@code sourceLine}; returns its index.
     */
    public int addNode(String id, int sourceLine) {
        if (nodeCount == nodeId.length) {
            int n = nodeCount * 2;
            nodeId = Arrays.copyOf(nodeId, n);
            nodeSourceLine = Arrays.copyOf(nodeSourceLine, n);
            nodeLineStart = Arrays.copyOf(nodeLineStart, n);
            nodeLineCount = Arrays.copyOf(nodeLineCount, n);
            nodeActions = Arrays.copyOf(nodeActions, n);
            nodeActionsLength = Arrays.copyOf(nodeActionsLength, n);
            nodeChoiceStart = Arrays.copyOf(nodeChoiceStart, n);
            nodeChoiceCount = Arrays.copyOf(nodeChoiceCount, n);
            nodeGotoStart = Arrays.copyOf(nodeGotoStart, n);
            nodeGotoCount = Arrays.copyOf(nodeGotoCount, n);
            nodeFlags = Arrays.copyOf(nodeFlags, n);
        }
        int i = nodeCount++;
        nodeId[i] = intern(id);
        nodeSourceLine[i] = sourceLine;
        nodeLineStart[i] = lineCount;
        nodeLineCount[i] = 0;
        nodeActions[i] = NONE;
        nodeActionsLength[i] = 0;
        nodeChoiceStart[i] = choiceCount;
        nodeChoiceCount[i] = 0;
        nodeGotoStart[i] = gotoCount;
        nodeGotoCount[i] = 0;
        nodeFlags[i] = 0;
        return i;
    }

    public int nodeCount() {
        return nodeCount;
    }

    /** The node named {@code id}, or -1. */
    public int findNode(String id) {
        int s = indexOf(id);
        if (s < 0) {
            return -1;
        }
        for (int i = 0; i < nodeCount; i++) {
            if (nodeId[i] == s) {
                return i;
            }
        }
        return -1;
    }

    public String nodeId(int node) {
        return strings.get(nodeId[node]);
    }

    public int nodeSourceLine(int node) {
        return nodeSourceLine[node];
    }

    public int nodeLineStart(int node) {
        return nodeLineStart[node];
    }

    public int nodeLineCount(int node) {
        return nodeLineCount[node];
    }

    /** The node's action program offset, or {@link #NONE}. */
    public int nodeActions(int node) {
        return nodeActions[node];
    }

    public int nodeActionsLength(int node) {
        return nodeActionsLength[node];
    }

    public void setNodeActions(int node, int offset, int length) {
        nodeActions[node] = offset;
        nodeActionsLength[node] = length;
    }

    public int nodeChoiceStart(int node) {
        return nodeChoiceStart[node];
    }

    public int nodeChoiceCount(int node) {
        return nodeChoiceCount[node];
    }

    public int nodeGotoStart(int node) {
        return nodeGotoStart[node];
    }

    public int nodeGotoCount(int node) {
        return nodeGotoCount[node];
    }

    // ---- Lines (added to the last node) -----------------------------------------------------

    /** Adds a speaker line to node {@code node} (the last node added). */
    public int addLine(int node, String speaker, String text, int sourceLine) {
        if (node != nodeCount - 1) {
            throw new IllegalStateException("lines are added to the last node");
        }
        if (lineCount == lineSpeaker.length) {
            int n = lineCount * 2;
            lineSpeaker = Arrays.copyOf(lineSpeaker, n);
            lineText = Arrays.copyOf(lineText, n);
            lineSourceLine = Arrays.copyOf(lineSourceLine, n);
        }
        int i = lineCount++;
        lineSpeaker[i] = intern(speaker);
        lineText[i] = intern(text);
        lineSourceLine[i] = sourceLine;
        nodeLineCount[node]++;
        return i;
    }

    public int lineCount() {
        return lineCount;
    }

    public String lineSpeaker(int line) {
        return strings.get(lineSpeaker[line]);
    }

    /** The {@code StrId} of the speaker of {@code line}. */
    public long lineSpeakerId(int line) {
        return stringIds[lineSpeaker[line]];
    }

    public String lineText(int line) {
        return strings.get(lineText[line]);
    }

    public int lineSourceLine(int line) {
        return lineSourceLine[line];
    }

    // ---- Choices --------------------------------------------------------------------------------

    /**
     * Adds a choice to node {@code node} (the last node): its text, a condition program ({@link
     * #NONE} for always), an ability check ({@link #NONE} for none, with its DC), the target and
     * the {@code else} target of a failed check.
     */
    public int addChoice(
            int node,
            String text,
            int condition,
            int conditionLength,
            int checkAbility,
            int checkDc,
            int target,
            int elseTarget,
            int sourceLine) {
        if (node != nodeCount - 1) {
            throw new IllegalStateException("choices are added to the last node");
        }
        if (choiceCount == choiceText.length) {
            int n = choiceCount * 2;
            choiceText = Arrays.copyOf(choiceText, n);
            choiceCondition = Arrays.copyOf(choiceCondition, n);
            choiceConditionLength = Arrays.copyOf(choiceConditionLength, n);
            choiceCheckAbility = Arrays.copyOf(choiceCheckAbility, n);
            choiceCheckDc = Arrays.copyOf(choiceCheckDc, n);
            choiceTarget = Arrays.copyOf(choiceTarget, n);
            choiceElse = Arrays.copyOf(choiceElse, n);
            choiceSourceLine = Arrays.copyOf(choiceSourceLine, n);
            choiceFlags = Arrays.copyOf(choiceFlags, n);
        }
        int i = choiceCount++;
        choiceText[i] = intern(text);
        choiceCondition[i] = condition;
        choiceConditionLength[i] = conditionLength;
        choiceCheckAbility[i] = checkAbility;
        choiceCheckDc[i] = checkDc;
        choiceTarget[i] = target;
        choiceElse[i] = elseTarget;
        choiceSourceLine[i] = sourceLine;
        choiceFlags[i] = 0;
        nodeChoiceCount[node]++;
        return i;
    }

    public int choiceCount() {
        return choiceCount;
    }

    public String choiceText(int choice) {
        return strings.get(choiceText[choice]);
    }

    public int choiceCondition(int choice) {
        return choiceCondition[choice];
    }

    public int choiceConditionLength(int choice) {
        return choiceConditionLength[choice];
    }

    public int choiceCheckAbility(int choice) {
        return choiceCheckAbility[choice];
    }

    public int choiceCheckDc(int choice) {
        return choiceCheckDc[choice];
    }

    public int choiceTarget(int choice) {
        return choiceTarget[choice];
    }

    public int choiceElse(int choice) {
        return choiceElse[choice];
    }

    public int choiceSourceLine(int choice) {
        return choiceSourceLine[choice];
    }

    /** Resolves a choice's targets once every node is known (the compiler). */
    public void setChoiceTargets(int choice, int target, int elseTarget) {
        choiceTarget[choice] = target;
        choiceElse[choice] = elseTarget;
    }

    // ---- Gotos --------------------------------------------------------------------------------

    /** Adds a goto to node {@code node} (the last node): the target and a condition or none. */
    public int addGoto(int node, int target, int condition, int conditionLength, int sourceLine) {
        if (node != nodeCount - 1) {
            throw new IllegalStateException("gotos are added to the last node");
        }
        if (gotoCount == gotoTarget.length) {
            int n = gotoCount * 2;
            gotoTarget = Arrays.copyOf(gotoTarget, n);
            gotoCondition = Arrays.copyOf(gotoCondition, n);
            gotoConditionLength = Arrays.copyOf(gotoConditionLength, n);
            gotoSourceLine = Arrays.copyOf(gotoSourceLine, n);
        }
        int i = gotoCount++;
        gotoTarget[i] = target;
        gotoCondition[i] = condition;
        gotoConditionLength[i] = conditionLength;
        gotoSourceLine[i] = sourceLine;
        nodeGotoCount[node]++;
        return i;
    }

    public int gotoCount() {
        return gotoCount;
    }

    public int gotoTarget(int g) {
        return gotoTarget[g];
    }

    public void setGotoTarget(int g, int target) {
        gotoTarget[g] = target;
    }

    public int gotoCondition(int g) {
        return gotoCondition[g];
    }

    public int gotoConditionLength(int g) {
        return gotoConditionLength[g];
    }

    public int gotoSourceLine(int g) {
        return gotoSourceLine[g];
    }

    // ---- Code -----------------------------------------------------------------------------------

    /** Appends one instruction word and returns its index. */
    public int emit(int word) {
        if (codeLength == code.length) {
            code = Arrays.copyOf(code, code.length * 2);
        }
        code[codeLength] = word;
        return codeLength++;
    }

    /** Overwrites the word at {@code index} (a jump target patched after its body). */
    public void patch(int index, int word) {
        code[index] = word;
    }

    public int codeLength() {
        return codeLength;
    }

    /** The bytecode (valid up to {@link #codeLength}; shared, not copied). */
    public int[] code() {
        return code;
    }

    // ---- File -----------------------------------------------------------------------------------

    /** Serializes to the file format. */
    public byte[] toBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer nodes = ints(nodeCount * NODE_INTS);
        for (int i = 0; i < nodeCount; i++) {
            nodes.putInt(nodeId[i])
                    .putInt(nodeSourceLine[i])
                    .putInt(nodeLineStart[i])
                    .putInt(nodeLineCount[i])
                    .putInt(nodeActions[i])
                    .putInt(nodeActionsLength[i])
                    .putInt(nodeChoiceStart[i])
                    .putInt(nodeChoiceCount[i])
                    .putInt(nodeGotoStart[i])
                    .putInt(nodeGotoCount[i])
                    .putInt(nodeFlags[i]);
        }
        ByteBuffer lines = ints(lineCount * LINE_INTS);
        for (int i = 0; i < lineCount; i++) {
            lines.putInt(lineSpeaker[i]).putInt(lineText[i]).putInt(lineSourceLine[i]);
        }
        ByteBuffer choices = ints(choiceCount * CHOICE_INTS);
        for (int i = 0; i < choiceCount; i++) {
            choices.putInt(choiceText[i])
                    .putInt(choiceCondition[i])
                    .putInt(choiceConditionLength[i])
                    .putInt(choiceCheckAbility[i])
                    .putInt(choiceCheckDc[i])
                    .putInt(choiceTarget[i])
                    .putInt(choiceElse[i])
                    .putInt(choiceSourceLine[i])
                    .putInt(choiceFlags[i]);
        }
        ByteBuffer gotos = ints(gotoCount * GOTO_INTS);
        for (int i = 0; i < gotoCount; i++) {
            gotos.putInt(gotoTarget[i])
                    .putInt(gotoCondition[i])
                    .putInt(gotoConditionLength[i])
                    .putInt(gotoSourceLine[i]);
        }
        ByteBuffer codeBuf = ints(codeLength);
        for (int i = 0; i < codeLength; i++) {
            codeBuf.putInt(code[i]);
        }
        ByteArrayOutputStream stringBytes = new ByteArrayOutputStream();
        ByteBuffer stringTable = ints(2 * strings.size());
        for (String s : strings) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            stringTable.putInt(stringBytes.size()).putInt(b.length);
            stringBytes.writeBytes(b);
        }
        long nodesAt = HEADER_SIZE;
        long linesAt = align(nodesAt + nodes.capacity(), 16);
        long choicesAt = align(linesAt + lines.capacity(), 16);
        long gotosAt = align(choicesAt + choices.capacity(), 16);
        long codeAt = align(gotosAt + gotos.capacity(), 16);
        long stringsAt = align(codeAt + codeBuf.capacity(), 16);
        long fileSize = stringsAt + stringTable.capacity() + stringBytes.size();
        header.putInt(MAGIC)
                .putInt(VERSION)
                .putInt(id)
                .putInt(title)
                .putInt(nodeCount)
                .putInt(lineCount)
                .putInt(choiceCount)
                .putInt(gotoCount)
                .putInt(codeLength)
                .putInt(strings.size())
                .putLong(nodesAt)
                .putLong(linesAt)
                .putLong(choicesAt)
                .putLong(gotosAt)
                .putLong(codeAt)
                .putLong(stringsAt)
                .putLong(fileSize);
        out.writeBytes(header.array());
        out.writeBytes(nodes.array());
        padTo(out, linesAt);
        out.writeBytes(lines.array());
        padTo(out, choicesAt);
        out.writeBytes(choices.array());
        padTo(out, gotosAt);
        out.writeBytes(gotos.array());
        padTo(out, codeAt);
        out.writeBytes(codeBuf.array());
        padTo(out, stringsAt);
        out.writeBytes(stringTable.array());
        out.writeBytes(stringBytes.toByteArray());
        return out.toByteArray();
    }

    private static ByteBuffer ints(int n) {
        return ByteBuffer.allocate(4 * n).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void padTo(ByteArrayOutputStream out, long offset) {
        while (out.size() < offset) {
            out.write(0);
        }
    }

    /**
     * Reads a {@code .jdlg}.
     *
     * @throws AssetFormatException on a bad magic, version, count or range
     */
    public static DialogueData read(MemorySegment file) throws AssetFormatException {
        checkRange(file, 0, HEADER_SIZE, ".jdlg header");
        if (file.get(U32, 0) != MAGIC) {
            throw new AssetFormatException("not a .jdlg file");
        }
        int version = file.get(U32, 4);
        if (version != VERSION) {
            throw new AssetFormatException(".jdlg version " + version + ", expected " + VERSION);
        }
        DialogueData d = new DialogueData();
        int id = file.get(U32, 8);
        int title = file.get(U32, 12);
        int nodeCount = file.get(U32, 16);
        int lineCount = file.get(U32, 20);
        int choiceCount = file.get(U32, 24);
        int gotoCount = file.get(U32, 28);
        int codeLength = file.get(U32, 32);
        int stringCount = file.get(U32, 36);
        long nodesAt = file.get(U64, 40);
        long linesAt = file.get(U64, 48);
        long choicesAt = file.get(U64, 56);
        long gotosAt = file.get(U64, 64);
        long codeAt = file.get(U64, 72);
        long stringsAt = file.get(U64, 80);
        long fileSize = file.get(U64, 88);
        if (nodeCount < 0
                || lineCount < 0
                || choiceCount < 0
                || gotoCount < 0
                || codeLength < 0
                || stringCount < 0
                || fileSize > file.byteSize()) {
            throw new AssetFormatException(".jdlg header counts out of range");
        }
        // Strings first: everything else indexes them.
        checkRange(file, stringsAt, 8L * stringCount, ".jdlg string table");
        long bytesAt = stringsAt + 8L * stringCount;
        for (int i = 0; i < stringCount; i++) {
            int off = file.get(U32, stringsAt + 8L * i);
            int len = file.get(U32, stringsAt + 8L * i + 4);
            checkRange(file, bytesAt + off, len, ".jdlg string " + i);
            byte[] b = new byte[len];
            MemorySegment.copy(file, ValueLayout.JAVA_BYTE, bytesAt + off, b, 0, len);
            String s = new String(b, StandardCharsets.UTF_8);
            d.strings.add(s);
            d.stringIndex.putIfAbsent(s, i);
            if (i >= d.stringIds.length) {
                d.stringIds =
                        Arrays.copyOf(d.stringIds, Math.max(stringCount, d.stringIds.length * 2));
            }
            d.stringIds[i] = StrId.of(s);
        }
        d.id = id;
        d.title = title;
        int[] n = readInts(file, nodesAt, nodeCount * NODE_INTS, ".jdlg nodes");
        d.nodeCount = nodeCount;
        d.nodeId = column(n, NODE_INTS, 0, nodeCount);
        d.nodeSourceLine = column(n, NODE_INTS, 1, nodeCount);
        d.nodeLineStart = column(n, NODE_INTS, 2, nodeCount);
        d.nodeLineCount = column(n, NODE_INTS, 3, nodeCount);
        d.nodeActions = column(n, NODE_INTS, 4, nodeCount);
        d.nodeActionsLength = column(n, NODE_INTS, 5, nodeCount);
        d.nodeChoiceStart = column(n, NODE_INTS, 6, nodeCount);
        d.nodeChoiceCount = column(n, NODE_INTS, 7, nodeCount);
        d.nodeGotoStart = column(n, NODE_INTS, 8, nodeCount);
        d.nodeGotoCount = column(n, NODE_INTS, 9, nodeCount);
        d.nodeFlags = column(n, NODE_INTS, 10, nodeCount);
        int[] l = readInts(file, linesAt, lineCount * LINE_INTS, ".jdlg lines");
        d.lineCount = lineCount;
        d.lineSpeaker = column(l, LINE_INTS, 0, lineCount);
        d.lineText = column(l, LINE_INTS, 1, lineCount);
        d.lineSourceLine = column(l, LINE_INTS, 2, lineCount);
        int[] c = readInts(file, choicesAt, choiceCount * CHOICE_INTS, ".jdlg choices");
        d.choiceCount = choiceCount;
        d.choiceText = column(c, CHOICE_INTS, 0, choiceCount);
        d.choiceCondition = column(c, CHOICE_INTS, 1, choiceCount);
        d.choiceConditionLength = column(c, CHOICE_INTS, 2, choiceCount);
        d.choiceCheckAbility = column(c, CHOICE_INTS, 3, choiceCount);
        d.choiceCheckDc = column(c, CHOICE_INTS, 4, choiceCount);
        d.choiceTarget = column(c, CHOICE_INTS, 5, choiceCount);
        d.choiceElse = column(c, CHOICE_INTS, 6, choiceCount);
        d.choiceSourceLine = column(c, CHOICE_INTS, 7, choiceCount);
        d.choiceFlags = column(c, CHOICE_INTS, 8, choiceCount);
        int[] g = readInts(file, gotosAt, gotoCount * GOTO_INTS, ".jdlg gotos");
        d.gotoCount = gotoCount;
        d.gotoTarget = column(g, GOTO_INTS, 0, gotoCount);
        d.gotoCondition = column(g, GOTO_INTS, 1, gotoCount);
        d.gotoConditionLength = column(g, GOTO_INTS, 2, gotoCount);
        d.gotoSourceLine = column(g, GOTO_INTS, 3, gotoCount);
        d.code = readInts(file, codeAt, codeLength, ".jdlg code");
        d.codeLength = codeLength;
        d.validate();
        return d;
    }

    private static int[] readInts(MemorySegment file, long at, int n, String what)
            throws AssetFormatException {
        checkRange(file, at, 4L * n, what);
        int[] a = new int[Math.max(1, n)];
        MemorySegment.copy(file, ValueLayout.JAVA_INT_UNALIGNED, at, a, 0, n);
        return a;
    }

    private static int[] column(int[] rows, int stride, int field, int count) {
        int[] a = new int[Math.max(8, count)];
        for (int i = 0; i < count; i++) {
            a[i] = rows[i * stride + field];
        }
        return a;
    }

    /** Checks every index: a file that passes cannot make the runtime read out of bounds. */
    public void validate() throws AssetFormatException {
        int strings = this.strings.size();
        check(id >= -1 && id < strings, "id");
        check(title >= -1 && title < strings, "title");
        for (int i = 0; i < nodeCount; i++) {
            check(nodeId[i] >= 0 && nodeId[i] < strings, "node " + i + " id");
            check(range(nodeLineStart[i], nodeLineCount[i], lineCount), "node " + i + " lines");
            check(program(nodeActions[i], nodeActionsLength[i]), "node " + i + " actions");
            check(
                    range(nodeChoiceStart[i], nodeChoiceCount[i], choiceCount),
                    "node " + i + " choices");
            check(range(nodeGotoStart[i], nodeGotoCount[i], gotoCount), "node " + i + " gotos");
        }
        for (int i = 0; i < lineCount; i++) {
            check(lineSpeaker[i] >= 0 && lineSpeaker[i] < strings, "line " + i + " speaker");
            check(lineText[i] >= 0 && lineText[i] < strings, "line " + i + " text");
        }
        for (int i = 0; i < choiceCount; i++) {
            check(choiceText[i] >= 0 && choiceText[i] < strings, "choice " + i + " text");
            check(
                    program(choiceCondition[i], choiceConditionLength[i]),
                    "choice " + i + " condition");
            check(
                    choiceCheckAbility[i] >= -1 && choiceCheckAbility[i] < 6,
                    "choice " + i + " check");
            check(target(choiceTarget[i]), "choice " + i + " target");
            check(target(choiceElse[i]), "choice " + i + " else");
        }
        for (int i = 0; i < gotoCount; i++) {
            check(target(gotoTarget[i]), "goto " + i + " target");
            check(program(gotoCondition[i], gotoConditionLength[i]), "goto " + i + " condition");
        }
    }

    private boolean range(int start, int count, int total) {
        return start >= 0 && count >= 0 && start + count <= total;
    }

    private boolean program(int offset, int length) {
        return offset == NONE
                ? length == 0
                : offset >= 0 && length > 0 && offset + length <= codeLength;
    }

    private boolean target(int t) {
        return t >= END && t < nodeCount;
    }

    private static void check(boolean ok, String what) throws AssetFormatException {
        if (!ok) {
            throw new AssetFormatException(".jdlg " + what + " out of range");
        }
    }
}
