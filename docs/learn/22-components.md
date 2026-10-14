# 22 — Components and behaviours: a gameplay API generated at compile time

*A game is full of things of the same kind that all do the same thing every tick: three hundred
skeletons that look for the party, sixteen fireballs that fly and explode. This chapter builds the
way Jeddy lets you write such things: a component is a plain Java `record`, a behaviour is a
`static` method whose parameters say which components it needs, and a program that runs inside the
compiler turns the two into stores with one array per field, flyweight views, loops that walk the
smallest store backwards, job graphs whose edges come from what each behaviour reads and writes, a
registry with every entry point the game calls, and a JSON schema for the editor. It builds, from
empty files, the annotations, the runtime they rest on, the annotation processor and its three
generators, and the build wiring; then it reads the demo's skeletons and fireballs through the
generated code line by line, walks the tests, and ends with the measurements that showed where a
parallel loop pays and where it does not.*

**Before you start.** You need [Lesson 0](lessons/00-foundations.md): big-O (§2.8), two's
complement and sign extension (§5), masks, shifts and packing (§6), object headers, arrays and the
zero-allocation rule (§8.2), caches, cache lines and dependent loads (§9.2–9.3), threads and
happens-before (§9.4–9.5). [Lesson 3](lessons/03-data-layout.md) is the companion of this chapter
and is assumed from here on: it measures what memory layout costs (§2–4), builds handles, the sparse
set, `Store`, the generated store, the views and the loop (§6–12), explains why the view is free
after inlining (§13), runs `ComponentsBench` (§14) and derives when a parallel loop pays (§15).
From the chapters: [01](01-architecture.md), on the game loop, the job system and its graphs, and
the iso demo's tick, which this chapter plugs into; [12](12-java-performance.md), on the JIT
(tiers, inlining limits, constants, escape analysis), the allocation assertion and lock-free
handoffs, which §8 leans on. [Lesson 4](lessons/04-binary-formats.md) builds the `ByteWriter` and
`ByteReader` the snapshots of §10 write into. Chapters [14](14-editor.md), on the level editor,
and [16](16-saves.md), on saves, are the two users of this chapter's output and are linked where
they meet it; chapter [15](15-rules.md), on the rules, raises the signal the demo's skeletons react
to.

**What you will build**

| Piece | File | What it does |
|---|---|---|
| The annotations | [`components.Component`](../../modules/components/src/main/java/dev/jeddy/components/Component.java), [`Behaviour`](../../modules/components/src/main/java/dev/jeddy/components/Behaviour.java), [`Components`](../../modules/components/src/main/java/dev/jeddy/components/Components.java), the field hints, the phases, [`Optional`](../../modules/components/src/main/java/dev/jeddy/components/Optional.java), [`Without`](../../modules/components/src/main/java/dev/jeddy/components/Without.java), [`Parallel`](../../modules/components/src/main/java/dev/jeddy/components/Parallel.java), [`ThreadSafe`](../../modules/components/src/main/java/dev/jeddy/components/ThreadSafe.java) | the whole schema language: how a component, a behaviour and a game are declared |
| The runtime | [`components.Store`](../../modules/components/src/main/java/dev/jeddy/components/Store.java), [`View`](../../modules/components/src/main/java/dev/jeddy/components/View.java), [`EntityIds`](../../modules/components/src/main/java/dev/jeddy/components/EntityIds.java), [`Levels`](../../modules/components/src/main/java/dev/jeddy/components/Levels.java) | what the generated code is built on: the store base with its generic column API and snapshots, the view base, stable ids, level rows into stores |
| The processor | [`ComponentProcessor`](../../tools/component-processor/src/main/java/dev/jeddy/components/processor/ComponentProcessor.java), [`Model`](../../tools/component-processor/src/main/java/dev/jeddy/components/processor/Model.java) | runs inside javac: reads the annotated declarations, rejects wrong shapes with a message at the element, builds a model |
| The generators | [`StoreGen`](../../tools/component-processor/src/main/java/dev/jeddy/components/processor/StoreGen.java), [`RegistryGen`](../../tools/component-processor/src/main/java/dev/jeddy/components/processor/RegistryGen.java), [`SchemaGen`](../../tools/component-processor/src/main/java/dev/jeddy/components/processor/SchemaGen.java) | write `<Name>Store`, `<Name>View`, `<Name>Mut`, the registry and `META-INF/jeddy/components.json` |
| The build wiring | [`dev.jeddy.game.gradle.kts`](../../build-logic/src/main/kotlin/dev.jeddy.game.gradle.kts), [`dev.jeddy.assets.gradle.kts`](../../build-logic/src/main/kotlin/dev.jeddy.assets.gradle.kts), [`Assets.kt`](../../build-logic/src/main/kotlin/dev/jeddy/gradle/Assets.kt), the processor's [`META-INF`](../../tools/component-processor/src/main/resources/META-INF/services/javax.annotation.processing.Processor) | puts the processor on javac's processor path, hands the schema to the cooker (`--components`), generates the level bindings |
| The demo | [`Mind`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/Mind.java), [`Monster`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/Monster.java), [`Sight`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/Sight.java), [`Flash`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/Flash.java), [`Fireball`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/Fireball.java), [`SkeletonAi`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/SkeletonAi.java), [`Fireballs`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/Fireballs.java) | five records and two behaviour classes that replaced two classes of hand-wired arrays |
| The tests and the benchmark | [`GeneratedGameTest`](../../modules/components/src/test/java/dev/jeddy/components/GeneratedGameTest.java), [`ComponentProcessorTest`](../../tools/component-processor/src/test/java/dev/jeddy/components/processor/ComponentProcessorTest.java), [`ComponentsBench`](../../bench/src/main/java/dev/jeddy/bench/ComponentsBench.java) | the sample game compiled through the processor; the diagnostics through the Compiler API; three shapes of one tick |

The generated sources are not in the repository. A build writes them under
`build/generated/sources/annotationProcessor/java/main/` of the module that declares the records
(for the demo, `samples/iso-demo/build/generated/sources/annotationProcessor/java/main/dev/jeddy/samples/iso/`),
and the schema into the module's class output. Every generated excerpt in this chapter was copied
from the processor's output for the demo on 2026-10-14; read yours next to it.

**Contents**

- Part A — The ideas
  1. [The problem, and what you can write now](#1-the-problem-and-what-you-can-write-now)
  2. [The shape: records, static methods, one registry](#2-the-shape-records-static-methods-one-registry)
  3. [The data layout, in one paragraph](#3-the-data-layout-in-one-paragraph)
  4. [Code generation at compile time: how javac runs a processor](#4-code-generation-at-compile-time-how-javac-runs-a-processor)
  5. [The query, the loop and its cost](#5-the-query-the-loop-and-its-cost)
  6. [Parallel behaviours: hazards, the dependency rule and the dispatch cost](#6-parallel-behaviours-hazards-the-dependency-rule-and-the-dispatch-cost)
  7. [Phases, stages and signals](#7-phases-stages-and-signals)
  8. [What the generated code costs: the JVM's view](#8-what-the-generated-code-costs-the-jvms-view)
- Part B — Building it
  9. [Step 1: the annotations](#9-step-1-the-annotations)
  10. [Step 2: `View`, and what `Store` adds beyond Lesson 3](#10-step-2-view-and-what-store-adds-beyond-lesson-3)
  11. [Step 3: `EntityIds`](#11-step-3-entityids)
  12. [Step 4: `Levels`](#12-step-4-levels)
  13. [Step 5: the processor's model](#13-step-5-the-processors-model)
  14. [Step 6: `ComponentProcessor`](#14-step-6-componentprocessor)
  15. [Step 7: `StoreGen`](#15-step-7-storegen)
  16. [Step 8: `RegistryGen`](#16-step-8-registrygen)
  17. [Step 9: `SchemaGen`](#17-step-9-schemagen)
  18. [Step 10: the build wiring](#18-step-10-the-build-wiring)
- Part C — Using it and proving it
  19. [The demo: five records and two behaviour classes](#19-the-demo-five-records-and-two-behaviour-classes)
  20. [Levels, the inspector and the cooker](#20-levels-the-inspector-and-the-cooker)
  21. [Snapshots and saves](#21-snapshots-and-saves)
  22. [The tests, walked through](#22-the-tests-walked-through)
  23. [Measured numbers](#23-measured-numbers)
  24. [Pitfalls met on the way](#24-pitfalls-met-on-the-way)
  25. [Things to run](#25-things-to-run)
- [Exercises](#exercises)
- [Glossary](#glossary)

---

# Part A — The ideas

## 1. The problem, and what you can write now

Before milestone M22, every gameplay system in the demo was a set of arrays wired by hand.
`Enemies` kept fifteen per-skeleton arrays (`agent`, `root`, `model`, `state`, `visible`,
`character`, `timer`, `homeX`, `homeY`, `homeZ`, `flash`, `style`, `hidden`, `cooldown`, `corpse`),
plus a table from crowd agent to skeleton; `Spells` kept eleven per-fireball arrays. That layout is
exactly the struct of arrays Lesson 3 measures, and it was fast. What it lacked was *meaning*:
nothing outside those two classes knew what a skeleton was beyond a transform and a renderable. The
editor could not show one. The save system, which came next (chapter [16](16-saves.md), on saves),
would have had to learn each class by hand. Adding a system meant writing arrays, a loop, growth
code and lookups again, and getting the swap-removal right again.

Unity answers this with `MonoBehaviour`: subclass it, override `Update()`, drag the component onto
an object in the inspector. Jeddy answers it with the same *surface* and a different *engine*. On
2026-10-12 the owner asked for "a unified API for basic behaviour, generated by an annotation
processor to generate efficient code, not an inheritance model". So a component is a `record`, a
behaviour is a `static` method, and an **annotation processor** (a program that javac runs while
it compiles your code, §4) turns the two into stores, views, loops, job graphs, a registry and a
schema. What runs at 30 Hz is static calls over primitive arrays: nothing reflects, nothing is
virtual, nothing allocates.

This is what you write in the demo. A component:

```java
// samples/iso-demo/src/main/java/dev/jeddy/samples/iso/Sight.java
@Component(doc = "Seen by the party, and hidden in the world")
public record Sight(@Default("true") boolean visible, boolean hidden) {}
```

A behaviour, with the three component views as its query and two engine objects as its tools:

```java
// samples/iso-demo/src/main/java/dev/jeddy/samples/iso/SkeletonAi.java
    @OnFixedUpdate(stage = 1)
    @Parallel(grain = 32)
    public static void see(
            MonsterView m,
            MindView mind,
            SightMut sight,
            @ThreadSafe Visibility vis,
            @ThreadSafe Crowd crowd) {
        boolean v =
                mind.state() != MonsterState.DEAD
                        && vis.isVisible(crowd.x(m.agent()), crowd.z(m.agent()));
        sight.setVisible(v);
    }
```

And one line on the package, `@Components(name = "CryptGame", capacity = 512)` in
`package-info.java`. The game then calls `game.fixedUpdateStage(1, dt)` once a tick, and the 300
skeletons of the performance scene are tested against the fog-of-war grid on the job system, by
code you never wrote.

What the milestone produced:

- `modules/components`: the annotations, `Store` (a sparse set with one array per column), `View`,
  the stable ids and snapshot I/O (into `core.io.ByteWriter`, since M16) and the level-row loader.
- `tools/component-processor`: the processor, JDK only.
- The demo's skeletons and fireballs as five records and two behaviour classes; the perf capture
  still at 0 bytes per frame, with a new row that times the behaviours.
- The editor's inspector shows an object's components from the schema the processor writes; the
  `.jlevel` carries their rows (format version 2); the cooker validates them; `CryptLevel.PLACEMENT_CHEST`
  names what the editor placed.
- Every store writes and reads itself: the data layer the saves of M16 were then built on.

## 2. The shape: records, static methods, one registry

Three declarations make a game's gameplay layer. Read them once as vocabulary; Part B builds the
machinery behind each word.

**A component is a public, top-level `record` marked `@Component`.** Its record components (the
fields) may be `int`, `long`, `float`, `boolean`, an `enum`, `Vec2f` or `Vec3f`; an `int` may be
marked `@EntityRef` (it holds an entity handle) or `@AssetRef("mesh")` (an asset handle of that
kind). `@Default("...")` gives a field its starting value; `@Range`, `@Angle`, `@Color` and `@Doc`
are hints for the editor. The record is the **cold form** of the data: what you pass to `add`,
what `get` returns to a tool, what the inspector edits. The processor generates three classes
beside it, in the same package: `<Name>Store`, the **hot form**, holding one primitive array per
scalar (an enum as its ordinal, a `Vec3f` as three `float` columns); `<Name>View`, a **flyweight**
that reads one row; and `<Name>Mut`, the view with setters.

**A behaviour is a `public static void` method in a `final` class marked `@Behaviour`, with one
phase annotation.** Its parameters are classified by type and name:

| Parameter | Meaning |
|---|---|
| `XView`, `XMut` of a component `X` | the **query**: the entity must have `X`; a `Mut` is a write, a `View` a read |
| `@Optional XView` | the entity need not have `X`; `present()` on the view says whether it does |
| `@Without({Y.class})` on the method | entities that have `Y` are skipped |
| `float dt`, `float alpha` | the tick's or the frame's values (the update phases) |
| `int entity`, `int trigger`, `int kind`, `int a`, `int b` | the entity; a trigger event's id and kind; a signal's two payload values |
| the registry's own type | the registry itself (a behaviour that despawns or reaches other stores) |
| anything else | a **singleton** the registry takes in its constructor |

The phases: `@OnSpawn` and `@OnDespawn` (once per entity, from `spawn`/`despawn`),
`@OnFixedUpdate(stage)` (every 30 Hz tick), `@OnUpdate(stage)` (every frame, with `alpha`),
`@OnTrigger(kind)` (a physics trigger enter or exit for the entity), `@OnTurn` (an encounter turn
starts for the entity), `@OnEvent(kind)` (a **signal** the game raises). A behaviour may also be
`@Parallel(grain)`: its loop then runs on the job system.

**A registry is one class per game, named by `@Components(name = ...)` on a `package-info.java`.**
It owns a field per store, takes the singletons the behaviours ask for in its constructor, and has
every entry point the game calls: `fixedUpdate(dt)` and `fixedUpdateStage(n, dt)`, `update(dt,
alpha)` and `updateStage`, `spawn`, `despawn`, `turn`, `trigger` and `dispatch`, `signal`, `load`
and `loadObject`, `write` and `read`, and an accessor per store.

The decisions behind the shape, each with the alternatives rejected, from [DESIGN §17](../DESIGN.md):

| Decision | Chosen | Rejected |
|---|---|---|
| **D105** components | records; a generated SoA sparse-set store, a `View` and a `Mut` per record | classes with fields per entity (an object per entity, pointer chasing, nothing for the editor to read); the dynamic `ComponentStore` of `scene` with named columns (kept for ad-hoc data; it cannot type-check behaviours); reflection over record components at run time |
| **D106** behaviours | `static` methods; component parameters are the query; other parameters are singletons the registry takes | a `MonoBehaviour`-like base class with a virtual `update()` (an object per entity and behaviour, megamorphic calls); systems as objects implementing an interface (the state they would hold is the singletons); a dependency-injection container (reflection, run-time wiring) |
| **D107** loops and schedule | drive from the smallest store, walk backwards, pre-positioned views; `@Parallel` as `JobKernel`s in a `JobGraph` with edges from declared reads and writes; no structural changes and only `@ThreadSafe` singletons in parallel | command buffers for deferred structural changes (more machinery than the backwards rule); run-time race detection (the static derivation prevents races); parallel by default (most gameplay behaviours call singletons that are not thread-safe) |
| **D108** registry | one generated class per game; events from `rules`, `animation` and `physics` arrive as signals the game raises or as trigger dispatch, so `components` depends only on `scene`, `asset` and `core` | a registry that reflects over the classpath at start; per-module adapters inside `components` (it would depend on every leaf module) |
| **D109** schema and level | `META-INF/jeddy/components.json` written by the processor; `.jlevel` version 2 rows keyed by component and column *names*; the cooker validates them | rows keyed by field index (a reordered record breaks every level); a schema derived at run time (reflection); a separate `.jcomp` file per level |
| **D110** bindings | the cooker generates `<Level>Level.java` with the ids of named objects as constants, compiled with the game | looking objects up by string at run time (a typo is a run-time bug); generating entity handles (they exist only after load) |
| **D111** snapshots | stores write a self-describing header and read back by name, defaulting what is missing | a hash of the field list that rejects every old save; Java serialization |
| **D114** identity | entities saved as 64-bit stable ids through the game's `EntityIds` | saving handles (valid for one run); a global registry of names; an engine-wide id allocator (only the game knows that skeleton 7 is skeleton 7 again) |

Why a `record`, and not a plain class with fields, as the cold form? A record is **data by
construction**: it has no hidden state and no behaviour to hide (its fields are `final` and every
one is a component), so "what is a `Mind`" is exactly its header line. It has a **canonical
constructor and one accessor per component** that the processor can read in declaration order
(`getRecordComponents()`, §14.3) and that the generated `add(entity, value)` and `at(row)` can call
without guessing. It has **equality** (`equals` and `hashCode` over the components), which is what
lets a test say `assertEquals(new Health(3, 5), store.get(b))`. And it has a **printable form**
(`toString` lists the components by name), which is what a tool or a log line wants. A class could
have all four, written by hand and kept in step by hand; the record has them by definition (D105).

## 3. The data layout, in one paragraph

Lesson 3 is the ground this chapter stands on, and it is not repeated here. In short: an object per
skeleton scatters 300 small objects over the heap, and a loop that reads one field pulls a 64-byte
cache line per object to use four bytes of it (L03 §3–4); a **struct of arrays** keeps one
primitive array per field, so the same loop reads one dense stream the prefetcher follows, nineteen
lines for 300 `float`s instead of three hundred (L03 §4.2; the engine's rule for everything
processed per frame, D5). An entity is an `int` handle, a slot
index and a generation (L03 §6–7). A **sparse set** maps an entity's slot index to a dense row in
$O(1)$ and keeps the rows packed, at the price that removal moves the last row into the hole (L03
§8). `components.Store` is that sparse set plus the full handle per row (so a stale handle fails
the lookup), growth, and three hooks the generated subclass fills in for its columns (L03 §9–10).
A **view** is one object repositioned at every row (L03 §11), and after the JIT inlines its
`final` getters, `mind.timer()` is the same array load a hand-written loop makes (L03 §13).
`ComponentsBench` measured the three shapes of one tick over 10,000 entities: 142 µs for an object
per entity with a megamorphic `update()`, 4.8 µs for hand-written columns, 6.5 µs through the
generated loop (L03 §14). This chapter builds everything *around* that core: the language, the
compiler plug-in, the loops and graphs, the registry, the files, and the demo.

## 4. Code generation at compile time: how javac runs a processor

### 4.1 Rounds, elements and the Filer

An **annotation processor** is a class that implements `javax.annotation.processing.Processor`
(the engine's extends the JDK's `AbstractProcessor`). javac finds it on the **processor path**, a
list of jars or directories given with `-processorpath` that Gradle fills from the
`annotationProcessor` configuration, and looks inside each for the service file
`META-INF/services/javax.annotation.processing.Processor`, which names the class. That file is the
convention of the JDK's **service loader** (`java.util.ServiceLoader`): a jar announces "I provide
an implementation of interface X" by listing class names in a file named after X, and whoever needs
an X asks the loader for every class so listed and instantiates it with its no-argument
constructor. Nothing about the
processor is linked into your program: it is a plug-in of the compiler, and it runs in the
compiler's process, once per compilation.

A compilation with processors goes in **rounds**:

1. javac parses every source file on the command line and enters their declarations into its
   symbol tables: packages, classes, records, methods, fields, record components. These are the
   **root elements** of round 1.
2. For each processor whose `getSupportedAnnotationTypes()` matches an annotation present on a
   root element (the engine's returns `"dev.jeddy.components.*"`, a package wildcard), javac calls
   `process(annotations, roundEnv)`. A processor that has been called once is called again in
   every later round, the last included, even when the round has none of its annotations (then
   `annotations` is empty); §14.1 shows how the engine's handles that. The processor asks `roundEnv.getElementsAnnotatedWith(type)`
   for the declarations that carry an annotation and reads them through `javax.lang.model`: an
   `Element` is a declaration (`TypeElement` for a class or record, `RecordComponentElement`,
   `ExecutableElement` for a method, `VariableElement` for a parameter, `PackageElement`), a
   `TypeMirror` is a type as used (`int`, a declared class, an array), and an `AnnotationMirror` is
   one use of an annotation with its values. This is the **compiler's own model** of the source,
   not reflection over loaded classes: no `Class` object exists yet, the code is not compiled, and
   the processor sees exactly what was written.
3. The processor writes new sources through the **`Filer`**: `createSourceFile("pkg.Name")` opens
   a writer whose file lands in the generated-sources directory (`-s`, which Gradle sets to
   `build/generated/sources/annotationProcessor/java/main`), and `createResource(CLASS_OUTPUT, "",
   "META-INF/jeddy/components.json")` writes a plain file into the class output, where it will be
   packed into the jar like any resource.
4. When every processor has returned, javac parses the files generated in this round and enters
   them. They become the root elements of the **next round**, and step 2 repeats: a processor may
   react to annotations in generated code, and may generate more.
5. When a round generates nothing, javac runs one last round with `roundEnv.processingOver()` true,
   so processors can finish, and then compiles everything, the generated files included, exactly as
   if they had been on the command line.

So the generated `MindStore.java` is an ordinary source file of your module: it is compiled with
the same flags (`-Xlint:all -Werror`, chapter [12](12-java-performance.md#1-the-rules-and-the-budget-they-protect)
on the rules), by the same javac, into the same output directory, and the IDE can open it. The only
difference is who wrote it.

### 4.2 Types that do not exist yet

`SkeletonAi.think(MonsterView m, MindMut mind, ...)` names classes that do not exist when javac
parses it: the processor is about to generate them. javac does not stop. During annotation
processing it treats an unresolvable name as an **error type**: a `TypeMirror` whose `getKind()` is
`TypeKind.ERROR` and whose `toString()` is the name as written. The "cannot find symbol" message is
**deferred**: it is reported only if the name is still unresolved after the last round. If a
processor generates the type in the meantime, the error never appears. That is the whole trick
that lets a behaviour be written against generated views: the processor recognises
`<Component>View` and `<Component>Mut` by their *names*, against the records it just collected,
and only trusts a qualified name when the type's kind is `DECLARED` (§14.5).

### 4.3 Retention, discovery and what a processor may not do

Every annotation of the schema has `@Retention(RetentionPolicy.SOURCE)`: javac reads it and does
not write it into the class file. No code can see `@Component` at run time, by reflection or
otherwise, which is the point: the annotations are instructions to the compiler, and the engine's
rule that nothing reflects at run time stays checkable by reading the class files. (Your program
still needs the `components` module at run time for `Store` and `View`, which the generated classes
extend.)

The processor itself depends on nothing but the JDK (`requires java.compiler;` in its
`module-info.java`). It does not even import the annotation classes: it matches them **by name**
(`"dev.jeddy.components.Component"`, §14.9). A processor that imported them would need the runtime
module on its own classpath, and the two would have a version to keep in step; by name, the
processor compiles alone and works with any build of the runtime that keeps the names.

Two files under `src/main/resources/META-INF` register it. `services/javax.annotation.processing.Processor`
holds the class name for javac's service loader, and `gradle/incremental.annotation.processors`
holds `dev.jeddy.components.processor.ComponentProcessor,aggregating`: Gradle's declaration that
the processor is **aggregating**, that is, it reads several sources (every record and behaviour)
to write one output (the registry), so Gradle must give it all of them when any changes rather
than only the changed file (§18.2).

### 4.4 Why generate, and not reflect or inherit

The alternatives the owner's request ruled out, and why, in the terms of this chapter:

- **Reflection at run time** over record components (`Class.getRecordComponents()`,
  `MethodHandle`s for the accessors) could build stores at start-up. But a loop over them would
  read fields through handles the JIT cannot resolve to array loads, the editor would need the
  classes loaded to show anything, and every field would be looked up by name somewhere at run
  time. The processor does the same reading once, at compile time, and writes code the JIT turns
  into array loads (D105, D109).
- **Inheritance** — a `Component` base class, a `Behaviour` base class with `update()` — gives the
  Unity surface directly. It also gives an object per entity per behaviour, a virtual call per
  entity that becomes megamorphic as soon as three behaviour classes exist, and no way for the
  compiler to know which components a behaviour touches, so no derived schedule (D106, D107). L03
  §14 measured the shape: 142 µs against 6.5.
- **A dynamic store with named columns** exists (`scene.ComponentStore`) and stays for ad-hoc data,
  but a behaviour over it would say `store.floatColumn("timer")` with a string, and nothing would
  check it until it ran (D105).

Generation has costs of its own: a processor is code that writes code, which is harder to read than
either; a mistake in it shows up in a file you did not write; and the generated sources must pass
the same lint as yours. §24 lists what that cost in practice.

## 5. The query, the loop and its cost

### 5.1 The query is the signature

A behaviour with required component views $C_1, \dots, C_k$ runs for every entity that has *all*
of them, minus those that have any `@Without` component. There is no query object, no builder, no
string: the parameter list *is* the query, and a parameter of the wrong name or type is a compile
error reported at that parameter (§14.5). Rename a record and every behaviour that used it fails to
compile, which is what you want.

### 5.2 Driving from the smallest store, and the cost

Which entities have all of `Monster`, `Mind`, `Sight` and `Flash`? The generated loop does not
scan the entity pool. It walks the dense rows of the **smallest** required store, the **driving
store**, and for each of its entities asks the other required stores for their row with
`indexOf`, which is two loads and a compare (`sparse[index]`, then `entities[row] == entity`, L03
§9.1). An entity missing from any of them is skipped. With a driver of $n$ rows and $k$ required
stores that is

$$n \text{ iterations} \times k \text{ lookups},$$

the driver's own lookup included (§5.4). For `think`, with all four components on the same 300
entities, that is 300 iterations and 1,200 lookups, each a few loads that hit the cache because the
sparse arrays are read in entity order: a few microseconds. An **archetype** ECS avoids the lookups
by storing the entities that have the same set of components together in chunks, so that a query
over `(Monster, Mind, Sight, Flash)` walks one contiguous block with no lookups at all. It pays for
that with chunk management, moving an entity between chunks whenever a component is added or
removed, and deferred structural changes; at the sizes of an isometric CRPG, hundreds to low
thousands of entities, the lookups cost less than that machinery would (D107, the "not an ECS
framework" paragraph of DESIGN §22).

Which store is smallest is decided **at run time**, by `Store.smaller` nested over the required
stores, because sizes change: the fireball store has 0 to 16 rows, the monster store 300. The same
loop body works whichever store drives, which keeps the generated code one piece instead of one
loop per candidate driver.

### 5.3 Walking backwards

A behaviour may remove the entity it is visiting. `Fireballs.fly` despawns a fireball when its
explosion has faded; the test sample's `Reaper.reap` despawns an entity with no health left.
Removal swaps the last row into the hole (L03 §8.3). Picture the loop walking **forwards** over
rows $0, 1, \dots$: at row $i$ the behaviour removes entity $i$, the last row moves into $i$, the
loop goes on to $i + 1$, and the moved entity is never visited this tick. Walking **backwards**,
from the last row down, the last row has *already* been visited when it moves into $i$, so moving
it is harmless; and a row added during the loop lands at the end, past where the loop started, and
runs next tick. That is why every generated loop counts down. It also guards against the size
shrinking by more than one under it: a behaviour that removes *several* entities in one call (an
explosion) can leave the loop index past the new size, so each iteration first checks `i <
drive.size()` and skips the index otherwise.

```
rows:   0    1    2    3    4            i = 4: visit E, i = 3: visit D
       [A]  [B]  [C]  [D]  [E]           i = 2: C removes itself ->
                                         E (already visited) moves into row 2, size 4
       [A]  [B]  [E]  [D]                i = 1: visit B, i = 0: visit A.   Nothing missed.
```

One anomaly remains, and it is documented rather than fixed (D107): if the behaviour at row $i$
removes *another* entity at a row $j < i$ that has not been visited yet, the last row, already
visited, moves into $j$ and runs a second time this tick when the loop reaches $j$. In the picture,
C removing B moves E into row 1 and E runs twice. Gameplay tolerates that (a skeleton that thinks
twice in one tick decides the same thing); a behaviour that cannot tolerate it defers the removal
to a later stage (§7), where it is the visited entity that goes.

The rejected alternative was a **command buffer**: record every structural change during the loop
and apply the list afterwards. It would make every case exact, and it would add a buffer, a
replay, and a rule about which entities a behaviour may read after it "removed" them. One loop
direction and one documented anomaly were judged smaller (D107).

### 5.4 An open decision: the driving store's own lookup

Look again at §5.2: the loop walks the driving store's rows, so for the driver the row *is* the
loop index $i$, and yet the generated multi-store loop asks `indexOf` for it too, because the
generator does not know at compile time which store will drive. That is a dependent chain of three
loads per row (`entities[i]` to get the entity, `sparse[index]` to get the row back, `entities[row]`
to compare), and L03 §14.2 measured what such a chain costs: the first version of the generated
loop made it even for a single-store behaviour and read **22 µs** instead of 6.5 over 10,000
entities. For loops over **one** required store the generator now writes `int r0 = i;` and the
cost is gone (§16.6). For loops over **several** stores it still makes the lookup for whichever
store drives. Whether to change that, and how — L03 exercise 8 works out `int rK = drive == storeK
? i : storeK.indexOf(e);`, one reference compare per store whose branch is perfectly predictable;
a loop variant per candidate driver chosen by a `switch` is the other way — is a decision the
owner has not made yet. It costs nothing for correctness, it would be measured on a loop like
`think` with four stores, and the change would go in `RegistryGen.match` (§16.6). This chapter
describes the code as it is and says so wherever the lookup appears.

## 6. Parallel behaviours: hazards, the dependency rule and the dispatch cost

### 6.1 Three hazards

Two behaviours over the same stores may run at the same time on different job threads only if
neither can observe the other half-done. Each behaviour touches, for the entity it visits, only
that entity's row of each store through its views, so two behaviours never write the same *bytes*
for the same entity at the same time unless they both write the same *component*. The question is
therefore about components, not rows, and it is the question a CPU pipeline asks about registers.
Let $R_B$ be the set of components behaviour $B$ reads (its `View` parameters, its `@Optional`
views, and its `@Without` components, whose *membership* it reads) and $W_B$ the set it writes
(its `Mut` parameters). For an earlier behaviour $A$ and a later $B$ in declaration order:

- **Read after write** (a true dependency): $A$ writes `Sight`, $B$ reads it. $B$ must see $A$'s
  result, so $B$ waits.
- **Write after write**: both write `Position`. Whichever ran last would win per entity, which must
  be $B$ (declaration order is the contract), so $B$ waits.
- **Write after read**: $A$ reads `Health`, $B$ writes it. $A$ must not see $B$'s new values, so
  $B$ waits.

Two behaviours that only read the same component, or that write disjoint components, run
concurrently, even over the same entities.

### 6.2 The rule, derived

Put the three cases together. $B$ depends on $A$ when
$W_A \cap R_B \neq \emptyset$ (read after write) or $W_A \cap W_B \neq \emptyset$ (write after
write) or $R_A \cap W_B \neq \emptyset$ (write after read). The first two share $W_A$, so:

$$B \text{ waits for } A \iff W_A \cap (R_B \cup W_B) \neq \emptyset \quad\text{or}\quad R_A \cap W_B \neq \emptyset.$$

That is exactly `Model.Behaviour.conflictsWith` (§13.4), and the registry's constructor turns every
true case into a `JobGraph` edge (§16.2): consecutive `@Parallel` behaviours of one stage become
one graph, each a node with its kernel and grain, each depending on every earlier node of the group
it conflicts with. A sequential behaviour between two parallel ones ends the group: the first group
runs and is waited for, the sequential loop runs on the game thread, the next group starts. Races
are thus **prevented by construction**, from the signatures, rather than detected at run time
(D107). Two things the author must still promise, and the processor checks what it can: a parallel
behaviour may not change structure (add or remove components, spawn or despawn), so it may not take
the registry (§14.6); and it may only take singletons its parameter marks `@ThreadSafe`, because
the processor cannot know whether `Crowd.x(a)` is safe to call from four threads at once — the
author does, and says so at the parameter, where the use is.

### 6.3 Worked example: `Movers`

The test sample's `Movers` (§22) has three parallel fixed-update behaviours in this order:
`turnLeft(PositionMut p, float dt)`, `rise(PositionMut p, HealthView h, float dt)`,
`bleed(HealthMut h)`. Their sets:

| Behaviour | $R$ | $W$ |
|---|---|---|
| `turnLeft` | $\emptyset$ | {Position} |
| `rise` | {Health} | {Position} |
| `bleed` | $\emptyset$ | {Health} |

`rise` against `turnLeft`: $W_{\text{turnLeft}} \cap (R_{\text{rise}} \cup W_{\text{rise}}) =
\{\text{Position}\} \cap \{\text{Health}, \text{Position}\} = \{\text{Position}\}$: an edge.
`bleed` against `turnLeft`: $\{\text{Position}\} \cap \{\text{Health}\} = \emptyset$ and
$\emptyset \cap \{\text{Health}\} = \emptyset$: no edge. `bleed` against `rise`:
$\{\text{Position}\} \cap \{\text{Health}\} = \emptyset$, but $R_{\text{rise}} \cap W_{\text{bleed}}
= \{\text{Health}\}$: an edge (write after read). The generated constructor says the same
(exercise 4 asks you to find it):

```java
// SampleGame.java (generated for the test sample)
        Movers_turnLeft_node = fixedUpdateGroup0.add(Movers_turnLeft_kernel, 0, 2);
        Movers_rise_node = fixedUpdateGroup0.add(Movers_rise_kernel, 0, 2, Movers_turnLeft_node);
        Movers_bleed_node = fixedUpdateGroup0.add(Movers_bleed_kernel, 0, 2, Movers_rise_node);
```

### 6.4 A thread's views

A sequential loop positions one set of views per behaviour and calls the method. A parallel one
runs on several threads at once, and a view that one thread positions while another reads it would
be a race. So a parallel behaviour gets **one set of views per thread slot** of the job system
(`jobs.threadSlots()`, the workers plus up to eight external threads; chapter
[01](01-architecture.md#126-thread-indices), on thread indices), and the kernel indexes them with
the `threadIndex` the job system passes (§16.8). The kernel is a `JobKernel` over ranges
`[start, end)` of the driving store's rows, created once as a method reference in a field
initializer, because a method reference that captures `this` allocates each time it is evaluated
(chapter [12](12-java-performance.md#192-kernels-stored-once), on kernels stored once). The driving
store and `dt` are plain fields written on the game thread before the graph runs; the workers see
them because the job system publishes each job with a synchronizing write after them (chapter
[12](12-java-performance.md#193-per-call-inputs-in-fields), on per-call inputs in fields; L00 §9.5).

### 6.5 When parallel pays

L03 §15 derives the rule and this chapter only states it. With $n$ rows of $t$ nanoseconds each,
$p$ threads and a fixed dispatch cost $d$ (publishing the job, waking workers, claiming chunks,
waiting), the parallel loop takes about $d + nt/p$ against $nt$ inline, so it wins only when

$$n\,t\left(1 - \frac{1}{p}\right) > d, \qquad d \approx 20\text{–}50\ \mu\text{s on a desktop}.$$

The demo's `see` is the measured example: 300 rows of a grid lookup of some 50 ns are 15 µs of
work; on the 2-core container the *Component behaviours per tick* row of the perf report read 0.14
ms average on the job system and **0.11 ms inline** (`--inline-behaviours`): the parallel version
lost. To pay for $d = 20$ µs at $p = 2$ and 50 ns a row a loop needs more than 800 rows; at $d = 50$
µs, 2,000. The demo keeps `see` parallel as the worked example (DESIGN §22 records the measurement
and the reason); on the owner's 8-core desktop the numbers differ, and the flag exists to compare. The rule is the lesson,
not the verdict: parallelize **work**, not loops, and measure.

## 7. Phases, stages and signals

One `fixedUpdate(dt)` that ran every behaviour at once would not fit the demo. Its tick is *think →
party → combat → crowd → agent sync → collision → fireballs → fog → visibility*, and the party, the
crowd and the fog are not behaviours: they are engine systems with their own order (chapter
[01](01-architecture.md#23-worked-example-the-iso-demo), on the iso demo's tick). So
`@OnFixedUpdate(stage = n)` groups behaviours into **stages**: `fixedUpdate(dt)` runs every stage
in ascending order, and `fixedUpdateStage(n, dt)` runs one, so the demo calls stage 0 where
`enemies.think` used to be, stage 2 where `spells.tick` was, and stage 1 after the fog has updated.
Within a stage, `@Behaviour(order)` on the class decides first, then the class's name, then the
order of the methods in the source (§14.2). `@OnUpdate` has stages the same way, run from `update`
and `updateStage` once a frame.

The per-entity phases are not loops. `spawn(e)` runs every `@OnSpawn` behaviour whose query `e`
matches, once; `despawn(e)` runs the `@OnDespawn` ones and then removes `e` from every store;
`turn(e)` the `@OnTurn` ones; `trigger(id, e, kind)` the `@OnTrigger` behaviours of that kind, and
`dispatch(events, count)` calls it for each `(trigger, entity, kind)` triple of the physics trigger
buffer.

**Signals** are how the rest of the engine reaches a behaviour without `components` depending on
it. The rules' events name creatures and the animation events name characters, in their own rings
in their own modules. The game drains a ring, maps the event to an entity, and raises
`signal(kind, entity, a, b)` with a kind *it* defines (`Signals.HURT = 1` in the demo); every
`@OnEvent(kind)` behaviour whose query matches runs for that entity with `a` and `b` as payload.
That keeps the module graph as DESIGN §3 draws it: `components` knows `scene`, `asset` and `core`,
and nothing above them (D108). There is no `@OnCollision`, because `physics` has no contact events:
bodies are queried and agents are steered, so there is nothing to dispatch.

## 8. What the generated code costs: the JVM's view

L03 §13 follows one getter through the JIT. Here is the rest of the generated code under the same
light, with the bytecode sizes measured for this chapter with `javap` on the demo's classes
(JDK 27+35) and the JDK 27 inlining limits of chapter
[12](12-java-performance.md#24-inlining): a hot call site inlines a callee up to 325 bytes of
bytecode (`FreqInlineSize`), any call site up to 35 (`MaxInlineSize`).

- **Every method the loop calls per row is tiny and has one target.** `MindView.at` is 11 bytes,
  `timer()` 13, `state()` 17, `MindMut.setTimer` 14; `Store.entityAt` 7, `size()` 8, `indexOf` 32,
  `SparseSet.indexOf` 24, `Handles.index` 5, `Store.smaller` 17. All are under 35 bytes, so C2
  inlines them even at a cold call site. All are `final` (or `static`, or in a `final` class), so
  the inlining needs no class check. Note that javac still emits `invokevirtual` for a call to a
  `final` instance method; it is the JIT, not the bytecode, that turns it into a direct call.
- **The behaviour is a `static` call, and its size decides what happens next.** `SkeletonAi.see` is
  49 bytes and `Fireballs.fly` 68: inlined into their loops at a hot site, so their getters read the
  columns straight from the loop's locals (L03 §13.2). `SkeletonAi.think` is 821 bytes: compiled on
  its own and *called* once per skeleton. Inside it the getters still inline, but `mind.store` and
  `mind.row` are real field loads from the view object; they hit L1, since the same view is used
  every row, and cost a few nanoseconds next to a body that searches the party for the nearest
  member.
- **The views are instance fields, and the JIT does not trust instance `final` fields as
  constants** (chapter [12](12-java-performance.md#25-constants-why-static-final-matters), on
  constants). So `SkeletonAi_think_v1` is loaded from `this` where it is used; one load that hits
  L1, not a constant folded into the code. The views themselves are 16-byte objects (an 8-byte
  header, the `int row` of `View`, the 4-byte compressed reference to the store; L03 §3's rules),
  created in the registry's constructor and never again.
- **The driving store is a `Store`, the abstract base.** `drive.size()` and `drive.entityAt(i)`
  are `final` methods of `Store`, so the call has one target although `drive`'s class is unknown
  at compile time; the lookups go through the concrete `MonsterStore.indexOf`, inherited and
  `final` too.
- **The parallel kernel is one object per behaviour** (`this::SkeletonAi_see_range`, a
  `JobKernel`), made by `invokedynamic` the first time the field initializer runs, which spins a
  small hidden class for it. The job system calls `kernel.run(start, end, thread)` once per
  **chunk** of `grain` rows through the interface: that call is megamorphic, since every kernel in
  the engine is a class of its own, and it is paid once per chunk, not per row (chapter
  [12](12-java-performance.md#192-kernels-stored-once), on kernels stored once). Inside the chunk,
  `_range` is an ordinary loop with everything inlined.
- **A thread's views may share a cache line with another thread's.** The per-thread views are
  allocated one after another in the constructor (`for (t ...) v[t] = new MonsterView()`), so four
  16-byte views sit in one 64-byte line, and two workers that reposition their views for every row
  write to the same line: the false sharing of chapter
  [12](12-java-performance.md#73-cache-coherence-and-false-sharing). This is a prediction from the
  layout, not a measurement; it is a candidate for part of the loss §6.5 measured, and exercise 9
  asks you to test it.
- **The cold paths are cold on purpose.** A Java `switch` on an `int` compiles to one of two
  bytecodes: a **`tableswitch`** when the case values are dense (a table of jump targets indexed
  by the value minus the smallest case, one bounds check and one indirect jump) or a
  **`lookupswitch`** when they are sparse (a sorted list of value-target pairs, searched); a
  `switch` on a `String` is a `lookupswitch` on the string's hash code followed by an `equals` per
  candidate (§15.4 shows the generated ones). `store(String)` is such a string `switch` (201
  bytes), `fixedUpdateStage` a `tableswitch` on the stage (128 bytes),
  `enumOrdinal` a loop over the constants' names: each runs once per tick, per load or per edit,
  never per row. `at(row)` and `get(entity)` allocate the record and say so in their Javadoc;
  behaviours never call them.
- **What allocates, and when.** At construction: the stores with `CAPACITY` rows each (for the
  demo's five stores at 512 rows, 45,056 bytes of columns and 10,240 bytes of `entities` arrays,
  computed from the records' fields), one view per behaviour parameter, one set of views per thread
  slot for each parallel behaviour (27 views for `see` on the 2-core container's nine slots), the
  job graphs. Afterwards nothing, unless a store grows past `CAPACITY`, which doubles every column
  (L03 §9.2). `GeneratedGameTest.aTickAllocatesNothingInlineAndOnTheJobSystem` proves the steady
  state (§22).

---
# Part B — Building it

The build goes in the order a reader would write it: first the language (the annotations), then
the runtime the generated code extends, then the processor that reads the language and writes the
code, then the build that runs it. The processor is the largest piece; it is shown method by method,
with the output it produces for the demo beside it.

## 9. Step 1: the annotations

Start in `modules/components/src/main/java/dev/jeddy/components/`. An annotation is declared with
`@interface`; its "methods" are its values, with optional defaults. Each of the engine's carries
three things that decide how javac treats it: a `@Target` (where it may be written), a `@Retention`
(`SOURCE`: javac reads it and throws it away, §4.3), and Javadoc that is the specification the
processor enforces. The import lines are left out below; every file imports
`java.lang.annotation.{ElementType, Retention, RetentionPolicy, Target}`.

### 9.1 Declaring a component

```java
/**
 * Marks a {@code record} as a component (DESIGN §22, D105). The processor generates, in the same
 * package, {@code <Name>Store} (a sparse-set store with one array per field), {@code <Name>View} (a
 * read-only flyweight over one row) and {@code <Name>Mut} (the view with setters). The record is
 * the cold form: given to {@code add}, returned by {@code get}, edited in the editor.
 *
 * <p>Record components may be {@code int}, {@code long}, {@code float}, {@code boolean}, an {@code
 * enum}, {@code dev.jeddy.math.Vec2f} or {@code Vec3f}; an {@code int} may be an {@link EntityRef}
 * or an {@link AssetRef}. Defaults come from {@link Default}; {@link Range}, {@link Angle}, {@link
 * Color} and {@link Doc} are hints for the editor.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface Component {
    String doc() default "";
}
```

`@Target(TYPE)` lets it be written on any class, interface or record; the *processor* rejects
everything but a public top-level record (§14.3), with a message, because an annotation target
cannot say "records only". `doc` is the one-line description the inspector shows for the
component.

The field annotations all target `RECORD_COMPONENT`, the thing between the parentheses of a
record, so `@Default("true") boolean visible` is legal and `@Default` on a method is not:

```java
/**
 * The default value of a record component as text, parsed by the processor for the field's type:
 * {@code "10"}, {@code "0.5"}, {@code "true"}, an enum constant's name, or {@code "1, 2, 3"} for a
 * vector. Without it a field defaults to zero, {@code false}, the first constant or the zero
 * vector. Also the value the editor shows for a new row.
 */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.SOURCE)
public @interface Default {
    String value();
}
```

Why text and not a typed value? An annotation value must be a compile-time constant of a fixed
type, and one annotation cannot have "an `int` or a `float` or an enum constant or three floats" as
its value. A string covers every field type, and the processor parses it against the declared type
and reports a mismatch at the record component (§14.4). The remaining hints:

```java
/** A one-line description shown by the editor next to the field. */
public @interface Doc { String value(); }

/** An editor hint: the field's sensible range (a slider). */
public @interface Range { double min(); double max(); }

/** An editor hint: the {@code float} is an angle in radians (shown in degrees). */
public @interface Angle {}

/** An editor hint: the {@code Vec3f} is a linear RGB color (a color picker). */
public @interface Color {}

/**
 * The {@code int} field holds an entity handle ({@code Handles.NULL} when none). The editor shows a
 * picker over the level's objects and the loader resolves the object id to the entity.
 */
public @interface EntityRef {}

/**
 * The {@code int} field holds an asset handle of the given kind ({@code "mesh"}, {@code "scene"},
 * {@code "sound"}, ...). The editor shows the asset browser and the loader resolves the path.
 */
public @interface AssetRef { String value(); }
```

(Condensed to one line each; the files have the `@Target(RECORD_COMPONENT)` and `@Retention(SOURCE)`
lines and the usual formatting.) `@Range` takes `double`s so that one annotation serves `int`,
`long` and `float` fields; the processor checks that the field is numeric, that `@Angle` sits on a
`float`, and that `@Color` sits on a `Vec3f` (§14.3). `@EntityRef` and `@AssetRef` change what the
column *means* without changing its type: it stays an `int` in the store, and three readers treat it
specially — the inspector (a picker instead of a number), the level loader (an object id or an
asset path resolved to a handle, §12) and the snapshot (an entity written as a stable id, §10.4).

### 9.2 Declaring behaviours

```java
/**
 * Marks a final class whose {@code public static} methods are behaviours (DESIGN §22, D106): each
 * method carries one phase annotation ({@link OnSpawn}, {@link OnDespawn}, {@link OnFixedUpdate},
 * {@link OnUpdate}, {@link OnTrigger}, {@link OnTurn}, {@link OnEvent}); its component view
 * parameters are the query, {@code float dt}-style parameters are the phase's values, and any other
 * parameter is a singleton the generated registry takes in its constructor.
 *
 * <p>Within a phase, behaviours run in {@link #order()} then declaration order.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface Behaviour {
    int order() default 0;
}
```

The seven phases, each `@Target(METHOD)`, `@Retention(SOURCE)`:

```java
/**
 * Phase: runs every simulation tick for every matching entity; takes {@code float dt}. {@code
 * fixedUpdate(dt)} on the registry runs every stage in ascending order; a game that interleaves the
 * behaviours with systems of its own (the crowd, the fog of war) runs one stage at a time with
 * {@code fixedUpdateStage(stage, dt)}.
 */
public @interface OnFixedUpdate { int stage() default 0; }

/**
 * Phase: runs every frame for every matching entity, after interpolation; takes {@code float dt}
 * and {@code float alpha}. Stages as in {@link OnFixedUpdate}: {@code update(dt, alpha)} runs them
 * all, {@code updateStage(stage, dt, alpha)} one.
 */
public @interface OnUpdate { int stage() default 0; }

/** Phase: runs once for an entity when the registry's {@code spawn(entity)} is called. */
public @interface OnSpawn {}

/** Phase: runs once for an entity when {@code despawn(entity)} is called, before its rows go. */
public @interface OnDespawn {}

/** Phase: runs when an encounter turn starts for the entity ({@code turn(entity)}). */
public @interface OnTurn {}

/**
 * Phase: runs when a physics trigger event of the given kind ({@link #ENTER} or {@link #EXIT})
 * names the entity; takes {@code int trigger} (the trigger id) and {@code int kind}. The values
 * equal {@code physics.trigger.Triggers.ENTER} and {@code EXIT}.
 */
public @interface OnTrigger {
    int ENTER = 1;
    int EXIT = 2;
    int value();
}

/**
 * Phase: runs when the game raises a signal of the given kind for the entity ({@code signal(kind,
 * entity, a, b)}); takes {@code int a} and {@code int b}. Signals are how the rules, animation and
 * dialogue event rings reach behaviours: the game drains a ring and raises one signal per event it
 * cares about, with the kinds it defines as constants.
 */
public @interface OnEvent { int value(); }
```

`OnTrigger` declares `ENTER = 1` and `EXIT = 2` as its own constants rather than referring to
`physics.trigger.Triggers`, because `components` does not depend on `physics` (D108); the Javadoc
states the equality and the demo passes the physics buffer through `dispatch` unchanged.

The query modifiers and the parallel pair:

```java
/**
 * On a view parameter of a behaviour: the entity need not have the component; {@code present()} on
 * the view says whether it does (the view is positioned at no row when it does not).
 */
@Target(ElementType.PARAMETER)
public @interface Optional {}

/** On a behaviour method: entities that have any of these components are skipped. */
@Target(ElementType.METHOD)
public @interface Without { Class<?>[] value(); }

/**
 * On a behaviour method: the loop runs on the job system over ranges of {@code grain} rows, and
 * consecutive parallel behaviours of a phase run concurrently when their declared access allows
 * (DESIGN §22, D107). A parallel behaviour may not change structure (add or remove components,
 * spawn or despawn) and may only take singletons its parameters mark {@link ThreadSafe}.
 */
@Target(ElementType.METHOD)
public @interface Parallel { int grain() default 64; }

/**
 * On a singleton parameter of a {@link Parallel} behaviour, or on one of the game's own singleton
 * types: the author asserts that what the behaviour does with it is safe from several job threads
 * at once (it only reads, or the type synchronizes). Engine types are never marked; the claim is
 * made at the parameter, where the use is.
 */
@Target({ElementType.PARAMETER, ElementType.TYPE})
public @interface ThreadSafe {}
```

`@Without` takes `Class<?>[]`, the record classes themselves, not strings. A misspelt class is
an error type during processing, so the processor reports it ("@Without names a component; Deadd
is not one") and javac adds its own "cannot find symbol" after the last round; a real class that
is not a component of this compilation gets the processor's message alone (§14.5). `@ThreadSafe` may target a `TYPE` too, for a game's own class that is safe by
construction, but the processor today reads it only at the parameter (§14.6): the claim is about
*this use*, and a `Crowd` that is safe to read is not safe to call `addAgent` on.

### 9.3 Declaring the game

```java
/**
 * On a {@code package-info.java}: generates the game's registry class {@code name} in that package
 * (DESIGN §22, D108) with a field per store, the singletons as constructor parameters, and the
 * entry points {@code fixedUpdate}, {@code update}, {@code spawn}, {@code despawn}, {@code
 * trigger}, {@code dispatch}, {@code turn}, {@code signal}, {@code load} and the snapshot calls.
 * One registry per game; every {@link Component} and {@link Behaviour} the compilation sees belongs
 * to it.
 */
@Target(ElementType.PACKAGE)
@Retention(RetentionPolicy.SOURCE)
public @interface Components {
    String name();

    /** Initial rows per store (they grow past it, allocating). */
    int capacity() default 256;
}
```

A package annotation lives in `package-info.java`, the only file where `@Something package x.y;`
is legal. One per compilation: the processor refuses two (§14.2), because every component and
behaviour it sees goes into *the* registry. `capacity` is the `CAPACITY` constant of the generated
class: the rows every store starts with. Set it to cover the game (the demo uses 512 for 300
skeletons and 16 fireballs), because growth allocates.

The module's `module-info.java` declares what the generated code will need from its users:

```java
module dev.jeddy.components {
    requires transitive dev.jeddy.core;
    requires transitive dev.jeddy.math;
    requires transitive dev.jeddy.scene;
    requires transitive dev.jeddy.asset;

    exports dev.jeddy.components;
}
```

`transitive`, because a game that `requires dev.jeddy.components` gets generated stores whose
constructors take `scene.EntityPool`, whose `write` takes `core.io.ByteWriter`, whose `load` takes
`asset.format.LevelData`, and whose vector fields are `math.Vec3f` (chapter
[01](01-architecture.md#2-modules-who-may-know-whom), on modules).

## 10. Step 2: `View`, and what `Store` adds beyond Lesson 3

### 10.1 `View`

The view base is twelve lines. L03 §11 explains why a flyweight; here is the file:

```java
public abstract class View {
    /** The dense row, or -1 when positioned at no row (an absent {@link Optional} component). */
    protected int row = -1;

    /** For the generated views. */
    protected View() {}

    /** Whether the view is positioned at a row (false for an absent optional component). */
    public final boolean present() {
        return row >= 0;
    }

    /** The dense row, or -1. */
    public final int row() {
        return row;
    }

    /** Positions the view at no row. */
    public final void none() {
        row = -1;
    }
}
```

`row` is `protected`, not private, because the generated subclass in another package reads it in
every getter (`store.timer[row]`). The store reference is *not* here: its type differs per
component, so the generated `MindView` declares `MindStore store` itself, package-private, beside
the package-private columns it reads. `present()` is how an `@Optional` parameter answers: the loop
positions the view at the row `indexOf` returned, which is −1 when the entity lacks the component
(§16.6), and a getter called on an absent view would throw `ArrayIndexOutOfBoundsException` at
index −1, which is the right failure for a behaviour that forgot to ask.

### 10.2 What `Store` is, in Lesson 3's words

L03 §9 builds the base class's membership half, and it is quoted here only as far as needed to read
the rest. `Store` keeps a `SparseSet members` from entity slot index to dense row, an `int[]
entities` with the full handle per row, and a `capacity`; the constructor registers the store with
the `EntityPool` so that destroying an entity removes its row. `indexOf(entity)` is the sparse
lookup followed by the handle comparison that makes stale handles fail; `has`, `entityAt`, `size`
and `capacity` read those; `add(entity)` appends a row, growing every column through the
`growColumns` hook when the capacity is reached, and resets the new row to its defaults through
`resetRow`; `remove(entity)` moves the last row into the hole through `moveRow`. All of them are
`final`: the JIT sees one target, and a generated store cannot change the contract.

One line of the constructor deserves a note the lesson does not make:

```java
    @SuppressWarnings("this-escape") // registration only stores the reference; no call back
    protected Store(EntityPool pool, String name, int capacity) {
```

javac's `this-escape` lint (part of `-Xlint:all`, so an error under `-Werror`) warns when a
constructor lets `this` out before the subclass's constructor has run, because the receiver could
be used half-built. Here `pool.register(this)` only stores the reference; the pool never calls back
during construction, so the warning is suppressed with the reason beside it.

### 10.3 The hooks and the generic column API

Below the membership methods come the three hooks the generated store implements, and then the
part of `Store` the lesson only names: the **generic column API**, the way the cold paths reach a
column *by name* without reflection. These are abstract; the processor writes each as a `switch`
per store (§15.4).

```java
    /** Removes every row. */
    public final void clear() {
        members.clear();
    }

    /** Picks the smaller store: the generated loops drive from it. */
    public static Store smaller(Store a, Store b) {
        return a.size() <= b.size() ? a : b;
    }

    // ---- Hooks the generated store implements -----------------------------------------------

    /** Reallocates every column to {@code capacity} rows, keeping the values. */
    protected abstract void growColumns(int capacity);

    /** Copies row {@code from} over row {@code to} in every column. */
    protected abstract void moveRow(int from, int to);

    /** Sets every column of {@code row} to its default. */
    protected abstract void resetRow(int row);

    // ---- The generic column API (cold paths: loading, editing, snapshots) -----------------------

    /** Scalar columns. */
    public abstract int columnCount();

    /** The column's name: the record component's, or {@code name.x} for a vector's parts. */
    public abstract String columnName(int column);

    /** {@link #INT}, {@link #LONG}, {@link #FLOAT} or {@link #BOOL}. */
    public abstract int columnType(int column);

    /** The column named {@code name}, or -1. */
    public abstract int columnIndex(String name);

    /**
     * For an enum column, the ordinal of the constant named {@code name}, or -1; -1 for other
     * columns. Levels and snapshots store enums by name so that reordering constants is safe.
     */
    public abstract int enumOrdinal(int column, String name);

    /** For an enum column, the name of ordinal {@code ordinal}; {@code null} for other columns. */
    public abstract String enumName(int column, int ordinal);

    /** For an {@code @AssetRef} column, its asset kind; {@code null} for other columns. */
    public abstract String assetKind(int column);

    /** Whether {@code column} is an {@code @EntityRef}: a handle a save translates (D114). */
    public abstract boolean isEntityRef(int column);

    public abstract int getInt(int row, int column);

    public abstract void setInt(int row, int column, int value);

    public abstract long getLong(int row, int column);

    public abstract void setLong(int row, int column, long value);

    public abstract float getFloat(int row, int column);

    public abstract void setFloat(int row, int column, float value);

    public abstract boolean getBool(int row, int column);

    public abstract void setBool(int row, int column, boolean value);
```

- **`clear` only clears the sparse set.** The `entities` array and the columns keep stale values
  past `size()`, which nothing reads; `read` (§10.4) calls it before re-adding every row.
- **`smaller` prefers `a` on a tie** (`<=`). The generated drive expression nests it left to right
  over the required stores in parameter order, so with equal sizes the *first* component parameter's
  store drives (§16.5). That is why `think`, whose four stores all hold 300 rows, drives from
  `monster`.
- **Columns are scalars, not fields.** A `Vec3f home` is three columns `home.x`, `home.y`,
  `home.z`; an enum is an `INT` column that `enumOrdinal`/`enumName` translate by constant name.
  The level file and the snapshot store an enum's *name*, so reordering the constants is safe.
- **The type constants** `INT = 0`, `LONG = 1`, `FLOAT = 2`, `BOOL = 3` are what `columnType`
  returns; `ENTITY = 4` is only a *snapshot header* type: `columnType` of an `@EntityRef` column
  still says `INT`, and `isEntityRef` says the rest.
- **A wrong typed access throws.** `getFloat(row, 0)` on an `int` column is an
  `IllegalArgumentException` from the generated `switch`'s default (§15.4): the cold paths are
  tools, and a tool that mixes types should fail loudly.

### 10.4 Snapshots: `write`

A store writes itself in one call, into `core.io.ByteWriter`, the engine's one binary stream
(D113; L04 §9 builds it), in a form a *different build* of the game can read: its name, its
columns by name and type, then the entity of every row as a **stable id**, then the columns one
after another. `EntityIds` (§11) is the game's translation from handles to ids; the one-argument
form uses the identity translation for tests and tools that read within one run.

```java
    /**
     * Writes the store: its name, its columns by name and type, the entity of each row as a stable
     * id from {@code ids}, and the columns, with every {@code @EntityRef} column as ids too.
     * Allocates nothing when the writer's buffer is large enough (a save-time path).
     */
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

    /** {@link #write(ByteWriter, EntityIds)} with handles as ids (one run of the program). */
    public final void write(ByteWriter w) {
        write(w, EntityIds.HANDLES);
    }
```

- **Column by column, not row by row.** The columns are the arrays; writing a whole column is a
  sequential walk of one array, the shape L03 §2 showed is cheapest, and the reader can skip a
  column it does not know in one `skip` (§10.6).
- **An `@EntityRef` column is written as ids** (`ENTITY` in the header, 8 bytes per value) so that
  the reader knows to translate it back; its `columnType` stays `INT`, so the `switch` tests
  `isEntityRef` inside the `INT` case.
- **It allocates nothing** once the writer's buffer is large enough: the strings are encoded by
  hand (L04 §9), the loops are over primitives, and `ids.idOf` is the game's (the demo's does two
  map lookups). `GeneratedGameTest.aSecondCaptureAllocatesNothing` checks it (§22.8).

**A worked example, byte by byte.** The test sample's `Health(int current, int max)` store with two
rows, entities created first and second in a fresh pool (slot 0 generation 1, slot 1 generation 1:
handles `0x400000` and `0x400001`, L03 §6), holding `Health(3, 5)` and `Health(10, 10)`, written
with `EntityIds.HANDLES`. Measured for this chapter by running the real classes; 70 bytes:

```
06 00 48 65 61 6c 74 68           u16 6, "Health"                 the store's name
02 00 00 00                       i32 2                           two columns
07 00 63 75 72 72 65 6e 74        u16 7, "current"                column 0's name
00 00 00 00                       i32 INT                         ... and type
03 00 6d 61 78                    u16 3, "max"                    column 1
00 00 00 00                       i32 INT
02 00 00 00                       i32 2                           two rows
00 00 40 00 00 00 00 00           i64 0x400000                    row 0's entity, as an id
01 00 40 00 00 00 00 00           i64 0x400001                    row 1's
03 00 00 00  0a 00 00 00          i32 3, 10                       column "current"
05 00 00 00  0a 00 00 00          i32 5, 10                       column "max"
```

Every number is little-endian (L04 §2): `0x400000` is written `00 00 40 00`. The identity
translation (`EntityIds.HANDLES`, §11) turns the handle `0x400000` into the id `0x400000`, eight
bytes, and the reader turns it back with a cast. Count the bytes: $8 + 4 + 13 + 9 + 4 + 16 + 8 +
8 = 70$.

### 10.5 Snapshots: `read`

Reading is the mirror image, with every difference between the file and this build resolved by
name. It is a load-time path and allocates (three small arrays and the strings).

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

- **The name must match**; a caller that does not know which store comes next peeks at it first
  (`ByteReader.peekString`, which reads the string and rewinds), as the registry does (§16.5).
- **`local[c]`** is this build's column index for the file's column $c$, or −1 when this build has
  no such column: the file's values are then read and discarded. **`types[c]`** is the file's type
  for it.
- **`clear()` first**: the store is replaced, not merged.
- **Every row's id goes through `ids.entityOf`**, and a row whose id resolves to nothing (a level
  object that no longer exists, a dead handle in the identity mapping) is **dropped with a
  warning**, not fatal: a save from a level that lost an object still loads (D114). `rows[i]` is
  the dense row the entity got, so the column loops below know where each value goes.

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

- **The `switch` is on the file's type**, because the file decides how many bytes each value takes;
  the value is *always* read, so the stream stays in step, and only stored when the column exists
  here (`to >= 0`) and the row was not dropped (`rows[i] >= 0`).
- **A column that changed type keeps its default.** `types[c]` must equal what this build would
  write for the column; otherwise `to` becomes −1 and the values are skipped. An `int` field that
  became `@EntityRef` (or the reverse) is caught by the same test, since one is `INT` in the header
  and the other `ENTITY`. (The old chapter said `read` "refuses" a changed type; it does not throw,
  it ignores the column.)
- **An `ENTITY` value is translated back** through `ids.entityOf`, and `NONE` becomes the null
  handle 0.
- **A column the file lacks** is never mentioned in the loop and keeps the default that `add`'s
  `resetRow` gave the row. So a save written before a field was added loads with the new field at
  its `@Default` (D111).

### 10.6 Snapshots: `skip`

A registry reading a file that names a store this build no longer has must step over it without
knowing its columns' meaning. The header has everything needed:

```java
    /**
     * Skips one store in a snapshot (a component this build no longer has) and returns its name.
     */
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

`8 * n` skips the entity ids; then each column is $n$ values of 4, 8 or 1 bytes by its type. This
is the reason the header carries types at all: without them a reader could not skip what it does
not understand, and a renamed component would make every old save unreadable.

## 11. Step 3: `EntityIds`

### 11.1 The problem

A handle is a slot index and a generation (L03 §6), valid for one run of the program; the next run
allocates its entities in another order and a saved handle would point at whatever got the slot.
A save needs a name for an entity that means the same thing next time. The engine cannot invent
one: it does not know that the skeleton that spawned seventh is "the same" skeleton as the one that
spawns seventh after a load. The game does. So `EntityIds` is an **interface the game implements**,
and the stores call it for every row and every `@EntityRef` value they write or read (D114).

### 11.2 The id, packed

An id is a `long` with a **kind** in its top byte and a **value** in the low 56 bits:

```
bit:  63 ........ 56 | 55 ...................................... 0
      kind (8 bits)  | value (56 bits)
```

Packing and unpacking follow L00 §6.4 exactly. The interface is shown in two pieces and in a
different order from the file, which declares `HANDLES` (§11.3) right after `FIRST_GAME_KIND` and
the two abstract methods last:

```java
public interface EntityIds {
    /** The id of nothing: a null handle on save, an unresolvable id on load. */
    long NONE = 0;

    /** Kind 1: a level object by its object id. */
    int OBJECT = 1;

    /** The first kind a game may define. */
    int FIRST_GAME_KIND = 2;

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

- **`(long) kind << 56`**: the cast comes first, because an `int` shifted by 56 would use only the
  low five bits of the distance (L00 §6.3) and shift by 24. `| value` then fills the low bits, which
  the check `value >>> 56 != 0` guarantees are the only ones set.
- **`kind(id)` uses `>>>`**, the unsigned shift: a kind of 128 or more sets bit 63 and makes the
  `long` negative, and `>>` would drag ones in. **`value(id)`** masks with 56 ones, written in hex
  with underscores so you can count the seven `FF` bytes.
- **Kind 0 is reserved for `NONE`**, so `kind(NONE)` is 0 and no real id is ever 0; the same
  convention as `Handles.NULL`. Kind 1 is a level object by its object id; a game's own kinds start
  at 2 (the demo: party members, skeletons, fireballs, §21).

Worked: `EntityIds.object(42)` is $1 \cdot 2^{56} + 42 = 72{,}057{,}594{,}037{,}927{,}978$,
hex `0x0100_0000_0000_002A`; `kind` of it is `0x01`, `value` is 42.

### 11.3 The two methods and the identity mapping

```java
    /** The stable id of {@code entity}, or {@link #NONE} for an entity the save cannot name. */
    long idOf(int entity);

    /**
     * The live entity for {@code id}, created if the game wants it so, or {@code 0} when nothing
     * answers to it.
     */
    int entityOf(long id);

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
}
```

`HANDLES` is the mapping the one-argument `write`/`read` use: tests, tools, and anything that reads
back within the same run. The mask `& 0xFFFFFFFFL` matters: a handle whose generation is 512 or
more has bit 31 set and is a negative `int` (L03 §6); widened to `long` without the mask it would
sign-extend (L00 §5.4) into a negative id whose top byte reads as kind 255. The mask keeps the 32
bits and zeros the rest, and `(int) id` truncates them back. `entityOf` may *create* an entity:
the demo's fireballs are made fresh on load, because nothing but the save knows they exist (§21).

## 12. Step 4: `Levels`

### 12.1 The problem

A level file carries, per object, the components the editor attached and the values it set: rows
keyed by component and column **names**, with typed values (D109; chapter [14](14-editor.md), on
the level format, and [formats/jlevel.md](../formats/jlevel.md)). At load the rows must become
`add` calls and column writes on the right stores, for the right entities, with every name resolved
against *this* build's records, and every unknown name skipped with a warning rather than a crash:
a level outlives a renamed record.

### 12.2 The binding

The loader does not know how a level object becomes an entity, or how an asset path becomes a
handle; the game does. So it takes a small interface:

```java
public final class Levels {
    private static final Logger LOG = Log.logger("components");

    private Levels() {}

    /** Resolves level objects and asset paths to runtime handles. */
    public interface Binding {
        /** The entity of level object {@code objectId}, or {@code Handles.NULL} (0) for none. */
        int entityOf(int objectId);

        /** The handle of asset {@code path} of {@code kind}, or 0 when unresolved. */
        default int assetOf(String kind, String path) {
            return 0;
        }
    }
```

In the demo the binding is one method reference, `levelLoader::rootOf` (§20): a placement's root
entity by its object id. `assetOf` has a default, because most games will not have `@AssetRef`
fields on day one.

### 12.3 `load`, `loadObject` and `applyRow`

```java
    /** Adds every row of {@code level} whose object maps to an entity. Returns the rows applied. */
    public static int load(Store[] stores, LevelData level, Binding binding) {
        int applied = 0;
        for (int row = 0; row < level.componentRowCount(); row++) {
            int entity = binding.entityOf(level.componentRowObject(row));
            if (entity != 0 && applyRow(stores, level, row, entity, binding)) {
                applied++;
            }
        }
        return applied;
    }

    /** Adds the rows of object {@code objectId} to {@code entity}. Returns the rows applied. */
    public static int loadObject(
            Store[] stores, LevelData level, int objectId, int entity, Binding binding) {
        int applied = 0;
        for (int row = level.nextComponentRowOf(objectId, 0);
                row >= 0;
                row = level.nextComponentRowOf(objectId, row + 1)) {
            if (applyRow(stores, level, row, entity, binding)) {
                applied++;
            }
        }
        return applied;
    }
```

`load` walks every row of the level once; `loadObject` walks the rows of *one* object, for the
editor's live edits (§20), using `LevelData.nextComponentRowOf(objectId, from)`, which returns the
next row of that object at or after `from`, or −1. Both count what they applied, which the tests
check and the console prints.

```java
    /** Adds one row to {@code entity}; false when the component is unknown. */
    public static boolean applyRow(
            Store[] stores, LevelData level, int row, int entity, Binding binding) {
        String name = level.componentRowComponent(row);
        Store store = find(stores, name);
        if (store == null) {
            LOG.warn(
                    "level object {} has unknown component {}",
                    level.componentRowObject(row),
                    name);
            return false;
        }
        int r = store.add(entity);
        for (int k = 0; k < level.componentValueCount(row); k++) {
            String column = level.componentValueColumn(row, k);
            int c = store.columnIndex(column);
            if (c < 0) {
                LOG.warn("component {} has no column {}", name, column);
                continue;
            }
            long bits = level.componentValueBits(row, k);
            switch (level.componentValueType(row, k)) {
                case LevelData.VALUE_INT -> store.setInt(r, c, (int) bits);
                case LevelData.VALUE_LONG -> store.setLong(r, c, bits);
                case LevelData.VALUE_FLOAT ->
                        store.setFloat(r, c, Float.intBitsToFloat((int) bits));
                case LevelData.VALUE_BOOL -> store.setBool(r, c, bits != 0);
                case LevelData.VALUE_ENUM -> {
                    int ordinal = store.enumOrdinal(c, level.componentValueText(row, k));
                    if (ordinal < 0) {
                        LOG.warn(
                                "component {} column {}: no constant {}",
                                name,
                                column,
                                level.componentValueText(row, k));
                    } else {
                        store.setInt(r, c, ordinal);
                    }
                }
                case LevelData.VALUE_ENTITY -> store.setInt(r, c, binding.entityOf((int) bits));
                case LevelData.VALUE_ASSET ->
                        store.setInt(
                                r,
                                c,
                                binding.assetOf(
                                        store.assetKind(c), level.componentValueText(row, k)));
                default -> LOG.warn("component {} column {}: unknown value type", name, column);
            }
        }
        return true;
    }

    /** The store named {@code name} among {@code stores}, or null. */
    public static Store find(Store[] stores, String name) {
        for (Store s : stores) {
            if (s.name().equals(name)) {
                return s;
            }
        }
        return null;
    }
```

- **`store.add(entity)` first**, which resets the row to the record's defaults; then only the
  columns the row sets are written. A column the level does not mention keeps its `@Default`.
- **The payload is 64 bits (`bits`) plus a type**, as the file stores it (formats/jlevel.md): an
  `int` is the low 32 bits, a `float` is its raw bit pattern (L00 §7.2) turned back with
  `Float.intBitsToFloat`, a `bool` is non-zero, an enum and an asset carry text.
- **Every name is resolved here and nowhere else**: the component through `find` (a linear search
  over a handful of stores, cold), the column through the generated `columnIndex` switch, the enum
  constant through `enumOrdinal`. Each failure is one `warn` with the names in it, and the load
  goes on.
- **`VALUE_ENTITY` and `VALUE_ASSET` go through the binding**: the file holds an object id or a
  path, the store holds a handle.

This is the generic column API earning its keep: without it, loading a level would need either
reflection or a generated `switch` over every (component, field) pair in the game, and the editor
and the snapshots would need the same again.
## 13. Step 5: the processor's model

Move to `tools/component-processor/src/main/java/dev/jeddy/components/processor/`. The processor
is split in two halves that never meet except through plain data: `ComponentProcessor` *reads* the
compiler's elements into a `Model`, and three generators *write* text from the `Model`. The split
is what keeps each half readable, and it is why the generators can be tested by eye on a small
model. `Model` is a `final` package-private class of nested data holders with public-by-package
fields and no behaviour beyond small derived lists; it is shown in the order the processor fills
it.

### 13.1 Field kinds and fields

```java
final class Model {
    /** Field kinds (the scalar expansion is in {@link Field#columns()}). */
    enum Kind {
        INT("int", "int", "INT"),
        LONG("long", "long", "LONG"),
        FLOAT("float", "float", "FLOAT"),
        BOOL("bool", "boolean", "BOOL"),
        ENUM("enum", "int", "INT"),
        VEC2("vec2", "float", "FLOAT"),
        VEC3("vec3", "float", "FLOAT"),
        ENTITY("entity", "int", "INT"),
        ASSET("asset", "int", "INT");

        /** The schema's name. */
        final String json;

        /** The column array's element type. */
        final String columnJava;

        /** The {@code Store} column type constant. */
        final String storeType;

        Kind(String json, String columnJava, String storeType) {
            this.json = json;
            this.columnJava = columnJava;
            this.storeType = storeType;
        }

        int columns() {
            return this == VEC2 ? 2 : this == VEC3 ? 3 : 1;
        }
    }
```

One enum holds the three spellings of every field kind: what the JSON says (`"vec3"`), what Java
array the store uses (`float[]`), and which `Store` constant `columnType` reports (`FLOAT`). An
`ENUM` is an `int` column, a `VEC3` three `float` columns, `ENTITY` and `ASSET` are `int`s that
only the schema and `isEntityRef`/`assetKind` tell apart. Keeping the spellings together means a
new field kind is one line here and nothing forgotten elsewhere.

```java
    /** One record component. */
    static final class Field {
        String name;
        Kind kind;

        /** The declared Java type as written (enum binary name, {@code Vec3f}, ...). */
        String javaType;

        /** Enum constants in ordinal order. */
        List<String> enumValues = new ArrayList<>();

        String assetKind = "";
        String doc = "";

        /** Default per column (an enum's as its ordinal, a bool as 0/1). */
        double[] defaults;

        double min = Double.NaN;
        double max = Double.NaN;
        boolean angle;
        boolean color;

        /** Column names: the field's, or {@code name.x}... for vectors. */
        List<String> columns() {
            return switch (kind) {
                case VEC2 -> List.of(name + ".x", name + ".y");
                case VEC3 -> List.of(name + ".x", name + ".y", name + ".z");
                default -> List.of(name);
            };
        }

        /** Java field names of the columns in the store. */
        List<String> columnFields() {
            return switch (kind) {
                case VEC2 -> List.of(name + "X", name + "Y");
                case VEC3 -> List.of(name + "X", name + "Y", name + "Z");
                default -> List.of(name);
            };
        }
    }
```

- **`defaults` is a `double[]` with one entry per column**, whatever the kind: an `int` default is
  stored as a `double`, an enum's as its ordinal, a `bool` as 0 or 1, a vector as three. That is
  the lazy, correct choice: a `double` holds every `int`, every `float` and every `long` up to
  $2^{53}$ exactly, and the generators cast back (`(int) v`, `(long) v`, `(float) v`) when they
  write a literal.
- **`NaN` means "no `@Range`"**: a range cannot be NaN, so no separate flag is needed; the schema
  writer tests `Double.isNaN` (§17).
- **Two spellings per column**: `columns()` is what files and the editor call it (`home.x`, the
  record component's name with a suffix), `columnFields()` what the generated Java calls it
  (`homeX`, an identifier). Both are derived from `name`, so they cannot drift apart.

### 13.2 Components

```java
    /** One {@code @Component} record. */
    static final class Component {
        String pkg;
        String name;
        String doc = "";
        List<Field> fields = new ArrayList<>();

        String qualified() {
            return pkg.isEmpty() ? name : pkg + "." + name;
        }

        String store() {
            return name + "Store";
        }

        String view() {
            return name + "View";
        }

        String mut() {
            return name + "Mut";
        }

        String qualifiedStore() {
            return pkg.isEmpty() ? store() : pkg + "." + store();
        }

        String qualifiedView() {
            return pkg.isEmpty() ? view() : pkg + "." + view();
        }

        String qualifiedMut() {
            return pkg.isEmpty() ? mut() : pkg + "." + mut();
        }

        /** The registry's field for the store: lower camel case of the name. */
        String fieldName() {
            return Character.toLowerCase(name.charAt(0)) + name.substring(1);
        }
    }
```

The three generated names are derived in one place from the record's simple name, and
`fieldName()` gives the registry's field and accessor (`mind`, `mind()`). The `pkg.isEmpty()`
branches handle the unnamed package, which the processor's tests use.

### 13.3 Parameters and phases

```java
    /** How a behaviour parameter is filled. */
    enum ParamKind {
        VIEW,
        MUT,
        DT,
        ALPHA,
        ENTITY,
        TRIGGER,
        KIND,
        A,
        B,
        SINGLETON
    }

    /** One behaviour parameter. */
    static final class Param {
        ParamKind kind;

        /** For views and muts. */
        Component component;

        boolean optional;

        /** For singletons: the declared type's qualified name. */
        String type;

        /** For singletons: the registry's field. */
        String field;

        boolean threadSafe;

        /** The parameter's name in the source, for messages. */
        String name;
    }

    /** The phases. */
    enum Phase {
        SPAWN("spawn"),
        DESPAWN("despawn"),
        FIXED_UPDATE("fixedUpdate"),
        UPDATE("update"),
        TRIGGER("trigger"),
        TURN("turn"),
        EVENT("event");

        final String json;

        Phase(String json) {
            this.json = json;
        }
    }
```

`ParamKind` is the table of §2 as an enum: every parameter of every behaviour ends up as exactly
one of these, and the generator's call-site `switch` (§16.6) has one case per constant, so a new
kind cannot be half supported. For a `SINGLETON`, `field` is the registry field that holds it —
or the literal `"this"` when the parameter is the registry itself.

### 13.4 Behaviours and the conflict rule

```java
    /** One behaviour method. */
    static final class Behaviour {
        /** The class's qualified name. */
        String owner;

        String ownerSimple;
        String method;
        Phase phase;

        /** The trigger kind or the signal kind. */
        int kindValue;

        int order;
        int stage;
        int sourceIndex;
        boolean parallel;
        int grain = 64;
        List<Param> params = new ArrayList<>();
        List<Component> without = new ArrayList<>();

        /** A unique Java identifier for the generated members. */
        String id() {
            return ownerSimple + "_" + method;
        }

        String name() {
            return ownerSimple + "." + method;
        }

        /** Required components: non-optional views and muts. */
        List<Component> required() {
            List<Component> out = new ArrayList<>();
            for (Param p : params) {
                if ((p.kind == ParamKind.VIEW || p.kind == ParamKind.MUT) && !p.optional) {
                    out.add(p.component);
                }
            }
            return out;
        }
```

`id()` is the prefix of every generated member for this behaviour: `SkeletonAi_think_v0`,
`SkeletonAi_think_loop`, `SkeletonAi_see_kernel`, `SkeletonAi_see_node`. Two behaviours cannot
share a prefix unless two classes with the same simple name declare a method with the same name,
which is possible across packages and would then fail to compile with a duplicate field; the
processor does not check it today (§24). `required()` is the query of §5.1, in parameter order,
which decides the tie-break of the driver (§10.3).

```java
        /** Components read: views, optional views and the excluded ones (membership). */
        List<String> reads() {
            List<String> out = new ArrayList<>();
            for (Param p : params) {
                if (p.kind == ParamKind.VIEW && !out.contains(p.component.name)) {
                    out.add(p.component.name);
                }
            }
            for (Component c : without) {
                if (!out.contains(c.name)) {
                    out.add(c.name);
                }
            }
            return out;
        }

        /** Components written: muts. */
        List<String> writes() {
            List<String> out = new ArrayList<>();
            for (Param p : params) {
                if (p.kind == ParamKind.MUT && !out.contains(p.component.name)) {
                    out.add(p.component.name);
                }
            }
            return out;
        }

        /** Whether {@code later} must wait for this one in a parallel group. */
        boolean conflictsWith(Behaviour later) {
            for (String w : writes()) {
                if (later.reads().contains(w) || later.writes().contains(w)) {
                    return true;
                }
            }
            for (String r : reads()) {
                if (later.writes().contains(r)) {
                    return true;
                }
            }
            return false;
        }
    }
```

This is §6.2 in code: $R$ is the `View` parameters plus the `@Without` components (membership is a
read: an entity added to or removed from `Dead` by a concurrent writer would change which rows this
behaviour visits), $W$ is the `Mut` parameters, optional or not. The first loop is $W_A \cap (R_B
\cup W_B)$, the second $R_A \cap W_B$. Lists rather than sets, with `contains`, because a behaviour
has a handful of components and this runs once per compilation.

### 13.5 Singletons and the registry

```java
    /** One singleton the registry takes. */
    static final class Singleton {
        String type;
        String field;
    }

    final List<Component> components = new ArrayList<>();
    final List<Behaviour> behaviours = new ArrayList<>();
    final List<Singleton> singletons = new ArrayList<>();
    String registryPackage;
    String registryName;
    int capacity = 256;

    String registryQualified() {
        return registryPackage == null || registryPackage.isEmpty()
                ? registryName
                : registryPackage + "." + registryName;
    }

    Component component(String simpleName) {
        for (Component c : components) {
            if (c.name.equals(simpleName)) {
                return c;
            }
        }
        return null;
    }

    Singleton singleton(String type) {
        for (Singleton s : singletons) {
            if (s.type.equals(type)) {
                return s;
            }
        }
        Singleton s = new Singleton();
        s.type = type;
        String simple = type.substring(type.lastIndexOf('.') + 1).replace('$', '_');
        String base = Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
        String field = base;
        int n = 2;
        while (hasSingletonField(field) || isReservedField(field)) {
            field = base + n++;
        }
        s.field = field;
        singletons.add(s);
        return s;
    }
```

`singleton(type)` is a find-or-create keyed by the **qualified** type name: two behaviours that
both take `Crowd` share one field and one constructor parameter. The field name is the simple name
with its first letter lower-cased (`Crowd` → `crowd`). The type comes from
`TypeElement.getQualifiedName()`, which writes a nested class with dots (`pkg.Outer.Inner`), so
`lastIndexOf('.')` gives `Inner` and the field is `inner`; the `replace('$', '_')` would only act on
a binary name with `$`, which this path never produces. And
if that name is taken — by another singleton with the same simple name in another package, or by
one of the registry's own fields — a number is appended: `level`, `level2`.

```java
    private boolean hasSingletonField(String field) {
        for (Singleton s : singletons) {
            if (s.field.equals(field)) {
                return true;
            }
        }
        return false;
    }

    private boolean isReservedField(String field) {
        if (field.equals("entities")
                || field.equals("jobs")
                || field.equals("dt")
                || field.equals("alpha")
                || field.equals("stores")) {
            return true;
        }
        for (Component c : components) {
            if (c.fieldName().equals(field)) {
                return true;
            }
        }
        return false;
    }
```

The reserved names are the registry's own fields (§16.1) and every store's field: a singleton of
type `Mind` next to a component `Mind` would otherwise generate two fields called `mind`. (The
behaviours' generated members start with the class name and an underscore, so they cannot clash
with a lower-camel field.)

```java
    List<Behaviour> ofPhase(Phase phase) {
        List<Behaviour> out = new ArrayList<>();
        for (Behaviour b : behaviours) {
            if (b.phase == phase) {
                out.add(b);
            }
        }
        return out;
    }

    /** The behaviours of {@code phase} with {@code stage}, in order. */
    List<Behaviour> ofStage(Phase phase, int stage) {
        List<Behaviour> out = new ArrayList<>();
        for (Behaviour b : ofPhase(phase)) {
            if (b.stage == stage) {
                out.add(b);
            }
        }
        return out;
    }

    /** The distinct stages of {@code phase}, ascending. */
    List<Integer> stages(Phase phase) {
        List<Integer> out = new ArrayList<>();
        for (Behaviour b : ofPhase(phase)) {
            if (!out.contains(b.stage)) {
                out.add(b.stage);
            }
        }
        java.util.Collections.sort(out);
        return out;
    }
}
```

Three filters the registry generator asks: the behaviours of a phase, of a stage within it, and
the stages a phase has. They return new lists; the processor runs once per compilation and is not
a hot path, so the brief's rules for engine code do not apply to it, and clarity wins.

## 14. Step 6: `ComponentProcessor`

### 14.1 The class and `process`

```java
public final class ComponentProcessor extends AbstractProcessor {
    static final String PKG = "dev.jeddy.components.";

    private boolean done;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(PKG + "*");
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
        if (done || env.processingOver()) {
            return false;
        }
        Model model = new Model();
        boolean ok = collect(env, model);
        if (model.components.isEmpty()
                && model.behaviours.isEmpty()
                && model.registryName == null) {
            return false;
        }
        done = true;
        if (!ok) {
            return false; // errors were reported; javac stops after this round
        }
        generate(model);
        return false;
    }
```

- **`AbstractProcessor`** gives the `processingEnv` field (the `Filer`, the `Messager` for
  diagnostics, `Elements` for lookups) that javac fills through `init`.
- **`latestSupported()`** rather than a fixed version: a processor that names an older release
  makes javac warn, on every JDK upgrade, that the processor supports a source version lower than
  the one in use. The processor reads nothing version-specific, so it claims whatever the running
  javac supports.
- **`PKG + "*"`** claims every annotation in `dev.jeddy.components`: one processor, one package,
  and a new phase annotation needs no registration.
- **`done`** makes the processor do its work once. javac keeps calling a processor in every round
  after the first one it was asked to process, the last round included (the contract in
  `Processor`'s Javadoc), with an empty set of annotations when the round's new files carry none
  of its own. Without the flag, a later round could collect again: the generated files carry no
  `@Component`, so `collect` would find nothing and return early, but if *another* processor
  generated an annotated file in a later round this one would try to create `MindStore.java` a
  second time, and the `Filer` refuses that with a `FilerException`. `processingOver()` is the
  last round, where there is nothing left to do.
- **`process` returns `false`** always: the annotations are not *claimed*, so another processor
  may see them. With `-Xlint:processing` on, javac would then warn "No processor claimed any of
  these annotations". The engine's lint set is `-Xlint:all,-module,-processing`, so the category
  is off; it has been off since the build scaffold of milestone M0, before any processor existed,
  and no decision records why (D15 explains only `-module`). The processor's own tests compile
  with `-Xlint:all,-processing` too (§22.11).
- **Errors stop generation.** `collect` reports every problem it finds (not only the first) through
  the `Messager`, each at its element; `ok` is `false` and nothing is generated. javac then ends
  the compilation. Because nothing was generated, the deferred "cannot find symbol" errors for the
  views the behaviours named are reported too, *after* the processor's own messages (checked for
  this chapter with javac 27: a `float speed` parameter gives "a float parameter of a behaviour is
  dt or alpha" first and "cannot find symbol: class HealthMut" second). Read the first errors of a
  failed build; the rest are consequences.

### 14.2 `collect`: records, the registry, the classes

```java
    private boolean collect(RoundEnvironment env, Model model) {
        boolean ok = true;
        List<TypeElement> records = new ArrayList<>();
        for (Element e : annotated(env, "Component")) {
            if (e.getKind() != ElementKind.RECORD) {
                error(e, "@Component must be on a record");
                ok = false;
                continue;
            }
            records.add((TypeElement) e);
        }
        records.sort(Comparator.comparing(t -> t.getQualifiedName().toString()));
        for (TypeElement t : records) {
            ok &= collectComponent(t, model);
        }
```

`annotated(env, "Component")` (§14.9) is `env.getElementsAnnotatedWith` for the annotation of that
name. The records are **sorted by qualified name** before anything else, so the stores' order in
the registry (fields, `stores[]`, the schema) is the same whatever order the files were given in:
the generated code is deterministic, which incremental builds and diffs depend on.

```java
        List<Element> packages = new ArrayList<>(annotated(env, "Components"));
        if (packages.size() > 1) {
            for (Element p : packages) {
                error(p, "one @Components package per compilation; found " + packages.size());
            }
            ok = false;
        } else if (packages.size() == 1) {
            Element p = packages.get(0);
            if (!(p instanceof PackageElement pe)) {
                error(p, "@Components must be on a package (package-info.java)");
                ok = false;
            } else {
                AnnotationMirror a = annotation(p, "Components");
                model.registryPackage = pe.getQualifiedName().toString();
                model.registryName = string(a, "name", "");
                model.capacity = integer(a, "capacity", 256);
                if (!SourceVersion.isIdentifier(model.registryName)
                        || SourceVersion.isKeyword(model.registryName)) {
                    error(p, "@Components name must be a Java identifier: " + model.registryName);
                    ok = false;
                }
            }
        }
```

The registry's name comes from the annotation as a string, so it is checked to be a legal
identifier that is not a keyword (`SourceVersion.isIdentifier` and `isKeyword` are the JDK's own
tests): `@Components(name = "my game")` would otherwise produce a file javac cannot parse, with an
error in a file you did not write.

```java
        List<TypeElement> classes = new ArrayList<>();
        for (Element e : annotated(env, "Behaviour")) {
            if (e.getKind() != ElementKind.CLASS) {
                error(e, "@Behaviour must be on a class");
                ok = false;
                continue;
            }
            classes.add((TypeElement) e);
        }
        classes.sort(Comparator.comparing(t -> t.getQualifiedName().toString()));
        for (TypeElement t : classes) {
            ok &= collectBehaviours(t, model);
        }
        if (!model.behaviours.isEmpty() && model.registryName == null) {
            error(
                    classes.get(0),
                    "behaviours need a registry: put @Components(name = \"...\") on a"
                            + " package-info.java");
            ok = false;
        }
        // The registry's constructor takes the singletons in alphabetical order of their field
        // names, whatever the order the behaviours mention them in: stable across edits.
        model.singletons.sort(Comparator.comparing(sg -> sg.field));
        model.behaviours.sort(
                Comparator.comparingInt((Behaviour b) -> b.stage)
                        .thenComparingInt(b -> b.order)
                        .thenComparing(b -> b.owner)
                        .thenComparingInt(b -> b.sourceIndex));
        return ok;
    }
```

- **Components without behaviours need no registry** (`componentsWithoutBehavioursNeedNoRegistry`
  in the tests): a module may declare records for another module's game. Behaviours without a
  registry are an error, reported at the first behaviour class, because the loops have nowhere to
  live.
- **The singletons are sorted by field name**, which is the type's simple name with a lower-case
  first letter (§13.5), so the constructor's parameter order is alphabetical by simple type name
  and does not change when a behaviour is edited to mention `Crowd` before `Animator`. (The sort is
  `String.compareTo`, by UTF-16 code unit: all upper-case letters come before all lower-case ones,
  which only matters for a type whose second letter is a capital.)
- **The behaviours are sorted by stage, then `@Behaviour(order)`, then the class's qualified name,
  then source order** within the class. The phase is not a key: `ofPhase` filters later, and the
  schema lists behaviours in this one order.

### 14.3 `collectComponent`: one record, field by field

```java
    private boolean collectComponent(TypeElement t, Model model) {
        boolean ok = true;
        Component c = new Component();
        c.pkg = packageOf(t);
        c.name = t.getSimpleName().toString();
        c.doc = string(annotation(t, "Component"), "doc", "");
        if (!t.getModifiers().contains(Modifier.PUBLIC)) {
            error(t, "a @Component record must be public (the generated store is)");
            ok = false;
        }
        if (t.getNestingKind().isNested()) {
            error(t, "a @Component record must be top-level (its store is generated beside it)");
            ok = false;
        }
        Component clash = model.component(c.name);
        if (clash != null) {
            error(
                    t,
                    "component names must be unique in a game: "
                            + c.name
                            + " is also "
                            + clash.qualified());
            ok = false;
        }
```

Three shape rules with their reasons in the messages. Public, because the generated store is a
public top-level class whose `add(int, Mind)`, `get` and `at` take or return the record: javac
would compile them with a package-private record (it is legal, and no lint flags it), but they
would be unusable from any other package, and the registry, the editor and the game's other
packages are exactly who calls them. Top-level, because `Filer.createSourceFile("pkg.Name")`
writes a top-level file; a nested record's store would have to be a nested class of a file the
processor does not own. Unique **simple** names across packages, because levels and snapshots name
a component by its simple name (`"Mind"`), and because `component(String)` resolves view
parameters by simple name (§14.5).

```java
        for (RecordComponentElement rc : t.getRecordComponents()) {
            Field f = new Field();
            f.name = rc.getSimpleName().toString();
            f.javaType = rc.asType().toString();
            TypeMirror type = rc.asType();
            boolean entityRef = annotation(rc, "EntityRef") != null;
            AnnotationMirror assetRef = annotation(rc, "AssetRef");
            switch (type.getKind()) {
                case INT -> {
                    f.kind = entityRef ? Kind.ENTITY : assetRef != null ? Kind.ASSET : Kind.INT;
                    if (entityRef && assetRef != null) {
                        error(rc, "a field is an @EntityRef or an @AssetRef, not both");
                        ok = false;
                    }
                    if (assetRef != null) {
                        f.assetKind = string(assetRef, "value", "");
                    }
                }
                case LONG -> f.kind = Kind.LONG;
                case FLOAT -> f.kind = Kind.FLOAT;
                case BOOLEAN -> f.kind = Kind.BOOL;
                case DECLARED -> {
                    TypeElement te = (TypeElement) ((DeclaredType) type).asElement();
                    String q = te.getQualifiedName().toString();
                    if (te.getKind() == ElementKind.ENUM) {
                        f.kind = Kind.ENUM;
                        f.javaType = q;
                        for (Element ec : te.getEnclosedElements()) {
                            if (ec.getKind() == ElementKind.ENUM_CONSTANT) {
                                f.enumValues.add(ec.getSimpleName().toString());
                            }
                        }
                        if (f.enumValues.isEmpty()) {
                            error(rc, "an enum field needs at least one constant");
                            ok = false;
                        }
                    } else if (q.equals("dev.jeddy.math.Vec2f")) {
                        f.kind = Kind.VEC2;
                    } else if (q.equals("dev.jeddy.math.Vec3f")) {
                        f.kind = Kind.VEC3;
                    } else {
                        error(
                                rc,
                                "unsupported component field type "
                                        + q
                                        + ": use int, long, float, boolean, an enum, Vec2f or"
                                        + " Vec3f");
                        ok = false;
                        continue;
                    }
                }
                default -> {
                    error(
                            rc,
                            "unsupported component field type "
                                    + type
                                    + ": use int, long, float, boolean, an enum, Vec2f or Vec3f");
                    ok = false;
                    continue;
                }
            }
```

- **`t.getRecordComponents()`** lists the record's components in declaration order: the compiler's
  model knows records since Java 16, and this is the only place the processor needs it.
- **The `switch` on `type.getKind()`** is over `TypeKind`: the primitive kinds `INT`, `LONG`,
  `FLOAT`, `BOOLEAN` match directly; everything else that is a class is `DECLARED`, and its
  `TypeElement` says whether it is an `enum` (then the constants are collected in ordinal order,
  because `getEnclosedElements()` keeps declaration order) or one of the two vector records,
  matched by qualified name. A `String`, a `double`, an array or a `short` lands in an error.
- **An enum needs a constant** because the default is "the first constant" (ordinal 0) and
  `STATE_VALUES[0]` of an empty enum would throw at run time.
- **The error path uses `continue`**, so a bad field is skipped and the rest of the record is still
  checked: one compile shows every problem.

```java
            if ((entityRef || assetRef != null) && type.getKind() != TypeKind.INT) {
                error(rc, "@EntityRef and @AssetRef go on int fields (handles)");
                ok = false;
            }
            f.defaults = new double[f.kind.columns()];
            AnnotationMirror def = annotation(rc, "Default");
            if (def != null) {
                ok &= parseDefault(rc, f, string(def, "value", ""));
            }
            AnnotationMirror range = annotation(rc, "Range");
            if (range != null) {
                f.min = number(range, "min", Double.NaN);
                f.max = number(range, "max", Double.NaN);
                if (!(f.kind == Kind.INT || f.kind == Kind.LONG || f.kind == Kind.FLOAT)) {
                    error(rc, "@Range goes on a numeric field");
                    ok = false;
                }
            }
            f.angle = annotation(rc, "Angle") != null;
            if (f.angle && f.kind != Kind.FLOAT) {
                error(rc, "@Angle goes on a float field");
                ok = false;
            }
            f.color = annotation(rc, "Color") != null;
            if (f.color && f.kind != Kind.VEC3) {
                error(rc, "@Color goes on a Vec3f field");
                ok = false;
            }
            AnnotationMirror doc = annotation(rc, "Doc");
            if (doc != null) {
                f.doc = string(doc, "value", "");
            }
            c.fields.add(f);
        }
        model.components.add(c);
        return ok;
    }
```

The hint checks are what `@Target` cannot express: an annotation can say "on a record component"
but not "on a `float` record component". `f.defaults` is allocated with one slot per column
*before* `parseDefault`, which fills it; without a `@Default` the zeros it starts with are the
defaults (0, `false`, the first constant, the zero vector).

### 14.4 `parseDefault`

```java
    private boolean parseDefault(Element at, Field f, String text) {
        try {
            switch (f.kind) {
                case INT, ENTITY, ASSET -> f.defaults[0] = Integer.parseInt(text.trim());
                case LONG -> f.defaults[0] = Long.parseLong(text.trim());
                case FLOAT -> f.defaults[0] = Float.parseFloat(text.trim());
                case BOOL -> {
                    String t = text.trim();
                    if (!t.equals("true") && !t.equals("false")) {
                        throw new NumberFormatException(t);
                    }
                    f.defaults[0] = t.equals("true") ? 1 : 0;
                }
                case ENUM -> {
                    int i = f.enumValues.indexOf(text.trim());
                    if (i < 0) {
                        error(at, "@Default " + text + " is not a constant of " + f.javaType);
                        return false;
                    }
                    f.defaults[0] = i;
                }
                case VEC2, VEC3 -> {
                    String[] parts = text.split(",");
                    if (parts.length != f.defaults.length) {
                        error(
                                at,
                                "@Default of a vector needs "
                                        + f.defaults.length
                                        + " comma-separated numbers");
                        return false;
                    }
                    for (int i = 0; i < parts.length; i++) {
                        f.defaults[i] = Float.parseFloat(parts[i].trim());
                    }
                }
            }
            return true;
        } catch (NumberFormatException e) {
            error(at, "@Default \"" + text + "\" is not a " + f.kind.json);
            return false;
        }
    }
```

The text of §9.1 parsed against the kind: `Integer.parseInt` for the three `int` kinds (an
`@EntityRef` default is normally 0, the null handle; an `@AssetRef` default of 0 means "none"),
`Float.parseFloat` for floats and for each part of a vector, an exact `true`/`false` for booleans
(a `NumberFormatException` is thrown by hand so the one `catch` reports it in the same words), and
a name looked up among the constants for an enum. The message names the kind's JSON spelling
(`is not a int`, as the test checks), which is what the editor would also show.

### 14.5 `collectBehaviours`: one class, method by method

This is the longest method of the processor, shown in four stages.

**Stage 1: find the phase.**

```java
    private boolean collectBehaviours(TypeElement t, Model model) {
        boolean ok = true;
        int order = integer(annotation(t, "Behaviour"), "order", 0);
        int index = 0;
        int found = 0;
        for (Element e : t.getEnclosedElements()) {
            if (e.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement m = (ExecutableElement) e;
            Phase phase = null;
            AnnotationMirror phaseAnnotation = null;
            int phases = 0;
            for (Phase p : Phase.values()) {
                AnnotationMirror a = annotation(m, phaseAnnotationName(p));
                if (a != null) {
                    phases++;
                    phase = p;
                    phaseAnnotation = a;
                }
            }
            if (phases == 0) {
                continue;
            }
            found++;
            if (phases > 1) {
                error(m, "a behaviour has one phase annotation");
                ok = false;
                continue;
            }
```

Every method of the class is examined; one without a phase annotation is simply not a behaviour (a
private helper, say), one with two is an error. `getEnclosedElements()` is in source order, and
`index` counts the behaviours in that order for the stage sort (§14.2).

**Stage 2: the method's shape.**

```java
            Behaviour b = new Behaviour();
            b.owner = t.getQualifiedName().toString();
            b.ownerSimple = t.getSimpleName().toString();
            b.method = m.getSimpleName().toString();
            b.phase = phase;
            b.order = order;
            b.sourceIndex = index++;
            b.kindValue = integer(phaseAnnotation, "value", 0);
            b.stage = integer(phaseAnnotation, "stage", 0);
            if (!m.getModifiers().contains(Modifier.STATIC)
                    || m.getModifiers().contains(Modifier.PRIVATE)) {
                error(m, "a behaviour is a non-private static method");
                ok = false;
            }
            if (m.getReturnType().getKind() != TypeKind.VOID) {
                error(m, "a behaviour returns void");
                ok = false;
            }
            AnnotationMirror parallel = annotation(m, "Parallel");
            if (parallel != null) {
                b.parallel = true;
                b.grain = Math.max(1, integer(parallel, "grain", 64));
                if (phase != Phase.FIXED_UPDATE && phase != Phase.UPDATE) {
                    error(m, "@Parallel applies to @OnFixedUpdate and @OnUpdate behaviours");
                    ok = false;
                }
            }
            AnnotationMirror without = annotation(m, "Without");
            if (without != null) {
                for (TypeMirror tm : types(without, "value")) {
                    String simple = simpleName(tm.toString());
                    Component c = model.component(simple);
                    if (c == null) {
                        error(m, "@Without names a component; " + tm + " is not one");
                        ok = false;
                    } else {
                        b.without.add(c);
                    }
                }
            }
```

`kindValue` and `stage` are read from the phase annotation with a default of 0, so the same two
lines serve `@OnTrigger(ENTER)`, `@OnEvent(HURT)` and `@OnFixedUpdate(stage = 1)`: an annotation
that lacks the value yields the fallback. Static and not private, because the registry calls the
method by its qualified name from another class. `@Parallel` only on the two loop phases; the
per-entity phases run one entity at a time and have nothing to split. A grain below 1 is clamped.
`@Without`'s classes arrive as `TypeMirror`s (that is what an annotation's `Class` value is in the
compiler's model) and are matched to collected components by simple name.

**Stage 3: the parameters.** The classification of §2, in the order it is tried:

```java
            for (VariableElement v : m.getParameters()) {
                Param p = new Param();
                p.name = v.getSimpleName().toString();
                p.optional = annotation(v, "Optional") != null;
                p.threadSafe = annotation(v, "ThreadSafe") != null;
                TypeMirror type = v.asType();
                String written = type.toString();
                String simple = simpleName(written);
                Component viewOf = null;
                boolean mut = false;
                if (simple.endsWith("View")
                        && model.component(simple.substring(0, simple.length() - 4)) != null) {
                    viewOf = model.component(simple.substring(0, simple.length() - 4));
                } else if (simple.endsWith("Mut")
                        && model.component(simple.substring(0, simple.length() - 3)) != null) {
                    viewOf = model.component(simple.substring(0, simple.length() - 3));
                    mut = true;
                }
                if (viewOf != null && type.getKind() == TypeKind.DECLARED) {
                    // The type exists (a later compilation): it must be the generated one.
                    String q =
                            ((TypeElement) ((DeclaredType) type).asElement())
                                    .getQualifiedName()
                                    .toString();
                    if (!q.equals(mut ? viewOf.qualifiedMut() : viewOf.qualifiedView())) {
                        viewOf = null;
                    }
                }
```

Here is §4.2 in practice. `type.toString()` of an error type is the name **as written**
(`MindMut`, or `dev.jeddy.samples.iso.MindMut` if the author wrote it qualified), and `simpleName`
strips a package and any `<...>`. A simple name ending in `View` or `Mut` whose prefix is a
collected component is a view parameter. If the type *does* exist already (`DECLARED`, for example
an IDE build that kept the generated sources while recompiling the records with them), its
qualified name must be the generated class's; a game's own class that happens to be called
`MindView` is then not mistaken for the view. Note what this does *not* cover: `model.component`
knows only the records of **this** compilation, so a behaviour compiled in a module whose records
were compiled elsewhere (another module, an earlier build) does not recognise `MindView` as a view
at all; the parameter falls through to the singleton branch below. Records and their behaviours
must be compiled together, in the module that carries `@Components`.

```java
                if (viewOf != null) {
                    p.kind = mut ? ParamKind.MUT : ParamKind.VIEW;
                    p.component = viewOf;
                } else if (model.registryName != null
                        && simple.equals(model.registryName)
                        && (type.getKind() == TypeKind.ERROR
                                || written.equals(model.registryQualified()))) {
                    // The registry itself: behaviours despawn, spawn and reach stores through it.
                    p.kind = ParamKind.SINGLETON;
                    p.type = model.registryQualified();
                    p.field = "this";
                } else if (type.getKind() == TypeKind.FLOAT) {
                    switch (p.name) {
                        case "dt" -> p.kind = ParamKind.DT;
                        case "alpha" -> p.kind = ParamKind.ALPHA;
                        default -> {
                            error(v, "a float parameter of a behaviour is dt or alpha");
                            ok = false;
                            continue;
                        }
                    }
                } else if (type.getKind() == TypeKind.INT) {
                    switch (p.name) {
                        case "entity" -> p.kind = ParamKind.ENTITY;
                        case "trigger" -> p.kind = ParamKind.TRIGGER;
                        case "kind" -> p.kind = ParamKind.KIND;
                        case "a" -> p.kind = ParamKind.A;
                        case "b" -> p.kind = ParamKind.B;
                        default -> {
                            error(
                                    v,
                                    "an int parameter of a behaviour is entity, trigger, kind, a or"
                                            + " b");
                            ok = false;
                            continue;
                        }
                    }
                } else if (type.getKind() == TypeKind.DECLARED) {
                    p.kind = ParamKind.SINGLETON;
                    p.type =
                            ((TypeElement) ((DeclaredType) type).asElement())
                                    .getQualifiedName()
                                    .toString();
                    p.field = model.singleton(p.type).field;
                } else if (type.getKind() == TypeKind.ERROR) {
                    error(
                            v,
                            "unknown parameter type "
                                    + written
                                    + " (a component view is <Component>View or <Component>Mut of"
                                    + " a @Component record in this compilation)");
                    ok = false;
                    continue;
                } else {
                    error(v, "unsupported behaviour parameter type " + written);
                    ok = false;
                    continue;
                }
```

- **The registry's own type** is recognised by name too, since it is generated in the same round;
  its "field" is `this`, which the call site emits verbatim (§16.6).
- **`float` and `int` parameters are classified by name.** A `float speed` is rejected with the
  list of allowed names: a behaviour's scalars come from the phase, and a per-behaviour constant
  belongs in a `static final` field or a singleton. This is what lets the generator write
  `think(..., dt, e, ...)` without a mapping table per behaviour.
- **Any other declared type is a singleton**, registered through `model.singleton` (§13.5), which
  hands back the field it will live in.
- **An error type that is not a view or the registry** is "unknown parameter type GhostView", with
  the rule in the message; a primitive that is neither `float` nor `int` (a `long`, a `boolean`) is
  "unsupported".

**Stage 4: the per-phase and parallel checks, and the query must not be empty.**

```java
                if (p.optional && p.kind != ParamKind.VIEW && p.kind != ParamKind.MUT) {
                    error(v, "@Optional goes on a component view parameter");
                    ok = false;
                }
                ok &= checkPhaseParam(v, b, p);
                b.params.add(p);
            }
            if (b.required().isEmpty()) {
                error(m, "a behaviour needs at least one required (non-optional) component view");
                ok = false;
            }
            model.behaviours.add(b);
        }
        if (found == 0) {
            error(t, "a @Behaviour class needs at least one phase-annotated static method");
            ok = false;
        }
        return ok;
    }
```

A behaviour with no required component would have no store to drive from: `driveExpression`
(§16.5) needs at least one. A `@Behaviour` class with no behaviour is an error rather than silence,
because the most likely cause is a forgotten phase annotation.

### 14.6 `checkPhaseParam`

```java
    private boolean checkPhaseParam(VariableElement v, Behaviour b, Param p) {
        boolean allowed =
                switch (p.kind) {
                    case DT -> b.phase == Phase.FIXED_UPDATE || b.phase == Phase.UPDATE;
                    case ALPHA -> b.phase == Phase.UPDATE;
                    case TRIGGER, KIND -> b.phase == Phase.TRIGGER;
                    case A, B -> b.phase == Phase.EVENT;
                    default -> true;
                };
        if (!allowed) {
            error(
                    v,
                    p.name
                            + " is not a parameter of an "
                            + phaseAnnotationName(b.phase)
                            + " behaviour");
            return false;
        }
        if (b.parallel) {
            if (p.kind == ParamKind.SINGLETON && "this".equals(p.field)) {
                error(v, "a @Parallel behaviour may not take the registry (structural changes)");
                return false;
            }
            if (p.kind == ParamKind.SINGLETON && !p.threadSafe) {
                error(
                        v,
                        "a @Parallel behaviour may only take a singleton marked @ThreadSafe at the"
                                + " parameter");
                return false;
            }
        }
        return true;
    }

    private static String phaseAnnotationName(Phase p) {
        return switch (p) {
            case SPAWN -> "OnSpawn";
            case DESPAWN -> "OnDespawn";
            case FIXED_UPDATE -> "OnFixedUpdate";
            case UPDATE -> "OnUpdate";
            case TRIGGER -> "OnTrigger";
            case TURN -> "OnTurn";
            case EVENT -> "OnEvent";
        };
    }
```

The table of which scalar each phase provides: `dt` in the two loop phases, `alpha` only per frame,
`trigger` and `kind` only for triggers, `a` and `b` only for signals, `entity` and views
everywhere. Then the two parallel rules of §6.2, enforced where the processor can: the registry is
the one object through which a behaviour changes structure, so a parallel behaviour may not have
it; and a singleton needs `@ThreadSafe` on the parameter. The processor cannot see what the body
does with the singleton, so this is the author's promise, placed where a reviewer will read it.

### 14.7 `generate` and `write`

```java
    private void generate(Model model) {
        Filer filer = processingEnv.getFiler();
        try {
            for (Component c : model.components) {
                write(filer, c.qualifiedStore(), StoreGen.store(c));
                write(filer, c.qualifiedView(), StoreGen.view(c));
                write(filer, c.qualifiedMut(), StoreGen.mut(c));
            }
            if (model.registryName != null) {
                String q =
                        model.registryPackage.isEmpty()
                                ? model.registryName
                                : model.registryPackage + "." + model.registryName;
                write(filer, q, RegistryGen.registry(model));
                try (Writer w =
                        filer.createResource(
                                        StandardLocation.CLASS_OUTPUT,
                                        "",
                                        "META-INF/jeddy/components.json")
                                .openWriter()) {
                    w.write(SchemaGen.json(model));
                }
            }
        } catch (IOException e) {
            processingEnv
                    .getMessager()
                    .printMessage(Diagnostic.Kind.ERROR, "component processor: " + e);
        }
    }

    private static void write(Filer filer, String qualifiedName, String source) throws IOException {
        try (Writer w = filer.createSourceFile(qualifiedName).openWriter()) {
            w.write(source);
        }
    }
```

Three files per component, then the registry and the schema when there is one. Every file is a
`String` built by a generator and written whole; the `Filer` decides where it goes (§4.1). An
`IOException` is turned into a compiler error with no element, because there is no source line to
point at.

### 14.8 Diagnostics

```java
    private void error(Element at, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, at);
    }
```

Every rejection goes through this one method with the **element** it is about: a record component,
a parameter, a method, a class, a package. javac prints the file and line of that element, and an
IDE underlines it. `ComponentProcessorTest` (§22.11) compiles small wrong games and checks that each
message appears.

### 14.9 Helpers: reading annotations by name

```java
    private Set<? extends Element> annotated(RoundEnvironment env, String simple) {
        TypeElement t = processingEnv.getElementUtils().getTypeElement(PKG + simple);
        return t == null ? Set.of() : env.getElementsAnnotatedWith(t);
    }

    private static String packageOf(TypeElement t) {
        Element e = t;
        while (e != null && e.getKind() != ElementKind.PACKAGE) {
            e = e.getEnclosingElement();
        }
        return e == null ? "" : ((PackageElement) e).getQualifiedName().toString();
    }

    private static String simpleName(String written) {
        int generic = written.indexOf('<');
        String s = generic >= 0 ? written.substring(0, generic) : written;
        return s.substring(s.lastIndexOf('.') + 1);
    }

    private static AnnotationMirror annotation(Element e, String simple) {
        for (AnnotationMirror a : e.getAnnotationMirrors()) {
            Element t = a.getAnnotationType().asElement();
            if (t instanceof TypeElement te
                    && te.getQualifiedName().toString().equals(PKG + simple)) {
                return a;
            }
        }
        return null;
    }
```

- **`annotated`** looks the annotation's `TypeElement` up by qualified name through `Elements`; if
  the `components` module is not on the compile path at all (a module that merely has the
  processor on its path), `getTypeElement` returns `null` and the processor does nothing.
- **`packageOf`** climbs the enclosing elements to the package; a top-level record's parent *is*
  the package, but the loop handles anything.
- **`annotation(e, simple)`** is the by-name match of §4.3: it walks the element's annotation
  mirrors and compares the annotation type's qualified name with `"dev.jeddy.components." +
  simple`. No `import dev.jeddy.components.Component` anywhere in the processor.

```java
    private static Object value(AnnotationMirror a, String name) {
        if (a == null) {
            return null;
        }
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e :
                a.getElementValues().entrySet()) {
            if (e.getKey().getSimpleName().contentEquals(name)) {
                return e.getValue().getValue();
            }
        }
        return null;
    }

    private static String string(AnnotationMirror a, String name, String fallback) {
        Object v = value(a, name);
        return v instanceof String s ? s : fallback;
    }

    private static int integer(AnnotationMirror a, String name, int fallback) {
        Object v = value(a, name);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    private static double number(AnnotationMirror a, String name, double fallback) {
        Object v = value(a, name);
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    private static List<TypeMirror> types(AnnotationMirror a, String name) {
        List<TypeMirror> out = new ArrayList<>();
        Object v = value(a, name);
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof AnnotationValue av && av.getValue() instanceof TypeMirror tm) {
                    out.add(tm);
                }
            }
        } else if (v instanceof TypeMirror tm) {
            out.add(tm);
        }
        return out;
    }
```

`AnnotationMirror.getElementValues()` is a map from the annotation's methods (`name()`,
`capacity()`) to the values written at the use site; a value left at its default is **absent**
from the map, which is why every reader takes a `fallback`. `AnnotationValue.getValue()` returns a
`String` for a string, a boxed `Integer`/`Double` for a number, a `TypeMirror` for a `Class`
value, and a `List<AnnotationValue>` for an array; the helpers pattern-match on those. `types`
accepts a single `TypeMirror` as well as a list, so that `@Without(Dead.class)` written without
braces (legal for an array-typed value) is handled whichever shape the compiler's model gives it.
## 15. Step 7: `StoreGen`

The generators build Java source in a `StringBuilder`, line by line. There is no template engine
and no syntax tree builder. The processor depends on the JDK alone (its `build.gradle.kts` says
so, and DESIGN §22 names `javax.annotation.processing`, "in the JDK", as the mechanism), so a
library such as a Java-source writer is out; and it is not needed: the output is small, the shape
is fixed, and the generated text is meant to be read, so the generator reads like the file it
produces with the variable parts filled in. `StoreGen` has three entry points, `store(c)`, `view(c)` and `mut(c)`,
and this section shows each beside its output for the demo's `Mind` (L03 §10–11 already quote
most of that output; it is repeated here only where the generator needs explaining).

### 15.1 Helpers

```java
final class StoreGen {
    private StoreGen() {}

    private static String header(Component c) {
        return c.pkg.isEmpty() ? "" : "package " + c.pkg + ";\n\n";
    }

    private static String doc(Component c, String what) {
        return "/** "
                + what
                + " of {@link "
                + c.name
                + "}; generated by the Jeddy component processor, do not edit. */\n";
    }

    private static String literal(Field f, int column) {
        double v = f.defaults[column];
        return switch (f.kind) {
            case INT, ENUM, ENTITY, ASSET -> Integer.toString((int) v);
            case LONG -> Long.toString((long) v) + "L";
            case FLOAT, VEC2, VEC3 -> Float.toString((float) v) + "f";
            case BOOL -> v != 0 ? "true" : "false";
        };
    }

    private static List<Field> enumFields(Component c) {
        List<Field> out = new ArrayList<>();
        for (Field f : c.fields) {
            if (f.kind == Kind.ENUM) {
                out.add(f);
            }
        }
        return out;
    }

    /** The static array of an enum's constants in the store. */
    static String valuesField(Field f) {
        return f.name.toUpperCase() + "_VALUES";
    }
```

`literal` turns a stored `double` default back into a Java literal of the column's type: `-1` for
`Monster.agent`, `0.0f` for a float, `1234567890123L` for the test sample's `long`, `true` for
`Sight.visible`. `Float.toString` writes a form Java parses back to the same `float` (`0.5f`), so
the default survives the round trip through text exactly. `valuesField` names the per-enum
constant array (`STATE_VALUES`) that both the store and the view use.

### 15.2 The store: fields, the column tables and the constructor

```java
    static String store(Component c) {
        StringBuilder b = new StringBuilder(header(c));
        b.append("import dev.jeddy.components.Store;\n");
        b.append("import dev.jeddy.scene.EntityPool;\n");
        b.append("import java.util.Arrays;\n\n");
        b.append(doc(c, "The store"));
        b.append("public final class ").append(c.store()).append(" extends Store {\n");
        // Column fields.
        List<String> columnNames = new ArrayList<>();
        List<String> columnFields = new ArrayList<>();
        List<Field> columnOf = new ArrayList<>();
        List<Integer> columnPart = new ArrayList<>();
        for (Field f : c.fields) {
            List<String> names = f.columns();
            List<String> fields = f.columnFields();
            for (int k = 0; k < names.size(); k++) {
                columnNames.add(names.get(k));
                columnFields.add(fields.get(k));
                columnOf.add(f);
                columnPart.add(k);
            }
            b.append("    ").append(f.kind.columnJava).append("[] ");
            for (int k = 0; k < fields.size(); k++) {
                b.append(k > 0 ? ", " : "").append(fields.get(k));
            }
            b.append(";\n");
        }
```

Four parallel lists flatten the record's fields into **columns**: for each scalar column its file
name, its Java field, the field it came from and which part of a vector it is (0, 1, 2). Everything
after this loop is written per column, so a `Vec3f` field costs nothing special anywhere else. The
column fields are **package-private** (`int[] state;`): the views in the same package read them
directly, and nothing else should (L03 §10).

```java
        b.append("\n    static final String[] COLUMNS = {");
        for (int i = 0; i < columnNames.size(); i++) {
            b.append(i > 0 ? ", " : "").append('"').append(columnNames.get(i)).append('"');
        }
        b.append("};\n    static final int[] TYPES = {");
        for (int i = 0; i < columnOf.size(); i++) {
            b.append(i > 0 ? ", " : "").append(columnOf.get(i).kind.storeType);
        }
        b.append("};\n");
        for (Field f : enumFields(c)) {
            b.append("    static final ")
                    .append(f.javaType)
                    .append("[] ")
                    .append(valuesField(f))
                    .append(" = ")
                    .append(f.javaType)
                    .append(".values();\n");
        }
        // Constructor.
        b.append("\n    /** Creates an empty store registered with {@code pool}. */\n");
        b.append("    public ").append(c.store()).append("(EntityPool pool, int capacity) {\n");
        b.append("        super(pool, \"").append(c.name).append("\", capacity);\n");
        b.append("        int n = capacity();\n");
        for (int i = 0; i < columnFields.size(); i++) {
            b.append("        ")
                    .append(columnFields.get(i))
                    .append(" = new ")
                    .append(columnOf.get(i).kind.columnJava)
                    .append("[n];\n");
        }
        b.append("    }\n\n");
```

For `Mind` this writes (from the demo's generated `MindStore.java`):

```java
public final class MindStore extends Store {
    int[] state;
    float[] timer;
    float[] cooldown;
    float[] corpse;
    float[] homeX, homeY, homeZ;

    static final String[] COLUMNS = {"state", "timer", "cooldown", "corpse", "home.x", "home.y", "home.z"};
    static final int[] TYPES = {INT, FLOAT, FLOAT, FLOAT, FLOAT, FLOAT, FLOAT};
    static final dev.jeddy.samples.iso.MonsterState[] STATE_VALUES = dev.jeddy.samples.iso.MonsterState.values();

    /** Creates an empty store registered with {@code pool}. */
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

- **`COLUMNS` and `TYPES`** are the two tables `columnName` and `columnType` index into: two
  array loads, no `switch`.
- **`STATE_VALUES`** is `MonsterState.values()` called **once**. `values()` clones the enum's
  constant array on every call (it must: the caller could modify the array), so a view that called
  it per row would allocate an array per row (for `MonsterState`'s four constants: 8 bytes of
  header, 4 of length and 16 of references, rounded to 32).
- **`int n = capacity()`**, not the parameter: the base constructor clamps the capacity to at
  least 1, and the columns must match what the base class believes.
- **Types are written qualified** (`dev.jeddy.samples.iso.MonsterState`), never imported, because
  the generator cannot know what else a package has imported or whether a simple name would clash;
  qualified names are always unambiguous, at the cost of long lines nobody formats.

### 15.3 The record's two directions: `add(entity, value)`, `get`, `at`

```java
        b.append(
                "    /** Gives {@code entity} the component with {@code value}'s fields (or sets"
                        + " them if it has it) and returns its row. */\n");
        b.append("    public int add(int entity, ").append(c.name).append(" value) {\n");
        b.append("        int row = add(entity);\n");
        for (Field f : c.fields) {
            List<String> fields = f.columnFields();
            switch (f.kind) {
                case ENUM ->
                        b.append("        ")
                                .append(fields.get(0))
                                .append("[row] = value.")
                                .append(f.name)
                                .append("().ordinal();\n");
                case VEC2, VEC3 -> {
                    String[] parts = {"x", "y", "z"};
                    for (int k = 0; k < fields.size(); k++) {
                        b.append("        ")
                                .append(fields.get(k))
                                .append("[row] = value.")
                                .append(f.name)
                                .append("().")
                                .append(parts[k])
                                .append("();\n");
                    }
                }
                default ->
                        b.append("        ")
                                .append(fields.get(0))
                                .append("[row] = value.")
                                .append(f.name)
                                .append("();\n");
            }
        }
        b.append("        return row;\n    }\n\n");
```

The cold form into the hot form: `add(entity)` of the base class gives the row (an existing
member keeps its row), then each record accessor is copied into its column, an enum through
`ordinal()`, a vector through `x()`, `y()`, `z()`. The reverse direction allocates and says so:

```java
        b.append(
                "    /** A copy of {@code entity}'s component (allocates; tools), or {@code null}."
                        + " */\n");
        b.append("    public ").append(c.name).append(" get(int entity) {\n");
        b.append(
                "        int row = indexOf(entity);\n"
                        + "        return row < 0 ? null : at(row);\n"
                        + "    }\n\n");
        b.append("    /** A copy of row {@code row} (allocates; tools). */\n");
        b.append("    public ").append(c.name).append(" at(int row) {\n");
        b.append("        return new ").append(c.name).append("(");
        for (int i = 0; i < c.fields.size(); i++) {
            Field f = c.fields.get(i);
            List<String> fields = f.columnFields();
            b.append(i > 0 ? ", " : "");
            switch (f.kind) {
                case ENUM ->
                        b.append(valuesField(f)).append("[").append(fields.get(0)).append("[row]]");
                case VEC2 ->
                        b.append("new dev.jeddy.math.Vec2f(")
                                .append(fields.get(0))
                                .append("[row], ")
                                .append(fields.get(1))
                                .append("[row])");
                case VEC3 ->
                        b.append("new dev.jeddy.math.Vec3f(")
                                .append(fields.get(0))
                                .append("[row], ")
                                .append(fields.get(1))
                                .append("[row], ")
                                .append(fields.get(2))
                                .append("[row])");
                default -> b.append(fields.get(0)).append("[row]");
            }
        }
        b.append(");\n    }\n\n");
```

Generated for `Mind`:

```java
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

    /** A copy of {@code entity}'s component (allocates; tools), or {@code null}. */
    public Mind get(int entity) {
        int row = indexOf(entity);
        return row < 0 ? null : at(row);
    }

    /** A copy of row {@code row} (allocates; tools). */
    public Mind at(int row) {
        return new Mind(STATE_VALUES[state[row]], timer[row], cooldown[row], corpse[row], new dev.jeddy.math.Vec3f(homeX[row], homeY[row], homeZ[row]));
    }
```

`at` creates the record *and* a `Vec3f`: two objects, 32 + 24 bytes (L03 §3). The demo's `Spells.cast`
therefore fills the columns directly instead of calling `add(entity, new Fireball(...))`, with a
comment saying so (§19.6).

### 15.4 Column accessors, the hooks and the generic API

```java
        // Column accessors.
        for (int i = 0; i < columnFields.size(); i++) {
            b.append("    /** The {@code ")
                    .append(columnNames.get(i))
                    .append("} column, in dense order; valid up to {@link #size()}. */\n");
            b.append("    public ")
                    .append(columnOf.get(i).kind.columnJava)
                    .append("[] ")
                    .append(columnFields.get(i))
                    .append("() {\n");
            b.append("        return ").append(columnFields.get(i)).append(";\n    }\n\n");
        }
        // Hooks.
        b.append("    @Override\n    protected void growColumns(int capacity) {\n");
        for (String f : columnFields) {
            b.append("        ")
                    .append(f)
                    .append(" = Arrays.copyOf(")
                    .append(f)
                    .append(", capacity);\n");
        }
        b.append("    }\n\n    @Override\n    protected void moveRow(int from, int to) {\n");
        for (String f : columnFields) {
            b.append("        ").append(f).append("[to] = ").append(f).append("[from];\n");
        }
        b.append("    }\n\n    @Override\n    protected void resetRow(int row) {\n");
        for (int i = 0; i < columnFields.size(); i++) {
            b.append("        ")
                    .append(columnFields.get(i))
                    .append("[row] = ")
                    .append(literal(columnOf.get(i), columnPart.get(i)))
                    .append(";\n");
        }
        b.append("    }\n\n");
```

The accessors return the **array itself**, not a copy, "valid up to `size()`": that is how the
demo's hand-written loops (`Enemies.nearestAlive`, `Spells.extract`) read columns at full speed
(§19.6). The three hooks are one line per column; `resetRow` is where `@Default` becomes code
(`agent[row] = -1;` in `MonsterStore`, from `@Default("-1") int agent`).

```java
        // Generic column API.
        b.append("    @Override\n    public int columnCount() {\n        return ")
                .append(columnFields.size())
                .append(";\n    }\n\n");
        b.append(
                "    @Override\n"
                        + "    public String columnName(int column) {\n"
                        + "        return COLUMNS[column];\n"
                        + "    }\n\n");
        b.append(
                "    @Override\n"
                        + "    public int columnType(int column) {\n"
                        + "        return TYPES[column];\n"
                        + "    }\n\n");
        b.append(
                "    @Override\n"
                        + "    public int columnIndex(String name) {\n"
                        + "        return switch (name) {\n");
        for (int i = 0; i < columnNames.size(); i++) {
            b.append("            case \"")
                    .append(columnNames.get(i))
                    .append("\" -> ")
                    .append(i)
                    .append(";\n");
        }
        b.append("            default -> -1;\n        };\n    }\n\n");
```

`columnIndex` is a `switch` on a string: javac compiles it to a `lookupswitch` on the string's
hash code followed by an `equals` per case, which is fine for a path that runs once per level row.

```java
        b.append("    @Override\n    public int enumOrdinal(int column, String name) {\n");
        b.append("        switch (column) {\n");
        for (int i = 0; i < columnOf.size(); i++) {
            Field f = columnOf.get(i);
            if (f.kind == Kind.ENUM) {
                b.append("            case ").append(i).append(" -> {\n");
                b.append("                for (int i = 0; i < ")
                        .append(valuesField(f))
                        .append(".length; i++) {\n");
                b.append("                    if (")
                        .append(valuesField(f))
                        .append(
                                "[i].name().equals(name)) {\n"
                                        + "                        return i;\n"
                                        + "                    }\n"
                                        + "                }\n");
                b.append("                return -1;\n            }\n");
            }
        }
        b.append(
                "            default -> {\n"
                        + "                return -1;\n"
                        + "            }\n"
                        + "        }\n"
                        + "    }\n\n");
```

Two shapes of `switch` appear in the generated code, and the choice is deliberate. `enumOrdinal`
is a `switch` *statement* whose cases `return`, because a `switch` *expression* must yield a value
from every arm and javac refuses one whose only arm is `default -> { ... }` with no result
("no result expressions"), which is exactly what a store with no enum column would produce. The
pitfall of §24 was found this way. Where every arm yields a value (`enumName`, `assetKind`,
`isEntityRef`) an expression is used:

```java
        b.append("    @Override\n    public String enumName(int column, int ordinal) {\n");
        b.append("        return switch (column) {\n");
        for (int i = 0; i < columnOf.size(); i++) {
            Field f = columnOf.get(i);
            if (f.kind == Kind.ENUM) {
                b.append("            case ")
                        .append(i)
                        .append(" -> ordinal >= 0 && ordinal < ")
                        .append(valuesField(f))
                        .append(".length ? ")
                        .append(valuesField(f))
                        .append("[ordinal].name() : null;\n");
            }
        }
        b.append("            default -> null;\n        };\n    }\n\n");
        b.append("    @Override\n    public String assetKind(int column) {\n");
        b.append("        return switch (column) {\n");
        for (int i = 0; i < columnOf.size(); i++) {
            Field f = columnOf.get(i);
            if (f.kind == Kind.ASSET) {
                b.append("            case ")
                        .append(i)
                        .append(" -> \"")
                        .append(f.assetKind)
                        .append("\";\n");
            }
        }
        b.append("            default -> null;\n        };\n    }\n\n");
        b.append("    @Override\n    public boolean isEntityRef(int column) {\n");
        b.append("        return switch (column) {\n");
        for (int i = 0; i < columnOf.size(); i++) {
            if (columnOf.get(i).kind == Kind.ENTITY) {
                b.append("            case ").append(i).append(" -> true;\n");
            }
        }
        b.append("            default -> false;\n        };\n    }\n\n");
        genericAccess(b, "int", "Int", "INT", columnFields, columnOf);
        genericAccess(b, "long", "Long", "LONG", columnFields, columnOf);
        genericAccess(b, "float", "Float", "FLOAT", columnFields, columnOf);
        genericAccess(b, "boolean", "Bool", "BOOL", columnFields, columnOf);
        b.append("}\n");
        return b.toString();
    }
```

The four typed getter/setter pairs come from one helper, parameterised by the Java type, the method
suffix and the `Store` constant, and each `switch` lists only the columns of that type; every other
column throws:

```java
    private static void genericAccess(
            StringBuilder b,
            String java,
            String suffix,
            String storeType,
            List<String> columnFields,
            List<Field> columnOf) {
        b.append("    @Override\n    public ")
                .append(java)
                .append(" get")
                .append(suffix)
                .append("(int row, int column) {\n");
        b.append("        switch (column) {\n");
        for (int i = 0; i < columnFields.size(); i++) {
            if (columnOf.get(i).kind.storeType.equals(storeType)) {
                b.append("            case ")
                        .append(i)
                        .append(" -> {\n                return ")
                        .append(columnFields.get(i))
                        .append("[row];\n            }\n");
            }
        }
        b.append(
                        "            default -> throw new IllegalArgumentException(\"column \" +"
                                + " column + \" is not ")
                .append(java)
                .append("\");\n");
        b.append("        }\n    }\n\n");
        b.append("    @Override\n    public void set")
                .append(suffix)
                .append("(int row, int column, ")
                .append(java)
                .append(" value) {\n");
        b.append("        switch (column) {\n");
        for (int i = 0; i < columnFields.size(); i++) {
            if (columnOf.get(i).kind.storeType.equals(storeType)) {
                b.append("            case ")
                        .append(i)
                        .append(" -> ")
                        .append(columnFields.get(i))
                        .append("[row] = value;\n");
            }
        }
        b.append(
                        "            default -> throw new IllegalArgumentException(\"column \" +"
                                + " column + \" is not ")
                .append(java)
                .append("\");\n");
        b.append("        }\n    }\n\n");
    }
```

Generated for `Mind`'s floats (the `int` pair has one case, column 0; the `long` and `boolean`
pairs have only the throwing default):

```java
    @Override
    public float getFloat(int row, int column) {
        switch (column) {
            case 1 -> {
                return timer[row];
            }
            case 2 -> {
                return cooldown[row];
            }
            case 3 -> {
                return corpse[row];
            }
            case 4 -> {
                return homeX[row];
            }
            case 5 -> {
                return homeY[row];
            }
            case 6 -> {
                return homeZ[row];
            }
            default -> throw new IllegalArgumentException("column " + column + " is not float");
        }
    }

    @Override
    public void setFloat(int row, int column, float value) {
        switch (column) {
            case 1 -> timer[row] = value;
            case 2 -> cooldown[row] = value;
            case 3 -> corpse[row] = value;
            case 4 -> homeX[row] = value;
            case 5 -> homeY[row] = value;
            case 6 -> homeZ[row] = value;
            default -> throw new IllegalArgumentException("column " + column + " is not float");
        }
    }
```

A `switch` over small dense `int`s compiles to a `tableswitch`, a jump through a table: one
indexed branch per access. That is the price of reaching a column by number instead of by field,
and only the cold paths pay it.

### 15.5 The view

```java
    static String view(Component c) {
        StringBuilder b = new StringBuilder(header(c));
        b.append("import dev.jeddy.components.View;\n\n");
        b.append(doc(c, "The read-only flyweight view"));
        b.append("public class ").append(c.view()).append(" extends View {\n");
        b.append("    ").append(c.store()).append(" store;\n\n");
        b.append("    /** Positions the view at {@code row} of {@code store} (-1: no row). */\n");
        b.append("    public final void at(").append(c.store()).append(" store, int row) {\n");
        b.append("        this.store = store;\n        this.row = row;\n    }\n\n");
        b.append("    /** The store the view reads. */\n");
        b.append("    public final ")
                .append(c.store())
                .append(" store() {\n        return store;\n    }\n\n");
        b.append("    /** The entity at the row. */\n");
        b.append("    public final int entity() {\n        return store.entityAt(row);\n    }\n\n");
        for (Field f : c.fields) {
            List<String> fields = f.columnFields();
            switch (f.kind) {
                case ENUM -> {
                    b.append("    public final ")
                            .append(f.javaType)
                            .append(" ")
                            .append(f.name)
                            .append("() {\n");
                    b.append("        return ")
                            .append(c.store())
                            .append(".")
                            .append(valuesField(f))
                            .append("[store.")
                            .append(fields.get(0))
                            .append("[row]];\n    }\n\n");
                }
                case VEC2, VEC3 -> {
                    for (String cf : fields) {
                        b.append("    public final float ").append(cf).append("() {\n");
                        b.append("        return store.").append(cf).append("[row];\n    }\n\n");
                    }
                }
                default -> {
                    b.append("    public final ")
                            .append(f.kind.columnJava)
                            .append(" ")
                            .append(f.name)
                            .append("() {\n");
                    b.append("        return store.")
                            .append(fields.get(0))
                            .append("[row];\n    }\n\n");
                }
            }
        }
        b.append("}\n");
        return b.toString();
    }
```

The view class is `public class`, **not** final, because `MindMut` extends it; every *method* is
`final`, which is what the JIT needs (L03 §11.3). `at` is the whole positioning protocol: two
field stores. The enum getter indexes the store's `STATE_VALUES`, so `mind.state() ==
MonsterState.DEAD` compares references to the shared constant, with no allocation and no
`values()` call. A vector has one `float` getter per column and no `Vec3f` getter: `mind.home()`
returning a `Vec3f` would allocate 24 bytes per call, and the author writes `mind.homeX()`
instead. The generated `MindView` is in L03 §11.2.

### 15.6 The mut

```java
    static String mut(Component c) {
        StringBuilder b = new StringBuilder(header(c));
        b.append(doc(c, "The mutable flyweight view"));
        b.append("public final class ")
                .append(c.mut())
                .append(" extends ")
                .append(c.view())
                .append(" {\n");
        for (Field f : c.fields) {
            List<String> fields = f.columnFields();
            String cap = Character.toUpperCase(f.name.charAt(0)) + f.name.substring(1);
            switch (f.kind) {
                case ENUM -> {
                    b.append("    public void set")
                            .append(cap)
                            .append("(")
                            .append(f.javaType)
                            .append(" value) {\n");
                    b.append("        store.")
                            .append(fields.get(0))
                            .append("[row] = value.ordinal();\n    }\n\n");
                }
                case VEC2, VEC3 -> {
                    String[] parts = {"x", "y", "z"};
                    b.append("    public void set").append(cap).append("(");
                    for (int k = 0; k < fields.size(); k++) {
                        b.append(k > 0 ? ", " : "").append("float ").append(parts[k]);
                    }
                    b.append(") {\n");
                    for (int k = 0; k < fields.size(); k++) {
                        b.append("        store.")
                                .append(fields.get(k))
                                .append("[row] = ")
                                .append(parts[k])
                                .append(";\n");
                    }
                    b.append("    }\n\n");
                    for (int k = 0; k < fields.size(); k++) {
                        String cf = fields.get(k);
                        String cfCap = Character.toUpperCase(cf.charAt(0)) + cf.substring(1);
                        b.append("    public void set").append(cfCap).append("(float value) {\n");
                        b.append("        store.").append(cf).append("[row] = value;\n    }\n\n");
                    }
                }
                default -> {
                    b.append("    public void set")
                            .append(cap)
                            .append("(")
                            .append(f.kind.columnJava)
                            .append(" value) {\n");
                    b.append("        store.")
                            .append(fields.get(0))
                            .append("[row] = value;\n    }\n\n");
                }
            }
        }
        b.append("}\n");
        return b.toString();
    }
}
```

`MindMut` is a `final class`, so its setters need no `final` of their own: a method of a final
class has one target. It inherits `at`, `store`, `entity()` and every getter from `MindView`, and
adds `setState(MonsterState)` (stores the ordinal), `setTimer(float)` and so on, and for the vector
both `setHome(float x, float y, float z)` and the three `setHomeX`, `setHomeY`, `setHomeZ`. The
generated `MindMut` is in L03 §11.2.

A `View`-typed parameter and a `Mut`-typed one see the same object class at run time only if the
registry made a `Mut`; it makes exactly what the parameter asks for, so a `MindView` parameter is a
`MindView` instance, with no setters reachable even by a cast.
## 16. Step 8: `RegistryGen`

The registry is the largest generated file and the one the game talks to. `RegistryGen.registry(m)`
writes it in one pass, top to bottom: imports and the class header, the fields (stores, singletons,
views, kernels, graphs), the constructor, the accessors, the phase entry points, the level and
snapshot methods, and finally one private method per behaviour. This section follows that order
and shows what each part became in the demo's `CryptGame.java`.

### 16.1 Header and fields

```java
final class RegistryGen {
    private RegistryGen() {}

    static String registry(Model m) {
        StringBuilder b = new StringBuilder();
        if (!m.registryPackage.isEmpty()) {
            b.append("package ").append(m.registryPackage).append(";\n\n");
        }
        b.append("import dev.jeddy.components.EntityIds;\n");
        b.append("import dev.jeddy.core.io.ByteReader;\n");
        b.append("import dev.jeddy.core.io.ByteWriter;\n");
        b.append("import dev.jeddy.components.Store;\n");
        b.append("import dev.jeddy.core.job.JobGraph;\n");
        b.append("import dev.jeddy.core.job.JobKernel;\n");
        b.append("import dev.jeddy.core.job.JobSystem;\n");
        b.append("import dev.jeddy.scene.EntityPool;\n\n");
        // ... the class Javadoc (quoted from the output below) ...
        b.append("public final class ").append(m.registryName).append(" {\n");
        b.append("    /** Rows each store starts with. */\n");
        b.append("    public static final int CAPACITY = ").append(m.capacity).append(";\n\n");
        b.append("    private final EntityPool entities;\n");
        b.append("    private final JobSystem jobs;\n");
        for (Component c : m.components) {
            b.append("    private final ")
                    .append(c.qualifiedStore())
                    .append(" ")
                    .append(c.fieldName())
                    .append(";\n");
        }
        b.append("    private final Store[] stores;\n");
        for (Singleton s : m.singletons) {
            b.append("    private final ").append(s.type).append(" ").append(s.field).append(";\n");
        }
        b.append("    private float dt;\n    private float alpha;\n\n");
```

(The four `append` calls that write the class Javadoc are left out; the output shows it.) Then one
field per view parameter, and for a parallel behaviour the extras: an *array* of views per
parameter, the driving store, the kernel and its node number in the graph:

```java
        // Views and kernels.
        for (Behaviour bh : m.behaviours) {
            int i = 0;
            for (Param p : bh.params) {
                if (p.kind == ParamKind.VIEW || p.kind == ParamKind.MUT) {
                    String type =
                            p.kind == ParamKind.MUT
                                    ? p.component.qualifiedMut()
                                    : p.component.qualifiedView();
                    if (bh.parallel) {
                        b.append("    private final ")
                                .append(type)
                                .append("[] ")
                                .append(viewField(bh, i))
                                .append(";\n");
                    } else {
                        b.append("    private final ")
                                .append(type)
                                .append(" ")
                                .append(viewField(bh, i))
                                .append(" = new ")
                                .append(type)
                                .append("();\n");
                    }
                }
                i++;
            }
            if (bh.parallel) {
                b.append("    private Store ").append(bh.id()).append("_drive;\n");
                b.append("    private final JobKernel ")
                        .append(bh.id())
                        .append("_kernel = this::")
                        .append(bh.id())
                        .append("_range;\n");
                b.append("    private final int ").append(bh.id()).append("_node;\n");
            }
        }
```

`viewField(bh, i)` is `bh.id() + "_v" + i` with `i` the parameter's position (counting every
parameter, so `SkeletonAi_think_v3` is the fourth parameter, `FlashMut flash`). The demo's output:

```java
/**
 * The component registry of this game: every store, the singletons the behaviours take, and the
 * phase entry points; generated by the Jeddy component processor, do not edit (DESIGN §22).
 *
 * <p>Thread ownership: the game thread; parallel behaviours run on the job system inside the
 * phase calls.
 */
public final class CryptGame {
    /** Rows each store starts with. */
    public static final int CAPACITY = 512;

    private final EntityPool entities;
    private final JobSystem jobs;
    private final dev.jeddy.samples.iso.FireballStore fireball;
    private final dev.jeddy.samples.iso.FlashStore flash;
    private final dev.jeddy.samples.iso.MindStore mind;
    private final dev.jeddy.samples.iso.MonsterStore monster;
    private final dev.jeddy.samples.iso.SightStore sight;
    private final Store[] stores;
    private final dev.jeddy.app.world.AgentSync agentSync;
    private final dev.jeddy.samples.iso.Animator animator;
    private final dev.jeddy.physics.crowd.Crowd crowd;
    private final dev.jeddy.samples.iso.Enemies enemies;
    private final dev.jeddy.samples.iso.Level level;
    private final dev.jeddy.samples.iso.Party party;
    private final dev.jeddy.math.Rng rng;
    private final dev.jeddy.samples.iso.Spells spells;
    private final dev.jeddy.physics.vis.Visibility visibility;
    private final dev.jeddy.app.world.World world;
    private float dt;
    private float alpha;

    private final dev.jeddy.samples.iso.MonsterView SkeletonAi_think_v0 = new dev.jeddy.samples.iso.MonsterView();
    private final dev.jeddy.samples.iso.MindMut SkeletonAi_think_v1 = new dev.jeddy.samples.iso.MindMut();
    private final dev.jeddy.samples.iso.SightView SkeletonAi_think_v2 = new dev.jeddy.samples.iso.SightView();
    private final dev.jeddy.samples.iso.FlashMut SkeletonAi_think_v3 = new dev.jeddy.samples.iso.FlashMut();
    // ... outline_v0..v2, flinch_v0..v2 likewise ...
    private final dev.jeddy.samples.iso.MonsterView[] SkeletonAi_see_v0;
    private final dev.jeddy.samples.iso.MindView[] SkeletonAi_see_v1;
    private final dev.jeddy.samples.iso.SightMut[] SkeletonAi_see_v2;
    private Store SkeletonAi_see_drive;
    private final JobKernel SkeletonAi_see_kernel = this::SkeletonAi_see_range;
    private final int SkeletonAi_see_node;
    // ... show_v0..v2, Fireballs_fly_v0 ...
    private final JobGraph fixedUpdateGroup0 = new JobGraph();
```

(Condensed where the comments say so.) The stores come first in sorted order (§14.2), the
singletons in field-name order (§13.5), and `dt` and `alpha` are the per-call inputs of the loops,
written by the stage methods and read by the loops. The kernel field initializer evaluates
`this::SkeletonAi_see_range` **once** (§6.4).

### 16.2 Groups and the constructor

```java
        // Parallel groups: maximal runs of consecutive parallel behaviours within a stage.
        Map<String, List<Behaviour>> groups = new LinkedHashMap<>();
        for (Phase phase : new Phase[] {Phase.FIXED_UPDATE, Phase.UPDATE}) {
            int g = 0;
            for (int stage : m.stages(phase)) {
                for (List<Behaviour> run : runs(m.ofStage(phase, stage))) {
                    if (run.get(0).parallel) {
                        String name = phase.json + "Group" + g++;
                        groups.put(name, run);
                        b.append("    private final JobGraph ")
                                .append(name)
                                .append(" = new JobGraph();\n");
                    }
                }
            }
        }
```

A **run** is a maximal sequence of consecutive behaviours of a stage that are all parallel or all
sequential (`runs`, §16.3); each parallel run is one `JobGraph`, named `fixedUpdateGroup0`,
`fixedUpdateGroup1`, ..., `updateGroup0`, ... per phase. The demo has one: `see`, alone, in stage 1.

```java
        // Constructor.
        b.append("    public ")
                .append(m.registryName)
                .append("(EntityPool entities, JobSystem jobs");
        for (Singleton s : m.singletons) {
            b.append(", ").append(s.type).append(" ").append(s.field);
        }
        b.append(") {\n        this.entities = entities;\n        this.jobs = jobs;\n");
        for (Singleton s : m.singletons) {
            b.append("        this.").append(s.field).append(" = ").append(s.field).append(";\n");
        }
        for (Component c : m.components) {
            b.append("        ")
                    .append(c.fieldName())
                    .append(" = new ")
                    .append(c.qualifiedStore())
                    .append("(entities, CAPACITY);\n");
        }
        b.append("        stores = new Store[] {");
        for (int i = 0; i < m.components.size(); i++) {
            b.append(i > 0 ? ", " : "").append(m.components.get(i).fieldName());
        }
        b.append("};\n");
```

(The constructor's Javadoc line is left out.) The signature is `(EntityPool, JobSystem, <singletons
in field order>)`; `jobs` may be `null`, which makes every parallel behaviour run inline on the
calling thread (the demo's `--inline-behaviours`). Then the per-thread views and the graph edges:

```java
        boolean anyParallel = false;
        for (Behaviour bh : m.behaviours) {
            anyParallel |= bh.parallel;
        }
        if (anyParallel) {
            b.append("        int slots = jobs == null ? 1 : jobs.threadSlots();\n");
            for (Behaviour bh : m.behaviours) {
                if (!bh.parallel) {
                    continue;
                }
                int i = 0;
                for (Param p : bh.params) {
                    if (p.kind == ParamKind.VIEW || p.kind == ParamKind.MUT) {
                        String type =
                                p.kind == ParamKind.MUT
                                        ? p.component.qualifiedMut()
                                        : p.component.qualifiedView();
                        b.append("        ")
                                .append(viewField(bh, i))
                                .append(" = new ")
                                .append(type)
                                .append("[slots];\n");
                        b.append("        for (int t = 0; t < slots; t++) {\n            ")
                                .append(viewField(bh, i))
                                .append("[t] = new ")
                                .append(type)
                                .append("();\n        }\n");
                    }
                    i++;
                }
            }
            for (Map.Entry<String, List<Behaviour>> g : groups.entrySet()) {
                List<Behaviour> run = g.getValue();
                for (int j = 0; j < run.size(); j++) {
                    Behaviour bh = run.get(j);
                    b.append("        ")
                            .append(bh.id())
                            .append("_node = ")
                            .append(g.getKey())
                            .append(".add(")
                            .append(bh.id())
                            .append("_kernel, 0, ")
                            .append(bh.grain);
                    for (int k = 0; k < j; k++) {
                        if (run.get(k).conflictsWith(bh)) {
                            b.append(", ").append(run.get(k).id()).append("_node");
                        }
                    }
                    b.append(");\n");
                }
            }
        }
        b.append("    }\n\n");
```

This is where §6.2 becomes edges: for each behaviour $j$ of a run, every earlier $k$ of the same run
with `conflictsWith` true is passed as a dependency to `JobGraph.add` (chapter
[01](01-architecture.md#141-jobgraph-a-reusable-dag), on the job graph), whose nodes must be added
in dependency order, which declaration order guarantees. The count is 0 at construction and set per
tick (§16.4). The demo's constructor:

```java
    public CryptGame(EntityPool entities, JobSystem jobs, dev.jeddy.app.world.AgentSync agentSync, dev.jeddy.samples.iso.Animator animator, dev.jeddy.physics.crowd.Crowd crowd, dev.jeddy.samples.iso.Enemies enemies, dev.jeddy.samples.iso.Level level, dev.jeddy.samples.iso.Party party, dev.jeddy.math.Rng rng, dev.jeddy.samples.iso.Spells spells, dev.jeddy.physics.vis.Visibility visibility, dev.jeddy.app.world.World world) {
        this.entities = entities;
        this.jobs = jobs;
        this.agentSync = agentSync;
        // ... the other nine singletons ...
        fireball = new dev.jeddy.samples.iso.FireballStore(entities, CAPACITY);
        flash = new dev.jeddy.samples.iso.FlashStore(entities, CAPACITY);
        mind = new dev.jeddy.samples.iso.MindStore(entities, CAPACITY);
        monster = new dev.jeddy.samples.iso.MonsterStore(entities, CAPACITY);
        sight = new dev.jeddy.samples.iso.SightStore(entities, CAPACITY);
        stores = new Store[] {fireball, flash, mind, monster, sight};
        int slots = jobs == null ? 1 : jobs.threadSlots();
        SkeletonAi_see_v0 = new dev.jeddy.samples.iso.MonsterView[slots];
        for (int t = 0; t < slots; t++) {
            SkeletonAi_see_v0[t] = new dev.jeddy.samples.iso.MonsterView();
        }
        // ... SkeletonAi_see_v1, SkeletonAi_see_v2 likewise ...
        SkeletonAi_see_node = fixedUpdateGroup0.add(SkeletonAi_see_kernel, 0, 32);
    }
```

Twelve parameters, and the demo builds the registry *after* the ten objects it names (§19.5). Every
store registers itself with `entities` in its base constructor, so `world.entities().destroy(e)`
removes `e`'s rows from all five.

### 16.3 Accessors, `runs` and `hasAny`

```java
        b.append("    public EntityPool entities() {\n        return entities;\n    }\n\n");
        b.append(
                "    /** The job system, or null. */\n"
                        + "    public JobSystem jobs() {\n"
                        + "        return jobs;\n"
                        + "    }\n\n");
        for (Component c : m.components) {
            b.append("    public ")
                    .append(c.qualifiedStore())
                    .append(" ")
                    .append(c.fieldName())
                    .append("() {\n        return ")
                    .append(c.fieldName())
                    .append(";\n    }\n\n");
        }
        b.append(
                "    /** Every store, in schema order; do not modify. */\n"
                        + "    public Store[] stores() {\n"
                        + "        return stores;\n"
                        + "    }\n\n");
        b.append(
                "    /** The store of the component named {@code name}, or null. */\n"
                        + "    public Store store(String name) {\n"
                        + "        return switch (name) {\n");
        for (Component c : m.components) {
            b.append("            case \"")
                    .append(c.name)
                    .append("\" -> ")
                    .append(c.fieldName())
                    .append(";\n");
        }
        b.append("            default -> null;\n        };\n    }\n\n");
        b.append(
                "    /** Whether {@code entity} has any component. */\n"
                        + "    public boolean hasAny(int entity) {\n");
        b.append(
                "        for (Store s : stores) {\n"
                        + "            if (s.has(entity)) {\n"
                        + "                return true;\n"
                        + "            }\n"
                        + "        }\n"
                        + "        return false;\n"
                        + "    }\n\n");
```

`game.mind()` returns the typed store for hand-written loops; `stores()` the array the level loader
and the snapshots iterate; `store("Mind")` the by-name lookup the snapshot reader uses. The `runs`
helper that the groups and the stage methods share:

```java
    private static String viewField(Behaviour bh, int param) {
        return bh.id() + "_v" + param;
    }

    /** Splits a phase's behaviours into maximal runs of parallel and of sequential ones. */
    private static List<List<Behaviour>> runs(List<Behaviour> phase) {
        List<List<Behaviour>> out = new ArrayList<>();
        List<Behaviour> run = null;
        for (Behaviour bh : phase) {
            if (run == null || run.get(0).parallel != bh.parallel) {
                run = new ArrayList<>();
                out.add(run);
            }
            run.add(bh);
        }
        return out;
    }
```

A new run starts whenever `parallel` flips. The consequence for an author: two parallel behaviours
with a sequential one between them in the same stage are two graphs with a barrier between them,
and ordering within a stage (`@Behaviour(order)`, source order) is therefore also how you choose
what runs concurrently.

### 16.4 The stage methods

```java
    private static void tickPhase(
            StringBuilder b, Model m, Phase phase, Map<String, List<Behaviour>> groups) {
        boolean update = phase == Phase.UPDATE;
        List<Integer> stages = m.stages(phase);
        String pass = update ? "dt, alpha" : "dt";
        // ... fixedUpdate(float dt) / update(float dt, float alpha): one call per stage ...
        for (int stage : stages) {
            b.append("        ")
                    .append(update ? "updateStage" : "fixedUpdateStage")
                    .append("(")
                    .append(stage)
                    .append(", ")
                    .append(pass)
                    .append(");\n");
        }
        b.append("    }\n\n");
        // ... fixedUpdateStage(int stage, float dt) { this.dt = dt; / updateStage: this.alpha = alpha too
        b.append("        switch (stage) {\n");
        int g = 0;
        for (int stage : stages) {
            b.append("            case ").append(stage).append(" -> {\n");
            for (List<Behaviour> run : runs(m.ofStage(phase, stage))) {
                if (run.get(0).parallel) {
                    String name = phase.json + "Group" + g++;
                    for (Behaviour bh : run) {
                        b.append("                ")
                                .append(bh.id())
                                .append("_drive = ")
                                .append(driveExpression(bh))
                                .append(";\n");
                    }
                    b.append("                if (jobs == null) {\n");
                    for (Behaviour bh : run) {
                        b.append("                    ")
                                .append(bh.id())
                                .append("_range(0, ")
                                .append(bh.id())
                                .append("_drive.size(), 0);\n");
                    }
                    b.append("                } else {\n");
                    for (Behaviour bh : run) {
                        b.append("                    ")
                                .append(name)
                                .append(".setCount(")
                                .append(bh.id())
                                .append("_node, ")
                                .append(bh.id())
                                .append("_drive.size());\n");
                    }
                    b.append("                    ")
                            .append(name)
                            .append(".run(jobs);\n                }\n");
                } else {
                    for (Behaviour bh : run) {
                        b.append("                ").append(bh.id()).append("_loop();\n");
                    }
                }
            }
            b.append("            }\n");
        }
        b.append("            default -> {}\n        }\n    }\n\n");
    }
```

(The `append` calls that write the two method headers and their Javadoc are condensed to comments.)
`tickPhase` is called once for `FIXED_UPDATE` and once for `UPDATE`, and each call writes two
methods: `fixedUpdate(dt)`, which calls `fixedUpdateStage` for every stage in ascending order, and
`fixedUpdateStage(stage, dt)`, which stores `dt` (and `alpha`) in the fields and switches on the
stage. Inside a case, a sequential run is one `_loop()` call per behaviour; a parallel run first
picks every behaviour's driving store (sizes may have changed since last tick), then either runs
each `_range` inline over the whole store with thread slot 0, or sets each node's count to its
driver's size and runs the graph. `default -> {}` makes an unknown stage a no-op, so a game that
calls stage 3 by mistake runs nothing rather than throwing; the demo's output:

```java
    /** Runs the fixed-update behaviours of every stage for this tick. */
    public void fixedUpdate(float dt) {
        fixedUpdateStage(0, dt);
        fixedUpdateStage(1, dt);
        fixedUpdateStage(2, dt);
    }

    /** Runs the fixed-update behaviours of one stage (a game that interleaves its own systems). */
    public void fixedUpdateStage(int stage, float dt) {
        this.dt = dt;
        switch (stage) {
            case 0 -> {
                SkeletonAi_think_loop();
            }
            case 1 -> {
                SkeletonAi_see_drive = Store.smaller(Store.smaller(monster, mind), sight);
                if (jobs == null) {
                    SkeletonAi_see_range(0, SkeletonAi_see_drive.size(), 0);
                } else {
                    fixedUpdateGroup0.setCount(SkeletonAi_see_node, SkeletonAi_see_drive.size());
                    fixedUpdateGroup0.run(jobs);
                }
                SkeletonAi_show_loop();
            }
            case 2 -> {
                Fireballs_fly_loop();
            }
            default -> {}
        }
    }
```

`JobGraph.run` submits every node and waits for all of them, helping with chunks while it waits
(chapter [01](01-architecture.md#136-isdone-and-wait-help-while-waiting), on help while waiting), so
when `fixedUpdateStage(1, dt)` returns, every row of `see` has run and `show`, sequential, sees the
result. A node whose count is 0 (an empty store) is submitted as no job at all.

### 16.5 The driver and the kind switches

```java
    private static String driveExpression(Behaviour bh) {
        List<Component> required = bh.required();
        String expr = required.get(0).fieldName();
        for (int i = 1; i < required.size(); i++) {
            expr = "Store.smaller(" + expr + ", " + required.get(i).fieldName() + ")";
        }
        return expr;
    }
```

One required store is the expression itself (`fireball`); several nest `Store.smaller` left to
right in parameter order, which with `<=` in `smaller` means the first parameter's store wins a
tie (§10.3).

```java
    private static void kindSwitch(StringBuilder b, List<Behaviour> behaviours, String args) {
        if (behaviours.isEmpty()) {
            return;
        }
        Map<Integer, List<Behaviour>> byKind = new LinkedHashMap<>();
        for (Behaviour bh : behaviours) {
            byKind.computeIfAbsent(bh.kindValue, k -> new ArrayList<>()).add(bh);
        }
        b.append("        switch (kind) {\n");
        for (Map.Entry<Integer, List<Behaviour>> e : byKind.entrySet()) {
            b.append("            case ").append(e.getKey()).append(" -> {\n");
            for (Behaviour bh : e.getValue()) {
                b.append("                ")
                        .append(bh.id())
                        .append("_one(")
                        .append(args)
                        .append(");\n");
            }
            b.append("            }\n");
        }
        b.append("            default -> {}\n        }\n");
    }
```

`trigger` and `signal` dispatch on an `int` kind: the behaviours are grouped by their
`@OnTrigger`/`@OnEvent` value and each case calls the `_one` method of every behaviour of that
kind, in order; an unknown kind runs nothing. With no behaviours of that phase the method body is
empty, as the demo's `trigger` is. The per-entity entry points and the level and snapshot methods
are written straight from string literals (§16.1's style) and appear in the output as:

```java
    /** Runs the spawn behaviours for {@code entity} (after its components were added). */
    public void spawn(int entity) {
    }

    /** Runs the despawn behaviours for {@code entity}, then removes its components. */
    public void despawn(int entity) {
        for (Store s : stores) {
            s.remove(entity);
        }
    }

    // ... turn(int entity), trigger(int trigger, int entity, int kind) and
    //     dispatch(int[] events, int count) left out here ...

    /** Raises a signal: runs the event behaviours of {@code kind} for {@code entity}. */
    public void signal(int kind, int entity, int a, int b) {
        switch (kind) {
            case 1 -> {
                SkeletonAi_flinch_one(entity, a, b);
            }
            default -> {}
        }
    }

    /** Adds the level's component rows to the entities {@code binding} maps its objects to, then runs the spawn behaviours for each of them; returns the rows applied (D109). */
    public int load(dev.jeddy.asset.format.LevelData level, dev.jeddy.components.Levels.Binding binding) {
        int applied = dev.jeddy.components.Levels.load(stores, level, binding);
        int last = 0;
        for (int row = 0; row < level.componentRowCount(); row++) {
            int entity = binding.entityOf(level.componentRowObject(row));
            if (entity != 0 && entity != last) {
                spawn(entity);
                last = entity;
            }
        }
        return applied;
    }

    // ... loadObject(...), write(ByteWriter, EntityIds) and write(ByteWriter) left out here ...

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

(Condensed: five of the generated methods are left out where the comments say so; they are of
the same shape, and `dispatch` is the loop of §7. The demo's `spawn`, `turn` and `trigger` are
empty because it declares no such behaviours.) Three details:

- **`despawn` runs the behaviours first, then removes the rows**, so an `@OnDespawn` behaviour
  still sees the entity's data. The entity itself is left to the game: `despawn` takes the
  components, `world.entities().destroy(e)` frees the handle (and would remove the rows too, through
  the pool's registered stores, chapter [01](01-architecture.md#73-sparse-sets-and-component-stores)).
- **`load` runs `spawn` once per entity when an object's rows are adjacent**, not once per row:
  it remembers only the previous row's entity (`last`). `LevelData.addComponentRow` appends rows in
  the order they were added, so an object whose components were added at different times, with
  another object's rows in between, has its spawn behaviours run twice. The demo's level has no
  such object today; a set of seen entities (the pool's slot indices in a `boolean[]`) would make
  the rule exact.
- **`read` routes each store by name** with `peekString`, skipping a store this build does not
  have, so a save written with a component that was since deleted still loads (D111).

### 16.6 `match`: the heart of every loop

Every behaviour body — a loop, a parallel range or a per-entity method — ends in the same seven
lines: look the entity up in each store, position the views, call the method. `match` writes them.
It has two overloads; the five-argument one, which the per-entity methods use (§16.8), passes no
driver row:

```java
    /** The membership tests, view positioning and call for one entity {@code e}; false to skip. */
    private static void match(
            StringBuilder b, Behaviour bh, String indent, String skip, boolean perThread) {
        match(b, bh, indent, skip, perThread, null);
    }

    /**
     * The membership tests, view positioning and call for one entity {@code e}. With {@code
     * driverRow} set (a loop over the one required store), that store's row is the loop index: no
     * lookup for it.
     */
    private static void match(
            StringBuilder b,
            Behaviour bh,
            String indent,
            String skip,
            boolean perThread,
            String driverRow) {
        int i = 0;
        int r = 0;
        List<Integer> rowOf = new ArrayList<>();
        List<Component> required = bh.required();
        Component driver = driverRow != null && required.size() == 1 ? required.get(0) : null;
        boolean driverUsed = false;
        for (Param p : bh.params) {
            if (p.kind == ParamKind.VIEW || p.kind == ParamKind.MUT) {
                if (p.component == driver && !p.optional && !driverUsed) {
                    driverUsed = true; // the driving store: its row is the loop index
                    b.append(indent)
                            .append("int r")
                            .append(r)
                            .append(" = ")
                            .append(driverRow)
                            .append(";\n");
                    rowOf.add(r++);
                    i++;
                    continue;
                }
                b.append(indent)
                        .append("int r")
                        .append(r)
                        .append(" = ")
                        .append(p.component.fieldName())
                        .append(".indexOf(e);\n");
                if (!p.optional) {
                    b.append(indent)
                            .append("if (r")
                            .append(r)
                            .append(" < 0) {\n")
                            .append(indent)
                            .append("    ")
                            .append(skip)
                            .append("\n")
                            .append(indent)
                            .append("}\n");
                }
                rowOf.add(r++);
            } else {
                rowOf.add(-1);
            }
            i++;
        }
```

- **`skip`** is `continue;` in a loop and `return;` in a per-entity method: the same text serves
  both.
- **`driver`** is set only when the behaviour has **exactly one** required store and the caller
  passed a `driverRow` (the loop index `i`); then that store's first non-optional parameter gets
  `int r0 = i;` and no lookup. This is the single-store fix of §5.4; with two or more required
  stores `driver` stays `null` and every store, the one that happens to drive included, is looked
  up. That is the open decision, in the line `required.size() == 1`.
- **An optional view is looked up but never skipped**: its `rK` may be −1, which `at` stores and
  `present()` reports.
- **`rowOf`** remembers which `rK` belongs to parameter `i`, or −1 for a non-view parameter.

```java
        for (Component w : bh.without) {
            b.append(indent)
                    .append("if (")
                    .append(w.fieldName())
                    .append(".has(e)) {\n")
                    .append(indent)
                    .append("    ")
                    .append(skip)
                    .append("\n")
                    .append(indent)
                    .append("}\n");
        }
        i = 0;
        for (Param p : bh.params) {
            if (p.kind == ParamKind.VIEW || p.kind == ParamKind.MUT) {
                b.append(indent)
                        .append(viewField(bh, i))
                        .append(perThread ? "[thread]" : "")
                        .append(".at(")
                        .append(p.component.fieldName())
                        .append(", r")
                        .append(rowOf.get(i))
                        .append(");\n");
            }
            i++;
        }
        b.append(indent).append(bh.owner).append(".").append(bh.method).append("(");
        i = 0;
        for (Param p : bh.params) {
            b.append(i > 0 ? ", " : "");
            switch (p.kind) {
                case VIEW, MUT -> b.append(viewField(bh, i)).append(perThread ? "[thread]" : "");
                case DT -> b.append("dt");
                case ALPHA -> b.append("alpha");
                case ENTITY -> b.append("e");
                case TRIGGER -> b.append("trigger");
                case KIND -> b.append("kind");
                case A -> b.append("a");
                case B -> b.append("b");
                case SINGLETON -> b.append(p.field);
            }
            i++;
        }
        b.append(");\n");
    }
```

The `@Without` tests come after the required lookups (an entity that lacks a required component
is skipped before any `has` runs; `has` is `indexOf(e) >= 0`, the same cost as a lookup); then every view is positioned, with `[thread]` appended
in the parallel case; then the call, with each parameter rendered by its kind: the view field, the
local `dt`/`alpha`, the entity `e`, the phase's `trigger`/`kind`/`a`/`b`, or the singleton's field
(`this` for the registry). The `switch` has no default: a new `ParamKind` without a case is a
compile error in the generator, not a silent gap in the output.

### 16.7 The sequential loop

```java
    private static void loop(StringBuilder b, Behaviour bh) {
        b.append("    private void ").append(bh.id()).append("_loop() {\n");
        b.append("        Store drive = ").append(driveExpression(bh)).append(";\n");
        b.append("        float dt = this.dt;\n");
        if (bh.phase == Phase.UPDATE) {
            b.append("        float alpha = this.alpha;\n");
        }
        b.append("        for (int i = drive.size() - 1; i >= 0; i--) {\n");
        b.append(
                "            if (i >= drive.size()) {\n"
                        + "                continue; // rows went away under the loop\n"
                        + "            }\n");
        b.append("            int e = drive.entityAt(i);\n");
        match(b, bh, "            ", "continue;", false, "i");
        b.append("        }\n    }\n\n");
    }
```

This is the loop of §5 and L03 §12: pick the driver, copy `dt` to a local (a field could be
rewritten by the behaviour's callees, and a local lets the JIT keep it in a register), count down,
guard against a shrunken store, take the entity, then `match` with `i` as the driver row. The
demo's `think` loop, with four required stores and therefore four lookups:

```java
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
            dev.jeddy.samples.iso.SkeletonAi.think(SkeletonAi_think_v0, SkeletonAi_think_v1, SkeletonAi_think_v2, SkeletonAi_think_v3, dt, e, enemies, party, crowd, animator, agentSync, level, rng, world);
        }
    }
```

And the one-store loop of `fly`, where the driver's row is the index:

```java
    private void Fireballs_fly_loop() {
        Store drive = fireball;
        float dt = this.dt;
        for (int i = drive.size() - 1; i >= 0; i--) {
            if (i >= drive.size()) {
                continue; // rows went away under the loop
            }
            int e = drive.entityAt(i);
            int r0 = i;
            Fireballs_fly_v0.at(fireball, r0);
            dev.jeddy.samples.iso.Fireballs.fly(Fireballs_fly_v0, dt, e, spells, this, world);
        }
    }
```

`fly` takes `CryptGame game`, so the call passes `this`.

### 16.8 The parallel range and the per-entity method

```java
    private static void parallelRange(StringBuilder b, Behaviour bh) {
        b.append("    private void ")
                .append(bh.id())
                .append("_range(int start, int end, int thread) {\n");
        b.append("        Store drive = ").append(bh.id()).append("_drive;\n");
        b.append("        float dt = this.dt;\n");
        if (bh.phase == Phase.UPDATE) {
            b.append("        float alpha = this.alpha;\n");
        }
        b.append("        for (int i = start; i < end; i++) {\n");
        b.append("            int e = drive.entityAt(i);\n");
        match(b, bh, "            ", "continue;", true, "i");
        b.append("        }\n    }\n\n");
    }

    private static void one(StringBuilder b, Behaviour bh) {
        String args =
                switch (bh.phase) {
                    case TRIGGER -> "int trigger, int e, int kind";
                    case EVENT -> "int e, int a, int b";
                    default -> "int e";
                };
        b.append("    private void ").append(bh.id()).append("_one(").append(args).append(") {\n");
        match(b, bh, "        ", "return;", false);
        b.append("    }\n\n");
    }
}
```

The range method has the `JobKernel` signature `(start, end, threadIndex)` so that
`this::X_range` is a kernel. It walks **forwards** over `[start, end)`: a parallel behaviour may
not change structure, so there is nothing to protect against, and a forward walk over a chunk is
what the prefetcher likes. It reads the driver from the field the stage method set (§16.4) and
indexes the views with `thread`. The per-entity method takes the phase's scalars and the entity,
with `return;` as the skip; it has no driver row, so every store is looked up. The demo's `see` and
`flinch`:

```java
    private void SkeletonAi_see_range(int start, int end, int thread) {
        Store drive = SkeletonAi_see_drive;
        float dt = this.dt;
        for (int i = start; i < end; i++) {
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
            SkeletonAi_see_v0[thread].at(monster, r0);
            SkeletonAi_see_v1[thread].at(mind, r1);
            SkeletonAi_see_v2[thread].at(sight, r2);
            dev.jeddy.samples.iso.SkeletonAi.see(SkeletonAi_see_v0[thread], SkeletonAi_see_v1[thread], SkeletonAi_see_v2[thread], visibility, crowd);
        }
    }

    private void SkeletonAi_flinch_one(int e, int a, int b) {
        int r0 = monster.indexOf(e);
        if (r0 < 0) {
            return;
        }
        int r1 = mind.indexOf(e);
        if (r1 < 0) {
            return;
        }
        int r2 = flash.indexOf(e);
        if (r2 < 0) {
            return;
        }
        SkeletonAi_flinch_v0.at(monster, r0);
        SkeletonAi_flinch_v1.at(mind, r1);
        SkeletonAi_flinch_v2.at(flash, r2);
        dev.jeddy.samples.iso.SkeletonAi.flinch(SkeletonAi_flinch_v0, SkeletonAi_flinch_v1, SkeletonAi_flinch_v2, enemies, animator);
    }
```

`see` does not use `dt`, and the local `float dt = this.dt;` in its range is dead code that javac
emits and the JIT removes; the generator writes it for every loop phase rather than tracking
whether the body needs it. The `_one` method is what a signal costs: three lookups and a call, for
one entity, on the game thread.

## 17. Step 9: `SchemaGen`

The schema is the one description of the components for everything that is not the compiled game:
the editor's inspector, the cooker's validation, the docs. It is JSON, written by hand with a
`StringBuilder` and one escaping routine, because the processor may depend only on the JDK and the
JDK has no JSON writer; `asset.format.ComponentSchema` (which uses the engine's own `core.json`)
reads it.

```java
final class SchemaGen {
    private SchemaGen() {}

    static String json(Model m) {
        StringBuilder b = new StringBuilder();
        b.append("{\n  \"version\": 1,\n  \"registry\": ");
        string(
                b,
                m.registryPackage.isEmpty()
                        ? m.registryName
                        : m.registryPackage + "." + m.registryName);
        b.append(",\n  \"capacity\": ").append(m.capacity).append(",\n  \"components\": [\n");
        for (int i = 0; i < m.components.size(); i++) {
            Component c = m.components.get(i);
            b.append("    {\"name\": ");
            string(b, c.name);
            b.append(", \"type\": ");
            string(b, c.qualified());
            b.append(", \"doc\": ");
            string(b, c.doc);
            b.append(", \"fields\": [\n");
            for (int k = 0; k < c.fields.size(); k++) {
                field(b, c.fields.get(k));
                b.append(k + 1 < c.fields.size() ? ",\n" : "\n");
            }
            b.append("    ]}").append(i + 1 < m.components.size() ? ",\n" : "\n");
        }
```

```java
        b.append("  ],\n  \"behaviours\": [\n");
        for (int i = 0; i < m.behaviours.size(); i++) {
            Behaviour bh = m.behaviours.get(i);
            b.append("    {\"name\": ");
            string(b, bh.name());
            b.append(", \"phase\": ");
            string(b, bh.phase.json);
            b.append(", \"parallel\": ").append(bh.parallel);
            b.append(", \"order\": ").append(bh.order);
            b.append(", \"stage\": ").append(bh.stage);
            if (bh.phase == Model.Phase.TRIGGER || bh.phase == Model.Phase.EVENT) {
                b.append(", \"kind\": ").append(bh.kindValue);
            }
            b.append(", \"reads\": ");
            strings(b, bh.reads());
            b.append(", \"writes\": ");
            strings(b, bh.writes());
            List<String> optional = new ArrayList<>();
            for (Param p : bh.params) {
                if (p.optional && !optional.contains(p.component.name)) {
                    optional.add(p.component.name);
                }
            }
            b.append(", \"optional\": ");
            strings(b, optional);
            List<String> without = new ArrayList<>();
            for (Component c : bh.without) {
                without.add(c.name);
            }
            b.append(", \"without\": ");
            strings(b, without);
            b.append("}").append(i + 1 < m.behaviours.size() ? ",\n" : "\n");
        }
        b.append("  ]\n}\n");
        return b.toString();
    }
```

A field carries its type's JSON name, its default in a form that depends on the type, and only the
hints it has:

```java
    private static void field(StringBuilder b, Field f) {
        b.append("      {\"name\": ");
        string(b, f.name);
        b.append(", \"type\": ");
        string(b, f.kind.json);
        b.append(", \"default\": ");
        switch (f.kind) {
            case INT, ENTITY, ASSET -> b.append((int) f.defaults[0]);
            case LONG -> b.append((long) f.defaults[0]);
            case FLOAT -> b.append(number(f.defaults[0]));
            case BOOL -> b.append(f.defaults[0] != 0);
            case ENUM -> string(b, f.enumValues.get((int) f.defaults[0]));
            case VEC2, VEC3 -> {
                b.append("[");
                for (int i = 0; i < f.defaults.length; i++) {
                    b.append(i > 0 ? ", " : "").append(number(f.defaults[i]));
                }
                b.append("]");
            }
        }
        if (f.kind == Kind.ENUM) {
            b.append(", \"values\": ");
            strings(b, f.enumValues);
        }
        if (f.kind == Kind.ASSET) {
            b.append(", \"asset\": ");
            string(b, f.assetKind);
        }
        if (!Double.isNaN(f.min) && !Double.isNaN(f.max)) {
            b.append(", \"min\": ")
                    .append(number(f.min))
                    .append(", \"max\": ")
                    .append(number(f.max));
        }
        if (f.angle) {
            b.append(", \"angle\": true");
        }
        if (f.color) {
            b.append(", \"color\": true");
        }
        if (!f.doc.isEmpty()) {
            b.append(", \"doc\": ");
            string(b, f.doc);
        }
        b.append("}");
    }

    private static String number(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }
```

An enum's default is written as its **name** (`"IDLE"`), not its ordinal, and the constants are
listed, so the editor can show a dropdown and a level can store a name. `number` writes a whole
`double` without a fractional part (`0`, not `0.0`), which keeps the file readable and the parser
simple. The escaping routine handles the four characters JSON needs escaped and every control
character:

```java
    static void string(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }
}
```

(`strings` writes a JSON array of them and is left out.) The demo's `Mind` and `see` in the output
file, `samples/iso-demo/build/classes/java/main/META-INF/jeddy/components.json`:

```json
    {"name": "Mind", "type": "dev.jeddy.samples.iso.Mind", "doc": "A skeleton's state machine and timers", "fields": [
      {"name": "state", "type": "enum", "default": "IDLE", "values": ["IDLE", "CHASE", "ATTACK", "DEAD"]},
      {"name": "timer", "type": "float", "default": 0, "doc": "Seconds to the next decision, path request or respawn"},
      {"name": "cooldown", "type": "float", "default": 0},
      {"name": "corpse", "type": "float", "default": 0},
      {"name": "home", "type": "vec3", "default": [0, 0, 0]}
    ]},
```

```json
    {"name": "SkeletonAi.see", "phase": "fixedUpdate", "parallel": true, "order": 0, "stage": 1, "reads": ["Monster", "Mind"], "writes": ["Sight"], "optional": [], "without": []},
```

The `reads` and `writes` lists are the sets of §6.1, so a docs panel (or you, reading the file) can
see the schedule the processor derived without opening the registry.

## 18. Step 10: the build wiring

### 18.1 Putting the processor on javac's path

Inside this repository the demo's `build.gradle.kts` says what a game needs:

```kotlin
dependencies {
    implementation(project(":app"))
    implementation(project(":editor"))
    implementation(project(":rules"))
    implementation(project(":components"))
    implementation(project(":save"))
    implementation(project(":gl"))
    // @Component records and @Behaviour classes become generated stores and the registry (§22).
    annotationProcessor(project(":component-processor"))
}
```

`implementation(project(":components"))` is the runtime: the annotations the sources import and the
`Store` and `View` the generated classes extend. `annotationProcessor(...)` is the compiler
plug-in: Gradle resolves that configuration separately, passes its jars to javac as
`-processorpath`, and sets `-s build/generated/sources/annotationProcessor/java/main`. The
`components` module's own tests do the same with `testAnnotationProcessor`, so the sample game
under `src/test/java/.../sample/` is compiled through the processor and its generated `SampleGame`
is what the tests exercise (§22). For a game outside the repository, the `dev.jeddy.game` plugin
adds the published artifact:

```kotlin
// The component processor (DESIGN §22): @Component records and @Behaviour classes in the game
// become generated stores, views, loops and the registry. It does nothing in a game without them.
dependencies {
    "annotationProcessor"("dev.jeddy:jeddy-component-processor:$JEDDY_VERSION")
}
```

### 18.2 The two `META-INF` files

`tools/component-processor/src/main/resources/META-INF/services/javax.annotation.processing.Processor`
is one line, `dev.jeddy.components.processor.ComponentProcessor`: the service registration javac's
loader reads from every entry of the processor path. The module descriptor says the same for a
module-path compilation (`provides javax.annotation.processing.Processor with ...`), so the
processor works either way.

`META-INF/gradle/incremental.annotation.processors` is one line too,
`dev.jeddy.components.processor.ComponentProcessor,aggregating`. Gradle's incremental Java
compilation recompiles only changed sources and their dependents, and it can only keep an
annotation processor in that scheme if the processor declares what kind it is. An **isolating**
processor generates files from one source at a time; an **aggregating** one, like this, reads many
sources to produce one file, so Gradle must re-run it with every annotated source whenever any of
them changes. The declaration is the processor's promise that it uses only the `Filer` and the
elements it is given, and nothing else from the file system.

Gradle's manual adds a condition this processor does **not** meet: an aggregating processor can
only read annotations with `CLASS` or `RUNTIME` retention, because Gradle finds the annotated
sources again from the compiled classes, and every annotation of §9 has `SOURCE` retention (§4.3).
What that does to an incremental build has not been tested for this chapter: the risk is a
changed record or behaviour whose registry is not regenerated, or regenerated without the
unchanged ones. A clean build always regenerates everything. This is listed as an open question
for the owner: either test an incremental build that edits one behaviour, or change the
retention to `CLASS` (which puts the annotations in the class files without making them visible to
reflection), or declare the processor without the incremental file.

### 18.3 The schema reaches the cooker: `--components`

The cooker validates a level's component rows against the game's schema when it has one. The
`dev.jeddy.assets` plugin gives `jeddyAssets` a `componentsSchema` property whose default is the
file the last compile wrote:

```kotlin
extension.componentsSchema.convention(layout.buildDirectory.file("classes/java/main/META-INF/jeddy/components.json"))
```

and the `cookAssets` task passes it on only when it exists (`Assets.kt`):

```kotlin
    /** The schema file; checked for existence when the task runs (it comes from a compile). */
    @get:Internal
    abstract val componentsSchema: RegularFileProperty

    @TaskAction
    fun cook() {
        val args = mutableListOf(
            "cook",
            "--source", sourceDir.get().asFile.absolutePath,
            "--out", cookedDir.get().asFile.absolutePath,
            "--cache", cacheDir.get().asFile.absolutePath,
            "--threads", threads.get().toString(),
        )
        shaderIncludeDirs.files.forEach { args += listOf("--shader-include", it.absolutePath) }
        if (!validateShaders.get()) {
            args += "--no-validate"
        }
        val schema = componentsSchema.orNull?.asFile
        if (schema != null && schema.isFile) {
            args += listOf("--components", schema.absolutePath)
        }
        args += extraArguments.get()
        runCooker(args)
    }
```

`@get:Internal` rather than `@get:InputFile`: the schema is an *output of compilation*, and a
declared `@InputFile` that does not exist fails the task before it runs, which on a first build or
in a game without components it would not. So the task checks for the file itself when it runs:
present, it is passed; absent, the cooker warns that rows were not validated and goes on. No task
dependency on `compileJava` is declared here; the cooker's own cache makes the schema a dependency
of each level (`LevelImporter.validateComponents` reads it as one, §20), so once the file exists a
changed schema re-cooks the levels.

### 18.4 The level bindings: `generateLevelBindings`

The same plugin registers the task that writes `<Name>Level.java` (D110):

```kotlin
// Level bindings (DESIGN §22, D110): only when jeddyAssets.bindingsPackage is set; dev.jeddy.game
// (and the demo) add the output directory to the main source set.
// A local, so the onlyIf lambda captures a Property and no script object (configuration cache).
val bindingsPackage = extension.bindingsPackage
tasks.register<GenerateLevelBindings>("generateLevelBindings") {
    group = "jeddy"
    description = "Writes <Name>Level.java for every .jlevel, with the named objects' ids."
    sourceDir = extension.sourceDir
    bindingsPackage = extension.bindingsPackage
    outputDir = extension.bindingsDir
    threads = extension.threads
    cookerClasspath.from(cookerRuntime)
    launcher = cookerLauncher
    onlyIf("jeddyAssets.bindingsPackage is set") { bindingsPackage.isPresent }
}
```

and the demo (like `dev.jeddy.game`) adds its output to the main source set. Because the source
directory is the task's output provider, Gradle runs `generateLevelBindings` before `compileJava`,
and `CryptLevel.java` is then compiled with the game's own sources (it carries no component
annotation, so the processor has nothing to do with it):

```kotlin
jeddyAssets {
    // Engine shader includes (#include "engine/...") for the cook-time shader checks.
    shaderIncludeDirs.from(rootProject.layout.projectDirectory.dir("modules/render/src/main/resources/dev/jeddy/render/shaders"))
    // CryptLevel and friends: the levels' named objects as constants (DESIGN §22, D110).
    bindingsPackage = "dev.jeddy.samples.iso.levels"
}

// The generated level bindings compile with the demo (dev.jeddy.game does this for game projects).
sourceSets.named("main") {
    java.srcDir(tasks.named<dev.jeddy.gradle.GenerateLevelBindings>("generateLevelBindings").flatMap { it.outputDir })
}
```

The comment about the local `bindingsPackage` records a pitfall (§24): Gradle's **configuration
cache** serializes every task's configuration, and a lambda that captures the build script object
cannot be serialized; capturing the `Property` in a local can. The task runs `jeddy-cook bindings`,
whose `LevelBindings` writes, for the demo's `levels/crypt.jlevel`:

```java
public final class CryptLevel {
    /** The level's asset path. */
    public static final String PATH = "levels/crypt.jlevel";

    private CryptLevel() {}

    /** The placement "chest". */
    public static final int PLACEMENT_CHEST = 119;
    // ... six more placements ...
    /** The light "candles". */
    public static final int LIGHT_CANDLES = 387;

    // Repeated names, no constant: PLACEMENT_FLOOR_TILE_LARGE, PLACEMENT_WALL, ... SPAWN_PARTY, SPAWN_SKELETON
}
```

Only an object whose name is unique among its kind gets a constant: a repeated name (`wall`,
`skeleton`) is a prefab's, not a designer's name for one object, and the comment lists what was
skipped so the omission is visible. The ids are the level file's stable object ids, so
`levelLoader.rootOf(CryptLevel.PLACEMENT_CHEST)` is the chest's entity after load, and a renamed
object is a compile error in every place that used it. The bindings are not asset outputs: the
cooker lower-cases asset paths, which is right for assets and wrong for `CryptLevel.java`, hence a
task of its own writing under `build/generated/sources/jeddy/java/main`, and the file is rewritten
only when its text changed, so an unchanged level does not touch its timestamp and trigger a
recompile.

---
# Part C — Using it and proving it

## 19. The demo: five records and two behaviour classes

### 19.1 The records

The skeleton's data was split into four records by what *changes together* and by who reads it:

```java
// Monster.java: a skeleton's identity, set at spawn and never by a behaviour
@Component(doc = "A skeleton: model, crowd agent, animator slot")
public record Monster(
        @Doc("0 warrior, 1 stalker, 2 adept") int model,
        @Default("-1") int agent,
        @Default("-1") int character,
        int index) {}

// Mind.java: the state machine, written by think and flinch
@Component(doc = "A skeleton's state machine and timers")
public record Mind(
        MonsterState state,
        @Doc("Seconds to the next decision, path request or respawn") float timer,
        float cooldown,
        float corpse,
        Vec3f home) {}

// Sight.java: written by the parallel see, applied by show
@Component(doc = "Seen by the party, and hidden in the world")
public record Sight(@Default("true") boolean visible, boolean hidden) {}

// Flash.java: the hit flash and the outline the world was last told
@Component(doc = "The hit flash and the outline style")
public record Flash(float flash, int style) {}
```

(Record Javadoc and imports left out.) Why four and not one `Skeleton` record? Because the
**dependency rule of §6 works on components**: `see` writes `Sight` and nothing else, so it could
run concurrently with another parallel behaviour that writes `Flash`; one record would make every
behaviour a writer of everything and serialize them all. Splitting by writer is the design rule the
split follows. `Monster.index` duplicates the row number for the old index-based code (§19.6);
`agent` and `character` default to −1, "none", since 0 is a valid agent.

The fireball is one record per cast, on an entity of its own:

```java
@Component(doc = "A fireball: flight, explosion, scorch")
public record Fireball(
        Vec3f from,
        Vec3f to,
        float age,
        boolean exploded,
        boolean burstPending,
        @Doc("The caster's creature") int caster) {}
```

Two `Vec3f`s become six `float` columns (`from.x` ... `to.z`), two booleans two `boolean[]`s, and
the whole store is `FireballStore` with ten columns. `Spells` used to declare eleven arrays by
hand; the eleventh, `boolean[] active`, said which slots held a fireball in flight, and the store's
membership replaced it: a fireball is active exactly when its entity has a row.

### 19.2 `think`, line by line

`SkeletonAi` is the behaviour class; its `think` is the old `Enemies.think` body with the arrays
replaced by views. It is 821 bytes of bytecode (§8), so it is called, not inlined; everything it
does per skeleton is here, condensed only by cutting the `switch` arms it shares with the old code.

```java
    /** One decision per tick: chase, strike, give up, lie dead, rise again. */
    @OnFixedUpdate(stage = 0)
    public static void think(
            MonsterView m,
            MindMut mind,
            SightView sight,
            FlashMut flash,
            float dt,
            int entity,
            Enemies enemies,
            Party party,
            Crowd crowd,
            Animator animator,
            AgentSync sync,
            Level level,
            Rng rng,
            World world) {
        Combat combat = enemies.combat;
        int i = m.index();
        if (flash.flash() > 0) {
            flash.setFlash(flash.flash() - dt);
        }
        mind.setCooldown(mind.cooldown() - dt);
        if (mind.state() == MonsterState.DEAD) {
            if (mind.corpse() > 0) {
                mind.setCorpse(mind.corpse() - dt);
                if (mind.corpse() <= 0 && !sight.hidden()) {
                    enemies.hide(i, true);
                }
            }
            mind.setTimer(mind.timer() - dt);
            if (enemies.respawnSeconds > 0 && mind.timer() <= 0) {
                enemies.respawn(i, party);
            }
            return;
        }
```

- **The signature is the query and the wiring.** `MonsterView` (read), `MindMut` (write),
  `SightView` (read), `FlashMut` (write): the processor records $R = \{$Monster, Sight$\}$, $W =
  \{$Mind, Flash$\}$ (the schema of §17 shows exactly that). `float dt` and `int entity` are the
  phase's; the eight others are singletons, so `CryptGame`'s constructor takes `Enemies`, `Party`,
  `Crowd`, `Animator`, `AgentSync`, `Level`, `Rng` and `World`.
- **`mind.setCooldown(mind.cooldown() - dt)`** is, after inlining, `store.cooldown[row] =
  store.cooldown[row] - dt` with `store` and `row` read once from the view (L03 §13.2). The old
  code wrote `cooldown[i] -= dt`; the generated form is the same instructions with one more
  indirection that hits L1.
- **The dead branch** counts the corpse timer down, hides the model through `Enemies.hide` (a
  singleton call that reaches the world), and respawns through `Enemies.respawn`, which writes the
  columns of *this* row directly (§19.6): a behaviour may touch its own entity's stores through a
  singleton, as long as it does not change structure.

```java
        int a = m.agent();
        float x = crowd.x(a);
        float z = crowd.z(a);
        int best = -1;
        float bestD2 = Float.MAX_VALUE;
        for (int k = 0; k < Party.SIZE; k++) {
            if (party.down[k]) {
                continue; // the fallen are no threat and no target
            }
            int pa = party.agent[k];
            float dx = crowd.x(pa) - x;
            float dz = crowd.z(pa) - z;
            float d2 = dx * dx + dz * dz;
            if (d2 < bestD2) {
                bestD2 = d2;
                best = pa;
            }
        }
        mind.setTimer(mind.timer() - dt);
        if (best < 0 && mind.state() != MonsterState.IDLE) {
            mind.setState(MonsterState.IDLE); // nobody left standing
            mind.setTimer(2);
            crowd.resetMoveTarget(a);
            combat.disengage(i);
        }
        switch (mind.state()) {
            case IDLE -> {
                if (best >= 0 && sight.visible() && bestD2 < Enemies.AGGRO * Enemies.AGGRO) {
                    mind.setState(MonsterState.CHASE);
                    mind.setTimer(0);
                    combat.engage(i);
                } else if (enemies.wander && mind.timer() <= 0) {
                    float[] w = level.walkable;
                    int o = rng.nextInt(level.walkableCount) * 3;
                    crowd.requestMoveTarget(a, w[o], w[o + 1], w[o + 2]);
                    mind.setTimer(5 + rng.nextFloat() * 6);
                }
            }
            case CHASE -> { /* leash, reach, re-path: as before, through the views */ }
            case ATTACK -> { /* cooldown, strike through combat, animator.action: as before */ }
            default -> {}
        }
        animator.setCombat(m.character(), mind.state() != MonsterState.IDLE);
    }
```

- **The nearest party member** is a six-iteration search with squared distances (no square root:
  comparing $d^2$ orders the same as comparing $d$); it reads the crowd's positions through
  `Crowd`, a singleton, for the party's agents, which are not components (§19.7).
- **`switch (mind.state())`** on an enum compiles, through javac's synthetic `$SwitchMap$` array
  (`SkeletonAi$1.class` in the output), to a `tableswitch` on the ordinal; `mind.state()` itself is
  `STATE_VALUES[store.state[row]]`, a reference the switch map then indexes by `ordinal()`. One
  could store the ordinal and switch on the `int` directly; the enum keeps the body readable and
  the two extra loads hit L1.
- **Everything the body calls is a singleton or a view.** There is no `this`, no field, no hidden
  state: a behaviour is a pure function of its parameters, which is what makes it testable and
  what lets the processor derive the schedule from the signature alone (D106).

### 19.3 `see`, `show`, `outline` and `flinch`

```java
    @OnFixedUpdate(stage = 1)
    @Parallel(grain = 32)
    public static void see(
            MonsterView m,
            MindView mind,
            SightMut sight,
            @ThreadSafe Visibility vis,
            @ThreadSafe Crowd crowd) {
        boolean v =
                mind.state() != MonsterState.DEAD
                        && vis.isVisible(crowd.x(m.agent()), crowd.z(m.agent()));
        sight.setVisible(v);
    }

    /** Applies the visibility to the world (hidden groups) and counts the visible. */
    @OnFixedUpdate(stage = 1)
    public static void show(MonsterView m, MindView mind, SightMut sight, int entity, World world) {
        if (mind.state() != MonsterState.DEAD && sight.hidden() == sight.visible()) {
            sight.setHidden(!sight.visible());
            world.setGroupHidden(entity, !sight.visible());
        }
    }
```

`see` is the parallel half: one grid lookup per living skeleton, writing one boolean. It takes
`Visibility` and `Crowd` as `@ThreadSafe` because it only *reads* them (`isVisible`, `x`, `z`), and
the fog has finished updating before stage 1 runs (§19.5). It cannot take `World`, which is not
thread-safe, so **applying** the result is `show`, sequential, in the same stage right after it:
`runs` (§16.3) makes the graph with `see` end before `show`'s loop starts. The pair is the pattern
for every "compute in parallel, apply on the game thread" behaviour. A grain of 32 makes
$\lceil 300 / 32 \rceil = 10$ chunks for the perf scene.

```java
    /** Pushes outline changes once per frame: hostile while hovered or just hit. */
    @OnUpdate
    public static void outline(
            MonsterView m,
            MindView mind,
            FlashMut flash,
            int entity,
            Enemies enemies,
            World world) {
        int s =
                mind.state() != MonsterState.DEAD
                                && (enemies.hovered == m.index() || flash.flash() > 0)
                        ? RenderPacket.HIGHLIGHT_HOSTILE
                        : 0;
        if (s != flash.style()) {
            flash.setStyle(s);
            world.setGroupHighlight(entity, s);
        }
    }

    /** Hurt by the rules (the combat raises the signal): flash, flinch, wake up. */
    @OnEvent(Signals.HURT)
    public static void flinch(
            MonsterView m, MindMut mind, FlashMut flash, Enemies enemies, Animator animator) {
        if (mind.state() == MonsterState.DEAD) {
            return;
        }
        flash.setFlash(0.35f);
        if (mind.state() == MonsterState.IDLE) {
            mind.setState(MonsterState.CHASE);
            mind.setTimer(0);
            enemies.combat.engage(m.index());
        }
        animator.action(m.character(), Animator.HIT);
    }
```

`outline` is an `@OnUpdate` behaviour: once per *frame*, because the highlight is a render-side
thing and the hover changes with the mouse, not the tick; it writes the world only when the style
changed, so an unchanged frame costs a compare per skeleton. `flinch` is the signal handler. The
chain that reaches it: the rules' `Combat.tick` drains the rules' event ring (chapter
[15](15-rules.md), on the rules), finds a `DAMAGE` event whose target is an enemy creature, and calls
`enemies.flinch(i)`, which is now one line:

```java
    /** Skeleton {@code i} was hurt: the {@link SkeletonAi#flinch} behaviour reacts. */
    void flinch(int i) {
        game.signal(Signals.HURT, root[i], 0, 0);
    }
```

with `Signals.HURT = 1` the demo's own constant. The registry's `signal` switches on the kind and
calls `SkeletonAi_flinch_one(entity, a, b)` (§16.8): three lookups, three `at`s, one call. (The
demo passes `0, 0` as the payload although `Signals` documents `a` as the amount and `b` as the
damage type; `flinch` does not read them, so nothing is lost yet.)

### 19.4 `Fireballs.fly`

```java
@Behaviour
public final class Fireballs {
    private Fireballs() {}

    @OnFixedUpdate(stage = 2)
    public static void fly(
            FireballMut f, float dt, int entity, Spells spells, CryptGame game, World world) {
        f.setAge(f.age() + dt);
        if (!f.exploded() && f.age() >= Spells.TRAVEL) {
            f.setExploded(true);
            f.setBurstPending(true);
            spells.explode(f);
        }
        if (f.age() >= Spells.TRAVEL + Spells.EXPLOSION) {
            game.despawn(entity); // removing the visited row is safe: the loop walks backwards
            world.entities().destroy(entity);
        }
    }
}
```

The fireball is the case §5.3 was built for: it **removes the entity it is visiting**. `game.despawn`
takes its `Fireball` row (the last row moves into the hole, already visited), then
`world.entities().destroy` frees the handle. It takes `CryptGame game` for that, so it cannot be
`@Parallel` (exercise 1). `spells.explode(f)` is handed the *view*: `Spells.explode(FireballView f)`
reads `f.toX()`, `f.toY()`, `f.toZ()` and `f.caster()` for its overlap query and the rules, and
does not keep the view, which L03 §11.3 says it must not. Stage 2, after the crowd and the
collision world have moved, so the explosion catches the bodies where they are now.

### 19.5 The registry in `DemoGame`

The registry is built after the ten objects it takes, in `DemoGame.init`:

```java
        // The generated registry (DESIGN §22): the stores of the skeletons and the fireballs
        // and the singletons their behaviours take. Built after the systems it refers to.
        game =
                new CryptGame(
                        world.entities(),
                        options.inlineBehaviours ? null : ctx.jobs(),
                        sync,
                        animator,
                        level.crowd,
                        enemies,
                        level,
                        party,
                        rng,
                        spells,
                        level.visibility,
                        world);
        enemies.attach(game);
        spells.attach(game, world.entities(), level.collision);
```

`--inline-behaviours` passes `null` for the job system (§16.2). `Enemies` and `Spells` are
constructed *before* the registry (they are its parameters) and get it afterwards through
`attach`: a two-step construction forced by the dependency in both directions, and the only
place in the demo where a singleton holds a reference to the registry. The level's rows and the
editor's live hook come next (§20), and the first visibility runs once before the game starts:

```java
        level.fog.update();
        game.fixedUpdateStage(1, 0); // see and show: the first visibility
```

The tick (`DemoGame.fixedUpdate`, condensed to the component calls and their neighbours):

```java
        long b0 = System.nanoTime();
        game.fixedUpdateStage(0, dt); // the skeletons think (SkeletonAi.think)
        behaviourNanos = System.nanoTime() - b0;
        a.mark(AllocProbe.THINK);
        party.tick();
        party.fight(dt, enemies);
        combat.tick(dt);
        // ...
        level.crowd.update(dt);
        sync.apply(dt);
        level.collision.update();
        b0 = System.nanoTime();
        game.fixedUpdateStage(2, dt); // the fireballs fly and explode (Fireballs.fly)
        behaviourNanos += System.nanoTime() - b0;
        // ... the party's viewers ...
        level.fog.update();
        b0 = System.nanoTime();
        game.fixedUpdateStage(1, dt); // see (parallel) and show
        behaviourNanos += System.nanoTime() - b0;
        a.mark(AllocProbe.VISIBILITY);
        world.updateTransforms(ctx.jobs());
```

Three stage calls at the three places the old calls were, timed into `behaviourNanos` for the perf
report's row (§23), with the allocation probe's marks around them (chapter
[12](12-java-performance.md#175-allocprobe-which-system-allocated), on the probe). Note the order:
stage 2 runs *before* stage 1 in the demo's tick. Stages are labels the game calls in the order it
needs; `fixedUpdate(dt)` would run them 0, 1, 2, which is not this game's order. Per frame,
`update` runs `game.update(dt, alpha)` for the outlines.

### 19.6 `Enemies` and `Spells` as facades

`Enemies` shrank from the system that *was* the skeletons to a facade that spawns them and answers
the lookups the rest of the demo already had. Its `attach` puts every skeleton into the stores:

```java
    void attach(CryptGame game) {
        this.game = game;
        MonsterStore monsters = game.monster();
        MindStore minds = game.mind();
        SightStore sights = game.sight();
        FlashStore flashes = game.flash();
        for (int i = 0; i < count; i++) {
            int e = root[i];
            int row = monsters.add(e);
            if (row != i || minds.add(e) != i || sights.add(e) != i || flashes.add(e) != i) {
                throw new IllegalStateException("skeleton " + i + " is not row " + i);
            }
            monsters.model()[i] = model0[i];
            monsters.agent()[i] = agent0[i];
            monsters.character()[i] = character0[i];
            monsters.index()[i] = i;
            minds.timer()[i] = rng.nextFloat() * 4;
            minds.homeX()[i] = home0[i * 3];
            minds.homeY()[i] = home0[i * 3 + 1];
            minds.homeZ()[i] = home0[i * 3 + 2];
        }
    }
```

**Skeleton $i$ is row $i$ of every store**, and `attach` asserts it: rows are added in order and
never removed, because a dead skeleton keeps its row and rises again. That invariant is what let
the old index-based code (`Combat`'s tables, `Party.fight`, the HUD) keep working with `i`
unchanged; it is also *fragile*, which is why the check throws rather than trusts. The columns are
filled through the array accessors (`monsters.agent()[i] = ...`) rather than `add(e, new
Monster(...))`: no record, no allocation, and the loop reads like the old one. The lookups the demo
still needs are hand-written column loops, as chapter [01](01-architecture.md#7-data-oriented-design-in-summary)
taught:

```java
    int nearestAlive(float x, float z, float radius) {
        int[] state = game.mind().state();
        boolean[] visible = game.sight().visible();
        int[] agent = game.monster().agent();
        int dead = MonsterState.DEAD.ordinal();
        int best = -1;
        float bestD2 = radius * radius;
        for (int i = 0; i < count; i++) {
            if (state[i] == dead || !visible[i]) {
                continue;
            }
            int a = agent[i];
            float dx = crowd.x(a) - x;
            float dz = crowd.z(a) - z;
            float d2 = dx * dx + dz * dz;
            if (d2 < bestD2) {
                bestD2 = d2;
                best = a;
            }
        }
        return best;
    }
```

Three columns hoisted into locals, one `ordinal()` outside the loop, then a plain loop over
`[0, count)`: this is the lower-bound shape of `ComponentsBench` (L03 §14.1), available whenever a
behaviour would be the wrong tool (a query that returns one answer, not a per-entity update).
`kill`, `respawn`, `hide`, `extractLights` and `write`/`read` are written the same way.

`Spells` casts by adding a row and filling its columns, explodes from a view, and extracts over the
columns:

```java
    boolean cast(int casterCreature, float sx, float sy, float sz, float tx, float ty, float tz) {
        FireballStore store = game.fireball();
        if (store.size() >= MAX) {
            return false;
        }
        int row = store.add(entities.create()); // the columns, not the record: no allocation
        store.fromX()[row] = sx;
        store.fromY()[row] = sy + 1.6f;
        store.fromZ()[row] = sz;
        store.toX()[row] = tx;
        store.toY()[row] = ty;
        store.toZ()[row] = tz;
        store.caster()[row] = casterCreature;
        casts++;
        return true;
    }
```

`entities.create()` makes the fireball's entity and `store.add` its row; `age`, `exploded` and
`burstPending` keep their defaults (0, `false`, `false`) from `resetRow`. `MAX = 16` caps the rows,
within `CAPACITY`.

### 19.7 What stayed a singleton, and why

`Party` and `Combat` did not become components, deliberately. The party is six fixed members with
a selection, a formation and orders: user-interface state, not per-entity data that varies in
count. `Combat` is the glue between the rules and the world, an orchestrator with no entities of
its own. DESIGN §22 states the criterion: a system becomes components when it has **many** entities
with **the same** fields and logic; singletons are the things behaviours *call*, not the things that
run. The skeletons (300) and the fireballs (any number) qualified; a party of six with a cursor
did not, and turning it into components would have made every behaviour that reads
`party.agent[k]` take a `PartyView` it has no entity for.

## 20. Levels, the inspector and the cooker

A `.jlevel` of format version 2 carries **component rows**: per level object, the components the
editor attached and the values it set, keyed by component and column names with typed values
(D109; chapter [14](14-editor.md), on the level format; [formats/jlevel.md](../formats/jlevel.md)
has the byte layout). The demo loads them right after the registry is built:

```java
        dev.jeddy.components.Levels.Binding binding = levelLoader::rootOf;
        game.load(levelLoader.level(), binding);
        editor.attachSchema(
                Editor.schemaOf(DemoGame.class),
                (lvl, id) -> {
                    int e = levelLoader.rootOf(id);
                    if (e != 0) {
                        game.despawn(e);
                        game.loadObject(lvl, id, e, binding);
                        game.spawn(e);
                    }
                });
```

- **The binding is `levelLoader::rootOf`**: a placement's root entity by its object id, which is
  what `Levels.load` asks for every row (§12.3). `game.load` applies the rows and runs the spawn
  behaviours once per entity (§16.5).
- **`Editor.schemaOf(DemoGame.class)`** reads `META-INF/jeddy/components.json` through the class
  loader: the file the processor wrote into the class output (§17) travels in the jar.
- **The `ComponentSync` lambda** is the editor's hook for a live edit (chapter [14](14-editor.md),
  on the inspector): when a row changes, the object's entity is despawned, its rows loaded again
  from the edited level and its spawn behaviours run, so the change is live in the running game.

In the editor's inspector the *Components* section is driven by the schema: one block per row with
a field per record component, a slider from `@Range`, degrees for an `@Angle` (stored in radians),
a color picker for a `@Color`, a dropdown over the level's objects for an `@EntityRef`, a path
text field for an `@AssetRef` (the annotation's Javadoc promises an asset browser; today it is a
field), and an *Add component...* dropdown over every component the schema
lists; every edit is a command with undo and merging (`ComponentRowCommand`,
`SetComponentValueCommand`). The standalone editor tool takes the schema with `--components FILE`,
defaulting to the sibling `build/classes/java/main/META-INF/jeddy/components.json`, and edits rows
without a running game.

The cooker validates rows against the schema when the cook has one (`--components`, §18.3):

```java
        for (int row = 0; row < level.componentRowCount(); row++) {
            String name = level.componentRowComponent(row);
            int object = level.componentRowObject(row);
            ComponentSchema.Component c = schema.component(name);
            if (c == null) {
                ctx.warn("object " + object + ": unknown component " + name);
                continue;
            }
            for (int k = 0; k < level.componentValueCount(row); k++) {
                String column = level.componentValueColumn(row, k);
                ComponentSchema.Field f = c.fieldOfColumn(column);
                if (f == null) {
                    ctx.warn("object " + object + ": " + name + " has no column " + column);
                } else if (f.type() == ComponentSchema.Type.ENUM
                        && level.componentValueType(row, k) == LevelData.VALUE_ENUM
                        && !f.enumValues().contains(level.componentValueText(row, k))) {
                    ctx.warn( /* "object N: Mind.state has no constant GHOST" */ );
                }
            }
        }
```

(From `LevelImporter.validateComponents`, condensed.) Unknown components, columns and constants are
**warnings**, since the runtime skips them too (§12.3): a level must never fail to cook because a
record was renamed in the game. The three readers of the schema (inspector, cooker, docs) and the
one writer (the processor) never meet at run time; the file is the contract.

## 21. Snapshots and saves

`Store.write` and `read` (§10.4–10.6) are the data layer; the registry writes all stores and reads
them by name (§16.5); the game supplies the identity. The demo's `CryptIds` implements `EntityIds`
with four kinds:

```java
final class CryptIds implements EntityIds {
    static final int PARTY = FIRST_GAME_KIND;
    static final int SKELETON = FIRST_GAME_KIND + 1;
    static final int FIREBALL = FIRST_GAME_KIND + 2;
    // ...
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

A party member is "member $i$", a skeleton "the one that spawned $i$th" (both exist before a load,
since the level is rebuilt first, so `entityOf` looks them up), a level object its object id, and a
fireball a counter that restarts at every capture: a fireball is the same fireball only within one
save, and `entityOf` **creates** an entity for it. A skeleton's row in `Monster` therefore comes
back at the same row (`Enemies.attach` has already made row $i$), its `agent` column restored, and
the save's other chunks (the crowd, the facings, the animations) line up by that agent index.
`CryptSave` writes the registry as its `COMP` chunk, second of ten, with `game.write(writer.chunk(COMP,
VERSION), ids)` and reads it back second too, because every later chunk depends on the agents the
stores name. Chapter [16](16-saves.md), on saves, has the whole file around it: the chunks, the
versions, the capture without a hitch.

## 22. The tests, walked through

Two test classes cover the two halves. `GeneratedGameTest` (in `modules/components`) exercises the
**generated code** for the sample game under `src/test/java/dev/jeddy/components/sample/`, which
the test build compiles through the processor: four records (`Health`, `Position`, `Tagged`,
`Dead`), one enum (`Team`), four behaviour classes (`Regen`, `Movers`, `Reaper`, `Lifecycle`) and a
`Counts` singleton they record into, with `@Components(name = "SampleGame", capacity = 4)`, a
capacity chosen small so that growth is exercised. `ComponentProcessorTest` (in the processor's
project) exercises the **diagnostics** through the Compiler API. The sample package is the smallest
complete example of every feature; read it before writing your own.

### 22.1 `storesAddRemoveAndSwapRowsLikeSparseSets`

Adds three entities to `health` (the second with `new Health(3, 5)`), checks the rows 0, 1, 2, that
adding `a` again keeps row 0, that the `@Default("10")` reached `current()[0]`, that `get(b)` equals
`new Health(3, 5)` (record equality); removes `a` and checks that `c` moved into row 0
(`entityAt(0) == c`, `indexOf(c) == 0`, `indexOf(a) == -1`); destroys `b` through the pool and
checks its row went and that `add(b)` with the stale handle throws; then adds twenty more rows past
the capacity of 4 and reads the last one back. Everything L03 §8–9 promised, on a generated store.

### 22.2 `everyFieldTypeRoundTripsThroughTheRecordAndTheColumns`

`Tagged(Team team, @EntityRef int target, @AssetRef("mesh") int mesh, boolean hostile, long seen)`
is one of every field type. The test checks the defaults as a record (`new Tagged(Team.UNDEAD, 0,
0, true, 1234567890123L)`), a round trip through `add(e, value)` and `get(e)`, then the generic
column API by name: `columnCount() == 5`, `columnName(0) == "team"`, `columnType(0) == INT`,
`columnType(4) == LONG`, `columnIndex("nope") == -1`, `enumOrdinal(0, "NEUTRAL") == 2`,
`enumOrdinal(1, "NEUTRAL") == -1` (not an enum column), `enumName(0, 0) == "PARTY"`, the typed
setters, and that `getFloat(row, 0)` on an `int` column throws. Then `Position(Vec3f pos, float
yaw)`: columns `pos.x`, `pos.y`, `pos.z`, `yaw`, the vector default `(1, 2, 3)`, `setFloat(p, 2,
9f)` landing in `posZ()`. And `Dead()`, a tag with no fields: `columnCount() == 0`, and it still
works as a set.

### 22.3 `fixedUpdateRunsTheQueriesWithWithoutAndOptional`

Four entities: one with health, one with health and `Dead`, one with health and `Tagged`, one with
only a position. After `fixedUpdate(0.5f)`: the first and third regenerated (`Regen.regen`), the
dead one did not (`@Without({Dead.class})`), the positioned one was never visited (no `Health`);
`Regen.count`, with `@Optional TaggedView t`, saw `present()` true once and false twice.

### 22.4 `aBehaviourMayDespawnTheEntityItVisits`

Ten entities with health alternating −5 and 5. Stage 0 (`Regen`, `Movers`) leaves all ten; stage 1
(`Reaper.reap`, which calls `game.despawn(entity)` for `current <= 0`) leaves five, every other
one, and `despawn` removed their `Position` rows too; the entities stay alive in the pool ("despawn
leaves the entity to the game"). This is §5.3 under test: five removals during one backwards loop
and nothing skipped.

### 22.5 `parallelBehavioursGiveTheSameResultAsInlineOnes`

The three `Movers` plus `drift` (per frame), over 37 entities, two-thirds of which have health,
five ticks and five frames, once with `jobs == null` and once with a `JobSystem(3)`: the positions,
yaws and maxima must agree to $10^{-6}$. With grains of 2 and 3 the 37 rows make many chunks,
so what this test exercises is the parallel machinery: the kernels, the chunking, the per-thread
views indexed by thread slot, and the graph runs themselves. It **cannot detect a missing edge**:
the three behaviours conflict on components but touch disjoint *fields* (`turnLeft` writes `yaw`,
`rise` writes `pos.y` and reads `current`, `bleed` writes `max`), so any order gives the same
numbers. The edges are checked by reading the generated constructor (§6.3, exercise 4), not by
this test. A sanity check on the values themselves: yaw grew by $5 \times 0.25 = 1.25$, `x` by
$5 \times 0.1 \times 0.5 = 0.25$.

### 22.6 `lifecyclePhasesReachTheMatchingEntities`

One entity with health and position, one with nothing. `spawn` runs `Lifecycle.spawned` once (it
fills health to max); `turn` once; `trigger(5, e, ENTER)` once and records `trigger * 10 + kind =
51`; an unknown kind 99 is ignored; `dispatch` over a packed array of three events reaches two;
`signal(HURT, e, 4, 2)` reaches `hurt` with `a = 4`, `b = 2` and takes 4 hit points; an unknown
signal kind does nothing; `despawn` runs `despawned` and leaves `hasAny(e)` false, and a second
`despawn` runs nothing.

### 22.7 `snapshotsRoundTripAndTolerateChangedStores`

Writes the whole registry with handles as ids, changes everything (a different `Health`, a removed
`Position`, a removed `Dead`), reads back and checks every store is as written. Then a hand-built
stream (`ByteWriter` calls that spell out the format of §10.4): a store `Ghost` this build does not
have, with one `float` column `boo`, followed by `Health` with `current` and a column `armor` this
build lacks; `read` skips `Ghost`, applies `current = 6`, ignores `armor`, keeps `max` at its
default 10, and drops `a`, which the hand-built `Health` did not mention. A two-byte stream throws
`ByteReader.Truncated`, a subclass of `IllegalStateException`, from the registry's first `r.int32()`
(the store count), before any name is read.

### 22.8 `snapshotsTranslateEntitiesAndReferencesThroughStableIds` and `aSecondCaptureAllocatesNothing`

An `EntityIds` of the test's own ("kind 2, value = the entity's slot index") with a remap table:
after writing, the pool's entities are destroyed and re-created in another order, `c` is gone; the
read puts `a`'s health on `a2`, drops the row of the vanished `c` with a warning, and translates
the `@EntityRef target` of `b2` to `a2`. Then a stale handle in a reference column: written as
`NONE`, read as 0. The static helpers `id`, `object`, `kind`, `value` and their range checks are
covered too. The allocation test writes twenty entities with three components each once (so the
writer's buffer has grown), then asserts with `Allocations.assertNoAllocation` that a second
`write` allocates nothing and produces the same number of bytes.

### 22.9 `theSchemaDescribesEveryComponentAndBehaviour` and `levelRowsLoadIntoTheStoresByName`

The schema test parses `components.json` from the test class output through `ComponentSchema` and
checks the registry's name, four components, `Health.current`'s type, default 10, range to 100 and
doc, `Position.pos`'s `vec3` default and columns and `yaw`'s `angle`, `Tagged.team`'s constants and
default ordinal 1, the `entity` and `asset` types, `Dead`'s empty fields, and for the behaviours
`Regen.regen`'s phase, `writes = [Health]`, `without = [Dead]`, `Movers.rise` parallel with
`writes = [Position]`, `reads = [Health]`, thirteen behaviours in all. The level test builds a
`LevelData` in memory with rows for a chest and a door, including an unknown column, an unknown
component and an object with no entity, loads it with a `Binding` that maps the two objects and
resolves `meshes/chest.jmesh` to 42, and checks four rows applied, `spawned` run once (it refilled
the chest's health to 10), the `Tagged` row's enum, entity reference and asset handle, `pos.y = 7`
with the other two parts at their defaults, and then `loadObject` after a `despawn`: three rows,
no spawn behaviour this time, so `current` is the row's 3.

### 22.10 `aTickAllocatesNothingInlineAndOnTheJobSystem`

Three hundred entities, each with health and position, every fourth tagged, every seventh dead,
plus one more; then, once with `jobs == null` and once with a `JobSystem(2)`,
`Allocations.assertNoAllocation` over `fixedUpdate`, `update`, `turn`, `trigger` and `signal`
together: every phase, 0 bytes, with at most 10 rounds of 200 calls. The assertion counts the
**calling thread's** allocation (chapter [12](12-java-performance.md#125-what-it-proves-and-what-it-does-not),
on what the assertion proves), so the jobs run proves the game thread's side of the dispatch; the
workers' kernels are the same `_range` methods the inline run proved.

### 22.11 `ComponentProcessorTest`

Each test compiles two or three strings as source files with the real processor through
`javax.tools.JavaCompiler`, with the engine's flags (`-Xlint:all,-processing -Werror --release
27`) and the test's class path, into a temporary directory, and inspects the diagnostics and the
files. `aValidGameYieldsStoresViewsTheRegistryAndTheSchema` checks that `HealthStore.java`,
`HealthView.java`, `HealthMut.java` and `TestGame.java` were generated, that `TestGame.class` was
compiled from them, that the schema contains `"name": "Health"`, `"default": 3`, `"name":
"Regen.regen"` and `"phase": "spawn"`, and that the registry's text contains `public void
fixedUpdate(float dt)`, the call `t.Regen.regen(Regen_regen_v0, dt);` and the backwards `for`.
`componentsWithoutBehavioursNeedNoRegistry` checks a lone `@Component` yields a store and no schema.
The other four compile wrong shapes and assert the message of §14 for each: a class instead of a
record, a package-private record, a `String` field, `@Default("x")` on an `int`, `@EntityRef` on a
`float`, `@Angle` on an `int`, a two-number default on a `Vec3f`, an instance method, `float
speed`, `alpha` in a fixed update, a behaviour with no required view, `GhostView`, two phase
annotations, a `@Behaviour` class without behaviours, `@Parallel` on `@OnSpawn`, a parallel
behaviour with a plain singleton and with the registry, `@Without(String.class)`, behaviours
without a registry, and two `Health` records in two packages (one error, not two). The last case
in `behaviourShapesAreChecked` is a *valid* parallel behaviour with a `@ThreadSafe StringBuilder`,
to show the rule is about the mark, not the type.

Run both with `./gradlew :components:test :component-processor:test`.

## 23. Measured numbers

Every number here is quoted from where it was measured.

- **The perf capture's row** *Component behaviours per tick (N skeletons think, see on jobs, show;
  the fireballs fly)*, on the 2-core container at M22 (2026-10-12), the behaviours of `think`,
  `see`, `show` and `fly` over 300 skeletons: avg 0.14 ms on the job system and 0.11 ms with
  `--inline-behaviours` (DESIGN §22 records both averages); p99 0.55 and 0.51 ms, as the M22 perf
  capture was quoted by the previous version of this chapter (no other record of the p99s exists
  in the repository). The budget the row checks is p99 ≤ 1 ms. The game thread
  stays at 0 bytes per frame in the second half of the run (chapter
  [12](12-java-performance.md#25-the-perf-capture-in-practice), on the perf capture).
- **`ComponentsBench`** (L03 §14), 10,000 entities, one tick, CI's smoke run of 2026-10-12:
  `objectsWithVirtualUpdate` 142 µs; `soaColumnsByHand` 4.8 µs; `generatedBehaviour` 6.5 µs; and
  22 µs for the generated loop before the single-store lookup was removed (§5.4).
  Ratios: the generated loop is 22 times faster than the object shape and 1.35 times the
  hand-written loop; the 1.7 µs difference is 0.17 ns per entity, the loop's bookkeeping, not the
  views.
- **Bytecode sizes** (§8), measured with `javap` on the demo's generated classes, JDK 27+35:
  `think` 821 bytes, `see` 49, `show` 56, `outline` 62, `flinch` 63, `fly` 68;
  `SkeletonAi_think_loop` 249, `SkeletonAi_see_range` 168, `Fireballs_fly_loop` 81; every getter of
  `MindView` at most 17 and `at` 11; `Store.indexOf` 32, `SparseSet.indexOf` 24.
- **A snapshot of two `Health` rows is 70 bytes** (§10.4), measured by running `Store.write`.
- **`GeneratedGameTest.aTickAllocatesNothingInlineAndOnTheJobSystem`**: 0 bytes, every phase,
  inline and on two job threads (§22.10).
- **What the registry allocates at construction** (computed from the demo's declarations, §8):
  45,056 bytes of columns and 10,240 bytes of entity arrays for five stores of 512 rows, 27 views
  for `see` on nine thread slots, one view per parameter of the other behaviours, one graph.

## 24. Pitfalls met on the way

- **Error types.** A processor that calls `getQualifiedName()` on a parameter type that does not
  exist yet gets nothing useful; the type's kind is `ERROR` and `toString()` returns what was
  written. Classify by name, and only trust a `DECLARED` type's qualified name (§4.2, §14.5).
- **`-Werror` applies to generated code.** A `switch` *expression* with only a throwing `default`
  does not compile ("no result expressions"), so `enumOrdinal` and the typed getters are
  statements (§15.4); a Javadoc comment before the `import`s is a dangling comment javac warns about,
  so the generated files put the class comment after them (§15.2).
- **The configuration cache.** A Gradle task's `onlyIf { extension.x.isPresent }` captures the
  build script, which the configuration cache cannot serialize; capture the `Property` in a local
  instead (§18.4). CI caught it.
- **Rows move.** Anything that keeps a row index across structural changes is wrong; keep the
  entity and ask `indexOf` again. `Enemies` keeps row indices only because its stores never remove,
  and asserts it (§19.6).
- **Lower-cased asset paths.** The cooker normalizes output paths to lower case, right for assets
  and wrong for `CryptLevel.java`; the bindings are a separate task, not an importer output
  (§18.4).
- **The driving store's own lookup** cost 15.5 µs over 10,000 rows (22 µs against 6.5, the
  overhead over the hand-written loop falling from 17.2 µs to 1.7) until a benchmark showed it
  (§5.4, L03 §14.2). Measure, then read the generated code.
- **A parallel behaviour is not always faster** (§6.5). Measure.

## 25. Things to run

- Build the demo and read the generated sources:
  `samples/iso-demo/build/generated/sources/annotationProcessor/java/main/dev/jeddy/samples/iso/`
  (`MindStore`, `MindView`, `MindMut`, `CryptGame`), the schema at
  `samples/iso-demo/build/classes/java/main/META-INF/jeddy/components.json`, and the bindings at
  `samples/iso-demo/build/generated/sources/jeddy/java/main/dev/jeddy/samples/iso/levels/CryptLevel.java`.
- `./gradlew :components:test :component-processor:test`: the sample game under
  `modules/components/src/test/java/dev/jeddy/components/sample/` and the diagnostics (§22).
- `./gradlew :bench:jmh "-Pjmh.includes=ComponentsBench"`: the three shapes of one tick (L03 §16
  says what to vary).
- Add a component to the demo: a `@Component public record Loot(@Default("10") int gold) {}` next
  to `Mind`, and in a `@Behaviour` class `@OnTrigger(OnTrigger.ENTER) public static void
  open(LootMut l, int trigger, Enemies e) { ... }`. The build regenerates `CryptGame` with a `loot()`
  accessor and a `trigger` body; the inspector lists *Loot* under *Add component...* on any
  placement; `game.load` puts it on the placement's entity.
- `./gradlew :iso-demo:run --args="--headless --perf 20 --warmup 6 --inline-behaviours"` against the
  same run without the flag: the *behaviours* row of the report (§23), on your machine.
- The standalone editor with the schema: `./gradlew :editor-tool:run --args="--project
  samples/iso-demo/assets --level levels/crypt.jlevel"` finds the demo's `components.json` in its
  build output (or pass `--components FILE`); select the chest and add a *Mind*.
- `javap -c -p` on `CryptGame.class` and `MindView.class` (§8): find the `invokevirtual` on a
  `final` method, the `tableswitch` in `fixedUpdateStage`, and the `invokestatic` of `think`. With
  `-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining` on a perf run, find `MindView::timer` inlined
  and `SkeletonAi::think` "too big" (L03 §16 does the same for the benchmark).

---
## Exercises

Each exercise has a hint and a full solution; try before you open them. Exercises 1, 2, 5, 7 and 9
change the demo or the processor (revert them afterwards); 3, 4, 8, 10, 11, 12 and 13 are pencil
and paper, or a few lines in `jshell`; 6 adds a test.

**1. A parallel fireball.** Mark `Fireballs.fly` `@Parallel(grain = 4)` and build. Which messages
does the processor give, which parameters would have to go to silence them, and why could the
flight still not be parallel even then?

<details><summary>Hint</summary>

§6.2 and §14.6 list the two rules the processor enforces; §5.3 and D107 say what a parallel
behaviour may never do, whatever its parameters.
</details>

<details><summary>Solution</summary>

Three errors, one per singleton parameter, each at the parameter: `game` gives "a @Parallel
behaviour may not take the registry (structural changes)", `spells` and `world` give "a @Parallel
behaviour may only take a singleton marked @ThreadSafe at the parameter". Marking `spells` and
`world` `@ThreadSafe` silences two of them (a lie: `Spells.explode` runs an overlap query, calls
the rules and plays a sound, and `World.entities().destroy` changes the pool); nothing silences the
third, because `game.despawn(entity)` is a structural change and the registry is the only way to
make one. Even a version that only aged the fireballs in parallel would have to leave the explosion
and the despawn to a sequential behaviour in a later stage, for the reason §5.3 gives: the
backwards loop makes removal of the visited row safe on one thread, and no rule makes it safe on
several. The right split is `age` in parallel (writes `Fireball`, takes nothing), then `explode`
sequential in the same stage (reads `Fireball`, takes `Spells`, `CryptGame`, `World`): `runs`
(§16.3) ends the graph before the sequential loop starts.
</details>

**2. Counting the flashless.** Write an `@OnFixedUpdate` behaviour over `MonsterView` and
`@Optional FlashView` that counts the skeletons without a `Flash` component into a singleton (there
are none at first; remove one with `game.flash().remove(e)` from the debugger, or from a test, to
see it count).

<details><summary>Hint</summary>

`present()`; a singleton is any class the behaviour takes that is not a view or a phase scalar, and
the registry's constructor then needs an instance of it.
</details>

<details><summary>Solution</summary>

```java
/** A singleton the behaviour counts into (reset it before each tick). */
public final class Census {
    public int withoutFlash;
}

@Behaviour
public final class Counters {
    private Counters() {}

    @OnFixedUpdate(stage = 0)
    public static void flashless(MonsterView m, @Optional FlashView flash, Census census) {
        if (!flash.present()) {
            census.withoutFlash++;
        }
    }
}
```

The build regenerates `CryptGame` with a `dev.jeddy.samples.iso.Census census` parameter (between
`animator` and `crowd`, by field name) and a loop that drives from `monster` alone (one required
store, so `int r0 = i;`), looks `flash` up without skipping (`int r1 = flash.indexOf(e);`, no `if`),
and positions `Counters_flashless_v1` at −1 for a skeleton without `Flash`. `DemoGame` must pass a
`new Census()` to the constructor and zero `withoutFlash` before stage 0. In a test, remove one
entity's `Flash` with `game.flash().remove(e)`, run `game.fixedUpdateStage(0, dt)` and expect 1.
</details>

**3. The bytes of `think`.** Compute, for the `think` loop at 300 rows, the bytes the four stores'
columns contribute per tick and the number of cache lines; add the lines the lookups touch; compare
with 300 skeletons as objects of the same fields (JDK 27: 8-byte headers, 8-byte alignment, 4-byte
references).

<details><summary>Hint</summary>

L03 §3–4 and §4.3; a `boolean` column is one byte per row; an object per record plus a `Vec3f` for
`home`.
</details>

<details><summary>Solution</summary>

Per row, the columns: `Monster` four `int`s (16 bytes), `Mind` one `int` and six `float`s (28),
`Sight` two `boolean`s (2), `Flash` a `float` and an `int` (8): 54 bytes, $300 \times 54 =
16{,}200$ bytes, $\lceil 16{,}200 / 64 \rceil = 254$ lines, in fifteen sequential streams. The
lookups add the driving store's `entities` (1,200 bytes, 19 lines) and, per store, a sequential
walk of `sparse` and of `entities` (19 lines each, if the skeletons' slot indices are consecutive):
$19 + 4 \times 2 \times 19 = 171$ lines, also sequential. About 425 lines in all, every one
prefetched. As objects: `Monster` is $8 + 4 \times 4 = 24$ bytes; `Mind` is $8 + 4 + 3 \times 4 +
4 = 28$ (header, the `state` reference, three `float`s, the `home` reference), rounded to 32, plus a `Vec3f` of $8 + 12 = 20$, rounded to 24; `Sight` $8 + 2 = 10$,
rounded to 16; `Flash` $8 + 8 = 16$: five objects, 112 bytes per skeleton, 33,600 bytes, 525 lines
if they happen to be adjacent and about $5 \times 300 = 1{,}500$ lines when scattered, two of them
dependent loads (`Mind`, then its `home`).
</details>

**4. The edges of `Movers`.** Derive the dependency edges the processor emits for the test sample's
`Movers` (`turnLeft` writes `Position`; `rise` writes `Position` and reads `Health`; `bleed` writes
`Health`) and check them against the generated `SampleGame` constructor.

<details><summary>Hint</summary>

§6.2's rule; `bleed` does not depend on `turnLeft`. The generated file is under
`modules/components/build/generated/sources/annotationProcessor/java/test/dev/jeddy/components/sample/`
after `./gradlew :components:test`.
</details>

<details><summary>Solution</summary>

$W_{\text{turnLeft}} = \{\text{Position}\}$; $R_{\text{rise}} = \{\text{Health}\}$,
$W_{\text{rise}} = \{\text{Position}\}$; $W_{\text{bleed}} = \{\text{Health}\}$. `rise` after
`turnLeft`: $W_{\text{turnLeft}} \cap (R_{\text{rise}} \cup W_{\text{rise}}) = \{\text{Position}\}$,
an edge. `bleed` after `turnLeft`: $\{\text{Position}\} \cap \{\text{Health}\} = \emptyset$ and
$R_{\text{turnLeft}} = \emptyset$: no edge. `bleed` after `rise`: $W_{\text{rise}} \cap
W_{\text{bleed}} = \emptyset$ but $R_{\text{rise}} \cap W_{\text{bleed}} = \{\text{Health}\}$: an edge.
The constructor:

```java
        Movers_turnLeft_node = fixedUpdateGroup0.add(Movers_turnLeft_kernel, 0, 2);
        Movers_rise_node = fixedUpdateGroup0.add(Movers_rise_kernel, 0, 2, Movers_turnLeft_node);
        Movers_bleed_node = fixedUpdateGroup0.add(Movers_bleed_kernel, 0, 2, Movers_rise_node);
```

`bleed` waits for `rise`, which waits for `turnLeft`, so in practice the three run one after
another; the graph would let `bleed` overlap `turnLeft` if `rise` were not between them.
</details>

**5. A slider in the inspector.** Add `@Range(min = 0, max = 5)` to `Mind.cooldown`, rebuild, and
find the slider in the inspector; set a `Mind` on the chest through the editor, save the level, and
read the `.jlevel`'s rows with `LevelData.toJson`. Then misspell a column in the file and cook.

<details><summary>Hint</summary>

The schema's `"min"`/`"max"` (§17) drive the slider; `LevelData.toJson()` prints the whole level,
component rows included; the cooker's warning (§20) names the object and the column.
</details>

<details><summary>Solution</summary>

After the rebuild `components.json` has `{"name": "cooldown", "type": "float", "default": 0,
"min": 0, "max": 5}` and the inspector shows a 0–5 slider for `cooldown` on every `Mind` row. With
the demo's editor open (chapter [14](14-editor.md), on the editor's hosts), select the chest, choose
*Mind* under *Add component...*, drag `cooldown` to 2.5 and save; the live hook (§20) despawns the
chest's entity and loads its rows again, so `game.mind().has(levelLoader.rootOf(CryptLevel.PLACEMENT_CHEST))`
is now true. `LevelData.read(...).toJson()` on the saved file lists, under `"components"`, the row
`{"object": 119, "component": "Mind", "values": {"cooldown": 2.5}}` (the writer prints each value
by its type: numbers bare, enum and asset text quoted). Renaming the column to `cooldwn` in a copy of the file and cooking it
with the schema prints `object 119: Mind has no column cooldwn`, and the runtime would skip it with
the same words from `Levels.applyRow`.
</details>

**6. A renamed column.** Give the sample game a snapshot test that renames a column in the record
(`Health.current` → `hp`), writes with the *old* build's bytes (keep them in the test as an array)
and reads with the new: which values survive? (The rename does not compile on its own: `Regen`,
`Reaper`, `Movers.rise` and `Lifecycle` call `h.current()`/`setCurrent`, and the existing tests use
`current()` too; rename those calls with it.)

<details><summary>Hint</summary>

§10.5: `read` matches columns by name; §10.4 has the 70 bytes of a two-row `Health` snapshot, which
are exactly the old build's bytes for that store.
</details>

<details><summary>Solution</summary>

With `record Health(@Default("10") int hp, @Default("10") int max)` the generated store's columns
are `hp` and `max`. The test keeps the bytes of §10.4 and reads them into a registry whose pool has
the same two entities (handles `0x400000` and `0x400001`: create them first in a fresh pool):

```java
    @Test
    void aRenamedColumnKeepsItsDefault() {
        byte[] old = {
            0x06, 0x00, 'H', 'e', 'a', 'l', 't', 'h', 0x02, 0x00, 0x00, 0x00,
            0x07, 0x00, 'c', 'u', 'r', 'r', 'e', 'n', 't', 0x00, 0x00, 0x00, 0x00,
            0x03, 0x00, 'm', 'a', 'x', 0x00, 0x00, 0x00, 0x00,
            0x02, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x03, 0x00, 0x00, 0x00, 0x0a, 0x00, 0x00, 0x00,
            0x05, 0x00, 0x00, 0x00, 0x0a, 0x00, 0x00, 0x00
        };
        EntityPool pool = new EntityPool(64);
        SampleGame g = new SampleGame(pool, null, new Counts());
        int a = pool.create();
        int b = pool.create();
        g.health().read(new ByteReader(old));
        assertEquals(new Health(10, 5), g.health().get(a), "hp at its default, max from the file");
        assertEquals(new Health(10, 10), g.health().get(b));
    }
```

`current` is looked up with `columnIndex` and gets −1 (unknown: eight bytes read and dropped);
`max` matches by name and type and survives; `hp` is never mentioned and keeps the `@Default` that
`add`'s `resetRow` gave it. The old values 3 and 10 of `current` are lost: a renamed field is a new
field to a snapshot, which is the price of matching by name and the reason to rename rarely.
(`Store.read` logs nothing for an unknown column; only dropped rows are logged.)
</details>

**7. The open decision, tried.** Change `RegistryGen.match` so that a multi-store loop uses the
loop index for whichever store drives, as L03 exercise 8 proposes, and measure it on a two-store
behaviour.

<details><summary>Hint</summary>

The generator knows the parameter order but not the driver; emit a comparison of references.
`ComponentsBench` has one store; add a second record and a behaviour over both, with the same
`regen` body.
</details>

<details><summary>Solution</summary>

In `match`, when `driverRow != null` and `required.size() > 1`, emit for each non-optional view
parameter

```java
int rK = drive == <storeK> ? i : <storeK>.indexOf(e);
```

instead of the plain `indexOf` (the `drive` local exists in `loop` and `parallelRange`; `one` has
no driver and keeps the lookups). The branch compares two references and goes the same way for
every row of a loop, so the branch predictor learns it after the first row; the win is `indexOf`'s
two dependent loads and compare for the driving store, the chain L03 §14.2 measured. To measure,
add to `bench/src/main/java/dev/jeddy/bench/components/` a `record Armor(int value)` and a
behaviour `regen2(HealthMut h, ArmorView a)` with `regen`'s body, give every entity both, and add
a `generatedBehaviourTwoStores` benchmark that calls a second stage. The single-store fix saved
15.5 µs per 10,000 rows (22 µs to 6.5, about 1.5 ns per row); for a two-store loop expect a saving
of the same order per row for the driving store's lookup, the other store's lookup remaining (a
prediction; the measurement decides, and the decision is the owner's).
</details>

**8. Why by name.** The processor recognises `MindMut` by its name and the records it collected. Give
a case where a `DECLARED` type called `MindMut` is *not* the generated view, and say what the
processor does with it; then explain why it cannot simply ask javac for the type of every
parameter.

<details><summary>Hint</summary>

§4.2, §14.5, and the check `q.equals(mut ? viewOf.qualifiedMut() : viewOf.qualifiedView())`.
</details>

<details><summary>Solution</summary>

A game's own class `com.example.tools.MindMut` imported into a behaviour class: the parameter's
type is `DECLARED` (the class exists), its simple name ends in `Mut` with a collected `Mind` before
it, so `viewOf` is first set; then the qualified-name check fails (`com.example.tools.MindMut` is not
`dev.jeddy.samples.iso.MindMut`), `viewOf` is cleared, and the parameter falls through to the
`DECLARED` branch: a **singleton** of that type, which the registry will take in its constructor.
The processor cannot ask javac for the type in the common case because the generated view does
not exist in round 1: the type is an *error type* with no element, no qualified name and no
package. Matching by name is the only information there is, and the qualified-name check covers
the round where the type does exist.
</details>

**9. False sharing of the per-thread views.** The views of a parallel behaviour are allocated back
to back, one per thread slot (§8). Make each thread's views land on their own cache lines and
measure the behaviours row with and without the change.

<details><summary>Hint</summary>

Chapter [12](12-java-performance.md#73-cache-coherence-and-false-sharing), on false sharing; a
view is 16 bytes, so four share a line. The simplest experiment needs no padding class: allocate
something of 48 or more bytes between consecutive views in the constructor loop, or allocate each
thread's three views together and the next thread's after a gap.
</details>

<details><summary>Solution</summary>

In `RegistryGen`'s constructor loop, change the generated

```java
        for (int t = 0; t < slots; t++) {
            SkeletonAi_see_v0[t] = new dev.jeddy.samples.iso.MonsterView();
        }
```

so that one iteration allocates *all* of a thread's views and then a throw-away `new long[6]`
(a 64-byte array object: 16 bytes of header and length, 48 of payload) before the next thread's,
or pad `View` itself with six `long` fields (L03 §3's arithmetic: 8 + 4 + 4 + 48 = 64 bytes). Run
`./gradlew :iso-demo:run --args="--headless --perf 20 --warmup 6"` before and after and compare the
*behaviours* row. On the 2-core container only one worker and the game thread run `see`, so the
difference there is at most one line ping-ponging per row; on an 8-core desktop with six workers
there are more pairs of threads sharing lines, and the effect, if there is one, is larger. Whether
it is measurable is the point of the exercise: the chapter predicts it from the layout and does not
know.
</details>

**10. An entity that runs twice.** Using the test sample, construct a tick in which one entity's
behaviour runs twice because another entity was despawned during the loop (§5.3), and show it with
a counter.

<details><summary>Hint</summary>

A behaviour that despawns a *specific other* entity, with a lower row than its own; count calls
per entity in a singleton array indexed by `Handles.index(entity)`.
</details>

<details><summary>Solution</summary>

Three entities `A`, `B`, `C` with `Health`, added in that order (rows 0, 1, 2). A behaviour

```java
    /** A singleton: who despawns whom, and how often each entity ran. */
    public final class Grudges {
        public int killer;
        public int victim;
        public final int[] calls = new int[64];
    }

    @OnFixedUpdate(stage = 1)
    public static void grudge(HealthView h, int entity, Grudges g, SampleGame game) {
        g.calls[Handles.index(entity)]++;
        if (entity == g.killer) {
            game.despawn(g.victim);
        }
    }
```

with `killer = C`'s handle and `victim = A`'s (the registry's constructor gains a `Grudges`
parameter). The loop walks rows 2, 1, 0: at row 2, `C` despawns
`A` (row 0); the last row, `C`'s, moves into row 0 and `size` becomes 2; the loop continues at row
1 (`B`), then at row 0, which is now `C` again. `calls[index(C)]` is 2, `calls[index(B)]` 1,
`calls[index(A)]` 0. Had `C` despawned `B` (row 1) the same thing happens. Had `A` (row 0) despawned
`C` (row 2, already visited), nothing runs twice: the removed row was the last one and nothing
moves. Gameplay code that cannot tolerate the double run moves the removal to a later stage, where
the victim is the visited entity.
</details>

**11. Ids by hand.** With `CryptIds.SKELETON = 3`: what is `EntityIds.id(3, 7)` in hex and decimal,
and what do `kind` and `value` give back? What does `EntityIds.kind(-1L)` return, and why is that
never a valid kind? What is `EntityIds.HANDLES.idOf(h)` for a handle with generation 600, and why
does the mask matter?

<details><summary>Hint</summary>

§11.2–11.3; L00 §5.3 (all ones is −1), §5.4 (sign extension), §6.3 (`>>>`).
</details>

<details><summary>Solution</summary>

$3 \cdot 2^{56} + 7$ = `0x0300_0000_0000_0007` = 216,172,782,113,783,815; `kind` is `0x03` = 3,
`value` 7. `kind(-1L)`: −1 is 64 ones, `>>> 56` leaves the top eight, 255; `id` refuses a kind
above 255 and reserves 0, so every id has a kind in 1..255, and −1 can only come from a corrupt
file. A handle with generation 600 is `600 << 22 | index`; 600 is `0b10_0101_1000`, whose top bit
(bit 9) lands on bit 31, so the `int` is negative. `entity & 0xFFFFFFFFL` widens it to a `long`
*then* keeps the low 32 bits, giving a positive id with kind 0 in the top byte, which is what the
identity mapping wants; without the mask the widening would sign-extend into `0xFFFFFFFF_xxxxxxxx`,
a kind of 255, and `read` would still decode it with `(int) id`, but any code that read the kind
would be misled.
</details>

**12. Signals or calls?** `Combat` could call `SkeletonAi.flinch(...)` directly instead of raising
a signal through the registry. List what it would need to do that, and what the signal saves.

<details><summary>Hint</summary>

§7, §16.8, D108; what a behaviour's parameters are and who positions them.
</details>

<details><summary>Solution</summary>

A direct call needs the three views positioned at the skeleton's rows in `Monster`, `Mind` and
`Flash`: three `indexOf` lookups, three `at` calls on view objects `Combat` would have to own, and
the two singletons. That is exactly `SkeletonAi_flinch_one` (§16.8), which the registry generates
and `signal` dispatches to by kind. The signal keeps that code in one place, keeps `Combat`
ignorant of which behaviours react to damage (a second `@OnEvent(HURT)` behaviour needs no change
in `Combat`), and keeps `components` free of `rules` (D108): the event ring stays in `rules`, the
game drains it, and the only thing crossing the boundary is an `int` kind and an entity.
</details>

**13. The stages, out of order.** The demo calls stage 0, then 2, then 1 (§19.5). What would change
if `DemoGame.fixedUpdate` called `game.fixedUpdate(dt)` once instead, at the place of the stage-0
call?

<details><summary>Hint</summary>

§7 and the tick's order; what `see` reads and what `fly` needs.
</details>

<details><summary>Solution</summary>

`fixedUpdate` runs stages 0, 1, 2 in that order, all before `party.tick`, the crowd, the collision
update and the fog. `see` would read the fog as the *previous* tick left it and the crowd positions
of the previous tick, so visibility lags one tick; `show` would apply that stale answer; `fly`
would explode against the collision world before this tick's movement, catching bodies where they
were. The game would run, and be subtly wrong in ways no test of the registry can see: stages exist
so that the game, which knows its order, places each group where it belongs.
</details>

## Glossary

- **Aggregating processor:** Gradle's term for a processor that reads many sources to write one
  output and must therefore be re-run with all of them (§4.3, §18.2).
- **Annotation processor:** a plug-in that javac runs during compilation, given the annotated
  declarations, which may generate sources and resources (§4.1).
- **Behaviour:** a `public static void` method in a `@Behaviour` class with a phase annotation;
  its view parameters are its query (§2).
- **Binding:** the game's resolver from level object ids and asset paths to handles, which the
  level loader takes (§12.2).
- **Cold form, hot form:** the record a tool passes and gets back; the columns a loop reads (§2).
- **Column:** one primitive array of a store, one per scalar: a vector is three columns, an enum
  one `int` column (§10.3).
- **Component:** a public top-level `record` marked `@Component` (§2, §9.1).
- **Dependency edge:** the order between two parallel behaviours derived from their reads and
  writes (§6.2).
- **Driving store:** the smallest required store, whose rows a loop walks (§5.2).
- **Error type:** javac's placeholder for a name it cannot resolve during processing, reported only
  if it stays unresolved (§4.2).
- **Filer:** the processor's way to create source files and resources (§4.1).
- **Generic column API:** `columnIndex`, `getInt`, `setFloat`, `enumOrdinal` and the rest: columns
  by name, for the cold paths (§10.3).
- **Hazard (read-after-write, write-after-write, write-after-read):** the three ways two behaviours
  can conflict on a component (§6.1).
- **Phase:** when a behaviour runs: spawn, despawn, fixed update, update, trigger, turn, event
  (§7).
- **Query:** the set of required, optional and excluded components a behaviour's signature declares
  (§5.1).
- **Registry:** the generated class per game that owns the stores, the singletons and the entry
  points (§2, §16).
- **Retention:** how long an annotation survives; `SOURCE` means the compiler alone sees it (§4.3).
- **Round:** one pass of javac over the current root elements with the processors; generated
  files start the next (§4.1).
- **Run (of behaviours):** a maximal sequence of consecutive parallel, or consecutive sequential,
  behaviours of a stage; each parallel run is a job graph (§16.3).
- **Schema:** `META-INF/jeddy/components.json`, the processor's description of the components and
  behaviours for the editor and the cooker (§17).
- **Signal:** an event the game raises on the registry, `signal(kind, entity, a, b)`, dispatched to
  the `@OnEvent(kind)` behaviours (§7).
- **Singleton:** any non-view, non-scalar parameter of a behaviour; an object the registry takes
  in its constructor (§2, §13.5).
- **Snapshot:** a store's self-describing serialization: name, columns by name and type, entity ids,
  columns (§10.4).
- **Stable id:** the 64-bit name of an entity across runs, a kind and a value, defined by the game
  through `EntityIds` (§11).
- **Stage:** a numbered group of fixed-update or update behaviours the game runs at a point of its
  own choosing (§7).
- **Thread slot:** the index a job thread passes to a kernel, used to pick that thread's views
  (§6.4).
- **View, Mut:** the generated read-only and writable flyweights over one row (§2, §15.5–15.6).

*Code this chapter builds or quotes: `components.Component`, `Components`, `Behaviour`, `Default`,
`Doc`, `Range`, `Angle`, `Color`, `EntityRef`, `AssetRef`, `Optional`, `Without`, `Parallel`,
`ThreadSafe`, `OnSpawn`, `OnDespawn`, `OnFixedUpdate`, `OnUpdate`, `OnTrigger`, `OnTurn`,
`OnEvent`; `components.Store`, `View`, `EntityIds`, `Levels`; the processor's
`ComponentProcessor`, `Model`, `StoreGen`, `RegistryGen`, `SchemaGen` and its two `META-INF`
files; the generated `MindStore`, `MindView`, `MindMut`, `CryptGame`, `SampleGame` and
`CryptLevel`, and `META-INF/jeddy/components.json`; the demo's `Monster`, `Mind`, `Sight`, `Flash`,
`Fireball`, `SkeletonAi`, `Fireballs`, `Signals`, `Enemies`, `Spells`, `CryptIds`, `CryptSave`,
`DemoGame`; the build's `dev.jeddy.game.gradle.kts`, `dev.jeddy.assets.gradle.kts`, `Assets.kt`,
the demo's `build.gradle.kts`, the cooker's `LevelImporter` and `LevelBindings`; the tests
`GeneratedGameTest` with its sample package and `ComponentProcessorTest`; `bench.ComponentsBench`.
Decisions: D5, D15, D105, D106, D107, D108, D109, D110, D111, D113, D114 in
[DESIGN §17](../DESIGN.md); the design in DESIGN §22. Lessons: [L00](lessons/00-foundations.md)
(bits, packing, sign extension, memory, caches, threads), [L03](lessons/03-data-layout.md) (the
data layout, the store, the views, the loop, the JIT, the benchmark, the parallel rule),
[L04](lessons/04-binary-formats.md) (`ByteWriter`, `ByteReader`). Chapters: [01](01-architecture.md)
(modules, the job system and its graphs, the iso demo's tick), [12](12-java-performance.md) (the
JIT, kernels stored once, false sharing, the allocation assertion, the perf capture),
[14](14-editor.md) (the level format, the inspector), [15](15-rules.md) (the rules' events),
[16](16-saves.md) (the save around the snapshots).*

[← 15 Rules](15-rules.md) · [Index](README.md)
