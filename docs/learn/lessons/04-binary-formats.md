# Lesson 4 — Binary formats: bytes, endianness, alignment, checksums and versions

*A lesson from M16. Chapter [16 Saves](../16-saves.md) says the `.jsave` container has a
48-byte header, little-endian fields, chunks padded to 8 and two CRC-32C checksums; this lesson
derives each of those from what a reader must be able to do with a file it did not write, and
reads a real file byte by byte. It applies to every binary format in the engine — `.jmesh`,
`.jlevel`, `.jrules`, `.jpak` ([formats/](../../formats/)) follow the same rules. Read chapter
[10](../10-assets.md) §2 and [12](../12-java-performance.md) first.*

## 1. A file is a sequence of bytes, and nothing else

A `float` in memory is 32 bits the CPU interprets by the instruction that loads it; on disk it is
four bytes that mean nothing until a reader agrees with the writer on three things: **which bytes
belong together** (the layout), **in which order** they form a number (the endianness), and
**what the number means** (the version). A format is that agreement written down, and a *good*
format is one that lets a reader check the agreement before trusting it.

Two tools make the rest concrete. `Float.floatToRawIntBits(1.5f)` is `0x3FC00000`: sign 0,
exponent `0x7F` (127, so $2^0$), mantissa `0x400000` ($1 + 2^{22}/2^{23} = 1.5$). And
`Integer.toHexString(0x11223344)` reminds us that the *most* significant byte is written first
when we write numbers, which is the opposite of what most machines do.

## 2. Endianness, derived

A 32-bit integer $v$ is four bytes $b_0 b_1 b_2 b_3$ with

$$v = b_0 + 2^{8} b_1 + 2^{16} b_2 + 2^{24} b_3 \quad\text{(little-endian: } b_0 \text{ first in memory)}$$

or the reverse (big-endian: $b_3$ first). For $v = \texttt{0x11223344}$ the bytes in memory are
`44 33 22 11` on x86 and ARM (both little-endian in practice) and `11 22 33 44` on a big-endian
machine or in a Java `DataOutputStream`, which is big-endian by the Java specification. Neither
order is better; what matters is that a file *fixes* one. Jeddy fixes little-endian everywhere
(DESIGN §10.1, D113), because every target it has is little-endian: a reader that maps a `.jpak`
section and hands it to the GPU does no byte swapping, and `ByteWriter` writes the host's order
by construction:

```java
private void put32(int at, int v) {
    bytes[at] = (byte) v;            // b0: bits 0..7
    bytes[at + 1] = (byte) (v >>> 8);
    bytes[at + 2] = (byte) (v >>> 16);
    bytes[at + 3] = (byte) (v >>> 24);
}
```

and the reader reverses the formula:

```java
return (bytes[p] & 0xFF) | (bytes[p + 1] & 0xFF) << 8 | (bytes[p + 2] & 0xFF) << 16 | (bytes[p + 3] & 0xFF) << 24;
```

The `& 0xFF` is the step beginners miss: a Java `byte` is signed, so `(int) bytes[p]` of `0xF0`
is `-16` = `0xFFFFFFF0`, and OR-ing that in would set every high bit. Masking first gives
`0x000000F0`. A `long` is two such 32-bit halves, low first: `low | high << 32`, with the low
half masked by `0xFFFFFFFFL` for the same reason at 64 bits.

**A worked example.** Bytes 16–23 of the file in §6 are `60 00 00 00 00 00 00 00`:
$v = 0\text{x}60 = 96$. Bytes 24–31 are `44 00 …`: $68$. So the payload is 96 bytes uncompressed
and 68 as stored — zstd saved 28 bytes on a file this small, and will save six times as many on a
real save.

## 3. Alignment: why pad to 8

A CPU loads a 4-byte `int` fastest when its address is a multiple of 4, an 8-byte `long` or
`double` at a multiple of 8; x86 tolerates misaligned loads at a cost, ARM may fault on some
instructions, and the Java memory API (`MemorySegment.get(JAVA_LONG, offset)`) *requires* natural
alignment by default and throws otherwise. A GPU wants more: a `vec4` at 16 bytes and a uniform block at 256 on most drivers, which is why
DESIGN §10.1 aligns pak sections to 16 and 64 bytes for direct upload. The rule for a format: **every
field is naturally aligned within its block, and every block starts at a multiple of the largest
alignment anything inside it needs.** For the save that is 8 (a `u64` size, a `long` of fog
bits).

That decides the header's layout. Fields are placed by decreasing alignment where it is free and
padded where it is not:

| Offset | Size | Field | Alignment check |
|---|---|---|---|
| 0 | 4 | magic | 0 mod 4 ✓ |
| 4 | 2 | version | 4 mod 2 ✓ |
| 6 | 2 | flags | 6 mod 2 ✓ |
| 8 | 4 | chunkCount | 8 mod 4 ✓ |
| 12 | 4 | metaBytes | 12 mod 4 ✓ |
| 16 | 8 | payloadBytes | 16 mod 8 ✓ |
| 24 | 8 | storedBytes | 24 mod 8 ✓ |
| 32 | 4 | payloadCrc | 32 mod 4 ✓ |
| 36 | 4 | headerCrc | 36 mod 4 ✓ |
| 40 | 8 | reserved | 40 mod 8 ✓ |
| 48 | | end | 48 mod 8 ✓: the meta block starts aligned |

Why `version` and `flags` are two `u16`s rather than two `u32`s: the header is 48 bytes either
way (the `u64`s force it), and the pair leaves 16 bits for flags without moving anything. The
`reserved` word makes the header a multiple of 8 *and* leaves room: a future field goes there
without a new version, because a version-1 reader ignores it and a version-2 reader checks the
version before reading it.

A chunk's data is padded to 8 with zeros, so the next chunk's header is aligned and, if a reader
ever wanted to map the payload and read a chunk's `long`s in place, it could. Padding bytes
exist only to make arithmetic come out: $\text{padded} = n + (8 - n \bmod 8) \bmod 8$, which the
code writes as `(8 - (length & 7)) & 7` — for $n = 1$: $(8 - 1) \,\&\, 7 = 7$; for $n = 8$:
$(8 - 0) \,\&\, 7 = 0$; for $n = 13$: $(8 - 5) \,\&\, 7 = 3$. `SaveFileTest.chunksArePaddedToEightBytes`
checks a 1-byte chunk is followed by a header at offset 24.

## 4. Self-description: magic, sizes and the order of checks

A reader is handed bytes. Before it trusts a single field it must answer, in this order, *is this
mine?*, *is it whole?*, *is it current?*, *where is everything?*:

1. **The magic.** Four bytes at offset 0 that spell the format: `J S A V`. Read as a
   little-endian `u32` they are `0x5641534A` (`'V' << 24 | 'A' << 16 | 'S' << 8 | 'J'`), which is
   why `SaveFile.MAGIC` is computed by `tag("JSAV")` rather than typed: a magic *typed* as
   `0x4A534156` would be the right bytes on a big-endian machine only. A wrong magic is the one
   error a reader reports as "not a save" rather than "damaged".
2. **The header checksum.** A CRC-32C over bytes 0–47 with the checksum field itself zeroed
   (you cannot include a number in its own checksum). It distinguishes a damaged save from a
   foreign file and protects every size the reader is about to trust.
3. **The version.** Refused if newer than the reader; read with a `switch` if older. Checked
   *after* the checksum (a damaged version byte would otherwise look like "a save from the
   future") and *before* any size (a newer header may lay the sizes out differently).
4. **The sizes against the file.** `48 + metaBytes ≤ length`, `metaBytes` a multiple of 8,
   `payloadAt + storedBytes ≤ length`, and sanity bounds (`MAX_PAYLOAD = 2^31`). Every later
   read is within a range this step proved exists, so the parser never throws an
   `ArrayIndexOutOfBounds` from a size a hostile file chose.
5. **The payload checksum**, then the decompression (the zstd frame's own content size must
   equal `payloadBytes`), then the chunk walk, where each `length` is checked against what
   remains before a `slice` is handed out.

`SaveFileTest.damageIsReportedByWhatItBroke` flips one byte in each place and asserts the
message names the right thing: "not a save file", "header checksum", "newer than this build",
"payload checksum", "overruns the file", "claims N bytes". The design rule behind the order:
*fail at the check that explains the failure.*

## 5. CRC-32C, briefly

A cyclic redundancy check treats the message as a polynomial over GF(2) (bits are coefficients;
addition is XOR) and keeps the remainder of dividing it by a fixed generator polynomial of degree
32. The "C" (Castagnoli) generator, `0x1EDC6F41`, detects any burst of up to 32 bits and any error of
up to three bits in a message up to $2^{31}$ bits long (up to five bits in messages under 655
bytes, by Koopman's tables), which is what a disk or a truncated download produces; it is *not* a cryptographic hash (anyone can craft a file with a
matching CRC), which a save does not need. The JDK's `java.util.zip.CRC32C` uses the SSE4.2
`crc32` instruction through a HotSpot intrinsic, at least a byte per cycle; a 110 KB payload costs
some tens of microseconds. The engine's
`compress.Checksums.crc32c` is the same algorithm over a `MemorySegment` for mapped paks.

Why *two* checksums rather than one over the whole file: the slot list reads the header and the
meta block only (`SaveFile.peek`), and it still wants to know the header is sound. The payload's
CRC is computed over the *stored* (compressed) bytes, so a damaged file is rejected before
zstd sees it — a decompressor on corrupted input can produce garbage of the right size.

## 6. A real file, byte by byte

The golden file `modules/save/src/test/resources/dev/jeddy/save/fixtures/v1.jsave` (172 bytes):

```
0000  4a 53 41 56 01 00 01 00 02 00 00 00 38 00 00 00   JSAV........8...
0010  60 00 00 00 00 00 00 00 44 00 00 00 00 00 00 00   `.......D.......
0020  63 47 8f 2c 3f 3e c3 e1 00 00 00 00 00 00 00 00   cG.,?>..........
0030  02 00 00 00 05 00 74 69 74 6c 65 0f 00 43 72 79   ......title..Cry
0040  70 74 2c 20 32 20 72 6f 75 6e 64 73 05 00 6c 65   pt, 2 rounds..le
0050  76 65 6c 0c 00 6c 65 76 65 6c 73 2f 63 72 79 70   vel..levels/cryp
0060  74 00 00 00 00 00 00 00 28 b5 2f fd 24 60 bd 01   t.......(./.$`..
```

| Bytes | Hex | Value | Meaning |
|---|---|---|---|
| 0–3 | `4a 53 41 56` | `JSAV` | magic |
| 4–5 | `01 00` | 1 | format version |
| 6–7 | `01 00` | 1 | flags: zstd |
| 8–11 | `02 00 00 00` | 2 | chunks |
| 12–15 | `38 00 00 00` | 56 | meta block bytes (padded; the payload starts at 48 + 56 = 104) |
| 16–23 | `60 00 00 00 00 00 00 00` | 96 | payload bytes uncompressed: two chunks of 16 + data |
| 24–31 | `44 00 00 00 00 00 00 00` | 68 | stored bytes (the zstd frame) |
| 32–35 | `63 47 8f 2c` | `0x2C8F4763` | CRC-32C of the 68 stored bytes |
| 36–39 | `3f 3e c3 e1` | `0xE1C33E3F` | CRC-32C of the header with these four bytes zero |
| 40–47 | `00 …` | 0 | reserved |
| 48–51 | `02 00 00 00` | 2 | meta pairs |
| 52–53 | `05 00` | 5 | length of `title` |
| 54–58 | `74 69 74 6c 65` | `title` | the key |
| 59–60 | `0f 00` | 15 | length of the value |
| 61–75 | `43 72 79 70 74 2c 20 32 20 72 6f 75 6e 64 73` | `Crypt, 2 rounds` | the value |
| 76–77, 78–82 | `05 00`, `level` | | the second key |
| 83–84, 85–96 | `0c 00`, `levels/crypt` | 12 | the second value |
| 97–103 | `00 × 7` | | padding: 4 bytes of count + 45 of pairs = 49, padded to 56 |
| 104–107 | `28 b5 2f fd` | | zstd's own magic (`0xFD2FB528`, also little-endian) |

Everything a slot list needs is readable up to byte 103 without decompressing anything, and the
file says so itself: `metaBytes` at offset 12 tells `peek` how many bytes to read after the
header. Inside the frame (decompress it with `zstd -d` and run the same dump) are the two chunks:
`GAME` version 3 with 24 bytes of data (`float64 12.5`, `int32 7`, the string `turn-based` as
`0a 00` + 10 bytes; already a multiple of 8), then `FOG ` version 1 with `int32 4` and four
`long`s: 36 bytes padded to 40. Two headers of 16 plus 24 plus 40 is the 96 of offset 16.

## 7. Versions and the three places they live

A format version is a promise: *a reader that knows version $k$ can read every file of version
$\le k$.* The cost of the promise is that the old reading code stays forever, so the question is
how to keep that code small. The save keeps three versions at three granularities, each owned by
the party that changes it (chapter 16 §2.4):

- the **file** version changes when the framing changes — rarely, since the header has reserved
  room and flags;
- a **chunk** version is the game's: which systems write into it, in which order;
- a **record** format is a system's: the first `int` of `Crowd.write`'s output.

The degenerate `switch` with one case is what version 1 looks like:

```java
int format = r.int32();
if (format != 1) {
    throw new IllegalStateException("crowd save format " + format + "; this build reads 1");
}
```

and version 2 adds `case 1 -> readV1(r)` beside the new layout. What protects the promise is not
the switch but the **golden file**: the bytes version 1 wrote, checked into the test resources
when version 1 was current, and a test that reads them with assertions about their content. A
reader that drops version 1 fails `formatOneFixtureStillReads`. (The component stores are the
exception that needs no version: their header *is* the description, columns by name and type,
and a reader matches rather than assumes — D111.)

A thing to notice about the file in §6: it was written by the build that introduced format 1
and will never be regenerated. `testing.Fixtures` writes a fixture only when it is missing and
then fails, so that a fixture is reviewed and committed, never silently replaced by what the
current build happens to write.

## 8. Things to run

- `./gradlew :save:test`: the container, every damage case, the padding, the golden file.
- `od -A d -t x1 modules/save/src/test/resources/dev/jeddy/save/fixtures/v1.jsave | head`:
  the dump of §6 (or `xxd`; `Format-Hex` on PowerShell).
- `zstd -d < v1.jsave`… will fail: the frame starts at byte 104. `tail -c +105 v1.jsave | zstd -d | od -A d -t x1`
  shows the two chunks.
- Change `ByteWriter.put32` to write the most significant byte first and run `:core:test`:
  `ByteStreamTest.scalarsRoundTripLittleEndian` fails on `assertEquals((byte) 0x44, b[3])`.
- In `SaveFile.parse`, move the version check before the header CRC and flip byte 4 of a file:
  the message changes from "damaged" to "newer than this build".

## 9. Exercises

1. Write the bytes of `float32 1.5f` and `int64 -1` in little-endian, then check with
   `ByteWriter`. *Hint: §1 for the float; two's complement for the long.*
2. A header of 40 bytes would hold the same fields if `reserved` were dropped. What breaks?
   *Hint: §3, the meta block's alignment.*
3. Compute by hand the padded size of chunks of 0, 7, 8, 9 and 16 data bytes with the formula in
   §3, and write a test like `chunksArePaddedToEightBytes` for the 0-byte case. *Hint: a
   0-byte chunk is legal; what does `SaveWriter.chunk` write for it?*
4. The payload CRC is over the stored bytes. If it were over the *uncompressed* bytes instead,
   which step of §4 would move, and what could happen before it? *Hint: §5's last paragraph.*
5. Design a format 2 of the `CROW` record that adds a per-agent `int team`, keeping format 1
   readable, and list the files you must change and the one you must not. *Hint: §7; the golden
   file is read-only.*
6. The meta block stores `savedAt` as epoch milliseconds in decimal text. Why text rather than a
   `u64`, and what would a `u64` have cost the capture? *Hint: chapter 16 §5, the fourth pitfall;
   the meta block is pairs of strings by design.*

---

*Code this lesson quotes: `core.io.ByteWriter`, `core.io.ByteReader`, `save.SaveFile`,
`save.SaveWriter`, `physics.crowd.Crowd.read`, `testing.Fixtures`. Decisions: D113, D116, D117
in [DESIGN §17](../../DESIGN.md); the format in [formats/jsave.md](../../formats/jsave.md).*
