# Lesson 3 — Data layout: cache lines, struct of arrays, sparse sets and the free flyweight

*A lesson from M22. Chapter [22 Components](../22-components.md) says the generated stores are
"struct of arrays" and that a view "inlines away"; this lesson derives both from how a CPU reads
memory and how the JIT compiles a call, with the demo's `Mind` component as the running example,
and ends in the benchmark that checks the claims. Read chapter [01](../01-architecture.md) §4
and [12](../12-java-performance.md) first.*

## 1. Memory is read in lines

A CPU does not read a byte; it reads a **cache line**, 64 bytes on every x86 and ARM core you
will meet, into a hierarchy of caches: L1 (32–48 KB per core, ~4 cycles), L2 (0.5–2 MB, ~14
cycles), L3 (shared, tens of MB, ~40 cycles), and beyond it main memory at ~200–300 cycles, a
hundred nanoseconds. A load that misses every cache costs as much as a few hundred arithmetic
instructions. The whole craft of data layout is to make the lines you fetch contain the bytes
you need, and to fetch them in an order the **prefetcher** can predict (it watches for strides
and fetches ahead; sequential walks are nearly free, pointer chasing never is).

Two numbers to carry: a line is 64 bytes, and an `int` or a `float` is 4 bytes, so a line holds
sixteen of them.

## 2. An object per entity against an array per field

The demo's skeleton state is seven numbers:

```java
public record Mind(MonsterState state, float timer, float cooldown, float corpse, Vec3f home) {}
```

**Array of structures (AoS).** Make it an object per skeleton. With compressed class pointers a
HotSpot object has a 12-byte header (8 with compact object headers,
`-XX:+UseCompactObjectHeaders`); seven 4-byte fields make 40 bytes, already a multiple of 8, so
each `Mind` object is 40 bytes either way. 300 skeletons are 12,000 bytes, 187.5 lines *if* the
objects were contiguous — and they are not: each was allocated when its skeleton spawned, among
everything else allocated then, so they are 300 separate places on the heap reached through 300
pointers from an array. A tick that only reads `timer` fetches a line per object: 300 lines,
19,200 bytes of traffic for 1,200 bytes of data, and no stride for the prefetcher.

**Structure of arrays (SoA).** Make it seven arrays. A tick that reads every `timer` walks one
`float[300]`: 1,200 bytes, $1200 / 64 = 18.75$, nineteen lines, sequential. A tick that reads
all seven fields walks seven arrays: $7 \times 1200 = 8400$ bytes, 131 lines, seven sequential
streams the prefetcher follows. The arithmetic:

$$\text{bytes fetched}_{AoS} = \text{rows} \times 64 \quad (\text{one line per object, any field})$$

$$\text{bytes fetched}_{SoA} = \text{rows} \times 4 \times \text{fields touched}$$

For one field over 300 rows that is 19,200 against 1,200 — sixteen to one, the ratio of a line
to a float. For all seven fields it is 19,200 against 8,400, still better than two to one, and
sequential. This is why every store the processor generates is SoA, and why a behaviour that
takes `MindView` but reads only `timer` costs what one array costs: the view does not drag the
other six fields through the cache.

Worked out for the demo's `think` loop (exercise 3 of the chapter): it reads `Monster` (four
`int` columns), `Mind` (seven), `Sight` (two `boolean` columns, one byte each) and writes `Flash`
(two): $300 \times (4 + 7 + 2) \times 4 + 300 \times 2 \times 1 = 16{,}200$ bytes, about 253 lines,
plus the sparse-set arrays. As objects it would be at least $300 \times 4$ objects — one per
component — twelve hundred lines, and twelve hundred cache misses on a cold heap.

## 3. The sparse set, derived

A store needs "the row of entity $e$" in constant time and dense rows for the loops. A
**sparse set** keeps two arrays over the entity index $i = \text{index}(e)$:

- `sparse[i]` — the dense row of $i$, or −1;
- `dense[row]` — the index at that row;

with the invariant `dense[sparse[i]] == i` for every member. The operations:

- *contains* $i$: `sparse[i] != -1` — one load.
- *add* $i$: `dense[size] = i; sparse[i] = size; size++` — two stores, $O(1)$, and the new row is
  the last one.
- *remove* $i$: let $r = $ `sparse[i]`, $l = $ `size - 1`; move `dense[l]` into row $r$ and update
  its `sparse`; clear `sparse[i]`; `size--` — four stores, $O(1)$, at the price that row $l$ moved
  to $r$. Every column must move its element too (the generated `moveRow`).

A hash map would give the same $O(1)$ with hashing, probing and a load factor; a sparse set is
two array loads because the keys are small integers, which entity indices are by construction
(D4: a handle is an index and a generation). The generation is what makes a stale handle fail:
the store keeps the full handle per row and `indexOf` compares it, so an entity destroyed and
its index reused does not find the old row.

Iteration order is the order of insertion until the first removal, and arbitrary after. Nothing
in a behaviour may depend on it, which is also why the generated loop may walk backwards (chapter
22 §2.3).

## 4. Why the flyweight is free

The view is an object with two fields, `store` and `row`. The behaviour reads `mind.timer()`.
Written out, that is four loads and a bounds check:

```
aload   mind            ; the view
getfield store          ; MindStore
getfield timer          ; float[]
aload   mind
getfield row            ; int
faload                  ; the element, with the bounds check
```

It looks like an object per access. It is not, after the JIT:

1. **The call is monomorphic.** `timer()` is declared `final` in `MindView`; the only subclass,
   `MindMut`, adds setters. C2 sees one target and *inlines* the body into the behaviour, and the
   behaviour (a static method called from one generated loop) into the loop. There is no call.
2. **The view does not escape.** It is a field of the registry, repositioned by `at(store, row)`
   before each call. Inside the inlined loop body the compiler knows `store` and `row` are the
   values just stored; it keeps them in registers (load elimination across the inlined
   `at()`/`timer()` pair) rather than reloading them from the object.
3. **What is left** is `store.timer[row]`: the array reference loaded once per iteration (or
   hoisted, since `timer` does not change inside the body), the index, the bounds check (which C2
   eliminates when it can prove the range, and otherwise costs a compare and a predicted branch),
   and the element load. That is exactly the hand-written `timer[i]`.

The benchmark `ComponentsBench` ([`bench/`](../../../bench/src/main/java/dev/jeddy/bench/ComponentsBench.java))
has the three shapes over 10,000 entities: `objectsWithVirtualUpdate` (142 µs on CI's smoke
run), `soaColumnsByHand` (4 µs) and `generatedBehaviour` (22 µs). The first is slow for two
reasons that compound: the objects are scattered (§2) and the call site sees three classes
(`Warrior`, `Archer`, `Mage`), so the inline cache gives up (a *megamorphic* call: a type check
that fails, then a vtable load and an indirect jump the branch predictor cannot learn). Between
the last two lie 18 µs, about two nanoseconds a row: the entity indirection (`entityAt`, a load
that the next loads depend on), the size guard, the view's two field stores, and in the demo's
loops the membership tests of the other stores (`indexOf`: two loads and a compare each). That
is the price of a *sparse set* — rows reachable from an entity in $O(1)$ — over a dense array
that is simply indexed, and it is the same whether the body is a view or hand-written. The body
is free; the query is not, and it is two nanoseconds. Run it:
`./gradlew :bench:jmh -Pjmh.includes=ComponentsBench`.

**What breaks the argument.** A view that *escapes* — stored in a field, passed to a method the
JIT does not inline, captured by a lambda — stays an object, and its loads stay loads. A
behaviour that is too large to inline (C2's default limit is 325 bytecodes for a hot method)
costs one static call per row, still cheap, still monomorphic. A `View` used through a
`Object`-typed or interface-typed variable would be virtual; the generated code never does that.

## 5. The fixed cost of a job

Chapter 22 §2.5 measured the parallel visibility behaviour at 0.14 ms against 0.11 ms inline.
Where does a dispatch's cost come from? Submitting a job graph means writing the nodes' counts,
publishing them to the workers (a release store, then waking a worker that sleeps on a park —
tens of microseconds if it has to be scheduled by the OS), each worker claiming ranges through a
compare-and-set, and the submitter waiting on a counter with the same cost in reverse. A single
`parallelFor` on an idle job system is of the order of 10–50 µs end to end on a desktop; on a
2-core container with the game thread as one of the two, it is worse, because the "worker" is
the other core and the game thread spins or parks meanwhile.

So the rule, in numbers: with $n$ rows of $t$ nanoseconds each and a dispatch cost $d$, running
on $p$ threads takes about $d + n t / p$ against $n t$ inline, and parallel wins when

$$n t \left(1 - \frac{1}{p}\right) > d.$$

For the visibility test $n = 300$, $t \approx 50$ ns, so $nt = 15\ \mu s$; with $p = 2$ the
left side is 7.5 µs, under any $d$. It would take $n \approx 2{,}000$ rows at $p = 2$, or $t
\approx 300$ ns of real work per row, to pay for a 20 µs dispatch. The animation update (chapter
09) parallelizes because its per-character work is microseconds, not nanoseconds; the crowd
(chapter 11) because ORCA is hundreds of nanoseconds per agent with 300 agents and the game has
eight cores to spread them on. Parallelize work, not loops.

## 6. Things to run

- `./gradlew :bench:jmh -Pjmh.includes=ComponentsBench`: the three shapes. Change `N` in the
  source to 1,000 and 100,000 and watch the object variant fall off a cliff when the objects
  outgrow L2.
- `./gradlew :iso-demo:run --args="--headless --perf 20 --warmup 6"` with and without
  `--inline-behaviours`: the behaviours row.
- `-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining` on the benchmark (JMH's `-jvmArgs`):
  find `MindView::timer` in the output marked `inline (hot)`.
- `-XX:+PrintAssembly` needs the hsdis disassembler; with it, the compiled `generatedBehaviour`
  loop shows no call instruction in its body.

## 7. Exercises

1. A `record Transform(Vec3f position, Quatf rotation, Vec3f scale)` as a component: how many
   columns, how many bytes per row, how many lines does a loop that reads only `position` touch
   for 10,000 rows? *Hint: §2's formulas; ten floats per row.*
2. The sparse set's `remove` moves the last row. Write the four array stores and show the
   invariant `dense[sparse[i]] == i` holds for the moved entity afterwards. *Hint: §3.*
3. Why does `Store.indexOf` compare the stored handle with the queried one after the sparse
   lookup? Construct the sequence of `create`, `add`, `destroy`, `create` that would otherwise
   return a wrong row. *Hint: the generation in a handle (D4).*
4. Take the benchmark's `Unit` hierarchy down to one subclass and run again: the virtual call
   becomes monomorphic and inlines. How much of the gap closes, and what remains? *Hint: §2 —
   the objects are still scattered.*
5. With $d = 30\ \mu s$ and $p = 8$, how many rows of 50 ns does a behaviour need before
   `@Parallel` helps? And of 500 ns? *Hint: §5's inequality.*

---

*Code this lesson quotes: `components.Store`, the generated `MindStore`/`MindView`/`MindMut`,
`core.collections.SparseSet`, `bench.ComponentsBench`. Decisions: D4, D105, D107 in
[DESIGN §17](../../DESIGN.md).*
