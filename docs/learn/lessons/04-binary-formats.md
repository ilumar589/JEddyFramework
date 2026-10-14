# Lesson 4 — Build a save file format: bytes, byte order, text, alignment, checksums and versions

*A save file is the game's state written to disk so that another run of the game — maybe a newer
version, on another computer, after the file sat in a cloud folder for a year — can read it back
exactly. This lesson builds the engine's binary stream (`ByteWriter`, `ByteReader`) and its save
container (`.jsave`: `SaveWriter`, `SaveFile`) from empty files, deriving every choice from what a
reader must be able to do with bytes it did not write: agree on the order of a number's bytes,
encode text, keep fields aligned, notice damage, and survive the format changing over the years. It
ends by reading a real save file byte by byte.*

**Before you start.** You need [Lesson 0](00-foundations.md) §3–7: hexadecimal, bytes, two's
complement, the signed-byte trap, shifts and masks, and how a `float` is stored. Chapter [16
Saves](../16-saves.md) explains *what* the game saves and when; this lesson is about *how* the bytes
are laid out. Every binary format in the engine (`.jmesh`, `.jlevel`, `.jrules`, `.jpak`, see
[formats/](../../formats/)) follows the same rules.

**What you will build**

| Piece | File | What it does |
|---|---|---|
| The writer | [`core.io.ByteWriter`](../../../modules/core/src/main/java/dev/jeddy/core/io/ByteWriter.java) | a growable little-endian `byte[]`: numbers, strings, arrays; reused without allocating |
| The reader | [`core.io.ByteReader`](../../../modules/core/src/main/java/dev/jeddy/core/io/ByteReader.java) | reads it back, checking every size before trusting it |
| The capture | [`save.SaveWriter`](../../../modules/save/src/main/java/dev/jeddy/save/SaveWriter.java) | meta entries and tagged, versioned, padded chunks |
| The container | [`save.SaveFile`](../../../modules/save/src/main/java/dev/jeddy/save/SaveFile.java) | the 48-byte header, two checksums, compression; reading in the right order |
| The golden file | [`testing.Fixtures`](../../../testing/src/main/java/dev/jeddy/testing/Fixtures.java) and `fixtures/v1.jsave` | the bytes an old version wrote, kept under test forever |

**Contents**

- Part A — The ideas
  1. [A file is bytes, and nothing else](#1-a-file-is-bytes-and-nothing-else)
  2. [Byte order: little-endian, derived](#2-byte-order-little-endian-derived)
  3. [Floats and booleans as bytes](#3-floats-and-booleans-as-bytes)
  4. [Text: from characters to UTF-8](#4-text-from-characters-to-utf-8)
  5. [Alignment and padding](#5-alignment-and-padding)
  6. [Noticing damage: from parity to CRC](#6-noticing-damage-from-parity-to-crc)
  7. [Compression, briefly](#7-compression-briefly)
  8. [Versions: the promise a format makes](#8-versions-the-promise-a-format-makes)
- Part B — Building it
  9. [Step 1: `ByteWriter`](#9-step-1-bytewriter)
  10. [Step 2: `ByteReader`](#10-step-2-bytereader)
  11. [Step 3: designing the container](#11-step-3-designing-the-container)
  12. [Step 4: `SaveWriter` — chunks with patched lengths](#12-step-4-savewriter--chunks-with-patched-lengths)
  13. [Step 5: encoding the file](#13-step-5-encoding-the-file)
  14. [Step 6: reading it, in the right order](#14-step-6-reading-it-in-the-right-order)
  15. [Step 7: writing to disk without losing the old save](#15-step-7-writing-to-disk-without-losing-the-old-save)
  16. [Step 8: versions in practice, and golden files](#16-step-8-versions-in-practice-and-golden-files)
- Part C — Reading a real file and proving it
  17. [A real file, byte by byte](#17-a-real-file-byte-by-byte)
  18. [The tests](#18-the-tests)
  19. [Things to run](#19-things-to-run)
  20. [Exercises](#20-exercises)
  21. [Glossary](#21-glossary)

---

# Part A — The ideas

## 1. A file is bytes, and nothing else

In memory, a `float` is 32 bits that the CPU interprets as a float because of the instruction that
loads it. On disk there are no types: a file is a sequence of bytes, numbered from 0 (the
**offset**). Bytes mean nothing until a reader agrees with the writer on three things:

1. **Which bytes belong together** — the **layout**: "bytes 16 to 23 are the payload size".
2. **In which order** they form a number — the **byte order** (§2).
3. **What the number means** — which depends on the **version** of the format (§8).

A **format** is that agreement written down. A *good* format also lets a reader **check** the
agreement before trusting it: is this even my kind of file? Is it complete? Is it damaged? Is it from
a version I understand?

### 1.1 Why binary and not text

Text formats such as JSON are readable by people, which is why the engine uses them for things people
edit (the rules' source files). A save is different: nobody edits it, and it has three needs text
handles badly:

- **Exact numbers.** A `float` printed as decimal text and parsed back must round-trip exactly, which
  takes care (and 9 significant digits). Writing its 32 raw bits is exact by construction.
- **Size and speed.** The demo's save holds thousands of floats and bit fields; as text it would be
  several times larger and much slower to parse.
- **Damage detection.** A truncated JSON file can still parse.

*Rejected alternatives*: Java serialization (it runs code from the file while reading it, a
security hazard, and breaks when classes change, D111); Protocol Buffers or FlatBuffers (good
formats, but runtime modules take no third-party dependencies, CLAUDE.md rule 5). The engine's own
stream is a few hundred lines.

## 2. Byte order: little-endian, derived

### 2.1 The problem

A 32-bit integer is four bytes. Take $v = $ `0x11223344` (287,454,020). In hex each pair of digits is
one byte (L00 §3.3): `11`, `22`, `33`, `44`. The `11` is the **most significant** byte (it is worth
the most: $\texttt{0x11} \times 2^{24}$), the `44` the **least significant**. Which one goes first in
the file?

### 2.2 The two answers

Number the bytes in the file $b_0, b_1, b_2, b_3$ (in the order they appear). Then either

$$v = b_0 + 2^8 b_1 + 2^{16} b_2 + 2^{24} b_3 \qquad \text{(little-endian: the least significant byte first)}$$

or

$$v = 2^{24} b_0 + 2^{16} b_1 + 2^{8} b_2 + b_3 \qquad \text{(big-endian: the most significant byte first)}.$$

For `0x11223344`:

```
offset:          0    1    2    3
little-endian:  44   33   22   11
big-endian:     11   22   33   44
```

Big-endian matches how we write numbers (most significant digit first). Little-endian matches how
the CPUs this engine runs on — x86-64, the only processor family it targets (ARM, too, is
little-endian in practice) — store numbers in memory (L00 §8.1). Java's
own `DataOutputStream` is big-endian, by the Java specification.

Neither order is better. What matters is that a format **fixes** one, so that a file written on one
machine reads the same on every other. Jeddy fixes **little-endian** for every format (DESIGN §10.1,
D113): every target machine is little-endian, so a pak section mapped into memory and handed to the
GPU needs no byte swapping.

### 2.3 Writing and reading little-endian by hand

**Writing**: byte $b_k$ is bits $8k$ to $8k + 7$ of the value, so shift them down and keep the low 8
(the cast to `byte` keeps the low 8 bits):

```java
bytes[at]     = (byte) v;            // bits 0..7
bytes[at + 1] = (byte) (v >>> 8);    // bits 8..15
bytes[at + 2] = (byte) (v >>> 16);   // bits 16..23
bytes[at + 3] = (byte) (v >>> 24);   // bits 24..31
```

**Reading**: the formula of §2.2, with shifts for the powers of two and OR for the sum (the shifted
bytes do not overlap, so OR and addition give the same result):

```java
int v = (bytes[p] & 0xFF)
        | (bytes[p + 1] & 0xFF) << 8
        | (bytes[p + 2] & 0xFF) << 16
        | (bytes[p + 3] & 0xFF) << 24;
```

The `& 0xFF` is the step beginners miss, and L00 §5.4 explained why it is needed: a Java `byte` is
signed, so the byte `0xF0` read as `(int) bytes[p]` is −16 = `0xFFFFFFF0`, and OR-ing that in would
set all 24 high bits. `& 0xFF` keeps only the byte's own 8 bits.

**Worked example.** Bytes `44 33 22 11` → `0x44 | 0x33 << 8 | 0x22 << 16 | 0x11 << 24` = `0x11223344`.
Bytes `60 00 00 00` → 96. Bytes `ff ff ff ff` → `0xFFFFFFFF`, which as an `int` is −1 (two's
complement, L00 §5.3).

**A `long`** is two 32-bit halves, low half first:

```java
long low = get32(p) & 0xFFFFFFFFL;       // mask: the int may be negative
long high = get32(p + 4) & 0xFFFFFFFFL;
long v = low | high << 32;
```

The mask is the same trap one size up: widening a negative `int` to `long` sign-extends it, and the
32 extra one-bits would clobber the high half. `0xFFFFFFFFL` (with the `L`) is a `long` with 32 ones;
without the `L` it would be the `int` −1, which widens to 64 ones and masks nothing.

## 3. Floats and booleans as bytes

A `float` is 32 bits with a meaning (L00 §7). To store it, ask Java for those bits as an `int` and
store the `int`:

```java
int bits = Float.floatToRawIntBits(1.5f);   // 0x3FC00000
float back = Float.intBitsToFloat(bits);    // 1.5f, exactly
```

`1.5f` is therefore the bytes `00 00 c0 3f` in a little-endian file. The *raw* variant keeps NaN
values bit for bit (the non-raw `floatToIntBits` turns every NaN into one canonical pattern), so
writing and reading returns exactly what was there. A `double` works the same way with
`doubleToRawLongBits` and 8 bytes: 12.5 is `00 00 00 00 00 00 29 40`.

A `boolean` is one byte, 0 or 1. Bit-packing eight booleans into a byte would save space, but the
engine's large boolean sets (the fog of war) are already stored as bit sets in `long`s by the
systems that own them, so the stream keeps the simple form.

## 4. Text: from characters to UTF-8

### 4.1 Characters are numbers

Text is stored as numbers too. **Unicode** gives every character a number, its **code point**,
written `U+` and hex: `A` is U+0041, `é` U+00E9, `€` U+20AC, the emoji 😀 U+1F600. There are about 1.1
million possible code points (up to U+10FFFF).

An **encoding** decides how code points become bytes. Java keeps strings in memory as **UTF-16**: a
`char` is 16 bits, enough for code points up to U+FFFF; larger ones (like 😀) take *two* `char`s,
called a **surrogate pair** (`"😀"`). So `String.length()` counts `char`s, not characters.

### 4.2 UTF-8

Files and networks almost always use **UTF-8**, which stores each code point in 1 to 4 bytes:

| Code points | Bits needed | Bytes | Pattern (x = the code point's bits) |
|---|---|---|---|
| U+0000 – U+007F | 7 | 1 | `0xxxxxxx` |
| U+0080 – U+07FF | 11 | 2 | `110xxxxx 10xxxxxx` |
| U+0800 – U+FFFF | 16 | 3 | `1110xxxx 10xxxxxx 10xxxxxx` |
| U+10000 – U+10FFFF | 21 | 4 | `11110xxx 10xxxxxx 10xxxxxx 10xxxxxx` |

The patterns are self-describing: a byte starting `0` is a whole character (plain ASCII text is valid
UTF-8 unchanged), a byte starting `110`, `1110` or `11110` starts a 2-, 3- or 4-byte sequence, and a
byte starting `10` continues one. A reader that lands in the middle of a sequence can find the next
character.

Worked examples (each verified):

- **é**, U+00E9 = binary `000 1110 1001`, 11 bits → two bytes: `110` + `00011` = `1100 0011` = `C3`,
  then `10` + `101001` = `1010 1001` = `A9`. So `é` is `C3 A9`.
- **€**, U+20AC = `0010 0000 1010 1100`, 16 bits → three bytes: `1110 0010` = `E2`, `10 000010` =
  `82`, `10 101100` = `AC`: `E2 82 AC`.
- **😀**, U+1F600 = `0 0001 1111 0110 0000 0000`, 21 bits → four bytes: `11110 000` = `F0`,
  `10 011111` = `9F`, `10 011000` = `98`, `10 000000` = `80`: `F0 9F 98 80`.

### 4.3 How the stream stores a string

A **length prefix** (how many bytes follow) then the UTF-8 bytes: `0a 00` + `turn-based` is a string of
10 bytes. The length is a `u16` (an unsigned 16-bit number, at most 65,535): the strings a save holds
are ids, names and short labels, two bytes of length are plenty, and the writer checks the limit.
(Rejected: a 4-byte length, which wastes two bytes per string; a terminating zero byte, which needs a
scan to find the end and cannot hold a zero inside.)

## 5. Alignment and padding

### 5.1 Why addresses matter

L00 §8.4: a CPU loads a 4-byte value fastest when its address is a multiple of 4, an 8-byte value at a
multiple of 8. x86 tolerates misaligned loads at some cost; some ARM instructions fault; Java's
`MemorySegment.get(ValueLayout.JAVA_LONG, offset)` throws when the *address* it reads is not a
multiple of 8, unless you ask for the unaligned layout (`JAVA_LONG_UNALIGNED`). Note "address": a
segment over a Java `byte[]` only promises 1-byte alignment, so aligned layouts fail on it at every
offset, which is one more reason `ByteReader` assembles values from single bytes. A GPU wants more — a `vec4` at 16 bytes, uniform buffers at 256 (lesson 1) —
which is why the asset paks align their sections to 16 and 64 bytes.

A file that will be read straight from memory (mapped, or loaded and viewed through a
`MemorySegment`) should therefore keep its fields **naturally aligned**: each field's offset a
multiple of its size. The rule the engine's formats follow:

> Every field is naturally aligned within its block, and every block starts at a multiple of the
> largest alignment anything inside it needs.

For the save, that largest alignment is 8 (64-bit sizes and the `long`s of the fog bit set).

### 5.2 Padding to a multiple of 8

To make the next block start aligned, a block of $n$ bytes is followed by **padding** — zero bytes
that exist only to make the arithmetic come out — up to the next multiple of 8. How many? The
remainder of $n$ after dividing by 8 is $n \bmod 8$; if it is 0 no padding is needed, otherwise $8 -
(n \bmod 8)$:

$$\text{pad}(n) = (8 - n \bmod 8) \bmod 8.$$

The outer `mod 8` turns the "8" of the aligned case into 0. Since 8 is a power of two, `n mod 8` is
`n & 7` (L00 §6.5), and the code writes it as:

```java
int pad = (8 - (length & 7)) & 7;
```

| $n$ | $n \,\&\, 7$ | $8 - \cdot$ | $\&\, 7$ | padded size |
|---|---|---|---|---|
| 0 | 0 | 8 | 0 | 0 |
| 1 | 1 | 7 | 7 | 8 |
| 8 | 0 | 8 | 0 | 8 |
| 13 | 5 | 3 | 3 | 16 |
| 36 | 4 | 4 | 4 | 40 |

An honest note: the engine's `ByteReader` reads byte by byte and would not care about alignment. The
save keeps the rule anyway because it costs at most 7 bytes per block, keeps the same layout rules as
the formats that *are* mapped, and lets a future reader map a save's payload and read its `long`s in
place.

## 6. Noticing damage: from parity to CRC

Files get damaged: a disk sector goes bad, a download stops halfway, a cloud folder syncs a
half-written copy, a USB stick is pulled out. A reader must notice before it trusts any number in the
file. The tool is a **checksum**: a small number computed from the data when it is written and stored
with it. The reader computes it again; if the two differ, the data changed.

### 6.1 Simple checksums and their blind spots

- **A parity bit**: the XOR of all bits (1 if the number of ones is odd). It catches any single
  flipped bit, and misses any two.
- **A sum of all bytes** (mod 256, or mod $2^{32}$): catches any single changed byte, but not two
  bytes **swapped** (`12 34` and `34 12` have the same sum), nor two changes that cancel.

Real damage is often a **burst**: several neighbouring bits wrong at once. A good checksum must catch
those.

### 6.2 CRC: division with XOR

A **cyclic redundancy check** (CRC) treats the message as one long binary number and keeps the
**remainder** of dividing it by a fixed number, the **generator**. Two twists make it fast and strong:

- the "subtraction" in the long division is **XOR** (no borrows; each bit position on its own),
- the generator is chosen so that typical errors can never be a multiple of it.

**A tiny worked example**, with the 4-bit generator `1011` (a 3-bit CRC) and the 4-bit message
`1101`. Append 3 zero bits (the CRC's size) and divide: wherever the leading bit is 1, XOR the
generator in, lined up under it:

```
1101000          the message followed by 000
1011             leading 1: XOR the generator
----
0110000
 1011            leading 1 one place further: XOR again
 ----
0011100
  1011
  ----
0001010
   1011
   ----
0000001          remainder 001: the CRC
```

The writer sends `1101` followed by `001`. The reader divides the whole `1101001` by `1011` the same
way and gets remainder `000`: intact. Flip any one bit — say `1101001` → `1101101` — and the
remainder is no longer zero (`100`). Damage noticed.

Why it catches bursts: the reader gets the sent bits XOR an **error pattern** $E$. The remainder of
the received data is the remainder of $E$ (the sent part divides exactly). The damage goes unnoticed
only if $E$ itself is a multiple of the generator. A generator with $k$ check bits whose lowest bit is
1 can never divide a burst shorter than $k + 1$ bits, so **every burst of up to $k$ bits is caught**.

### 6.3 CRC-32C

The engine uses **CRC-32C** (the "Castagnoli" CRC): 32 check bits, generator `0x1EDC6F41`. It
catches every burst of up to 32 bits, every error of up to 3 bits in messages of up to $2^{31}$ bits,
and any other random damage with probability $1 - 2^{-32}$ (one miss in 4 billion). Two conventions
of real CRCs, both in the standard: the register starts at all ones (so that leading zero bytes
change the result) and the result is inverted at the end; and the bits of each byte are processed
lowest first, which is why the code uses the bit-reversed generator `0x82F63B78`. A complete
bit-by-bit implementation:

```java
static int crc32c(byte[] data) {
    int crc = 0xFFFFFFFF;
    for (byte b : data) {
        crc ^= b & 0xFF;
        for (int k = 0; k < 8; k++) {
            crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0x82F63B78 : crc >>> 1;
        }
    }
    return ~crc;
}
```

Every CRC standard publishes a **check value**: the CRC of the nine ASCII bytes `123456789`. For CRC-32C
it is `0xE3069283`; test any implementation with it. The engine does not use this loop: the JDK's
`java.util.zip.CRC32C` computes the same function using the CPU's dedicated `crc32` instruction (SSE4.2
on x86), several bytes per cycle.

**What a CRC is not**: protection against someone *deliberately* changing a file. Anyone can compute a
matching CRC for any content (the test in §18 does exactly that to craft damaged files). Protecting a
save against cheating would need a cryptographic signature with a secret key, which a single-player
save does not need.

## 7. Compression, briefly

The save's main block is compressed with **zstd** (Zstandard), a fast general-purpose compressor the
engine already uses for its paks (chapter 10). Compression finds repetition — runs of zero bytes in a
fog bit set, the same float patterns again and again — and stores it shorter. A compressed **frame**
starts with zstd's own magic number, the bytes `28 b5 2f fd`, records the original size, and can
end with a checksum of the original bytes (the engine's frames have one).

Only the main block is compressed. The header and the small "meta" block (the title and play time a
save-slot list shows) stay uncompressed, so a list of 50 saves reads 50 small prefixes and decompresses
nothing.

## 8. Versions: the promise a format makes

Games are patched; formats change. A format **version** is a promise: *a reader that knows version $k$
reads every file of version $k$ or older.* The cost is that the code for every old version stays
forever. To keep that code small, the save keeps versions at three levels, each owned by the code that
changes it (chapter 16, D117):

- the **file** version: the container's own layout (the header, the chunk framing). It rarely
  changes: the header has reserved space and flag bits for growth;
- a **chunk** version: one per tagged block, owned by the game (which systems it writes, in which
  order);
- a **record** format: the first `int` each system writes for itself (`Crowd.write` begins with its
  `SAVE_FORMAT`).

A reader that meets a version **newer** than it knows refuses with a clear message ("newer than this
build"); one that meets an **older** version reads it with the old code. And what makes sure the old
code really still works is a **golden file** (§16): the bytes an old version wrote, kept in the tests.

---

# Part B — Building it

## 9. Step 1: `ByteWriter`

### 9.1 Requirements

Every system with state to save (the crowd, the fog, the rules, the component stores) writes itself
into one stream. The stream must:

- write numbers little-endian (§2), floats as raw bits (§3), strings as a `u16` length and UTF-8
  (§4), and whole arrays (a store's column, a bit set) in one call;
- **allocate nothing** once warmed up: a save is captured on the game thread between two ticks, and
  a capture that allocated would make the frame hitch (D118). So the buffer is **kept** between
  saves and **reused**.

### 9.2 The buffer

```java
public final class ByteWriter {
    private byte[] bytes;
    private int size;

    public ByteWriter(int capacity) {
        bytes = new byte[Math.max(16, capacity)];
    }

    public ByteWriter() {
        this(1024);
    }

    private void ensure(int more) {
        if (size + more > bytes.length) {
            bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, size + more));
        }
    }

    /** Rewinds to empty, keeping the buffer. */
    public void reset() {
        size = 0;
    }
```

- **`bytes` and `size`**: a plain `byte[]` and how much of it is used — the same shape as an
  `ArrayList`, without boxing.
- **`ensure(more)`** grows the array when the next write would not fit, to at least double the size
  (so that a stream written in many small pieces copies each byte only about once on average) or to
  what is needed if that is more.
- **`reset`** forgets the contents but keeps the array. The game keeps one writer for the life of a
  level; after the first save it is big enough, and every later save allocates nothing.

*Why not `ByteBuffer`?* A `java.nio.ByteBuffer` has a fixed capacity (growing means allocating a new
one and copying, by hand) and its default order is big-endian, so every buffer would need
`order(LITTLE_ENDIAN)`. A `byte[]` with explicit shifts is simpler and the JIT compiles it just as
well.

### 9.3 Numbers

```java
public void int8(int v) {
    ensure(1);
    bytes[size++] = (byte) v;
}

public void int16(int v) {
    ensure(2);
    bytes[size] = (byte) v;
    bytes[size + 1] = (byte) (v >>> 8);
    size += 2;
}

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

public void int64(long v) {
    ensure(8);
    put32(size, (int) v);                 // the low half first
    put32(size + 4, (int) (v >>> 32));    // then the high half
    size += 8;
}

public void float32(float v) {
    int32(Float.floatToRawIntBits(v));
}

public void float64(double v) {
    int64(Double.doubleToRawLongBits(v));
}

public void bool(boolean v) {
    ensure(1);
    bytes[size++] = (byte) (v ? 1 : 0);
}
```

This is §2.3 and §3 in code. `int8` and `int16` take an `int` and keep its low 8 or 16 bits, so a
caller can pass −1 or 65,535 alike. `put32` is separate from `int32` because three other methods (the
`long`, the bulk arrays, and patching, §9.5) need to write 4 bytes at a given position. `int64`
splits the `long` into its low and high `int` halves: `(int) v` keeps the low 32 bits, `(int) (v >>>
32)` the high.

### 9.4 Strings, without allocating

The easy way, `s.getBytes(StandardCharsets.UTF_8)`, allocates a new `byte[]` on every call. The save
writes strings during the capture (store names, meta values), so the writer encodes UTF-8 itself, in
two passes over the characters: first count the bytes (needed for the length prefix), then write them.

```java
public void string(CharSequence s) {
    int chars = s == null ? 0 : s.length();
    int n = 0;
    for (int i = 0; i < chars; i++) {                       // pass 1: how many bytes?
        char c = s.charAt(i);
        if (c < 0x80) {
            n += 1;
        } else if (c < 0x800) {
            n += 2;
        } else if (Character.isHighSurrogate(c)
                && i + 1 < chars
                && Character.isLowSurrogate(s.charAt(i + 1))) {
            n += 4;                                         // a surrogate pair: one 4-byte character
            i++;
        } else {
            n += 3;
        }
    }
    if (n > 0xFFFF) {
        throw new IllegalArgumentException("string too long for the stream: " + n);
    }
    ensure(2 + n);
    bytes[size] = (byte) n;                                 // the u16 length
    bytes[size + 1] = (byte) (n >>> 8);
    size += 2;
    for (int i = 0; i < chars; i++) {                       // pass 2: the bytes (§4.2's table)
        char c = s.charAt(i);
        if (c < 0x80) {
            bytes[size++] = (byte) c;
        } else if (c < 0x800) {
            bytes[size++] = (byte) (0xC0 | c >> 6);
            bytes[size++] = (byte) (0x80 | c & 0x3F);
        } else if (Character.isHighSurrogate(c)
                && i + 1 < chars
                && Character.isLowSurrogate(s.charAt(i + 1))) {
            int cp = Character.toCodePoint(c, s.charAt(++i));
            bytes[size++] = (byte) (0xF0 | cp >> 18);
            bytes[size++] = (byte) (0x80 | cp >> 12 & 0x3F);
            bytes[size++] = (byte) (0x80 | cp >> 6 & 0x3F);
            bytes[size++] = (byte) (0x80 | cp & 0x3F);
        } else {
            int cp = Character.isSurrogate(c) ? 0xFFFD : c;
            bytes[size++] = (byte) (0xE0 | cp >> 12);
            bytes[size++] = (byte) (0x80 | cp >> 6 & 0x3F);
            bytes[size++] = (byte) (0x80 | cp & 0x3F);
        }
    }
}
```

Read the encoding lines against §4.2's patterns. For `é` (U+00E9, `c < 0x800`): `0xC0 | c >> 6` puts
the top 5 of its 11 bits after the `110` marker → `0xC3`; `0x80 | c & 0x3F` puts the low 6 bits after
the `10` marker → `0xA9`. (`>>` and `&` bind tighter than `|` in Java, so no parentheses are needed.)
The other cases do the same with 3 and 4 bytes.

Details worth noticing:

- **`CharSequence`, not `String`**: a `StringBuilder` is a `CharSequence`, so the game can format a
  meta value ("Crypt, 2 rounds") into a kept `StringBuilder` and write it without ever creating a
  `String` — the capture stays allocation-free.
- **`null` is written as the empty string**, so callers need no special case.
- **A lone surrogate** (half of a pair without its other half — invalid UTF-16, but a `String` can
  contain one) is written as U+FFFD, the official "replacement character", rather than as bytes no
  UTF-8 reader would accept.

### 9.5 Arrays, raw bytes and patching

```java
/** Writes {@code n} and {@code a[0, n)}. */
public void ints(int[] a, int n) {
    int32(n);                         // the count first
    ensure(4 * n);
    for (int i = 0; i < n; i++) {
        put32(size + 4 * i, a[i]);
    }
    size += 4 * n;
}
```

Every bulk method writes a `u32` **count** and then the elements: `ints`, `longs`, `floats` (as raw
bits), `bools` (a byte each), `bytes`. Taking `n` separately from the array lets a store write the
first `size` rows of a column that has spare capacity. There are also:

```java
/** Appends {@code a[offset, offset + n)} without a count (a nested stream, padding). */
public void raw(byte[] a, int offset, int n) { ... System.arraycopy ... }

/** Appends {@code n} zero bytes. */
public void zeros(int n) { ... Arrays.fill ... }

/** Overwrites the {@code int32} at {@code at} (a length patched after its contents). */
public void patchInt32(int at, int v) {
    if (at < 0 || at + 4 > size) {
        throw new IndexOutOfBoundsException("patch at " + at + " of " + size);
    }
    put32(at, v);
}
```

**Patching** solves a common problem: a block's length must come *before* the block, but you only
know it *after* writing the block. So write a placeholder 0, remember its position, write the block,
then patch the real length in. `SaveWriter` (§12) does exactly this for every chunk.

Finally, the accessors: `size()`, `buffer()` (the backing array itself, valid up to `size`, so the
save job can copy it out without an intermediate copy), `capacity()` and `toBytes()` (a trimmed copy,
for tests and tools).

## 10. Step 2: `ByteReader`

### 10.1 Trust nothing

The writer can assume its caller is correct. The reader cannot: it may be handed a truncated file, a
damaged one, or one crafted to crash it. So **every read checks its size first**, and failures say
where:

```java
public final class ByteReader {
    /** The stream ended before a read completed. */
    public static final class Truncated extends IllegalStateException {
        Truncated(int at, long needed) {
            super("stream truncated at byte " + at + " (needed " + needed + " more)");
        }
    }

    private final byte[] bytes;
    private final int end;     // one past the last byte this reader may read
    private int at;            // the next byte to read

    public ByteReader(byte[] bytes, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > bytes.length) {
            throw new IllegalArgumentException("range " + offset + "+" + length);
        }
        this.bytes = bytes;
        this.at = offset;
        this.end = offset + length;
    }

    private void need(int n) {
        // n > end - at, not at + n > end: a huge n from a damaged file must not overflow the sum
        // into a negative number that passes the check.
        if (n < 0 || n > end - at) {
            throw new Truncated(at, n);
        }
    }
```

- A reader covers a **range** of an array (`offset`, `length`), so a chunk inside a bigger buffer can
  get its own reader that cannot read past the chunk (`slice`, §10.3).
- **`need(n)`** is called before every read. Look at how it compares: `n > end - at`, not the more
  natural `at + n > end`. With a hostile `n` close to `Integer.MAX_VALUE` and `at` above zero, `at +
  n` **overflows** (L00 §4.3) into a negative number, which is *not* greater than `end`, and the check
  would pass. `end - at` cannot overflow (both are between 0 and the array's length). This line was
  written the natural way at first, and changed when this lesson's review asked "what does a crafted
  file do here?" — together with two other gaps (§10.3, §14) that the same question found.
- `Truncated` is an `IllegalStateException`, so callers that do not care need not declare it; the save
  module turns problems it detects into its own checked `SaveFormatException`.

### 10.2 Numbers

```java
public int int8() {
    need(1);
    return bytes[at++];                  // a signed byte, -128..127
}

public int int16() {
    need(2);
    int v = (bytes[at] & 0xFF) | (bytes[at + 1] & 0xFF) << 8;
    at += 2;
    return (short) v;                    // signed: 0xFFFF reads as -1
}

private int get32(int p) {
    return (bytes[p] & 0xFF)
            | (bytes[p + 1] & 0xFF) << 8
            | (bytes[p + 2] & 0xFF) << 16
            | (bytes[p + 3] & 0xFF) << 24;
}

public int int32() {
    need(4);
    int v = get32(at);
    at += 4;
    return v;
}

public long int64() {
    need(8);
    long low = get32(at) & 0xFFFFFFFFL;
    long high = get32(at + 4) & 0xFFFFFFFFL;
    at += 8;
    return low | high << 32;
}

public float float32() { return Float.intBitsToFloat(int32()); }
public double float64() { return Double.longBitsToDouble(int64()); }
public boolean bool() { need(1); return bytes[at++] != 0; }
```

§2.3 again. `int8` and `int16` return **signed** values; a caller that stored an unsigned `u16` (the
container's version field, §14) masks with `& 0xFFFF`. `bool` treats any non-zero byte as true, which
is the forgiving choice for a value with only two meanings.

### 10.3 Strings, counts and slices

```java
public String string() {
    need(2);
    int n = (bytes[at] & 0xFF) | (bytes[at + 1] & 0xFF) << 8;   // the u16 length
    at += 2;
    need(n);
    String s = new String(bytes, at, n, StandardCharsets.UTF_8);
    at += n;
    return s;
}
```

Reading a string allocates (a `String` *is* an object), which is fine: loading is allowed to allocate,
capturing is not. The JDK's UTF-8 decoder handles §4.2 and replaces malformed sequences.

Bulk reads check the **whole** size before reading any element:

```java
private int count(int elementBytes) {
    int n = int32();
    if (n < 0) {
        throw new IllegalStateException("negative count " + n + " at byte " + (at - 4));
    }
    // In long arithmetic: a count of 0x20000001 longs is 2^32 + 8 bytes, which as an int
    // would wrap to 8, pass the check, and then allocate a 4 GiB array.
    long length = (long) n * elementBytes;
    if (length > end - at) {
        throw new Truncated(at, length);
    }
    return n;
}

/** Reads a count and that many {@code int}s into a new array of exactly that size. */
public int[] ints() {
    int n = count(4);
    int[] a = new int[n];
    for (int i = 0; i < n; i++) {
        a[i] = get32(at + 4 * i);
    }
    at += 4 * n;
    return a;
}
```

The comment in `count` is the second gap the review found. The count comes from the file. Multiplied
by 8 in `int` arithmetic, `0x20000001` (536,870,913) becomes $2^{32} + 8$, which wraps to 8; if 8 bytes
follow, the check passes and `new long[536_870_913]` asks for 4 GiB. Computing in `long` makes the
check honest. (`ByteStreamTest.hugeSizesFailWithoutOverflowing` writes exactly that file.)

The bulk reads of `int`s, `long`s, `float`s and booleans also have an **`Into`** variant
(`intsInto(int[] into)`, …) that reads into an array the caller already has and returns the count, so a system that reloads often can reuse its arrays.

And navigation:

```java
public void skip(int n) { need(n); at += n; }
public int position() { return at; }
public void seek(int position) { ... an absolute offset, at most end ... }

/** A reader over the next {@code length} bytes, which this reader then skips. */
public ByteReader slice(int length) {
    need(length);
    ByteReader r = new ByteReader(bytes, at, length);
    at += length;
    return r;
}

public boolean hasRemaining() { return at < end; }
public int remaining() { return end - at; }
```

`slice` is what gives every chunk its own reader: a system reading its chunk cannot run into the next
one, and reading too little or too much shows up as `hasRemaining()` or a `Truncated` *inside* that
chunk.

## 11. Step 3: designing the container

### 11.1 What a reader must be able to do

A `.jsave` file is read in two situations:

- **The slot list.** The pause menu lists every save with its title, level and play time. It should
  read a small prefix of each file, not decompress 50 saves.
- **Loading.** Everything, after making sure it is a save, it is whole, and it is a version this build
  can read.

That gives the layout: a fixed **header** with sizes and checksums, an uncompressed **meta block** of
string pairs, and a compressed **payload** of **chunks**, one per part of the game (D116).

### 11.2 The header, field by field

Fields are placed so each is naturally aligned (§5.1):

| Offset | Size | Field | Why it is there | Aligned? |
|---|---|---|---|---|
| 0 | 4 | magic `JSAV` | "is this a save at all?" | 0 mod 4 ✓ |
| 4 | 2 | format version | which layout follows | 4 mod 2 ✓ |
| 6 | 2 | flags (bit 0: zstd) | how the payload is stored | 6 mod 2 ✓ |
| 8 | 4 | chunk count | how many chunks to read | 8 mod 4 ✓ |
| 12 | 4 | meta block bytes | where the payload starts; what `peek` reads | 12 mod 4 ✓ |
| 16 | 8 | payload bytes, uncompressed | the size to decompress into | 16 mod 8 ✓ |
| 24 | 8 | payload bytes, as stored | where the file should end | 24 mod 8 ✓ |
| 32 | 4 | CRC-32C of the stored payload | payload damage | 32 mod 4 ✓ |
| 36 | 4 | CRC-32C of the header | header damage | 36 mod 4 ✓ |
| 40 | 8 | reserved (0) | room to grow | 40 mod 8 ✓ |
| 48 | | end | the meta block starts aligned | 48 mod 8 ✓ |

Choices in that table:

- **Two `u16`s for version and flags**, not two `u32`s: magic, version, flags, count and meta size
  then fill exactly 16 bytes, so the 8-byte sizes start aligned at 16. With two `u32`s those fields
  would take 20 bytes, the sizes would need 4 bytes of padding to start at 24, and the header would
  grow to 56. Sixteen bits are plenty for both a version and a set of flags.
- **`reserved`** makes the header a multiple of 8 *and* leaves room: a future field can go there
  without a new file version, because today's reader ignores it.
- **Sizes are 64-bit** although no save comes close to 4 GiB: the field costs 4 extra bytes and
  removes a limit forever. (The reader still caps them at 2 GiB, `MAX_PAYLOAD`: a bigger save would be
  a bug, and Java arrays cannot be bigger anyway.)
- **The header checksum covers the header**, with its own four bytes zeroed while computing (a number
  cannot include itself in its own checksum).

### 11.3 Tags: four letters as a number

Each chunk is labelled with a four-character **tag** — `GAME`, `CROW` (crowd), `FOG ` — stored as a
`u32`, and the magic is the tag `JSAV`. The four characters are the four bytes, in order, so a hex
dump shows the name:

```java
public static int tag(String name) {
    if (name.length() != 4) {
        throw new IllegalArgumentException("a tag has four characters: " + name);
    }
    int t = 0;
    for (int i = 0; i < 4; i++) {
        char c = name.charAt(i);
        if (c < 0x20 || c > 0x7E) {
            throw new IllegalArgumentException("a tag is printable ASCII: " + name);
        }
        t |= c << 8 * i;        // character i into byte i: little-endian
    }
    return t;
}
```

`tag("JSAV")` is `'J' | 'S' << 8 | 'A' << 16 | 'V' << 24` = `0x5641534A`, which written little-endian
is the bytes `4A 53 41 56` = "JSAV". That is why `MAGIC` is *computed* with `tag("JSAV")` rather than
typed: typing `0x4A534156` (the letters in reading order) would put `VASJ` in the file. `tagName` does
the reverse, showing `?` for non-printable bytes so an error message about a damaged tag stays
readable.

### 11.4 Chunks

The payload is a sequence of chunks, each:

```
[u32 tag][u32 version][u32 length][u32 reserved]   16-byte header
[length bytes of data]
[0–7 zero bytes]                                    padding to 8 (§5.2)
```

The **length** lets a reader skip a chunk it does not know (a newer game added a chunk; an older
reader can still read the rest). The **version** belongs to the chunk's owner (§8). The header is 16
bytes so the data starts 8-aligned.

## 12. Step 4: `SaveWriter` — chunks with patched lengths

`SaveWriter` is what the game writes into during a capture. It holds two `ByteWriter`s, one for the
meta block and one for the payload, and keeps them between saves:

```java
public final class SaveWriter {
    public static final int CHUNK_HEADER_BYTES = 16;

    private final ByteWriter meta;
    private final ByteWriter payload;
    private int metaCount;
    private int chunkCount;
    private int openLengthAt = -1;    // where the open chunk's length goes, or -1
    private int openDataStart;        // where the open chunk's data began

    public SaveWriter(int payloadCapacity) {
        meta = new ByteWriter(256);
        payload = new ByteWriter(payloadCapacity);
        meta.int32(0); // the pair count, patched by finish
    }

    public void reset() {
        meta.reset();
        meta.int32(0);
        payload.reset();
        metaCount = 0;
        chunkCount = 0;
        openLengthAt = -1;
    }
```

**Meta entries** are pairs of strings:

```java
public void meta(String key, CharSequence value) {
    meta.string(key);
    meta.string(value);
    metaCount++;
}
```

**Chunks** are opened, written through the returned `ByteWriter`, and closed automatically when the
next one opens:

```java
public ByteWriter chunk(int tag, int version) {
    closeOpenChunk();
    payload.int32(tag);
    payload.int32(version);
    openLengthAt = payload.size();
    payload.int32(0); // the length, patched at close
    payload.int32(0); // reserved
    openDataStart = payload.size();
    chunkCount++;
    return payload;
}

private void closeOpenChunk() {
    if (openLengthAt < 0) {
        return;
    }
    int length = payload.size() - openDataStart;
    payload.patchInt32(openLengthAt, length);
    int pad = (8 - (length & 7)) & 7;
    payload.zeros(pad);
    openLengthAt = -1;
}

public void finish() {
    closeOpenChunk();
    meta.patchInt32(0, metaCount);
}
```

- **Patching** (§9.5): the length is written as 0, its position remembered, and the real length
  patched in when the chunk closes. The writer of a chunk never has to know its size in advance — the
  crowd system just writes its agents.
- **Padding** after each chunk: §5.2's formula, then that many zero bytes.
- **Returning the payload writer itself**, not a separate one per chunk, means no buffers to merge and
  nothing to allocate: chunks are simply consecutive in one array.
- **`finish` is idempotent** (calling it twice is harmless: there is no open chunk the second time).

Using it, from the tests:

```java
w.reset();
w.meta("title", "Crypt, 2 rounds");
w.meta("level", "levels/crypt");
ByteWriter g = w.chunk(GAME, 3);
g.float64(12.5);
g.int32(7);
g.string("turn-based");
ByteWriter f = w.chunk(FOG, 1);
f.longs(bits);
w.finish();
```

## 13. Step 5: encoding the file

`SaveFile.encode` runs on the save job's background thread (chapter 16), so it may allocate. It
turns a finished capture into the bytes of a file:

```java
static byte[] encode(
        byte[] meta, int metaSize, byte[] payload, int payloadSize, int chunkCount, int level) {
    int metaPadded = metaSize + ((8 - (metaSize & 7)) & 7);
    byte[] stored;
    int storedSize;
    int flags = 0;
    if (level > 0 && payloadSize > 0) {
        stored = compress(payload, payloadSize, level);
        storedSize = stored.length;
        flags |= FLAG_ZSTD;
    } else {
        stored = payload;
        storedSize = payloadSize;
    }
    ByteWriter w = new ByteWriter(HEADER_BYTES + metaPadded + storedSize);
    w.int32(MAGIC);
    w.int16(FORMAT_VERSION);
    w.int16(flags);
    w.int32(chunkCount);
    w.int32(metaPadded);
    w.int64(payloadSize);
    w.int64(storedSize);
    w.int32(crc(stored, 0, storedSize));
    w.int32(0); // the header CRC, patched below
    w.int64(0);
    w.raw(meta, 0, metaSize);
    w.zeros(metaPadded - metaSize);
    w.raw(stored, 0, storedSize);
    byte[] file = w.toBytes();
    int headerCrc = crc(file, 0, HEADER_BYTES);
    file[36] = (byte) headerCrc;
    file[37] = (byte) (headerCrc >>> 8);
    file[38] = (byte) (headerCrc >>> 16);
    file[39] = (byte) (headerCrc >>> 24);
    return file;
}
```

In order: pad the meta block's size to 8; compress the payload (unless the level is 0, which tests use
to see the bytes); write the header of §11.2 field by field, with the header CRC as 0; append the meta
block, its padding and the stored payload; then compute the CRC of the first 48 bytes — with the CRC
field still 0 — and write it into bytes 36–39, little-endian. The payload CRC is over the **stored**
(compressed) bytes, for a reason §14 gives.

`crc` is three lines over the JDK:

```java
private static int crc(byte[] a, int offset, int n) {
    CRC32C crc = new CRC32C();
    crc.update(a, offset, n);
    return (int) crc.getValue();
}
```

(`getValue` returns a `long` holding the 32-bit result; the cast keeps those 32 bits.)

## 14. Step 6: reading it, in the right order

A reader is handed bytes. Before it trusts a single field it must answer, in this order: *is this
mine?* *is the header whole?* *can I read this version?* *do the sizes fit the file?* *is the payload
whole?* Each check guards the ones after it, so the order is part of the design. The rule behind it:
**fail at the check that explains the failure.**

```java
private static SaveFile parse(byte[] file, boolean payloadToo) throws SaveFormatException {
    if (file.length < HEADER_BYTES) {
        throw new SaveFormatException("file is " + file.length + " bytes; not a save");
    }
    ByteReader h = new ByteReader(file, 0, HEADER_BYTES);
    int magic = h.int32();
    if (magic != MAGIC) {                                                    // 1. mine?
        throw new SaveFormatException(
                "not a save file (magic " + tagName(magic) + ", expected JSAV)");
    }
    int version = h.int16() & 0xFFFF;
    int flags = h.int16() & 0xFFFF;
    int chunkCount = h.int32();
    int metaBytes = h.int32();
    long payloadBytes = h.int64();
    long storedBytes = h.int64();
    int payloadCrc = h.int32();
    int headerCrc = h.int32();
    byte[] zeroed = Arrays.copyOf(file, HEADER_BYTES);
    zeroed[36] = zeroed[37] = zeroed[38] = zeroed[39] = 0;
    if (crc(zeroed, 0, HEADER_BYTES) != headerCrc) {                         // 2. header whole?
        throw new SaveFormatException("header checksum does not match; the file is damaged");
    }
    if (version > FORMAT_VERSION) {                                          // 3. readable version?
        throw new SaveFormatException("save format " + version
                + " is newer than this build reads (" + FORMAT_VERSION + ")");
    }
    if (metaBytes < 4 || (metaBytes & 7) != 0 || HEADER_BYTES + metaBytes > file.length) {
        throw new SaveFormatException(                                       // 4. sizes fit?
                "meta block of " + metaBytes + " bytes at 48 overruns the file");
    }
    if (chunkCount < 0
            || payloadBytes < 0
            || storedBytes < 0
            || payloadBytes > MAX_PAYLOAD
            || storedBytes > MAX_PAYLOAD
            || (long) chunkCount * SaveWriter.CHUNK_HEADER_BYTES > payloadBytes) {
        // Every chunk has a 16-byte header, so a count the payload cannot hold is damage (and
        // would otherwise size the chunk list before anything checks it).
        throw new SaveFormatException("header sizes are out of range");
    }
    ... the meta block, read through its own ByteReader ...
    if (!payloadToo) {
        return new SaveFile(version, meta, new ArrayList<>());               // peek stops here
    }
    int payloadAt = HEADER_BYTES + metaBytes;
    if (payloadAt + storedBytes > file.length) {
        throw new SaveFormatException("payload of " + storedBytes + " bytes at " + payloadAt
                + " overruns the file (" + file.length + " bytes)");
    }
    if (crc(file, payloadAt, (int) storedBytes) != payloadCrc) {             // 5. payload whole?
        throw new SaveFormatException("payload checksum does not match; the file is damaged");
    }
    ... decompress (the zstd frame's own size must equal payloadBytes) ...
    ... walk the chunks ...
}
```

Why each check is where it is:

1. **The magic first.** A wrong magic is the one failure reported as "not a save" rather than
   "damaged": someone pointed the game at a PNG.
2. **The header checksum before any header value is used.** It separates a damaged save from a foreign
   file and protects every size the reader is about to rely on.
3. **The version after the checksum** — otherwise a damaged version byte would look like "a save from
   the future" and give the player the wrong advice — and **before the sizes**, because a newer header
   might lay its sizes out differently.
4. **Sizes against the file and against each other**, with `long` arithmetic where a sum could
   overflow. Every later read is inside a range this step proved exists, so the parser never throws an
   array index exception for a size a hostile file chose. The last condition — the chunk count must fit
   the payload at 16 bytes per chunk header — is the third gap this lesson's review closed: without it,
   a crafted count of two billion would size the chunk list (`new ArrayList<>(chunkCount)`) before any
   chunk was checked.
5. **The payload checksum, then decompression.** The CRC is over the *stored* bytes so that damage is
   caught *before* zstd sees them. A decompressor given corrupted input may fail, or — for frames
   without zstd's own content checksum — produce garbage of exactly the expected size, which would
   then be parsed as game state. (The engine's frames do carry that checksum, so zstd would usually
   object too; the CRC makes the container's guarantee independent of how the payload was
   compressed, and checks fewer bytes.)

The chunk walk checks each chunk against what remains:

```java
ByteReader p = new ByteReader(payload, payloadOffset, (int) payloadBytes);
for (int i = 0; i < chunkCount; i++) {
    if (p.remaining() < SaveWriter.CHUNK_HEADER_BYTES) {
        throw new SaveFormatException(
                "chunk " + i + " of " + chunkCount + " has no header (payload is short)");
    }
    int tag = p.int32();
    int chunkVersion = p.int32();
    int length = p.int32();
    p.int32(); // reserved
    if (length < 0 || length > p.remaining()) {
        throw new SaveFormatException("chunk " + tagName(tag) + " claims " + length
                + " bytes, " + p.remaining() + " remain");
    }
    chunks.add(new Chunk(tag, chunkVersion, p.slice(length)));
    int pad = (8 - (length & 7)) & 7;
    if (pad > p.remaining()) {
        throw new SaveFormatException(
                "chunk " + tagName(tag) + " is not padded to 8 bytes (payload is short)");
    }
    p.skip(pad);
}
```

Each chunk becomes a `Chunk(tag, version, reader)` whose reader is a `slice` (§10.3): the owning
system reads its own bytes and cannot run past them. The padding check is the last addition: before
it, a payload that ended right after a chunk's data threw `Truncated` from `skip` — an unchecked
exception escaping a method that promises `SaveFormatException`.

**`peek`**, for the slot list, reads only the header (48 bytes) and then exactly `metaBytes` more, and
runs the same parse with `payloadToo = false`: one small read per file, nothing decompressed, and the
header still verified by its checksum. That is the reason for having *two* checksums instead of one
over the whole file.

## 15. Step 7: writing to disk without losing the old save

The job that writes a save (chapter 16, `SaveJob`) must never leave the player with *no* save: if the
game crashes or the power fails halfway through writing `quick.jsave`, the old quick save must still be
there. The technique is **write to a temporary file, then rename it over the target**:

```java
Path temp = target.resolveSibling(target.getFileName() + ".tmp");
Files.write(temp, file);
try {
    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
} catch (AtomicMoveNotSupportedException e) {
    // A file system without atomic replace (rare: some network shares): the plain move
    // still replaces, with a window where the target is missing.
    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
}
```

An **atomic** rename either happens completely or not at all, as seen by anyone reading the
directory: at every moment, `quick.jsave` is either the complete old file or the complete new one. A
crash before the rename leaves the old save and a stray `.tmp`; a crash after leaves the new save.

One step is deliberately not taken: forcing the temporary file's bytes to the disk before the rename
(`FileChannel.force`). Without it, after a *power cut* (not a crash) some file systems can end up with
the rename recorded but the new file's contents not yet written. Exercise 6 discusses that trade-off;
the CRCs of §6 at least make sure such a file is detected rather than loaded.

## 16. Step 8: versions in practice, and golden files

### 16.1 A record format

Each system writes its own format number first. The crowd:

```java
public static final int SAVE_FORMAT = 1;

public void write(ByteWriter w) {
    w.int32(SAVE_FORMAT);
    ...
}

public void read(ByteReader r) {
    int format = r.int32();
    if (format != 1) {
        throw new IllegalStateException("crowd save format " + format + "; this build reads 1");
    }
    ...
}
```

With one version this is a degenerate `switch`. The day format 2 exists, `read` becomes `switch
(format) { case 1 -> readV1(r); case 2 -> readV2(r); default -> throw ... }`, and `readV1` keeps the
old code — forever, because saves made by the old game are still on players' disks.

### 16.2 The golden file

What protects that promise is not the `switch` but a test that **reads bytes an old version actually
wrote**. A **golden file** (or fixture) is such bytes, written once by the build that introduced the
format, checked into the test resources, and never regenerated:

```java
public static byte[] bytesOrCreate(Class<?> near, String name, byte[] fresh) {
    byte[] existing = bytes(near, name);
    if (existing != null) {
        return existing;                        // the normal case: the committed file
    }
    String resource = name.startsWith("/")
            ? name.substring(1)
            : near.getPackageName().replace('.', '/') + "/" + name;
    Path file = Path.of("src", "test", "resources").resolve(resource);
    try {
        Files.createDirectories(file.getParent());
        Files.write(file, fresh);
    } catch (IOException e) {
        throw new UncheckedIOException(e);
    }
    throw new AssertionError("fixture " + file + " did not exist; written from the current build ("
            + fresh.length + " bytes): review it, commit it and run again");
}
```

The first time a test asks for a fixture that does not exist, `Fixtures.bytesOrCreate` writes it from
the current build **and fails the test**, so a person looks at the new file and commits it. From then
on the test reads the committed bytes. A later build that broke format 1 — even by accident, by
reordering two writes — fails `formatOneFixtureStillReads`, which reads the 172-byte `v1.jsave` and
checks its contents value by value. A fixture is never silently replaced by whatever the current
build happens to write; that would test nothing.

(The component stores are the exception that needs no version number: their saved header *describes*
the columns by name and type, and the reader matches columns by name rather than assuming an order —
D111.)

---

# Part C — Reading a real file and proving it

## 17. A real file, byte by byte

The golden file `modules/save/src/test/resources/dev/jeddy/save/fixtures/v1.jsave` is 172 bytes. Its
dump (`od -A d -t x1`, offsets in decimal on the left):

```
0000000 4a 53 41 56 01 00 01 00 02 00 00 00 38 00 00 00
0000016 60 00 00 00 00 00 00 00 44 00 00 00 00 00 00 00
0000032 63 47 8f 2c 3f 3e c3 e1 00 00 00 00 00 00 00 00
0000048 02 00 00 00 05 00 74 69 74 6c 65 0f 00 43 72 79
0000064 70 74 2c 20 32 20 72 6f 75 6e 64 73 05 00 6c 65
0000080 76 65 6c 0c 00 6c 65 76 65 6c 73 2f 63 72 79 70
0000096 74 00 00 00 00 00 00 00 28 b5 2f fd 24 60 bd 01
0000112 00 c4 02 47 41 4d 45 03 00 00 00 18 00 29 40 07
0000128 00 00 00 0a 00 74 75 72 6e 2d 62 61 73 65 64 46
0000144 4f 47 20 01 00 00 00 24 00 04 00 00 00 ff 00 04
0000160 10 00 41 38 0d e6 38 24 74 c4 9f cc
```

### 17.1 The header (bytes 0–47)

| Bytes | Hex | Value | Meaning |
|---|---|---|---|
| 0–3 | `4a 53 41 56` | "JSAV" | the magic: `tag("JSAV")` = `0x5641534A`, little-endian |
| 4–5 | `01 00` | 1 | format version |
| 6–7 | `01 00` | 1 | flags: bit 0 set, the payload is zstd |
| 8–11 | `02 00 00 00` | 2 | two chunks |
| 12–15 | `38 00 00 00` | 56 | meta block bytes (padded): the payload starts at 48 + 56 = 104 |
| 16–23 | `60 00 …` | 96 | payload bytes uncompressed |
| 24–31 | `44 00 …` | 68 | payload bytes as stored: 104 + 68 = 172, the file's length ✓ |
| 32–35 | `63 47 8f 2c` | `0x2C8F4763` | CRC-32C of the 68 stored bytes |
| 36–39 | `3f 3e c3 e1` | `0xE1C33E3F` | CRC-32C of bytes 0–47 with these four zero |
| 40–47 | `00 × 8` | 0 | reserved |

Both CRCs were recomputed independently while writing this lesson (with the bit-by-bit function of
§6.3): they match.

### 17.2 The meta block (bytes 48–103)

| Bytes | Hex | Value |
|---|---|---|
| 48–51 | `02 00 00 00` | 2 pairs |
| 52–53 | `05 00` | a string of 5 bytes: |
| 54–58 | `74 69 74 6c 65` | `title` |
| 59–60 | `0f 00` | a string of 15 bytes: |
| 61–75 | `43 72 79 70 74 2c 20 32 20 72 6f 75 6e 64 73` | `Crypt, 2 rounds` |
| 76–77, 78–82 | `05 00`, `6c 65 76 65 6c` | `level` |
| 83–84, 85–96 | `0c 00`, `6c 65 76 65 6c 73 2f 63 72 79 70 74` | `levels/crypt` (12 bytes) |
| 97–103 | `00 × 7` | padding: 4 + (2 + 5 + 2 + 15) + (2 + 5 + 2 + 12) = 49 bytes, padded to 56 |

Everything a slot list needs is readable from the first 104 bytes, and the file says how many those
are (offset 12).

### 17.3 The payload (bytes 104–171)

`28 b5 2f fd` is zstd's magic (`0xFD2FB528`, little-endian too); the 68 bytes are one compressed
frame. You can recognize pieces of the original inside it (`47 41 4d 45` is "GAME", `74 75 72 6e` is
"turn"): zstd stores short, unrepeated data as it is. Decompressed, the frame is these 96 bytes:

```
0000  47 41 4d 45 03 00 00 00 18 00 00 00 00 00 00 00     GAME, version 3, length 24, reserved
0016  00 00 00 00 00 00 29 40 07 00 00 00 0a 00 74 75     12.5 (float64), 7 (int32), string of 10:
0032  72 6e 2d 62 61 73 65 64 46 4f 47 20 01 00 00 00     "turn-based" | FOG , version 1
0048  24 00 00 00 00 00 00 00 04 00 00 00 ff ff ff ff     length 36, reserved | count 4, long 0 = -1
0064  ff ff ff ff 00 00 00 00 00 00 00 00 00 00 00 00     ... long 1 = 0, long 2 = 0
0080  00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00     long 3 = 0 | 4 bytes of padding
```

- **`GAME`**: 8 + 4 + (2 + 10) = 24 bytes of data, already a multiple of 8, no padding.
- **`FOG `** (the space is part of the tag): a count (4) and four `long`s = 36 bytes, padded to 40.
- Two chunk headers of 16, plus 24, plus 40: **96**, the number at offset 16. ✓

## 18. The tests

[`ByteStreamTest`](../../../modules/core/src/test/java/dev/jeddy/core/io/ByteStreamTest.java) and
[`SaveFileTest`](../../../modules/save/src/test/java/dev/jeddy/save/SaveFileTest.java) are the
specification of Part B. What each checks:

- **`scalarsRoundTripLittleEndian`**: every scalar type and a string with 1-, 2-, 3- and 4-byte UTF-8
  sequences (`"héllo €😀"`) round-trip, and `0x11223344` is stored with `0x44` first.
- **`truncatedStreamsFailAtTheRead`**: a string cut short throws `Truncated` naming byte 6; a count of
  a million with nothing after it fails before reading any element; a negative count fails.
- **`hugeSizesFailWithoutOverflowing`**: the overflowing count of §10.3 and a `skip(Integer.MAX_VALUE)`
  from a non-zero position (§10.1) both throw cleanly.
- **`aResetWriterAllocatesNothing`** and **`aSecondCaptureAllocatesNothing`**: once the buffers are big
  enough, a whole capture allocates zero bytes (L00 §8.2).
- **`chunksArePaddedToEightBytes`**: a 1-byte chunk is followed by 7 zeros and the next header at
  offset 24.
- **`damageIsReportedByWhatItBroke`** breaks a good file in one place at a time and checks the message
  names the right problem:

  ```java
  byte[] notOne = file.clone();
  notOne[0] = 'X';
  assertMessage(notOne, "not a save file");
  byte[] header = file.clone();
  header[20] ^= 1;                              // flip one bit of the payload size
  assertMessage(header, "header checksum");
  byte[] newer = file.clone();
  newer[4] = 9;                                 // version 9...
  resignHeader(newer);                          // ...with a valid header checksum
  assertMessage(newer, "newer than this build");
  byte[] payload = file.clone();
  payload[file.length - 1] ^= 0x40;
  assertMessage(payload, "payload checksum");
  byte[] cut = Arrays.copyOf(file, SaveFile.HEADER_BYTES + 56);
  assertMessage(cut, "overruns the file");
  ```

  `resignHeader` recomputes the header CRC after the edit. That is how the test reaches the checks
  *behind* the checksum — and a demonstration of §6.3's warning: anyone can make a modified file pass
  a CRC.
- **`hostileSizesAreReportedNotThrownThrough`**: a re-signed header claiming 2 billion chunks, and an
  uncompressed payload cut right after a chunk's data, both end in `SaveFormatException` (§14).
- **`formatOneFixtureStillReads`**: the golden file of §17, read and checked value by value.

## 19. Things to run

- `./gradlew :core:test --tests 'dev.jeddy.core.io.ByteStreamTest'` and `./gradlew :save:test`.
- `od -A d -t x1 modules/save/src/test/resources/dev/jeddy/save/fixtures/v1.jsave` (or
  `Format-Hex` in PowerShell): the dump of §17.
- With the `zstd` command-line tool: `tail -c +105 v1.jsave | zstd -d | od -A d -t x1` shows the 96
  bytes of §17.3 (`tail -c +105` starts at the 105th byte, which is offset 104 counting from 0).
- Change `ByteWriter.put32` to write the most significant byte first and run `:core:test`:
  `scalarsRoundTripLittleEndian` fails on `assertEquals((byte) 0x44, b[3])`.
- In `SaveFile.parse`, move the version check before the header CRC check and run `:save:test`: the
  flipped-bit file still fails, but ask yourself which message a flipped bit *in the version field*
  would now give.
- Put `need`'s old comparison (`at + n > end`) back and run `hugeSizesFailWithoutOverflowing`.

## 20. Exercises

**1. Bytes by hand.** Write the little-endian bytes of `float32 1.5f`, `int16 -2`, `int64 -1` and the
string `"é"`, then check with a `ByteWriter` in `jshell`.

<details><summary>Solution</summary>

`1.5f` = `0x3FC00000` → `00 00 c0 3f`. −2 as 16 bits is `0xFFFE` → `fe ff`. −1 as 64 bits is all ones →
`ff ff ff ff ff ff ff ff`. `"é"` → length 2 as `u16` (`02 00`), then `c3 a9`.
</details>

**2. Drop `reserved`.** Without the 8 reserved bytes the header would be 40 bytes. Is the meta block
still aligned? What would you lose?

<details><summary>Solution</summary>

40 is a multiple of 8, so alignment is unaffected. What is lost is room to grow: the first new header
field would change the layout and force file format 2, and every reader would carry the version-1
header parsing from then on. Eight bytes per save buy one future field without a version change.
</details>

**3. Padding.** Compute the padded sizes of chunks with 0, 7, 8, 9 and 16 bytes of data, and write a
test like `chunksArePaddedToEightBytes` for a chunk with no data.

<details><summary>Solution</summary>

0 → 0, 7 → 8, 8 → 8, 9 → 16, 16 → 16. A chunk opened and closed with nothing written has length 0 and
no padding, so the next chunk's header starts 16 bytes after the first's:

```java
SaveWriter w = new SaveWriter();
w.chunk(GAME, 1);                 // nothing written
w.chunk(FOG, 1).int32(2);
w.finish();
ByteReader r = new ByteReader(Arrays.copyOf(w.payloadBuffer(), w.payloadSize()));
r.skip(8);
assertEquals(0, r.int32());       // the patched length
r.skip(4);
assertEquals(FOG, r.int32());     // at offset 16
```
</details>

**4. Which bytes the payload CRC covers.** If the payload CRC were computed over the *uncompressed*
bytes, which check of §14 would move, and what could happen before it?

<details><summary>Solution</summary>

Step 5 would have to come after decompression. A damaged frame would reach zstd first; a decompressor
given corrupted input may fail, or (for a frame without zstd's own content checksum) may produce output
of exactly the declared size with wrong contents, and only then would the CRC catch it. Checking the stored bytes rejects damage before any decoder runs
on it, and the CRC runs over fewer bytes (68 instead of 96 here; 18 KB instead of 110 KB for the
demo's mid-fight save).
</details>

**5. Format 2 of the crowd record.** Design `Crowd`'s format 2, which adds an `int team` per agent,
keeping format 1 readable. List the files you change, the file you add, and the file you must not
touch.

<details><summary>Solution</summary>

Change `Crowd.java`: `SAVE_FORMAT = 2`; `write` adds the team after each agent's existing fields;
`read` becomes `switch (format) { case 1 -> readV1(r); case 2 -> readV2(r); default -> throw ... }`,
with `readV1` the current code plus a default team. Add a test that writes format 2 and a new fixture
(`crowd-v2.bin`) through `Fixtures.bytesOrCreate`, reviewed and committed. Keep the test that reads
`crowd-v1.bin`, and never regenerate or edit that file: it is the proof that old saves still load.
</details>

**6. Power cuts.** §15's write survives a crash but not necessarily a power cut. Add
`FileChannel.force(true)` on the temporary file before the move: where, at what cost, and would you?

<details><summary>Solution</summary>

Write the bytes through a `FileChannel` opened on `temp`, call `force(true)` before closing it, then
move. `force` waits until the storage device reports the data written: typically milliseconds on an
SSD, more on a spinning disk or a network share. The save job runs on its own background thread
(D118), so the game does not wait for it; the cost is a longer time until the save is "done". Most
games accept it for manual saves. It is a reasonable change to propose to the owner, with the
measurement.
</details>

**7. Text in the meta block.** The meta block stores `savedAt` and `playSeconds` as decimal text, not as
binary numbers. Why, and how does the capture write them without allocating?

<details><summary>Solution</summary>

The meta block is, by design, a list of string pairs: the slot list can show any entry, and a new entry
(such as `kills`) needs no format change, because a reader never needs to know an entry's type.
Binary values would need a type per key. Text costs nothing at capture time because the game formats
numbers into a kept `StringBuilder` (`append(long)` allocates nothing) and passes it as a
`CharSequence` (§9.4); chapter [16](../16-saves.md) §19.1, on the pitfalls met on the way, tells how
the first version, with `Long.toString`, cost 1,120 bytes per autosave.
</details>

**8. Attack the reader.** Write three hostile files — a string length larger than the file, a chunk
`length` of −1, and a meta pair count of a billion — and predict, then check, which message each gets.

<details><summary>Hint</summary>Build a good file with `SaveFile.encode(w, 0)`, edit bytes, and re-sign
with the test's `resignPayload` (which also re-signs the header).</details>
<details><summary>Solution</summary>

A string length past the end of the meta block: `ByteReader.string` throws `Truncated`, which `parse`
wraps as "meta block is damaged". A chunk length of −1: "claims −1 bytes". A billion meta pairs: the
loop reads pairs until the meta block's reader runs out and throws `Truncated` → "meta block is
damaged" — after reading only as many pairs as fit, because the reader is a slice of exactly
`metaBytes`. Notice that the two meta-block edits needed no re-signing at all: exercise 9.
</details>

**9. The bytes no checksum covers.** List which bytes of a `.jsave` are covered by the header CRC,
which by the payload CRC, and which by neither. What can damage in the uncovered part do today? Then
design a fix that every existing reader keeps accepting.

<details><summary>Hint</summary>Look at what the demo does with the `level` entry when it loads
(`CryptSave.apply`), and at the two header fields §11.2 kept for growth.</details>
<details><summary>Solution</summary>

The header CRC covers bytes 0–47, the payload CRC the stored payload; the **meta block** (48 to 48 +
`metaBytes`) is covered by neither. Damage there can show a wrong title or play time in the slot list,
make the meta block unreadable ("meta block is damaged", refusing the load), or change the `level`
entry so that `CryptSave.apply` refuses the save as belonging to another level. It cannot silently
change the loaded game state, which lives in the payload. A fix that old readers still accept: store a
CRC-32C of the meta block in the first four bytes of the `reserved` word and set a new flag bit (bit
1, "meta CRC present"). A version-1 reader ignores both (it checks neither `reserved` nor unknown
flags), so no format version bump is needed; a new reader checks the meta CRC when the bit is set.
That is exactly what the reserved word and the flag bits were left for. (It is a format decision for
the owner; the lesson only records the gap.)
</details>

## 21. Glossary

- **Atomic rename:** a move that is seen as complete or not at all (§15).
- **Big-endian, little-endian:** the most or least significant byte first (§2.2).
- **Check value:** the published CRC of `"123456789"`, used to test implementations (§6.3).
- **Checksum, CRC, generator:** a number computed from data to detect changes; the
  division-remainder kind; its divisor (§6).
- **Chunk, tag:** a versioned, length-prefixed block of the payload; its four-letter name (§11.3–11.4).
- **Code point, UTF-16, UTF-8, surrogate pair:** a character's Unicode number; Java's in-memory
  encoding; the 1-to-4-byte file encoding; two UTF-16 units for one code point above U+FFFF (§4).
- **Fixture (golden file):** bytes an old version wrote, kept under test (§16.2).
- **Format, layout, offset:** the agreement between writer and reader; where each field is; a byte's
  position in the file (§1).
- **Frame (zstd):** one compressed unit, starting `28 b5 2f fd` (§7).
- **Length prefix:** a size written before the data it describes (§4.3).
- **Magic number:** bytes at the start of a file that identify its format (§11.2).
- **Meta block:** the uncompressed string pairs a slot list reads (§11.1).
- **Natural alignment, padding:** an offset that is a multiple of the field's size; zero bytes added
  to reach one (§5).
- **Patching:** writing a placeholder and filling it in later (§9.5).
- **Raw bits:** a float's exact 32-bit pattern (§3).
- **Version (file, chunk, record):** the three levels at which the save's formats change (§8).

*Code this lesson builds or quotes: `core.io.ByteWriter`, `core.io.ByteReader`, `save.SaveWriter`,
`save.SaveFile`, `save.SaveJob`, `physics.crowd.Crowd`, `testing.Fixtures`, `ByteStreamTest`,
`SaveFileTest`. Decisions: D111, D113, D116, D117, D118 in [DESIGN §17](../../DESIGN.md); the format
in [formats/jsave.md](../../formats/jsave.md).*
