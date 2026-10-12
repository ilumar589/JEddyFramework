# Lesson 0 — Foundations: numbers, bits, memory, the CPU and the GPU

*The lesson every other lesson stands on. It assumes you can read and write Java and nothing
else: no math beyond arithmetic, no knowledge of how a computer stores a number, no graphics.
Everything the later lessons use is defined here, with examples you can type into `jshell` and
check. Read it once from top to bottom, then come back to a section when a later lesson points
to it (they say "L00 §5" and so on).*

**What you will be able to do after this lesson**

- read the math notation the lessons use (sums, powers, logarithms, `mod`, intervals);
- convert between decimal, binary and hexadecimal in your head for small numbers;
- predict what Java's bit operators (`& | ^ ~ << >> >>>`) do to a number, including negative
  ones, and pack several small numbers into one `int`;
- say how a `float` is stored and why `0.1f` is not exactly 0.1;
- explain what an address, a cache line and a cache miss are, and why the order in which a
  program touches memory matters more than the number of instructions it runs;
- explain what a GPU is, what a driver is, why OpenGL calls return before anything is drawn,
  and what a frame is;
- read the glossary at the end without surprises.

**Contents**

1. [How to use these lessons](#1-how-to-use-these-lessons)
2. [The math notation, from zero](#2-the-math-notation-from-zero)
3. [Numbers in other bases: binary and hexadecimal](#3-numbers-in-other-bases-binary-and-hexadecimal)
4. [Bits, bytes and Java's number types](#4-bits-bytes-and-javas-number-types)
5. [Negative numbers: two's complement](#5-negative-numbers-twos-complement)
6. [Bit operations and packing](#6-bit-operations-and-packing)
7. [Fractions in binary: how a float is stored](#7-fractions-in-binary-how-a-float-is-stored)
8. [Memory: addresses, the Java heap and native memory](#8-memory-addresses-the-java-heap-and-native-memory)
9. [The CPU: instructions, caches and threads](#9-the-cpu-instructions-caches-and-threads)
10. [The GPU, the driver and OpenGL](#10-the-gpu-the-driver-and-opengl)
11. [Frames and time budgets](#11-frames-and-time-budgets)
12. [Calling C from Java: the Foreign Function & Memory API](#12-calling-c-from-java-the-foreign-function--memory-api)
13. [Check yourself](#13-check-yourself)
14. [Glossary](#14-glossary)

---

## 1. How to use these lessons

Each lesson after this one builds a real part of the engine **from an empty file**. It explains
the idea, derives any math it needs, then writes the code the repository has, function by
function, and says why each line is the way it is and which alternatives were considered and
rejected. You can follow along in two ways:

- **Read alongside the real code.** Every code block names its file. Open it in your editor
  next to the lesson.
- **Rebuild it.** Make a scratch package (for example `src/test/java/scratch/` in the module the
  lesson is about), type the code as the lesson grows it, and run the small tests the lesson
  gives. Typing it yourself is slower and teaches much more.

### 1.1 The tools you need

- **`jshell`**, which comes with the JDK. It runs one Java line at a time and prints the result,
  which is perfect for checking a number:

  ```
  $ jshell
  jshell> Integer.toBinaryString(300)
  $1 ==> "100101100"
  jshell> Integer.toHexString(-1)
  $2 ==> "ffffffff"
  jshell> /exit
  ```

  Every "Try it" box in these lessons is meant for `jshell`.
- **Gradle**, through the wrapper in the repository: `./gradlew :math:test` runs the tests of the
  `math` module (on Windows, `gradlew.bat :math:test`). A single test class:
  `./gradlew :math:test --tests 'dev.jeddy.math.GeometryAndUtilTest'`.
- **A hex dump tool** for lesson 4: `od -A d -t x1 file` on Linux and macOS, `Format-Hex file`
  in PowerShell.

### 1.2 How the lessons are written

- A term in **bold** is being defined. If you meet a word you do not know, look in the glossary
  (§14) or in the lesson's own glossary.
- Numbers in examples are real: they were computed or measured, and the lesson says how.
- "Why not …?" paragraphs give the rejected alternatives. They matter as much as the chosen
  design: a decision is only understood when you know what it was chosen over.
- Decisions have numbers like **D103**. They are listed, with their reasons, in
  [DESIGN.md §17](../../DESIGN.md).
- Each section ends with a few questions. The answers are folded under "Answer"; try first.

---

## 2. The math notation, from zero

The lessons use a small amount of notation. Each piece is a shorthand for something you already
know how to do.

### 2.1 Variables and functions

A **variable** is a letter that stands for a number: in "a die has $S$ faces", $S$ might be 6 or
20. A **function** is a rule that turns inputs into an output, written like a Java method
without the types: $f(x) = 2x + 1$ means "to get $f$ of something, double it and add one", so
$f(3) = 7$. Writing two things next to each other means multiplying them: $2x$ is $2 \cdot x$,
and $S(S+1)$ is $S \cdot (S + 1)$.

### 2.2 Sums: the $\Sigma$ sign

$\sum$ (the Greek capital sigma, "sum") is a `for` loop that adds:

$$\sum_{k=1}^{6} k \;=\; 1 + 2 + 3 + 4 + 5 + 6 \;=\; 21$$

reads "the sum, for $k$ from 1 to 6, of $k$". In Java:

```java
int sum = 0;
for (int k = 1; k <= 6; k++) {
    sum += k;
}
```

The expression after the sign can be anything: $\sum_{k=1}^{3} k^2 = 1 + 4 + 9 = 14$.

Two facts about sums are used again and again:

- **A constant factor comes out.** $\sum 3k = 3 \sum k$, because $3 \cdot 1 + 3 \cdot 2 + \dots$
  is $3 \cdot (1 + 2 + \dots)$.
- **The sum of the first $n$ whole numbers** is $\frac{n(n+1)}{2}$. Why: write the sum forwards
  and backwards and add the two lines column by column:

  ```
    1 +   2 +   3 + ... +   n
    n + n-1 + n-2 + ... +   1
  -----------------------------
  (n+1)+(n+1)+(n+1)+...+(n+1)     n columns, each n + 1
  ```

  Two copies of the sum make $n(n+1)$, so one copy is $n(n+1)/2$. For $n = 6$: $6 \cdot 7 / 2 = 21$.
  This is the first derivation in lesson 2.

### 2.3 Powers, roots and logarithms

$2^{10}$ ("2 to the power 10") is 2 multiplied by itself 10 times: 1024. Rules you will need:
$2^a \cdot 2^b = 2^{a+b}$ (multiplying adds the exponents: $2^3 \cdot 2^4 = 8 \cdot 16 = 128 =
2^7$), $2^0 = 1$, and $2^{-n} = 1/2^n$, so $2^{-1} = 0.5$ and $2^{-24}$ is about 0.00000006.

Powers of two are everywhere in computing. Learn the first few by heart:

| $n$ | $2^n$ | | $n$ | $2^n$ |
|---|---|---|---|---|
| 0 | 1 | | 10 | 1,024 ("1 Ki") |
| 1 | 2 | | 16 | 65,536 |
| 2 | 4 | | 20 | 1,048,576 ("1 Mi") |
| 3 | 8 | | 24 | 16,777,216 |
| 4 | 16 | | 30 | 1,073,741,824 ("1 Gi") |
| 5 | 32 | | 31 | 2,147,483,648 |
| 6 | 64 | | 32 | 4,294,967,296 (about 4.3 billion) |
| 7 | 128 | | 63 | about $9.22 \times 10^{18}$ |
| 8 | 256 | | 64 | about $1.84 \times 10^{19}$ |

$\sqrt{x}$ (the **square root**) is the number that gives $x$ when multiplied by itself:
$\sqrt{25} = 5$. The **logarithm base 2**, $\log_2 x$, asks the opposite question of a power:
"2 to what power gives $x$?" So $\log_2 1024 = 10$, and $\log_2 20 \approx 4.32$, which tells you a
d20's result needs 5 bits (§4).

**Scientific notation** writes big and small numbers compactly: $3.7 \times 10^{-9}$ is
0.0000000037 (move the point 9 places left), $2 \times 10^8$ is 200,000,000.

### 2.4 Division with remainder: `mod`

Dividing whole numbers gives a **quotient** and a **remainder**: 17 divided by 5 is 3 remainder
2, because $17 = 3 \cdot 5 + 2$. The remainder is written $17 \bmod 5 = 2$ ("17 modulo 5"). In
Java, `17 / 5` is `3` (integer division throws the fraction away) and `17 % 5` is `2`.

Two warnings for Java:

- For a **negative** left side, Java's `%` keeps the sign: `-7 % 3` is `-1`, not 2. The math
  `mod` is always between 0 and the divisor; Java's `Math.floorMod(-7, 3)` gives that, `2`.
- Integer division rounds **towards zero**: `-7 / 2` is `-3`, not −4.

`mod` describes anything that wraps around. A clock is "mod 12". The frame ring of lesson 1 uses
"frame number mod 3" to cycle through regions 0, 1, 2, 0, 1, 2, …:

```java
frame = (frame + 1) % frames;   // 0 → 1 → 2 → 0 → ...
```

### 2.5 Rounding: floor, ceiling and "round up to a multiple"

$\lfloor x \rfloor$ (**floor**) is the largest whole number not above $x$: $\lfloor 2.7 \rfloor = 2$,
$\lfloor -2.7 \rfloor = -3$. $\lceil x \rceil$ (**ceiling**) is the smallest whole number not below
$x$: $\lceil 2.1 \rceil = 3$.

A pattern you will meet in every lesson: **round $x$ up to the next multiple of $a$** (to put data
at an address the hardware likes, §8.4). The formula is

$$\text{roundUp}(x, a) = \left\lfloor \frac{x + a - 1}{a} \right\rfloor \cdot a$$

Why it works: if $x$ is already a multiple of $a$, adding $a - 1$ does not reach the next
multiple, and the division brings you back to $x$. If $x$ is past a multiple by even 1, adding
$a - 1$ pushes it past the next multiple, and the division (which throws away the fraction) lands
exactly there. Check with $a = 256$:

| $x$ | $x + 255$ | $\lfloor \cdot / 256 \rfloor$ | $\times 256$ |
|---|---|---|---|
| 0 | 255 | 0 | 0 |
| 1 | 256 | 1 | 256 |
| 256 | 511 | 1 | 256 |
| 257 | 512 | 2 | 512 |
| 300 | 555 | 2 | 512 |

In Java with whole numbers the floor is just integer division: `(x + a - 1) / a * a`. Lesson 1
uses exactly this line to size its ring, and a faster bit trick for the same thing (§6.6).

### 2.6 Intervals and inequalities

$a \le b$ means "$a$ is less than or equal to $b$". An **interval** is a range of numbers:
$[0, 20)$ means "from 0 to 20, including 0, excluding 20", so for whole numbers 0, 1, …, 19. A
square bracket includes the end, a round one excludes it. Java's `for (int i = 0; i < 20; i++)`
walks $[0, 20)$. The lessons write ranges of array indices the same way: `a[0, n)` is the first
`n` elements.

### 2.7 Fractions, percentages and probabilities

A probability is a number between 0 (never) and 1 (always). $\frac{1}{20} = 0.05 = 5\,\%$: a
percentage is a fraction out of 100. "Percentage points" are differences between percentages:
going from 50 % to 55 % is 5 percentage points (but 10 percent *more*). Lesson 2 builds all of
probability from counting; you do not need more here.

### 2.8 Big-O: how cost grows

"$O(1)$" and "$O(n)$" describe how the work of an operation grows with the size $n$ of the data,
ignoring constant factors:

- **$O(1)$ (constant):** the same work whatever $n$ is. Reading `array[i]` is $O(1)$.
- **$O(n)$ (linear):** work proportional to $n$. Searching an unsorted array for a value is
  $O(n)$: in the worst case you look at every element.
- **$O(\log n)$:** work grows with the number of *digits* of $n$. Binary search is $O(\log n)$:
  each step halves what is left, so a million elements need about 20 steps ($2^{20} \approx 10^6$).

Big-O says nothing about the constant: an $O(1)$ operation that misses the cache (§9.3) can be
slower than an $O(n)$ loop over 50 elements that are already in it. The lessons always give the
constant too.

<details><summary>Check: what are $\sum_{k=1}^{4} 2k$, $2^5 \cdot 2^3$, $23 \bmod 8$, <code>-23 % 8</code> in Java, and roundUp(1000, 256)?</summary>

$2 + 4 + 6 + 8 = 20$ (or $2 \cdot \frac{4 \cdot 5}{2} = 20$); $2^8 = 256$; $23 = 2 \cdot 8 + 7$
so 7; Java gives `-7` (sign of the left side); $(1000 + 255) / 256 = 4$ (integer), $4 \cdot 256 = 1024$.
</details>

---

## 3. Numbers in other bases: binary and hexadecimal

### 3.1 What "base 10" means

When you write 300, each digit is worth ten times the digit to its right:

$$300 = 3 \cdot 10^2 + 0 \cdot 10^1 + 0 \cdot 10^0$$

That is **base 10** (decimal): ten digit symbols, 0 to 9, and each position is a power of 10.
Nothing about the number 300 needs base 10; it is just the base we count in.

### 3.2 Binary: base 2

A computer stores numbers with switches that are off or on: two symbols, 0 and 1, so it counts
in **base 2** (binary). Each position is a power of 2:

```
position:   8    7    6    5    4    3    2    1    0
worth:    256  128   64   32   16    8    4    2    1
300   =     1    0    0    1    0    1    1    0    0     = 256 + 32 + 8 + 4
```

So 300 in binary is `100101100`. Java writes a binary literal with `0b`: `0b100101100 == 300`.

**Decimal to binary by hand.** Repeatedly take the largest power of two that fits: 300 − 256 =
44, 44 − 32 = 12, 12 − 8 = 4, 4 − 4 = 0; the powers used were 256, 32, 8, 4, so those positions
are 1. Or divide by 2 repeatedly and read the remainders from the bottom up:

```
300 / 2 = 150 r 0
150 / 2 =  75 r 0
 75 / 2 =  37 r 1
 37 / 2 =  18 r 1
 18 / 2 =   9 r 0
  9 / 2 =   4 r 1
  4 / 2 =   2 r 0
  2 / 2 =   1 r 0
  1 / 2 =   0 r 1      read upwards: 100101100
```

**Binary to decimal**: add the worths of the 1 positions.

### 3.3 Hexadecimal: base 16, a shorthand for binary

Binary is long: a 32-bit number is 32 digits. **Hexadecimal** (hex, base 16) uses sixteen
symbols, 0–9 and then A–F for 10–15, and each hex digit is exactly **four binary digits**, because
$16 = 2^4$. That makes conversion mechanical: group the bits in fours from the right.

| Hex | Binary | Decimal | | Hex | Binary | Decimal |
|---|---|---|---|---|---|---|
| 0 | 0000 | 0 | | 8 | 1000 | 8 |
| 1 | 0001 | 1 | | 9 | 1001 | 9 |
| 2 | 0010 | 2 | | A | 1010 | 10 |
| 3 | 0011 | 3 | | B | 1011 | 11 |
| 4 | 0100 | 4 | | C | 1100 | 12 |
| 5 | 0101 | 5 | | D | 1101 | 13 |
| 6 | 0110 | 6 | | E | 1110 | 14 |
| 7 | 0111 | 7 | | F | 1111 | 15 |

```
300 = 1 0010 1100      (group from the right)
    = 1    2    C      → 0x12C
check: 1·256 + 2·16 + 12 = 256 + 32 + 12 = 300
```

Java writes hex with `0x`: `0x12C == 300`. Upper or lower case does not matter. Long literals
need an `L`: `0xFFFFFFFFL`. You may put underscores anywhere between digits for readability:
`0x1_0000_0000L` is $2^{32}$, `1_000_000` is a million. The lessons write hex because it shows the
bits: `0xFF` is "eight ones", `0x80` is "only the top bit of a byte", `0x7F` is "every bit of a
byte but the top".

> **Try it.** `Integer.toBinaryString(300)`, `Integer.toHexString(300)`,
> `Integer.parseInt("12C", 16)`, `0b1111_1111`, `0xFF`. Then convert 1000 to hex by hand (answer:
> `0x3E8`) and check.

<details><summary>Check: what are 0x40, 0x100, 0b1010_0000 and 0xFFFF in decimal?</summary>

64; 256; 160 (128 + 32); 65,535 ($2^{16} - 1$).
</details>

---

## 4. Bits, bytes and Java's number types

### 4.1 Bits and bytes

A **bit** is one binary digit, 0 or 1. A **byte** is 8 bits, so one byte holds $2^8 = 256$
different values. Memory and files are made of bytes: every byte has a position (its
**address** in memory, its **offset** in a file), and anything bigger than a byte is several
bytes next to each other.

With $n$ bits you can make $2^n$ different patterns, so $n$ bits can count from 0 to $2^n - 1$.
Going the other way, to store a value up to $V$ you need $\lceil \log_2 (V + 1) \rceil$ bits: a d20
result (1 to 20) fits in 5 bits ($2^5 = 32 \ge 20$), a die's number of sides up to 255 in 8.

**Units.** A kilobyte is ambiguous: 1000 or 1024 bytes. The unambiguous names are **KiB** (1024
bytes, $2^{10}$), **MiB** ($2^{20}$ = 1,048,576 bytes) and **GiB** ($2^{30}$). The engine sizes
buffers in MiB, written with a shift (§6.3): `32L << 20` is $32 \cdot 2^{20}$ = 33,554,432 bytes,
32 MiB.

### 4.2 Java's primitive types

| Type | Bits | Bytes | Range | Notes |
|---|---|---|---|---|
| `byte` | 8 | 1 | −128 … 127 | signed! (§5.4) |
| `short` | 16 | 2 | −32,768 … 32,767 | |
| `char` | 16 | 2 | 0 … 65,535 | unsigned; a UTF-16 code unit (lesson 4) |
| `int` | 32 | 4 | −2,147,483,648 … 2,147,483,647 | about ±2.1 billion |
| `long` | 64 | 8 | about ±$9.22 \times 10^{18}$ | |
| `float` | 32 | 4 | about ±$3.4 \times 10^{38}$, 7 digits | §7 |
| `double` | 64 | 8 | about ±$1.8 \times 10^{308}$, 16 digits | |
| `boolean` | — | 1 in arrays | `true`/`false` | a `boolean[]` uses a byte per element |

An `int` is the engine's workhorse: it names entities (handles), indexes arrays, packs dice. A
`float` is its number for positions, times and colors. `long` holds 64-bit random numbers and
byte offsets into buffers that may be larger than 2 GiB.

### 4.3 Overflow: what happens past the top

Java integers do not grow. Adding 1 to the largest `int` **wraps around** to the smallest:

```
jshell> Integer.MAX_VALUE + 1
$1 ==> -2147483648
```

Why it wraps (rather than throwing) is §5. Two consequences for the engine: sizes in bytes of big
buffers are `long` (`(long) count * 64`, with the cast *before* the multiply, or the multiply
overflows in `int` first), and some code relies on wrapping on purpose (the random generator's
multiplications in lesson 2).

<details><summary>Check: how many bits does a value from 0 to 1000 need? What is <code>(long) (100_000 * 100_000)</code>?</summary>

10 bits ($2^{10} = 1024 > 1000$). The product is computed in `int` first: $10^{10}$ overflows and
wraps to 1,410,065,408, and only then is it widened. Write `100_000L * 100_000` to get
10,000,000,000.
</details>

---

## 5. Negative numbers: two's complement

### 5.1 The problem

Bits are only 0s and 1s; there is no minus sign. How does an `int` hold −5?

### 5.2 The idea: count backwards from zero

Picture a 4-bit counter, like a car's odometer with 4 binary digits. It holds 0000 to 1111 (0 to
15). Count down from 0000 by one and it rolls over to 1111, just as an odometer at 0000 rolls
back to 9999. So it is natural to *call* 1111 "−1", 1110 "−2", and so on:

```
bits:   0000 0001 0010 0011 0100 0101 0110 0111 | 1000 1001 1010 1011 1100 1101 1110 1111
value:     0    1    2    3    4    5    6    7 |   -8   -7   -6   -5   -4   -3   -2   -1
```

This is **two's complement**, the way every modern CPU and Java store signed integers. Its
properties:

- **The top bit is the sign.** 0 for zero and positives, 1 for negatives. For an `int` the top bit
  is bit 31.
- **There is one more negative than positive.** 4 bits go from −8 to 7; an `int` from $-2^{31}$
  to $2^{31} - 1$. That is why `Integer.MIN_VALUE` has no positive partner: `-Integer.MIN_VALUE`
  is itself.
- **Addition needs no special case.** The CPU adds the bit patterns and ignores the carry out of
  the top, and the answer is right for signed and unsigned readings alike: $-1 + 1$ is
  `1111 + 0001 = 1 0000`, drop the carry, `0000`. This is why wrapping (§4.3) is what happens:
  `0111 + 0001 = 1000` is 7 + 1 = "−8".

### 5.3 Negating by hand

The rule: **flip every bit, then add 1**. $-x = \lnot x + 1$ (where $\lnot$ flips bits, Java's `~`).

```
 5           = 0000 0101   (8 bits)
~5           = 1111 1010   (flip)
~5 + 1       = 1111 1011   = 0xFB  → this is −5
check: 0xFB as unsigned is 251 = 256 − 5
```

That check is the general reading: an $n$-bit pattern whose unsigned value is $u$ means $u -
2^n$ when its top bit is set. `0xFB` → $251 - 256 = -5$. `0xFFFFFFFF` as an `int` →
$4{,}294{,}967{,}295 - 2^{32} = -1$. **All ones is −1** in every width; you will see `-1L`
written to mean "a `long` with all 64 bits set".

### 5.4 The signed-byte trap

Java's `byte` is signed, so the byte with bits `1111 0000` (0xF0) is −16, not 240. When Java
widens a `byte` to an `int` it **sign-extends**: it copies the top bit into all the new bits, so
the value stays −16:

```
(byte) 0xF0          =                               1111 0000   = -16
(int) (byte) 0xF0    = 1111 1111 1111 1111 1111 1111 1111 0000   = -16 = 0xFFFFFFF0
```

To get 240 you must throw the extra ones away with a mask (§6.2): `b & 0xFF` keeps only the low 8
bits, giving `0x000000F0` = 240. Every byte reader in the engine does this; forgetting it is the
classic bug of lesson 4.

> **Try it.** `(byte) 0xF0`, `(int) (byte) 0xF0`, `((byte) 0xF0) & 0xFF`, `~5`, `~5 + 1`,
> `Integer.toBinaryString(-5)`, `Integer.MIN_VALUE == -Integer.MIN_VALUE`.

<details><summary>Check: what is the 8-bit two's complement pattern of −1, of −128, and what <code>int</code> does <code>(byte) 200</code> give?</summary>

`1111 1111` (0xFF); `1000 0000` (0x80); 200 is `1100 1000`, whose top bit is set, so as a byte
it is $200 - 256 = -56$.
</details>

---

## 6. Bit operations and packing

The engine stores a lot of information in the individual bits of an `int` or a `long`: flags,
dice, handles, sort keys. Six operators do all of it. They work on every bit position
independently (the first four) or move bits sideways (the shifts).

### 6.1 AND, OR, XOR, NOT

For one pair of bits:

| a | b | `a & b` (AND) | `a \| b` (OR) | `a ^ b` (XOR) |
|---|---|---|---|---|
| 0 | 0 | 0 | 0 | 0 |
| 0 | 1 | 0 | 1 | 1 |
| 1 | 0 | 0 | 1 | 1 |
| 1 | 1 | 1 | 1 | 0 |

- **AND** is 1 only when both are 1.
- **OR** is 1 when either is 1.
- **XOR** ("exclusive or") is 1 when they *differ*. XOR-ing with 1 flips a bit; XOR-ing with 0
  keeps it; XOR-ing a value with itself gives 0.
- **NOT** (`~a`) flips every bit.

On whole numbers, Java applies the table to each of the 32 (or 64) positions:

```
  0b1100        0b1100        0b1100        ~0b1100 (4 bits shown)
& 0b1010      | 0b1010      ^ 0b1010      = 0b0011
= 0b1000      = 0b1110      = 0b0110
```

Do not confuse `&` with `&&`: `&&` is the boolean "and" that skips its right side when the left
is false; `&` on two `int`s is the bitwise operator.

### 6.2 Masks: picking, setting and clearing bits

A **mask** is a number whose 1 bits mark the positions you care about.

- **Keep only some bits:** `x & mask`. `x & 0xFF` keeps the low 8 bits (the lowest byte) and
  zeros the rest. This is the fix for the signed-byte trap (§5.4).
- **Test a flag:** `(flags & FLAG) != 0` is true when the flag's bit is set. Lesson 1's buffer
  code does `if ((bufferFlags & MAPPED) != 0)`.
- **Set a flag:** `flags |= FLAG` (OR in the bit; other bits unchanged).
- **Clear a flag:** `flags &= ~FLAG` (AND with everything *except* that bit).
- **Toggle:** `flags ^= FLAG`.

Flags are powers of two so that each owns one bit: `DYNAMIC = 1` (bit 0) and `MAPPED = 2`
(bit 1) in lesson 1, so `DYNAMIC | MAPPED` is 3, both bits.

### 6.3 Shifts

- `x << n` moves every bit `n` places to the left, filling with 0s on the right. Each place is a
  doubling, so `x << n` is $x \cdot 2^n$ (as long as nothing falls off the top): `1 << 10` is 1024,
  `3 << 4` is 48.
- `x >>> n` (**unsigned** right shift) moves bits right and fills with **0s** on the left: for a
  non-negative `x` it is integer division by $2^n$.
- `x >> n` (**signed** right shift) moves bits right and fills with **copies of the sign bit**, so
  a negative number stays negative: `-16 >> 2` is −4. This is division by $2^n$ rounding *down*
  (towards −∞).

```
-16            = 1111 1111 1111 1111 1111 1111 1111 0000
-16 >> 2       = 1111 1111 1111 1111 1111 1111 1111 1100   = -4
-16 >>> 28     = 0000 0000 0000 0000 0000 0000 0000 1111   = 15
```

Two Java rules to remember: an `int` shift uses only the low 5 bits of the distance (so `1 << 32`
is `1 << 0` = 1, not 0), and a `long` shift the low 6. And `byte`/`short` values are widened to
`int` (with sign extension) *before* shifting.

**Rotation** moves bits out of one end and back in at the other: `Long.rotateLeft(x, 7)` is
`(x << 7) | (x >>> 57)`. Nothing is lost, which is why random number generators like it (lesson 2).

### 6.4 Packing several numbers into one

Say you want to store a dice expression like "2d6+3" (two dice of six sides, plus 3) in a single
`int`, so a table of weapons can be an `int[]` instead of an array of objects. Decide which bits
hold what:

```
bit:   31 ........ 24 | 23 ........ 16 | 15 ............................ 0
       count (8 bits) | sides (8 bits) | bonus (16 bits, signed)
```

**Packing** shifts each field to its place and ORs them together:

```java
int dice = count << 24 | sides << 16 | (bonus & 0xFFFF);
```

The `& 0xFFFF` matters for a negative bonus: −1 is 32 ones, and without the mask they would
overwrite the count and sides. For 2d6+3: `2 << 24` is `0x02000000`, `6 << 16` is `0x00060000`,
3 is `0x00000003`, so the packed value is `0x02060003`.

**Unpacking** shifts the field down and masks off its neighbours:

```java
int count = dice >>> 24;           // nothing above it, no mask needed
int sides = dice >>> 16 & 0xFF;    // drop the bonus, keep 8 bits (>>> binds tighter than &)
int bonus = (short) (dice & 0xFFFF); // keep 16 bits, then reinterpret them as a signed short
```

The `(short)` cast is the reverse of the mask: it takes the low 16 bits and reads them as a
signed number, so `0xFFFF` becomes −1 again. This is exactly the engine's `math.Dice`, which
lesson 2 builds line by line. The same idea packs **handles** (an index and a generation, lesson
3) and the renderer's sort keys.

### 6.5 Two tricks with powers of two

- **`x % 8` is `x & 7` for non-negative `x`.** The remainder after dividing by $2^n$ is just the
  low $n$ bits, and $2^n - 1$ is a mask of $n$ ones (7 is `0b111`). A CPU does an AND in one cycle
  and a division in tens.
- **`x` is a power of two exactly when `(x & (x - 1)) == 0`** (for `x > 0`). A power of two has a
  single 1 bit; subtracting 1 turns it into 0 and every bit below into 1, so nothing overlaps:
  `1000 & 0111 = 0`. Any other number keeps its top bit: `1100 & 1011 = 1000`.

### 6.6 Rounding up to a multiple of a power of two

§2.5 rounded up with a division. When $a$ is a power of two there is a division-free version that
lesson 1's ring uses on every allocation:

$$\text{roundUp}(x, a) = (x + a - 1) \;\&\; {-a}$$

Why: $-a$ in two's complement is $\lnot a + 1$ (§5.3). For $a = 256 = $ `0x100`, $\lnot a$ is
`0xFFFFFEFF` and adding 1 gives `0xFFFFFF00`: ones everywhere except the low 8 bits. AND-ing with
it clears the low 8 bits, which rounds *down* to a multiple of 256. Adding $a - 1$ first turns the
round-down into a round-up, for the same reason as in §2.5. Worked: $x = 300$:
$300 + 255 = 555 = $ `0x22B`; `0x22B & 0xFFFFFF00` = `0x200` = 512. ✓. This only works when $a$
is a power of two: for $a = 24$, $-24$ is `...1110 1000`, which is not "ones then zeros", and the
AND produces nonsense.

> **Try it.** `Integer.toHexString(2 << 24 | 6 << 16 | (3 & 0xFFFF))`, then unpack it. Pack
> 1d4−1 (count 1, sides 4, bonus −1) and confirm it is `0x0104FFFF` and that `(short) 0xFFFF`
> is −1. Then `(300 + 255) & -256` and `(300 + 15) & -16`.

<details><summary>Check: what are <code>0b1011 &amp; 0b0110</code>, <code>0b1011 ^ 0b0110</code>, <code>5 &lt;&lt; 3</code>, <code>-1 &gt;&gt;&gt; 24</code>, and how do you clear bit 2 of <code>f</code>?</summary>

`0b0010` (2); `0b1101` (13); 40; 255 (`0xFF`: the low 24 bits fall off the right end and zeros come in at the top);
`f &= ~(1 << 2)`, which is `f &= ~4`.
</details>

---

## 7. Fractions in binary: how a float is stored

### 7.1 Binary fractions

Positions to the right of a binary point are worth $\frac{1}{2}, \frac{1}{4}, \frac{1}{8}, …$
(that is $2^{-1}, 2^{-2}, 2^{-3}$), just as decimal positions are tenths and hundredths. So
`1.1` in binary is $1 + \frac12 = 1.5$, and `0.011` is $\frac14 + \frac18 = 0.375$.

Some fractions that are short in decimal never end in binary, exactly like $\frac13 =
0.3333…$ never ends in decimal. **0.1 is one of them**: in binary it is
`0.000110011001100110011…` forever. A computer must cut it off somewhere, so `0.1f` is not 0.1.

### 7.2 Scientific notation in base 2

A `float` stores a number the way scientific notation does, $\pm\, m \times 2^e$, with a
**mantissa** $m$ between 1 and 2 and a whole **exponent** $e$. The 32 bits are split:

```
bit:  31 | 30 ......... 23 | 22 ................................ 0
      sign | exponent (8)  | fraction (23)
value = (-1)^sign × 1.fraction × 2^(exponent − 127)
```

- The **sign** bit: 0 positive, 1 negative.
- The **exponent** field is stored with a **bias** of 127 so that it can be negative without a
  sign of its own: a field of 127 means $2^0$, 128 means $2^1$, 126 means $2^{-1}$.
- The **fraction** is the binary digits after the point; the leading "1." is not stored (every
  normal number has it, so it is free).

**Worked example: 1.5f.** $1.5 = 1.1_2 \times 2^0$. Sign 0. Exponent $0 + 127 = 127$ =
`0111 1111`. Fraction: the digits after the point, `1000…0` (a 1 then 22 zeros). Together:

```
0 | 0111 1111 | 1000 0000 0000 0000 0000 000
= 0011 1111 1100 0000 0000 0000 0000 0000 = 0x3FC00000
```

and indeed `Float.floatToRawIntBits(1.5f)` is `0x3FC00000`. Lesson 4 writes floats into files
exactly this way: it asks Java for these 32 bits and writes them as an `int`.

### 7.3 What this means in practice

- **Precision is relative.** 23 stored fraction bits plus the hidden 1 give 24 significant bits,
  about 7 decimal digits. Near 1.0 the gap between neighbouring floats is $2^{-23} \approx 1.2
  \times 10^{-7}$; near 1000 it is about $6 \times 10^{-5}$; just below 16,777,216 ($2^{24}$) it
  is 1, and from there up it is 2, so 16,777,217 cannot be stored at all:
  `16_777_216f + 1f == 16_777_216f` is true (the exact sum lies halfway between two floats and is
  rounded to the "even" one). Game worlds keep coordinates within a few kilometres of the origin
  for this reason.
- **Rounding is everywhere.** `0.1f` is stored as `0x3DCCCCCD`, which is
  0.100000001490116…. Never compare floats with `==` after arithmetic; compare with a tolerance.
- **Special values.** Exponent all ones means infinity (fraction 0) or **NaN**, "not a number"
  (fraction non-zero), the result of `0f / 0f`. NaN is not equal to anything, itself included.
- **`double`** is the same design with 64 bits (11 exponent bits, bias 1023, 52 fraction bits):
  about 16 digits. The engine uses `float` for everything the GPU sees (GPUs are fastest at
  32-bit floats) and `double` where precision matters more than speed (accumulated play time).

> **Try it.** `Integer.toHexString(Float.floatToRawIntBits(1.5f))`,
> `Integer.toHexString(Float.floatToRawIntBits(0.1f))`, `(double) 0.1f`,
> `0.1f + 0.2f == 0.3f`, `16_777_216f + 1f`, `Float.isNaN(0f / 0f)`.

<details><summary>Check: write −2.0f in bits.</summary>

$-2 = -1.0_2 \times 2^1$: sign 1, exponent $1 + 127 = 128$ = `1000 0000`, fraction 0. Bits:
`1 10000000 000…0` = `0xC0000000`.
</details>

---

## 8. Memory: addresses, the Java heap and native memory

### 8.1 Memory is one long array of bytes

Main memory (**RAM**) behaves like a giant `byte[]`. Each byte has a number, its **address**,
starting from 0. A 64-bit machine uses 64-bit addresses. A value larger than a byte occupies
consecutive addresses: an `int` at address 1000 uses bytes 1000, 1001, 1002 and 1003. The
**byte order** within those four (which byte holds the low bits) is called **endianness**; the
machines this engine runs on are **little-endian**, which puts the lowest byte at the lowest
address. Lesson 4 derives it in detail.

A **pointer** is a variable that holds an address. Java hides pointers, but every object
reference is one underneath.

### 8.2 The Java heap

When you write `new Monster()`, the JVM finds free space in a region of memory it manages, the
**heap**, and returns a reference. Each object has a **header** (8 bytes on the JDK 27 this
engine uses, where *compact object headers* are the default; 12 bytes on older JDKs or with
`-XX:-UseCompactObjectHeaders`) followed by its fields, and its size is rounded up to a multiple of
8. An **array** is an object too: a header, a length, then the elements **next to
each other** — an `int[1000]` is 4,000 bytes of ints in a row.

An array of *objects* is different: `Monster[] a` holds 1000 **references**, and each one points
to an object that can be anywhere in the heap. Lesson 3 is about why that difference decides how
fast a game loop runs.

The **garbage collector** (GC) finds objects nothing refers to any more and reuses their memory.
It is excellent, but it does work proportional to how much you allocate, and some of that work
can pause your program. A game that allocates a little every frame pays a little every frame and
occasionally a lot. That is why the engine's rule is **zero bytes allocated per frame** after
loading: no `new` in the game loop, no boxing (`Integer` instead of `int`), no lambdas that
capture variables, no string building. Each lesson's code obeys it and its tests prove it with
`Allocations.assertNoAllocation`, which runs a piece of code many times and fails if the JVM's
per-thread allocation counter moved.

### 8.3 Native memory

The GPU, the operating system and C libraries cannot read the Java heap: the GC moves objects
around, and their layout is the JVM's business. Memory those parties share with Java is
**native memory** (also called off-heap memory): plain bytes at a fixed address, outside the
GC's control. Java reaches it through a **`MemorySegment`** (§12): a pointer plus a size, with
bounds checks. The engine allocates native memory from an **`Arena`**, which frees everything it
allocated when it is closed. A segment's bytes are read and written with typed accessors:

```java
try (Arena arena = Arena.ofConfined()) {
    MemorySegment s = arena.allocate(16);           // 16 bytes, freed when the arena closes
    s.set(ValueLayout.JAVA_INT, 0, 42);             // an int at byte offset 0
    s.set(ValueLayout.JAVA_FLOAT, 4, 1.5f);         // a float at byte offset 4
    int v = s.get(ValueLayout.JAVA_INT, 0);         // 42
}
```

### 8.4 Alignment

A value is **aligned** when its address is a multiple of its size: an `int` at an address
divisible by 4, a `long` at one divisible by 8. CPUs read aligned values in one step; a value that
straddles a boundary may take two reads, and some instructions refuse it. Java's
`ValueLayout.JAVA_LONG` *requires* an aligned offset and throws otherwise. GPUs have their own,
larger alignment rules (lesson 1). Rounding an offset up to a multiple (§2.5, §6.6) is how data is
aligned, and the unused bytes skipped to get there are **padding**.

<details><summary>Check: an <code>int[256]</code> holds how many bytes of elements? A <code>long</code> may be stored at which of the offsets 12, 16, 20, 24?</summary>

1,024 bytes; 16 and 24 (multiples of 8).
</details>

---

## 9. The CPU: instructions, caches and threads

### 9.1 What a CPU does

The **CPU** (central processing unit) runs your program as a stream of tiny **instructions**:
load a value from memory into a **register** (one of a few dozen named slots inside the CPU that
hold the numbers it is working on), add two registers, compare, jump to another instruction,
store a register to memory. Java source becomes **bytecode** when compiled; the JVM first
interprets the bytecode and then, for code that runs often ("hot" code), its **JIT compiler**
(just-in-time: C1 quickly, then C2 thoroughly) turns it into real machine instructions while the
program runs. Lesson 3 shows what C2 does to the engine's loops.

A CPU's **clock** ticks a few billion times a second: 4 GHz is 4 billion **cycles** per second,
so one cycle is 0.25 nanoseconds (a nanosecond, ns, is a billionth of a second). A modern core
starts several instructions per cycle and works on many at once, overlapping them whenever one
does not need the result of another.

### 9.2 Memory is far away

The instructions are fast; memory is not. Reading a value from RAM takes on the order of **80–100
ns**, which at 4 GHz is 300–400 cycles: time in which the core could have done over a thousand
additions. To hide this, the CPU keeps copies of recently used memory in **caches**, small fast
memories on the chip:

| Level | Typical size | Typical latency | Shared? |
|---|---|---|---|
| Registers | a few hundred bytes | 0 (part of the instruction) | per core |
| L1 cache | 32–48 KiB data | ~4–5 cycles (~1 ns) | per core |
| L2 cache | 0.5–2 MiB | ~12–15 cycles (~3–4 ns) | per core |
| L3 cache | 8–64 MiB | ~40–60 cycles (~10–15 ns) | all cores |
| RAM | gigabytes | ~300–400 cycles (~80–100 ns) | everything |

The numbers vary by processor; their *ratios* are what matter: an L1 hit is about a hundred times
cheaper than a trip to RAM.

### 9.3 Cache lines and the prefetcher

Caches do not store single bytes. They move memory in blocks of **64 bytes** called **cache
lines** (64 on the x86-64 processors this engine targets). Ask for one `int` and the CPU fetches
the whole 64-byte line around it, so the next 15 `int`s are now free. Two consequences:

- **Neighbours are free, strangers are expensive.** Walking an `int[]` from start to end uses
  every byte of every line fetched: one miss brings 16 useful values. Following references from
  object to object (`a.next.next.value`) may miss on every step, and each miss uses only the few
  bytes you wanted out of the 64 that came.
- **Predictable is free too.** The **prefetcher** watches the addresses a core reads; when it sees
  a pattern (address, address + 64, address + 128, …) it fetches the next lines before they are
  asked for. A sequential array walk runs almost entirely from cache. A walk over references
  scattered around the heap has no pattern, and each step waits for the one before it, because
  the address of the next object is *in* the current one. That chain of waits, a **dependent
  load**, is the most expensive shape of code on a modern CPU.

A **cache miss** is a read whose line is not in the cache. When this course says a layout is
"cache-friendly" it means: the bytes a loop needs are packed together, in the order the loop
visits them. Lesson 3 measures the difference, more than twenty times on the same work.

### 9.4 Threads and cores

A **thread** is one sequence of instructions being executed; a program can have many. A
**core** runs one thread at a time (two with hyper-threading); a CPU has several cores, so threads
can truly run at the same time. The operating system's **scheduler** decides which thread runs on
which core and switches between them. Waking a sleeping thread goes through the scheduler and
costs microseconds (a microsecond, µs, is a thousand nanoseconds), which lesson 3 turns into a rule
about when parallel loops pay.

Jeddy has a few long-lived threads with fixed jobs (DESIGN §5):

- the **main thread**, which owns the window and every OpenGL call (§10);
- the **game thread**, which runs the simulation at a fixed 30 ticks a second;
- **job workers**, a pool that splits big loops between cores;
- background threads for loading and saving.

### 9.5 Sharing data between threads

Two threads writing the same variable at the same time is a **data race**: each core works on its
own cached copy, and without coordination one write can be lost, or a reader can see values in an
order that never happened. Java gives three tools the lessons use:

- **`volatile`** on a field: every write is visible to every later read on any thread, and writes
  made before a volatile write are visible to a thread that reads the volatile value after it.
- **Compare-and-set (CAS)**, an atomic CPU instruction: "if the variable still holds `expected`,
  replace it with `new`, and tell me whether you did". If another thread changed it first, the
  CAS fails and you retry with the new value. A loop around a CAS lets many threads update one
  counter **without locks** (lock-free). Lesson 1's ring hands out memory to several threads this
  way. Java reaches CAS through `AtomicLong`, or through a **`VarHandle`** on a plain field, which
  is what the engine uses to avoid an extra object.
- **Handing work over whole**: one thread prepares data, then publishes it through a
  synchronizing operation (a volatile write, a queue, a job system's completion counter), and only
  then does another thread read it. Most of the engine works like this.

<details><summary>Check: why is summing an <code>int[1_000_000]</code> much faster than summing the same numbers stored in a million <code>Integer</code> objects?</summary>

The `int[]` is 4 MB of consecutive values: each 64-byte line brings 16 of them and the prefetcher
streams the lines ahead. The `Integer[]` is an array of references, each pointing to a 16-byte
object somewhere on the heap; every element costs an extra dependent load, the objects may be
scattered (a miss each), and each line fetched carries one useful number.
</details>

---

## 10. The GPU, the driver and OpenGL

### 10.1 What a GPU is

A **GPU** (graphics processing unit) is a second processor designed for one situation: the same
small program run on a huge number of independent inputs. Drawing a frame is exactly that: run
"where does this vertex land on the screen?" for every vertex of every mesh, then "what color is
this pixel?" for every one of two million pixels. A CPU has a handful of big, clever cores; a
GPU has thousands of simple ones, grouped so that 32 or 64 of them execute the same instruction at
the same time on different data.

There are two kinds:

- A **discrete GPU** is a separate card with its own memory (**VRAM**), very fast for the GPU
  (hundreds of GB/s) and connected to the CPU's memory by the **PCIe** bus (around 25–30 GB/s each
  way on a typical PCIe 4.0 slot).
- An **integrated GPU** is part of the CPU chip and uses the same RAM as the CPU. The owner's
  laptop has one (an Intel GPU), and lesson 1 ends with what happened when the engine first ran
  on it.

Either way, the GPU runs **asynchronously**: it works through its own queue of work, at its own
pace, while the CPU goes on with something else.

### 10.2 The programs the GPU runs: shaders

A **shader** is a program written for the GPU, in Jeddy's case in **GLSL** (the OpenGL Shading
Language, which looks like C). The driver compiles it when the engine asks. The kinds the
lessons mention:

- a **vertex shader** runs once per vertex and outputs where it lands on screen;
- a **fragment shader** runs once per covered pixel (a **fragment**) and outputs its color;
- a **compute shader** runs a grid of invocations on arbitrary data, with no drawing involved:
  the engine uses them to cull objects and sort lights on the GPU.

Between the vertex and fragment stages, fixed hardware called the **rasterizer** works out which
pixels each triangle covers. The whole path, vertices in and pixels out, is the **graphics
pipeline**; chapter [05](../05-opengl-from-java.md) walks through it.

### 10.3 GPU memory objects: buffers and textures

Shaders read their inputs from memory the GPU can reach:

- a **buffer** is a block of bytes with no structure of its own: vertex positions, per-object
  transforms, materials, light lists. The shader declares how it interprets them;
- a **texture** is an image the GPU can **sample** (read with filtering).

The engine creates them, fills them from the CPU and binds them so shaders can find them.
Lesson 1 is entirely about buffers: how the CPU writes them while the GPU is reading them.

### 10.4 The driver

The engine never talks to the GPU hardware. It calls the **driver**, the GPU maker's software
(Intel's, NVIDIA's, AMD's, or Mesa on Linux), which has two halves:

- a **user-mode driver**, a library loaded into the game's own process. It checks each call
  (**validation**), keeps track of the current state, compiles shaders, and translates the calls
  into the hardware's own command format, written into memory called a **command buffer**;
- a **kernel-mode driver**, part of the operating system, which manages GPU memory and hands
  filled command buffers to the GPU.

The GPU's **command processor** then reads the commands one after another and starts the work they
describe. So between "the engine called a function" and "the GPU did it" there is a queue, and
often most of a frame of time.

### 10.5 OpenGL

**OpenGL** is a standard API (application programming interface): a list of C functions such as
`glCreateBuffers` and `glDrawElements`, with a specification of what each must do. The specification
is written by the Khronos Group; each driver implements it. Jeddy uses **OpenGL 4.6, core
profile**, the last and most capable version, and its **direct state access** style, in which a
call names the object it changes (`glNamedBufferStorage(buffer, ...)`) instead of first "binding"
it.

Key ideas for the lessons:

- **Names.** GL objects are identified by small integers called **names**: `glCreateBuffers`
  gives you, say, buffer 7, and every later call about it passes 7.
- **The context.** All GL state belongs to a **context**, which is *current* on exactly one thread.
  In Jeddy that is the main thread, so every GL call happens there (CLAUDE.md, rule 4).
- **Calls return early.** `glDrawElements` does not draw; it appends "draw this" to the command
  buffer and returns, typically in well under a microsecond. The GPU draws later. The CPU only
  waits for the GPU when it asks for something the GPU must produce first (reading pixels back,
  `glFinish`, or a **fence**, lesson 1), or when the driver's queue is full.
- **Errors are quiet.** A wrong call sets an error flag and is usually ignored. Jeddy turns on
  the **debug output** (`-Djeddy.gl.debug=true`), which makes the driver report problems as
  messages; lesson 1 shows what it found.
- **The specification is a promise, not a guarantee.** Drivers have bugs, and different drivers
  have different ones. Lesson 1 is the story of finding three.

### 10.6 The swap

The GPU draws into an image that is not on screen (the **back buffer**). When the frame is
complete, the engine calls `SDL_GL_SwapWindow`, which asks for that image to be shown and gives
the next frame a fresh one. If the GPU or the display is behind, the driver lets the CPU run ahead
only a few frames, then blocks the swap until the GPU catches up.

<details><summary>Check: the main thread calls 2,000 GL functions in a frame and they all return within 1 ms. Has the GPU drawn the frame?</summary>

Not necessarily, and usually not yet: the calls recorded commands. The GPU executes them later,
often while the CPU is already preparing the next frame. Only a synchronization point (a fence
wait, a read-back, `glFinish`, a blocked swap) tells the CPU the GPU got somewhere.
</details>

---

## 11. Frames and time budgets

A game shows a new image, a **frame**, many times a second: the **frame rate**, in frames per
second (fps). The time available per frame is the **frame budget**:

$$\text{budget} = \frac{1000\ \text{ms}}{\text{fps}}: \quad 60\ \text{fps} \to 16.7\ \text{ms}, \quad
30 \to 33.3\ \text{ms}, \quad 144 \to 6.9\ \text{ms}.$$

Everything the CPU does for a frame (input, simulation, preparing draws) and everything the GPU
does (drawing) must fit, in parallel, in that budget. A frame that takes longer is a visible
**hitch**. A sense of scale, rounded and for a typical desktop:

| Thing | Time | Per 16.7 ms frame |
|---|---|---|
| One CPU cycle at 4 GHz | 0.25 ns | 67 million |
| An L1 cache hit | ~1 ns | |
| A RAM access (cache miss) | ~100 ns | 167,000 if that were all you did |
| A call from Java into C through FFM | a few ns to tens of ns | |
| One OpenGL call (recording) | ~0.1–1 µs | |
| Waking a sleeping thread | ~5–50 µs | |
| Writing a small file | ~0.1–1 ms | |
| A garbage-collection pause | 0.1 ms to many ms | why the engine allocates nothing per frame |

Jeddy simulates the world at a fixed **tick** of 1/30 s on the game thread and draws on the main
thread as fast as the display allows, interpolating between the last two ticks (chapter
[01](../01-architecture.md)). The CPU prepares frame $n + 1$ while the GPU draws frame $n$: that
overlap is what lesson 1's ring buffer makes safe.

---

## 12. Calling C from Java: the Foreign Function & Memory API

OpenGL, SDL (windows and input) and zstd (compression) are C libraries. Java calls them through
the **Foreign Function & Memory API** (FFM), part of the JDK since Java 22:

- a **`MemorySegment`** is a bounded view of native memory (§8.3), with an address and a size;
- an **`Arena`** owns segments and frees them when closed. `Arena.ofConfined()` belongs to one
  thread; `Arena.ofShared()` may be used by several. The engine never uses `Arena.ofAuto()`,
  which frees memory whenever the garbage collector decides, because the GPU may still be
  reading it;
- a **downcall handle** is a `MethodHandle` that calls a C function. You describe the C signature
  with a `FunctionDescriptor` (`JAVA_INT` for a C `int`, `JAVA_LONG` for 64-bit sizes, `ADDRESS`
  for a pointer) and ask `Linker.nativeLinker()` for the handle.

The engine generates a Java method for each GL function from the official registry file, so the
lessons simply call `glNamedBufferStorage(name, size, data, flags)` as if it were Java. Behind
it, each function has a tiny holder class with a `static final MethodHandle`, which the JIT treats
as a constant and compiles into a direct native call (chapter [05](../05-opengl-from-java.md) shows
a generated method, chapter [12](../12-java-performance.md) the costs). Two GL details the lessons
rely on:

- GL enumerations and bit flags (`GL_MAP_WRITE_BIT`, `GL_TIMEOUT_EXPIRED`) are plain `int`
  constants in the generated `GL46` class (a few 64-bit values, such as `GL_TIMEOUT_IGNORED`, are
  `long`);
- a GL function that takes a pointer takes a `MemorySegment`. For the pointer parameters that
  carry data or buffer offsets (`data`, `pixels`, `indices`, `indirect`, `pointer`, `offset`) the
  generator adds a second version taking a `long`, so code that already has a native address or an
  offset passes it without creating a segment object (D29). Out-parameters such as
  `glCreateBuffers`'s take only a `MemorySegment`.

---

## 13. Check yourself

Try each one before opening the answer.

1. Convert 200 to binary and hex. <details><summary>Answer</summary>200 = 128 + 64 + 8 =
   `1100 1000` = `0xC8`.</details>
2. What is `0x0108FFFF >>> 16 & 0xFF`, and `(short) (0x0108FFFF & 0xFFFF)`? <details><summary>Answer</summary>
   `0x0108FFFF >>> 16` is `0x0108`; `& 0xFF` keeps `0x08` = 8. The low 16 bits are `0xFFFF`, which
   as a `short` is −1. (This is the packed dice 1d8−1: lesson 2.)</details>
3. Why does `(bytes[i] & 0xFF)` appear in every byte reader? <details><summary>Answer</summary>
   A Java `byte` is signed; widening a byte with its top bit set produces an `int` with 24 extra
   1 bits (sign extension). The mask keeps only the 8 bits that were in the file.</details>
4. Round 1000 up to a multiple of 64 with the bit trick. <details><summary>Answer</summary>
   `(1000 + 63) & -64` = `1063 & ...11000000` = 1024.</details>
5. How many 64-byte cache lines does a loop touch that reads one `float` from each of 300
   consecutive elements of a `float[]`? And if each float lived in its own object scattered
   around the heap? <details><summary>Answer</summary>300 × 4 = 1,200 bytes = 18.75, so 19
   lines (20 if the elements start more than 16 bytes into a cache line). Scattered objects: about one line
   per object, 300 lines, plus the lines holding the references.</details>
6. A frame calls `glDrawElements` and the call returns after 0.3 µs. What happened during those
   0.3 µs? <details><summary>Answer</summary>The driver validated the call and wrote a draw
   command into its command buffer. The GPU has not drawn anything yet.</details>
7. What is the frame budget at 90 fps? <details><summary>Answer</summary>1000 / 90 ≈ 11.1 ms.</details>
8. Why does the engine forbid `Arena.ofAuto()`? <details><summary>Answer</summary>Its memory is
   freed when the garbage collector notices the arena is unreachable, at an unpredictable time;
   memory handed to the GPU or a C library must be freed at a moment the engine controls.</details>

---

## 14. Glossary

- **Address:** the number of a byte in memory (§8.1).
- **Alignment:** an address that is a multiple of a value's size or of what the hardware asks
  for (§8.4).
- **Arena:** an FFM object that owns native memory and frees it when closed (§12).
- **Asynchronous:** happening independently, at its own pace; the GPU works asynchronously to
  the CPU (§10.1).
- **Bias (float exponent):** the 127 added to a float's exponent so it can be stored without a
  sign (§7.2).
- **Bit, byte:** a binary digit; 8 bits (§4.1).
- **Buffer (GPU):** a block of GPU-visible bytes (§10.3).
- **Cache, cache line, cache miss:** fast on-chip copies of memory; the 64-byte unit they move; a
  read that has to go further away (§9.2–9.3).
- **CAS (compare-and-set):** an atomic "replace if unchanged" instruction (§9.5).
- **Command buffer, command processor:** the memory where the driver writes GPU commands; the
  part of the GPU that reads them (§10.4).
- **Context (GL):** all of OpenGL's state, current on one thread (§10.5).
- **Dependent load:** a memory read whose address comes from a previous read; it cannot start
  until that one finishes (§9.3).
- **Driver:** the GPU maker's software between the API and the hardware (§10.4).
- **Endianness, little-endian:** the order of a value's bytes in memory; lowest byte first (§8.1,
  lesson 4).
- **FFM:** Java's Foreign Function & Memory API (§12).
- **Fragment, fragment shader:** a covered pixel; the program that colors it (§10.2).
- **Frame, frame budget, frame rate:** one displayed image; the time per frame; frames per second
  (§11).
- **GC (garbage collector), heap:** the JVM's automatic memory manager and the memory it manages
  (§8.2).
- **Hex (hexadecimal):** base 16; one digit per 4 bits (§3.3).
- **JIT, C1, C2:** the JVM's compilers from bytecode to machine code while the program runs (§9.1).
- **Mask:** a number whose 1 bits select bit positions (§6.2).
- **`mod`:** the remainder of a division (§2.4).
- **Name (GL):** the integer that identifies a GL object (§10.5).
- **NaN:** the float value "not a number" (§7.3).
- **Native memory, `MemorySegment`:** memory outside the Java heap at a fixed address; Java's
  view of it (§8.3).
- **Padding:** unused bytes inserted to reach an aligned offset (§8.4).
- **Prefetcher:** the CPU part that fetches cache lines before they are asked for (§9.3).
- **Register:** a slot inside the CPU core holding a value being worked on (§9.1).
- **Shader, vertex shader, compute shader:** a GPU program; one per vertex; one over an arbitrary
  grid (§10.2).
- **Sign extension:** filling the new high bits with the sign bit when widening (§5.4).
- **Texture:** an image the GPU samples (§10.3).
- **Thread, core, scheduler:** a sequence of execution; the hardware that runs one; the OS part
  that assigns them (§9.4).
- **Tick:** one fixed 1/30 s step of the simulation (§11).
- **Two's complement:** the encoding of negative integers in which −1 is all ones (§5).
- **Validation:** the driver checking a call is legal (§10.4).
- **`volatile`:** a Java field whose writes are visible to other threads in order (§9.5).
- **VRAM:** a discrete GPU's own memory (§10.1).
- **XOR:** bitwise "different" (§6.1).
- **Zero allocation:** the engine's rule that steady-state frames create no Java objects (§8.2).

---

*Next: [Lesson 1 — Build a frame ring](01-buffers-in-flight.md), which uses §6, §8, §9.5 and
§10 of this lesson to build the engine's per-frame GPU memory from scratch.*
