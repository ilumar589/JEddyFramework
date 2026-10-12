package dev.jeddy.script;

import dev.jeddy.asset.format.QuestData;
import dev.jeddy.core.id.StrId;
import dev.jeddy.core.io.ByteReader;
import dev.jeddy.core.io.ByteWriter;

import java.util.Arrays;

/**
 * The quests of a playthrough (DESIGN §24, D124): the stage each quest is at (0 before it starts)
 * and the <em>journal</em>, the stage texts in the order they were reached. The definitions come
 * from a {@link QuestData} (titles, stage texts and kinds); a script's {@code set quest ghost 2}
 * moves the stage and, when the stage is declared, adds its text to the journal. A stage set that
 * the data does not declare still moves the stage (a script may count with it) but writes nothing.
 * Quests are keyed by the {@code StrId} of their id so the interpreter never touches a string.
 *
 * <p>Saved by quest id and stage, and the journal by (quest id, stage), so a re-cooked quest file
 * with new entries loads old saves. Allocates nothing after construction except when the journal
 * grows past its capacity.
 *
 * <p>Thread ownership: the game thread.
 */
public final class Quests {
    /** The layout {@link #write} uses (D117). */
    public static final int SAVE_FORMAT = 1;

    private QuestData data;
    private int[] stage = new int[0];
    private int entryCount;
    private int[] entryQuest = new int[32];
    private int[] entryStage = new int[32];
    private int version;

    /** Quests over no definitions yet; {@link #bind} gives them. */
    public Quests() {}

    public Quests(QuestData data) {
        bind(data);
    }

    /**
     * Binds (or re-binds, on a hot reload) the definitions, keeping the stages of quests that still
     * exist and dropping the journal entries of quests that do not.
     */
    public void bind(QuestData data) {
        QuestData old = this.data;
        int[] oldStage = stage;
        this.data = data;
        stage = new int[data.questCount()];
        if (old != null) {
            for (int q = 0; q < old.questCount(); q++) {
                int now = data.findQuest(old.questKey(q));
                if (now >= 0) {
                    stage[now] = oldStage[q];
                }
            }
            int kept = 0;
            for (int i = 0; i < entryCount; i++) {
                int now = data.findQuest(old.questKey(entryQuest[i]));
                if (now >= 0) {
                    entryQuest[kept] = now;
                    entryStage[kept] = entryStage[i];
                    kept++;
                }
            }
            entryCount = kept;
        }
        version++;
    }

    public QuestData data() {
        return data;
    }

    /** Bumped on every change (the journal redraws when it moves). */
    public int version() {
        return version;
    }

    public int questCount() {
        return data == null ? 0 : data.questCount();
    }

    /** The stage of quest {@code key} (a {@code StrId}), 0 before it starts or when unknown. */
    public int stage(long key) {
        int q = data == null ? -1 : data.findQuest(key);
        return q < 0 ? 0 : stage[q];
    }

    public int stage(String id) {
        return stage(StrId.of(id));
    }

    /** The stage of quest index {@code quest}. */
    public int stageOf(int quest) {
        return stage[quest];
    }

    /** Whether quest {@code quest} has started (its stage is not 0). */
    public boolean started(int quest) {
        return stage[quest] != 0;
    }

    /**
     * {@link QuestData#STAGE_OPEN}, {@code STAGE_DONE} or {@code STAGE_FAILED} of the current
     * stage.
     */
    public int kindOf(int quest) {
        int s = data.findStage(quest, stage[quest]);
        return s < 0 ? QuestData.STAGE_OPEN : data.stageKind(s);
    }

    /**
     * Moves quest {@code key} to {@code stage}; a declared stage adds its text to the journal. A
     * key the data does not know is ignored.
     */
    public void set(long key, int stage) {
        int q = data == null ? -1 : data.findQuest(key);
        if (q < 0 || this.stage[q] == stage) {
            return;
        }
        this.stage[q] = stage;
        if (data.findStage(q, stage) >= 0) {
            addEntry(q, stage);
        }
        version++;
    }

    public void set(String id, int stage) {
        set(StrId.of(id), stage);
    }

    private void addEntry(int quest, int stage) {
        if (entryCount == entryQuest.length) {
            entryQuest = Arrays.copyOf(entryQuest, entryCount * 2);
            entryStage = Arrays.copyOf(entryStage, entryCount * 2);
        }
        entryQuest[entryCount] = quest;
        entryStage[entryCount] = stage;
        entryCount++;
    }

    // ---- The journal ----------------------------------------------------------------------------

    /** Entries in the order they were reached. */
    public int entryCount() {
        return entryCount;
    }

    public int entryQuest(int entry) {
        return entryQuest[entry];
    }

    public int entryStage(int entry) {
        return entryStage[entry];
    }

    /** The text of entry {@code entry}, or an empty string when its stage is no longer declared. */
    public String entryText(int entry) {
        int s = data.findStage(entryQuest[entry], entryStage[entry]);
        return s < 0 ? "" : data.stageText(s);
    }

    public String title(int quest) {
        return data.questTitle(quest);
    }

    // ---- Saves ----------------------------------------------------------------------------------

    /**
     * Writes every started quest by id and stage, and the journal by (id, stage). Allocates
     * nothing.
     */
    public void write(ByteWriter w) {
        w.int32(SAVE_FORMAT);
        int started = 0;
        for (int q = 0; q < stage.length; q++) {
            if (stage[q] != 0) {
                started++;
            }
        }
        w.int32(started);
        for (int q = 0; q < stage.length; q++) {
            if (stage[q] != 0) {
                w.string(data.questId(q));
                w.int32(stage[q]);
            }
        }
        w.int32(entryCount);
        for (int i = 0; i < entryCount; i++) {
            w.string(data.questId(entryQuest[i]));
            w.int32(entryStage[i]);
        }
    }

    /** Reads what {@link #write} wrote; a quest the data no longer has is skipped. */
    public void read(ByteReader r) {
        int format = r.int32();
        if (format != 1) {
            throw new IllegalStateException(
                    "quests save format " + format + "; this build reads 1");
        }
        Arrays.fill(stage, 0);
        int started = r.int32();
        for (int i = 0; i < started; i++) {
            int q = data == null ? -1 : data.findQuest(r.string());
            int s = r.int32();
            if (q >= 0) {
                stage[q] = s;
            }
        }
        entryCount = 0;
        int entries = r.int32();
        for (int i = 0; i < entries; i++) {
            int q = data == null ? -1 : data.findQuest(r.string());
            int s = r.int32();
            if (q >= 0) {
                addEntry(q, s);
            }
        }
        version++;
    }
}
