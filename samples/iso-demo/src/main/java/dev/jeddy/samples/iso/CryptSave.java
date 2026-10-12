package dev.jeddy.samples.iso;

import dev.jeddy.app.world.AgentSync;
import dev.jeddy.app.world.LevelLoader;
import dev.jeddy.app.world.World;
import dev.jeddy.core.io.ByteReader;
import dev.jeddy.core.io.ByteWriter;
import dev.jeddy.core.job.JobSystem;
import dev.jeddy.core.log.Log;
import dev.jeddy.core.log.Logger;
import dev.jeddy.save.SaveFile;
import dev.jeddy.save.SaveFormatException;
import dev.jeddy.save.SaveJob;
import dev.jeddy.save.SaveSlots;
import dev.jeddy.save.SaveWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The crypt's save game (DESIGN §21, TASKS M16-08): what the demo writes and reads, in which order,
 * and the slots it keeps. A save is the dynamic state only (D112) — the level, the models and the
 * rulebook come from the paks — in one chunk per system, each written by that system:
 *
 * <pre>
 *  GAME  the demo's own scalars (play time, modes, the camera) and its random generator
 *  COMP  the registry's stores, entities as {@link CryptIds}
 *  CROW  the crowd's agents in their slots
 *  ENEM  the skeletons: counts, bodies and facings
 *  SYNC  the facing of every bound agent
 *  ANIM  what every character is playing
 *  RULE  the rules (creatures by content id, the generator), the encounter, the fight's state
 *  PRTY  the party's selection, formation and cooldowns
 *  SPEL  the spells' counters
 *  FOG   the explored bits
 * </pre>
 *
 * <p>The capture runs on the game thread between ticks into a {@link SaveWriter} kept for the level
 * (0 B after the first save of that size); the {@link SaveJob} compresses and writes elsewhere
 * (D118). A load reads the file whole and applies the chunks in the order above, which is the order
 * the state depends on: the stores give every skeleton its agent, the crowd restores the agents,
 * the skeletons rebind them, the facings land, the animations replay, the rules rebuild the
 * creatures at their handles and the party re-derives who is down.
 *
 * <p>Thread ownership: the game thread.
 */
final class CryptSave {
    private static final Logger LOG = Log.logger("iso-demo");

    /** What the demo writes into {@code GAME} and the title the slot list shows. */
    interface Host {
        void writeGame(ByteWriter w);

        void readGame(ByteReader r);

        /** Appends the title the slot list shows (into a kept builder: no allocation). */
        void title(StringBuilder out);

        double playSeconds();
    }

    static final int GAME = SaveFile.tag("GAME");
    static final int COMP = SaveFile.tag("COMP");
    static final int CROW = SaveFile.tag("CROW");
    static final int ENEM = SaveFile.tag("ENEM");
    static final int SYNC = SaveFile.tag("SYNC");
    static final int ANIM = SaveFile.tag("ANIM");
    static final int RULE = SaveFile.tag("RULE");
    static final int PRTY = SaveFile.tag("PRTY");
    static final int SPEL = SaveFile.tag("SPEL");
    static final int FOG = SaveFile.tag("FOG ");

    /** The version every chunk is written with; a reader keeps the old ones (D117). */
    static final int VERSION = 1;

    private final SaveSlots slots;
    private final SaveWriter writer = new SaveWriter(256 * 1024);
    private final CryptIds ids;
    private final Host host;
    private final World world;
    private final Level level;
    private final String levelPath;
    private final Party party;
    private final Enemies enemies;
    private final Animator animator;
    private final Spells spells;
    private final CryptGame game;
    private final Combat combat;
    private final AgentSync sync;
    private final JobSystem jobs;
    private final StringBuilder text = new StringBuilder(96); // meta values, without allocating
    private long captureNanos;
    private long loadNanos;
    private String lastError;

    CryptSave(
            Path directory,
            Host host,
            World world,
            Level level,
            LevelLoader loader,
            String levelPath,
            Party party,
            Enemies enemies,
            Animator animator,
            Spells spells,
            CryptGame game,
            Combat combat,
            AgentSync sync,
            JobSystem jobs) {
        this.slots = new SaveSlots(directory);
        this.host = host;
        this.world = world;
        this.level = level;
        this.levelPath = levelPath;
        this.party = party;
        this.enemies = enemies;
        this.animator = animator;
        this.spells = spells;
        this.game = game;
        this.combat = combat;
        this.sync = sync;
        this.jobs = jobs;
        ids = new CryptIds(loader, party, enemies, spells);
    }

    SaveSlots slots() {
        return slots;
    }

    SaveJob job() {
        return slots.job();
    }

    /** What the last capture took on the game thread. */
    long captureNanos() {
        return captureNanos;
    }

    /** The last load's failure, or {@code null}. */
    String lastError() {
        return lastError;
    }

    /** What the last load took: the read, the decompression and the apply. */
    long loadNanos() {
        return loadNanos;
    }

    /** The slots on disk, newest first (a menu lists them when it opens). */
    List<SaveSlots.SaveInfo> list() {
        try {
            return slots.list();
        } catch (IOException e) {
            LOG.warn("cannot list saves: " + e);
            return List.of();
        }
    }

    // ---- Capture ------------------------------------------------------------------------------

    /** Captures the state into the writer (between ticks). */
    void capture() {
        long t0 = System.nanoTime();
        writer.reset();
        ids.beginCapture();
        writer.meta("game", "crypt");
        text.setLength(0);
        host.title(text);
        writer.meta("title", text);
        writer.meta("level", levelPath);
        text.setLength(0);
        text.append((long) host.playSeconds());
        writer.meta("playSeconds", text);
        text.setLength(0);
        text.append(System.currentTimeMillis()); // epoch millis; the menu formats it
        writer.meta("savedAt", text);
        text.setLength(0);
        text.append(enemies.kills);
        writer.meta("kills", text);
        host.writeGame(writer.chunk(GAME, VERSION));
        game.write(writer.chunk(COMP, VERSION), ids);
        level.crowd.write(writer.chunk(CROW, VERSION));
        enemies.write(writer.chunk(ENEM, VERSION));
        sync.write(writer.chunk(SYNC, VERSION));
        animator.write(writer.chunk(ANIM, VERSION));
        combat.write(writer.chunk(RULE, VERSION));
        party.write(writer.chunk(PRTY, VERSION));
        spells.write(writer.chunk(SPEL, VERSION));
        level.visibility.write(writer.chunk(FOG, VERSION));
        writer.finish();
        captureNanos = System.nanoTime() - t0;
    }

    /**
     * Captures and hands the bytes to the job for {@code slot}; the job writes in the background.
     */
    void save(String slot) {
        capture();
        slots.save(slot, writer, captureNanos);
    }

    /** The capture's payload size (after {@link #capture}). */
    int payloadBytes() {
        return writer.payloadSize();
    }

    /** A copy of the last capture's payload (tests and the scripted round-trip check). */
    byte[] payloadCopy() {
        return java.util.Arrays.copyOf(writer.payloadBuffer(), writer.payloadSize());
    }

    /**
     * Compares the last capture's payload with {@code expected} chunk by chunk and returns {@code
     * null} when they are the same bytes, else which chunk differs first and where: a load that
     * restored everything captures again to the byte, so this is the demo's whole-state check.
     */
    String differenceFrom(byte[] expected) {
        byte[] actual = payloadCopy();
        if (actual.length != expected.length) {
            return "payload is " + actual.length + " bytes, expected " + expected.length;
        }
        StringBuilder out = new StringBuilder();
        ByteReader a = new ByteReader(actual);
        ByteReader e = new ByteReader(expected);
        while (a.hasRemaining() && e.hasRemaining()) {
            int tagA = a.int32();
            int tagE = e.int32();
            a.int32();
            e.int32();
            int lengthA = a.int32();
            int lengthE = e.int32();
            a.int32();
            e.int32();
            if (tagA != tagE || lengthA != lengthE) {
                return out
                        + "chunk "
                        + SaveFile.tagName(tagE)
                        + " is "
                        + lengthA
                        + " bytes, expected "
                        + lengthE;
            }
            int startA = a.position();
            int startE = e.position();
            int reported = 0;
            for (int i = 0; i < lengthA && reported < 4; i++) {
                if (actual[startA + i] != expected[startE + i]) {
                    out.append("chunk ")
                            .append(SaveFile.tagName(tagE))
                            .append(" differs at byte ")
                            .append(i)
                            .append(" of ")
                            .append(lengthA);
                    if (tagE == COMP) {
                        out.append(" (")
                                .append(storeAt(new ByteReader(expected, startE, lengthE), i))
                                .append(')');
                    }
                    out.append("; ");
                    reported++;
                    i = i / 4 * 4 + 3; // the next word
                }
            }
            int padded = lengthA + ((8 - (lengthA & 7)) & 7);
            a.skip(padded);
            e.skip(padded);
        }
        return out.isEmpty() ? null : out.toString();
    }

    /** The store of a {@code COMP} chunk that spans byte {@code offset}, for the check above. */
    private static String storeAt(ByteReader comp, int offset) {
        int start = comp.position();
        int n = comp.int32();
        for (int i = 0; i < n; i++) {
            int at = comp.position() - start;
            String name = dev.jeddy.components.Store.skip(comp);
            int end = comp.position() - start;
            if (offset >= at && offset < end) {
                return "store " + name + ", byte " + (offset - at) + " of its " + (end - at);
            }
        }
        return "past the stores";
    }

    // ---- Load ---------------------------------------------------------------------------------

    /**
     * Loads {@code slot} over the running state; returns whether it did (the failure is logged).
     */
    boolean load(String slot) {
        lastError = null;
        if (!slots.exists(slot)) {
            lastError = "no save in slot " + slot;
            return false;
        }
        long t0 = System.nanoTime();
        try {
            apply(slots.load(slot));
            loadNanos = System.nanoTime() - t0;
            return true;
        } catch (IOException | RuntimeException e) {
            lastError = e.getMessage() == null ? e.toString() : e.getMessage();
            LOG.warn("load of " + slot + " failed: " + e);
            return false;
        }
    }

    /** Applies a read file; throws on a save for another level or a chunk from a newer build. */
    void apply(SaveFile file) throws IOException {
        String saved = file.meta().get("level");
        if (saved != null && !saved.equals(levelPath)) {
            throw new SaveFormatException("the save is for level " + saved + ", not " + levelPath);
        }
        ByteReader r = chunk(file, GAME);
        if (r != null) {
            host.readGame(r);
        }
        spells.clear();
        ids.beginLoad();
        r = chunk(file, COMP);
        if (r != null) {
            game.read(r, ids);
        }
        r = chunk(file, CROW);
        if (r != null) {
            level.crowd.read(r);
        }
        r = chunk(file, ENEM);
        if (r != null) {
            enemies.read(r);
        }
        r = chunk(file, SYNC);
        if (r != null) {
            sync.read(r);
        }
        r = chunk(file, ANIM);
        if (r != null) {
            animator.read(r);
        }
        r = chunk(file, RULE);
        if (r != null) {
            combat.read(r);
        }
        r = chunk(file, PRTY);
        if (r != null) {
            party.read(r);
        }
        r = chunk(file, SPEL);
        if (r != null) {
            spells.read(r);
        }
        r = chunk(file, FOG);
        if (r != null) {
            level.visibility.read(r);
            level.fog.invalidate();
        }
        world.updateTransforms(jobs);
        for (int i = 0; i < Party.SIZE; i++) {
            int a = party.agent[i]; // the viewers stand where the crowd put them back
            level.visibility.setViewer(i, level.crowd.x(a), level.crowd.z(a), Party.SIGHT);
        }
        level.fog.update();
        game.fixedUpdateStage(1, 0); // see and show: who is visible now
    }

    /**
     * The reader of chunk {@code tag}, or {@code null} with a warning when the save lacks it (an
     * older save); throws for a version this build does not know (a newer save).
     */
    private static ByteReader chunk(SaveFile file, int tag) throws SaveFormatException {
        SaveFile.Chunk c = file.chunk(tag);
        if (c == null) {
            LOG.warn(
                    "save has no " + SaveFile.tagName(tag) + " chunk; that state keeps its values");
            return null;
        }
        if (c.version() > VERSION) {
            throw new SaveFormatException(
                    "chunk "
                            + c.tagName()
                            + " is version "
                            + c.version()
                            + "; this build reads up to "
                            + VERSION);
        }
        return c.data();
    }
}
