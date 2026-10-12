# Lesson 3 — Build a component store: memory, caches, struct of arrays and sparse sets

*A game has hundreds of things of the same kind — skeletons, torches, arrows — and every tick a
loop visits each of them. How fast that loop runs depends less on the instructions in it than on
how the data it reads is laid out in memory. This lesson measures that first, then builds, from
empty files, the machinery the engine's component system generates for every gameplay record:
entity handles, a sparse set, a store with one array per field, flyweight views, and the loop that
calls a behaviour for every entity. It ends by measuring the result against the "object per entity"
design most engines use, and with the rule for when a loop is worth running on several threads.*

**Before you start.** You need [Lesson 0](00-foundations.md) §6 (packing bits), §8 (memory, the heap,
arrays, objects) and §9 (the CPU, caches, cache lines, dependent loads, threads). Chapter
[22 Components](../22-components.md) describes the components API from the game programmer's side;
this lesson builds what is underneath it.

**What you will build**

| Piece | File | What it does |
|---|---|---|
| Handles | [`core.handle.Handles`](../../../modules/core/src/main/java/dev/jeddy/core/handle/Handles.java), [`HandlePool`](../../../modules/core/src/main/java/dev/jeddy/core/handle/HandlePool.java) | an entity is an `int`: a slot index and a generation that makes stale handles fail |
| The sparse set | [`core.collections.SparseSet`](../../../modules/core/src/main/java/dev/jeddy/core/collections/SparseSet.java) | entity index → dense row in O(1), members packed for fast loops |
| The store | [`components.Store`](../../../modules/components/src/main/java/dev/jeddy/components/Store.java) and a generated `MindStore` | one primitive array per field, rows kept dense by swap-removal |
| The views | [`components.View`](../../../modules/components/src/main/java/dev/jeddy/components/View.java), generated `MindView`, `MindMut` | an object that reads one row, reused for every entity |
| The loop | generated `CryptGame.SkeletonAi_think_loop` | joins several stores and calls a static behaviour per entity |
| The measurement | [`bench.ComponentsBench`](../../../bench/src/main/java/dev/jeddy/bench/ComponentsBench.java) | objects with virtual calls, hand-written arrays, and the generated code, side by side |

**Contents**

- Part A — How memory decides speed
  1. [The question](#1-the-question)
  2. [An experiment: same work, different order](#2-an-experiment-same-work-different-order)
  3. [What a Java object costs in memory](#3-what-a-java-object-costs-in-memory)
  4. [Array of structures against structure of arrays](#4-array-of-structures-against-structure-of-arrays)
- Part B — Identity without references
  5. [Why entities are not objects](#5-why-entities-are-not-objects)
  6. [Step 1: handles](#6-step-1-handles)
  7. [Step 2: the handle pool](#7-step-2-the-handle-pool)
- Part C — Building the store
  8. [Step 3: the sparse set](#8-step-3-the-sparse-set)
  9. [Step 4: the store's base class](#9-step-4-the-stores-base-class)
  10. [Step 5: the generated columns](#10-step-5-the-generated-columns)
  11. [Step 6: views — one object for every row](#11-step-6-views--one-object-for-every-row)
  12. [Step 7: the loop that calls a behaviour](#12-step-7-the-loop-that-calls-a-behaviour)
- Part D — Proving it
  13. [Why the view costs nothing: what the JIT does](#13-why-the-view-costs-nothing-what-the-jit-does)
  14. [The benchmark](#14-the-benchmark)
  15. [When to use several threads](#15-when-to-use-several-threads)
  16. [Things to run](#16-things-to-run)
  17. [Exercises](#17-exercises)
  18. [Glossary](#18-glossary)

---

# Part A — How memory decides speed

## 1. The question

The demo's crypt has 64 skeletons by default and 294 in its performance scene (`--perf`). Each has
an AI state the game calls `Mind`:

```java
public record Mind(
        MonsterState state,   // IDLE, CHASE, ATTACK or DEAD
        float timer,          // seconds to the next decision
        float cooldown,       // seconds until it may swing again
        float corpse,         // seconds the body stays visible
        Vec3f home) {}        // where it idles and returns to
```

Thirty times a second, a behaviour called `think` visits every skeleton, counts its timers down and
decides what it does next. How should 300 `Mind`s be stored so that this loop is fast?

The obvious answer — 300 `Mind` objects in an array or a list — is what most object-oriented game
engines do. This lesson shows why the engine does something else, starting with the hardware.

## 2. An experiment: same work, different order

L00 §9 said memory is far away, that the CPU moves it in 64-byte lines, that the prefetcher
rewards predictable access and that dependent loads are the most expensive shape of code. Here is
how large those effects are. The program below adds up 16,777,216 `int`s (64 MiB, far more than any
cache) in five ways that do *exactly the same additions*:

```java
int n = 1 << 24;
int[] data = new int[n];        // the values
int[] order = ...;              // 0 .. n-1, shuffled
int[] seqOrder = ...;           // 0 .. n-1, in order
Box[] boxes = ...;              // n small objects { int v; }, allocated one after another
Box[] shuffled = ...;           // the same objects, visited in shuffled order

for (int i = 0; i < n; i++) s += data[i];               // 1. sequential
for (int i = 0; i < n; i++) s += data[seqOrder[i]];     // 2. through an index array, in order
for (int i = 0; i < n; i++) s += data[order[i]];        // 3. through an index array, shuffled
for (int i = 0; i < n; i++) s += boxes[i].v;            // 4. objects, in allocation order
for (int i = 0; i < n; i++) s += shuffled[i].v;         // 5. the same objects, shuffled
```

And a sixth, a **pointer chase**, where each step's address comes from the previous step (`p =
next[p]`, around one big shuffled cycle), against the same chase in address order.

Measured on a small cloud machine (2 cores, Intel Xeon at 2.1 GHz, JDK 27), time per element:

| Loop | ns per element | What the hardware does |
|---|---|---|
| 1. sequential `int[]` | 0.3 | every line fully used and prefetched; the JIT unrolls the loop and can add several elements per instruction |
| 2. via index array, in order | 1.4 | two sequential streams; each load from `data` needs its index loaded first, and an indexed load cannot be done several elements at a time |
| 3. via index array, shuffled | 6.5 | a cache miss per element, but independent: the core overlaps many |
| 4. objects in allocation order | 2.0 | objects happen to be adjacent; each line holds four 16-byte objects |
| 5. same objects, shuffled | 13 | a miss for the reference's target, per element |
| 6a. dependent chase, shuffled | 130–150 | a full trip to memory per element; nothing can overlap |
| 6b. dependent chase, in order | 2.7 | the same dependency, but every next element is already in cache |

The code is in the lesson's exercises (§17, exercise 1) so you can run it on your machine; the
ratios, not the exact numbers, are the point:

- **Order alone changes the cost twentyfold** (1 vs 3), with identical instructions.
- **Dependent misses are the worst case**, hundreds of times slower than a sequential read (6a vs
  1). A cache miss costs about 100 ns; independent misses can be overlapped (the core keeps a
  dozen or so in flight), dependent ones cannot.
- **Objects are fine when they happen to be adjacent** (4) and expensive when they are not (5).
  Whether they are adjacent depends on *when* they were allocated, which a game does not control:
  skeletons spawn over time, interleaved with every other allocation.

## 3. What a Java object costs in memory

On the 64-bit HotSpot JVM of JDK 27, every object starts with a **header** of 8 bytes (the class,
and the bits for hashing, locking and the garbage collector: *compact object headers*, the default
since JDK 27; older JDKs, or `-XX:-UseCompactObjectHeaders`, use 12). The fields follow, and the
total is rounded up to a multiple of 8, so an object may end with **padding**. A reference field is
4 bytes (a compressed pointer).

A `Mind` record as written:

```
Mind object                                Vec3f object (home)
  header          8 bytes                    header     8 bytes
  state   (ref)   4  → MonsterState.CHASE    x          4
  timer           4                          y          4
  cooldown        4                          z          4
  corpse          4                          padding    4
  home    (ref)   4  → ─────────────────────▶          = 24 bytes
  padding         4
= 32 bytes
```

So one skeleton's `Mind` is **two objects, 56 bytes, in two places**, plus the shared enum
constant. Of those 56 bytes, 28 are the actual numbers; the rest are headers, references and
padding. (With 12-byte headers the two objects are still 32 and 24 bytes: the padding is gone and
the headers fill it.) And
an array of 300 `Mind`s is an array of 300 *references* (another 1,200 bytes), each pointing
wherever the object happened to be allocated.

## 4. Array of structures against structure of arrays

### 4.1 Two layouts

**Array of structures (AoS)**: one object (structure) per skeleton, in an array. This is the
object-oriented default.

**Structure of arrays (SoA)**: one *array per field*, each indexed by the skeleton's row:

```
int[]   state    = [ s0, s1, s2, ... s299 ]
float[] timer    = [ t0, t1, t2, ... t299 ]
float[] cooldown = [ c0, c1, ... ]
float[] corpse   = [ ... ]
float[] homeX    = [ ... ]
float[] homeY    = [ ... ]
float[] homeZ    = [ ... ]
```

Skeleton 7 is "row 7 of every array". The enum is stored as its ordinal (an `int`) and the `Vec3f` as
three `float` arrays, so every column is a primitive array: no headers, no references, no objects.

### 4.2 The bytes a loop pulls in

Count the cache lines (64 bytes each) a loop over 300 skeletons fetches.

**A loop that reads only `timer`** (the countdown):

| Layout | Bytes the loop needs | Lines fetched | Access pattern |
|---|---|---|---|
| SoA | $300 \times 4 = 1{,}200$ | $\lceil 1200/64 \rceil = 19$ | one sequential stream |
| Objects, adjacent (best case) | 1,200 | $300 \times 32 / 64 = 150$, plus 19 for the reference array | sequential, but 8× the bytes |
| Objects, scattered (typical) | 1,200 | about 300 (one per object) + 19 | a miss per object; each line brings 4 useful bytes |

Scattered objects fetch about **sixteen times** the bytes for this loop: 64 bytes of line for every
4 bytes of `timer`.

**A loop that reads all seven fields:**

| Layout | Lines fetched |
|---|---|
| SoA | $7 \times 19 = 133$, seven sequential streams the prefetcher follows |
| Objects, adjacent | about 150 (`Mind`) + 113 (`Vec3f`) + 19 = 282 |
| Objects, scattered | about 300 + 300 + 19 ≈ 620, two dependent misses per skeleton (`Mind`, then its `home`) |

Even when every field is used, SoA reads fewer lines, because headers and references are not data.

### 4.3 What `think` really touches

`think` takes four components of each skeleton:

```java
public static void think(MonsterView m, MindMut mind, SightView sight, FlashMut flash, float dt, ...)
```

It reads `Monster` (four `int` columns: model, agent, character, index), reads and writes `Mind`
(seven 4-byte columns), reads `Sight` (two `boolean` columns, one byte each) and reads and writes
`Flash` (a `float` and an `int`). Per row, $16 + 28 + 2 + 8 = 54$ bytes; for 300 rows, 16,200 bytes,
about 254 lines, in 15 sequential streams. As objects it would be four objects per skeleton (five
with the `Vec3f`) — around 1,500 scattered lines on a cold heap.

So the engine's rule (DESIGN §4.1, D5): **data that many entities share goes in primitive arrays,
one per field.** The rest of this lesson builds the machinery that lets a game programmer write
`record Mind(...)` and a method taking `MindMut`, and get these arrays without ever writing them.

<details><summary>Check: a record <code>Health(int current, int max)</code> for 10,000 entities. How many lines does a loop that reads only <code>current</code> fetch as SoA, and roughly as scattered objects?</summary>

SoA: 40,000 bytes / 64 = 625 lines. Scattered objects: about 10,000 lines (one per object; each
`Health` is 8 + 8 = 16 bytes, so even adjacent objects would need 2,500 lines), plus 625 for
the reference array.
</details>

---

# Part B — Identity without references

## 5. Why entities are not objects

If skeletons are rows in arrays, what *is* a skeleton? Something must say "this is skeleton 7" in a
way that other code can store: the crowd system remembers which agent belongs to which skeleton,
the combat system who is attacking whom, a save file who was where.

A Java reference cannot do it:

- it would need an object per entity, which is what we are avoiding;
- if the entity is destroyed while someone holds the reference, the object lives on (the GC sees
  the reference) and the holder has no way to know it is dead;
- a reference cannot be written into a save file or sent to another thread's arrays.

The engine names an entity with an **`int` handle**. Inside, a handle is two numbers packed together
(L00 §6.4):

- a **slot index**, which is the entity's position in the entity tables, and
- a **generation**, a counter that goes up every time that slot is reused.

An entity destroyed and its slot given to a new entity gets a new generation, so old handles to the
slot no longer match. This is the same idea lesson 1 used for GPU buffers, now for gameplay.

## 6. Step 1: handles

`Handles` is a class of static methods that pack and unpack:

```java
public final class Handles {
    public static final int NULL = 0;
    public static final int INDEX_BITS = 22;
    public static final int GENERATION_BITS = 32 - INDEX_BITS;               // 10
    public static final int MAX_INDEX = (1 << INDEX_BITS) - 1;               // 4,194,303
    public static final int MAX_GENERATION = (1 << GENERATION_BITS) - 1;     // 1,023
    private static final int INDEX_MASK = MAX_INDEX;

    private Handles() {}

    public static int pack(int index, int generation) {
        return (generation << INDEX_BITS) | (index & INDEX_MASK);
    }

    public static int index(int handle) {
        return handle & INDEX_MASK;
    }

    public static int generation(int handle) {
        return handle >>> INDEX_BITS;
    }

    public static int nextGeneration(int generation) {
        return generation == MAX_GENERATION ? 1 : generation + 1;
    }
}
```

The layout is `generation (10 bits) | index (22 bits)`:

- **22 bits of index** allow 4 million live entities, more than any game here needs; **10 bits of
  generation** allow 1,023 reuses of a slot before the counter wraps.
- **`(1 << n) - 1`** is a mask of `n` ones (L00 §6.5): `MAX_INDEX` is `0x3FFFFF`.
- **Generation 0 is never used** (`nextGeneration` skips from 1,023 back to 1), so a valid handle is
  never 0, and `0` can mean "no entity" (`NULL`). Every `int` field defaults to 0 in Java, so an
  uninitialized handle is automatically `NULL`.
- `generation` uses `>>>` so that a generation of 512 or more (which sets the top bit, making the
  `int` negative) still comes back positive.

Worked example: slot 5, generation 1 → `1 << 22 | 5` = `0x00400005` = 4,194,309. After the entity
is destroyed and the slot reused: generation 2 → `0x00800005`. Both have `index` 5; they differ in
`generation`, which is how the old one is recognized as stale.

**The wrap-around risk.** After 1,023 reuses the generation comes back to a value it had before, and
a handle that old would match again. The pool below reuses freed slots in first-in-first-out order,
so a slot comes round again only after every other freed slot has; reaching the same generation
needs over a thousand rounds of that. For a game that is acceptable (a handle held that long is a
bug anyway); the 10/22 split is a trade between that margin and the number of entities.

## 7. Step 2: the handle pool

`HandlePool` hands out handles and remembers which are alive:

```java
public final class HandlePool {
    private int[] generations;                    // the current generation of each slot
    private boolean[] alive;
    private final IntRing free = new IntRing(64); // freed slots, oldest first
    private int highWater;                        // slots ever used
    private int liveCount;
    private final int maxSlots;

    public int allocate() {
        int index;
        if (!free.isEmpty()) {
            index = free.removeFirst();           // reuse the slot freed longest ago
        } else {
            if (highWater == maxSlots) {
                throw new IllegalStateException("handle pool exhausted (" + maxSlots + " slots)");
            }
            index = highWater++;                  // a brand-new slot
            if (index == generations.length) {    // grow both arrays (rare; generations start at 1)
                int newCap = Math.min(maxSlots, Math.max(index + 1, generations.length * 2));
                int old = generations.length;
                generations = Arrays.copyOf(generations, newCap);
                Arrays.fill(generations, old, newCap, 1);
                alive = Arrays.copyOf(alive, newCap);
            }
        }
        alive[index] = true;
        liveCount++;
        return Handles.pack(index, generations[index]);
    }

    public void free(int handle) {
        if (!isValid(handle)) {
            throw new IllegalArgumentException("stale or invalid handle " + Handles.toString(handle));
        }
        int index = Handles.index(handle);
        alive[index] = false;
        generations[index] = Handles.nextGeneration(generations[index]);
        free.addLast(index);
        liveCount--;
    }

    public boolean isValid(int handle) {
        int index = Handles.index(handle);
        return handle != Handles.NULL
                && index < highWater
                && alive[index]
                && generations[index] == Handles.generation(handle);
    }
}
```

(Constructors and small accessors are left out here; the file has them.)

- **The free list is a FIFO ring of `int`s** (`IntRing`, a circular array): no objects, and the
  first-in-first-out order spreads reuse over all freed slots, as §6 needed.
- **`free` bumps the generation at once**, so every outstanding handle to the slot becomes stale the
  moment the entity dies, not when the slot is reused.
- **`isValid`** checks four things: not `NULL`, a slot that exists, a slot in use, and the *same*
  generation. A handle passing all four names a living entity.
- **Arrays, not a map**: slots are small consecutive integers, so the "lookup" is an array index.

The game's entities come from `scene.EntityPool`, which wraps a `HandlePool` and adds one duty:
`destroy(entity)` first tells every registered store to remove the entity, then frees the handle.
So an entity never leaves rows behind in a store.

---

# Part C — Building the store

## 8. Step 3: the sparse set

### 8.1 What a store needs

A store holds one component for *some* entities: every skeleton has a `Mind`, but the torches do
not. It must answer two kinds of question, both fast:

- **"What is the row of entity $e$?"** — for a behaviour that receives an entity, or a loop over
  several stores. Must be $O(1)$ (L00 §2.8).
- **"Visit every row"** — for the loops. Rows must be **dense**: packed at the start of the arrays,
  no gaps, so a loop walks `[0, size)` sequentially (§2).

A plain array indexed by the entity's slot index would answer the first in one load, but the rows
would have gaps (torches' slots unused), and a loop would walk all of them. A hash map from entity
to row would keep the rows dense, but every lookup hashes, probes and compares. Entity indices are
small consecutive integers, which allows something simpler than both.

### 8.2 Two arrays

A **sparse set** keeps two `int` arrays:

- **`dense[row]`**: the members, packed: `dense[0 .. size)` are the ids in the set.
- **`sparse[id]`**: for every possible id, its row in `dense`, or −1 if it is not a member.

They point at each other. The **invariant** — the property every operation must keep true — is:

$$\text{for every member } i: \quad \texttt{dense[sparse[i]]} = i.$$

A picture, with ids 7, 2 and 5 added in that order:

```
id:       0   1   2   3   4   5   6   7   8   9
sparse:  -1  -1   1  -1  -1   2  -1   0  -1  -1        (row of each id, or -1)

row:      0   1   2
dense:    7   2   5                                    size = 3
```

`sparse` is as long as the largest id ever stored (it is the "sparse" side: mostly −1); `dense` is
as long as the number of members.

### 8.3 The operations

Start `SparseSet.java` in `dev.jeddy.core.collections`:

```java
public final class SparseSet {
    private static final int ABSENT = -1;

    private int[] sparse;
    private int[] dense;
    private int size;

    public SparseSet(int universe, int capacity) {
        sparse = new int[Math.max(1, universe)];
        Arrays.fill(sparse, ABSENT);
        dense = new int[Math.max(1, capacity)];
    }

    public int size() { return size; }
    public int[] dense() { return dense; }
```

**Lookup** is one array read, with a range check because an id beyond the array has never been
added:

```java
public boolean contains(int id) {
    return id >= 0 && id < sparse.length && sparse[id] != ABSENT;
}

public int indexOf(int id) {
    return id >= 0 && id < sparse.length ? sparse[id] : ABSENT;
}
```

**Add** appends at the end of `dense` and records the row in `sparse`:

```java
public int add(int id) {
    if (id < 0) {
        throw new IllegalArgumentException("negative id " + id);
    }
    if (id >= sparse.length) {                       // grow the sparse side to cover id
        int old = sparse.length;
        sparse = Arrays.copyOf(sparse, Math.max(id + 1, old * 2));
        Arrays.fill(sparse, old, sparse.length, ABSENT);
    }
    int existing = sparse[id];
    if (existing != ABSENT) {
        return existing;                             // already a member: keep its row
    }
    if (size == dense.length) {
        dense = Arrays.copyOf(dense, dense.length * 2);
    }
    dense[size] = id;
    sparse[id] = size;
    return size++;
}
```

Two stores and an increment: $O(1)$. The new member always gets the **last** row, which is what keeps
`dense` packed. Growth doubles (allocating), which happens while a level loads, not in steady play.

**Remove** is the clever part. Taking an element out of the middle of `dense` would leave a hole,
and shifting everything after it down would be $O(n)$. Instead, **move the last member into the
hole**:

```java
public int remove(int id) {
    int index = indexOf(id);
    if (index == ABSENT) {
        return ABSENT;
    }
    int last = dense[--size];      // the last member (and the set shrinks by one)
    dense[index] = last;           // it moves into the hole
    sparse[last] = index;          // and is told its new row
    sparse[id] = ABSENT;           // the removed id is no longer a member
    return index;
}
```

Removing 7 from the picture above: its row is 0; the last member is 5 (row 2); 5 moves to row 0;
`sparse[5]` becomes 0; `sparse[7]` becomes −1:

```
sparse:  -1  -1   1  -1  -1   0  -1  -1  -1  -1
dense:    5   2                                        size = 2
```

The invariant holds again: `dense[sparse[5]] = dense[0] = 5`, `dense[sparse[2]] = dense[1] = 2`.

**The order of the last two lines matters.** If the removed id *is* the last member (remove 2 now:
row 1, and the last member is 2 itself), the method first sets `sparse[2] = 1` (moving 2 onto itself)
and then `sparse[2] = ABSENT`. Written the other way round, the second line would undo the removal.
Exercise 3 asks you to check every case.

The price of swap-removal: **rows move**. Whatever data a store keeps per row must make the same
move, and the order of the members changes. Nothing may depend on iteration order, which is also
what later allows the loops to walk backwards (§12).

`remove` returns the row it emptied, so a caller with parallel arrays knows where to move their
data; `clear` resets only the `sparse` entries of the current members (not the whole array).

## 9. Step 4: the store's base class

Every component gets a generated store class; the parts that are the same for all of them live in
the abstract base class `components.Store`. Its job: the sparse set, the entity handle of each row,
growth, and swap-removal — and to call hooks the generated subclass implements for its columns.

```java
public abstract class Store implements EntityStore {
    private final EntityPool pool;
    private final String name;
    private final SparseSet members;   // entity index → row
    private int[] entities;            // row → the full entity handle
    private int capacity;

    protected Store(EntityPool pool, String name, int capacity) {
        this.pool = pool;
        this.name = name;
        this.capacity = Math.max(1, capacity);
        members = new SparseSet(Math.max(64, pool.highWaterMark()), this.capacity);
        entities = new int[this.capacity];
        pool.register(this);           // so that destroying an entity removes its row here
    }
```

### 9.1 Why keep the full handle per row

The sparse set is keyed by the entity's **slot index** (22 bits), not by the whole handle: indices are
small and dense, which the sparse array needs. But an index alone cannot tell the current entity in
that slot from a destroyed one. So the store also keeps `entities[row]`, the **full handle** (index and
generation) of the entity that owns the row, and every lookup compares it:

```java
public final int indexOf(int entity) {
    int row = members.indexOf(Handles.index(entity));
    return row >= 0 && entities[row] == entity ? row : -1;
}

public final boolean has(int entity) {
    return indexOf(entity) >= 0;
}

public final int entityAt(int row) {
    return entities[row];
}

public final int size() {
    return members.size();
}
```

A stale handle — same index, older generation — finds the row but fails the comparison, and the
store answers "not a member" instead of returning another entity's data. That is lesson 1's stale
buffer check, for gameplay.

The methods are `final`: a generated store cannot change them, and the JIT knows every call to them
has exactly one target (§13).

### 9.2 Adding

```java
public final int add(int entity) {
    if (!pool.isAlive(entity)) {
        throw new IllegalArgumentException(
                "stale or invalid entity " + Handles.toString(entity) + " for " + name);
    }
    int index = Handles.index(entity);
    int existing = members.indexOf(index);
    if (existing >= 0 && entities[existing] == entity) {
        return existing;               // already has the component: keep its row and values
    }
    int row = members.add(index);
    if (row == capacity) {             // the columns are full: grow them all
        capacity *= 2;
        entities = Arrays.copyOf(entities, capacity);
        growColumns(capacity);
    }
    entities[row] = entity;
    resetRow(row);                     // every column of the new row to its default
    return row;
}
```

The new row is always the last one (the sparse set appends). If that is one past the columns' end,
everything doubles together — `entities` here and every column in the subclass, through the
`growColumns` hook. `resetRow` matters because a row may have belonged to a removed entity a moment
ago and still hold its values.

### 9.3 Removing

```java
@Override
public final void remove(int entity) {
    int row = indexOf(entity);
    if (row < 0) {
        return;
    }
    int last = members.size() - 1;
    members.remove(Handles.index(entity));
    if (row != last) {
        entities[row] = entities[last];
        moveRow(last, row);            // every column: copy the last row into the hole
    }
}
```

The same swap as the sparse set, applied to the store's own arrays: `entities` here, every column
through `moveRow`. If the removed row was the last one there is nothing to move. `remove` is also
what `EntityPool.destroy` calls on every registered store (`EntityStore` is that interface).

### 9.4 The hooks and the generic API

```java
/** Reallocates every column to {@code capacity} rows, keeping the values. */
protected abstract void growColumns(int capacity);

/** Copies row {@code from} over row {@code to} in every column. */
protected abstract void moveRow(int from, int to);

/** Sets every column of {@code row} to its default. */
protected abstract void resetRow(int row);

public static Store smaller(Store a, Store b) {
    return a.size() <= b.size() ? a : b;
}
```

Below the hooks, the base class declares a **generic column API** — `columnCount()`,
`columnName(c)`, `columnType(c)`, `getInt(row, c)`, `setFloat(row, c, v)` and so on — that the level
loader, the editor's inspector and the save system use to reach columns *by name*, without
reflection. Those are cold paths (loading, editing), so a `switch` on a column number is fine there;
behaviours never use them.

## 10. Step 5: the generated columns

For `record Mind(MonsterState state, float timer, float cooldown, float corpse, Vec3f home)`, the
annotation processor writes `MindStore` when the game compiles (chapter 22 explains how a processor
works; DESIGN D105). Reading the generated file is reading the SoA layout of §4:

```java
public final class MindStore extends Store {
    int[] state;
    float[] timer;
    float[] cooldown;
    float[] corpse;
    float[] homeX, homeY, homeZ;

    public MindStore(EntityPool pool, int capacity) {
        super(pool, "Mind", capacity);
        int n = capacity();
        state = new int[n];
        timer = new float[n];
        cooldown = new float[n];
        corpse = new float[n];
        homeX = new float[n];
        homeY = new float[n];
        homeZ = new float[n];
    }
```

- **One primitive array per scalar.** The enum becomes an `int` column (its ordinal); the `Vec3f`
  becomes three `float` columns. The processor expands every record component down to primitives.
- **The fields are package-private**, not private: the generated views in the same package read them
  directly (§11), and nothing else should.

The hooks are a line per column:

```java
@Override
protected void growColumns(int capacity) {
    state = Arrays.copyOf(state, capacity);
    timer = Arrays.copyOf(timer, capacity);
    // ... cooldown, corpse, homeX, homeY, homeZ
}

@Override
protected void moveRow(int from, int to) {
    state[to] = state[from];
    timer[to] = timer[from];
    // ... the other five columns
}

@Override
protected void resetRow(int row) {
    state[row] = 0;
    timer[row] = 0.0f;
    // ... the other five columns (a record component marked @Default gets its default here)
}
```

And the convenience methods connect the record and the columns, for setup and tools:

```java
/** Gives {@code entity} the component with {@code value}'s fields and returns its row. */
public int add(int entity, Mind value) {
    int row = add(entity);
    state[row] = value.state().ordinal();
    timer[row] = value.timer();
    cooldown[row] = value.cooldown();
    corpse[row] = value.corpse();
    homeX[row] = value.home().x();
    homeY[row] = value.home().y();
    homeZ[row] = value.home().z();
    return row;
}

/** A copy of row {@code row} (allocates; tools). */
public Mind at(int row) {
    return new Mind(STATE_VALUES[state[row]], timer[row], cooldown[row], corpse[row],
            new dev.jeddy.math.Vec3f(homeX[row], homeY[row], homeZ[row]));
}
```

The **record is the cold form** of the data: what you write in a level file, what the inspector
shows, what `at` returns for a tool. The **columns are the hot form**, what loops touch. `at`
allocates (two objects) and says so; behaviours never call it. `STATE_VALUES` is
`MonsterState.values()` computed once, because `values()` returns a new array on every call.

## 11. Step 6: views — one object for every row

### 11.1 The problem views solve

A behaviour should read like ordinary code: `mind.setTimer(mind.timer() - dt)`, not
`store.timer[row] = store.timer[row] - dt` with a store and a row passed separately. But creating a
`Mind`-like object per entity per tick would allocate 9,000 objects a second for 300 skeletons — the
very thing the layout avoids.

The answer is the **flyweight** pattern: *one* object that can be pointed at any row. It holds a
reference to the store and a row number, and its getters read the columns. Before calling the
behaviour for entity $e$, the loop moves the view to $e$'s row. One view per behaviour parameter,
created once, reused forever.

### 11.2 The base and the generated view

```java
public abstract class View {
    /** The dense row, or -1 when positioned at no row (an absent {@link Optional} component). */
    protected int row = -1;

    protected View() {}

    public final boolean present() { return row >= 0; }
    public final int row() { return row; }
    public final void none() { row = -1; }
}
```

The generated read-only view:

```java
public class MindView extends View {
    MindStore store;

    /** Positions the view at {@code row} of {@code store} (-1: no row). */
    public final void at(MindStore store, int row) {
        this.store = store;
        this.row = row;
    }

    public final int entity() { return store.entityAt(row); }

    public final MonsterState state() { return MindStore.STATE_VALUES[store.state[row]]; }
    public final float timer() { return store.timer[row]; }
    public final float cooldown() { return store.cooldown[row]; }
    public final float corpse() { return store.corpse[row]; }
    public final float homeX() { return store.homeX[row]; }
    public final float homeY() { return store.homeY[row]; }
    public final float homeZ() { return store.homeZ[row]; }
}
```

(Condensed to one line per getter; the file has the usual formatting.) And the writable one, which
adds setters:

```java
public final class MindMut extends MindView {
    public void setState(MonsterState value) { store.state[row] = value.ordinal(); }
    public void setTimer(float value) { store.timer[row] = value; }
    public void setCooldown(float value) { store.cooldown[row] = value; }
    public void setCorpse(float value) { store.corpse[row] = value; }
    public void setHome(float x, float y, float z) {
        store.homeX[row] = x;
        store.homeY[row] = y;
        store.homeZ[row] = z;
    }
    // setHomeX, setHomeY, setHomeZ
}
```

### 11.3 The design choices in those few lines

- **Every getter is `final`, and `MindMut` is a `final` class.** A call `mind.timer()` therefore has
  exactly one possible target, which the JIT can inline without any type check (§13).
- **`state()` returns the enum**, looked up from the shared `values()` array by ordinal: no
  allocation, and the behaviour gets a type-safe constant.
- **`Vec3f home` has no getter returning a `Vec3f`** — that would allocate — but three float getters
  and a three-argument setter.
- **View versus Mut is a declaration.** A behaviour that only reads `Mind` takes `MindView`; one that
  writes takes `MindMut`. The processor uses this to know what each behaviour reads and writes, which
  is how it builds the dependency graph for running behaviours in parallel safely (chapter 22, D107).
- **Views must not be kept.** A view means "this entity" only during the call it is passed to: right
  after, the loop moves it to the next entity. A behaviour that stored one in a field would later
  read whatever row the loop visited last. To remember an entity, store its handle (`entity()`).

## 12. Step 7: the loop that calls a behaviour

The game programmer writes:

```java
@OnFixedUpdate(stage = 0)
public static void think(MonsterView m, MindMut mind, SightView sight, FlashMut flash,
        float dt, int entity, Enemies enemies, Party party, Crowd crowd, ...) {
    ...
    mind.setCooldown(mind.cooldown() - dt);
    ...
}
```

and the processor generates, in the game's registry class (`CryptGame`), a field per view and this
loop:

```java
private final MonsterView SkeletonAi_think_v0 = new MonsterView();
private final MindMut SkeletonAi_think_v1 = new MindMut();
private final SightView SkeletonAi_think_v2 = new SightView();
private final FlashMut SkeletonAi_think_v3 = new FlashMut();

private void SkeletonAi_think_loop() {
    Store drive = Store.smaller(Store.smaller(Store.smaller(monster, mind), sight), flash);
    float dt = this.dt;
    for (int i = drive.size() - 1; i >= 0; i--) {
        if (i >= drive.size()) {
            continue; // rows went away under the loop
        }
        int e = drive.entityAt(i);
        int r0 = monster.indexOf(e);
        if (r0 < 0) {
            continue;
        }
        int r1 = mind.indexOf(e);
        if (r1 < 0) {
            continue;
        }
        int r2 = sight.indexOf(e);
        if (r2 < 0) {
            continue;
        }
        int r3 = flash.indexOf(e);
        if (r3 < 0) {
            continue;
        }
        SkeletonAi_think_v0.at(monster, r0);
        SkeletonAi_think_v1.at(mind, r1);
        SkeletonAi_think_v2.at(sight, r2);
        SkeletonAi_think_v3.at(flash, r3);
        dev.jeddy.samples.iso.SkeletonAi.think(SkeletonAi_think_v0, SkeletonAi_think_v1,
                SkeletonAi_think_v2, SkeletonAi_think_v3, dt, e, enemies, party, crowd, animator,
                agentSync, level, rng, world);
    }
}
```

Each decision, and what it was chosen over:

- **Drive from the smallest store.** The behaviour needs entities that have *all four* components.
  The loop walks the smallest store and asks the others, so it visits at most as many entities as the
  rarest component has. (Rejected: walking every entity of the pool and testing membership in each
  store — most entities in a level are not skeletons.)
- **Look the entity up in each store.** Rows are not aligned across stores — skeleton 7 may be row 3
  of `Mind` and row 12 of `Flash` — so each store is asked for its row: two loads and a compare each
  (§9.1). When a behaviour needs only *one* component, the generator skips the lookup and uses the
  loop index as the row directly.
- **Walk backwards.** A behaviour may remove the entity it is visiting (a skeleton that dies and
  despawns). Swap-removal moves the *last* row into the visited one. Walking from the end, the last
  row has already been visited, so nothing is skipped or visited twice. (Rejected: deferring removals
  into a command buffer until after the loop — more machinery than one loop direction, D107.)
- **The `i >= drive.size()` guard.** A behaviour that removes *other* entities can shrink the store by
  more than one row; the guard skips indices that no longer exist.
- **`dt` copied into a local.** A field read inside the loop might have to be repeated after every
  call (the call could change it); a local is known not to change.
- **The views are fields, created once.** The loop allocates nothing; `at` is two field stores.
- **The behaviour is a `static` method.** No object to call it on, no virtual dispatch: the call has
  exactly one target. Singletons (`enemies`, `crowd`, …) are passed as parameters from registry
  fields (D106).

---

# Part D — Proving it

## 13. Why the view costs nothing: what the JIT does

A view looks like it adds work: an object, a method call per field, two field stores per entity.
Whether that work survives depends on the JIT compiler (L00 §9.1). This section follows a call to
`mind.timer()` through it.

### 13.1 Inlining

A **call** is expensive in a way that has little to do with the jump itself. The compiler must
assume the called method may read or write any memory, so it cannot keep values in registers across
it, cannot move loads out of a loop past it, and cannot combine work on either side of it.
**Inlining** — copying the callee's body into the caller — removes the call and lets the compiler
optimize the combined code as one piece. It is the optimization that makes the others possible.

To inline, the JIT must know **which** method a call reaches:

- **`static`, `private` and `final` methods**, and methods of `final` classes, have exactly one
  target. They are inlined if they are small enough: by default up to 325 bytes of bytecode for a hot
  method (`-XX:FreqInlineSize`), 35 for a rarely called one (`-XX:MaxInlineSize`).
- **A virtual call** (an overridable method) is inlined if the JIT has seen only **one** receiver
  class there (**monomorphic**) or two (**bimorphic**), behind a cheap check of the object's class;
  if a new class ever shows up, the compiled code is thrown away and recompiled
  (**deoptimization**).
- **Three or more classes** at one call site (**megamorphic**): the JIT gives up on inlining and
  emits a true virtual call — load the object's class, load the method's address from the class's
  method table (**vtable**), jump there indirectly. Every optimization across the call is lost.

The generated code is designed for the first case: the behaviour is `static`, the views' getters are
`final`, `MindMut` is a `final` class, and the store's lookup methods are `final`.

### 13.2 Following `mind.timer()`

For a small behaviour, the JIT inlines `at(...)`, the behaviour and its getters into the loop. In
the loop body the code is then, written as Java:

```java
v1.store = mind;                 // at(): two stores into the view object
v1.row = r1;
...
float t = v1.store.timer[v1.row];   // timer(), inlined
```

The compiler sees that `v1.store` and `v1.row` are read right after being written with no call in
between, so it uses the values it just stored instead of loading them again (**load elimination**):

```java
float t = mind.timer[r1];
```

What remains is exactly what a hand-written loop over the columns does: load the array reference
`mind.timer`, check `r1` against the array's length (**bounds check**; the JVM must throw rather
than read outside an array), load the element. The two stores into the view stay — the view is an
object in a field, other code could look at it — but stores are cheap: they do not wait for memory.

Two refinements:

- **Bounds checks.** In a loop like `for (i = 0; i < a.length; i++) a[i]`, the JIT proves `i` is in
  range and removes the check (**range check elimination**). Here the row comes from `indexOf`, so
  the check stays: a compare and a branch that is never taken, which the branch predictor learns at
  once.
- **Large behaviours.** `think` is far bigger than 325 bytes of bytecode, so it is compiled on its
  own and *called* once per skeleton. Inside it, `mind.timer()` still inlines (the getter is final
  and tiny), but `mind.store` and `mind.row` are now real loads from the view object — loads that hit
  L1, since the same view is used every iteration. That is a few nanoseconds per skeleton, next to a
  behaviour that searches the party for the nearest target. The "free" claim is exact for small
  behaviours and close to it for big ones.

### 13.3 What would break it

- **A view stored in a field, passed to code the JIT does not inline, or captured by a lambda**: its
  `store` and `row` must then be read from memory wherever they are used.
- **Calling a behaviour through an interface** (`for (System s : systems) s.update()`): megamorphic
  as soon as a few implementations exist — the shape the next section measures.
- **A view typed as `Object` or as an interface**: virtual again. The generated code never does this.

## 14. The benchmark

### 14.1 Three shapes of the same tick

[`ComponentsBench`](../../../bench/src/main/java/dev/jeddy/bench/ComponentsBench.java) runs one
trivial "tick" over 10,000 entities in three ways — "regenerate one hit point, up to the maximum":

```java
/** The object-per-entity shape. */
abstract static class Unit {
    int current;
    int max;
    abstract void update();
}
static final class Warrior extends Unit { void update() { if (current < max) current++; } }
static final class Archer  extends Unit { void update() { if (current < max) current++; } }
static final class Mage    extends Unit { void update() { if (current < max) current++; } }

@Benchmark
public int objectsWithVirtualUpdate() {           // 1. an object per entity, a virtual call each
    Unit[] o = objects;
    for (int i = 0; i < N; i++) {
        o[i].update();
    }
    return o[0].current;
}

@Benchmark
public int soaColumnsByHand() {                   // 2. two int arrays, written by hand
    int[] c = soaCurrent;
    int[] m = soaMax;
    for (int i = 0; i < N; i++) {
        if (c[i] < m[i]) {
            c[i]++;
        }
    }
    return c[0];
}

@Benchmark
public int generatedBehaviour() {                 // 3. record Health + static behaviour + registry
    game.fixedUpdate(1f / 30);
    HealthStore h = game.health();
    return h.current()[0];
}
```

The third uses a real component, `record Health(int current, int max)`, and a real behaviour:

```java
@OnFixedUpdate
public static void regen(HealthMut h) {
    int c = h.current();
    if (c < h.max()) {
        h.setCurrent(c + 1);
    }
}
```

Three classes of `Unit`, assigned in rotation (`i % 3`), make the first call site megamorphic, as a
real game with many entity types would be. `max` is a billion so the work never stops changing.

### 14.2 The numbers

From CI's short ("smoke") run on 2026-10-12 — your machine will give different absolute numbers,
and the ratios are what to compare:

| Shape | µs per tick | ns per entity |
|---|---|---|
| objects, virtual `update()` | 142 | 14.2 |
| SoA by hand | 4.8 | 0.48 |
| generated behaviour | 6.5 | 0.65 |

**The objects.** In this benchmark the 10,000 objects were allocated in one loop in `setup`, each
followed by the temporary `Health` record the same iteration creates, so they sit close together in
memory — near the friendly case of §2 (row 4). The thirty-fold gap is
therefore mostly the **megamorphic call**: no inlining, a class load, a table load and an indirect
jump per entity, and the loop cannot be unrolled or vectorized around a call. In a game, where
entities are created over time and their objects are scattered, the cache misses of §2 (row 5) come
on top.

**Hand-written against generated.** 1.7 µs over 10,000 entities is 0.17 ns per entity: a fraction of
a cycle. It is not the view (§13); it is the loop's bookkeeping — walking backwards with the
`i >= drive.size()` guard, the `entityAt(i)` the generated loop reads for every row, and the view's
two field stores. With one required component the row *is* the loop index. A behaviour that joins
several stores looks the entity up in **every** store it joins (two loads and a compare each, §9.1),
the driving store included, because which store drives is only known at run time (exercise 8 asks how
to avoid that one). That is the price of reaching rows from entities in $O(1)$, and it is the same
whether the body is written with views or by hand.

**A lesson from the first run.** The first version of the generated loop read **22 µs**, not 6.5. It
looked up the driving store's *own* row again with `indexOf`, although the loop index already was
that row: `entities[i]`, then `sparse[index]`, then `entities[row]` to compare — three loads, each
needing the previous one's result. That dependent chain (§2, row 6a in miniature, but in cache) is
the expensive shape on a modern core: the out-of-order engine cannot overlap the loads. Removing that
one lookup took the overhead over the hand-written loop from 17.2 µs to 1.7 µs, nine tenths of it.
The fix applies to single-component loops; multi-component loops like `think`'s still make that
lookup for whichever store drives.

## 15. When to use several threads

The engine's job system (chapter 01) can split a loop across worker threads, and a behaviour marked
`@Parallel` is run that way (chapter 22). Is that always faster?

### 15.1 The fixed cost

Starting parallel work costs time before any row is processed, and again after: the submitting
thread publishes the job (writes the job's counters, then a release store so workers see them),
wakes workers that are asleep (through the operating system's scheduler: microseconds each, L00
§9.4), each worker claims ranges of rows with a compare-and-set, and the submitter waits until the
completion counter says all ranges are done. Altogether, on an otherwise idle job system, of the order
of **20–50 µs** per dispatch on a desktop (chapter 22's estimate); on a 2-core machine where the game
thread is one of the two cores, worse.

### 15.2 The inequality

With $n$ rows of $t$ nanoseconds each, $p$ threads and a dispatch cost $d$:

- inline, the loop takes $n t$;
- in parallel, about $d + n t / p$.

Parallel wins when $d + nt/p < nt$, that is when

$$n t \left(1 - \frac{1}{p}\right) > d.$$

The demo's `see` behaviour (a visibility test per skeleton) is the measured example: $n = 300$, $t
\approx 50$ ns, so $nt = 15$ µs; with $p = 2$ the left side is 7.5 µs, well under any $d$. On the
2-core container the behaviours took 0.14 ms per tick in parallel against 0.11 ms inline
(`--inline-behaviours`). To pay for $d = 20$ µs at $p = 2$ and 50 ns a row, a loop needs $n > 20{,}000
/ (50 \times 0.5) = 800$ rows; at $d = 50$ µs, 2,000.

That is why the animation update parallelizes (each character's work is microseconds) and the crowd
does (hundreds of nanoseconds per agent, on eight cores), while `see` is kept parallel only as the
worked example. **Parallelize work, not loops** — and measure, because $d$ and $t$ differ on every
machine.

## 16. Things to run

- `./gradlew :bench:jmh "-Pjmh.includes=ComponentsBench"`: the three shapes. Change `N` in the source
  to 1,000 and to 1,000,000 and watch the object variant change as the objects outgrow the caches.
- Add `-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining` to the benchmark's JVM arguments and find
  `HealthView::current` (declared `final` in the view; `HealthMut` only adds setters) and
  `Regen::regen` marked as inlined, and `Unit::update` as a virtual call.
- `./gradlew :iso-demo:run --args="--headless --perf 20 --warmup 6"`, with and without
  `--inline-behaviours`: the "Component behaviours" row of the report.
- The cache experiment of §2: exercise 1.

## 17. Exercises

**1. The cache experiment.** Write the program of §2 as a single-file Java program and run it with
`java CacheDemo.java` (Java runs a single source file directly). Use the pointer chase too.

<details><summary>Solution</summary>

```java
import java.util.Random;

public class CacheDemo {
    static final class Box { int v; Box(int v) { this.v = v; } }

    public static void main(String[] args) {
        int n = 1 << 24;
        int[] data = new int[n];
        int[] order = new int[n];
        int[] seqOrder = new int[n];
        for (int i = 0; i < n; i++) { data[i] = i & 1023; order[i] = i; seqOrder[i] = i; }
        Random r = new Random(1);
        for (int i = n - 1; i > 0; i--) {           // shuffle (Fisher–Yates)
            int j = r.nextInt(i + 1);
            int t = order[i]; order[i] = order[j]; order[j] = t;
        }
        Box[] boxes = new Box[n];
        for (int i = 0; i < n; i++) boxes[i] = new Box(i & 1023);
        Box[] shuffled = new Box[n];
        for (int i = 0; i < n; i++) shuffled[i] = boxes[order[i]];
        int[] next = new int[n];                    // one cycle through all elements, shuffled
        for (int i = 0; i < n; i++) next[order[i]] = order[(i + 1) % n];
        int[] seqNext = new int[n];                 // the same cycle in address order
        for (int i = 0; i < n; i++) seqNext[i] = (i + 1) % n;

        for (int round = 0; round < 6; round++) {  // the first rounds warm up the JIT
            long t0 = System.nanoTime();
            long s = 0;
            for (int i = 0; i < n; i++) s += data[i];                 // 1
            long t1 = System.nanoTime();
            for (int i = 0; i < n; i++) s += data[seqOrder[i]];       // 2
            long t2 = System.nanoTime();
            for (int i = 0; i < n; i++) s += data[order[i]];          // 3
            long t3 = System.nanoTime();
            for (int i = 0; i < n; i++) s += boxes[i].v;              // 4
            long t4 = System.nanoTime();
            for (int i = 0; i < n; i++) s += shuffled[i].v;           // 5
            long t5 = System.nanoTime();
            int p = 0;
            for (int i = 0; i < n; i++) p = next[p];                  // 6a
            long t6 = System.nanoTime();
            int q = 0;
            for (int i = 0; i < n; i++) q = seqNext[q];               // 6b
            long t7 = System.nanoTime();
            System.out.printf("1: %.2f  2: %.2f  3: %.2f  4: %.2f  5: %.2f  6a: %.2f  6b: %.2f ns (%d)%n",
                    (t1 - t0) / (double) n, (t2 - t1) / (double) n, (t3 - t2) / (double) n,
                    (t4 - t3) / (double) n, (t5 - t4) / (double) n, (t6 - t5) / (double) n,
                    (t7 - t6) / (double) n, s + p + q);
        }
    }
}
```

Run it with enough heap (`java -Xmx3g CacheDemo.java`). Expect the ordering of §2's table; the exact
numbers depend on your CPU, memory and operating system. Printing `s + p` keeps the JIT from
deleting loops whose results are unused.
</details>

**2. A transform component.** `record Transform(Vec3f position, Quatf rotation, Vec3f scale)` as a
component: how many columns, how many bytes per row, and how many cache lines does a loop that reads
only `position` touch for 10,000 rows? Compare with scattered objects.

<details><summary>Solution</summary>

Ten `float` columns (3 + 4 + 3), 40 bytes per row. Position is three columns: $3 \times 40{,}000 =
120{,}000$ bytes, 1,875 lines, three sequential streams. As objects, each entity is a `Transform`
(12 + 3 references × 4 = 24 bytes) pointing to a `Vec3f` (24 bytes): reading the position is two
dependent misses per entity when scattered, about 20,000 lines plus the reference array.
</details>

**3. Swap-removal, every case.** For a sparse set holding 7, 2, 5 (rows 0, 1, 2), apply `remove(2)`,
then `remove(5)`, then `remove(9)`, writing both arrays after each, and check the invariant. Then swap
the last two lines of `remove` and redo `remove(5)` when 5 is the last member: what goes wrong?

<details><summary>Solution</summary>

`remove(2)`: row 1, last member 5 moves to row 1: `dense = [7, 5]`, `sparse[5] = 1`, `sparse[2] = −1`.
`remove(5)`: row 1, and 5 is the last member: `dense[1] = 5` (itself), `sparse[5] = 1`, then
`sparse[5] = −1`; `dense = [7]`. `remove(9)`: `indexOf(9)` is −1 (9 is beyond `sparse` or −1): nothing
happens, returns −1. The invariant holds after each.

Swapped, the last two lines become `sparse[id] = ABSENT; sparse[last] = index;`. When `id == last`,
the second line writes `sparse[5] = 1` back: 5 still looks like a member (`contains(5)` is true) at
row 1, which is now past `size`. A later `add` would put another id in row 1, and 5 would appear to
own it.
</details>

**4. Why compare the handle.** `Store.indexOf` checks `entities[row] == entity` after the sparse
lookup. Construct a sequence of `create`, `add`, `destroy`, `create`, `add` after which, without the
comparison, `indexOf` of the first entity would return the second entity's row.

<details><summary>Solution</summary>

`e1 = pool.create()` (slot 0, generation 1); `store.add(e1)` (row 0). `pool.destroy(e1)`: the store
removes row 0, slot 0's generation becomes 2. `e2 = pool.create()` reuses slot 0 (generation 2);
`store.add(e2)` gives it row 0. Now `indexOf(e1)`: `members.indexOf(index(e1)) = members.indexOf(0) =
0`, and only `entities[0] == e1` (false: it holds `e2`) prevents returning row 0. A system that kept
`e1` around would otherwise read and write `e2`'s data.
</details>

**5. When does `@Parallel` pay?** With $d = 30$ µs and $p = 8$, how many rows of 50 ns does a
behaviour need before running it in parallel helps? And of 500 ns?

<details><summary>Solution</summary>

$n > d / (t (1 - 1/p)) = 30{,}000 / (50 \times 0.875) \approx 686$ rows; at 500 ns, about 69 rows.
</details>

**6. One subclass.** Change the benchmark so every `Unit` is a `Warrior`, and run it again. What
changes, and what gap remains?

<details><summary>Solution</summary>

The call site becomes monomorphic, the JIT inlines `update()`, and the object loop gets much closer
to the others. What remains is the layout: each element costs a reference load and then a load from
the object, and each 64-byte line holds four 16-byte `Unit` objects (8 of them header) instead of
sixteen `int`s per column. (Measure it: the size of that remainder is the point of the exercise.)
</details>

**7. A store by hand.** Without the processor, write `HealthStore extends Store` with two `int`
columns and the three hooks, then a test that adds three entities, removes the middle one, checks that
the last one's values moved into its row, and checks that a destroyed entity's handle is no longer
found.

<details><summary>Hint</summary>Copy the shape of `MindStore` (§10); the base class does the sparse
set and the entity array. Construct it with an `EntityPool` so `destroy` reaches it.</details>
<details><summary>Solution</summary>

```java
final class HealthStore extends Store {
    int[] current;
    int[] max;

    HealthStore(EntityPool pool, int capacity) {
        super(pool, "Health", capacity);
        current = new int[capacity()];
        max = new int[capacity()];
    }

    @Override protected void growColumns(int capacity) {
        current = Arrays.copyOf(current, capacity);
        max = Arrays.copyOf(max, capacity);
    }
    @Override protected void moveRow(int from, int to) {
        current[to] = current[from];
        max[to] = max[from];
    }
    @Override protected void resetRow(int row) {
        current[row] = 0;
        max[row] = 0;
    }
    // ... and the generic column API (columnCount, columnName, getInt, ...) the base declares
}
```

The test: `a`, `b`, `c` from `pool.create()`, added with `current` 1, 2, 3; `store.remove(b)`; then
`store.indexOf(c)` is 1 (it moved into `b`'s row) and `current[1]` is 3; `pool.destroy(a)` and
`store.indexOf(a)` is −1; `store.size()` is 1. Compare your store with a generated one in the
build directory of any module that has components.
</details>

**8. The driving store's own lookup.** In `SkeletonAi_think_loop` (§12) the store that drives the
loop is also looked up with `indexOf`, although its row is `i`. The generator cannot simply write
`int r1 = i` for `mind`, because which store drives is decided at run time by `Store.smaller`. What
could it generate instead, what does that cost per row, and what does it save?

<details><summary>Solution</summary>

For each joined store, `int rK = drive == storeK ? i : storeK.indexOf(e);`. The comparison of two
references costs almost nothing and its branch goes the same way for every row of a loop, so the
branch predictor learns it at once. For the one store that drives, it saves `indexOf`'s two
dependent loads and its comparison (`sparse[index]`, then `entities[row]`) — the chain §14.2
measured, minus the `entityAt(i)` the loop needs anyway.
Whether it pays for `think`, which does far more work per row, is a question for a benchmark with
several components; the change would go in `RegistryGen.match`.
</details>

## 18. Glossary

- **AoS / SoA:** array of structures (an object per entity); structure of arrays (an array per field)
  (§4.1).
- **Bounds check, range check elimination:** the JVM's array index check; the JIT removing it when it
  can prove the index is in range (§13.2).
- **Bimorphic, monomorphic, megamorphic:** a call site that has seen two, one, or three or more
  receiver classes (§13.1).
- **Deoptimization:** discarding compiled code whose assumptions became false (§13.1).
- **Dense, sparse:** the packed member array and the id-indexed row array of a sparse set (§8.2).
- **Dependent load:** a load whose address comes from a previous load (§2).
- **Dispatch cost:** the fixed time to start and finish parallel work (§15.1).
- **Flyweight, view:** one object reused to read any row (§11).
- **Generation:** the counter in a handle that makes stale handles fail (§6).
- **Handle, slot index:** an `int` naming an entity; its position in the tables (§6).
- **Inlining:** copying a callee's body into the caller (§13.1).
- **Invariant:** a property every operation keeps true (§8.2).
- **Load elimination:** reusing a value just stored instead of loading it again (§13.2).
- **Stale handle:** a handle whose entity was destroyed (§6).
- **Swap-removal:** removing by moving the last element into the hole (§8.3).
- **Vtable:** a class's table of method addresses for virtual calls (§13.1).

*Code this lesson builds or quotes: `core.handle.Handles`, `HandlePool`, `core.collections.SparseSet`,
`scene.EntityPool`, `components.Store`, `View`, the generated `MindStore`, `MindView`, `MindMut` and
`CryptGame`, `SkeletonAi.think`, `bench.ComponentsBench`, `bench.components.Regen`. Decisions: D5,
D105, D106, D107 in [DESIGN §17](../../DESIGN.md); handles in DESIGN §4.1.*
