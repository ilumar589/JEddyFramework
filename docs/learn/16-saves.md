# 16 — Saves: the dynamic state, in chunks, written in the background

*A player presses a key and expects the game to come back, days later and after a patch, exactly
where it was. This chapter builds everything a save needs above the byte stream and the file
container of lesson [L04](lessons/04-binary-formats.md): the decision of what a save holds (and
what it does not), the stable ids that let a save name an entity the next run of the program can
find again, the rule by which the rules rebuild every creature from the current rulebook and lay
the player's wounds over it, the ten chunks of the demo's save in the exact order they are written
and read, the background writer that keeps the frame from hitching, the save slots, and the
demo's quick save, quick load and autosave. It ends with a scripted run that saves, loads a second
later, captures again and proves the round trip exact to the byte with 294 skeletons mid-fight,
and with the measured cost of each half.*

**Before you start.** You need [Lesson 0](lessons/00-foundations.md) §4 (bytes and Java's number
types), §6 (masks, shifts and packing, which the stable ids use), §8.2 (the heap, objects and the
zero-allocation rule), §9.4–9.5 (threads, `volatile` and handing work over whole) and §11 (frames
and budgets). [Lesson 4](lessons/04-binary-formats.md) is the layer under this chapter: it builds
`ByteWriter`, `ByteReader`, `SaveWriter` and `SaveFile` from empty files and derives byte order,
UTF-8, padding, the two CRC-32C checksums, compression, the atomic rename and the three levels of
version; this chapter summarizes it in one paragraph (§3.1) and cites its sections instead of
rebuilding it. Chapter [01](01-architecture.md) (threads, the game loop, the job system and
happens-before) says where a capture runs and why the writer is not a job. Lesson
[L03](lessons/03-data-layout.md) builds the handles and stores whose rows a save holds, and
chapter [22](22-components.md) (components) the generated registry that writes them. Chapters
[09](09-animation-and-skinning.md) (the animator's save), [11](11-physics-navigation-crowds.md) (the
crowd's, the formation walk's and the fog's) and [15](15-rules.md) (the rules) each describe what
their system writes; this chapter links them by topic and does not repeat their code.

**What you will build**

| Piece | File | What it does |
|---|---|---|
| Stable ids | [`components.EntityIds`](../../modules/components/src/main/java/dev/jeddy/components/EntityIds.java), the demo's [`CryptIds`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/CryptIds.java) | names an entity with a `long` the next run can resolve: a kind in the top byte, a value below |
| A store that writes itself | [`components.Store`](../../modules/components/src/main/java/dev/jeddy/components/Store.java) `write`, `read`, `skip` | a self-describing record: columns by name and type, entities and references as ids |
| The rules' overlay | [`rules.Rules`](../../modules/rules/src/main/java/dev/jeddy/rules/Rules.java) `write`/`read`, [`Encounter`](../../modules/rules/src/main/java/dev/jeddy/rules/Encounter.java), [`ActionBudget`](../../modules/rules/src/main/java/dev/jeddy/rules/ActionBudget.java), the demo's [`Combat`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/Combat.java) | creatures by content id plus what play changed, rebuilt from the current rulebook at the same handle |
| The world's records | [`app.world.AgentSync`](../../modules/app/src/main/java/dev/jeddy/app/world/AgentSync.java), the demo's [`Enemies`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/Enemies.java), [`Party`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/Party.java), [`Spells`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/Spells.java), [`DemoGame`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/DemoGame.java) | facings, bodies, the party, the cast counter, the camera and the generator |
| The composition | [`CryptSave`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/CryptSave.java) | ten chunks, written in the order they are read back; the load order and the whole-state check |
| The background writer | [`save.SaveJob`](../../modules/save/src/main/java/dev/jeddy/save/SaveJob.java) | copies the capture, wakes one parked thread, encodes and replaces the file atomically; the game polls |
| The slots | [`save.SaveSlots`](../../modules/save/src/main/java/dev/jeddy/save/SaveSlots.java), [`SaveDirectory`](../../modules/save/src/main/java/dev/jeddy/save/SaveDirectory.java), [`SaveFormatException`](../../modules/save/src/main/java/dev/jeddy/save/SaveFormatException.java) | `quick`, `auto` and named files in the user's data directory, listed from their meta blocks |
| The demo's glue | `DemoGame.saves`, the pause menu in [`DemoUi`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/DemoUi.java) | F5, F9, the autosave timer, *Save as*, *Load*, *Delete*, and the scripted `--save-at` round trip |
| The proof | [`SaveSlotsTest`](../../modules/save/src/test/java/dev/jeddy/save/SaveSlotsTest.java), [`SavesTest`](../../modules/rules/src/test/java/dev/jeddy/rules/SavesTest.java), [`GeneratedGameTest`](../../modules/components/src/test/java/dev/jeddy/components/GeneratedGameTest.java), [`CrowdTest`](../../modules/physics/src/test/java/dev/jeddy/physics/crowd/CrowdTest.java), [`FormationTest`](../../modules/physics/src/test/java/dev/jeddy/physics/crowd/FormationTest.java), [`VisibilityTest`](../../modules/physics/src/test/java/dev/jeddy/physics/vis/VisibilityTest.java), [`AgentSyncTest`](../../modules/app/src/test/java/dev/jeddy/app/world/AgentSyncTest.java) and the golden files | round trips, patched content, slots and failures, zero allocation, and the bytes old versions wrote |

**Contents**

- Part A — The ideas
  1. [Goal and result](#1-goal-and-result)
  2. [What a save holds: static, derived, dynamic](#2-what-a-save-holds-static-derived-dynamic)
  3. [Every system writes itself, on top of lesson 4](#3-every-system-writes-itself-on-top-of-lesson-4)
  4. [Identity across runs](#4-identity-across-runs)
  5. [Chunks and versions](#5-chunks-and-versions)
  6. [Threads: the capture, the job, and what the job system does not do](#6-threads-the-capture-the-job-and-what-the-job-system-does-not-do)
  7. [Where the files live](#7-where-the-files-live)
- Part B — Building it
  8. [Step 1: stable ids](#8-step-1-stable-ids)
  9. [Step 2: a store writes itself](#9-step-2-a-store-writes-itself)
  10. [Step 3: the rules by content id, plus the overlay](#10-step-3-the-rules-by-content-id-plus-the-overlay)
  11. [Step 4: the world's records](#11-step-4-the-worlds-records)
  12. [Step 5: the composition, `CryptSave`](#12-step-5-the-composition-cryptsave)
  13. [Step 6: the background writer, `SaveJob`](#13-step-6-the-background-writer-savejob)
  14. [Step 7: slots and the directory](#14-step-7-slots-and-the-directory)
  15. [Step 8: the demo's keys, menu, autosave and scripted check](#15-step-8-the-demos-keys-menu-autosave-and-scripted-check)
- Part C — Using it and proving it
  16. [A save and a load, end to end](#16-a-save-and-a-load-end-to-end)
  17. [The tests, walked through](#17-the-tests-walked-through)
  18. [Performance](#18-performance)
  19. [Pitfalls, known problems and two open decisions](#19-pitfalls-known-problems-and-two-open-decisions)
  20. [Things to run](#20-things-to-run)
- [Exercises](#exercises)
- [Glossary](#glossary)

---
# Part A — The ideas

## 1. Goal and result

A CRPG is played over many sittings. The player expects to **quick save** on a key and have the
game exactly where it was, to find an **autosave** after a crash, to keep **named saves** before a
hard fight, and to load a save made last month into a game that has been patched since. Behind
those four sentences are the four hard parts of a save system:

1. **What is a save?** Not a memory dump. A game's state is mostly derived or static: the level's
   thousands of renderables came from a `.jlevel` and the paks, the creatures' stat blocks from the
   rulebook, the crowd's paths from the navmesh. What play *changed* is small (§2).
2. **Identity across runs.** An entity handle is an index and a generation (lesson
   [L03](lessons/03-data-layout.md) §6), valid for one run of the program; a save that stored
   handles would point at whatever the next run allocated (§4).
3. **A format that lasts.** Fields get added, records get renamed, the rulebook gets rebalanced;
   the file written yesterday must still load, and a damaged file must fail with a reason rather
   than a crash deep inside a system (§5, and all of lesson L04).
4. **No hitch.** Compressing and writing a hundred kilobytes takes milliseconds; a frame has
   sixteen (L00 §11). The player must not see the save (§6).

Milestone M16 built the `save` module (the container, the background job, the slots) and gave every
system with state the two methods it needed. The demo saves on **F5**, loads on **F9**, autosaves on
a timer and manages named slots from the pause menu. A scripted run proves the round trip is exact:
a capture right after a load is the same bytes as the save, chunk for chunk. Run on the two-core
container this guide is written on, headless, with 294 skeletons mid-fight (§20 has the command):

```
14:30:12.560  Saved quick (109 KB captured in 6454 us)
14:30:13.571  loaded quick in 21357 us
14:30:13.579  save round trip exact: 111904 bytes captured again in 2962 us
```

Those three lines are the chapter: a **capture** of 111,904 bytes on the game thread (6.5 ms on
the first save of a run, under a millisecond once the JIT has compiled the path, §18), a **load**
of 21 ms that rebuilds 300 creatures from the rulebook, and a second capture that is
**byte-for-byte identical** to the first — the test that found the real bugs (§19).

## 2. What a save holds: static, derived, dynamic

### 2.1 The three layers

Think of the running game as three layers (**D112**, decided with the owner on 2026-10-12):

| Layer | Where it comes from | In the save? |
|---|---|---|
| **Static** | the paks: meshes, textures, the level's placements and lights, the navmesh, the occlusion grid, the rulebook, the animation clips | no — reloaded |
| **Derived** | computed from the layers below: world transforms from the crowd's positions, the fog's *visible* bits from the viewers, the skeletons' outlines, the GPU's instance buffers, a crowd agent's corridor from its target | no — recomputed |
| **Dynamic** | what play changed: every component row, hit points and conditions, who is engaged and whose turn it is, where each agent stands and where it is going, what each character is playing, the party's selection and formation, what the fog has *explored*, the random generators | **yes** |

### 2.2 The boundary test

A useful test of the boundary: *could the game recompute this from the rest of the save and the
paks, and would the player notice the difference?*

- A crowd agent's **corridor** (its path through the navmesh) can be recomputed by asking for the
  target again; nobody notices. Its **target state** cannot: an agent that had *arrived* must not
  start walking again, and one whose path had *failed* must not retry forever. So the crowd saves
  the target and its state, not the corridor (chapter [11](11-physics-navigation-crowds.md), on the
  crowd's save format).
- The fog's **visible** bits are recomputed by the next update from the viewers' positions; nobody
  notices. Its **explored** bits are what the player has seen; everybody would.
- A character's **joint matrices** are recomputed from its clip and phase every frame; the **phase**
  and which clip is playing are what the player sees (chapter [09](09-animation-and-skinning.md), on
  saving animation state).
- A creature's **armour class** is the sum of its base, its equipment and its conditions; the rules
  recompute it. Its **hit points** are the history of the fight; nothing recomputes them (§4.4).

### 2.3 What that buys: size, and patching

The size follows. The demo's crypt with 294 skeletons has 3,222 renderables, 300 crowd agents and
15 lights (the build log of §1's run); its save is 111,904 bytes of dynamic state, written as a
16,723-byte file (16,547 bytes of compressed payload behind the 48-byte header and the 128-byte meta
block), and would be the same size in a level ten times richer. With the default 64 skeletons it is
28,856 bytes, a 5,363-byte file.

The rejected alternative — snapshot everything — would be ten times the bytes and, worse, could not
*change*: a patched level or a rebalanced rulebook would not show in a loaded game, because the
save would carry the old copy. "The save holds what changed" is also what makes **patching**
possible: a loaded game is *the current content plus the player's changes to it* (§4.4). That is
the honest definition of a save, and it is why the chapter spends most of its length on the
question "what changed, and how do I name it?".

## 3. Every system writes itself, on top of lesson 4

### 3.1 What lesson 4 built

[Lesson L04](lessons/04-binary-formats.md) built the whole layer under this chapter, and this
chapter does not rebuild it. In one paragraph: `core.io.ByteWriter` is a growable little-endian
`byte[]` with `int8`…`int64`, `float32`, `float64`, `bool`, `string` (a `u16` length and UTF-8,
encoded by hand so that it allocates nothing) and the bulk methods `ints`, `longs`, `floats`,
`bools`, `bytes` (a count and the elements), kept and `reset()` between uses (L04 §9);
`core.io.ByteReader` reads it back, checks every size before trusting it, throws `Truncated` with
the byte offset, and hands out a `slice(length)` so a system cannot read past its own record (L04
§10). `save.SaveWriter` captures **meta entries** (string pairs) and **chunks** — a four-letter
tag, a version, a length patched in when the chunk closes, and padding to 8 bytes (L04 §11–12).
`save.SaveFile.encode` turns a capture into a `.jsave` file: a 48-byte header with the sizes and
two CRC-32C checksums, the uncompressed meta block, the payload as one zstd frame (L04 §13);
`SaveFile.read` checks the magic, the header checksum, the version, every size, and the payload
checksum before decompressing — *fail at the check that explains the failure* (L04 §14) — and
`SaveFile.peek` reads only the header and the meta block, for the slot list. Writing goes through
a temporary file and an atomic rename (L04 §15). Versions live at three levels, and every version
that shipped keeps a **golden file** under test (L04 §8, §16). The format itself is specified in
[formats/jsave.md](../formats/jsave.md) and a real file is read byte by byte in L04 §17.

### 3.2 No serializer, and no `save` module that knows the systems (D113)

There is no serializer in the engine. Each class that owns dynamic state has `write(ByteWriter)`
and `read(ByteReader)` next to the arrays they copy: `Crowd` writes its agents, `Visibility` its
explored bits, `Rules` its creatures, `AgentSync` its facings, the generated registry its stores,
the demo's `Party`, `Enemies`, `Animator`, `Combat` and `Spells` theirs. The game composes them
(§12).

*Why not a `save` module that reaches into every system?* It would have to depend on `physics`,
`rules`, `components`, `app` and the game, and know their arrays better than they do; a change to
one array would be a change in two modules. The stream lives in `core` instead, because every
module with state depends on `core` and on nothing else in common (DESIGN §21, D113). *Why not
Java serialization?* It runs code from the file while reading it, reflects, owns the format, and
breaks when a class changes (D111). *Why not `java.io.DataOutput`?* Big-endian by specification,
stream-shaped, and the stream chain allocates per call. *Why not `ByteBuffer`?* A fixed capacity
and a slice per use (L04 §9.2).

The `save` module therefore knows **no game system**. Its `module-info`
(`modules/save/src/main/java/module-info.java`) requires `core` and `compress` and nothing else:

```java
module dev.jeddy.save {
    requires transitive dev.jeddy.core;
    requires dev.jeddy.compress;

    exports dev.jeddy.save;
}
```

`transitive` because a game that uses `SaveWriter` writes into `core.io.ByteWriter`, so it must see
`core` whenever it sees `save`.

### 3.3 Copy, do not compute

The one rule every `write` follows: **copy, do not compute.** A capture runs on the game thread
between two ticks, when nothing else touches the state (§6.1), and copies arrays into the writer's
buffer — a loop per column, the shape of `System.arraycopy`. What costs time (compression, the
file) happens on another thread (§6). A `write` that computed something — re-solved a path,
rebuilt a creature, formatted a string — would both cost frame time and risk changing the state it
is supposed to photograph. Every `write` in this chapter also **allocates nothing**, proven by an
`Allocations.assertNoAllocation` test (§17); the one `write` that writes strings (`Rules.write`,
§10) reads them from an interned string table, so even that is a copy.

## 4. Identity across runs

### 4.1 Three kinds of index

Three kinds of index cross a save, and each gets its own answer (**D114**):

| Index | Example | Across a save |
|---|---|---|
| A **slot** in a system that has no generation counter | crowd agent 47, creature 47 | stays the same slot: the system reads into it (§4.2) |
| An **entity handle** (index + generation) | the root entity of skeleton 41 | translated to a **stable id** by the game (§4.3) |
| A **content row** in the rulebook | row 2 of the monsters table | never stored: the rules write the row's **id string** (§4.4) |

### 4.2 Slots that stay

**Agents and creatures keep their indices.** `Crowd.read` puts every saved agent back *in its
slot* (chapter [11](11-physics-navigation-crowds.md)); `Creatures.createAt(c, …)` recreates a
creature *at its handle* (§10). The game rebuilds its party and skeletons in the same order on every
run — six party members first, then skeleton 0, 1, 2, … — so the demo's `Party.agent[i]`,
`Monster.agent` and `Combat.partyCreature[i]` need no translation at all: skeleton 41 drives agent
47 and creature 47 before and after.

A slot index is stable because the crowd and the rules have **no generation counter** — a slot is
just a slot, and nothing checks whether its occupant is "the same" — and because the game, not the
engine, decides what fills it. That is a property of the demo's spawn order, not of the engine, and
§10's `Combat.read` says so in its Javadoc: "the party at `0..5`, skeleton `i` at `6 + i`, as they
were built".

### 4.3 Stable ids: a kind and a value in one `long`

Entities are different. An entity handle carries a generation (L03 §6) precisely so that a stale
handle *fails*; the same index may be a different entity in the next run, and the component stores
are keyed by entity. So a store cannot write handles. It writes **stable ids**, and the game
translates: `long idOf(int entity)` at save time, `int entityOf(long id)` at load time.

An id packs two numbers into one `long`, the way L00 §6.4 packed dice into an `int`:

```
bit:   63 ........ 56 | 55 ...................................................... 0
       kind (8 bits)  | value (56 bits)
```

$$\text{id}(k, v) = k \cdot 2^{56} + v, \qquad k(\text{id}) = \lfloor \text{id} / 2^{56} \rfloor, \qquad v(\text{id}) = \text{id} \bmod 2^{56}.$$

In Java the multiplication by $2^{56}$ is `(long) kind << 56` (L00 §6.3), the sum is `|` because the
two fields do not overlap, the division is `>>> 56` and the remainder is a mask of 56 ones,
`0x00FF_FFFF_FFFF_FFFFL` (L00 §6.5). Worked, with the demo's kinds (`PARTY` = 2, `SKELETON` = 3,
`FIREBALL` = 4, and the engine's `OBJECT` = 1):

| Id of | `kind` | `value` | `(long) kind << 56 \| value` |
|---|---|---|---|
| party member 3 | 2 | 3 | `0x0200_0000_0000_0003` |
| skeleton 41 | 3 | 41 | `0x0300_0000_0000_0029` |
| level object 17 | 1 | 17 | `0x0100_0000_0000_0011` |
| the first fireball | 4 | 0 | `0x0400_0000_0000_0000` |
| nothing (`NONE`) | 0 | 0 | `0` |

Unpacking `0x0200_0000_0000_0003`: `>>> 56` drops the low 56 bits and leaves `0x02` = 2 (the shift is
unsigned, so a kind of 255 does not come back negative); `& 0x00FF_FFFF_FFFF_FFFFL` keeps the low
56 bits, 3. Why 8 and 56 rather than 32 and 32? A kind is a small enum (the demo has four); a value
may be a large counter. $2^{56} - 1 = 72{,}057{,}594{,}037{,}927{,}935$ fireballs is enough, and a
hex dump shows the kind in the top byte at a glance. Why not two `int`s? One `long` is one field,
one write and one read, and the id `0` is "nothing" in every kind at once.

The engine defines the packing, kind 1 (a **level object**, by the object id the level file gave
it) and the identity mapping for tests; the game defines every other kind, because *the engine
cannot know that skeleton 7 is skeleton 7 again; the game can.* §8 builds both halves. Rejected
(D114): saving handles (valid for one run), a global registry of names (every spawn would register a
string), and an engine-wide id allocator (it would have to be saved and restored itself, and still
could not say which skeleton is which).

### 4.4 Creatures by content id, and the overlay (D115)

A creature is built from the rulebook: `Rules.buildCharacter` and `buildMonster` take a content row
and add abilities, a class progression, feats, equipment, each contributing typed modifiers
(chapter [15](15-rules.md)). Three ways to save it:

1. **Dump the creature's tables.** Every modifier, every stat. A patch that rebalances the
   skeletal warrior would not reach the warriors the player has already met: the save carries the
   old numbers. Rejected.
2. **Store the content row.** "Monster row 2." A rulebook edit that inserts a monster before it
   renumbers every row after it, and the save now builds the wrong creature. Rejected.
3. **Store the content id and the overlay.** "`skeletalWarrior`", plus only what play changed: hit
   points, temporary hit points and their rounds, spell slots used, attacks of opportunity used,
   whether it acted this round, the feats and spells it knows and its equipment (as ids), the
   active conditions (id and rounds left), and the modifiers no rebuild would recreate. **Chosen.**

A load recreates the creature at its handle, rebuilds it from the *current* content, and lays the
overlay on: a monster rebalanced in a patch comes back with the new stat block and its old wounds
(§10, and the test `creaturesAreRebuiltFromThePatchedContentWithTheirOldWounds`, §17.2). The random
generator's state goes with it — four `long`s of xoshiro256\*\* state, [L02](lessons/02-dice-and-probability.md)
§12.3 — so a loaded game rolls the dice the saved one would have.

## 5. Chunks and versions

### 5.1 The container in one paragraph

A `.jsave` file is a header, a meta block and a payload of chunks (L04 §11, **D116**). The meta
block is a list of string pairs that is never compressed, so a slot list reads one small prefix per
file and decompresses nothing. The payload is one zstd frame of chunks, each `[tag][version]
[length][reserved]` plus data padded to 8 bytes. A reader hands each system a `ByteReader` that is
a slice of its own chunk: a `read` that runs past its record fails at the boundary, not in the next
system's bytes. Rejected (D116): one zstd frame over everything (listing twenty slots would
decompress twenty saves), JSON (ten times the size, and parsing allocates), and the `.jpak` format
(built for mapping assets read-only, not for writing a state).

### 5.2 One chunk per system: the demo's ten

The demo's save has ten chunks, written in the order they are read back:

| Tag | Written by | Holds | Bytes, 294 skeletons |
|---|---|---|---|
| `GAME` | `DemoGame` | play time, the follow flag, the camera, the demo's generator | 65 |
| `COMP` | the generated registry | every component store, entities as `CryptIds` | 25,665 |
| `CROW` | `Crowd` | every active agent in its slot: position, velocity, parameters, target | 25,972 |
| `ENEM` | `Enemies` | alive and kills, each skeleton's body position and facing | 4,716 |
| `SYNC` | `AgentSync` | the facing of every agent slot | 2,708 |
| `ANIM` | `Animator` | each character's mode, stance, action and clip phase | 4,204 |
| `RULE` | `Combat` | `Rules` (creatures by content id + the overlay, the generator), `Encounter`, the clock, the fight's own state | 45,589 |
| `PRTY` | `Party` | selection, formation, cooldowns, the formation walk in progress | 198 |
| `SPEL` | `Spells` | the cast counter | 4 |
| `FOG ` | `Visibility` | the grid's size and the explored bits | 2,584 |

The byte counts are from §1's save, read back with the engine's own reader (§20). There is no
`FORM` chunk: the formation walk (`FormationMove`, chapter [11](11-physics-navigation-crowds.md))
is written *inside* `PRTY`, because the party owns the mover and reads it after its own fields.
§12 gives every chunk's exact layout and derives those counts.

What is **not** in the list says as much. Quests, dialogue and script flags have `write`/`read`
of the same shape in the `script` module (`Quests`, `Conversation`, `Flags`; §11.8) for a game that
uses them; the demo has no quests, so it writes no such chunk. A screenshot for the slot list, the
editor's state and the undo history are explicitly *not in M16* (DESIGN §21).

### 5.3 Three levels of version, and who owns each (D117)

Versions live at three levels, each owned by whoever changes it (L04 §8):

- the **file** version (`SaveFile.FORMAT_VERSION`, today 1): the header and the chunk framing;
  owned by the `save` module; changes rarely, because the header has reserved space and flag bits;
- the **chunk** version (the `version` field of each chunk header; the demo writes
  `CryptSave.VERSION` = 1 into every chunk): the game's, saying which systems wrote into the chunk
  and in what order (`RULE` holds four records back to back, §10.4);
- the **record** format: the system's own, the first `int` of what `Crowd.write`, `Rules.write`,
  `Visibility.write`, `AgentSync.write` and `FormationMove.write` emit (`SAVE_FORMAT` = 1 in each).

A reader keeps every older version readable with a `switch` beside the current code — a migration
is a function per old version — and refuses a *newer* one with a message naming it ("crowd save
format 2; this build reads 1"). The component stores need no version at all (D111): their header
names every column with its type, the reader matches by name, defaults the missing and skips the
unknown (§9). Rejected (D117): a single global save version (one bump invalidates every reader's
fixtures) and migration scripts that rewrite the player's files (the player's files are never
rewritten).

One wrinkle to know, since it is a decision the owner may revisit: `CryptSave` uses **one**
constant, `VERSION`, for all ten chunk headers. Bumping it because `Combat` added a field to `RULE`
marks the other nine chunks "version 2" as well, and a reader that must accept version 2 of `RULE`
accepts version 2 of everything. It works — the reader refuses only a version *greater* than its
own, per chunk — but it gives up the independence D117 was written for. A constant per chunk
(`RULE_VERSION`, `PRTY_VERSION`, …) would keep it; exercise 8 asks for the change.

### 5.4 Golden files

What protects "an older version still loads" is not the `switch` but a test that reads bytes an
old version *actually wrote*: a **golden file** committed under `src/test/resources`, written once
through `testing.Fixtures.bytesOrCreate` and never regenerated (L04 §16.2). Three exist today:
`v1.jsave` (the container), `crowd-v1.bin` (the crowd's record, chapter 11) and `rules-v1.bin`
(the rules' and the encounter's records, decoded in §17.2). A later build that changes a layout
without a branch for the old one fails the test that reads it.

## 6. Threads: the capture, the job, and what the job system does not do

### 6.1 Where a capture runs

Chapter [01](01-architecture.md) (the game loop) has the game thread run, every frame, the input,
then as many fixed ticks as real time requires, then the once-per-frame `Game.update`, then
`extract`. The demo's `DemoGame.update` calls `saves(dt)` (§15) after its input handling and the
registry's per-frame behaviours and before the animator's update. So a capture runs **after the
frame's last tick and before extract**, on the game thread:

- no tick is in progress, so no system is halfway through a step;
- every job graph a tick or the update launched has completed (`JobGraph.run` waits, chapter 01
  on the job system), so the workers touch nothing;
- the main thread reads only the packet the game thread published last frame, never the state.

That is what "between two ticks, when nothing else touches the state" means in §3.3, and it is why
a `write` may read any array without a lock.

### 6.2 Why the file is not written on the game thread

A save has two halves with a copy between them (**D118**):

```
game thread                                       save-job thread (parked between saves)
-----------                                       -------------------------------------
writer.reset()
every system .write(writer)      0.6–1.1 ms *
job.submit(writer, path)         copy, 15 µs *   ──unpark──▶  SaveFile.encode: zstd + 2 CRCs
(the frame goes on)                                            Files.write(quick.jsave.tmp)
                                                               Files.move(... ATOMIC_MOVE)
poll job.isDone() / error()     ◀── volatile ──                target = null
```

\* measured, §18, once the JIT has compiled the path (the first save of a run costs 6–14 ms to
capture and 17 ms to encode and write: cold code, the compressor's first use).

Writing on the game thread was rejected because a file write is milliseconds and **unbounded**: a
spinning disk waking up, an antivirus scanning the new file, a network share. Compression is
bounded but not free. Neither belongs in a frame that has a 33 ms tick budget and a 0 B allocation
budget. So the game thread does the one thing only it can do — photograph the state while it is
whole — and hands the bytes over.

### 6.3 Why not a job on the job system

The engine already has worker threads. They are the wrong tool here, for the reason chapter 01's
thread table gives: workers "must never block on IO". A kernel that called `Files.write` would hold a
worker for milliseconds while the crowd's or the renderer's parallel loops waited for it, and the
job system has no notion of "this job may block": a worker that blocks is a core that the frame has
lost. The asset loader solves its blocking reads with **virtual threads** (D26, chapter
[10](10-assets.md)), which are cheap to block — and that was the first design of the save job too.
It lost to a measurement (§6.4).

What the job system *does* do for saves is on the **load** side: `CryptSave.apply` (§12) runs
`world.updateTransforms(jobs)`, `fog.update()` and the registry's `see` stage on the workers, which
is fine because a load runs on the game thread, synchronously, with nothing else in flight.

### 6.4 Why one platform thread, parked (the 24 bytes, derived)

Three designs were measured, not argued about:

- **A thread per save** allocates its `Thread` object, its name and its stack on every save.
  Rejected.
- **A virtual thread as the worker**, unparked on each save: 24 B allocated per save on the game
  thread, caught by `SaveSlotsTest.submitAllocatesNothingOnceTheBuffersFit` (§17.1). Rejected.
- **One platform daemon thread for the life of the job**, parked between saves: 0 B. **Chosen.**

Where do the 24 bytes come from? A virtual thread is a **continuation** — the saved stack and
program position of a computation that can be suspended and resumed later — that the JDK runs, when
it is ready, on one of a small pool of ordinary platform threads called **carrier threads**. Waking
a virtual thread therefore means handing its continuation to that pool as a task. In JDK 27's
`java.lang.VirtualThread`, `unpark()` on a parked virtual
thread calls `submitRunContinuation()`, which submits the thread's `runContinuation` task to the
scheduler through `ForkJoinPool.submit(ForkJoinTask.adapt(task))` — and `ForkJoinTask.adapt`
allocates `new AdaptedRunnableAction(runnable)`. Size it with L00 §8.2 (an 8-byte header, the
total rounded up to a multiple of 8) and L03 §3 (a reference field is 4 bytes, a compressed
pointer): the header (8), the inherited `int status` (4), the inherited `Aux aux` reference (4), its
own `Runnable runnable` reference (4) — 20 bytes, rounded up: **24**. Unparking a platform thread, by
contrast, goes through `Unsafe.unpark` to a native condition variable and creates no Java object.
The engine's rule is 0 B per frame, and an autosave frame is a frame; 24 B is a failing test.

Why **parked** (`LockSupport.park`) and not sleeping or spinning: a save happens seconds apart, so
a spinning worker would burn a core for nothing, and a sleeping one would add its sleep to every
save's latency. A parked thread costs nothing until it is unparked, and an unpark costs
microseconds (L00 §9.4) — once per save. Why a **daemon**: a daemon thread does not keep the JVM
alive, so a game that exits with the job idle exits at once. (The cost of that choice is in §19:
the demo does not wait for a save in flight at exit.)

### 6.5 Publishing the buffers: happens-before, derived

The game thread copies bytes into the job's arrays and the worker reads them. L00 §9.5 and chapter
[01](01-architecture.md) (on happens-before) give the rule: a write to a `volatile` field
*happens-before* every later read of that field, and everything the writing thread did before the
volatile write is visible to the reading thread after its read. `SaveJob` has exactly one such
field for the request, `target`:

```
game thread                              worker thread
System.arraycopy(... → payload)   (1)
target = path                     (2)  ── happens-before ──▶  Path t = target;      (3)
                                                              encode(meta, payload)  (4)
```

(1) comes before (2) in program order; (2) is a volatile write that (3), a volatile read of the
same field that sees the value `path`, synchronizes with; (3) comes before (4) in program order. By
transitivity (1) happens-before (4): the worker sees the copied bytes, and the plain fields
`metaSize`, `payloadSize`, `chunkCount` and `level` with them. The same argument in the other
direction, through `target = null` in the worker's `finally` and `isDone()`'s read, publishes
`bytesWritten`, `writeNanos` and `error` to the game thread. §13 walks the code.

The second thing the hand-over needs is that the game thread **does not overwrite the arrays while
the worker reads them**. `submit` begins with `await()`, which returns only when `target` is
`null` — that is, when no write is in progress — so the copy never races the encode. And there is no
lost wake-up: `LockSupport.unpark` leaves a *permit* if the worker has not parked yet, and its next
`park` returns at once. (The job system's timed parks in chapter 01 exist because its waiters have
no such single flag to re-check; the save job has one.)

### 6.6 What a crash does

Three failures, three outcomes (L04 §15 has the rename):

| Failure | What is on disk | What the game sees |
|---|---|---|
| An `IOException` on the worker (a full disk, a directory where the temporary file should go) | the **old** `quick.jsave`, untouched; maybe a stray `quick.jsave.tmp` | `error()` is the exception, `bytesWritten()` is 0, a warning in the log; the next save overwrites the `.tmp` |
| The process dies mid-write (a crash, a kill) | the old `quick.jsave` or the complete new one, never a mix — the rename is atomic; maybe a stray `.tmp` | nothing: the daemon thread died with the process |
| A power cut after the rename | possibly a renamed file whose bytes never reached the disk | "payload checksum does not match" on the next load: detected, not loaded |

The third row is one of this chapter's two **open decisions** (§19.3): the temporary file is not
forced to disk before the rename.

## 7. Where the files live

`SaveDirectory.of("crypt")` (§14) is `%APPDATA%\crypt\saves` on Windows and
`$XDG_DATA_HOME/crypt/saves` — by default `~/.local/share/crypt/saves` — on Linux, and the system
property `jeddy.saves.dir` overrides both (**D119**). Rejected: the cache directory (a cache may be
cleared by the system or the user) and the game's install directory (not writable under *Program
Files*, and not the player's data). The slots in it are `quick.jsave`, `auto.jsave` and
`slot-<name>.jsave` with the name reduced to letters, digits, dashes and underscores; the demo's
`--saves DIR` points them elsewhere for tests.

---
# Part B — Building it

The order below is the order a reader would write the code: first the naming of entities, which
the stores need; then the stores; then the rules, the largest record; then every other system's
two methods; then the composition that calls them; then the writer thread, the slots and the demo's
keys. Each class is introduced by its problem, then its fields, then its methods as the repository
has them.

## 8. Step 1: stable ids

### 8.1 The interface the engine owns

[`components.EntityIds`](../../modules/components/src/main/java/dev/jeddy/components/EntityIds.java)
is an interface with two methods and a handful of static helpers. The constants first:

```java
public interface EntityIds {
    /** The id of nothing: a null handle on save, an unresolvable id on load. */
    long NONE = 0;

    /** Kind 1: a level object by its object id. */
    int OBJECT = 1;

    /** The first kind a game may define. */
    int FIRST_GAME_KIND = 2;
```

- **`NONE` is 0** in every kind at once, because kind 0 is reserved: `id(0, v)` is refused below.
  A null handle (`0`, L03 §6) saves as `NONE`, and an id that nothing answers to loads as entity
  `0`.
- **`OBJECT` is the one kind the engine defines**: a level object has an id in its `.jlevel`
  (chapter [14](14-editor.md), on the level format's stable ids), and `LevelLoader.placementOf`
  / `rootOf` translate between that id and the entity it spawned. Every other kind is the game's.

The packing of §4.3, with its checks:

```java
    /** Builds an id from a kind (1..255) and a value (0..2^56 − 1). */
    static long id(int kind, long value) {
        if (kind <= 0 || kind > 255) {
            throw new IllegalArgumentException("kind " + kind);
        }
        if (value < 0 || value >>> 56 != 0) {
            throw new IllegalArgumentException("value " + value);
        }
        return (long) kind << 56 | value;
    }

    /** The id of level object {@code objectId}. */
    static long object(int objectId) {
        return id(OBJECT, objectId);
    }

    /** The kind of {@code id} (0 for {@link #NONE}). */
    static int kind(long id) {
        return (int) (id >>> 56);
    }

    /** The value of {@code id}. */
    static long value(long id) {
        return id & 0x00FF_FFFF_FFFF_FFFFL;
    }
```

- `value >>> 56 != 0` is the cheap way to ask "does the value need more than 56 bits?": shifting
  the 56 low bits off leaves exactly the bits that would collide with the kind. A negative `value`
  is refused separately, because `>>>` of a negative `long` is non-zero anyway but the message
  should say why.
- The cast `(long) kind` comes **before** the shift: `kind << 56` on an `int` would use only the
  low 5 bits of the distance (L00 §6.3) and shift by 24 instead.
- `kind` returns `int` because a kind is a small enum; `value` stays a `long`.

Then the two methods a game implements, and the identity mapping for tests:

```java
    /** Translates handles to themselves (as ids) and back: for saves read in the same run. */
    EntityIds HANDLES =
            new EntityIds() {
                @Override
                public long idOf(int entity) {
                    return entity == 0 ? NONE : entity & 0xFFFFFFFFL;
                }

                @Override
                public int entityOf(long id) {
                    return (int) id;
                }
            };
```

and, after the static helpers shown above, the two methods themselves:

```java
    /** The stable id of {@code entity}, or {@link #NONE} for an entity the save cannot name. */
    long idOf(int entity);

    /**
     * The live entity for {@code id}, created if the game wants it so, or {@code 0} when nothing
     * answers to it.
     */
    int entityOf(long id);
}
```

`HANDLES` writes the handle's 32 bits as the id: `entity & 0xFFFFFFFFL` masks because a handle with
its top bit set would otherwise sign-extend into 64 ones (the trap of L04 §2.3, one size up). Its
`kind` is 0, which is fine *within one run*: nothing decodes it. The generated registry's
one-argument `write(ByteWriter)` and `read(ByteReader)` use it, so a test can round-trip a game
without inventing ids.

### 8.2 The demo's four kinds: `CryptIds`

[`CryptIds`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/CryptIds.java) answers the
question the engine cannot. Its fields:

```java
final class CryptIds implements EntityIds {
    static final int PARTY = FIRST_GAME_KIND;
    static final int SKELETON = FIRST_GAME_KIND + 1;
    static final int FIREBALL = FIRST_GAME_KIND + 2;

    private final LevelLoader loader;
    private final Party party;
    private final Enemies enemies;
    private final Spells spells;
    private final IntIntMap skeletonOfRoot;
    private final IntIntMap fireballOfEntity = new IntIntMap(32, -1);
    private final LongIntMap entityOfFireball = new LongIntMap(32, -1);
    private int nextFireball;

    CryptIds(LevelLoader loader, Party party, Enemies enemies, Spells spells) {
        this.loader = loader;
        this.party = party;
        this.enemies = enemies;
        this.spells = spells;
        skeletonOfRoot = new IntIntMap(Math.max(16, enemies.count * 2), -1);
        for (int i = 0; i < enemies.count; i++) {
            skeletonOfRoot.put(enemies.entity(i), i);
        }
    }
```

- **A party member is its index** (0–5): six fixed members, rebuilt in the same order.
- **A skeleton is its spawn order**: `skeletonOfRoot` maps the root entity of skeleton $i$ to $i$,
  filled once at construction, because the demo never spawns a skeleton after the level is built (a
  dead one keeps its row and rises again, chapter [22](22-components.md)). The map is a primitive
  `IntIntMap` (no boxing), sized at twice the count so lookups do not probe long chains.
- **A fireball is a counter that restarts at every capture.** A fireball is "the same fireball"
  only within one save: nothing outside the save refers to it, so a load may give it any fresh
  entity. `fireballOfEntity` numbers them during a capture; `entityOfFireball` remembers, during a
  load, which entity was created for which id, so that a reference column pointing at a fireball
  resolves to the same new entity as its row.

```java
    /** Starts a capture: fireballs are numbered afresh. */
    void beginCapture() {
        fireballOfEntity.clear();
        nextFireball = 0;
    }

    /** Starts a load: the fireball entities of the previous load are forgotten. */
    void beginLoad() {
        entityOfFireball.clear();
    }
```

The two maps are cleared, not reallocated: a capture must allocate nothing, and `IntIntMap.clear`
keeps its arrays.

```java
    @Override
    public long idOf(int entity) {
        if (entity == 0) {
            return NONE;
        }
        for (int i = 0; i < Party.SIZE; i++) {
            if (party.root[i] == entity) {
                return EntityIds.id(PARTY, i);
            }
        }
        int skeleton = skeletonOfRoot.get(entity);
        if (skeleton >= 0) {
            return EntityIds.id(SKELETON, skeleton);
        }
        int object = loader.placementOf(entity);
        if (object >= 0) {
            return EntityIds.object(object);
        }
        int fireball = fireballOfEntity.get(entity);
        if (fireball < 0) {
            fireball = nextFireball++;
            fireballOfEntity.put(entity, fireball);
        }
        return EntityIds.id(FIREBALL, fireball);
    }
```

The order of the tests is cheapest first and most specific first: six comparisons for the party, a
hash lookup for the skeletons, the loader's table for level objects, and *anything else* is a
fireball — the only other entity the demo gives components to. A game with more kinds would add
tests before the last one, or keep a kind per entity in a table. `nextFireball++` hands out 0, 1, 2
… in the order the stores visit the rows; `put` into a map that was sized for 32 allocates only if a
capture ever holds more than 32 fireballs at once (`Spells.MAX` is 16).

```java
    @Override
    public int entityOf(long id) {
        int value = (int) EntityIds.value(id);
        return switch (EntityIds.kind(id)) {
            case OBJECT -> loader.rootOf(value);
            case PARTY -> value >= 0 && value < Party.SIZE ? party.root[value] : 0;
            case SKELETON -> value >= 0 && value < enemies.count ? enemies.entity(value) : 0;
            case FIREBALL -> {
                int e = entityOfFireball.get(id);
                if (e < 0) {
                    e = spells.spawnEntity();
                    entityOfFireball.put(id, e);
                }
                yield e;
            }
            default -> 0;
        };
    }
}
```

- The party and the skeletons **exist before a load** (the level is rebuilt first), so their ids
  are looked up, with a range check so a save from a bigger game yields `0` ("nothing") rather than
  an exception.
- A fireball's id **creates** an entity (`Spells.spawnEntity` is `entities.create()`), once per id:
  the row and every reference to it get the same new entity.
- `default -> 0`: an unknown kind — a save from a game that had more kinds — resolves to nothing,
  and the store drops the row with a warning (§9.3). *Loading degrades; it does not crash.*

## 9. Step 2: a store writes itself

### 9.1 The record

Lesson [L03](lessons/03-data-layout.md) built `Store`: one primitive array per column, a sparse set
from entity index to dense row, and the generic column API (`columnCount`, `columnName`,
`columnType`, `getInt` …) that the generated subclasses implement. Its save record (**D111**) is
**self-describing**: the file carries the column names and types, so a reader built from a changed
record can still match what it finds. The layout, as `write` emits it:

```
string  store name                     e.g. "Monster"
u32     column count
per column:  string name, u32 type     type: 0 INT, 1 LONG, 2 FLOAT, 3 BOOL, 4 ENTITY
u32     row count n
n × i64 entity id                      ids.idOf(entities[i])
per column, in header order:
   INT    n × i32          FLOAT  n × f32         BOOL  n × u8
   LONG   n × i64          ENTITY n × i64 (ids.idOf of the reference)
```

Column by column, not row by row: the file's layout *is* the store's SoA layout, so the copy walks
each array once, sequentially (L00 §9.3). `ENTITY` (4) is not a Java type; it is the marker for an
`int` column that the processor flagged `@EntityRef` (`Store.isEntityRef`), so the reader knows
those eight bytes hold an id, not an `int`.

Worked: the demo's `Monster` store with 294 rows. Name `"Monster"` is 2 + 7 = 9 bytes; the count 4;
the four columns `model`, `agent`, `character`, `index` are (2 + 5 + 4) + (2 + 5 + 4) + (2 + 9 + 4)
+ (2 + 5 + 4) = 48; the row count 4; the ids 294 × 8 = 2,352; the four `INT` columns 4 × 294 × 4 =
4,704. Total 9 + 4 + 48 + 4 + 2,352 + 4,704 = **7,121 bytes**, which is what §1's file holds for
that store (§12.4). The ids are a third of it: an `int` handle would be half the size, and could
not be resolved next run.

### 9.2 `write`

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
                case LONG -> {
                    for (int i = 0; i < n; i++) {
                        w.int64(getLong(i, c));
                    }
                }
                case FLOAT -> {
                    for (int i = 0; i < n; i++) {
                        w.float32(getFloat(i, c));
                    }
                }
                default -> {
                    for (int i = 0; i < n; i++) {
                        w.bool(getBool(i, c));
                    }
                }
            }
        }
    }
```

- `final`: the base class owns the format; a generated subclass may not change it.
- The entity loop writes `ids.idOf(entities[i])` for the first `n` rows of the dense entity array;
  rows beyond `size()` are spare capacity.
- Each column goes through the generic `getInt(i, c)` — a virtual call per element with a `switch`
  on the column inside. That is the one place a capture spends time it need not (exercise 5 asks
  you to measure it); the generated store could write its arrays with the bulk `ints(a, n)` in one
  call per column. It was left generic because the save is captured seconds apart and 0.6 ms for
  the whole demo was under budget.
- The `write(ByteWriter)` overload (not shown) passes `EntityIds.HANDLES`.

### 9.3 `read`

```java
    public final void read(ByteReader r, EntityIds ids) {
        String stored = r.string();
        if (!stored.equals(name)) {
            throw new IllegalStateException("snapshot has " + stored + ", store is " + name);
        }
        int columns = r.int32();
        int[] local = new int[columns];
        int[] types = new int[columns];
        for (int c = 0; c < columns; c++) {
            local[c] = columnIndex(r.string());
            types[c] = r.int32();
        }
        clear();
        int n = r.int32();
        int[] rows = new int[n]; // -1 for a dropped row
        int dropped = 0;
        for (int i = 0; i < n; i++) {
            long id = r.int64();
            int e = id == EntityIds.NONE ? 0 : ids.entityOf(id);
            if (e != 0 && pool.isAlive(e)) {
                rows[i] = add(e);
            } else {
                rows[i] = -1;
                dropped++;
            }
        }
        if (dropped > 0) {
            LOG.warn(name + ": " + dropped + " of " + n + " saved rows name no entity; dropped");
        }
```

Three things happen before any column is read:

1. **The header is matched to this store.** `local[c]` is this store's index of the stored
   column's name, or −1 for a column this build does not have (`columnIndex` returns −1); the
   stored type is kept for the comparison below. The arrays are allocated: a load may allocate,
   a capture may not (L04 §10.3's rule).
2. **The store is cleared** and every saved entity is resolved. A row whose id resolves to nothing,
   or to an entity the pool says is dead, is **dropped** and counted; the warning names the store
   so a player's report ("my fireballs vanished") can be acted on. The `rows` array remembers, per
   saved row, which dense row it landed in — necessary because `add(e)` returns rows in insertion
   order, which is the saved order only when nothing was dropped.
3. **Dropped rows still have bytes in every column**, so the column loops below read them and
   throw them away rather than skipping ahead: the file's layout is fixed by the saved `n`, not by
   how many rows survived.

```java
        for (int c = 0; c < columns; c++) {
            int to = local[c];
            // The stored type must be the column's: an int column that became a reference (or
            // the reverse) and any other change of type keeps its default.
            if (to >= 0 && types[c] != (isEntityRef(to) ? ENTITY : columnType(to))) {
                to = -1;
            }
            switch (types[c]) {
                case INT -> {
                    for (int i = 0; i < n; i++) {
                        int v = r.int32();
                        if (to >= 0 && rows[i] >= 0) {
                            setInt(rows[i], to, v);
                        }
                    }
                }
                case ENTITY -> {
                    for (int i = 0; i < n; i++) {
                        long id = r.int64();
                        if (to >= 0 && rows[i] >= 0) {
                            setInt(rows[i], to, id == EntityIds.NONE ? 0 : ids.entityOf(id));
                        }
                    }
                }
                case LONG -> {
                    for (int i = 0; i < n; i++) {
                        long v = r.int64();
                        if (to >= 0 && rows[i] >= 0) {
                            setLong(rows[i], to, v);
                        }
                    }
                }
                case FLOAT -> {
                    for (int i = 0; i < n; i++) {
                        float v = r.float32();
                        if (to >= 0 && rows[i] >= 0) {
                            setFloat(rows[i], to, v);
                        }
                    }
                }
                default -> {
                    for (int i = 0; i < n; i++) {
                        boolean v = r.bool();
                        if (to >= 0 && rows[i] >= 0) {
                            setBool(rows[i], to, v);
                        }
                    }
                }
            }
        }
    }
```

The `switch` is on the **stored** type, because that is what decides how many bytes each element
takes; whether the value is *kept* is `to >= 0 && rows[i] >= 0`. So the four cases a changed record
can produce all work: a stored column this build lacks is read and discarded; a column this build
has that the file lacks keeps the default the generated `resetRow` gave it; a column whose type
changed — say `agent` went from `int` to `@EntityRef int`, or a `float` became an `int` — keeps its
default, because reinterpreting the bytes would be worse than losing them; and a reference column
resolves each id through `entityOf`, so a reference to a fireball lands on the fireball's *new*
entity. `GeneratedGameTest.snapshotsRoundTripAndTolerateChangedStores` writes a file by hand with a
`Ghost` store and an `armor` column to prove each case (§17.4).

### 9.4 `skip`, and the registry

A store this build no longer has must still be *stepped over*, because the registry reads stores
by name from one stream:

```java
    public static String skip(ByteReader r) {
        String stored = r.string();
        int columns = r.int32();
        int[] types = new int[columns];
        for (int c = 0; c < columns; c++) {
            r.string();
            types[c] = r.int32();
        }
        int n = r.int32();
        r.skip(8 * n);
        for (int c = 0; c < columns; c++) {
            switch (types[c]) {
                case INT, FLOAT -> r.skip(4 * n);
                case LONG, ENTITY -> r.skip(8 * n);
                default -> r.skip(n);
            }
        }
        return stored;
    }
```

It is the reader's arithmetic without the stores: the header says how many rows and which types,
and that is enough to compute every column's size. `CryptSave.differenceFrom` (§12.6) uses it to
say *which store* a differing byte belongs to.

The generated registry (`CryptGame`, chapter [22](22-components.md); the processor's output is
not in the repository) composes the stores:

```java
    public void write(ByteWriter w, EntityIds ids) {
        w.int32(stores.length);
        for (Store s : stores) {
            s.write(w, ids);
        }
    }

    /** {@link #write(ByteWriter, EntityIds)} with handles as ids. */
    public void write(ByteWriter w) {
        write(w, EntityIds.HANDLES);
    }

    /** Reads what {@link #write} wrote: stores by name, unknown ones skipped. */
    public void read(ByteReader r, EntityIds ids) {
        int n = r.int32();
        for (int i = 0; i < n; i++) {
            Store s = store(r.peekString());
            if (s != null) {
                s.read(r, ids);
            } else {
                Store.skip(r);
            }
        }
    }
```

`peekString` (L04 §10.3's reader, extended for this) reads the name without consuming it, so the
registry can route the record to the right store, which then reads the name again and checks it.
`store(name)` is a generated `switch` over the five names (`Fireball`, `Flash`, `Mind`, `Monster`,
`Sight`); the `for (Store s : stores)` is an array loop, not an iterator, so `write` allocates
nothing. The order of the stores in the file is the registry's alphabetical order; `read` does not
care.

---
## 10. Step 3: the rules by content id, plus the overlay

### 10.1 The record

[`rules.Rules`](../../modules/rules/src/main/java/dev/jeddy/rules/Rules.java) owns the creatures
(`Creatures`, a SoA store with no generation counter, chapter [15](15-rules.md)), their modifiers
and conditions, the content and the dice. Its record, format 1:

```
u32   SAVE_FORMAT (1)
u32   4, then 4 × i64              the generator's state (Rng.saveState)
u32   live creature count
per live creature, in handle order:
  i32  handle                        Creatures.createAt puts it back here
  i32  team
  string name
  i8   SAVED_CHARACTER (1) + string character id
    or SAVED_MONSTER (2)   + string monster id
    or SAVED_BLANK (0)     + 6 × i32 base abilities, i32 level, i32 base attack bonus,
                             3 × i32 base saves, i32 base max hp, i32 size, i32 base speed,
                             i32 casting, i32 casting ability, bool dies at zero
  i32  hp, i32 temp hp, i32 temp hp rounds
  10 × i32 spell slots used (Creatures.SPELL_LEVELS)
  i32  attacks of opportunity used, bool acted
  u32  feat count,  feat ids (strings)
  u32  spell count, spell ids (strings)
  3 × string equipment ids ("" for an empty slot; Creatures.SLOTS)
  u32  condition count, (string condition id, i32 rounds) each
  u32  kept modifier count, (i32 stat, i32 type, i32 value, i32 source kind,
                             string source id or i32 source id, i32 rounds) each
```

The three kinds of creature are §4.4's decision in bytes: a character or monster is named by the
**id string** of its content row, and only the overlay follows; a creature made by hand (the
tests' giant rat) has no row to rebuild from, so its base fields are written verbatim.

### 10.2 `write`, in three stages

The constants and the scratch, then the header:

```java
    public static final int SAVE_FORMAT = 1;

    private static final int SAVED_BLANK = 0;
    private static final int SAVED_CHARACTER = 1;
    private static final int SAVED_MONSTER = 2;
    private final long[] rngState = new long[4];
```

then `write` itself:

```java
    public void write(ByteWriter w) {
        w.int32(SAVE_FORMAT);
        rng.saveState(rngState);
        w.longs(rngState);
        w.int32(creatures.liveCount());
        int top = creatures.highWaterMark();
        for (int c = 0; c < top; c++) {
            if (!creatures.isAlive(c)) {
                continue;
            }
            w.int32(c);
            w.int32(creatures.team(c));
            w.string(creatures.name(c));
```

`rngState` is a field, not a local `new long[4]`, so the capture allocates nothing. The loop runs to
the **high-water mark** (the highest handle ever used plus one) and skips dead slots, writing the
handle of each live one: a save after a creature was destroyed keeps the gap, and `read` keeps it
too (§17.2's `creatureHandlesAreRestoredWhereTheyWere`).

Stage two, the identity and the base:

```java
            int characterRow = creatures.characterRow(c);
            int monsterRow = creatures.monsterRow(c);
            boolean content = characterRow >= 0 || monsterRow >= 0;
            if (characterRow >= 0) {
                w.int8(SAVED_CHARACTER);
                w.string(this.content.id(RulesSchema.CHARACTERS, characterRow));
            } else if (monsterRow >= 0) {
                w.int8(SAVED_MONSTER);
                w.string(this.content.id(RulesSchema.MONSTERS, monsterRow));
            } else {
                w.int8(SAVED_BLANK);
                for (int a = 0; a < 6; a++) {
                    w.int32(creatures.baseAbility(c, a));
                }
                w.int32(creatures.level(c));
                w.int32(creatures.baseAttackBonus(c));
                for (int k = 0; k < 3; k++) {
                    w.int32(creatures.baseSave(c, k));
                }
                w.int32(creatures.baseMaxHp(c));
                w.int32(creatures.size(c));
                w.int32(creatures.baseSpeed(c));
                w.int32(creatures.casting(c));
                w.int32(creatures.castingAbility(c));
                w.bool(creatures.diesAtZero(c));
            }
```

`content.id(kind, row)` reads `RulesData.getString`, which indexes an **interned string table**
(`strings[cells[...]]`): the `String` exists already, and `ByteWriter.string` encodes it without
allocating (L04 §9.4). That is how a `write` full of strings stays at 0 B — `SavesTest` asserts it.

Stage three, the overlay:

```java
            w.int32(creatures.hp(c));
            w.int32(creatures.tempHp(c));
            w.int32(creatures.tempHpRounds(c));
            for (int l = 0; l < Creatures.SPELL_LEVELS; l++) {
                w.int32(creatures.slotsUsed(c, l));
            }
            w.int32(creatures.aooUsed(c));
            w.bool(creatures.acted(c));
            writeIds(w, RulesSchema.FEATS, creatures.feats(c));
            writeIds(w, RulesSchema.SPELLS, creatures.spells(c));
            for (int slot = 0; slot < Creatures.SLOTS; slot++) {
                int item = creatures.equipment(c, slot);
                w.string(item < 0 ? "" : this.content.id(RulesSchema.ITEMS, item));
            }
            IntList conds = creatures.conditionHandles(c);
            w.int32(conds.size());
            for (int i = 0; i < conds.size(); i++) {
                int h = conds.get(i);
                w.string(this.content.id(RulesSchema.CONDITIONS, conditions.condition(h)));
                w.int32(conditions.rounds(h));
            }
            IntList mods = creatures.modifierHandles(c);
            int kept = 0;
            for (int i = 0; i < mods.size(); i++) {
                if (keptInSave(mods.get(i), content)) {
                    kept++;
                }
            }
            w.int32(kept);
            for (int i = 0; i < mods.size(); i++) {
                int h = mods.get(i);
                if (!keptInSave(h, content)) {
                    continue;
                }
                w.int32(modifiers.stat(h));
                w.int32(modifiers.type(h));
                w.int32(modifiers.value(h));
                int source = modifiers.source(h);
                int kind = Modifiers.sourceKind(source);
                w.int32(kind);
                RulesSchema.Kind table = sourceTable(kind);
                if (table != null) {
                    w.string(this.content.id(table, Modifiers.sourceId(source)));
                } else {
                    w.int32(Modifiers.sourceId(source));
                }
                w.int32(modifiers.rounds(h));
            }
        }
    }
```

- **Feats, spells, equipment, conditions as ids**: a feat is a content row at run time and a
  string in the file, for the reason of §4.4 (rows renumber). An empty equipment slot is the empty
  string, which `ByteWriter.string` writes as a `u16` of 0.
- **The modifier count is counted first**, in a pass over the list, because the count must precede
  the elements and the filter decides per element. Two passes over a dozen modifiers cost nothing;
  the alternative — write a placeholder and patch it, as `SaveWriter` does for chunk lengths — would
  need the writer's `patchInt32` and a remembered position per creature.
- A modifier's **source** names where it came from (a feat, an item, a spell, the game) so the
  rules can remove it when the source goes away; its id is a content row for the kinds that have a
  table and a plain number otherwise, and the file keeps that distinction.

### 10.3 `keptInSave`: what a rebuild recreates

```java
    private boolean keptInSave(int h, boolean contentBuilt) {
        int kind = Modifiers.sourceKind(modifiers.source(h));
        if (kind == Modifiers.SOURCE_CONDITION) {
            return false;
        }
        if (!contentBuilt) {
            return true;
        }
        return switch (kind) {
            case Modifiers.SOURCE_CHARACTER,
                    Modifiers.SOURCE_CLASS,
                    Modifiers.SOURCE_ANCESTRY,
                    Modifiers.SOURCE_FEAT,
                    Modifiers.SOURCE_ITEM,
                    Modifiers.SOURCE_MONSTER ->
                    false;
            default -> true;
        };
    }
```

This is the heart of D115, as a decision table:

| The modifier came from | Content-built creature | Hand-made creature | Why |
|---|---|---|---|
| a condition | not written | not written | `read` re-applies the condition through `applyCondition`, which recreates its modifiers *and* its events |
| the character, its class, its ancestry, a feat, an item, the monster | not written | written | a rebuild from the current content recreates them — with the patched numbers; a hand-made creature has nothing to rebuild from |
| a spell, the game's own (`SOURCE_SPELL`, `SOURCE_GAME`, `SOURCE_NONE`) | written | written | nothing else would recreate a `shield` cast three rounds ago |

`sourceTable` maps a source kind to the content table its id indexes (feats, class features,
ancestries, spells, monsters, characters), or `null` for the kinds whose id is just a number
(a condition instance, an equipment slot, the game's own).

### 10.4 `read`, in three stages

```java
    public void read(ByteReader r) {
        int format = r.int32();
        if (format != 1) {
            throw new IllegalStateException("rules save format " + format + "; this build reads 1");
        }
        creatures.clear();
        int n = r.longsInto(rngState);
        if (n == 4) {
            rng.restoreState(rngState);
        }
        int count = r.int32();
        for (int k = 0; k < count; k++) {
            int c = r.int32();
            int team = r.int32();
            String name = r.string();
            creatures.createAt(c, name, team);
```

- **The format first** (D117): a record this build does not know is refused with a message, not
  misread. Today the `if` is the degenerate `switch` of L04 §16.1.
- **`creatures.clear()`** destroys every creature, its modifiers and its condition instances, and
  resets the free list; the saved ones are then created *at their handles*. `Creatures.createAt(c,
  …)` grows the arrays if `c` is past the capacity, destroys a live creature already there, takes
  `c` off the free list if it was free, and — when `c` is past the current count — pushes the
  skipped handles onto the free list, so that the next `create` after a load reuses the same
  handle it would have in the saved run.
- **`longsInto`** reads the count and the four `long`s into the kept array without allocating; a
  record with a different count (there is none today) would leave the generator alone rather than
  restore half a state. `Rng.restoreState` refuses an all-zero state, which xoshiro cannot leave.

Stage two rebuilds from the **current** content:

```java
            int kind = r.int8();
            switch (kind) {
                case SAVED_CHARACTER -> {
                    String id = r.string();
                    int row = content.find(RulesSchema.CHARACTERS, id);
                    if (row >= 0) {
                        fillCharacter(c, row);
                    } else {
                        LOG.warn("save names character " + id + ", which the content lacks");
                    }
                }
                case SAVED_MONSTER -> {
                    String id = r.string();
                    int row = content.find(RulesSchema.MONSTERS, id);
                    if (row >= 0) {
                        fillMonster(c, row);
                    } else {
                        LOG.warn("save names monster " + id + ", which the content lacks");
                    }
                }
                default -> {
                    for (int a = 0; a < 6; a++) {
                        creatures.setBaseAbility(c, a, r.int32());
                    }
                    creatures.setLevel(c, r.int32());
                    creatures.setBaseAttackBonus(c, r.int32());
                    for (int s = 0; s < 3; s++) {
                        creatures.setBaseSave(c, s, r.int32());
                    }
                    creatures.setBaseMaxHp(c, r.int32());
                    creatures.setSize(c, r.int32());
                    creatures.setBaseSpeed(c, r.int32());
                    int casting = r.int32();
                    creatures.setCasting(c, casting, r.int32());
                    creatures.setDiesAtZero(c, r.bool());
                }
            }
            creatures.setName(c, name);
```

`fillCharacter` and `fillMonster` are the halves of `buildCharacter` and `buildMonster` that fill
an *already created* creature from a content row: M16 split the builders in two so a load could
reuse them. They set the abilities, the class progression, the feats and the equipment the content
grants — all from the rulebook as it is *now*. A content id the rulebook no longer has is a warning
and a blank creature, not a failed load. `setName` comes after the fill because the fill would
otherwise overwrite a renamed creature with the content's name.

Stage three lays the overlay on, in an order that matters:

```java
            int hp = r.int32();
            int tempHp = r.int32();
            int tempHpRounds = r.int32();
            for (int l = 0; l < Creatures.SPELL_LEVELS; l++) {
                creatures.setSlotsUsed(c, l, r.int32());
            }
            creatures.setAooUsed(c, r.int32());
            creatures.setActed(c, r.bool());
            int feats = r.int32();
            for (int i = 0; i < feats; i++) {
                int row = content.find(RulesSchema.FEATS, r.string());
                if (row >= 0) {
                    learnFeat(c, row);
                }
            }
            int spells = r.int32();
            for (int i = 0; i < spells; i++) {
                int row = content.find(RulesSchema.SPELLS, r.string());
                if (row >= 0 && !creatures.spells(c).contains(row)) {
                    creatures.spells(c).add(row);
                }
            }
            for (int slot = 0; slot < Creatures.SLOTS; slot++) {
                String id = r.string();
                int row = id.isEmpty() ? -1 : content.find(RulesSchema.ITEMS, id);
                if (row != creatures.equipment(c, slot)) {
                    equip(c, slot, row);
                }
            }
            int conds = r.int32();
            for (int i = 0; i < conds; i++) {
                String id = r.string();
                int rounds = r.int32();
                int row = content.find(RulesSchema.CONDITIONS, id);
                if (row >= 0) {
                    applyCondition(c, row, rounds);
                }
            }
            int mods = r.int32();
            for (int i = 0; i < mods; i++) {
                int stat = r.int32();
                int type = r.int32();
                int value = r.int32();
                int sourceKind = r.int32();
                RulesSchema.Kind table = sourceTable(sourceKind);
                int sourceId;
                if (table != null) {
                    sourceId = content.find(table, r.string());
                } else {
                    sourceId = r.int32();
                }
                int rounds = r.int32();
                if (sourceId >= 0) {
                    creatures.addModifier(
                            c, stat, type, value, Modifiers.source(sourceKind, sourceId), rounds);
                }
            }
            // Last, since the rebuild and the equipment set hit points to the maximum.
            creatures.setHp(c, hp);
            creatures.setTempHp(c, tempHp, tempHpRounds);
        }
    }
```

- **Feats** are learned through `learnFeat`, which adds the feat's modifiers, so a feat the player
  picked after the rebuild comes back with its effect; one the content already granted is a no-op.
- **Equipment** is re-equipped only where it differs from what the rebuild gave — `equip` removes
  the old item's modifiers and adds the new one's — so a character who swapped the content's
  longsword for the greataxe gets the greataxe, and one who kept the longsword is not re-equipped
  for nothing.
- **Conditions** go through `applyCondition`, *as they would in play*: their modifiers come back and
  their events fire (the combat log would print "Aldric is shaken" again; §10.6 says how the demo
  avoids that).
- **Hit points are set last**, because `fillCharacter` and `fillMonster` end with
  `creatures.setHp(c, creatures.stat(c, Stat.MAX_HP))` — a rebuilt creature starts at full health —
  and a feat (toughness) or an item's effects change the maximum after that. Set earlier, the wound
  would be healed by the next line; the code's own comment says so.

### 10.5 The encounter and its budget

[`Encounter`](../../modules/rules/src/main/java/dev/jeddy/rules/Encounter.java) is the fight in
progress: the initiative order, the round, whose turn it is:

```java
    public static final int SAVE_FORMAT = 1;
```

```java
    public void write(ByteWriter w) {
        w.int32(SAVE_FORMAT);
        w.bool(active);
        w.int32(round);
        w.int32(turnIndex);
        w.bool(currentLeft);
        w.int32(joinCounter);
        w.ints(order.array(), order.size());
        w.ints(initiative.array(), initiative.size());
        w.ints(joined.array(), joined.size());
        budget.write(w, rules.creatures().highWaterMark());
    }

    /** Reads what {@link #write} wrote, replacing the fight in progress. */
    public void read(ByteReader r) {
        int format = r.int32();
        if (format != 1) {
            throw new IllegalStateException(
                    "encounter save format " + format + "; this build reads 1");
        }
        active = r.bool();
        round = r.int32();
        turnIndex = r.int32();
        currentLeft = r.bool();
        joinCounter = r.int32();
        readList(r, order);
        readList(r, initiative);
        readList(r, joined);
        budget.read(r);
    }

    private static void readList(ByteReader r, IntList into) {
        into.clear();
        int n = r.int32();
        for (int i = 0; i < n; i++) {
            into.add(r.int32());
        }
    }
```

`order` holds **creature handles**, which §4.2 made stable, so the list is written as it is;
`initiative` and `joined` (the join sequence that breaks initiative ties) go beside it.
`ints(array, size)` writes the first `size` elements of a list that has spare capacity — the reason
the bulk methods take `n` separately (L04 §9.5). `readList` reads into the kept `IntList` without
replacing it.

The action budget — what each creature may still do this turn — is a bit set per creature plus a
"moved" flag, written for every handle up to the high-water mark and read back growing to fit:

```java
    void write(ByteWriter w, int highWaterMark) {
        int n = Math.min(highWaterMark, left.length);
        w.ints(left, n);
        w.bools(moved, n);
    }

    /** Reads what {@link #write} wrote, growing to fit. */
    void read(ByteReader r) {
        int n = r.int32();
        ensure(n);
        for (int c = 0; c < n; c++) {
            left[c] = r.int32();
        }
        int m = r.int32();
        ensure(m);
        for (int c = 0; c < m; c++) {
            moved[c] = r.bool();
        }
    }
```

The real-time driver keeps one float the fight needs: seconds into the current round, which decides
whose turn is due next. It has no `write`; the game writes the clock itself and restores it with
`setClock` (M16 added the setter), because the driver is the game's to create and the clock is the
only state it has.

### 10.6 The demo's `Combat`

[`Combat`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/Combat.java) composes the
three rules records and adds the fight's state that the demo owns:

```java
    void write(ByteWriter w) {
        rules.write(w);
        encounter.write(w);
        w.float32(realTime.clock());
        w.bool(turnBasedMode);
        w.int32(rounds);
        w.int32(engaged);
        w.float32(regenTimer);
        int n = creatures.highWaterMark();
        w.ints(attacksLeft, Math.min(n, attacksLeft.length));
        w.ints(attackIndex, Math.min(n, attackIndex.length));
        w.floats(moveLeft, Math.min(n, moveLeft.length));
        w.int32(aiCreature);
        w.int32(aiPhase);
        w.float32(aiTimer);
        w.float32(aiTargetX);
        w.float32(aiTargetZ);
        w.int32(aiVictim);
    }
```

This is the `RULE` chunk: four records back to back (the rules, the encounter, the clock, the
demo's own) with one chunk version over them (§5.3). Reading it back:

```java
    void read(ByteReader r) {
        rules.read(r);
        encounter.read(r);
        realTime.setClock(r.float32());
        turnBasedMode = r.bool();
        rounds = r.int32();
        engaged = r.int32();
        regenTimer = r.float32();
        int n = r.intsInto(attacksLeft);
        java.util.Arrays.fill(attacksLeft, n, attacksLeft.length, 0);
        n = r.intsInto(attackIndex);
        java.util.Arrays.fill(attackIndex, n, attackIndex.length, 0);
        n = r.floatsInto(moveLeft);
        java.util.Arrays.fill(moveLeft, n, moveLeft.length, 0);
        aiCreature = r.int32();
        aiPhase = r.int32();
        aiTimer = r.float32();
        aiTargetX = r.float32();
        aiTargetZ = r.float32();
        aiVictim = r.int32();
        for (int i = 0; i < enemies.count; i++) {
            bind(enemyCreature[i], enemies.agent(i), -1, i);
        }
        eventsRead = rules.events().next();
    }
```

- `intsInto` reads into the kept array and returns the count; the tail past it is zeroed, so a
  save from a smaller fight does not leave stale budgets behind.
- **Creature handles need no translation**: the constructor builds the party at handles 0–5 and
  skeleton $i$ at $6 + i$, and `spawnEnemy` (a respawn) destroys and immediately recreates, which
  reuses the freed handle; `Rules.read` puts every creature back where it was. What *is* rebound is
  each creature's crowd agent, re-read from the stores (`enemies.agent(i)`), because a respawn may
  have moved a skeleton to another slot.
- `eventsRead = rules.events().next()` **drops the events the rebuild raised**: `applyCondition`
  fired "is shaken" for every restored condition, and nothing happened in play, so the combat log
  must not print them.

## 11. Step 4: the world's records

Every other system follows the same shape: a format number (where the module owns the layout), a
count, the arrays. Four of them are built in other chapters and only summarized here.

### 11.1 The crowd (`CROW`): chapter 11

[`Crowd.write`](../../modules/physics/src/main/java/dev/jeddy/physics/crowd/Crowd.java) writes the
format, the capacity, the active count, and per active agent its slot, position, velocity, the
eight parameters, flags, filter, target state, target and requested velocity: 88 bytes per agent,
and *not the corridor*. `read` removes every agent, resets the request queue, places each saved
agent back in its slot, re-requests its target and solves every path before returning, so a loaded
agent walks on the next tick with the target state the save had (`ARRIVED` and `FAILED` are set
without a request). Three lines there matter to this chapter's round trip:

```java
                place(i, x, y, z);
                // The saved position was on the mesh already: keep its exact bits rather than
                // the re-projection's (a few ulps off in y), so that a save made right after a
                // load is the same bytes. The corridor starts there too.
                px[i] = prevX[i] = x;
                py[i] = y;
                pz[i] = prevZ[i] = z;
                corridors[i].reset(corridors[i].firstPoly(), x, y, z);
```

`place` snaps the saved position to the navmesh, and the projection of a point that already lies on
a triangle onto that triangle comes back a few **ulps** off in $y$ — an *ulp* ("unit in the last
place") is the gap between a float and its neighbour, the smallest step a `float` can take at that
size (L00 §7.3: about $1.2 \times 10^{-7}$ near 1.0). A plane projection is not exactly idempotent in
floating point. That is harmless for play and fatal for a byte comparison, so the saved bits are
written back over the re-projection, into the current and the previous position (`prevX`, `prevZ`
feed interpolation) and into the corridor's start. Chapter [11](11-physics-navigation-crowds.md)
(the crowd's save format) walks the rest of the code and decodes the golden file `crowd-v1.bin`.

### 11.2 The formation walk, inside `PRTY`: chapter 11

[`FormationMove.write`](../../modules/physics/src/main/java/dev/jeddy/physics/crowd/FormationMove.java)
writes its format, the member count, per member the agent, its normal speed and its slot, then the
slot positions; `read` restores them after the crowd, since the members' targets live in the
crowd's record. The pacing (each member's speed scaled so the group arrives together) is recomputed
every tick from those. Chapter [11](11-physics-navigation-crowds.md) (formations) has the code and
notes one gap: `read` clamps the count to its capacity but does not skip the extra records of a
save written by a bigger mover.

### 11.3 The fog (`FOG `): chapter 11

[`Visibility.write`](../../modules/physics/src/main/java/dev/jeddy/physics/vis/Visibility.java)
writes the format, the grid's width and height, and the `explored` bit set (`longs`); `read` takes
the bits only when the size matches — a re-cooked occlusion grid would put them on the wrong cells —
clears the exploration otherwise, returns whether it took them, and marks every viewer dirty so the
next update recomputes the *visible* bits. Chapter [11](11-physics-navigation-crowds.md) (the fog
of war's packing and saving) has the code. For §1's crypt the grid is 163 × 107 cells of 0.5 m,
$\lceil 163 / 64 \rceil = 3$ words per row, 321 words: 12 + 4 + 321 × 8 = 2,584 bytes.

### 11.4 The animator (`ANIM`): chapter 09

[`Animator.write`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/Animator.java) writes
the character count and, per character, 14 bytes: the mode (`int8`: locomotion, action, dead), the
combat stance (`bool`), the action slot (`int32`), the seconds left of the action (`float32`) and
the phase of the clip playing (`float32`). `read` replays: the dead play their death clip, cut
(fade 0) and moved to the saved phase; an action replays its one-shot at the saved phase with the
saved time left; a character in locomotion lets `locomotion()` pick idle, walk or run from its
agent's restored speed — with `locomotion`'s usual **0.2 s crossfade** (`FADE`) from whatever pose
the character showed before the load, so for a fifth of a second it is not "exactly as it was" —
and then `setPhase` moves the new state to the saved phase. Chapter
[09](09-animation-and-skinning.md) (saving animation state) has the code and explains what is
deliberately *not* written: the blend weight and speed (derived from the agent), any crossfade in
progress, the joint matrices, the `Animations` slot numbers.

One line of `read` is wrong, and this chapter reports it rather than hides it (§19.2): the
`ACTION` branch replays the one-shot but does not reset `moving[c] = NONE` as `action()` does, so a
character saved while swinging and standing stays frozen on the swing's last frame after the load,
until its agent moves. Chapter 09's exercise on a loaded one-shot that never ends has the fix.

### 11.5 Facings (`SYNC`): `AgentSync`

[`AgentSync`](../../modules/app/src/main/java/dev/jeddy/app/world/AgentSync.java) (chapter
[01](01-architecture.md), on sync systems) copies each crowd agent's position to its bound entity
every tick and turns the entity toward its motion, or toward a facing the game asked for with
`face`. Its state per agent slot is the current yaw, the wanted yaw and whether one is wanted:

```java
    /** The layout {@link #write} uses (D117). */
    public static final int SAVE_FORMAT = 1;

    /**
     * Writes the facing of every bound agent (saves): the binding itself is the game's (it rebinds
     * its agents and entities before reading). Allocates nothing.
     */
    public void write(ByteWriter w) {
        w.int32(SAVE_FORMAT);
        w.int32(entity.length);
        for (int a = 0; a < entity.length; a++) {
            w.float32(yaw[a]);
            w.float32(wantYaw[a]);
            w.bool(wants[a]);
        }
    }
```

Every slot is written, bound or not (9 bytes each: 2,708 bytes for the demo's 300), because the
binding itself is *not* saved: "the binding is the game's" — the game rebinds its agents and
entities before reading (§11.6), and the facings land on whatever is bound then.

```java
    public void read(ByteReader r) {
        int format = r.int32();
        if (format != 1) {
            throw new IllegalStateException(
                    "agent sync save format " + format + "; this build reads 1");
        }
        int n = r.int32();
        for (int a = 0; a < n; a++) {
            float y = r.float32();
            float want = r.float32();
            boolean w = r.bool();
            if (a >= entity.length) {
                continue;
            }
            yaw[a] = y;
            wantYaw[a] = want;
            wants[a] = w;
            int e = entity[a];
            if (e != 0 && crowd.isActive(a)) {
                world.transforms().setPosition(e, crowd.x(a), crowd.y(a), crowd.z(a));
                world.setYaw(e, y);
            }
        }
    }
```

- The three values are read *before* the range check, so a save from a bigger crowd is consumed
  record by record and the slots that fit are restored.
- **The facing is applied at once**: a bound, active agent's entity is moved to the agent's restored
  position and turned to the saved yaw, so a loaded character stands and faces as it did *without
  waiting for a tick* — and the fog's viewers, set from the same positions in `CryptSave.apply`,
  agree with what the player sees. `AgentSyncTest.facingsSurviveASaveAndLandOnTheEntitiesAtOnce`
  checks it, and that a *wanted* facing is still wanted: standing still, the entity keeps turning
  toward it after the load.

### 11.6 Bodies and counts (`ENEM`): `Enemies`

Since M22 a skeleton's state is in the registry's stores (`Monster`, `Mind`, `Sight`, `Flash`;
chapter [22](22-components.md)), which the `COMP` chunk holds.
[`Enemies`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/Enemies.java) keeps the few
whole-population numbers and what no store has: where a *dead* skeleton's body lies.

```java
    void write(ByteWriter w) {
        w.int32(count);
        w.int32(alive);
        w.int32(kills);
        int[] agent = game.monster().agent();
        for (int i = 0; i < count; i++) {
            int e = root[i];
            w.float32(world.transforms().position(e, 0));
            w.float32(world.transforms().position(e, 1));
            w.float32(world.transforms().position(e, 2));
            w.float32(agent[i] >= 0 ? sync.yaw(agent[i]) : deathYaw[i]);
        }
    }
```

A living skeleton's position is its agent's, which the crowd saves; writing it again here costs 12
bytes per skeleton and makes the record uniform. A dead one has no agent (`kill` removed it), so
its body's position is in the transform store only, and its facing in `deathYaw`, which `kill`
copied from the sync before unbinding. 16 bytes per skeleton: 4,716 for 294.

```java
    void read(ByteReader r) {
        int n = Math.min(count, r.int32());
        alive = r.int32();
        kills = r.int32();
        hovered = -1;
        java.util.Arrays.fill(enemyOfAgent, -1);
        MonsterStore monsters = game.monster();
        boolean[] hidden = game.sight().hidden();
        for (int i = 0; i < n; i++) {
            float x = r.float32();
            float y = r.float32();
            float z = r.float32();
            float yaw = r.float32();
            int a = monsters.agent()[i];
            int c = monsters.character()[i];
            if (a >= 0 && crowd.isActive(a)) {
                enemyOfAgent[a] = i;
                sync.bind(a, root[i], yaw);
                animator.setAgent(c, a);
            } else {
                monsters.agent()[i] = -1;
                animator.setAgent(c, -1);
                deathYaw[i] = yaw;
                world.transforms().setPosition(root[i], x, y, z);
                world.setYaw(root[i], yaw);
            }
            world.setGroupHidden(root[i], hidden[i]);
        }
    }
```

This is the method that makes the load order of §12.5 necessary. It reads `monsters.agent()[i]`
and `sight().hidden()[i]`, which `COMP` restored, and asks `crowd.isActive(a)`, which `CROW`
restored; then it **rebinds**: a living skeleton's agent is bound to its root entity in the sync and
pointed at by its animator slot, the agent → skeleton table is rebuilt (`enemyOfAgent`, filled with
−1 first so a dead skeleton's old slot does not answer to it), and a dead one gets its body placed
and its facing kept for the next save. `setGroupHidden` hides what the fog had hidden, which the
renderer and the animation system both honour (a hidden corpse's animation instance is inactive,
chapter 09). `hovered = -1`: a derived value, reset.

### 11.7 The party (`PRTY`), the cast counter (`SPEL`) and the demo's own (`GAME`)

[`Party`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/Party.java):

```java
    void write(ByteWriter w) {
        w.bools(selected);
        w.int32(formation);
        w.floats(cooldown);
        mover.write(w);
    }

    /** Reads what {@link #write} wrote and re-derives who is down from the rules. */
    void read(ByteReader r) {
        r.boolsInto(selected);
        formation = Math.clamp(r.int32(), 0, formations.length - 1);
        r.floatsInto(cooldown);
        mover.read(r);
        hovered = -1;
        for (int i = 0; i < SIZE; i++) {
            down[i] = !combat.canAct(combat.partyCreature(i));
        }
        rebuildSelection();
    }
```

The selection (six booleans), the formation index (clamped: a save from a build with more
formations must not index past the table), the strike cooldowns and the formation walk. `down[]` —
who cannot act — is **re-derived from the rules**, which is why `PRTY` is read after `RULE`;
`rebuildSelection` then recomputes the selected-and-not-down list the orders use. The party's
agents and roots are not written: they are slots 0–5 and the roots the level built.

[`Spells`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/Spells.java) writes one
`int`, the cast counter; the fireballs in flight are rows of the `Fireball` store in `COMP`. Its
`clear()` destroys every fireball entity before `COMP` is read (so the saved rows get fresh ones
through `CryptIds`, §8.2) and `read` turns targeting off: a load in the middle of aiming must not
leave the aim template on the floor.

The `GAME` chunk is the demo's own scalars, written by `DemoGame` through `CryptSave.Host`:

```java
    @Override
    public void writeGame(ByteWriter w) {
        w.float64(time - (loadedTime < 0 ? 0 : loadedTime));
        w.bool(following);
        w.float32(camera.target.x);
        w.float32(camera.target.y);
        w.float32(camera.target.z);
        w.float32(camera.yaw);
        w.float32(camera.distance);
        rng.saveState(rngState);
        w.longs(rngState);
    }

    @Override
    public void readGame(ByteReader r) {
        double played = r.float64();
        loadedTime = time - played; // play time continues from the save's
        following = r.bool();
        camera.target.set(r.float32(), r.float32(), r.float32());
        camera.yaw = r.float32();
        camera.distance = r.float32();
        if (r.longsInto(rngState) == 4) {
            rng.restoreState(rngState);
        }
    }
```

Play time is stored as seconds played (a `double`, L00 §7.3: the demo's clock accumulates for
hours) and restored by moving `loadedTime`, so the counter continues from the save's value. The
camera is its target, yaw and distance — the three numbers the orbit camera of chapter
[04](04-cameras-and-projection.md) is built from; the controller's smoothing goals are re-synced
after the load (`afterLoad`, §15). The demo's own generator (the one that seeds skeleton timers,
cooldowns and respawn points) is the second xoshiro state in the file; the rules have their own.
The chunk is 65 bytes, and nothing in it is aligned after the `bool` at offset 8 — which the byte
stream does not mind (L04 §5.2's honest note).

### 11.8 What a bigger game would add: quests, dialogue, flags

The `script` module's three state holders have the same two methods for a game that uses them;
the demo has no quests and writes none of them:

- [`Quests.write`](../../modules/script/src/main/java/dev/jeddy/script/Quests.java): the format,
  every started quest **by id** with its stage, and the journal as (quest id, stage) pairs; `read`
  skips a quest the data no longer has — the same decision as the rules' content ids.
- [`Conversation.write`](../../modules/script/src/main/java/dev/jeddy/script/Conversation.java):
  whether a dialogue is active and, if so, the node **by id**, the line, and the visible choices
  as indices *within the node*, so a re-cooked dialogue with the same nodes resumes; the dialogue
  itself is the game's to name before it. `read(r, dialogue)` ends the conversation when the node
  is gone.
- [`Flags.write`](../../modules/script/src/main/java/dev/jeddy/script/Flags.java): every flag as a
  64-bit key and an `int` value, read back replacing all.

Each starts with its own `SAVE_FORMAT` and would take a chunk of its own (`QUES`, `DLOG`, `FLAG`)
in a game's composition.

---
## 12. Step 5: the composition, `CryptSave`

### 12.1 The problem

Ten systems can each write and read themselves; something must call them in an order, give each a
chunk, fill the meta block, and know where the files are. That is
[`CryptSave`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/CryptSave.java), the
demo's **composition**. There is no "chunk registry" object: the composition *is* the two lists of
calls, `capture` and `apply`, side by side in one file, so the writer and the reader of every chunk
and their order are visible in one place. A registry of callbacks would hide the order behind a
table, cost an object or a lambda per entry, and make the load order (§12.5) something you would
have to reconstruct.

### 12.2 Tags, the version and the fields

```java
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
```

- **`Host`** is the one thing `DemoGame` gives the composition that no system owns: its own
  scalars (§11.7) and the slot list's title. An interface rather than a reference to `DemoGame`
  keeps `CryptSave` testable without the whole game.
- **Tags are computed with `SaveFile.tag`** (L04 §11.3), so `FOG ` with its trailing space is the
  four bytes `46 4f 47 20` in the file and never `GOF?` by a typing slip. They are `static final
  int`s, which the JIT folds into constants.
- **One `VERSION`** for all chunks: §5.3's wrinkle.

```java
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
```

- **One `SaveWriter`, kept for the life of the level**, sized at 256 KiB so that the demo's
  111,904-byte payload fits from the first capture and the writer never grows (§3.1: a capture
  that fits the previous buffer allocates nothing). A game would size it from its largest expected
  save, or let the first save grow it.
- **`text`**, a kept `StringBuilder` of 96 characters, is where every meta value is formatted:
  `append(long)` writes digits into the builder's own array, and `SaveWriter.meta` takes a
  `CharSequence`, so no `String` is ever made (L04 §9.4; the pitfall of §19.1 is what happened
  before it existed).
- `lastError` is a `String` because a load may allocate and a failure message is for a person.

The constructor stores the references and builds the ids (`ids = new CryptIds(loader, party,
enemies, spells)`); `slots = new SaveSlots(directory)` creates the job with it.

### 12.3 `capture`

```java
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
```

Six meta entries, then ten chunks, then `finish` (which closes the last chunk and patches the meta
count, L04 §12). Each `writer.chunk(tag, VERSION)` closes the previous chunk — patching its length
and padding it — and returns the payload writer for the next system to write into. The meta
entries are what the slot list shows and what `apply` checks: `game` and `level` identify the
save, `title` is the host's ("Crypt, round 1, 5 slain"), `playSeconds` and `savedAt` are decimal
text (L04 exercise 7 says why text), `kills` is a number a menu might show. For §1's save the meta
block is 4 + 13 + 32 + 28 + 17 + 24 + 10 = 128 bytes, a multiple of 8 by luck, so no padding.

Why **write in the load order** when writing has no dependencies? Because a file is read by people
too: a hex dump of the payload shows the chunks in the order they are applied, and
`differenceFrom` (§12.6) reports the *first* chunk that differs, which is then the first system
whose restore went wrong.

### 12.4 The payload, byte by byte

For §1's save (294 skeletons, 300 agents, after 18 s of auto-turns: 5 dead), the payload is
111,904 bytes laid out as:

| Offset | Chunk | Data bytes | Padded + 16-byte header | Formula (n = 294 skeletons, a = 300 slots, 295 live agents) |
|---|---|---|---|---|
| 0 | `GAME` | 65 | 88 | 8 + 1 + 5 × 4 + (4 + 4 × 8) |
| 88 | `COMP` | 25,665 | 25,688 | 4 + Fireball 137 + Flash 4,741 + Mind 10,682 + Monster 7,121 + Sight 2,980 |
| 25,776 | `CROW` | 25,972 | 25,992 | 12 + 88 × 295 |
| 51,768 | `ENEM` | 4,716 | 4,736 | 12 + 16 n |
| 56,504 | `SYNC` | 2,708 | 2,728 | 8 + 9 a |
| 59,232 | `ANIM` | 4,204 | 4,224 | 4 + 14 a |
| 63,456 | `RULE` | 45,589 | 45,608 | 44 (format, generator, count) + 40,270 (300 creature records, 134 B on average: a minion 121, Aldric 175) + 1,622 (encounter: 18 + 3 × 32 + 1,204 + 304) + 3,653 (`Combat`'s own: 17 + 3 × 1,204 + 24) |
| 109,064 | `PRTY` | 198 | 216 | (4 + 6) + 4 + (4 + 24) + (8 + 6 × 12 + 4 + 18 × 4): a walk of six in progress |
| 109,280 | `SPEL` | 4 | 24 | one `int`, padded to 8 |
| 109,304 | `FOG ` | 2,584 | 2,600 | 12 + 4 + 321 × 8 |
| 111,904 | end | | | the header's `payloadBytes`, `0x1b520` |

Every number in the "Data bytes" column was read back from the file with the engine's reader
(§20); every formula is §9–§11's layouts. `COMP` and `CROW` are each a quarter of the save, `RULE`
is two fifths. Inside `RULE`, the strings with their `u16` lengths — names and content ids such as
`"skeletalWarrior"` and `"improvedInitiative"`, the price of D115 — are 15,626 bytes, a third; the
ten spell-slot `int`s per creature alone are another 12,000 (300 × 10 × 4, almost all zeros for
skeletons). The encounter's 1,204-byte budget array and `Combat`'s three 1,204-byte tables are
written for every handle up to the high-water mark (§10.5–10.6). Repeated strings and runs of zeros
are what zstd compresses best: the payload stores as 16,547 bytes, 6.8 times smaller, and the file
is 6.7 times smaller than the payload. The container's header for this file reads `0a 00 00 00` (10 chunks), `80 00 00
00` (a 128-byte meta block), `20 b5 01 00 …` (111,904 payload bytes) and `a3 40 00 00 …` (16,547
stored), and 48 + 128 + 16,547 = 16,723, the file's length.

### 12.5 `apply`: the load order, and why

```java
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
```

The order is a dependency graph, flattened. Each step, and what it needs:

| Step | Needs | Because |
|---|---|---|
| the `level` check | nothing | a save for another level would resolve its object ids against the wrong placements and its spawn indices against the wrong tables; refuse before touching anything (and the saved level must *equal* the running one: "save-game compatibility across levels" is not in M16) |
| `GAME` | nothing | play time, camera and the demo's generator depend on nothing; nothing in `apply` rolls dice, so its position is convention |
| `spells.clear()`, `ids.beginLoad()` | before `COMP` | `Store.read` clears *rows*, not entities: the fireball entities of the running game must be destroyed in the pool, or they would survive as entities with no components; the id map forgets the previous load |
| `COMP` | the party's and skeletons' roots (built with the level) | gives every skeleton its saved agent slot, state, timers, hidden flag; the fireballs their fresh entities |
| `CROW` | nothing from the save | agents back in their slots; must precede everything that asks the crowd a question |
| `ENEM` | `COMP` (`Monster.agent`, `Sight.hidden`), `CROW` (`isActive`) | rebinds agents to entities in the sync and the animator; places the dead bodies |
| `SYNC` | `ENEM` (the bindings), `CROW` (the positions) | facings land on *bound* entities only, and the entity is moved to its agent |
| `ANIM` | `ENEM` (`animator.setAgent`), `CROW` (the velocities) | `locomotion` reads the restored agent's speed to pick idle, walk or run |
| `RULE` | `COMP` (`enemies.agent(i)` for the rebind) | creatures rebuilt at their handles; `Combat` rebinds each skeleton's creature to its agent |
| `PRTY` | `RULE` (`combat.canAct`), `CROW` (the members' targets) | who is down is re-derived; the formation walk resumes on restored targets |
| `SPEL` | nothing | the counter; the rows came with `COMP` |
| `FOG ` | nothing | the explored bits; `fog.invalidate()` forces the feed to repack for the renderer |
| the epilogue | everything | `updateTransforms` propagates the positions `SYNC` and `ENEM` set (on the workers); the viewers are placed where the crowd put the party back; `fog.update()` recomputes the *visible* bits; stage 1 of the registry (`see` on the workers, then `show`) decides which skeletons the party sees and hides the rest |

The epilogue is where two of the round-trip bugs were (§19.1): the viewers were once not moved
before the fog recomputed, so the visible bits were those of the party's position a second after
the save. A chunk that is *missing* — an older save without `SPEL`, say — is skipped with a warning
and the running game keeps that system's values; a chunk whose version is newer than `VERSION` is
refused:

```java
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
```

`SaveFile.chunk(tag)` is a linear search over the chunk list — ten entries, called ten times: not
worth a map. The reader it returns is the chunk's `slice` (L04 §10.3), so `Rules.read` cannot
read into `PRTY` by mistake.

`load(slot)` wraps `apply` for the game: it refuses a slot with no file, times the whole thing
(the read, the decompression and the apply) into `loadNanos`, and turns any `IOException` or
`RuntimeException` into `lastError` and a warning rather than letting it escape — a damaged save
must never take the game down. (What a failure *inside* `apply` leaves behind is §19.2.)

### 12.6 The whole-state check: `differenceFrom`

A load that restored everything will, captured again, reproduce the saved bytes exactly. That is a
stronger test than any assertion about a few fields, and the demo has it built in:

```java
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
```

It walks both payloads chunk by chunk — the same `[tag][version][length][reserved]` framing L04
§11.4 defined — and reports, per chunk, up to four differing *words* (`i = i / 4 * 4 + 3` rounds
`i` down to its 4-byte word and the loop's `i++` moves to the next, so a differing float is
reported once, not four times). For `COMP` it goes one level deeper with `storeAt`, which uses
`Store.skip` (§9.4) to find which store spans the byte and reports "store Mind, byte 1204 of its
10682". A `null` return is the line of §1: "save round trip exact". This method may allocate freely:
it runs once, in a scripted test.

## 13. Step 6: the background writer, `SaveJob`

### 13.1 Fields

[`SaveJob`](../../modules/save/src/main/java/dev/jeddy/save/SaveJob.java) is the writing half of
D118. Its state:

```java
public final class SaveJob {
    private static final Logger LOG = Log.logger("save");

    private byte[] meta = new byte[256];
    private int metaSize;
    private byte[] payload = new byte[64 * 1024];
    private int payloadSize;
    private int chunkCount;
    private int level = SaveFile.DEFAULT_LEVEL;
    // The worker: one daemon thread for the life of the job, parked between writes, so a submit
    // allocates nothing (a thread per save would: its object, its stack, its name). A platform
    // thread, not a virtual one: unparking a virtual thread submits its continuation to the
    // scheduler's pool, which wraps it in a task object (24 B per save, measured by the test).
    private Thread worker;
    private volatile Path target; // the request: set by submit, cleared by the worker when done
    private volatile boolean closed;
    private volatile Throwable error;
    private volatile long bytesWritten;
    private volatile long writeNanos;
    private long captureNanos;
```

- **The job's own copies** of the meta block and the payload, with their sizes and the chunk count:
  everything `SaveFile.encode` needs. They start at 256 bytes and 64 KiB and grow to the first
  capture that does not fit, doubling, after which a submit allocates nothing (§6.2). Why copy at
  all, rather than hand the writer's buffers to the worker? Because the game thread must be free to
  `reset()` and reuse its `SaveWriter` on the very next frame — an autosave timer or a second F5
  must not wait for the disk — and because the alternative, two writers swapped between the
  threads, is a second 256 KiB buffer *and* a protocol.
- **`target` is the whole protocol** (§6.5): non-null means "a write is requested or in progress",
  null means idle. It is `volatile` because two threads read and write it and because its write is
  the fence that publishes the copied buffers.
- `closed`, `error`, `bytesWritten` and `writeNanos` are `volatile` because the worker writes them
  and the game reads them (or the reverse) without any other synchronization; `captureNanos` is
  plain because only the submitting thread touches it. `level` is plain too — a change while a
  write is running is a benign race on an `int`, but one to know about.

### 13.2 `submit`: copy, publish, wake

```java
    public void submit(SaveWriter writer, Path target, long captureNanos) {
        if (closed) {
            throw new IllegalStateException("job closed");
        }
        await();
        writer.finish();
        this.captureNanos = captureNanos;
        metaSize = writer.metaSize();
        if (meta.length < metaSize) {
            meta = new byte[Math.max(metaSize, meta.length * 2)];
        }
        System.arraycopy(writer.metaBuffer(), 0, meta, 0, metaSize);
        payloadSize = writer.payloadSize();
        if (payload.length < payloadSize) {
            payload = new byte[Math.max(payloadSize, payload.length * 2)];
        }
        System.arraycopy(writer.payloadBuffer(), 0, payload, 0, payloadSize);
        chunkCount = writer.chunkCount();
        error = null;
        bytesWritten = 0;
        if (worker == null) {
            worker = Thread.ofPlatform().daemon(true).name("save-job").start(this::loop);
        }
        this.target = target; // the volatile write publishes the buffers to the worker
        LockSupport.unpark(worker);
    }
```

Line by line:

1. **`await()` first.** If a write is still running, wait for it: the arrays below are the ones
   the worker is reading. A player who saves faster than the disk writes gets a short wait, not a
   lost save and not a torn buffer.
2. **`writer.finish()`** closes an open chunk and patches the meta count, so the sizes read next
   are final (it is idempotent; `capture` already called it).
3. **Grow, then copy.** `System.arraycopy` is a `memcpy`: the game thread's part of the write is
   a copy at memory speed — the whole `submit` measured 15–16 µs for the demo's 111,904-byte
   payload once warm (§18).
4. **Reset the results** of the previous write, so a poll cannot read a stale error.
5. **Start the worker lazily**, once: `Thread.ofPlatform()` because of §6.4, `daemon(true)` so the
   JVM can exit with the job idle, named for the debugger and the log. (`this::loop` is a method
   reference evaluated once; it does not run per save.)
6. **Publish, then wake.** The volatile write of `target` is the fence of §6.5; `unpark` after it
   either wakes the parked worker or leaves it a permit.

The three-argument form carries the capture's time so that `captureNanos()` and `writeNanos()`
can be read together; the two-argument form passes 0.

### 13.3 `loop`: the worker

```java
    private void loop() {
        while (!closed) {
            Path t = target;
            if (t == null) {
                LockSupport.park(this);
                continue;
            }
            long t0 = System.nanoTime();
            try {
                byte[] file =
                        SaveFile.encode(meta, metaSize, payload, payloadSize, chunkCount, level);
                write(file, t);
                bytesWritten = file.length;
            } catch (Throwable e) {
                error = e;
                LOG.warn("save to " + t + " failed: " + e);
            } finally {
                writeNanos = System.nanoTime() - t0;
                target = null; // done: the volatile write publishes the results
            }
        }
    }
```

- **Read `target` once into a local** (`t`): the field could change under the loop, and the write
  must go to the path that was requested when the bytes were copied.
- **`park(this)`** parks with the job as the *blocker*, which thread dumps show ("parking to wait
  for <SaveJob>"); a spurious wake-up simply loops, finds `target` still null, and parks again.
- **`encode` allocates** (the compressed buffer, the file image: L04 §13) — on this thread, which
  has no budget. `write` is L04 §15's temporary file and atomic move, with one addition: it creates
  the directory first (`Files.createDirectories`), so the first save of a fresh install does not
  fail because `~/.local/share/crypt/saves` does not exist yet.
- **`catch (Throwable)`**, not `IOException`: an `OutOfMemoryError` in the compressor or an
  `IllegalStateException` from a bad level must not end the worker. If a throwable escaped the
  loop, the `finally` would still clear `target` — so `isDone()` would look fine — but the thread
  would be dead: the next `submit` would set `target`, `unpark` a thread that no longer exists, and
  its `await` (or the next `load`'s) would nap for ever, because nothing would ever clear `target`
  again. Catching everything keeps the worker alive and turns the failure into `error()`. The
  `finally` guarantees `target = null` after every attempt, and that volatile write publishes
  `bytesWritten`, `error` and `writeNanos` (§6.5).
- The warning is logged here, on the worker; the game reads `error()` when it polls.

### 13.4 Polling, waiting and closing

```java
    /** Whether no write is in progress (also before the first submit). */
    public boolean isDone() {
        return target == null;
    }

    /** Waits for the write in progress, if any (parks in short naps; a cold path). */
    public void await() {
        while (target != null) {
            LockSupport.parkNanos(this, 100_000);
        }
    }

    /** Stops the worker after the write in progress; the job takes no more submits. */
    public void close() {
        closed = true;
        await();
        Thread w = worker;
        if (w != null) {
            LockSupport.unpark(w);
        }
    }
```

- **`isDone`** is one volatile read: what a frame may call every time it wants to know whether it
  can show "saved" in the status line.
- **`await`** naps 100 µs at a time rather than being woken by the worker, because the worker
  would then need a reference to whoever waits; a nap loop is simpler and the path is cold (a load,
  a delete, a second save in flight, the menu's list after a save). Nothing in the engine calls it
  per frame.
- **`close`** sets the flag, waits for a write in progress, and unparks the worker so that its
  `while (!closed)` sees the flag and exits. A `submit` after `close` throws. §19.2 says who should
  call it and does not.

The accessors (`error()`, `bytesWritten()`, `writeNanos()`, `captureNanos()`, `payloadCapacity()`)
are plain reads of the volatile fields, and `toString` prints `SaveJob[idle, 16723 B]` for a log.

### 13.5 Why not …

| Alternative | Why not |
|---|---|
| write on the game thread | milliseconds, unbounded, in a frame (§6.2) |
| a job on the job system | workers never block on IO (§6.3) |
| a virtual thread | 24 B per unpark (§6.4) |
| a thread per save | its object, stack and name per save (§6.4) |
| an `ExecutorService` | a `Runnable`/`Future` object per submit, and a queue of them |
| a callback from the worker into the game | the game thread owns its state; a callback on another thread would need a lock or a queue. The game polls (D118) |
| hand the writer's buffers to the worker (no copy) | the game could not reuse the writer until the write finished (§13.1) |
| `synchronized` and `wait`/`notify` | a monitor, a lock per poll, and a `notify` that needs the lock held; `volatile` + `park` is the smaller tool for one flag and one waiter |

## 14. Step 7: slots and the directory

### 14.1 `SaveSlots`

[`SaveSlots`](../../modules/save/src/main/java/dev/jeddy/save/SaveSlots.java) names the files of
one directory and owns one `SaveJob`:

```java
public final class SaveSlots {
    private static final Logger LOG = Log.logger("save");

    public static final String QUICK = "quick";
    public static final String AUTO = "auto";
    public static final String EXTENSION = ".jsave";
    private static final String NAMED_PREFIX = "slot-";

    /** One slot as the list shows it: its name, file, meta entries, time and size. */
    public record SaveInfo(
            String slot, Path path, Map<String, String> meta, FileTime time, long bytes) {
        /** A meta value or {@code fallback}. */
        public String meta(String key, String fallback) {
            String v = meta.get(key);
            return v == null ? fallback : v;
        }
    }

    private final Path directory;
    private final SaveJob job;
    private final Path quickPath; // resolved once: an autosave frame allocates nothing
    private final Path autoPath;

    /** Slots in {@code directory}, written by {@code job}. */
    public SaveSlots(Path directory, SaveJob job) {
        this.directory = directory;
        this.job = job;
        quickPath = directory.resolve(QUICK + EXTENSION);
        autoPath = directory.resolve(AUTO + EXTENSION);
    }

    /** Slots in {@code directory} with a job of their own. */
    public SaveSlots(Path directory) {
        this(directory, new SaveJob());
    }
```

`quickPath` and `autoPath` are resolved **once**: `Path.resolve` allocates a new `Path`, and the
autosave frame must not (§19.1 found this). A named slot's path is resolved on demand, because a
name comes from the player and naming a save is a cold path.

```java
    /** A slot name reduced to {@code [A-Za-z0-9_-]}, at most 48 characters, never empty. */
    public static String sanitize(String name) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < name.length() && b.length() < 48; i++) {
            char c = name.charAt(i);
            if (c >= 'a' && c <= 'z'
                    || c >= 'A' && c <= 'Z'
                    || c >= '0' && c <= '9'
                    || c == '-'
                    || c == '_') {
                b.append(c);
            } else if (c == ' ') {
                b.append('_');
            }
        }
        return b.isEmpty() ? "save" : b.toString();
    }

    /** The file of {@code slot} ({@link #QUICK}, {@link #AUTO} or a name, sanitized). */
    public Path pathOf(String slot) {
        if (QUICK.equals(slot)) {
            return quickPath;
        }
        if (AUTO.equals(slot)) {
            return autoPath;
        }
        return directory.resolve(NAMED_PREFIX + sanitize(slot) + EXTENSION);
    }
```

`sanitize` is a security boundary as much as a tidiness one: the name becomes part of a file path,
and `"../../"` must not walk out of the save directory. The whitelist keeps letters, digits, dashes
and underscores, turns spaces into underscores, drops everything else (so a `/`, a `:` or a `é`
cannot reach the file system), caps the length at 48, and never returns an empty name. "Before the
altar" becomes `slot-Before_the_altar.jsave`; `"../../"` becomes `slot-save.jsave`.
`slotOf(path)` is the inverse for the listing: `quick`, `auto`, or the part after `slot-`; any other
`.jsave` is not a slot.

```java
    public List<SaveInfo> list() throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<SaveInfo> out = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + EXTENSION)) {
            for (Path p : files) {
                String slot = slotOf(p);
                if (slot == null || !Files.isRegularFile(p)) {
                    continue;
                }
                try {
                    SaveFile f = SaveFile.peek(p);
                    out.add(
                            new SaveInfo(
                                    slot,
                                    p,
                                    f.meta(),
                                    Files.getLastModifiedTime(p),
                                    Files.size(p)));
                } catch (IOException e) {
                    LOG.warn("skipping " + p + ": " + e.getMessage());
                }
            }
        }
        out.sort((a, b) -> b.time().compareTo(a.time()));
        return Collections.unmodifiableList(out);
    }
```

- A directory that does not exist yet is an empty list, not an error: a fresh install has no saves.
- The glob `*.jsave` leaves `quick.jsave.tmp` out; `slotOf` leaves `notes.jsave` out.
- **`SaveFile.peek`** reads the header and the meta block only (L04 §14), so listing fifty saves
  reads fifty small prefixes. A file that is not a save — the test writes three bytes into
  `slot-broken.jsave` — is logged and skipped, so one damaged file does not empty the menu.
- Sorted **newest first by the file's modification time**, not by the `savedAt` meta entry: the
  file system's clock is the one that cannot be absent from an older save. The list allocates (a
  menu lists once when it opens).

```java
    /** Hands {@code writer}'s capture to the job for {@code slot}; returns the job to poll. */
    public SaveJob save(String slot, SaveWriter writer, long captureNanos) {
        job.submit(writer, pathOf(slot), captureNanos);
        return job;
    }

    /** Reads {@code slot} whole. */
    public SaveFile load(String slot) throws IOException {
        job.await(); // a save of this slot may still be writing
        return SaveFile.read(pathOf(slot));
    }

    /** Deletes {@code slot}'s file; returns whether there was one. */
    public boolean delete(String slot) throws IOException {
        job.await();
        return Files.deleteIfExists(pathOf(slot));
    }
```

`load` and `delete` **wait for the job first**: a quick load a few milliseconds after a quick save
must read the new file, not the old one, and a delete must not race the rename. These, with a
second `submit` and the menu's refresh after a save, are the four places the game ever waits for a
write — all cold paths.

### 14.2 `SaveDirectory`

```java
    public static Path of(String game) {
        String explicit = System.getProperty("jeddy.saves.dir");
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit);
        }
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData, game, "saves");
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Path.of(xdg, game, "saves");
        }
        String home = System.getProperty("user.home", ".");
        return Path.of(home, ".local", "share", game, "saves");
    }
```

The property wins (tests and launchers), then Windows' roaming application data (so a domain profile
carries the saves), then the XDG data directory, then its documented default. `isBlank` treats an
environment variable set to the empty string as unset, which is how shells leave them. The test
`theDirectoryFollowsThePropertyAndTheEnvironment` checks the property and that the default ends in
`crypt/saves`.

### 14.3 `SaveFormatException`

[`SaveFormatException`](../../modules/save/src/main/java/dev/jeddy/save/SaveFormatException.java)
extends `IOException` — a *checked* exception, because a caller that reads a file must decide what
to do when it is not one, and the compiler should make it. Its Javadoc draws the line that §12.5
relies on: the container's problems (magic, checksums, sizes, a newer version, a chunk that
overruns) are this exception; a problem *inside* a chunk's stream is the `Truncated` or
`IllegalStateException` the system itself throws, and `CryptSave.load` catches both kinds.

## 15. Step 8: the demo's keys, menu, autosave and scripted check

### 15.1 Requests, then one place that runs them

Every way to ask for a save or a load — F5 and F9 in `handleInput`, the menu's buttons, the autosave
timer, the scripted run — sets a **pending request**, and one method, `saves(dt)`, runs it
between ticks (§6.1):

```java
    private void saves(float dt) {
        if (demoUi.saveRequested != null) {
            pendingSave = demoUi.saveRequested;
        }
        if (demoUi.loadRequested != null) {
            pendingLoad = demoUi.loadRequested;
        }
        if (demoUi.deleteRequested != null) {
            try {
                saves.slots().delete(demoUi.deleteRequested);
                demoUi.saveStatus = "Deleted " + demoUi.deleteRequested;
            } catch (java.io.IOException e) {
                demoUi.saveStatus = "Cannot delete: " + e.getMessage();
            }
            demoUi.saves = saves.list();
        }
        if (options.autosaveSeconds > 0 && ready) {
            autosaveTimer += dt;
            if (autosaveTimer >= options.autosaveSeconds) {
                autosaveTimer = 0;
                pendingSave = SaveSlots.AUTO;
            }
        }
        if (options.saveAt > 0 && ready) {
            double since = time - loadedTime;
            if (!scriptedSaveDone && since >= options.saveAt) {
                scriptedSaveDone = true;
                pendingSave = SaveSlots.QUICK;
            } else if (scriptedSaveDone && !scriptedLoadDone && since >= options.saveAt + 1) {
                scriptedLoadDone = true;
                pendingLoad = SaveSlots.QUICK;
            }
        }
```

The keys are two lines in `handleInput`: `if (in.wasKeyPressed(Scancode.F5)) { pendingSave =
SaveSlots.QUICK; }` and the same for F9 and `pendingLoad`. The UI's requests are strings the menu
set this frame (`DemoUi` clears them at the start of each frame). `ready` — the level is built and
every renderable has loaded — gates the autosave and the script: "a save made while the level is
still streaming in" is not in M16, and the demo refuses it by never requesting one.

```java
        if (pendingSave != null) {
            String slot = pendingSave;
            pendingSave = null;
            saves.save(slot);
            if (options.saveAt > 0 && scriptedPayload == null) {
                scriptedPayload = saves.payloadCopy();
            }
            demoUi.saveStatus =
                    "Saved "
                            + slot
                            + " ("
                            + saves.payloadBytes() / 1024
                            + " KB captured in "
                            + saves.captureNanos() / 1000
                            + " us)";
            demoUi.log.start().add("Saved ").add(slot).end();
            LOG.info(demoUi.saveStatus);
            if (demoUi.menuOpen) {
                saves.job().await(); // the list below should show the new file
                demoUi.saves = saves.list();
            }
        }
```

`saves.save(slot)` is `capture()` plus `slots.save(...)`: the capture and the hand-over, and the
frame goes on. The status line and the log line are the one allocation a save frame makes: 176 B
per save frame, measured (§18), and *attributed* — not itemised by measurement — to the status
`String` and its `byte[]` (about 80 B), `LOG.info`'s empty varargs array (16 B), the logger's
`Log.Record` (40 B) and the log queue's node. It is a cold path by design, once per save, and not
part of the capture. The scripted run keeps a copy of the first capture's payload for the
comparison. If the pause menu is open, the game **waits for the write** and lists again, so the
player sees the new file in the list at once.

```java
        if (pendingLoad != null) {
            String slot = pendingLoad;
            pendingLoad = null;
            if (saves.load(slot)) {
                afterLoad();
                demoUi.saveStatus = "Loaded " + slot;
                demoUi.log.start().add("Loaded ").add(slot).end();
                LOG.info("loaded " + slot + " in " + saves.loadNanos() / 1000 + " us");
                if (scriptedPayload != null) {
                    // The scripted check: a capture right after the load must reproduce the
                    // saved bytes, chunk for chunk (the whole-state round trip).
                    saves.capture();
                    String difference = saves.differenceFrom(scriptedPayload);
                    if (difference == null) {
                        LOG.info(
                                "save round trip exact: "
                                        + saves.payloadBytes()
                                        + " bytes captured again in "
                                        + saves.captureNanos() / 1000
                                        + " us");
                    } else {
                        LOG.error("save round trip differs: " + difference);
                        failed = true;
                    }
                    scriptedPayload = null;
                }
            } else {
                demoUi.saveStatus = "Cannot load " + slot + ": " + saves.lastError();
                demoUi.log.start().add("Cannot load ").add(slot).end();
            }
        }
        if (demoUi.menuOpen && !menuListed) {
            demoUi.saves = saves.list();
            menuListed = true;
        } else if (!demoUi.menuOpen) {
            menuListed = false;
        }
    }
```

A load replaces the state in place and the frame carries on with it; `afterLoad` re-derives what
the frame caches outside the save (the turn-based flag the HUD shows, the flood-fill member, the
range boundary, the click marker's age, the spell targeting, the party's highlight styles, the
camera controller's smoothing goals). `failed = true` on a differing round trip makes the scripted
run exit with an error, which is how CI would notice. The menu lists the slots once each time it
opens (`menuListed`), not every frame: a listing reads files.

### 15.2 The pause menu

`DemoUi.savesSection` (chapter [13](13-ui.md), on the demo's screens) is a *Saves* heading, a row
with *Quick save (F5)* and *Quick load (F9)* (the latter enabled only when a quick slot exists), a
row with a 48-character name field and *Save as* (pressing Enter in the field submits too), the
status line, and a scrolling list of the slots from `demoUi.saves` — each row the slot's name, its
`title` meta entry, *Load* and *Delete*. The buttons only set `saveRequested`, `loadRequested` and
`deleteRequested`; nothing in the UI touches a file, which keeps the UI pass allocation-free and
the file work in one place.

### 15.3 The scripted round trip

`--save-at S` is the chapter's proof: at $S$ seconds after the level loaded it quick-saves and keeps
the payload; at $S + 1$ it quick-loads, captures again and compares (§12.6). One more thing has to
be true for the comparison to be exact, and `build` sets it when the option is on:

```java
        if (options.saveAt > 0) {
            // The round-trip check compares a capture after the load with the saved bytes; the
            // fog's visible bits are derived, and exactly so only when every viewer rescans as
            // soon as it moves (the default skips moves under 0.25 m: the saved bits lag).
            level.visibility.setMoveThreshold(0);
        }
```

The fog's visible bits are recomputed only when a viewer has moved more than the **move
threshold** (chapter [11](11-physics-navigation-crowds.md), on the fog of war): 0.25 m is
`Visibility`'s default, and the demo sets 0.5 m (`Level`'s constructor,
`visibility.setMoveThreshold(0.5f)`). So the bits at save time can lag the viewers slightly, and a
fresh recompute after the load — which `apply` always does — would differ at the edge *even though
nothing is wrong*. A threshold of 0 makes the derivation exact for the check; play keeps 0.5 m.
(The code comment above still says "0.25 m", the engine default, not the demo's value: §19.2.) This is the general lesson of §19.1: a byte comparison must compare
*state*, and anything derived must be derived the same way on both sides.

---
# Part C — Using it and proving it

## 16. A save and a load, end to end

### 16.1 A save

Follow F5 through the engine, with the times measured in §18 on this guide's two-core container
(294 skeletons; "warm" means after a few dozen saves, once the JIT has compiled the path):

```
GAME THREAD (frame N)                                    SAVE-JOB THREAD
 handleInput: F5 → pendingSave = "quick"
 game.update (the registry's per-frame behaviours)
 saves(dt):
   CryptSave.capture()                 0.6–1.1 ms warm   (parked)
     writer.reset(); ids.beginCapture()
     6 meta entries into the kept StringBuilder
     10 × system.write(writer.chunk(TAG, 1))
     writer.finish()
   SaveSlots.save → SaveJob.submit     15 µs warm
     await() (idle: returns at once)
     arraycopy meta, payload → the job's arrays
     target = quick.jsave (volatile)  ──unpark──▶  loop wakes
   status line, log record            176 B            SaveFile.encode:
 animator.update, camera, audio, UI …                     zstd level 3, two CRC-32Cs
 extract → packet → publish                               Files.write(quick.jsave.tmp)
frame N+1 …                                               Files.move(ATOMIC_MOVE)
 (isDone() true some frames later)    ◀── volatile ──     target = null        1.1–2.3 ms warm
```

The game thread's whole cost is the capture and a 15 µs copy; the 1–2 ms of compression and file
IO happen on the other thread while the next frames are simulated. Nothing waits: `isDone()` is
one volatile read, and the demo does not even poll it unless the menu is open.

### 16.2 A load: what comes back exactly, what is recomputed, what is dropped

F9 runs `SaveSlots.load` (which waits for a write in progress), `SaveFile.read` (the checks of L04
§14, the decompression), `CryptSave.apply` (§12.5) and `afterLoad`, all on the game thread, in 21 ms
for 294 skeletons (§18). What the player gets back:

| Restored **exactly**, bit for bit | Recomputed from it, by the load or the next tick | Not in the save, reset or left as it was |
|---|---|---|
| every component row (`Mind` states and timers, `Sight.hidden`, `Flash`, the fireballs in flight) | the fog's *visible* bits and which skeletons are hidden (`fog.update`, stage 1 `see`/`show`) | the combat log's text, and the events the rebuild raised (dropped) |
| every agent's position, velocity, parameters, target and target state | every agent's corridor (re-solved before `read` returns) | particles already emitted, a fireball's sound in flight, the ambience |
| every facing, wanted facing and whether one is wanted | the world transforms (`updateTransforms`) and the entities' positions (from the agents) | the hovered skeleton and member (−1), the spell targeting (off), the click marker |
| each character's mode, stance, action slot, time left and clip phase | the blend weight and rate (from the agent's speed), the joint matrices, the crossfade: a locomotion state fades in over 0.2 s from the pose shown before the load | the `Animations` slot numbers (recreated at spawn) |
| every creature's content id, hit points, temporary hit points, slots, conditions and rounds, kept modifiers, feats, spells, equipment | every stat (armour class, attack bonus, maximum hit points: from the *current* rulebook), the content-granted modifiers | a creature whose content id a patch removed (blank, warned) |
| the encounter's order, initiatives, round, turn, budgets; the clock; the fight's tables; the party's selection, formation, cooldowns and walk; the cast counter; the explored bits; play time; the camera; both generators | who is down (`Party.read`), the selection list, the pacing of the walk, the party's highlight styles, the camera controller's goals (`afterLoad`), the turn-based flag the HUD shows | the editor's state, the undo history, the menu, the profiler, the settings |

Two rules of thumb fall out. *Everything a `write` copied comes back the same bytes*, which the
round-trip check holds the demo to. *Everything else is derived again*, and the load must derive it
in the order the dependencies run (§12.5), or it derives it from the old state — the fog from the
old viewer positions was the first bug the check found (§19.1).

## 17. The tests, walked through

Every claim in Parts A and B has a test. All of them passed on this container on 2026-10-14
(`SaveFileTest` 8, `SaveSlotsTest` 5, `SavesTest` 4, `GeneratedGameTest` 12, `CrowdTest` 11,
`FormationTest` 5, `VisibilityTest` 7, `AgentSyncTest` 2).

### 17.1 The `save` module

[`SaveFileTest`](../../modules/save/src/test/java/dev/jeddy/save/SaveFileTest.java) is lesson 4's
(L04 §18): the container's round trip, `peek`, padding, damage reported by what it broke, hostile
sizes, a capture that allocates nothing, the golden `v1.jsave`.
[`SaveSlotsTest`](../../modules/save/src/test/java/dev/jeddy/save/SaveSlotsTest.java) is this
chapter's:

- **`namesAreSanitizedAndSlotsMapToFiles`**: `quick` and `auto` map to their files; "Before the
  altar" to `slot-Before_the_altar.jsave`; `"../../"` sanitizes to `save`; `"a-b_c9!?"` to
  `a-b_c9`; a hundred `x`s are cut at 48; `slotOf` recognizes the three shapes and rejects
  `notes.txt` and `other.jsave`.
- **`saveListLoadAndDelete`**: a directory that does not exist lists empty; a quick save and a
  named save are written and waited for (`.await()`), with `error()` null, `bytesWritten()` past
  the header and `captureNanos()` the 1234 the test passed; the quick file's modification time is
  pushed back to 1970 and a `notes.txt` and a three-byte `slot-broken.jsave` are planted; the list
  has two entries, the broken file skipped, the named save first (newest), its `title` meta read
  back, a missing meta key giving the fallback; `load` returns the named file's chunk; `delete`
  returns true then false; `load` of the deleted slot throws `IOException`.
- **`aFailedWriteKeepsThePreviousFileAndReportsTheError`** is §6.6's first row made real: after a
  good save, the test creates a *directory* named `quick.jsave.tmp`, so `Files.write` to the
  temporary file fails; the job reports the error, `bytesWritten()` is 0, and the quick save's
  bytes are unchanged. The directory removed, the next save succeeds and the temporary file is
  gone (moved, not copied).
- **`submitAllocatesNothingOnceTheBuffersFit`** is D118's proof. After one submit has grown the
  job's buffers, `Allocations.assertNoAllocation("capture and submit", 6, 20, …)` runs a capture
  of two chunks and a `submit` twenty times per round — each `submit` waits for the previous write,
  so a round is twenty files — and demands a round of 0 B on the calling thread. This is the test
  that measured the virtual thread's 24 B (§6.4): with `Thread.ofVirtual()` in `submit`, the
  `AdaptedRunnableAction` that each `unpark` allocates is on *this* thread's counter, because the
  game thread is the one that calls `unpark`. After `close()`, a `submit` throws.
- **`theDirectoryFollowsThePropertyAndTheEnvironment`**: §14.2.

### 17.2 The rules, and the golden record decoded

[`SavesTest`](../../modules/rules/src/test/java/dev/jeddy/rules/SavesTest.java) builds a small
fight in `play`: Aldric and Cassius (characters), a skeletal warrior (a monster) and a hand-made
giant rat with a natural-armour modifier; an encounter started and advanced two turns; Aldric hurt
for 7, shaken for 3 rounds, taught *dodge* and given a greataxe; the warrior slowed indefinitely
and down one attack of opportunity; Cassius with 4 temporary hit points for 5 rounds and *shield*
cast on himself; a standard action spent.

- **`creaturesEncounterAndGeneratorRoundTrip`**: the whole write (rules, encounter, a clock) under
  `Allocations.assertNoAllocation` — 0 B, strings included (§10.2) — then read into a *fresh*
  `Rules` with another seed. Every stat of every creature agrees (`assertSame` compares teams,
  names, all stats, hit points, flags, feats, spells, slots, equipment, conditions, the full-attack
  count and the armour class), the encounter's order, initiatives and budgets agree, the clock is
  2.5, **the next ten d20s agree**, and the shaken condition ends on the same turn in both games
  over sixteen turns.
- **`creaturesAreRebuiltFromThePatchedContentWithTheirOldWounds`** is §4.4's promise. It writes the
  save, then copies the demo's rulebook to a temporary directory with one edit — `"hp": 16` becomes
  `"hp": 30` in `monsters.json` — cooks it in memory (`DemoContent.build`), and reads the save into
  rules built on the patched content: the warrior's maximum hit points are 14 higher, its wound is
  the same, Aldric's armour class is unchanged, and the rat (no content row) keeps its base
  abilities and its hand-made modifier.
- **`creatureHandlesAreRestoredWhereTheyWere`** (§4.2, §10.4): handles 0 and 2 live, 1 destroyed,
  saved; a stranger built at 0 in the other game; after the read, 0 and 2 are the saved creatures,
  1 is free again and is what the next `create` returns. Then `createAt(7)` on a count of 3 leaves
  3–6 free (the next creates return 6, 5, 4: the free list is a stack) and `createAt(7)` over a live
  creature replaces it.
- **`formatOneFixtureStillReads`** reads
  [`rules-v1.bin`](../../modules/rules/src/test/resources/dev/jeddy/rules/fixtures/rules-v1.bin),
  937 bytes written by the build that introduced the format, and checks it value by value, and
  that a record claiming format 2 is refused with a message naming "format 2". Decoded with §10.1's
  layout (a small Python script over `struct`, offsets in bytes):

| Bytes | Record | What it holds |
|---|---|---|
| 0–43 | header | format 1; the generator's four `long`s (`0xb0e4d618b094d784`, …); 4 creatures |
| 44–236 | Aldric (193 B) | handle 0, team 0, `SAVED_CHARACTER` `aldric`; hp 40, no temporary hp; no slots used; acted; feats `weaponFocus`, `toughness`, `dodge`; no spells; equipment `greataxe`, `breastplate`, `heavyShield`; conditions `flatFooted` (indefinite, −1) and `shaken` (3 rounds); 0 kept modifiers |
| 237–487 | Cassius (251 B) | handle 1, `cassius`; hp 22, 4 temporary for 5 rounds; one level-1 slot used; feats `spellFocus`, `improvedInitiative`; six spells; `quarterstaff` and two empty slots; `flatFooted`; **one kept modifier**: stat 6 (AC), type 2 (shield bonus), +4, source kind 6 (a spell) `shield`, 10 rounds — the cast that nothing would rebuild |
| 488–642 | Skeletal warrior (155 B) | handle 2, team 1, `SAVED_MONSTER` `skeletalWarrior`; hp 16; one attack of opportunity used; acted; feat `improvedInitiative`; no equipment ids; `slowed` (indefinite); 0 kept modifiers — its stat block is the rulebook's |
| 643–830 | Giant rat (188 B) | handle 3, `SAVED_BLANK`: abilities 10, 17, 10, 10, 10, 10, level 1, base attack 0, saves 0, 0, 0, base max hp 5, size 2, speed 30, no casting, dies at zero; hp 10; **one kept modifier**: AC, type 3 (natural), +1, source kind 0 (none), id 0, permanent (−1) — a hand-made creature keeps everything |
| 831–936 | encounter (106 B) | format 1; active; round 1, turn index 1, `currentLeft` false, join counter 4; order [3, 2, 0, 1]; initiatives [23, 23, 16, 13]; joined [3, 2, 0, 1]; budgets `0x0`, `0x0`, `0xe`, `0xf`; nobody moved |

The two kept modifiers are §10.3's table in a file: the spell on Cassius and the hand-made bonus on
the rat are written; the warrior's content modifiers and everyone's condition modifiers are not.
The budget `0xe` is `MOVE | SWIFT | STEP` — the warrior (order index 1, whose turn it is) has spent
its standard action — and `0xf` is `ALL`: the rat went first and spent nothing; Aldric and Cassius
have not had a turn yet, so their budgets are still 0.

### 17.3 Physics and the app

- [`CrowdTest.aSaveRestoresAgentsInTheirSlotsAndTheirTargets`](../../modules/physics/src/test/java/dev/jeddy/physics/crowd/CrowdTest.java):
  three agents (one removed and its slot reused), one walking around a block and one drifting at a
  requested velocity, 45 ticks in; `write` and `read` both under `assertNoAllocation`; read into a
  fresh crowd that already holds two stray agents, which are gone afterwards; positions and
  velocity within $10^{-4}$, parameters exact, target states `VALID` (re-requested and solved at
  once), `VELOCITY` and `NONE`; the loaded walker completes the same walk; and a save from a crowd
  of 16 loads its first 8 slots into a crowd of 8. **`formatOneFixtureStillReads`** reads
  `crowd-v1.bin` (chapter [11](11-physics-navigation-crowds.md) decodes it).
- [`FormationTest.aFormationWalkContinuesAfterASave`](../../modules/physics/src/test/java/dev/jeddy/physics/crowd/FormationTest.java):
  six agents (five in a row at $z = 5$, one 4 m behind them) ordered into a box at $(15, 0, 20)$,
  about 17 m from the group, saved mid-walk after 45 ticks;
  in another crowd and mover the slots are the same and the party still arrives within 1.6 s of
  each other with its normal speeds restored — the formation walk that an early version of the
  load dropped (§19.1).
- [`VisibilityTest.aSaveRestoresWhatWasExploredAndTheViewersRescan`](../../modules/physics/src/test/java/dev/jeddy/physics/vis/VisibilityTest.java):
  a viewer explores two places; the write allocates nothing; a fresh visibility with a viewer at the
  second place reads it, has both explored, rescans **without moving** (the dirty mark), sees the
  second place and not the first; a 50 × 50 grid refuses the 100 × 100 bits, clears its exploration
  and has consumed the whole record.
- [`AgentSyncTest.facingsSurviveASaveAndLandOnTheEntitiesAtOnce`](../../modules/app/src/test/java/dev/jeddy/app/world/AgentSyncTest.java):
  §11.5.

### 17.4 The components

[`GeneratedGameTest`](../../modules/components/src/test/java/dev/jeddy/components/GeneratedGameTest.java)
works on the processor's sample game (`Health`, `Position`, `Tagged` with an `@EntityRef target`,
a `Dead` tag):

- **`snapshotsRoundTripAndTolerateChangedStores`**: a round trip through `HANDLES`, then a file
  written by hand with a `Ghost` store (skipped), a `Health` store whose columns are `current` and
  `armor` (the second unknown, discarded) and no `max` (keeps its default 10): §9.3's four cases.
- **`snapshotsTranslateEntitiesAndReferencesThroughStableIds`**: the test's own `EntityIds` maps an
  entity to kind 2 and its index; the file's header says 4 (`ENTITY`) for the `target` column;
  "another run" destroys all three entities and creates two in the other order, with a remap; the
  row of the vanished entity is dropped, the reference is translated to the new entity, a stale
  handle in a reference column saves as `NONE` and loads as 0, and the `EntityIds` helpers refuse
  kind 0 and a 57-bit value.
- **`aSecondCaptureAllocatesNothing`**: twenty entities in three stores, written into a 16-byte
  writer once (it grows), then under `assertNoAllocation`.

### 17.5 The demo's round trip

The iso demo has no test sources; its test is the scripted run of §15.3, which exits with a failure
when the second capture differs. §1 shows it passing with 294 skeletons on this container, and it
passed again with the default 64 (`28856 bytes captured again in 792 us`). It is the only test that
exercises `CryptSave`, `CryptIds`, `Enemies.read`, `Animator.read` and the load order together —
and it is why the known problem of §19.2 slipped through: a skeleton saved mid-swing *does* round
trip (the bytes agree), it just looks wrong a second later.

## 18. Performance

All numbers measured on 2026-10-14 on the two-core container this guide is written on (`nproc` =
2), headless, with the commands of §20; the previous version of this chapter and DESIGN §21 report
the same magnitudes from the CI container.

| What | Measured | How |
|---|---|---|
| Capture, 294 skeletons (111,904 B), first save of a run | 6.5 ms, 9.8 ms, 14.1 ms in three runs | cold code: the interpreter, then C1 |
| Capture, the same, saves 2–10 | median 2.9 ms | `--autosave 1`, 44 saves |
| Capture, saves 11–20 | median 1.4 ms | |
| Capture, saves 25–44 | median 0.81 ms, min 0.61, max 1.42 | 138 MB/s at the median: the `getInt` per element of §9.2, not memory bandwidth |
| Capture right after a load | 3.0 ms (294), 0.8 ms (64) | the round-trip check's second capture |
| Capture, 64 skeletons (28,856 B), first save | 2.4 ms | |
| Hand-over (`submit`: `await`, two `arraycopy`s, the volatile write, `unpark`) | 15–16 µs warm; 1.6 ms the first time (the thread starts, the buffers grow) | a scratch program over the real payload, 40 submits |
| Encode and write on the job thread (zstd level 3, two CRCs, write, rename) | 1.1–2.3 ms warm; 17 ms the first time | the same program, `writeNanos()` |
| File size | 111,904-byte payload → 16,723-byte file (6.7×; the compressed payload alone is 16,547 B, 6.8×); 28,856 → 5,363 B (5.4×); after 45 s of fighting, 18,569 B | the files |
| Load (read, decompress, `apply`, fog, sight), first load of a run | 21.4 ms (294 skeletons), 19.9 ms (64) | `loaded quick in … us`; cold code and every creature rebuilt: the 64-skeleton load is nearly as slow, so most of it is warm-up, not creatures |
| A save frame's allocation | 0 B in the capture; 176 B in `saves(dt)`, every save, attributed to the status `String` and the log record (§15.1) | the perf capture with `--autosave 3`, `--alloc-probe`: all 10 save frames of the measured second half at 176 B |

The last row corrects the previous version of this chapter, which attributed the 176 B to JIT
warm-up "gone after a dozen saves". The perf capture's D70 rule labels an allocating frame within
500 ms of a C2 compile as warm-up, and in a 60-second run with a save every 3 s most save frames
land near *some* compile — 7 of the 10 were labelled so and 3 failed the budget — but the amount is
the same 176 B on the last save of the run as on the first, and it is consistent with the status
line (`"Saved " + slot + …`) and `LOG.info`'s record, built on purpose on a cold path once per save
(an attribution from the code, §15.1, not an itemised measurement). The *capture* is
0 B, which is what the tests prove; the perf scene's budget is kept without autosaves.

Where the capture's time goes is exercise 5; the short answer is that 0.8 ms for 112 KB is a
virtual call and a `switch` per element in `Store.write` and `float32` per field in the crowd's
loop, and that a bulk `ints`/`floats` per column would bring the stores' share close to a
`memcpy`.

## 19. Pitfalls, known problems and two open decisions

### 19.1 Pitfalls met on the way

- **The round-trip check found the real bugs.** Comparing bytes after a load is an exacting test,
  and three of the four things it caught were not test artefacts: the viewers' positions were not
  restored before the fog recomputed (the fog came out as of a second after the save); the
  formation walk in progress was dropped (members arrived unpaced); and a target the agent had
  reached was re-requested (it started walking again). The fourth was the ulps of §11.1, fixed by
  keeping the saved bits. The check runs in the demo on `--save-at`.
- **Derived state in the comparison.** The fog's visible bits are recomputed only when a viewer
  moves more than the move threshold (0.5 m in the demo; 0.25 m is the engine's default), so the
  bits at save time can lag the viewers slightly and a fresh recompute after the load differs at
  the edge. The check sets the threshold to 0, which makes the derivation exact; play keeps the
  threshold (§15.3).
- **Order matters.** Hit points are set last in `Rules.read`, after the rebuild and the equipment
  reset them to the maximum (§10.4); the crowd's request queue is reset before the saved agents are
  placed, or a load during a pending request overflows the ring (chapter 11); the skeletons' agent
  bindings are rebuilt from the stores *after* the crowd has its agents back (§12.5).
- **A string allocates.** `Instant.now().toString()`, `Long.toString` and a title concatenation cost
  1,120 B on every autosave frame (the perf capture, during M16) until the meta values were
  formatted into a kept `StringBuilder` and the slot paths resolved once (§12.2, §14.1); the combat
  log line is copied char by char. What remains is the 176 B of §18, on purpose.
- **Unparking a virtual thread allocates.** 24 B, every time; the worker is a platform thread
  (§6.4).
- **A chunk's reader is a slice.** A `read` that consumed too little or too much would be caught
  at the chunk boundary (`hasRemaining`, `Truncated`), not by a wrong value three systems later —
  which is why `SaveFile` hands out slices rather than one reader over the payload.

### 19.2 Known problems (verified against the source on 2026-10-14)

Reported, not fixed: the fixes are the owner's to make, and chapter 09's and 11's exercises ask
for two of them.

1. **A loaded one-shot never ends** ([`Animator.read`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/Animator.java),
   the `ACTION` branch). `action()` sets `moving[c] = NONE` so that the next `locomotion` call
   *plays* idle or walk rather than adjusting the weight of a state that is no longer the
   locomotion blend; `read`'s `ACTION` branch replays the one-shot but leaves `moving[c]` as it was.
   A character that was `STANDING` before the load and whose save holds an attack in progress
   stays frozen on the attack's last frame once `actionLeft` runs out, until its agent moves (a
   `MOVING` one slides along in that frame until it stops). The fix is the one line `action()` has;
   chapter [09](09-animation-and-skinning.md) (exercise on a loaded one-shot that never ends) gives
   it and the regression test it needs. The round-trip check does not see it: the bytes are right.
2. **A save in flight at exit is lost.** `SaveJob`'s worker is a daemon thread and nothing in the
   demo calls `saves.job().await()` or `close()` on shutdown (`DemoGame.shutdown` saves the
   settings and returns; `SaveSlots` has no `close`). F5 followed by quitting within the write's
   1–20 ms leaves the *previous* quick save (the rename is atomic, so nothing is corrupt) and a
   stray `quick.jsave.tmp`. Exercise 9 adds the wait.
3. **`FormationMove.read` does not skip extra members** of a save written by a bigger mover
   (chapter [11](11-physics-navigation-crowds.md) notes it; games use one mover of a fixed size).
4. **A failure inside `apply` leaves a half-applied game.** `CryptSave.load` catches the exception
   and logs it, but the chunks applied before the failing one have already replaced the running
   state: a save whose `RULE` record throws leaves the stores, the crowd and the facings loaded and
   the rules as they were. The container's checksums make this unreachable by *damage* (a damaged
   payload fails in `SaveFile.read`, before any chunk is applied); it remains reachable by a logic
   error in a `read` or by a file edited and re-signed. Exercise 12 discusses the two ways out.
5. **Two Javadocs say "virtual thread".** `modules/save/src/main/java/dev/jeddy/save/package-info.java`
   ("`SaveJob` writes on a virtual thread") and `SaveFile`'s class comment ("`encode` runs on the
   save job's virtual thread") describe the first design; the worker has been a platform thread
   since the 24 B were measured (D118).
6. **The perf capture's warm-up rule can hide a per-save allocation** (§18): a constant 176 B on
   every save frame was reported as JIT warm-up on 7 save frames of 10. The rule is right for what it
   was built for (D70); a save every 3 s is a case it did not foresee.
7. **A stale comment on the fog threshold.** `DemoGame.build`'s comment above
   `setMoveThreshold(0)` says "the default skips moves under 0.25 m"; that is `Visibility`'s default,
   but the demo's `Level` sets 0.5 m. The code is right; the comment names the wrong value.
8. **`SaveJob.level` is a plain field** read by the worker; `level(int)` while a write is running is
   a benign race on an `int`, but a reader of the class should know the field is not published
   like the others.

### 19.3 Two open decisions, for the owner

Both are stated here as **open**, not decided, and both are discussed in lesson 4:

- **The meta block is not checksummed.** The header CRC covers bytes 0–47 and the payload CRC the
  stored payload; the meta block between them is covered by neither (L04 exercise 9's solution;
  [formats/jsave.md](../formats/jsave.md), under the header table). Damage there
  can show a wrong title in the slot list, refuse a load ("meta block is damaged", or the `level`
  check of §12.5), but cannot change the loaded state, which is all in the payload. L04 exercise 9
  designs a fix every existing reader keeps accepting — a meta CRC in the first four bytes of the
  reserved word behind a new flag bit — and leaves the decision to the owner.
- **The temporary file is not forced to disk before the rename** (`FileChannel.force`). After a
  *power cut* (not a crash) some file systems can record the rename and not the new file's
  contents; the CRCs then detect the file rather than load it. Forcing costs milliseconds per save
  on the job thread, which the frame never waits for. L04 §15 states the trade-off and its exercise
  6 the change; the owner decides.

## 20. Things to run

- `./gradlew :save:test :rules:test :physics:test :components:test :app:test`: the container and
  its damage cases, the slots and the failed write, the hand-over at 0 B, the rules' round trip and
  the patched-content load, the crowd's slots, the formation walk, the fog, the facings, the stores'
  id translation, the golden files.
- The scripted round trip, headless (this is what produced §1's three lines):

  ```
  ./gradlew :iso-demo:run --args="--headless --no-audio --enemies 294 --auto-turns --save-at 18 --quit-after 22 --saves build/saves"
  ```

  Look for `save round trip exact`; `build/saves/quick.jsave` is a file like §12.4's (about 16.7
  KB; the exact bytes depend on when the save lands in the fight).
- `./gradlew :iso-demo:run --args="--autosave 5 --saves build/saves"`: play, press **F5** and
  **F9**, open the menu with **Esc** and use *Save as*, *Load* and *Delete*; the files appear under
  `build/saves`. Without `--saves` they go to your data directory (`%APPDATA%\crypt\saves` or
  `~/.local/share/crypt/saves`). With `--autosave 1 --enemies 294 --quit-after 45` the log shows
  the capture time settling from about 10 ms to under 1 ms over the first thirty saves (§18).
- **Break a save.** Overwrite one byte of the payload — on Linux, `printf '\x00' | dd
  of=build/saves/quick.jsave bs=1 seek=16000 conv=notrunc` — and press **F9**: the status line and
  the log say `payload checksum does not match; the file is damaged`, and the game plays on with its
  state untouched. Flip a byte of the *header* instead (`seek=20`) for "header checksum".
- **Read a save yourself.** In a scratch class with `dev.jeddy.save` on the module path:
  `SaveFile f = SaveFile.read(Path.of("build/saves/quick.jsave"));` then `f.meta()` for the six
  entries and `for (SaveFile.Chunk c : f.chunks()) System.out.println(c.tagName() + " " + c.version() + " " + c.data().remaining());`
  prints the table of §12.4; `Store.skip(c.data())` after reading the store count of `COMP` names
  each store and `position()` differences give their sizes.
- **Patch the rulebook.** Edit `samples/iso-demo/assets/rules/monsters.json` (the warrior's `hp`),
  re-cook, load a save made before: the warriors' maximum changed, their wounds did not — the test
  of §17.2 done by hand.
- **Watch the hand-over.** Take a thread dump of the running demo (`jcmd <pid> Thread.print`)
  between saves: the `save-job` thread is `WAITING (parking)` on the `SaveJob`; during a write it
  is `RUNNABLE` inside the compressor or `Files.write`.
## Exercises

**1. A new column.** Add a field to `Mind` (say `float rage`), rebuild, and load a save made before
the change: which value does the row get, and which line of `Store.read` decides it?

<details><summary>Hint</summary>The column is matched by name; D111 (§9.3). What happens to a
column the file does not mention at all?</details>
<details><summary>Solution</summary>

The row gets `rage`'s **default** — 0, or whatever `@Default` says. No line of the column loop
decides it, and that is the point: `local[c] = columnIndex(r.string())` maps the *file's* columns
to this store's, so a column the file lacks is never visited by the `switch`. What sets it is the
`clear()` before the rows are added and the `add(e)` per row, which the generated store completes
with `resetRow` — the row starts from its defaults and only the stored columns are written over
them. `GeneratedGameTest.snapshotsRoundTripAndTolerateChangedStores` checks the same thing with
`Health.max` ("max kept its default", §17.4).
</details>

**2. Format 2 of the fog.** Make `Visibility`'s record format 2 by writing the move threshold after
the bits, and keep format 1 readable. Which test must you add *before* the change, and what does it
protect?

<details><summary>Hint</summary>§5.4; `testing.Fixtures.bytesOrCreate`; L04 §16 and its exercise
5, which does the same for the crowd.</details>
<details><summary>Solution</summary>

Before touching `write`, add to `VisibilityTest` a `formatOneFixtureStillReads` that writes a
visibility with a known exploration, obtains the bytes through
`Fixtures.bytesOrCreate(VisibilityTest.class, "fixtures/fog-v1.bin", w.toBytes())`, reads them
into a fresh `Visibility` and checks `isExplored` for the known cells. The first run writes the
fixture and fails ("review it, commit it and run again"); commit `fog-v1.bin`. *Then* change the
code: `SAVE_FORMAT = 2`; `write` appends `w.float32(moveThreshold)` after `w.longs(explored)`;
the body of today's `read` after the format check moves, unchanged, into a private
`boolean readV1(ByteReader r)` (it still returns whether the bits were taken), and `read` becomes

```java
public boolean read(ByteReader r) {
    int format = r.int32();
    return switch (format) {
        case 1 -> readV1(r);
        case 2 -> {
            boolean fits = readV1(r);
            moveThreshold = r.float32();
            yield fits;
        }
        default -> throw new IllegalStateException(
                "fog save format " + format + "; this build reads 2");
    };
}
```

`read` returns `boolean` (whether the explored bits fitted the grid, §11.3), so each case must
yield that result; `case 2` reads the bits first, because that is the order `write` wrote them.

The fixture test protects the promise that saves written by the old game still load: it reads bytes
no current code can regenerate, so a later edit that drops `case 1` or reorders a field fails it.
The size check of the grid (§11.3) is unchanged and still applies to both formats.
</details>

**3. A reference that needs no translation.** The demo's `Fireball` component holds `caster` as a
creature handle. Why does it not need an `@EntityRef`-style translation, and under what change to
`Combat` would it?

<details><summary>Hint</summary>§4.1's table and §4.2: which indices are stable, and why.</details>
<details><summary>Solution</summary>

`caster` is a **creature** handle, not an entity handle. Creatures have no generation counter and
are rebuilt in the same order on every run (the party at 0–5, skeleton $i$ at $6 + i$), and
`Rules.read` recreates each saved creature *at its handle* (§10.4) — so the `int` means the same
creature after a load, and the store can write it as a plain `INT` column. It would need a
translation if creature handles stopped being reproducible: if `Combat` created creatures in an
order that depends on play (summons, hirelings, a respawn that does not reuse the freed handle
first), or if `Fireball` held the caster's *root entity* instead — an entity handle with a
generation, which must go through `EntityIds` (`@EntityRef`).
</details>

**4. Mid-respawn.** Save a game with a skeleton mid-respawn (`Mind.state == DEAD`, `timer` counting
down) and load it: trace which chunk restores the timer, which the body's position, and which the
hidden flag.

<details><summary>Hint</summary>§12.5's load order; `Enemies.read` (§11.6).</details>
<details><summary>Solution</summary>

- The **timer** (and the `DEAD` state, and `corpse`) is a `Mind` column: `COMP` restores it
  bit for bit, and `SkeletonAi.think` continues the countdown on the next tick.
- The **body's position** is in `ENEM`: `Monster.agent` is −1 (from `COMP`), so `Enemies.read`
  takes the dead branch, `world.transforms().setPosition(root[i], x, y, z)` and `world.setYaw`
  from the saved yaw (which `kill` had copied into `deathYaw`). `ANIM` then replays the death clip
  at phase 1, the body on the floor.
- The **hidden flag** is the `Sight.hidden` column, restored by `COMP`; it is *applied* to the
  world by `Enemies.read`'s last line, `world.setGroupHidden(root[i], hidden[i])`, which also
  deactivates the animation instance. When the timer runs out, `respawn` adds an agent, rebinds,
  revives the animator slot and `spawnEnemy` rebuilds the creature — the same path as in play.
</details>

**5. Where the capture's time goes.** The capture copies 111,904 bytes in about 0.8 ms once warm
(§18), which is about 140 MB/s — far below the memory bandwidth of any machine. Profile
`Store.write` and find where the time goes.

<details><summary>Hint</summary>A virtual call per element (`getInt(i, c)`) with a `switch` inside,
against a bulk `ints(column, n)` (L04 §9.5); how would the generated store write its columns
directly?</details>
<details><summary>Solution</summary>

`Store.write` reads every element through the generic accessors: `getInt(i, c)` is an abstract
method, so each call is a virtual dispatch into the generated store, which then `switch`es on `c`
to pick the array, bounds-checks and returns one value; `ByteWriter.int32` then calls `ensure`
and stores four bytes. Per element that is tens of instructions for four bytes. The crowd's loop
is similar: 22 `float32`/`int32` calls per agent, each with its own `ensure`. A `memcpy` moves
four bytes in a fraction of a cycle. Measure it with a JMH benchmark in `bench/` that calls
`game.write(w)` on 300 rows (expect the stores' 25 KB to take a few hundred microseconds) against
a hand-written loop that calls `w.ints(monster.agent(), n)` per column (expect tens of
microseconds: one `ensure`, one tight loop the JIT vectorizes). The change that makes the
generated store do the second thing is a generated `writeColumns(ByteWriter w, int n)` per store
— `w.ints(model, n); w.ints(agent, n); …` — called by `Store.write` instead of the generic loop
(with the entity-reference columns still going through `ids.idOf` one by one). The format does
not change, so no version bump and no new fixture; the round-trip check proves it.
</details>

**6. The hitch, measured.** Write the save on the game thread instead
(`SaveJob.write(SaveFile.encode(writer, SaveFile.DEFAULT_LEVEL), path)` right after the capture)
and measure the frame with the perf capture's `max` column.

<details><summary>Hint</summary>§18 measured the encode and the write on the job thread: 1.1–2.3 ms
warm, 17 ms the first time. The perf report's game-thread row (chapter
[12](12-java-performance.md), on the perf capture); compare 294 and 64 skeletons.</details>
<details><summary>Solution</summary>

Replace `slots.save(slot, writer, captureNanos)` in `CryptSave.save` with
`SaveJob.write(SaveFile.encode(writer, SaveFile.DEFAULT_LEVEL), slots.pathOf(slot))` (it throws
`IOException`; catch and log it). Run `--perf 60 --autosave 3 --headless` and read the game-thread
row: the *average* barely moves (one frame in 180), but the **max** rises by the encode-and-write
time — a prediction from §18's figures: 1–2 ms per save once warm, and 17 ms on the first save,
which on this container's 2.5 ms average frame is a visible hitch and, at 60 fps, a dropped frame
(L00 §11). With 64 skeletons the payload is a quarter the size, so expect the warm cost to shrink
with it and the first-save cost (zstd's first use, the JIT) not to. The perf capture would also
flag the allocation: `encode` allocates the file image on the game thread, which the job thread
was absorbing.
</details>

**7. The 24 bytes, measured.** Reproduce §6.4's measurement without touching the engine: park a
platform thread and a virtual thread, unpark each 2,000 times, and read the calling thread's
allocation counter.

<details><summary>Hint</summary>`com.sun.management.ThreadMXBean.getCurrentThreadAllocatedBytes`
(what `Allocations` uses); make sure the target is *parked* before each unpark (`getState() ==
WAITING`), or the unpark only leaves a permit and allocates nothing.</details>
<details><summary>Solution</summary>

```java
import java.lang.management.ManagementFactory;
import java.util.concurrent.locks.LockSupport;

public class Unpark {
    static volatile int done;

    public static void main(String[] args) throws Exception {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        for (String kind : new String[] {"platform", "virtual"}) {
            Thread t = kind.equals("platform")
                    ? Thread.ofPlatform().daemon(true).start(Unpark::loop)
                    : Thread.ofVirtual().start(Unpark::loop);
            Thread.sleep(100);
            for (int round = 0; round < 6; round++) {
                int n = 2000;
                long b0 = bean.getCurrentThreadAllocatedBytes();
                for (int i = 0; i < n; i++) {
                    int before = done;
                    while (t.getState() != Thread.State.WAITING) {
                        Thread.onSpinWait(); // wait until it is really parked
                    }
                    LockSupport.unpark(t);
                    while (done == before) {
                        Thread.onSpinWait();
                    }
                }
                long bytes = bean.getCurrentThreadAllocatedBytes() - b0;
                if (round >= 3) {
                    System.out.println(kind + ": " + bytes + " B for " + n + " unparks = "
                            + (bytes / (double) n) + " B/unpark");
                }
            }
        }
    }

    static void loop() {
        while (true) {
            LockSupport.park();
            done++;
        }
    }
}
```

Run with `java Unpark.java` (JDK 27). Measured on this guide's container: `platform: 0 B for 2000
unparks = 0.0 B/unpark` and `virtual: 48000 B for 2000 unparks = 24.0 B/unpark`, three rounds each
— exactly the `AdaptedRunnableAction` of §6.4 (8 + 4 + 4 + 4 = 20 bytes, rounded to 24). Without
the `getState` wait the virtual number drops towards 0 in some rounds: an unpark that arrives before
the thread has parked again only sets its permit, and `VirtualThread.unpark` submits nothing. The
save job's worker is always parked when a save arrives (it parks nanoseconds after clearing
`target`, and the game thread's `await` naps 100 µs at a time), so the engine would pay the 24 B on
every save.
</details>

**8. A version per chunk.** Replace `CryptSave.VERSION` with one constant per chunk and bump only
`RULE`'s when `Combat` adds a field. What changes in `chunk()`, and what would an old save's `RULE`
chunk look like to the new reader?

<details><summary>Hint</summary>§5.3's wrinkle; `chunk(file, tag)` compares `c.version()` to
`VERSION`.</details>
<details><summary>Solution</summary>

Ten constants (`GAME_VERSION = 1`, …, `RULE_VERSION = 2`, …), each passed to `writer.chunk(TAG,
TAG_VERSION)` in `capture`, and `chunk(SaveFile file, int tag, int version)` taking the expected
version so that `c.version() > version` is checked per chunk. An old save's `RULE` chunk carries
version 1; the new reader accepts it (1 ≤ 2) and `Combat.read` must know it is reading version 1 —
so `chunk` should *return the version* too (or `apply` should read `c.version()` before calling
`combat.read(r, version)`), and `Combat.read` switches on it: `case 1` reads the old field list,
`case 2` the new one. The other nine chunks keep version 1 and their readers unchanged, which is the
independence D117 asked for: a fixture of the old `RULE` layout (`rules-v1.bin` already exists for
the rules' part; add one for `Combat`'s own fields) is the only test the change needs.
</details>

**9. A save in flight at exit.** §19.2's second problem: F5, then quit at once. Make the demo wait for
a write in progress on shutdown, and say what the wait costs and what it must not do.

<details><summary>Hint</summary>`SaveJob.close()` (§13.4) waits and stops the worker;
`DemoGame.shutdown` runs on the game thread.</details>
<details><summary>Solution</summary>

Add to `DemoGame.shutdown`, before the settings are saved:

```java
        if (saves != null) {
            saves.job().close(); // a quick save pressed a moment ago finishes before the JVM exits
        }
```

`close` sets `closed`, `await`s the write in progress — at most the encode-and-write time, 1–20 ms
(§18) — and unparks the worker so it exits its loop. It must not be called from the main thread
while the game thread could still `submit` (a `submit` after `close` throws), which is why it goes
in the game's own shutdown. `SaveSlots` could expose `close()` to make the intent visible; the
`SaveJob` Javadoc already lists `close` among the game-thread calls. With the wait in place the
stray `quick.jsave.tmp` of §6.6's second row cannot be left by a normal quit, only by a crash.
</details>

**10. The frozen swing.** §19.2's first problem. Write the fix in `Animator.read` and a test for it.
The iso demo has no test sources, so say what the test needs and where it could live.

<details><summary>Hint</summary>Chapter [09](09-animation-and-skinning.md)'s exercise on a loaded
one-shot that never ends; `moving[c] = NONE` in `action()`.</details>
<details><summary>Solution</summary>

The fix is one line in the `ACTION` branch of `read`:

```java
                case ACTION -> {
                    mode[c] = ACTION;
                    moving[c] = NONE; // like action(): locomotion restarts when it ends
                    animations.playOnce(i, clip(rig, Math.clamp(actionSlot[c], 0, SLOTS - 1)), 0);
                }
```

so that when `actionLeft` runs out, `update` switches to `LOCOMOTION` and `locomotion` sees
`moving != STANDING`, *plays* the idle with the 0.2 s fade, and the character leaves the swing's
last frame. A test needs an `Animator` over a `World`, an `Animations` and a `Crowd` with one
standing agent — the pieces `AnimationsTest` and `AgentSyncTest` already build in `animation` and
`app` — so the simplest home is a new `samples/iso-demo/src/test` with a test that: registers a
character; calls `action(c, ATTACK_1H)`; writes; registers a second animator whose character is
idle (`moving == STANDING`); reads; advances `update` past `actionLeft`; and asserts
`animations.current(instance)` is the idle clip, not the attack. Before the fix the assertion fails
with the attack still current.
</details>

**11. Extra members.** `FormationMove.read` clamps the member count to its capacity but does not skip
the records of the members past it (§19.2, chapter 11). Write the fix, following
`Crowd.read`'s pattern for slots past the capacity.

<details><summary>Hint</summary>Each extra member is `int32, float32, int32` — 12 bytes — and the slot
positions are written *after* all members with `floats(slots, count × 3)`, with the *saved* count.</details>
<details><summary>Solution</summary>

```java
    public void read(ByteReader r) {
        int format = r.int32();
        if (format != 1) {
            throw new IllegalStateException(
                    "formation save format " + format + "; this build reads 1");
        }
        int saved = r.int32();
        count = Math.min(saved, members.length);
        for (int k = 0; k < count; k++) {
            members[k] = r.int32();
            baseSpeed[k] = r.float32();
            slotOf[k] = Math.clamp(r.int32(), 0, members.length - 1);
        }
        r.skip(12 * (saved - count));     // the members past this mover's capacity
        int floats = r.int32();           // saved × 3
        int keep = Math.min(floats, slots.length);
        for (int i = 0; i < keep; i++) {
            slots[i] = r.float32();
        }
        r.skip(4 * (floats - keep));
    }
```

`floatsInto(slots)` would throw ("array of … for … floats") on a bigger record, so the slot
positions are read by hand up to this mover's capacity and the rest skipped; a saved `slotOf`
pointing past the kept slots is already clamped. A test writes a mover of 8 and reads it into one
of 6: `count()` is 6, the first six slots match, and `r.hasRemaining()` is false afterwards.
</details>

**12. Half a load.** §19.2's fourth problem: a `RUNTIME` failure inside the sixth chunk's `read`
leaves five chunks applied. Design two ways out and say what each costs.

<details><summary>Hint</summary>What does the game have in memory *before* `apply` starts? What does
a database do before applying a transaction?</details>
<details><summary>Solution</summary>

*Validate first, apply second.* Give every system a `check(ByteReader)` — or run `read` against a
scratch copy of its state — before any `read` touches the live state. For the stores and the crowd
this is cheap (a dry run over the bytes); for the rules it means rebuilding 300 creatures twice.
Cost: a second pass per chunk, more code per system, and it still cannot catch a `read` that is
*wrong* rather than *throwing*.

*Capture, then restore on failure.* Before `apply`, capture the running game into a second
`SaveWriter` (0.8 ms warm, §18); if any `read` throws, `apply` that capture back — the load path
already exists and is byte-exact (§12.6). Cost: a second 256 KiB writer, and the restore itself
can only fail the same way, so it must be the last resort (log, then give up). This is what a
database does — a transaction log it can roll back — and it is the smaller change: a dozen lines in
`CryptSave.load`, no change to any system. Either way the container's checksums keep this a
*logic-error* path, not a damage path.
</details>

**13. The meta block's bytes.** §19.3's first open decision. Without changing `FORMAT_VERSION`, add a
CRC-32C over the meta block that every existing reader keeps accepting, and say what a *new* reader
does with an *old* file.

<details><summary>Hint</summary>L04 exercise 9 has the design; the header's `reserved` word and the
flag bits were kept for exactly this.</details>
<details><summary>Solution</summary>

In `SaveFile.encode`, compute `crc(meta, 0, metaSize)` and write it into the first four bytes of
the reserved word (offset 40) instead of `w.int64(0)`, and set a new flag bit (`FLAG_META_CRC =
2`) in `flags`. In `parse`, read the reserved word's low `int` and, **only when the flag is set**,
compare it with the CRC of bytes 48 to 48 + `metaBytes` (padding included: it is part of what the
writer hashed) before the meta pairs are read, throwing "meta checksum does not match". A
version-1 reader built before the change ignores both the reserved word and unknown flag bits, so
it still reads new files; the new reader reads an old file (flag clear) exactly as before, without
the check. No version bump, no new fixture for old files — but a new golden file *with* the flag,
written once, so the check itself is kept under test. It is a format decision: this exercise
proposes it, the owner decides it.
</details>

## Glossary

- **Autosave:** a save on a timer to the `auto` slot (§15.1).
- **Capture:** the game-thread half of a save: every system's `write` into the kept `SaveWriter`
  (§6.2, §12.3).
- **Chunk, tag, chunk version:** a tagged, versioned, padded block of the payload; its four-letter
  name; the game's version of what it holds (§5.2–5.3; L04 §11).
- **Composition:** the game's list of `write` and `read` calls, in order (`CryptSave`, §12).
- **Content id:** the string that names a rulebook row across patches (`skeletalWarrior`), written
  instead of the row (§4.4).
- **Daemon thread:** a thread that does not keep the JVM alive (§6.4).
- **Derived state:** anything the game recomputes from the dynamic state and the paks (§2.1).
- **Dynamic state:** what play changed; what a save holds (§2.1).
- **Golden file (fixture):** the bytes an old format wrote, committed and read by a test for ever
  (§5.4; L04 §16).
- **Hand-over:** `SaveJob.submit`: the copy, the volatile write and the unpark (§6.5, §13.2).
- **Happens-before:** the Java memory model's ordering rule that makes the copied bytes visible to
  the worker (§6.5; chapter 01).
- **Job (save job):** the writing half of a save: one parked platform thread that encodes and
  replaces the file (§13).
- **Load order:** the dependency order in which `apply` reads the chunks (§12.5).
- **Meta block:** the uncompressed string pairs a slot list reads (§12.3; L04 §11).
- **Overlay:** the per-creature values a rebuild would not recreate, laid over the rebuilt creature
  (§4.4, §10).
- **Permit (`LockSupport`):** the one-shot token an `unpark` leaves for a thread that has not parked
  yet (§6.5).
- **Quick save, quick load:** F5 and F9; the `quick` slot (§15.1).
- **Record format:** the first `int` a system writes, naming its own layout (§5.3).
- **Round-trip check:** capture, load, capture again, compare the bytes (§12.6, §15.3).
- **Slot (crowd, creature):** an index without a generation, which a save keeps (§4.2).
- **Slot (save):** a named file in the save directory: `quick`, `auto` or `slot-<name>` (§14).
- **Stable id:** a `long` naming an entity across runs: a kind in the top byte, a value below (§4.3,
  §8).
- **Static state:** what the paks hold; reloaded, never saved (§2.1).
- **Three layers:** static, derived, dynamic (§2.1).
- **`volatile` hand-over:** publishing data to another thread with one volatile write and one
  volatile read (§6.5).

---

*Code this chapter builds or quotes: `components.EntityIds`, `components.Store` (`write`, `read`,
`skip`), the generated `CryptGame` registry's `write`/`read`, `rules.Rules` (`write`, `keptInSave`,
`sourceTable`, `read`), `rules.Encounter`, `rules.ActionBudget`, `rules.RealTimeDriver.setClock`,
`app.world.AgentSync`, `save.SaveJob`, `save.SaveSlots`, `save.SaveDirectory`,
`save.SaveFormatException`, the `save` module's `module-info`, and the demo's `CryptSave`,
`CryptIds`, `Combat`, `Enemies`, `Party`, `Spells`, `Animator` (by reference), `DemoGame`
(`saves`, `writeGame`, `readGame`, `title`, `playSeconds`) and `DemoUi.savesSection` (by
reference); the tests `SaveSlotsTest`, `SavesTest`, `GeneratedGameTest`, `CrowdTest`,
`FormationTest`, `VisibilityTest`, `AgentSyncTest`; the fixtures `v1.jsave`, `crowd-v1.bin`,
`rules-v1.bin`. Decisions: D111–D119 in [DESIGN §17](../DESIGN.md), with D26 (virtual threads for
blocking IO) and D70 (the perf capture's warm-up rule); the module in DESIGN §21; the format in
[formats/jsave.md](../formats/jsave.md). Lessons: [L04](lessons/04-binary-formats.md) (the stream
and the container), [L03](lessons/03-data-layout.md) (handles and stores),
[L02](lessons/02-dice-and-probability.md) (the generator's state). Chapters:
[01](01-architecture.md) (threads, the game loop, the job system, happens-before),
[09](09-animation-and-skinning.md) (the animator's save), [11](11-physics-navigation-crowds.md)
(the crowd's, the formation walk's and the fog's), [12](12-java-performance.md) (the perf capture
and JIT warm-up), [13](13-ui.md) (the pause menu), [15](15-rules.md) (the rules),
[22](22-components.md) (the registry and the stores).*

[← 15 Rules](15-rules.md) · [Index](README.md) · [22 Components →](22-components.md)
