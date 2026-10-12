# `.jsave` save game, format 1

A save game (DESIGN §21, D116): the **dynamic state** of a game in progress — every component
store, the rules' live values, the crowd's agents, what the characters are doing, the party, the
fog, the random generators — as tagged, versioned chunks that each system writes itself into
`core.io.ByteWriter` and reads back from `ByteReader`. The level, the models and the rulebook are
not in it: a load rebuilds them from the paks and lays the save over them (D112).
`dev.jeddy.save.SaveFile` encodes and reads the container; `SaveWriter` captures; `SaveJob`
writes; `SaveSlots` names the files.

Little-endian throughout; every multi-byte field is naturally aligned within its block, and the
meta block and every chunk are padded to 8 bytes.

## Header (48 bytes)

| Offset | Type | Field | Notes |
|---|---|---|---|
| 0 | u32 | magic | `0x5641534A` (`"JSAV"` in file order) |
| 4 | u16 | version | 1; a reader refuses a higher one ("newer than this build") |
| 6 | u16 | flags | bit 0: the payload is one zstd frame |
| 8 | u32 | chunkCount | chunks in the payload |
| 12 | u32 | metaBytes | bytes of the meta block, padded to 8 (so the payload starts at `48 + metaBytes`, a multiple of 8) |
| 16 | u64 | payloadBytes | the payload uncompressed |
| 24 | u64 | storedBytes | the payload as stored (compressed when bit 0 is set) |
| 32 | u32 | payloadCrc | CRC-32C of the stored payload bytes |
| 36 | u32 | headerCrc | CRC-32C of bytes 0..48 with this field zero |
| 40 | u64 | reserved | 0 |

The two checksums tell a damaged file from a foreign one: a wrong magic is "not a save", a wrong
header CRC is "damaged", and a wrong payload CRC is "damaged" before anything is decompressed.
The reader also refuses a `chunkCount` larger than `payloadBytes / 16` (every chunk has a 16-byte
header) and a last chunk whose padding is missing, so a crafted file ends in a
`SaveFormatException` rather than a huge allocation or an unchecked exception. The meta block is
covered by neither checksum: damage there can show a wrong slot entry or refuse a load (the demo
checks the `level` entry), but cannot change the loaded state, which is all in the payload.

## Meta block (at 48, `metaBytes` long)

```
u32 pairCount
pairCount × (string key, string value)
zero padding to 8
```

A `string` is a `u16` byte length and UTF-8 bytes (the `ByteWriter` encoding). The block is never
compressed: a slot list reads the header and this block only (`SaveFile.peek`). The demo writes
`game`, `title`, `level`, `playSeconds`, `savedAt` (epoch milliseconds) and `kills`; a game may
write anything a menu shows.

## Payload (at `48 + metaBytes`)

`chunkCount` chunks, one after another:

| Offset | Type | Field | Notes |
|---|---|---|---|
| 0 | u32 | tag | four printable ASCII characters in file order (`SaveFile.tag("COMP")`) |
| 4 | u32 | version | the chunk's own version; the reader keeps old ones readable (D117) |
| 8 | u32 | length | bytes of data |
| 12 | u32 | reserved | 0 |
| 16 | — | data | `length` bytes, then zero padding to a multiple of 8 |

When flag bit 0 is set the whole payload is one zstd frame whose content size must equal
`payloadBytes` (a frame that disagrees is refused). Level 3 is the default; a save compresses
four to ten times (the demo's 110 KB capture stores as 18 KB).

## What a chunk holds

The container does not know. Each system writes a record that starts with its own **format
number** (`Crowd.SAVE_FORMAT`, `Rules.SAVE_FORMAT`, …) and refuses a newer one; the generated
component stores are self-describing instead (name, columns by name and type, entities as 64-bit
stable ids, D111 and D114). The demo's chunks ([`CryptSave`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/CryptSave.java)):

| Tag | Written by | Contents |
|---|---|---|
| `GAME` | `DemoGame` | play time, following, the camera, the demo's generator |
| `COMP` | the registry | every store, entities as `CryptIds` |
| `CROW` | `Crowd` | agents in their slots: position, velocity, parameters, target |
| `ENEM` | `Enemies` | alive, kills, each skeleton's body position and facing |
| `SYNC` | `AgentSync` | the facing of every bound agent |
| `ANIM` | `Animator` | each character's mode, stance, action and clip phase |
| `RULE` | `Combat` | `Rules` (creatures by content id + the dynamic overlay, the generator), `Encounter`, the clock, the fight's own state |
| `PRTY` | `Party` | selection, formation, cooldowns, the formation walk |
| `SPEL` | `Spells` | the cast counter |
| `FOG ` | `Visibility` | grid size and the explored bits |

## A real file

The golden file of format 1 under test
(`modules/save/src/test/resources/dev/jeddy/save/fixtures/v1.jsave`, 172 bytes; two meta
entries, two chunks, compressed):

```
0000  4a 53 41 56 01 00 01 00 02 00 00 00 38 00 00 00   JSAV........8...
0010  60 00 00 00 00 00 00 00 44 00 00 00 00 00 00 00   `.......D.......
0020  63 47 8f 2c 3f 3e c3 e1 00 00 00 00 00 00 00 00   cG.,?>..........
0030  02 00 00 00 05 00 74 69 74 6c 65 0f 00 43 72 79   ......title..Cry
0040  70 74 2c 20 32 20 72 6f 75 6e 64 73 05 00 6c 65   pt, 2 rounds..le
0050  76 65 6c 0c 00 6c 65 76 65 6c 73 2f 63 72 79 70   vel..levels/cryp
0060  74 00 00 00 00 00 00 00 28 b5 2f fd 24 60 bd 01   t.......(./.$`..
```

Bytes 0–3 are the magic, 4–5 the version `1`, 6–7 the flags `1` (zstd), 8–11 two chunks, 12–15
a 56-byte meta block (`0x38`), 16–23 a 96-byte payload (`0x60`) stored as 68 bytes (`0x44`,
24–31), then the two checksums and the reserved word. The meta block at 48 holds `2` pairs:
`title` (5 bytes) → `Crypt, 2 rounds` (15), `level` → `levels/crypt`, padded with zeros to 56.
The zstd frame starts at 104 with its own magic `28 b5 2f fd`. Lesson
[L04](../learn/lessons/04-binary-formats.md) reads this dump byte by byte.

## Versioning

- The **file** version changes only when the header, the meta block or the chunk framing
  changes; a reader refuses a newer file.
- A **chunk** version is the game's: which systems write into it and in what order.
- A **record** format is the system's: the first `int` of what `Crowd.write`, `Rules.write`,
  … emit. `read` switches on it, keeps older layouts readable, and refuses a newer one with a
  message naming the format.
- A **store** needs none: columns are matched by name and type, with defaults for the missing
  (D111).

Every version that ever shipped keeps a golden file under `src/test/resources` and a test that
reads it (`testing.Fixtures`), so a reader never loses an old layout without a test failing.
