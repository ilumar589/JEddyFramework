# 16 — Saves: the dynamic state, in chunks, written in the background

*Milestone M16, built after M22. Read [01 Architecture](01-architecture.md) (handles, SoA
stores, the game thread), [22 Components](22-components.md) (the stores a save holds) and
[10 Assets](10-assets.md) (the engine's other binary formats) first; [15 Rules](15-rules.md)
explains the creatures the save rebuilds. Lesson [L04](lessons/04-binary-formats.md) reads the
bytes of a real save.*

## 1. Goal and result

A CRPG is played over many sittings. The player expects to **quick save** on a key and have the
game exactly where it was, to find an **autosave** after a crash, to keep **named saves** before a
hard fight, and to load a save made last month into a game that has been patched since. Behind
those four sentences are the four hard parts of a save system:

1. **What is a save?** Not a memory dump. A game's state is mostly derived or static: the level's
   thousand renderables came from a `.jlevel` and the paks, the creatures' stat blocks from the
   rulebook, the crowd's paths from the navmesh. What play *changed* is small.
2. **Identity across runs.** An entity handle is an index and a generation (D4), valid for one
   run of the program; a save that stored handles would point at whatever the next run allocated.
3. **A format that lasts.** Fields get added, records get renamed, the rulebook gets rebalanced;
   the file written yesterday must still load, and a damaged file must fail with a reason rather
   than a crash deep inside a system.
4. **No hitch.** Compressing and writing a hundred kilobytes takes milliseconds; a frame has
   sixteen. The player must not see the save.

M16 built the `save` module (the container, the background job, the slots) and gave every system
with state the two methods it needed. The demo saves on **F5**, loads on **F9**, autosaves on a
timer and manages named slots from the pause menu, and a scripted run proves the round trip is
exact: a capture right after a load is the same bytes as the save, chunk for chunk, with 294
skeletons mid-fight.

```
17:06:43  Saved quick (16 KB captured in 3818 us)
17:06:44  loaded quick
17:06:44  save round trip exact: 16840 bytes captured again in 564 us
```

## 2. What a save holds: the dynamic state

Think of the running game as three layers (D112):

| Layer | Where it comes from | In the save? |
|---|---|---|
| **Static** | the paks: meshes, textures, the level's placements and lights, the navmesh, the occlusion grid, the rulebook, the animation clips | no — reloaded |
| **Derived** | computed from the layers below: world transforms from the crowd's positions, the fog's *visible* bits from the viewers, the skeletons' outlines, the GPU's instance buffers, a crowd agent's corridor from its target | no — recomputed |
| **Dynamic** | what play changed: every component row, hit points and conditions, who is engaged and whose turn it is, where each agent stands and where it is going, what each character is playing, the party's selection and formation, what the fog has *explored*, the random generators | **yes** |

The size follows: the demo's level has a thousand renderables, but its save is the 110 KB of
dynamic state for 300 skeletons (18 KB compressed), and would be the same size in a level ten
times richer. The alternative — snapshot everything — was rejected because it would be ten times
the bytes and, worse, could not change: a patched level or rulebook would not show in a loaded
game. "The save holds what changed" is also what makes **patching** possible (§2.3).

A useful test of the boundary: *could the game recompute this from the rest of the save and the
paks, and would the player notice the difference?* A crowd agent's corridor can be recomputed by
asking for the target again (nobody notices); its *target state* cannot (an agent that had
arrived must not start walking). The fog's visible bits are recomputed by the next update
(nobody notices); its explored bits are what the player has seen (everybody would).

### 2.1 Every system writes itself (D113)

There is no serializer. Each class that owns dynamic state has `write(ByteWriter)` and
`read(ByteReader)` next to the arrays, and a `save` module that knew those classes would depend
on all of them. The stream lives in `core` instead — `core.io.ByteWriter` is a growable
little-endian `byte[]` with `int32`, `int64`, `float32`, `bool`, `string` and the bulk methods
`ints`, `floats`, `longs`, `bools` (a count and the elements) — because every module with state
depends on `core` and on nothing else in common. `Crowd` writes its agents; `Visibility` its
explored bits; `Rules` its creatures; the generated registry its stores; the demo's `Party`,
`Combat` and `Animator` theirs. The game composes them (§3.5).

The one rule each `write` follows: **copy, do not compute.** A capture runs on the game thread
between two ticks, when nothing else touches the state, and copies arrays into the writer's
buffer — a `System.arraycopy`-shaped loop per column. What costs time (compression, the file)
happens elsewhere (§2.5).

### 2.2 Identity: stable ids (D114)

Three kinds of index cross a save, and each gets its own answer.

**Agents and creatures keep their indices.** `Crowd.read` puts every saved agent back *in its
slot*; `Creatures.createAt(c, …)` recreates a creature *at its handle*. The game rebuilds its
party and skeletons in the same order on every run, so the demo's `Party.agent[i]`,
`Monster.agent` and `Combat.partyCreature[i]` need no translation at all: skeleton 41 drives
agent 47 and creature 47 before and after. A slot index is stable because the crowd and the rules
have no generation counter — a slot is just a slot — and because the game, not the engine, decides
what fills it.

**Entities get stable ids.** An entity handle has a generation, so the same index may be a
different entity next run, and the component stores are keyed by entity. The game's
`EntityIds` translates: `long idOf(int entity)` at save time and `int entityOf(long id)` at load
time, with a *kind* in the top byte and a *value* in the low 56 bits:

```java
public interface EntityIds {
    long NONE = 0;
    int OBJECT = 1;            // a level object by its object id
    int FIRST_GAME_KIND = 2;   // the game's own kinds
    static long id(int kind, long value) { return (long) kind << 56 | value; }
    long idOf(int entity);
    int entityOf(long id);
}
```

The demo's [`CryptIds`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/CryptIds.java)
has four kinds: a level object (`LevelLoader.placementOf` on the way out, `rootOf` on the way
back), a party member by index, a skeleton by its spawn order, and a fireball by a counter that
restarts at every capture — a fireball is "the same fireball" only within one save, so
`entityOf` creates a fresh entity for it. The engine could not have assigned these: it cannot know
that skeleton 7 is skeleton 7 again; the game can.

`Store.write(w, ids)` writes the entity of every row and every `@EntityRef` column as the
64-bit id, marking the column `ENTITY` in its header so the reader knows those eight bytes hold
an id, not an `int`; `read(r, ids)` asks for the entity back and *drops* a row whose id resolves
to nothing, with a warning — a save from a level that lost an object still loads.

### 2.3 Patching: the rules write by content id (D115)

A creature is built from the rulebook (`buildCharacter`, `buildMonster`): abilities, a class
progression, feats, equipment, each adding typed modifiers. If the save stored the result, a
patch that rebalances the skeletal warrior would not reach the warriors the player has already
met. If the save stored *content rows*, a rulebook edit that inserted a feat would renumber every
row after it. So `Rules.write` stores, per creature, the **id string** of what it was built from
and only the **dynamic overlay**: hit points, temporary hit points and their rounds, spell slots
used, attacks of opportunity used, whether it acted, the feats and spells it knows and the
equipment (as ids), the active conditions (id and rounds left), and the modifiers no rebuild would
recreate — a spell's, the game's own, and everything but conditions on a creature made by hand.

`read` recreates each creature at its handle, rebuilds it from the *current* content
(`fillCharacter`/`fillMonster`, the halves of `buildCharacter`/`buildMonster` that fill a created
creature), then lays the overlay on: equipment that differs is re-equipped, missing feats learned,
conditions re-applied through `applyCondition` so their effects and events happen as they would in
play, and hit points set last, since the rebuild and the equipment set them to the maximum.
`SavesTest.creaturesAreRebuiltFromThePatchedContentWithTheirOldWounds` edits the rulebook's JSON
(the warrior gets 30 hit points instead of 16), cooks it in memory and loads a save made before
the edit: the warrior comes back with 14 more maximum hit points and the same wound.

The random generator's state goes with it (`Rng.saveState`, four longs of xoshiro256** state,
lesson [L02](lessons/02-dice-and-probability.md) §7), so a loaded game rolls the dice the saved
one would have: `assertEquals(a.rng().d20(), b.rng().d20())` ten times in a row.

### 2.4 A format that lasts (D116, D117)

The container is [`formats/jsave.md`](../formats/jsave.md): a 48-byte header with the sizes, the
chunk count and two CRC-32C checksums; a **meta block** of string pairs that is never compressed,
so a slot list reads one small prefix per file; and a **payload** of chunks
`[tag][version][length][reserved]` + data, each padded to 8 bytes, compressed as one zstd frame.
Lesson [L04](lessons/04-binary-formats.md) derives each of those choices — why little-endian, why
pad to 8, why two checksums, where the magic goes — from what a reader has to be able to do with
a file it did not write.

Versions live at three levels, each owned by whoever changes it:

- the **file** version (`SaveFile.FORMAT_VERSION`): the header and the framing;
- the **chunk** version: the game's, saying which systems wrote into the chunk and in what order
  (`CryptSave.VERSION`);
- the **record** format: the system's own, the first `int` of what `Crowd.write` or
  `Rules.write` emits (`Crowd.SAVE_FORMAT`).

A reader keeps every older version readable with a `switch` beside the current code — a
migration is a function per old version — and refuses a *newer* one with a message naming it.
The component stores need no version at all (D111): their header names every column with its
type, the reader matches by name, defaults the missing and skips the unknown. And every version
that shipped keeps a **golden file** under `src/test/resources` with a test that reads it
(`testing.Fixtures`): `v1.jsave`, `rules-v1.bin`, `crowd-v1.bin`. A later build that changes a
layout without a branch for the old one fails those tests.

### 2.5 No hitch: capture, then the job (D118)

A save has two halves with a copy between them:

```
game thread                                 save-job thread (parked between saves)
-----------                                 -------------------------------------
writer.reset()
every system .write(writer)   ~0.5 ms
job.submit(writer, path)      copy ~0.03 ms  ──unpark──▶  encode: zstd + CRCs  ~3 ms
(next frame)                                              write quick.jsave.tmp
                                                          move over quick.jsave (atomic)
poll job.isDone() / error()   ◀──volatile───             target = null
```

The **capture** copies into buffers the `SaveWriter` keeps, so after the first save of a given
size it allocates nothing; the demo's 110 KB capture settles at 0.5–0.9 ms on the CI-class
container (two slow cores) once the JIT has compiled the path, and the test
`SaveSlotsTest.submitAllocatesNothingOnceTheBuffersFit` holds the hand-over at 0 B. The **job**
copies the bytes into its own buffers (so the writer is free at once), then wakes a worker
thread that encodes, writes a temporary file beside the slot and moves it over the slot
atomically: a crash or a full disk halfway leaves the previous save intact
(`SaveSlotsTest.aFailedWriteKeepsThePreviousFileAndReportsTheError`). The game polls; nothing
calls back into the game thread, which owns its state.

Two things were measured rather than assumed. A thread *per save* allocates its object, its
stack and its name, so the worker is one thread for the life of the job, parked with
`LockSupport.park` between saves. And it is a **platform** daemon thread, not a virtual one:
unparking a virtual thread submits its continuation to the scheduler's pool, which wraps it in a
task object — 24 B per save, caught by the allocation test. The JIT matters here too: the first
saves in a run allocate a few hundred bytes each *in the capture*, which the perf report
attributes to C2 compile requests resolving the save path's string literals (D70); after a dozen
saves the path is compiled and the capture is 0 B (chapter [12](12-java-performance.md) §4).

## 3. The code

### 3.1 The stream: `ByteWriter` and `ByteReader`

[`core/io/ByteWriter.java`](../../modules/core/src/main/java/dev/jeddy/core/io/ByteWriter.java):

```java
public void int32(int v) {
    ensure(4);
    put32(size, v);
    size += 4;
}

private void put32(int at, int v) {
    bytes[at] = (byte) v;
    bytes[at + 1] = (byte) (v >>> 8);
    bytes[at + 2] = (byte) (v >>> 16);
    bytes[at + 3] = (byte) (v >>> 24);
}

/** Writes {@code n} and {@code a[0, n)} as raw IEEE-754 bits. */
public void floats(float[] a, int n) {
    int32(n);
    ensure(4 * n);
    for (int i = 0; i < n; i++) {
        put32(size + 4 * i, Float.floatToRawIntBits(a[i]));
    }
    size += 4 * n;
}
```

The byte order is fixed (least significant byte first) whatever the host, which is what makes a
file portable; `floatToRawIntBits` keeps a NaN's payload, so a float round-trips to the bit.
Strings are encoded by hand (`u16` length, UTF-8 from `charAt`), so a capture that writes a
store's column names allocates nothing. The reader checks every read against its end and throws
`ByteReader.Truncated` with the byte offset; a `slice(length)` gives a system a reader that ends
where its chunk ends, so a `read` that runs past its record fails at the boundary rather than
reading the next system's bytes.

### 3.2 A store writes itself

[`components/Store.java`](../../modules/components/src/main/java/dev/jeddy/components/Store.java),
the M22 snapshot with M16's entity translation:

```java
public final void write(ByteWriter w, EntityIds ids) {
    w.string(name);
    int columns = columnCount();
    w.int32(columns);
    for (int c = 0; c < columns; c++) {
        w.string(columnName(c));
        w.int32(isEntityRef(c) ? ENTITY : columnType(c));
    }
    int n = size();
    w.int32(n);
    for (int i = 0; i < n; i++) {
        w.int64(ids.idOf(entities[i]));
    }
    for (int c = 0; c < columns; c++) {
        switch (columnType(c)) {
            case INT -> {
                if (isEntityRef(c)) {
                    for (int i = 0; i < n; i++) {
                        w.int64(ids.idOf(getInt(i, c)));
                    }
                } else {
                    for (int i = 0; i < n; i++) {
                        w.int32(getInt(i, c));
                    }
                }
            }
            ...
```

Column by column, not row by row: the file's layout is the store's SoA layout, so the copy walks
each array once. `read` adds a row per saved entity (`ids.entityOf`, dropped when it resolves to
nothing), then fills column by column, matching by name and type.

### 3.3 The crowd and the fog

[`physics/crowd/Crowd.java`](../../modules/physics/src/main/java/dev/jeddy/physics/crowd/Crowd.java)
writes every active agent in its slot — position, velocity, the parameters, the target state and
target — and not the corridor. `read` removes every agent, places the saved ones back in their
slots, requests their targets again and solves the paths at once (`solveRequests` until the
queue is empty), so a loaded agent walks on the next tick with a state the save had: `ARRIVED`
and `FAILED` are set without a request. Two lines there are easy to miss:

```java
place(i, x, y, z);
// The saved position was on the mesh already: keep its exact bits rather than
// the re-projection's (a few ulps off in y), so that a save made right after a
// load is the same bytes. The corridor starts there too.
px[i] = prevX[i] = x;
py[i] = y;
pz[i] = prevZ[i] = z;
```

`place` snaps to the nearest navmesh point, and the projection of a point that is already on a
triangle onto that triangle returns the point a few ulps off (a plane projection is not exactly
idempotent in floating point). That is harmless for play and fatal for a test that compares bytes;
§5 says why the test is worth keeping. `Visibility.write` is the explored bitset and the grid's
size; `read` takes the bits only when the size matches (a re-cooked occlusion grid would put them
on the wrong cells) and marks every viewer dirty so the next update recomputes the visible bits.

### 3.4 The rules

[`rules/Rules.java`](../../modules/rules/src/main/java/dev/jeddy/rules/Rules.java), the overlay
for one creature:

```java
w.int32(c);
w.int32(creatures.team(c));
w.string(creatures.name(c));
if (characterRow >= 0) {
    w.int8(SAVED_CHARACTER);
    w.string(this.content.id(RulesSchema.CHARACTERS, characterRow));
} else if (monsterRow >= 0) {
    w.int8(SAVED_MONSTER);
    w.string(this.content.id(RulesSchema.MONSTERS, monsterRow));
} else {
    w.int8(SAVED_BLANK);
    ... the base fields of a creature made by hand
}
w.int32(creatures.hp(c));
w.int32(creatures.tempHp(c));
w.int32(creatures.tempHpRounds(c));
for (int l = 0; l < Creatures.SPELL_LEVELS; l++) {
    w.int32(creatures.slotsUsed(c, l));
}
...
writeIds(w, RulesSchema.FEATS, creatures.feats(c));
writeIds(w, RulesSchema.SPELLS, creatures.spells(c));
```

and the modifier filter, which decides what a rebuild recreates:

```java
private boolean keptInSave(int h, boolean contentBuilt) {
    int kind = Modifiers.sourceKind(modifiers.source(h));
    if (kind == Modifiers.SOURCE_CONDITION) {
        return false;            // re-applied with the condition
    }
    if (!contentBuilt) {
        return true;             // nothing rebuilds a hand-made creature
    }
    return switch (kind) {
        case Modifiers.SOURCE_CHARACTER, Modifiers.SOURCE_CLASS, Modifiers.SOURCE_ANCESTRY,
                Modifiers.SOURCE_FEAT, Modifiers.SOURCE_ITEM, Modifiers.SOURCE_MONSTER -> false;
        default -> true;         // a spell's, the game's own
    };
}
```

`Encounter.write` is the order, the initiatives and the join sequence (creature handles, stable
by §2.2), the round, the turn, whether the fight is on, and the action budget; `RealTimeDriver`
got `setClock` for the seconds into the round. `content.id(kind, row)` reads an interned string
table (`RulesData.getString`), so writing ids allocates nothing — `SavesTest` asserts the whole
rules write at 0 B.

### 3.5 The demo: `CryptSave`

[`CryptSave`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/CryptSave.java) is the
composition: one chunk per system, written in the order the state depends on, and read back in
that order:

```java
void capture() {
    long t0 = System.nanoTime();
    writer.reset();
    ids.beginCapture();
    writer.meta("game", "crypt");
    text.setLength(0);
    host.title(text);                       // "Crypt, round 3, 12 slain", into a kept builder
    writer.meta("title", text);
    ...
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
```

The load order is the dependency order: the stores give every skeleton its agent (`COMP`), the
crowd restores the agents (`CROW`), the skeletons rebind them and place the dead bodies (`ENEM`),
the facings land on the entities (`SYNC`), the animations replay (`ANIM`: the demo `Animator`
plays each character's clip again at its saved phase, so a swing continues mid-swing and a corpse
stays down), the rules rebuild the creatures and `Combat` its tables (`RULE`), the party re-derives
who is down (`PRTY`), and the fog comes last. Then `apply` restores the party's viewers from the
crowd, updates the fog and runs the registry's stage 1 (`see` and `show`) so the skeletons' sight
is right before the next frame. A missing chunk (an older save) warns and keeps that system's
values; a newer chunk version is refused.

[`DemoGame.saves`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/DemoGame.java) runs
the frame's requests between ticks: F5 and F9, the menu's buttons, the autosave timer
(`--autosave SECONDS`), and the scripted `--save-at S`, which quick saves at *S* seconds, loads a
second later, captures again and compares with the saved payload (`CryptSave.differenceFrom`:
the first differing chunk, and within `COMP`, the store and the byte). The pause menu's *Saves*
section lists the slots from their meta blocks with *Load* and *Delete*, a name field and *Save
as*; it refreshes when the menu opens and after a save, waiting for the job then (the only place
the game ever waits for a write).

## 4. Why it is built this way

- **Dynamic state only (D112).** Small, patchable, and honest about what a save is: the result
  of play. A snapshot of everything would have been easier to write and impossible to evolve.
- **One stream in `core`, every system writes itself (D113).** The alternative — a `save`
  module that reaches into the systems — would depend on all of them and know their arrays
  better than they do. `DataOutput` is big-endian and stream-shaped; `ByteBuffer` has a fixed
  capacity and a slice per use; Java serialization reflects and owns the format.
- **Stable ids from the game (D114).** The engine cannot know what makes an entity "the same"
  across runs. A global name registry would make every spawn register a string; an engine-wide id
  allocator would have to be saved and restored itself and still could not say that skeleton 7 is
  skeleton 7.
- **Rebuild from content, overlay the rest (D115).** The only way a patch reaches the creatures
  a player has met. The cost is the filter in §3.4 and a load that runs `buildCharacter` per
  creature (microseconds each).
- **A container with checksums and an uncompressed meta block (D116).** A slot list that
  decompressed twenty saves to show twenty titles would be slow and wrong; a file without
  checksums fails inside a system's `read` with a message about the wrong thing.
- **Versions at three levels, golden files under test (D117).** A single global version would
  invalidate every reader's fixture on any change; migration scripts that rewrite the player's
  files were rejected on principle (the player's files are never rewritten).
- **Capture, copy, worker thread, atomic replace (D118).** The frame pays for a copy; the disk
  and zstd are paid for by a thread the frame never waits for. The platform-thread choice came
  from a measurement, not a preference.
- **The user's data directory (D119).** Caches may be cleared; the install directory may be
  read-only. `%APPDATA%` and `$XDG_DATA_HOME` are where the platforms say user data goes.

## 5. Pitfalls met on the way

- **The round-trip check found the real bugs.** Comparing bytes after a load is an exacting
  test, and three of the four things it caught were not test artefacts: the viewers' positions
  were not restored before the fog recomputed (the fog came out as of a second after the save);
  the formation walk in progress was dropped (members arrived unpaced); and a target the agent
  had reached was re-requested (it started walking again). The fourth was the ulps of §3.3,
  fixed by keeping the saved bits. The check runs in the demo on `--save-at`.
- **Derived state in the comparison.** The fog's visible bits are recomputed only when a viewer
  moves more than 0.25 m, so the bits at save time can lag the viewers slightly and a fresh
  recompute after the load differs at the edge. The check sets the threshold to 0, which makes
  the derivation exact; play keeps the threshold.
- **Order matters.** Hit points are set last in `Rules.read`, after the rebuild and the equipment
  set them to the maximum; the crowd's request queue is reset before the saved agents are placed,
  or a load during a pending request overflows the ring; the skeletons' agent bindings are
  rebuilt from the stores *after* the crowd has its agents back.
- **A string allocates.** `Instant.now().toString()`, `Long.toString` and a title concatenation
  cost 1,120 B on every autosave frame until the meta values were formatted into a kept
  `StringBuilder` and the slot paths resolved once; the combat log line is copied char by char.
  What was left (176 B on some saves) was the JIT compiling the save path, gone after a dozen
  saves.
- **Unparking a virtual thread allocates.** 24 B, every time; the worker is a platform thread.

## 6. Performance

| What | Measured | Where |
|---|---|---|
| Capture, 294 skeletons mid-fight (110 KB) | 0.5–0.9 ms once compiled; 8–10 ms on the first save of a run | the headless demo, `--autosave 1`, 2-core CI-class container |
| Capture, 64 skeletons (27 KB) | ~0.5 ms | same |
| Hand-over to the job | 0 B, microseconds | `SaveSlotsTest.submitAllocatesNothingOnceTheBuffersFit` |
| Stored size | 110 KB → 18 KB (6×), 27 KB → 4.6 KB | the demo's files |
| Load (read, decompress, apply, fog, sight), 294 skeletons | 19 ms on the first load of a run (cold code, every creature rebuilt) | the headless demo, `--save-at` |

The perf scene's 0 B/frame budget is kept without autosaves; with `--autosave 3` the first few
save frames show the JIT allocations described in §2.5 and the budget flags them — the same
warm-up effect chapter 12 explains, visible here because the save path runs once every three
seconds rather than every frame.

## 7. Try it

- `./gradlew :save:test :rules:test :physics:test :components:test`: the container and its
  damage cases, the rules' round trip and the patched-content load, the crowd's slots, the
  stores' id translation, the golden files.
- `./gradlew :iso-demo:run --args="--headless --no-audio --enemies 294 --auto-turns --save-at 18 --quit-after 22"`:
  the scripted round trip; look for `save round trip exact`.
- `./gradlew :iso-demo:run --args="--autosave 5 --saves build/saves"`: play, press **F5** and
  **F9**, open the menu with **Esc** and use *Save as* and *Load*; the files appear under
  `build/saves`. Without `--saves` they go to your data directory
  (`%APPDATA%\crypt\saves` or `~/.local/share/crypt/saves`).
- Break a save: open `quick.jsave` in a hex editor, flip a byte in the payload, press **F9**:
  "payload checksum does not match; the file is damaged" in the log and the status line.
- Edit `samples/iso-demo/assets/rules/monsters.json` (the warrior's `hp`), re-cook, load a save
  made before: the warriors' maximum changed, their wounds did not.

## 8. Exercises

1. Add a field to `Mind` (say `float rage`), rebuild, and load a save made before the change:
   which value does the row get, and which line of `Store.read` decides it? *Hint: the column is
   matched by name; D111.*
2. Make `Visibility`'s record format 2 by writing the move threshold after the bits, and keep
   format 1 readable. Which test must you add before the change, and what does it protect?
   *Hint: §2.4; `testing.Fixtures`.*
3. The demo's `Fireball` component holds `caster` as a creature handle. Why does it not need an
   `@EntityRef`-style translation, and under what change to `Combat` would it? *Hint: §2.2, the
   first paragraph.*
4. Save a game with a skeleton mid-respawn (`Mind.state == DEAD`, `timer` counting down) and
   load it: trace which chunk restores the timer, which the body's position, and which the
   hidden flag. *Hint: §3.5's load order; `Enemies.read`.*
5. The capture copies 110 KB in about 0.5 ms, which is 220 MB/s — far below the memory
   bandwidth of any machine. Profile `Store.write` and find where the time goes. *Hint: a virtual
   call per element (`getInt(i, c)`) against a bulk `ints(column, n)`; how would the generated
   store write its columns directly?*
6. Write the save on the game thread instead (`SaveJob.write(SaveFile.encode(...), path)` right
   after the capture) and measure the frame with the perf capture's `max` column. *Hint: the
   perf report's game-thread row; compare 294 and 64 skeletons.*

---

*Code this chapter quotes: `core.io.ByteWriter`, `components.Store`, `components.EntityIds`,
`physics.crowd.Crowd`, `physics.vis.Visibility`, `rules.Rules`, `rules.Encounter`,
`save.SaveWriter`, `save.SaveFile`, `save.SaveJob`, `save.SaveSlots`, the demo's `CryptSave`,
`CryptIds` and `DemoGame`. Decisions: D112–D119 in [DESIGN §17](../DESIGN.md); the format in
[formats/jsave.md](../formats/jsave.md).*
