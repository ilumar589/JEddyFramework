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
 * Cooked quests, {@code .jquest} (DESIGN §24, D123; docs/formats/jquest.md): every quest of a
 * source file with its title and its stages — a number, a kind (open, done, failed) and the journal
 * text the player reads on reaching it. The runtime's {@code Quests} keys them by the {@code StrId}
 * of the quest id ({@link #questId}), so a script's {@code set quest ghost 2} and this table meet
 * on a {@code long}.
 *
 * <p>Thread ownership: built on the cooker's thread; read on the loader's; then immutable.
 */
public final class QuestData {
    /** {@code "JQST"} in file order. */
    public static final int MAGIC = 0x5453514A;

    public static final int VERSION = 1;

    public static final int STAGE_OPEN = 0;
    public static final int STAGE_DONE = 1;
    public static final int STAGE_FAILED = 2;

    private static final int HEADER_SIZE = 48;
    private static final int QUEST_INTS = 5;
    private static final int STAGE_INTS = 4;

    private int questCount;
    private int[] questId = new int[4];
    private int[] questTitle = new int[4];
    private int[] questStageStart = new int[4];
    private int[] questStageCount = new int[4];
    private int[] questSourceLine = new int[4];
    private int stageCount;
    private int[] stageNumber = new int[8];
    private int[] stageKind = new int[8];
    private int[] stageText = new int[8];
    private int[] stageSourceLine = new int[8];
    private final List<String> strings = new ArrayList<>();
    private final Map<String, Integer> stringIndex = new HashMap<>();
    private long[] stringIds = new long[16];

    public QuestData() {}

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

    public String string(int index) {
        return strings.get(index);
    }

    public int stringCount() {
        return strings.size();
    }

    /** Adds a quest; returns its index. Stages are added to the last quest. */
    public int addQuest(String id, String title, int sourceLine) {
        if (questCount == questId.length) {
            int n = questCount * 2;
            questId = Arrays.copyOf(questId, n);
            questTitle = Arrays.copyOf(questTitle, n);
            questStageStart = Arrays.copyOf(questStageStart, n);
            questStageCount = Arrays.copyOf(questStageCount, n);
            questSourceLine = Arrays.copyOf(questSourceLine, n);
        }
        int i = questCount++;
        questId[i] = intern(id);
        questTitle[i] = intern(title == null ? "" : title);
        questStageStart[i] = stageCount;
        questStageCount[i] = 0;
        questSourceLine[i] = sourceLine;
        return i;
    }

    public void setQuestTitle(int quest, String title) {
        questTitle[quest] = intern(title);
    }

    /** Adds a stage to quest {@code quest} (the last one); returns its index. */
    public int addStage(int quest, int number, int kind, String text, int sourceLine) {
        if (quest != questCount - 1) {
            throw new IllegalStateException("stages are added to the last quest");
        }
        if (stageCount == stageNumber.length) {
            int n = stageCount * 2;
            stageNumber = Arrays.copyOf(stageNumber, n);
            stageKind = Arrays.copyOf(stageKind, n);
            stageText = Arrays.copyOf(stageText, n);
            stageSourceLine = Arrays.copyOf(stageSourceLine, n);
        }
        int i = stageCount++;
        stageNumber[i] = number;
        stageKind[i] = kind;
        stageText[i] = intern(text);
        stageSourceLine[i] = sourceLine;
        questStageCount[quest]++;
        return i;
    }

    public int questCount() {
        return questCount;
    }

    public String questId(int quest) {
        return strings.get(questId[quest]);
    }

    /** The {@code StrId} of quest {@code quest}'s id. */
    public long questKey(int quest) {
        return stringIds[questId[quest]];
    }

    public String questTitle(int quest) {
        return strings.get(questTitle[quest]);
    }

    public int questStageStart(int quest) {
        return questStageStart[quest];
    }

    public int questStageCount(int quest) {
        return questStageCount[quest];
    }

    public int questSourceLine(int quest) {
        return questSourceLine[quest];
    }

    /** The quest with id {@code id}, or -1. */
    public int findQuest(String id) {
        Integer s = stringIndex.get(id);
        if (s == null) {
            return -1;
        }
        for (int i = 0; i < questCount; i++) {
            if (questId[i] == s) {
                return i;
            }
        }
        return -1;
    }

    /** The quest whose id has {@code StrId} {@code key}, or -1. */
    public int findQuest(long key) {
        for (int i = 0; i < questCount; i++) {
            if (stringIds[questId[i]] == key) {
                return i;
            }
        }
        return -1;
    }

    /** The stage of {@code quest} numbered {@code number}, or -1. */
    public int findStage(int quest, int number) {
        for (int s = questStageStart[quest];
                s < questStageStart[quest] + questStageCount[quest];
                s++) {
            if (stageNumber[s] == number) {
                return s;
            }
        }
        return -1;
    }

    public int stageCount() {
        return stageCount;
    }

    public int stageNumber(int stage) {
        return stageNumber[stage];
    }

    /** {@link #STAGE_OPEN}, {@link #STAGE_DONE} or {@link #STAGE_FAILED}. */
    public int stageKind(int stage) {
        return stageKind[stage];
    }

    public String stageText(int stage) {
        return strings.get(stageText[stage]);
    }

    public int stageSourceLine(int stage) {
        return stageSourceLine[stage];
    }

    // ---- File -----------------------------------------------------------------------------------

    /** Serializes to the file format. */
    public byte[] toBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer quests =
                ByteBuffer.allocate(4 * questCount * QUEST_INTS).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < questCount; i++) {
            quests.putInt(questId[i])
                    .putInt(questTitle[i])
                    .putInt(questStageStart[i])
                    .putInt(questStageCount[i])
                    .putInt(questSourceLine[i]);
        }
        ByteBuffer stages =
                ByteBuffer.allocate(4 * stageCount * STAGE_INTS).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < stageCount; i++) {
            stages.putInt(stageNumber[i])
                    .putInt(stageKind[i])
                    .putInt(stageText[i])
                    .putInt(stageSourceLine[i]);
        }
        ByteArrayOutputStream stringBytes = new ByteArrayOutputStream();
        ByteBuffer stringTable =
                ByteBuffer.allocate(8 * strings.size()).order(ByteOrder.LITTLE_ENDIAN);
        for (String s : strings) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            stringTable.putInt(stringBytes.size()).putInt(b.length);
            stringBytes.writeBytes(b);
        }
        long questsAt = HEADER_SIZE;
        long stagesAt = align(questsAt + quests.capacity(), 16);
        long stringsAt = align(stagesAt + stages.capacity(), 16);
        long fileSize = stringsAt + stringTable.capacity() + stringBytes.size();
        header.putInt(MAGIC)
                .putInt(VERSION)
                .putInt(questCount)
                .putInt(stageCount)
                .putInt(strings.size())
                .putInt(0)
                .putLong(questsAt)
                .putLong(stagesAt)
                .putLong(stringsAt);
        out.writeBytes(header.array());
        out.writeBytes(quests.array());
        padTo(out, stagesAt);
        out.writeBytes(stages.array());
        padTo(out, stringsAt);
        out.writeBytes(stringTable.array());
        out.writeBytes(stringBytes.toByteArray());
        if (out.size() != fileSize) {
            throw new IllegalStateException("size " + out.size() + " != " + fileSize);
        }
        return out.toByteArray();
    }

    private static void padTo(ByteArrayOutputStream out, long offset) {
        while (out.size() < offset) {
            out.write(0);
        }
    }

    /** Reads a {@code .jquest}. */
    public static QuestData read(MemorySegment file) throws AssetFormatException {
        checkRange(file, 0, HEADER_SIZE, ".jquest header");
        if (file.get(U32, 0) != MAGIC) {
            throw new AssetFormatException("not a .jquest file");
        }
        int version = file.get(U32, 4);
        if (version != VERSION) {
            throw new AssetFormatException(".jquest version " + version + ", expected " + VERSION);
        }
        int questCount = file.get(U32, 8);
        int stageCount = file.get(U32, 12);
        int stringCount = file.get(U32, 16);
        long questsAt = file.get(U64, 24);
        long stagesAt = file.get(U64, 32);
        long stringsAt = file.get(U64, 40);
        if (questCount < 0 || stageCount < 0 || stringCount < 0) {
            throw new AssetFormatException(".jquest header counts out of range");
        }
        QuestData d = new QuestData();
        checkRange(file, stringsAt, 8L * stringCount, ".jquest string table");
        long bytesAt = stringsAt + 8L * stringCount;
        for (int i = 0; i < stringCount; i++) {
            int off = file.get(U32, stringsAt + 8L * i);
            int len = file.get(U32, stringsAt + 8L * i + 4);
            checkRange(file, bytesAt + off, len, ".jquest string " + i);
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
        checkRange(file, questsAt, 4L * questCount * QUEST_INTS, ".jquest quests");
        int[] q = new int[Math.max(1, questCount * QUEST_INTS)];
        MemorySegment.copy(
                file, ValueLayout.JAVA_INT_UNALIGNED, questsAt, q, 0, questCount * QUEST_INTS);
        d.questCount = questCount;
        d.questId = column(q, QUEST_INTS, 0, questCount);
        d.questTitle = column(q, QUEST_INTS, 1, questCount);
        d.questStageStart = column(q, QUEST_INTS, 2, questCount);
        d.questStageCount = column(q, QUEST_INTS, 3, questCount);
        d.questSourceLine = column(q, QUEST_INTS, 4, questCount);
        checkRange(file, stagesAt, 4L * stageCount * STAGE_INTS, ".jquest stages");
        int[] s = new int[Math.max(1, stageCount * STAGE_INTS)];
        MemorySegment.copy(
                file, ValueLayout.JAVA_INT_UNALIGNED, stagesAt, s, 0, stageCount * STAGE_INTS);
        d.stageCount = stageCount;
        d.stageNumber = column(s, STAGE_INTS, 0, stageCount);
        d.stageKind = column(s, STAGE_INTS, 1, stageCount);
        d.stageText = column(s, STAGE_INTS, 2, stageCount);
        d.stageSourceLine = column(s, STAGE_INTS, 3, stageCount);
        d.validate();
        return d;
    }

    private static int[] column(int[] rows, int stride, int field, int count) {
        int[] a = new int[Math.max(4, count)];
        for (int i = 0; i < count; i++) {
            a[i] = rows[i * stride + field];
        }
        return a;
    }

    /** Checks every index. */
    public void validate() throws AssetFormatException {
        int strings = this.strings.size();
        for (int i = 0; i < questCount; i++) {
            check(questId[i] >= 0 && questId[i] < strings, "quest " + i + " id");
            check(questTitle[i] >= 0 && questTitle[i] < strings, "quest " + i + " title");
            check(
                    questStageStart[i] >= 0
                            && questStageCount[i] >= 0
                            && questStageStart[i] + questStageCount[i] <= stageCount,
                    "quest " + i + " stages");
        }
        for (int i = 0; i < stageCount; i++) {
            check(stageKind[i] >= 0 && stageKind[i] <= STAGE_FAILED, "stage " + i + " kind");
            check(stageText[i] >= 0 && stageText[i] < strings, "stage " + i + " text");
        }
    }

    private static void check(boolean ok, String what) throws AssetFormatException {
        if (!ok) {
            throw new AssetFormatException(".jquest " + what + " out of range");
        }
    }
}
