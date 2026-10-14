# 15 — Rules: a d20 core, content as data, encounters in two tempos

*A party CRPG needs a rulebook: characters with scores, classes and feats; attack rolls against an
armor class made of typed bonuses; damage dice, critical hits and saving throws; conditions that
last a number of rounds; spells with slots; and an encounter structure that works both turn by turn
and in real time with pause, the two ways Pathfinder: Wrath of the Righteous lets you play the
same fight. This chapter builds that rulebook, from empty files, as the `rules` module: a stat
block as columns of `int`s, typed modifiers that stack by the tabletop rule, conditions with
durations, an engine that resolves attacks, spells, damage and death and writes every outcome into
a ring of numbers, an encounter with initiative and an action budget, and the two drivers that run
the same encounter on a clock or on the player's "End Turn". It then builds the content pipeline
around it (JSON definitions whose behaviour is a list of operations, cooked into a binary table
file the editor can also edit), wires the iso demo's party and skeletons to it, and ends with the
tests that assert every number by hand and the performance capture that found three allocations.*

**Before you start.** You need [Lesson 0](lessons/00-foundations.md): the notation, floor and
`mod` (§2.2–2.5), the primitive types and overflow (§4), masks, shifts and packing (§6), the heap
and the zero-allocation rule (§8.2), threads (§9.4) and ticks (§11). Read
[Lesson 2](lessons/02-dice-and-probability.md) first and whole: it teaches probability from
counting, builds the random generator `Rng`, the packed dice `Dice`, and the two methods of this
module that roll for an attack, `Rules.check` and `Rules.attack`, and derives every number about
hit chances, criticals, expected damage and saving throws that this chapter uses. §4 below
summarizes it and cites its sections; nothing from it is rebuilt here. From
[Lesson 3](lessons/03-data-layout.md) you need the idea of a struct-of-arrays store with `int`
handles (§4–§7); the stores here are hand-written in that shape. Chapter
[01](01-architecture.md), on threads and the fixed-step clock, tells you why everything in this
chapter runs on the game thread at 30 ticks a second; §10 derives the round arithmetic on top of
it. Chapter [11](11-physics-navigation-crowds.md), on the crowd and turn-based movement, owns the
distances this chapter only consumes; chapter [14](14-editor.md), on the editor's panels, hosts the
Rules panel; chapter [16](16-saves.md), on save games, walks through the save code §28 only
summarizes; chapter [22](22-components.md), on components and behaviours, explains the
`SkeletonAi` behaviour that calls into §27. Nothing in this chapter touches the GPU: the rules are
pure game-thread Java, and what the player sees of them is drawn by the UI of chapter
[13](13-ui.md).

**What you will build**

| Piece | File | What it does |
|---|---|---|
| The vocabularies | [`rules.Stat`](../../modules/rules/src/main/java/dev/jeddy/rules/Stat.java), [`BonusType`](../../modules/rules/src/main/java/dev/jeddy/rules/BonusType.java) | the statistics a modifier can target and the bonus types, as `int` constants that a static check keeps aligned with the content schema |
| Typed modifiers | [`rules.Modifiers`](../../modules/rules/src/main/java/dev/jeddy/rules/Modifiers.java) | a store of (creature, stat, type, value, source, rounds) with a free list; the stacking rule in `total`; durations that tick |
| Condition instances | [`rules.Conditions`](../../modules/rules/src/main/java/dev/jeddy/rules/Conditions.java) | which creature has which condition for how many rounds |
| The stat block | [`rules.Creatures`](../../modules/rules/src/main/java/dev/jeddy/rules/Creatures.java) | every creature as parallel arrays, and `stat(creature, Stat)`, which resolves any statistic from the bases and the stacked modifiers |
| Typed content | [`rules.Content`](../../modules/rules/src/main/java/dev/jeddy/rules/Content.java) | reads of the cooked tables by `(kind, row, field)`, the field indices resolved by name once |
| The log without strings | [`rules.Events`](../../modules/rules/src/main/java/dev/jeddy/rules/Events.java) | a ring of eight-`int` records: what the rules decided, for the UI and the tests |
| The engine | [`rules.Rules`](../../modules/rules/src/main/java/dev/jeddy/rules/Rules.java) | builds creatures from content, interprets effect operations, resolves attacks, spells, saves, damage, healing, dying and death, ticks durations, writes and reads itself |
| The action economy | [`rules.ActionBudget`](../../modules/rules/src/main/java/dev/jeddy/rules/ActionBudget.java) | standard, move, swift and a 5-foot step as bits per creature |
| The fight | [`rules.Encounter`](../../modules/rules/src/main/java/dev/jeddy/rules/Encounter.java) | participants in initiative order, rounds and turns, joining and leaving, attacks of opportunity, who won |
| The two tempos | [`rules.TurnBasedDriver`](../../modules/rules/src/main/java/dev/jeddy/rules/TurnBasedDriver.java), [`RealTimeDriver`](../../modules/rules/src/main/java/dev/jeddy/rules/RealTimeDriver.java), [`TurnListener`](../../modules/rules/src/main/java/dev/jeddy/rules/TurnListener.java) | when the next turn starts: on "End Turn", or when a six-second clock says so |
| The content pipeline | [`asset.format.RulesSchema`](../../modules/asset/src/main/java/dev/jeddy/asset/format/RulesSchema.java), [`RulesData`](../../modules/asset/src/main/java/dev/jeddy/asset/format/RulesData.java), [`cooker.rules.RulesImporter`](../../tools/cooker/src/main/java/dev/jeddy/cooker/rules/RulesImporter.java) | one description of the kinds and their fields; the `.jrules` tables in memory and on disk; JSON validated and cooked |
| The demo | [`iso.Combat`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/Combat.java), [`CombatLog`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/CombatLog.java), parts of [`DemoGame`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/DemoGame.java), [`Party`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/Party.java), [`Spells`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/Spells.java), [`SkeletonAi`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/SkeletonAi.java) | the party and the skeletons as creatures; strikes, fireballs, orders, the skeletons' AI turn, the log |
| The tests | [`CreaturesTest`](../../modules/rules/src/test/java/dev/jeddy/rules/CreaturesTest.java), [`ModifiersTest`](../../modules/rules/src/test/java/dev/jeddy/rules/ModifiersTest.java), [`RulesTest`](../../modules/rules/src/test/java/dev/jeddy/rules/RulesTest.java), [`EncounterTest`](../../modules/rules/src/test/java/dev/jeddy/rules/EncounterTest.java), [`SavesTest`](../../modules/rules/src/test/java/dev/jeddy/rules/SavesTest.java), [`RulesImporterTest`](../../tools/cooker/src/test/java/dev/jeddy/cooker/rules/RulesImporterTest.java) | every derived number by hand, exact rolls through an oracle generator, one fight through both drivers, the file round trip, allocation at zero |

**Contents**

- Part A — The ideas
  1. [One fight, and the number that explains it](#1-one-fight-and-the-number-that-explains-it)
  2. [The vocabulary of a d20 game](#2-the-vocabulary-of-a-d20-game)
  3. [Ability scores and modifiers](#3-ability-scores-and-modifiers)
  4. [Checks, attacks, criticals and saves: what lesson 2 already proved](#4-checks-attacks-criticals-and-saves-what-lesson-2-already-proved)
  5. [Iterative and extra attacks](#5-iterative-and-extra-attacks)
  6. [Progressions, hit points and the derived statistics](#6-progressions-hit-points-and-the-derived-statistics)
  7. [Hit points, dying and death](#7-hit-points-dying-and-death)
  8. [Conditions and durations](#8-conditions-and-durations)
  9. [Rounds, initiative and the action economy](#9-rounds-initiative-and-the-action-economy)
  10. [Two tempos, one encounter: the time arithmetic](#10-two-tempos-one-encounter-the-time-arithmetic)
  11. [Content as data: definitions, operations and the cooked file](#11-content-as-data-definitions-operations-and-the-cooked-file)
- Part B — Building it
  12. [Step 1: `Stat` and `BonusType`](#12-step-1-stat-and-bonustype)
  13. [Step 2: `Modifiers`](#13-step-2-modifiers)
  14. [Step 3: `Conditions`](#14-step-3-conditions)
  15. [Step 4: `Creatures`](#15-step-4-creatures)
  16. [Step 5: `Content`](#16-step-5-content)
  17. [Step 6: `Events`](#17-step-6-events)
  18. [Step 7: `Rules`, building creatures from content](#18-step-7-rules-building-creatures-from-content)
  19. [Step 8: `Rules`, effects and conditions](#19-step-8-rules-effects-and-conditions)
  20. [Step 9: `Rules`, damage, healing and death](#20-step-9-rules-damage-healing-and-death)
  21. [Step 10: `Rules`, checks, saves, attacks and spells](#21-step-10-rules-checks-saves-attacks-and-spells)
  22. [Step 11: `Rules`, turns and attacks of opportunity](#22-step-11-rules-turns-and-attacks-of-opportunity)
  23. [Step 12: `ActionBudget`](#23-step-12-actionbudget)
  24. [Step 13: `Encounter`](#24-step-13-encounter)
  25. [Step 14: the two drivers](#25-step-14-the-two-drivers)
  26. [Step 15: the content pipeline](#26-step-15-the-content-pipeline)
- Part C — Using it and proving it
  27. [The demo: `Combat`](#27-the-demo-combat)
  28. [Saving the rules state](#28-saving-the-rules-state)
  29. [The tests, walked through](#29-the-tests-walked-through)
  30. [Performance: zero allocation, and what the capture caught](#30-performance-zero-allocation-and-what-the-capture-caught)
  31. [Why it is built this way](#31-why-it-is-built-this-way)
  32. [Pitfalls met on the way](#32-pitfalls-met-on-the-way)
  33. [Things to run](#33-things-to-run)
- [Exercises](#exercises)
- [Glossary](#glossary)

---

# Part A — The ideas

## 1. One fight, and the number that explains it

Before milestone M15 the demo's fights were a hit-point counter in `Enemies`: a swing landed when
the animation said so and took a fixed amount. M15 replaced that with a rulebook. The six
adventurers and the crypt's skeletons are now **creatures** built from content files; every swing
is an attack roll; a fireball is a spell with a Reflex save; **T** switches to turn-based play
with an initiative order and an action budget; and the combat log at the bottom right of the
screen reads the engine's event ring. A run of the demo prints lines like these (abridged: they
come from different moments of a fight, and the demo's own "is destroyed" line also says how many
skeletons are left, "Skeletal adept is destroyed (N left)"):

```
Combat begins
Skeletal warrior hits Brunhild (14 vs AC 14)
  Brunhild takes 3 slashing (52 left)
Skeletal warrior misses Brunhild (12 vs AC 15)
Ewan crits Skeletal adept (26 vs AC 14)
  Skeletal adept takes 14 piercing (0 left)
Skeletal adept is destroyed
Cassius is Dying
Cassius stabilizes
```

Read the second and fourth lines again. Brunhild's armor class is 14 on the first and 15 on the
second. Nothing about her changed between them except that her first turn of the fight had come:
until then she was **flat-footed**, a condition that takes away the Dexterity bonus to armor class,
and her Dexterity 12 is worth +1. That one number is most of this chapter: a condition is a row of
content with a flag; the flag is read when a statistic is resolved; the resolution adds a base, a
capped ability bonus and a stack of typed modifiers; and the turn that removed the condition came
from an encounter that a clock advanced. By the end of Part B you will be able to follow the 14
and the 15 through every line of code they pass.

The other lines preview the rest. "52 left" is 55 − 3: Brunhild's hit points come from a hit die,
her level and her Constitution (§6.2). Ewan's critical is a natural roll in his shortsword's
threat range confirmed by a second roll (§4), and 14 piercing is the skeletal adept's whole 14
hit points, so it is destroyed: undead "die at zero" (§7). Cassius, a wizard with 22 hit points,
fell below zero, was dying, and stabilized on a natural 20 at the end of one of his turns (§7,
§22).

## 2. The vocabulary of a d20 game

A **d20 system** is a set of tabletop rules in which almost every uncertain action is decided by
one twenty-sided die. The engine's rules are shaped like Pathfinder 1st edition with original
names and content (the mechanics of a game are not protected by copyright; its text and names are,
and are not reproduced: D96). The words you need, each defined once here and used throughout:

- A **creature** is anything that can fight: a party member or a monster. It has six **ability
  scores** (Strength, Dexterity, Constitution, Intelligence, Wisdom, Charisma), a **level**, **hit
  points**, an **armor class** (AC), an **attack bonus**, three **saving throws** (Fortitude,
  Reflex, Will), a **size**, a **speed** in feet, equipment, feats, spells and a **team**.
- A **check** is $d20 + b \ge DC$: roll the die, add a bonus $b$, succeed if the total reaches a
  **difficulty class** $DC$. An **attack roll** is a check against the target's AC. A **saving
  throw** is a check the target makes against a spell's DC.
- A **modifier** is a typed number added to a statistic: "+2 armor bonus to AC", "−2 penalty to
  attack rolls". Modifiers come from equipment, feats, class features, spells and conditions, and
  the **stacking rule** (§6.6) decides which of them count.
- A **condition** is a state a creature is in for a while (shaken, stunned, flat-footed, dying)
  that carries modifiers and, for the few the engine must know about, flags.
- An **effect** is what a feat, spell, item, class feature or condition does: a list of
  **operations** such as "modify AC by +1 dodge" or "deal 5d6 fire damage" (§11).
- A **round** is six seconds of fight time in which every creature gets one **turn**; the order of
  turns is the **initiative** order; a turn grants an **action budget** (§9).
- An **encounter** is one fight: who is in it, the order, the round, whose turn it is.

Everything the rules decide is deterministic for a seed: all randomness comes from one `Rng` per
engine (L02 §11), so a fight replays exactly and the tests assert exact numbers.

## 3. Ability scores and modifiers

An ability score $s$ is a whole number, 3 to 18 for most mortals, with 10 as the average. What a
creature adds to its rolls is not the score but its **modifier**:

$$m(s) = \left\lfloor \frac{s - 10}{2} \right\rfloor.$$

Derivation of the shape: the designers wanted 10 to be worth nothing, every two points above it to
be worth +1, and the same below. "$(s - 10)/2$" does the first two; the floor (L00 §2.5) makes the
odd scores round *down* on both sides, so that 11 is still 0 and 9 is already −1: the step from
an even score to the next odd one buys nothing, and the step from odd to even buys a point.
Tabulated:

```
s:     1   2   3   4   5   6   7   8   9  10  11  12  13  14  15  16  17  18  19  20
m(s): -5  -4  -4  -3  -3  -2  -2  -1  -1   0   0   1   1   2   2   3   3   4   4   5
```

The table matters for the code because Java's `/` is not the floor: it truncates towards zero
(L00 §2.4), so `(9 - 10) / 2` is `0`, not −1, and `(7 - 10) / 2` is `-1`, not −2. The engine
therefore writes the formula with `Math.floorDiv`:

```java
// Stat.java
/** Returns the ability modifier of a score: {@code floor((score - 10) / 2)}. */
public static int modifier(int score) {
    return Math.floorDiv(score - 10, 2);
}
```

Worked: Aldric's Strength 16 gives $\lfloor 6/2 \rfloor = 3$; Dara's Strength 8 gives
$\lfloor -2/2 \rfloor = -1$; Cassius's Intelligence 20 gives +5. A consequence worth knowing when
you design content: raising a score by one changes the modifier only when the score becomes even
(exercise 6 meets this). Every number a creature rolls with is a sum of such modifiers and flat
bonuses, which is why the ability modifier is the first function of the module.

## 4. Checks, attacks, criticals and saves: what lesson 2 already proved

[Lesson 2](lessons/02-dice-and-probability.md) builds, from counting outcomes, everything this
chapter needs about dice, and the code that rolls them. In one paragraph, with the sections to
reread:

- **The die and its sums.** A fair d$S$ averages $(S + 1)/2$ and $N$d$S$+$B$ averages
  $N(S+1)/2 + B$ (L02 §3): a longsword's `1d8` averages 4.5, Aldric's `1d8+4` 8.5, a fireball's
  `5d6` 17.5. Sums of dice are bell-shaped (L02 §5), which is why a critical *rolls the dice
  again* rather than doubling one roll: same mean, less spread (L02 §5.4), and why `Dice.times`
  multiplies the count and the bonus, never the total.
- **Dice in an `int`.** `math.Dice` packs count, sides and a signed bonus into one `int` (count in
  bits 24–31, sides in 16–23, bonus in 0–15) so a content table's damage column is an `int[]` and
  rolling allocates nothing (L02 §16, L00 §6.4). `Dice.parse` reads `"2d6+3"` for the cooker.
- **The generator.** `math.Rng` is xoshiro256** seeded through SplitMix64 with Lemire's
  rejection method for `nextInt(bound)`, so every face is exactly as likely as the others; its
  state is four `long`s a save can store (L02 §12–§15).
- **The check.** `Rules.check(modifier, dc)` rolls a d20, adds the modifier, and succeeds on a
  natural 20, fails on a natural 1, and otherwise compares the total with the DC, leaving
  `lastNatural` and `lastTotal` in fields so the caller allocates nothing to read them (L02 §17).
  With $n = DC - b$ the face needed, the probability of success is $(21 - n)/20$ clipped to
  $[1/20, 19/20]$ (L02 §6.2): **each point of bonus or DC is worth exactly 5 percentage points**.
  Aldric attacks at +10 and a skeletal warrior has AC 17, so $n = 7$ and he hits 70 % of the time;
  against AC 20 it is 55 %, against the same warrior flat-footed (AC 15) 80 %. The clipping keeps
  every check fallible: a +100 bonus still misses on a natural 1, which
  `RulesTest.naturalOneMissesAndNaturalTwentyHits` asserts (`r.attack(dara, skeleton, 0, 100, 0)`
  with a seed whose first d20 is 1 returns `MISS`).
- **The attack.** `Rules.attack(attacker, target, index, bonus, flags)` gathers the dice, threat
  range and multiplier from the weapon or the monster's attack row, computes the attack bonus,
  asks for the AC this attack faces, checks, confirms a natural roll in the threat range with a
  second check against the same AC, and rolls the damage `multiplier` times on a confirmed
  critical, at least 1 per hit (L02 §18). The expected damage per swing is
  $E = pD + q_1 p (m - 1) D$, with $p$ the hit chance, $D$ the mean damage of a hit, $q_1$ the
  chance that the first roll both hits and threatens, and $m$ the multiplier (L02 §8.4): for
  Aldric against the warrior, $0.70 \cdot 8.5 + 0.10 \cdot 0.70 \cdot 8.5 = 6.545$. The critical
  adds 0.6 of the 6.5: in d20 games criticals are flavour, the hit chance is the economy. A
  wider threat range of 18–20 ($q_1 = 3/20 = 0.15$) raises the second term to $0.15 \cdot 0.70
  \cdot 1 \cdot 8.5 = 0.89$; a ×3 multiplier on the same 19–20 range raises it to $0.10 \cdot 0.70
  \cdot 2 \cdot 8.5 = 1.19$; neither comes near the first term's 5.95. A
  Monte Carlo of 100,000 `attack` calls (exercise 2) gives 6.55, with 70.1 % hits and 7.0 %
  criticals.
- **Saving throws.** A save is the same `check` with the save bonus and the spell's DC; a spell
  that is *halved* on a successful save expects $D(1 - p_s/2)$ with $p_s$ the chance to save
  (L02 §9). Cassius's fireball has DC 18 (§6.7); Dara (Reflex +12) needs a 6, saves 75 % of the
  time and expects 10.9 of the 17.5, or 10.75 once you account for the code halving with integer
  division; a skeletal warrior (Reflex +4) needs a 14, saves 35 % of the time and expects 14.4
  (14.35).
- **Testing.** Tests choose seeds that produce the natural rolls they need (`seedWhereFirstD20Is`,
  `seedWhereD20sAre`) and replay the engine's rolls on an *oracle* `Rng` with the same seed, so
  they assert not only the arithmetic but how many times and in which order the engine rolls
  (L02 §19). The first seed whose first d20 is 1 is 9, whose first is 20 is 10, whose first two
  are 20 then 1 is 73, 20 then 20 is 765 (computed by running the search).

Everything else in the module is built on `check`, `attack` and the two stores the lesson leaves
to this chapter.

## 5. Iterative and extra attacks

A **full attack** is a full-round action (§9.2) in which a creature swings more than once. Two
sources give extra swings:

- **Iterative attacks** come from a high base attack bonus (BAB): a second attack at −5 from BAB
  6, a third from BAB 11, a fourth from BAB 16. The count is
  $$1 + \left\lfloor \frac{BAB - 1}{5} \right\rfloor \quad (BAB \ge 1),$$
  because the thresholds 6, 11, 16 are the values at which $(BAB - 1)/5$ reaches 1, 2, 3. The
  $i$th iterative ($i$ from 0) attacks at $BAB - 5i$ plus the usual bonuses, so a fighter with
  +6/+1 needs five more on the die for his second swing: its hit chance is 25 percentage points
  lower. Against AC 17 the second attack of a +6/+1 fighter is at +1, needs a 16 and hits
  $(21 - 16)/20 = 25$ % of the time (the first, at +6, needs an 11: 50 %). A full attack is
  worth it only when the target is easy to hit, which is the trade-off the action economy of §9 is
  built around; L02 §8.4 works the +11/+6/+1 case: 14.0 expected damage from a full round against
  7.0 from one attack after moving.
- **Extra attacks** come from effects: the two-weapon fighting feat and the *hasted* condition each
  grant one, as an `extraAttack` operation (§11) that lands in the `EXTRA_ATTACKS` statistic.
  They come at the full bonus, after the iteratives.

In code (§21), `fullAttackCount` is the iterative count plus the extra attacks, capped at six, and
`attackBonus(c, index)` subtracts 5 per index only while the index is still an iterative. Worked
for the demo (computed by running the module against its content): Aldric, BAB 5, has one attack
at +10; Dara, BAB 3 with two-weapon fighting, has two, at +8 and +8; Ewan, BAB 5 with the same
feat, two at +6 and +6. The BAB table:

```
BAB:        0   1   5   6  10  11  16  20
attacks:    1   1   1   2   2   3   4   4
```

(For BAB 0 the Java `(0 - 1) / 5` truncates to 0, and `Math.max(0, …)` guards the formula
anyway.)

**A monster's extra attacks: an open decision.** For a creature built from a monster stat block,
`attack` takes the bonus from the attack row and subtracts `5 * index` for *every* attack after
the first, whether it is an iterative or an extra one; the skeletal stalker, which has two-weapon
fighting and a +4 knife, therefore attacks at +2 and −3 (its feat's −2 applies to both), while Dara
with the same feat attacks at +8/+8. Lesson 2 §18.5 found this while writing the attack code and
recorded the behaviour as it is; whether a monster's extra attacks should come at full bonus is a
rules decision that belongs to the owner, and it is still open. L02 exercise 3 computes what the
−5 costs the stalker (about a third of its damage); the code in §21 marks the line.

## 6. Progressions, hit points and the derived statistics

A **stat block** is what a printed monster entry or a character sheet shows: a dozen numbers that
all derive from a few. The engine stores the few (the **bases**: scores, level, class, base attack
bonus, base saves, base hit points, size, speed, equipment) and derives the rest every time it is
asked, so that any change — a feat learned, a condition applied — is visible at once and nothing
has to be invalidated. This section derives each number the way `Creatures.stat` (§15) computes
it, and checks every one against the demo's characters.

### 6.1 Base attack bonus and base saves by level

A class has a **progression** for its base attack bonus: *full* (the fighter's: one point per
level), *three quarters* (the rogue's) or *half* (the wizard's):

$$BAB_{\text{full}}(L) = L, \qquad BAB_{3/4}(L) = \left\lfloor \frac{3L}{4} \right\rfloor, \qquad BAB_{1/2}(L) = \left\lfloor \frac{L}{2} \right\rfloor.$$

Each of its three saves is *good* or *poor*:

$$\text{save}_{\text{good}}(L) = 2 + \left\lfloor \frac{L}{2} \right\rfloor, \qquad \text{save}_{\text{poor}}(L) = \left\lfloor \frac{L}{3} \right\rfloor.$$

A good save starts at +2 and gains a point every two levels; a poor one starts at 0 and gains one
every three. The floors are integer divisions of positive numbers, so Java's `/` is exact here
(`Content.baseAttack`, `Content.baseSave` in §16). Tabulated for the levels the demo uses:

```
L:       1   2   3   4   5   6   7   8   9  10
full:    1   2   3   4   5   6   7   8   9  10
3/4:     0   1   2   3   3   4   5   6   6   7
half:    0   1   1   2   2   3   3   4   4   5
good:    2   3   3   4   4   5   5   6   6   7
poor:    0   0   1   1   1   2   2   2   3   3
```

The demo's party is level 5: Aldric (warden, full) has BAB 5, Dara (shadow, three quarters) 3,
Cassius (arcanist, half) 2; a good save is 4 and a poor one 1. A monster writes its BAB and base
saves directly in its stat block (a skeletal warrior: BAB 2, Fortitude 0, Reflex 2, Will 3).

### 6.2 Hit points by level

A class has a **hit die** $d$ (d10 for a warden, d12 for a berserker, d6 for an arcanist). At
level 1 a character gets the die's full value; at every later level the engine gives the die's
average rounded up, $\lfloor d/2 \rfloor + 1$ (5.5 → 6 for a d10), instead of rolling, so that two
characters of the same class and level are equal; and the Constitution modifier $m_{\text{Con}}$ is
added per level:

$$HP(L) = d + (L - 1)\left(\left\lfloor \frac{d}{2} \right\rfloor + 1\right) + \max\big(1 - L,\; L \cdot m_{\text{Con}}\big), \qquad \text{at least } L.$$

The $\max(1 - L, \cdot)$ caps a Constitution *penalty*: it may take away at most $L - 1$ points in
total, and the final $\max(L, \cdot)$ guarantees a point per level, the tabletop's "at least 1 hit
point per level". For a positive modifier the cap never applies.

Worked: Aldric, d10, level 5, Constitution 14 (+2): $10 + 4 \cdot 6 + \max(-4, 10) = 10 + 24 +
10 = 44$, plus the Toughness feat's +3 to the maximum (a modifier, §6.6): **47**. Brunhild, d12,
Constitution 16 (+3): $12 + 4 \cdot 7 + 15 = 55$, the 55 behind "52 left" in §1. Cassius, d6,
Constitution 10 (0): $6 + 4 \cdot 4 + 0 = 22$. Dara, d8, Constitution 12 (+1): $8 + 4 \cdot 5 + 5
= 33$. The code is one line in `applyClass` (§18): `die + (level - 1) * (die / 2 + 1) +
Math.max(1 - level, con * level)`.

A monster's hit points are a number in its stat block (16 for the skeletal warrior).

### 6.3 Armor class

$$AC = 10 + \text{size} + \min\big(\max(0, m_{\text{Dex}}),\; \text{maxDex}\big) + \sum \text{modifiers to AC}.$$

- **10** is the base: an unarmored, unskilled, medium creature is hit on a 10 or better by a
  creature with no bonus.
- **Size** is $2 - \text{size index}$ with the indices tiny 0, small 1, medium 2, large 3, huge
  4: a small creature is harder to hit (+1), a large one easier (−1). The same number is added to
  attack rolls (a small creature aims at bigger targets).
- **The Dexterity bonus** is capped by the armor worn (`maxDex`: 3 for a breastplate, 6 for
  leather, 99 for none), and is lost entirely while the creature is flat-footed, stunned,
  unconscious or dying (the conditions whose flags include `loseDexToAc`, §8). A Dexterity *penalty* (a score below 10) is
  not applied here (`Math.max(0, …)`), which is a simplification the engine makes: a clumsy
  creature is no easier to hit than an average one.
- **The modifiers** are the armor's bonus (type *armor*), the shield's (*shield*), a monster's
  natural armor (*natural*), and whatever feats, class features, spells and conditions add,
  stacked by §6.6.

Worked, Dara: $10 + 1\ (\text{small}) + \min(5, 6) + [2\ \text{leather (armor)} + 1\ \text{Dodge
feat (dodge)} + 1\ \text{uncanny dodge (dodge)}] = 20$. Brunhild: $10 + 0 + \min(1, 4) + 4\
\text{chain shirt} = 15$, and 14 flat-footed. A skeletal warrior: $10 + 0 + 2 + [2\ \text{natural}
+ 3\ \text{hauberk (armor, from its effects)}] = 17$.

A **touch attack** (a spell's ray, a touch spell) ignores armor, shield and natural bonuses: the
code subtracts those three typed totals from the AC (§21). The warrior's touch AC is 12, Dara's 18
(she keeps her Dexterity and her dodge bonuses).

### 6.4 The attack and damage bonuses

$$\text{attack} = BAB + \begin{cases} \max(m_{\text{Str}}, m_{\text{Dex}}) & \text{finesse weapon} \\ m_{\text{Str}} & \text{otherwise} \end{cases} + \text{size} + \sum \text{modifiers to attack},$$

$$\text{damage} = \begin{cases} m_{\text{Str}} + \lfloor m_{\text{Str}} / 2 \rfloor & \text{two-handed weapon} \\ m_{\text{Str}} & \text{otherwise} \end{cases} + \sum \text{modifiers to damage}.$$

A **finesse** weapon (a dagger, a shortsword) lets a nimble character use Dexterity on the attack
roll, never on damage. A **two-handed** weapon adds one and a half times the Strength modifier to
damage; the code writes it `str + str / 2`, which for a negative modifier rounds towards zero
(−1 → −1, not −2), a small kindness to weak creatures.

Worked, Aldric: $5 + 3 + 0 + [1\ \text{Weapon focus} + 1\ \text{weapon training}] = 10$; damage
$3 + 1 = 4$, so his longsword does `1d8+4`. Dara: $3 + \max(-1, 5) + 1 + [1\ \text{finesse
feature} - 2\ \text{two-weapon fighting}] = 8$; damage $-1$ (finesse adds nothing), so a dagger does
`1d4-1`, at least 1. Brunhild with her greataxe in two hands: attack $5 + 5 = 10$; damage $5 + 2 +
2\ \text{ferocity} = 9$, so `1d12+9`.

A monster's attack row carries its own bonus with Strength and size already inside, as printed
stat blocks do, and its damage dice carry the Strength bonus (`1d6+2`): §21 explains the branch.

### 6.5 Saves, initiative, speed and the rest

$$\text{Fortitude} = \text{base}_F + m_{\text{Con}} + \sum, \quad \text{Reflex} = \text{base}_R + m_{\text{Dex}} + \sum, \quad \text{Will} = \text{base}_W + m_{\text{Wis}} + \sum,$$
$$\text{initiative} = m_{\text{Dex}} + \sum, \qquad \text{speed} = \max(0, \text{base speed} + \sum), \qquad \text{max HP} = \max(1, \text{base HP} + \sum),$$
$$\text{attacks of opportunity per round} = 1 + \sum, \qquad \text{extra attacks} = \sum.$$

Worked: Aldric's saves are $4 + 2 = 6$, $1 + 1 = 2$, $1 + 1 + 1\ \text{bravery} = 3$; Dara's
Reflex is $4 + 5 + 2\ \text{evasion} + 1\ \text{luck} = 12$; Cassius's initiative is $3 + 4\
\text{Improved initiative} = 7$; the skeletal warrior's Will is $3 + 0 + 2\ \text{undead} = 5$.

### 6.6 Stacking: typed bonuses

Every modifier has a **type**: armor, shield, natural, deflection, dodge, enhancement, morale,
competence, luck, size, insight, sacred, profane, circumstance, racial, alchemical, untyped, or
penalty (eighteen in the schema). The rule that keeps the numbers sane: **bonuses of the same type
do not stack; the largest applies**, except *untyped*, *dodge* and *circumstance* bonuses, which
add, and *penalties*, which always add. For a statistic with modifiers $v_i$ of types $t_i$, with
$S$ the set of stacking types:

$$\text{total} = \sum_{t \in S}\ \sum_{t_i = t} v_i \;+\; \sum_{t \notin S}\ \max\Big(0, \max_{t_i = t} v_i\Big).$$

The inner $\max$ with 0 is what the code does: a non-stacking type contributes its largest
*positive* value, and a negative value of a non-stacking type is ignored; the content writes every
penalty with type *penalty* so that it counts. Why the rule exists: without it, a +2 armor spell on
top of a +6 breastplate would give +8, and every buff would be worth stacking with every other.
With it, armor is worth the best armor you have, and only untyped, dodge and circumstance
bonuses reward collecting. *Luck* is not among them: the hearthkin's +1 luck bonuses to the three
saves count only because nothing else in the content gives a luck bonus to the same save.

Worked, Dara's AC again: the two dodge bonuses (feat and class feature) both count, +2; a second
suit of armor would not, and neither would a +2 armor spell on top of her +2 leather (a +4 one
would replace it). Aldric's attack has two *untyped* +1s (Weapon focus and weapon training) and
they add. `Modifiers.total` (§13) implements the formula with a scratch array of one slot per type
and no allocation.

### 6.7 Spell DCs and slots

$$DC = 10 + \text{spell level} + m(\text{casting ability}).$$

Cassius (Intelligence 20, +5) casting *fireball* (arcane level 3) sets DC 18; his *magic missile*
(level 1) would set 16 if it allowed a save. A caster has a number of **slots** per spell level
that depends on the class and the class level (an arcanist at level 5: 4, 3, 2, 1 for spell levels
0 to 3); casting spends one, and a spell without a slot left cannot be cast. The demo refills
slots when a fight ends (§27.2).

## 7. Hit points, dying and death

Damage subtracts **temporary hit points** first (a buffer some spells grant, with a duration of
its own), then hit points. At **0 or below** a creature is **dying**: unconscious, unable to act,
losing one hit point at the end of each of its turns, with a 1-in-20 chance to **stabilize** each
time (a natural 20, after which it is unconscious but no longer losing points). At $-\text{Con}$
(the Constitution *score*, not the modifier: −14 for Aldric) it is **dead**. Healing above zero
wakes a dying or unconscious creature; the dead cannot be healed or damaged. Mindless undead **die
at zero**: the skeletons are destroyed outright, which is a flag on the creature
(`diesAtZero`), set when a monster is built.

```
hp:   ... 3   2   1   0  -1  -2 ... -13 -14 -15 ...     (Aldric, Con 14)
      ---- up ----  |-------- dying --------|-- dead --
                    0 or below: dying        <= -Con: dead
```

The log of §1 shows both: Cassius falls to dying and stabilizes; the adept at 0 is destroyed.
*Dying*, *dead* and *unconscious* are conditions from the content like any other, looked up by id
when the engine is created; the engine knows their rows, not their behaviour (§20).

## 8. Conditions and durations

A **condition** is a definition with two parts: **effects** (operations, §11, usually `modify`
operations: *shaken* is −2 to attack rolls and the three saves, all of type *penalty*) and
**flags**, the handful of behaviours the engine implements in code because an operation list
cannot say them:

| Flag | Bit | Who reads it |
|---|---|---|
| `cannotAct` | 1 | `Rules.canAct`: no actions, no attacks (dazed, stunned, unconscious, dying, dead) |
| `flatFooted` | 2 | `Rules.opportunityAttacksLeft`: none before the first turn |
| `prone` | 4 | nobody yet (standing up as a move action waits for a later milestone) |
| `helpless` | 8 | nobody yet (the coup de grace is not in M15) |
| `loseDexToAc` | 16 | `Creatures.stat` for AC: the Dexterity bonus is lost (flat-footed, stunned, unconscious, dying) |
| `noMove` | 32 | nobody yet |
| `dying` | 64 | nobody: the engine finds the *dying* condition by its id, not by the flag |
| `dead` | 128 | `Rules.isDead` |

The bits are powers of two in the order of the schema's `CONDITION_FLAGS` names (L00 §6.2); the
cooker turns the JSON array `["cannotAct", "loseDexToAc"]` into `1 | 16 = 17`. A creature's active
flags are the OR of its conditions' flags, cached in one `int` per creature (`Creatures.flags`)
and refreshed whenever a condition starts or ends, so that `stat` and `canAct` test a bit instead
of walking a list.

An **instance** of a condition on a creature has a remaining duration in **rounds**, or is
**indefinite** (until removed: flat-footed ends at the first turn, dying when healed, dead never).
Durations tick **at the end of the affected creature's turn**: `endTurn(c)` decrements the rounds
of `c`'s own modifiers and conditions and removes the ones that reach zero (D99). Two consequences
to know:

- A condition applied *to a target during someone else's turn* with 1 round left lasts through
  the target's next turn and ends there, which is close to the tabletop's "until the caster's next
  turn" (the target's turn always comes before the caster's next one).
- A condition a creature applies *to itself during its own turn* with 1 round left ends at the end
  of that same turn. The demo's shortest durations are 3 rounds (*cause fear*) and 5 (*haste* at
  caster level 5), so this never shows; content with 1-round self-buffs would have to say 2.

Applying a condition that is already present does not double its effects: the instance keeps the
longer duration (§19). Removing a condition removes exactly the modifiers it added, because every
modifier remembers its **source**, a packed (kind, id) that for a condition is the instance handle
(§13).

## 9. Rounds, initiative and the action economy

### 9.1 Initiative and the order

Combat runs in **rounds** of six seconds. When a fight starts, every participant rolls
**initiative**, $d20 + \text{initiative modifier}$ (§6.5), and the participants act in descending
order of the total each round. Ties go to the higher Dexterity *score*, then to whoever joined the
fight first. A creature that joins a running fight rolls when it joins and slots into the order
(acting this round if its place is still to come, otherwise next round). Everyone is flat-footed
until their first turn, so the first seconds of a fight favour whoever won initiative.

Worked (seed 8, the seven participants of `EncounterTest`, initiative rolls 17, 13, 12, 19, 8, 5,
10 in the order they were added): Aldric 17 + 1 = 18, Brunhild 13 + 1 = 14, Dara 12 + 5 = 17,
skeletal warrior A 19 + 6 = 25, warrior B 8 + 6 = 14, stalker 5 + 3 = 8, minion 10 + 1 = 11. The
order is warrior A (25), Aldric (18), Dara (17), warrior B (14, Dexterity 14), Brunhild (14,
Dexterity 12), minion (11), stalker (8): the tie at 14 went to the warrior's higher Dexterity.

### 9.2 The action budget

Each turn grants an **action budget**:

- a **standard action**: an attack, a spell, an item;
- a **move action**: moving up to the speed, standing up, drawing;
- a **swift action**: one quick thing a round;
- a free **5-foot step**, once a turn, as long as the creature takes no other movement.

A **full-round action** (a full attack with its iteratives, a charge) spends standard and move
together. A creature may trade its standard action for a second move action, never the reverse.
So the choices a turn offers are: move and attack once; stand still and make a full attack; or
move twice. §5 priced the first two; the third is how you get out of trouble.

The budget is per creature rather than per turn, so that in real time (§10) a creature's turn can
hand it attacks that the animations spend over the following seconds while other creatures act.

### 9.3 Attacks of opportunity

Leaving the reach of an enemy, or casting a spell next to one, **provokes** an **attack of
opportunity**: each threatening enemy that can act and still has one this round makes one attack
at full bonus, outside its own turn. A creature has one per round (more with the *Reflexes* feat,
which adds its Dexterity modifier), none while flat-footed. The rules do not know where anyone
is: the *game* reports who threatens the square a creature left or the action it took (chapter
[11](11-physics-navigation-crowds.md), on the turn-based navigation's threat crossings, computes
that from the crowd's positions), and the engine resolves the attacks (§24).

## 10. Two tempos, one encounter: the time arithmetic

Real time with pause and turn-based play are not two rule sets. They are two ways of deciding
*when the next turn starts* over one encounter that holds the order, the round, the turn index and
each creature's budget (D100). The turn-based driver starts the next turn when the player presses
Enter or the AI finishes. The real-time driver runs a clock.

### 10.1 Rounds, ticks and offsets

Let $R = 6$ s be the round length and $h = 1/30$ s the simulation tick (chapter
[01](01-architecture.md), on the fixed step; D13). A round is

$$\frac{R}{h} = \frac{6}{1/30} = 180 \text{ ticks}.$$

If every creature acted at the instant a round began, the fight would look like a flip-book:
seven attack animations starting on the same tick, then six seconds of nothing. The driver instead
starts the $i$th of $n$ turns (counting from 0, in initiative order) at the **offset**

$$t_i = \frac{i}{n} \cdot R$$

seconds into the round. The turns are evenly spread, the order is still the initiative order, and
each creature still acts once per six seconds. With six party members and four skeletons, $n =
10$ and a turn starts every 0.6 s, 18 ticks. With the seven participants of the test, $t_i = 6i/7$:

```
i:        0      1      2      3      4      5      6
t_i (s):  0    0.857  1.714  2.571  3.429  4.286  5.143
ticks:    0     25.7   51.4   77.1  102.9  128.6  154.3
```

The clock advances by $h$ per tick, so a turn due at $t_i$ starts in the first tick whose clock
reaches it: after $\lceil t_i / h \rceil$ ticks (L00 §2.5): 26, 52, 78, 103, 129, 155. The first
turn is due at 0 and starts on the very first tick. When the clock reaches $R$, the driver
subtracts $R$ (the clock is "seconds into the current round", not since the fight began) and starts
a new round with turn 0.

### 10.2 A worked timeline

Running the test's seven-creature fight through `RealTimeDriver.advance(1/30)` once per tick and
printing the tick in which each turn started (seed 8, the order of §9.1):

```
tick    1  round 1 turn 0: Skeletal warrior      (due 0.000 s: the first advance)
tick   26  round 1 turn 1: Aldric                (due 0.857 s, clock 0.867)
tick   52  round 1 turn 2: Dara                  (due 1.714 s, clock 1.733)
tick   78  round 1 turn 3: Skeletal warrior      (due 2.571 s, clock 2.600)
tick  103  round 1 turn 4: Brunhild              (due 3.429 s, clock 3.433)
tick  129  round 1 turn 5: Skeletal minion       (due 4.286 s, clock 4.300)
tick  155  round 1 turn 6: Skeletal stalker      (due 5.143 s, clock 5.167)
tick  181  round 2 turn 0: Skeletal warrior      (due 6.000 s: one tick late, see 10.3)
tick  206  round 2 turn 1: Aldric
...
tick  361  round 3 turn 0: Skeletal warrior
```

A turn never starts *between* ticks; it starts at most one tick ($h = 33$ ms) after it is due,
which no player can see. The test `realTimeTurnsAreSpreadOverTheRound` (§29) checks the same
arithmetic at a coarser grain: `advance(0f)` starts turn 0 (due at 0), `advance(0.5)` then starts
no turn (the second is due at 6/7 = 0.857 s), the next `advance(0.4)` (clock 0.9 ≥ 0.857) starts
one, and `advance(5.5)` from there starts the five remaining turns of round 1 and the
first of round 2.

### 10.3 What the float does to the clock

The clock is a `float` that accumulates `dt`. L00 §7.3 warned that `0.1f` is not 0.1; neither is
`1f / 30` exactly $1/30$: it is 0.033333335, slightly *above*, and the sum of 180 of them, with the
rounding of each addition, comes out *below* six: computed in `float`, 180 additions of `1f / 30`
give 5.9999957. So the first round boundary is not reached on tick 180 but on tick 181, when the
clock is 6.033. The driver then subtracts 6 and the clock keeps the residue, 0.033; from there each
round is exactly 180 ticks (the residue stays, it does not grow: every round subtracts an exact 6
from a sum that drifted by the same amount). The whole effect is one tick once per fight, and the
test asserts only that the clock stays below `ROUND_SECONDS` after a round boundary. If it
mattered, the fix would be chapter 01's: count whole ticks in an `int` and compare $i \cdot 180$
with $n \cdot \text{tick}$, which is exact.

### 10.4 Switching tempo mid-fight

Switching to turn-based play keeps the round, the turn index and each creature's remaining budget:
nothing in the encounter knows which driver is stepping it. Switching back must put the clock
somewhere sensible: `RealTimeDriver.sync` sets it to the current turn's offset, $t_{\text{turnIndex}}$,
so the next creature's turn is due after its share of the round rather than at once (§25). The
test `bothDriversPlayTheSameFight` (§29) runs one scripted fight through both drivers, and through
the real-time one at two step sizes (1/60 s and 0.75 s), and asserts the same events, the same hit
points and the same number of rounds: one fight, two tempos, no second rule set.

## 11. Content as data: definitions, operations and the cooked file

### 11.1 Why operations, not classes

Feats, spells, items, class features, ancestries, monsters, characters and conditions are
**definitions**: rows of content, written in JSON under `assets/rules/` (one file per kind), cooked
into the tables of a `.jrules` file. None of them is a Java class. A definition's behaviour is a
list of **operations** the engine interprets (D98). The Dodge feat and the two-weapon fighting
feat, from [`feats.json`](../../samples/iso-demo/assets/rules/feats.json):

```json
{"id": "dodge", "name": "Dodge", "description": "+1 dodge bonus to AC.", "minDex": 13,
 "effects": [{"op": "modify", "stat": "ac", "type": "dodge", "value": 1}]},
{"id": "twoWeaponFighting", "name": "Two-weapon fighting", "description": "An extra attack with the off hand at −2.", "minDex": 15,
 "effects": [{"op": "extraAttack", "value": 1}, {"op": "modify", "stat": "attack", "type": "penalty", "value": -2}]},
```

and a spell adds the delivery around its operations, from
[`spells.json`](../../samples/iso-demo/assets/rules/spells.json):

```json
{"id": "fireball", "name": "Fireball", "description": "A burst of flame: 1d6 fire damage per caster level (max 10d6), Reflex halves.",
 "arcaneLevel": 3, "school": "evocation", "action": "standard", "range": 400, "shape": "burst", "radius": 20, "targets": "all",
 "save": "reflex", "saveResult": "half",
 "effects": [{"op": "damage", "dice": "5d6", "damageType": "fire"}]},
```

The whole vocabulary is nine operations: `modify`, `damage`, `heal`, `tempHp`, `condition`,
`removeCondition`, `grantFeat`, `extraAttack`, `setSpeed`. A feat is "while known, these"; a
condition is "while it lasts, these"; a spell is "on cast, against this save, these"; a class
feature is "from this level, these"; an item is "while equipped, these" and, for a consumable,
"when used, these". The engine runs the list with a *source* (who or what caused it, so it can be
undone) and a *target* (who it happens to); the operations never name a creature. Every rule in
the demo's content is written in them. The few things an operation list cannot say are **flags** on the
feat (`reflexes`, `powerAttack`, `pointBlank`, `cleave`): behaviour the engine is meant to
implement in code and the content switches on, the escape hatch, kept small. Today only
`reflexes` is implemented (extra attacks of opportunity equal to the Dexterity modifier, §18.4);
the other three are only queryable through `Rules.hasFeatFlag`, and nothing in the engine or the
demo yet applies Power attack's trade, Point-blank shot's bonus or Cleave's extra swing. A
character who knows them (Brunhild knows Power attack and Cleave) gains nothing from them yet.

Why not a class per effect (`DodgeFeat extends Feat`)? Every new feat would be Java, a designer
could not write one, and data files could not express it. Why not a scripting language? That is
milestone M17's dialogue and quest graphs, which will *call* operations, not replace them (D98).

### 11.2 The operation record

In the cooked file every operation is **eight ints** in one flat array:

```
index:  0    1     2     3      4     5       6    7
field:  op  stat  type  value  dice  rounds  ref  damageType
```

`op` selects the operation; `stat` and `type` are the statistic and bonus type of a `modify`
(indices into `Stat` and `BonusType`); `value` is a modify's bonus, an `extraAttack`'s count or a
`setSpeed`'s feet; `dice` is a packed dice expression for `damage`, `heal` and `tempHp`; `rounds`
is a duration, 0 meaning "while the source applies"; `ref` is `kind << 24 | row` of the condition
or feat a `condition`, `removeCondition` or `grantFeat` names, or −1; `damageType` indexes the
damage-type names. A definition's `effects` field is a *range* (offset, count) into that array, so
applying a feat is a loop over eight-int records with no object in sight. Cooked, the Dodge feat's
single operation is the row `0 6 5 1 0 0 -1 10` (modify, AC, dodge, +1, no dice, no rounds, no
ref, damage type "untyped" by default), and the fireball's is `1 6 0 0 0x05060000 0 -1 3` (damage,
dice `5d6` packed as in L02 §16.2, type fire).

### 11.3 One schema, four readers

`RulesSchema` is the single description of all this: the eleven **kinds** of definition
(conditions, ancestries, classes, class slots, class features, feats, spells, items, monsters,
monster attacks, characters), and for each kind its **fields** with a type (`INT`, `BOOL`,
`STRING`, `ENUM`, `FLAGS`, `DICE`, `REF`, `REF_LIST`, `INT_LIST`, `OPS`, `NESTED`), a default and a
one-line documentation string. Four readers use it and nothing else (D97):

1. the cooker's `RulesImporter` validates the JSON against it (unknown fields, enum names,
   dice, references to unknown ids, operations missing a stat, a ref or dice) and writes the
   tables in its column order;
2. `RulesData` reads the file back and checks every range and reference against the table sizes;
3. the engine's `Content` resolves the field indices it needs by name, once, at construction;
4. the editor's Rules panel generates its property grid from it (chapter [14](14-editor.md), on
   the editor's panels) and validates the same way before writing the JSON back, which the
   cooker's watcher re-cooks and the running game hot-reloads: `DemoGame.buildCombat` rebuilds
   the fight's creatures when the asset's version changes (§27.9).

Adding a field to the content means adding it in one place. Why JSON cooked to binary, rather than
JSON read at runtime or content as Java builders? The owner's choice (D97): runtime JSON parses
at load and validates nothing before play; Java builders need a recompile per tweak and a
programmer per designer.

### 11.4 The file

A `.jrules` file ([`docs/formats/jrules.md`](../formats/jrules.md)) is a 64-byte header, then
**one table per kind** of fixed-stride `int` rows (a row's cells are read as `(kind, row, field)`
through the schema's offsets), then the operation array, a **pool** of ints that list fields slice,
and a string table. A field is one int (`INT`, `BOOL`, `ENUM`, `FLAGS`, `DICE`, `REF`) or two (an
offset and a count: `STRING`, `REF_LIST`, `INT_LIST`, `OPS`, `NESTED`); a kind's **stride** is the
sum. The feats table, for instance, has stride 18: `id`, `name` and `description` (two ints each),
seven requirement ints, `requires` (two), `flags` (one), `effects` (two). Nested kinds (a class's
slots and features, a monster's attacks) are rows of their own table that the parent's `NESTED`
field ranges over. The demo's whole rulebook cooks to **16,609 bytes**: 16 conditions, 6
ancestries, 6 classes with 10 slot rows and 14 features, 14 feats, 13 spells, 17 items, 4
monsters with 4 attacks, 6 characters, 82 operations and 264 strings (counted by cooking it in a
test JVM). Lesson [L04](lessons/04-binary-formats.md) explains the little-endian layout and
alignment conventions this format follows; §26 builds the reader and the importer.

---
# Part B — Building it

The module is `dev.jeddy.rules`, pure Java with no third-party dependency, depending only on
`core` (the byte streams, and `core.collections.IntList`: a growable list of primitive `int`s
that never boxes, read with `get(i)` and `size()` and grown only when an `add` passes its
capacity), `math` (`Rng`, `Dice`) and `asset` (the schema and
the cooked tables). Its tests additionally use the cooker, so that they build the demo's content
from the JSON files the game ships and the hand-computed numbers in them follow those files
(D97). Everything in it runs on the game thread and allocates only when a store grows past the
capacity it was given. The steps below follow the order you would write the classes in: the
vocabularies, the three stores, the typed content reader, the event ring, the engine in five
parts, the budget, the encounter, the drivers, and the pipeline that feeds them.

## 12. Step 1: `Stat` and `BonusType`

The content and the engine must agree on what "ac" or "dodge" means. The schema (§26) lists the
statistics and the bonus types as arrays of names; the engine wants `int` constants it can
`switch` on and index arrays with. Create `Stat.java`:

```java
public final class Stat {
    private Stat() {}

    public static final int STR = 0;
    public static final int DEX = 1;
    public static final int CON = 2;
    public static final int INT = 3;
    public static final int WIS = 4;
    public static final int CHA = 5;
    public static final int AC = 6;
    public static final int ATTACK = 7;
    public static final int DAMAGE = 8;
    public static final int FORTITUDE = 9;
    public static final int REFLEX = 10;
    public static final int WILL = 11;
    public static final int INITIATIVE = 12;
    public static final int SPEED = 13;
    public static final int MAX_HP = 14;
    public static final int AOO = 15;
    public static final int EXTRA_ATTACKS = 16;

    /** Number of statistics. */
    public static final int COUNT = 17;

    static {
        if (RulesSchema.STATS.length != COUNT
                || !RulesSchema.STATS[AC].equals("ac")
                || !RulesSchema.STATS[AOO].equals("aoo")
                || !RulesSchema.STATS[EXTRA_ATTACKS].equals("extraAttacks")) {
            throw new ExceptionInInitializerError("Stat constants disagree with RulesSchema.STATS");
        }
    }
```

- **The six abilities come first, in the order every ability-indexed array uses** (`str`, `dex`,
  …), so that an ability's `Stat` index is also its index in the six-per-creature score array
  (§15), and `isAbility(stat)` is a range test.
- **The static initializer** compares the constants with the schema's names the first time the
  class is used. If someone inserts a name into `RulesSchema.STATS` and forgets this file, the
  module fails loudly at startup instead of silently applying a bonus to the wrong statistic.
  Three spot checks (the first after the abilities, the last two) are enough to catch an insertion
  anywhere.

`modifier(score)` from §3 and `name(stat)` (the schema's name, for logs) complete it.
`BonusType.java` is the same shape with eighteen constants (`UNTYPED = 0`, `ARMOR = 1`, …,
`PENALTY = 17`, `COUNT = 18`), its own spot check, and the stacking rule of §6.6 as a function:

```java
/** Returns whether bonuses of {@code type} add up rather than take the largest. */
public static boolean stacks(int type) {
    return type == UNTYPED || type == DODGE || type == CIRCUMSTANCE || type == PENALTY;
}
```

Why constants in a `final` class rather than an `enum`? A modifier's type is a column in an
`int[]` (§13) and an operation's field is an `int` in a file; an `enum` would be boxed into those
columns through `ordinal()` and `values()[i]` on every read, and `values()` allocates an array.
The `int` is the representation the data already has.

## 13. Step 2: `Modifiers`

### 13.1 What a modifier is, as columns

A modifier is "a value of a type, to a statistic of a creature, from a source, for a number of
rounds". Create `Modifiers.java` as a struct-of-arrays store (L03 §4) with a free list:

```java
public final class Modifiers {
    /** Source kinds. */
    public static final int SOURCE_NONE = 0;

    public static final int SOURCE_CONDITION = 1; // id: the condition instance handle
    public static final int SOURCE_FEAT = 2; // id: the feat row
    public static final int SOURCE_CLASS = 3; // id: the feature row
    public static final int SOURCE_ANCESTRY = 4; // id: the ancestry row
    public static final int SOURCE_ITEM = 5; // id: the equipment slot
    public static final int SOURCE_SPELL = 6; // id: the spell row
    public static final int SOURCE_MONSTER = 7; // id: the monster row
    public static final int SOURCE_CHARACTER = 8; // id: the character row
    public static final int SOURCE_GAME = 9; // id: whatever the game wants

    /** Lasts until removed by its source. */
    public static final int PERMANENT = -1;

    private int[] creature;
    private int[] stat;
    private int[] type;
    private int[] value;
    private int[] source;
    private int[] rounds;
    private boolean[] alive;
    private int count;
    private final IntList free;
    private int liveCount;
```

Six parallel `int[]`s and a `boolean[]`: handle `h` is an index into all of them. `count` is the
high-water mark (slots ever used), `free` the slots given back, `liveCount` the slots in use.
There is no generation in these handles (L03 §6): a modifier handle is held only by its creature's
own list, which is cleaned whenever a modifier is removed, so a stale handle cannot exist.

```java
public Modifiers(int capacity) {
    int n = Math.max(1, capacity);
    creature = new int[n];
    stat = new int[n];
    type = new int[n];
    value = new int[n];
    source = new int[n];
    rounds = new int[n];
    alive = new boolean[n];
    free = new IntList(Math.max(16, n / 4));
}
```

The no-argument constructor gives 64. `Rules` (§18) sizes it at sixteen per creature: a store
that doubles allocates on the game thread, and §30 tells how that was found.

### 13.2 The source

The source answers "why is this modifier here?", so that removing a feat, unequipping an item or
ending a condition removes exactly its modifiers and nothing else. A source is a kind and an id
packed into one `int` (L00 §6.4):

```java
/** Packs a source. */
public static int source(int kind, int id) {
    return kind << 24 | (id & 0xFFFFFF);
}

/** The kind of a packed source. */
public static int sourceKind(int source) {
    return source >>> 24;
}

/** The id of a packed source. */
public static int sourceId(int source) {
    return source & 0xFFFFFF;
}
```

Eight bits of kind, twenty-four of id (16 million rows or handles, far more than any rulebook or
fight). `& 0xFFFFFF` keeps an id that is −1 from spilling into the kind. Worked:
`source(SOURCE_FEAT, 7)` is `2 << 24 | 7` = `0x02000007`; `sourceKind` shifts the 2 back down with
`>>>` (no sign extension, L00 §6.3) and `sourceId` masks the 7. What the id means depends on the
kind, as the comments say: a feat's row, an equipment *slot* (so re-equipping a slot replaces the
old item's modifiers), a condition *instance* handle (two instances of the same condition on
different creatures are different sources).

### 13.3 Adding and removing

```java
public int add(int creature, int stat, int type, int value, int source, int rounds) {
    int h;
    if (!free.isEmpty()) {
        h = free.removeLast();
    } else {
        if (count == alive.length) {
            grow();
        }
        h = count++;
    }
    this.creature[h] = creature;
    this.stat[h] = stat;
    this.type[h] = type;
    this.value[h] = value;
    this.source[h] = source;
    this.rounds[h] = rounds;
    alive[h] = true;
    liveCount++;
    return h;
}

/** Removes modifier {@code h}. */
public void remove(int h) {
    if (alive[h]) {
        alive[h] = false;
        free.add(h);
        liveCount--;
    }
}
```

The free list is used last-in-first-out (`removeLast`), which keeps recently used slots, and their
cache lines, warm. `grow` doubles every array with `Arrays.copyOf`. `remove` is idempotent: removing
twice does not put the handle on the free list twice, which would later hand one slot to two
modifiers.

### 13.4 `total`: the stacking rule

The method the whole chapter turns on. It is given the creature's own handle list (§15), so it
never walks the store:

```java
public int total(IntList handles, int stat, int[] scratch) {
    Arrays.fill(scratch, 0, BonusType.COUNT, 0);
    int stacked = 0;
    for (int i = 0, n = handles.size(); i < n; i++) {
        int h = handles.get(i);
        if (!alive[h] || this.stat[h] != stat) {
            continue;
        }
        int t = type[h];
        int v = value[h];
        if (BonusType.stacks(t)) {
            stacked += v;
        } else if (v > scratch[t]) {
            scratch[t] = v; // the largest bonus of a type; a same-type penalty would be PENALTY
        }
    }
    for (int t = 0; t < BonusType.COUNT; t++) {
        if (!BonusType.stacks(t)) {
            stacked += scratch[t];
        }
    }
    return stacked;
}
```

This is the formula of §6.6, line by line:

- **`scratch`** is an `int[BonusType.COUNT]` the caller owns (the creature store keeps one): one
  slot per non-stacking type, holding the largest value seen. Filling eighteen ints is cheaper than
  any map, and nothing allocates.
- **The first loop** reads each handle once: skips dead handles and other statistics, adds a
  stacking type straight into `stacked`, and keeps the maximum per non-stacking type. Because the
  slots start at 0, a negative value of a non-stacking type never wins: the content writes
  penalties as `PENALTY`, which stacks, and the comment says so.
- **The second loop** adds the eighteen maxima. The cost is $O(\text{modifiers} + \text{types})$
  and the creature with the most modifiers in the demo (Dara) has ten.

Worked with `ModifiersTest.sameTypeTakesTheLargestAndStackingTypesAdd` (§29): armor +4 and armor
+6, dodge +1 and dodge +1, untyped +1, penalties −2 and −1 → $6 + (1 + 1) + 1 + (-2 - 1) = 6$,
which the test writes as `6 + 2 + 1 - 3`.

A second query, `totalOfType(handles, stat, type)`, sums or maxes only one type: `armorClass`
(§21) uses it to strip the armor, shield and natural bonuses for a touch attack.

### 13.5 Removing by source, and ticking

```java
public void removeBySource(IntList handles, int source) {
    for (int i = handles.size() - 1; i >= 0; i--) {
        int h = handles.get(i);
        if (!alive[h] || this.source[h] == source) {
            remove(h);
            handles.removeAt(i);
        }
    }
}

public int tick(IntList handles) {
    int expired = 0;
    for (int i = handles.size() - 1; i >= 0; i--) {
        int h = handles.get(i);
        if (!alive[h]) {
            handles.removeAt(i);
            continue;
        }
        if (rounds[h] > 0 && --rounds[h] == 0) {
            remove(h);
            handles.removeAt(i);
            expired++;
        }
    }
    return expired;
}
```

Both loops **run backwards**: `handles.removeAt(i)` shifts the elements after `i` down by one, so
walking from the end means the shift only ever moves elements already visited, and `i` stays
valid. (Walking forwards you would have to not increment `i` after a removal, the classic
off-by-one.) Both also compact out dead handles they happen to meet. In `tick`, `rounds[h] > 0`
keeps `PERMANENT` (−1) modifiers forever, and `--rounds[h] == 0` decrements and tests in one
expression: a one-round modifier expires on its first tick.

## 14. Step 3: `Conditions`

A condition *instance* is smaller than a modifier: which creature, which condition row, how many
rounds. The store is the same shape with three columns and an `INDEFINITE = -1` duration:

```java
public final class Conditions {
    /** Lasts until removed. */
    public static final int INDEFINITE = -1;

    private int[] creature;
    private int[] condition;
    private int[] rounds;
    private boolean[] alive;
    private int count;
    private int liveCount;
    private final dev.jeddy.core.collections.IntList free;
```

`add` and `remove` are the modifier store's (package-private: only `Rules` and `Creatures` create
and destroy instances, because an instance's effects must be applied and removed with it, §19).
The condition's *effects* do not live here: they are modifiers in `Modifiers` whose source is
`source(SOURCE_CONDITION, h)`, so that ending instance `h` removes exactly them. The default
capacity is 32; `Rules` gives it four per creature.

## 15. Step 4: `Creatures`

### 15.1 The stat block as columns

Everything about a creature that §6 called a base, plus the lists that hang off it. Create
`Creatures.java`:

```java
public final class Creatures {
    /** Equipment slots. */
    public static final int SLOT_WEAPON = 0;

    public static final int SLOT_ARMOR = 1;
    public static final int SLOT_SHIELD = 2;
    public static final int SLOTS = 3;

    /** Spell levels tracked. */
    public static final int SPELL_LEVELS = 10;

    private final Modifiers modifiers;
    private final Conditions conditionStore;
    private int capacity;
    private int count;
    private boolean[] alive;
    private final IntList free = new IntList(16);
    private int liveCount;

    private String[] name;
    private int[] team;
    private int[] characterRow; // -1 for monsters
    private int[] monsterRow; // -1 for characters
    private int[] ability; // 6 per creature, base scores
    private int[] level;
    private int[] classRow;
    private int[] ancestryRow;
    private int[] bab;
    private int[] baseSave; // 3 per creature
    private int[] baseMaxHp;
    private int[] hp;
    private int[] tempHp;
    private int[] tempHpRounds;
    private int[] size;
    private int[] baseSpeed;
    private int[] equipment; // SLOTS per creature, item rows or -1
    private int[] monsterAttack; // first attack row, or -1
    private int[] monsterAttackCount;
    private int[] casting;
    private int[] castingAbility;
    private int[] slotsUsed; // SPELL_LEVELS per creature
    private boolean[] diesAtZero;
    private IntList[] feats;
    private IntList[] spells;
    private IntList[] mods;
    private IntList[] conditions; // condition instance handles into conditionStore
    private int[] flags; // the union of the active conditions' flags, kept by Rules
    private int[] aooUsed;
    private boolean[] acted;
    private final int[] scratch = new int[BonusType.COUNT];
```

Reading the columns:

- **Fixed-width groups are flattened** into one array with a stride: the six scores are
  `ability[c * 6 + a]`, the three saves `baseSave[c * 3 + s]`, the three equipment slots
  `equipment[c * SLOTS + slot]`, the ten spell levels' used slots `slotsUsed[c * SPELL_LEVELS +
  level]`. One array per group means one allocation per group and every creature's scores next to
  each other (L03 §4.2).
- **Variable-length groups are an `IntList` per creature**: feats known, spells known, modifier
  handles, condition instance handles. These are the only objects per creature, created once when
  the store is allocated (with capacities 8, 8, 16 and 8) and cleared, never replaced, on reuse.
- **`characterRow` / `monsterRow`** remember which definition the creature was built from: the
  save (§28) writes the definition's *id*, never a row, so that a patched rulebook can renumber
  (D115).
- **`flags`** is the cached OR of the active conditions' flags (§8). **`aooUsed`** and **`acted`**
  are per-round state the encounter resets. **`scratch`** is the one `int[BonusType.COUNT]` that
  every `bonus` query passes to `Modifiers.total` (§13.4).
- **`name`** is a `String[]`: the only strings in the module, set once per creature from the
  content and read by the demo's log; the engine never builds one.

Three more columns are declared at the bottom of the file, next to the method that sets them,
because they are "weapon-dependent switches" rather than bases: `maxDexToAc` (the equipped armor's
cap, 99 for none), `finesse` and `twoHanded` (§6.4).

### 15.2 Allocation and growth

```java
public Creatures(Modifiers modifiers, Conditions conditionStore, int capacity) {
    this.modifiers = modifiers;
    this.conditionStore = conditionStore;
    this.capacity = Math.max(1, capacity);
    allocate(this.capacity);
}

private void allocate(int n) {
    alive = Arrays.copyOf(alive == null ? new boolean[0] : alive, n);
    name = Arrays.copyOf(name == null ? new String[0] : name, n);
    team = grow(team, n);
    characterRow = grow(characterRow, n);
    monsterRow = grow(monsterRow, n);
    ability = grow(ability, n * 6);
    ...
    mods = growLists(mods, n, 16); // ancestry, class, feats, items and a fight's conditions
    conditions = growLists(conditions, n, 8);
    flags = grow(flags, n);
    aooUsed = grow(aooUsed, n);
    acted = Arrays.copyOf(acted == null ? new boolean[0] : acted, n);
    int old = maxDexToAc == null ? 0 : maxDexToAc.length;
    maxDexToAc = grow(maxDexToAc, n);
    Arrays.fill(maxDexToAc, old, n, 99);
    finesse = Arrays.copyOf(finesse == null ? new boolean[0] : finesse, n);
    twoHanded = Arrays.copyOf(twoHanded == null ? new boolean[0] : twoHanded, n);
    capacity = n;
}
```

(Condensed: the middle of the list repeats `grow` for each column.) One method serves both the
constructor and growth: `grow(a, n)` returns `new int[n]` for a `null` array and `Arrays.copyOf(a,
n)` otherwise, and `growLists` creates an `IntList` only for the new slots. `maxDexToAc` is the
one column whose default is not 0, so the new part is filled with 99 ("no cap"). The constructor
takes the two other stores rather than creating them, so that `Rules` can size all three from one
number and so that destroying a creature can give its modifiers and instances back (§15.3).

### 15.3 Creating, destroying and recycling

```java
public int create(String name, int team) {
    int c;
    if (!free.isEmpty()) {
        c = free.removeLast();
    } else {
        if (count == capacity) {
            allocate(capacity * 2);
        }
        c = count++;
    }
    init(c, name, team);
    return c;
}
```

`init` sets every column to a neutral default (scores 10, level 1, 10 hit points, medium, 30
feet, no class, no equipment, `casting` none) and clears the four lists; `buildCharacter` and
`buildMonster` (§18) fill it from content. Note the order inside `init`: it calls `release(c)`
*before* clearing the flags, so that a slot being reused after `destroy` (which already released)
is harmless, and a slot used for the first time has empty lists anyway.

```java
/** Removes creature {@code c}, its modifiers and its condition instances. */
public void destroy(int c) {
    if (!isAlive(c)) {
        return;
    }
    release(c);
    alive[c] = false;
    liveCount--;
    free.add(c);
}

/** Gives {@code c}'s modifiers and condition instances back to their stores. */
private void release(int c) {
    for (int i = 0; i < mods[c].size(); i++) {
        modifiers.remove(mods[c].get(i));
    }
    mods[c].clear();
    for (int i = 0; i < conditions[c].size(); i++) {
        conditionStore.remove(conditions[c].get(i));
    }
    conditions[c].clear();
}
```

`release` is the line that a 120-second performance capture asked for: the first version of
`destroy` freed the creature's modifiers but not its condition instances, so every skeleton that
died and was rebuilt leaked its `dead` instance, and the condition store doubled after enough
respawns (§30). The test `destroyedCreaturesReleaseTheirConditionsSoRespawnsDoNotGrowTheStore`
runs ten generations of 48 skeletons through death and rebuild and asserts the store never grew.

`createAt(c, name, team)` creates a creature *at a given handle*, for a load that must restore
creatures at the handles the rest of the game holds (§28): it destroys whatever lives there, takes
`c` off the free list or marks the skipped slots free, and calls `init`. `clear()` destroys
everyone for a load that starts from nothing.

### 15.4 Resolution

The accessors of the bases are one line each (`hp(c)`, `setHp(c, v)`, …). The derived numbers come
from four methods:

```java
/** The stacked modifier total for {@code stat}. */
public int bonus(int c, int stat) {
    return modifiers.total(mods[c], stat, scratch);
}

/** The current ability score {@code a}: base plus modifiers. */
public int ability(int c, int a) {
    return ability[c * 6 + a] + bonus(c, a);
}

/** The current ability modifier. */
public int abilityModifier(int c, int a) {
    return Stat.modifier(ability(c, a));
}
```

An ability score is itself a statistic a modifier can target (the *fatigued* condition is −2
Strength and −2 Dexterity; *raging* is +4 morale to Strength and Constitution), and the ability
index *is* the `Stat` index (§12), so `bonus(c, a)` works unchanged. Then the `switch` that turns
§6 into code:

```java
public int stat(int c, int stat) {
    switch (stat) {
        case Stat.STR, Stat.DEX, Stat.CON, Stat.INT, Stat.WIS, Stat.CHA -> {
            return ability(c, stat);
        }
        case Stat.AC -> {
            int dex =
                    hasFlag(c, Content.FLAG_LOSE_DEX)
                            ? 0
                            : Math.max(0, abilityModifier(c, Stat.DEX));
            return 10
                    + Content.sizeModifier(size[c])
                    + Math.min(dex, maxDexToAc[c])
                    + bonus(c, Stat.AC);
        }
        case Stat.ATTACK -> {
            int str = abilityModifier(c, Stat.STR);
            int dex = abilityModifier(c, Stat.DEX);
            int best = finesse[c] ? Math.max(str, dex) : str;
            return bab[c] + best + Content.sizeModifier(size[c]) + bonus(c, Stat.ATTACK);
        }
        case Stat.DAMAGE -> {
            int str = abilityModifier(c, Stat.STR);
            return (twoHanded[c] ? str + str / 2 : str) + bonus(c, Stat.DAMAGE);
        }
        case Stat.FORTITUDE -> {
            return baseSave[c * 3] + abilityModifier(c, Stat.CON) + bonus(c, Stat.FORTITUDE);
        }
        case Stat.REFLEX -> {
            return baseSave[c * 3 + 1] + abilityModifier(c, Stat.DEX) + bonus(c, Stat.REFLEX);
        }
        case Stat.WILL -> {
            return baseSave[c * 3 + 2] + abilityModifier(c, Stat.WIS) + bonus(c, Stat.WILL);
        }
        case Stat.INITIATIVE -> {
            return abilityModifier(c, Stat.DEX) + bonus(c, Stat.INITIATIVE);
        }
        case Stat.SPEED -> {
            return Math.max(0, baseSpeed[c] + bonus(c, Stat.SPEED));
        }
        case Stat.MAX_HP -> {
            return Math.max(1, baseMaxHp[c] + bonus(c, Stat.MAX_HP));
        }
        case Stat.AOO -> {
            return 1 + bonus(c, Stat.AOO);
        }
        case Stat.EXTRA_ATTACKS -> {
            return bonus(c, Stat.EXTRA_ATTACKS);
        }
        default -> throw new IllegalArgumentException("stat " + stat);
    }
}
```

Follow Brunhild's 14 and 15 through the `AC` case: `hasFlag(c, FLAG_LOSE_DEX)` is true while the
*flat-footed* condition is on her (its flags are `flatFooted` and `loseDexToAc`), so `dex` is 0
and the result is `10 + 0 + min(0, 4) + 4 = 14`; after `startTurn` removes the condition (§22),
`dex` is `max(0, modifier(12)) = 1`, capped by the chain shirt's 4, and the result is 15. Every
case is a direct read of columns plus one `bonus` call, which is one pass over the creature's
handle list: `CreaturesTest.statResolutionAllocatesNothing` resolves all seventeen statistics of
Aldric, feats and equipment included, at 0 bytes.

Why resolve on every call instead of caching the results? A cache must be invalidated by every
`addModifier`, `remove`, `equip`, condition start and end, and the ability case feeds the others
(Dexterity feeds AC, Reflex and initiative), so the invalidation would be a graph. At the sizes of
a CRPG fight (tens of creatures, at most tens of modifiers each) the recomputation costs a few
dozen array reads, and every rule change is visible at once.

The last method of the file sets the weapon switches; `Rules.equip` (§18) calls it whenever a
slot changes:

```java
void setWeaponTraits(int c, boolean finesseWeapon, boolean twoHandedWeapon, int maxDex) {
    finesse[c] = finesseWeapon;
    twoHanded[c] = twoHandedWeapon;
    maxDexToAc[c] = maxDex;
}
```

## 16. Step 5: `Content`

### 16.1 Field indices resolved once

The cooked tables (`RulesData`, §26) are read by `(kind, row, field)`. The engine could look up
"what is the index of `threat` in the items kind?" every time it rolls an attack, but that is a
string comparison per read. Instead `Content` asks the schema once, at construction, for every
field it will ever need and keeps the answers in `final int`s:

```java
public Content(RulesData data) {
    this.data = data;
    Kind c = RulesSchema.CONDITIONS;
    cFlags = c.require("flags");
    cEffects = c.require("effects");
    Kind a = RulesSchema.ANCESTRIES;
    aSize = a.require("size");
    aSpeed = a.require("speed");
    aAbility = a.require("str");
    aEffects = a.require("effects");
    ...
    Kind i = RulesSchema.ITEMS;
    iKind = i.require("kind");
    iDamage = i.require("damage");
    iThreat = i.require("threat");
    iCritical = i.require("critical");
    ...
}
```

(Condensed: the constructor has one such line per field, 78 in all.) `require` throws with
the kind and field name when a field is missing, so a schema edit that removes a field the engine
reads fails at the first `new Content`, not in the middle of a fight. The accessors are then one
array read each:

```java
public int itemThreat(int row) {
    return data.getInt(RulesSchema.ITEMS, row, iThreat);
}

public int conditionEffectsStart(int row) {
    return data.start(RulesSchema.CONDITIONS, row, cEffects);
}

public int conditionEffectsCount(int row) {
    return data.count(RulesSchema.CONDITIONS, row, cEffects);
}
```

Two conventions make the ability fields cheap: a kind's six ability fields are declared
consecutively in the schema, so `ancestryAbility(row, ability)` reads `aAbility + ability`; the
three save progressions likewise (`clSave + save`).

### 16.2 Lists, nested rows and references

A `REF_LIST` field (a character's feats) is an offset and a count into the pool: `featsStart` and
`featsCount`, then `content.pool(start + i)` gives the $i$th row. A `NESTED` field (a class's slot
rows) is an offset and a count into the nested kind's table. `classSlots` walks the nested rows to
find the one for a class level:

```java
public int classSlots(int row, int level, int spellLevel) {
    int start = data.start(RulesSchema.CLASSES, row, clSlots);
    int n = data.count(RulesSchema.CLASSES, row, clSlots);
    Kind k = RulesSchema.CLASS_SLOTS;
    for (int i = 0; i < n; i++) {
        if (data.getInt(k, start + i, csLevel) == level) {
            int ls = data.start(k, start + i, csSlots);
            int lc = data.count(k, start + i, csSlots);
            return spellLevel < lc ? data.pool(ls + spellLevel) : 0;
        }
    }
    return 0;
}
```

A slot row is `{"level": 5, "slots": [4, 3, 2, 1]}`: the list is spell levels 0 upwards, so a
spell level past the list's end has no slots, and a class level with no row has none either. The
arcanist at level 5 has one third-level slot (`pool(ls + 3)` = 1) and no fourth-level one (`4 < 4`
is false).

An operation's `ref` packs the kind and the row, and two static helpers unpack it: `refRow(ref)`
is `ref & 0xFFFFFF`, `refKind(ref)` is `ref >>> 24`, the same layout as a modifier's source.

### 16.3 The arithmetic helpers

The progressions of §6.1 and the size modifier of §6.3 are static methods here because they
depend on content conventions (the progression names) rather than on a creature:

```java
public static int baseAttack(int progression, int level) {
    return switch (progression) {
        case BAB_FULL -> level;
        case BAB_THREE_QUARTERS -> level * 3 / 4;
        default -> level / 2;
    };
}

public static int baseSave(int progression, int level) {
    return progression == SAVE_GOOD ? 2 + level / 2 : level / 3;
}

/** The attack and AC modifier of a size (medium 0, small +1, tiny +2, large −1, huge −2). */
public static int sizeModifier(int size) {
    return SIZE_MEDIUM - size;
}
```

`level * 3 / 4` multiplies first, then divides, which is the floor of $3L/4$ for positive $L$;
`level / 4 * 3` would floor too early (5/4·3 = 3 by luck here, but 7/4·3 = 3 instead of 5).

The constants at the top of the file (`FLAG_*`, `FEAT_*`, `OP_*`, `BAB_*`, `SAVE_*`, `CASTING_*`,
`TARGETS_*`, `ITEM_*`, `SIZE_MEDIUM`) are the engine's names for the schema's enum and flag
orders, with the same promise as `Stat`'s: they match the arrays in `RulesSchema` by position.

## 17. Step 6: `Events`

### 17.1 The log without strings

Every outcome the rules decide is written somewhere the UI, the game and the tests can read it.
A `String` per outcome would allocate in the middle of a fight; a listener interface would make
the rules call back into the game while they are in the middle of resolving something. The engine
does neither: it appends a fixed-width record of eight `int`s to a ring, and whoever cares drains
the ring afterwards. Create `Events.java`:

```java
public final class Events {
    public static final int ATTACK_HIT = 0;
    public static final int ATTACK_MISS = 1;
    public static final int CRITICAL = 2;
    public static final int DAMAGE = 3;
    public static final int HEAL = 4;
    public static final int SAVE_SUCCESS = 5;
    public static final int SAVE_FAIL = 6;
    public static final int CONDITION_ON = 7;
    public static final int CONDITION_OFF = 8;
    public static final int DYING = 9;
    public static final int DEATH = 10;
    public static final int STABLE = 11;
    public static final int TURN = 12;
    public static final int ROUND = 13;
    public static final int SPELL = 14;
    public static final int OPPORTUNITY = 15;
    public static final int TEMP_HP = 16;
    public static final int ITEM = 17;

    // ... NAMES: one display name per kind ("hit", "miss", ...), for logs and tests

    private final int[] kind;
    private final int[] actor;
    private final int[] target;
    private final int[] natural;
    private final int[] total;
    private final int[] against;
    private final int[] amount;
    private final int[] ref;
    private final int capacity;
    private long written; // total ever written; the ring holds the last `capacity`
    private long dropped;
```

(Condensed: the `NAMES` array between the constants and the fields is left out.)
A record is a kind, the actor and target creatures, the natural roll and the total of a check,
the number it was compared with (an AC or a DC), an amount (damage, healing, rounds, a round
number) and a reference (a weapon, spell, condition or item row). Which fields mean what depends
on the kind; the table in §17.3 lists them.

### 17.2 The ring

```java
public void add(
        int kind,
        int actor,
        int target,
        int natural,
        int total,
        int against,
        int amount,
        int ref) {
    int i = (int) (written % capacity);
    if (written >= capacity) {
        dropped++;
    }
    this.kind[i] = kind;
    this.actor[i] = actor;
    this.target[i] = target;
    this.natural[i] = natural;
    this.total[i] = total;
    this.against[i] = against;
    this.amount[i] = amount;
    this.ref[i] = ref;
    written++;
}

/** Returns the sequence number the next record will get (readers remember where they are). */
public long next() {
    return written;
}

/** Returns the oldest sequence number still in the ring. */
public long oldest() {
    return Math.max(0, written - capacity);
}
```

- **Records are addressed by a `long` sequence number** that only grows, not by a slot; a reader
  remembers the sequence number it has read up to, and `written % capacity` maps a sequence to a
  slot (L00 §2.4). A `long` because a ten-hour session of fights could pass $2^{31}$ events only
  if it wrote 60,000 a second, but the cost of a `long` is nothing and the question never arises.
- **A full ring overwrites its oldest record** and counts the loss in `dropped`; it never grows and
  never blocks. The demo drains it every tick, so with a capacity of 1,024 (`Rules` creates it
  with that) nothing is ever dropped in practice; a reader that falls behind clamps its position to
  `oldest()` (§27.8).
- **Eight `int[1024]`s** are 8 × 4,112 bytes = 32,896 bytes, allocated once.

The accessors take a sequence number and check it against `[oldest, written)`:

```java
private int slot(long seq) {
    if (seq < oldest() || seq >= written) {
        throw new IndexOutOfBoundsException("event " + seq);
    }
    return (int) (seq % capacity);
}

public int kind(long seq) {
    return kind[slot(seq)];
}
```

### 17.3 What each kind carries

| Kind | actor | target | natural, total, against | amount | ref |
|---|---|---|---|---|---|
| `ATTACK_HIT`, `ATTACK_MISS`, `CRITICAL` | attacker | target | the roll, the total, the AC faced | the attack index | the equipped weapon's row (−1 for a monster's attack or an unarmed strike); a spell's row for a touch attack |
| `DAMAGE` | who dealt it | who took it | —, —, the damage type | the points lost | the weapon, spell or source |
| `HEAL` | healer | healed | — | the points gained | the item or spell |
| `SAVE_SUCCESS`, `SAVE_FAIL` | the caster | who saved | the roll, the total, the DC | the save kind (1, 2, 3) | the spell |
| `CONDITION_ON`, `CONDITION_OFF` | the creature | the creature | — | the rounds (on) | the condition row |
| `DYING`, `DEATH` | who caused it | who fell | — | the hit points | — |
| `STABLE` | the creature | the creature | 20 | — | — |
| `TURN` | the creature | the creature | — | — | — |
| `ROUND` | −1 | −1 | — | the round number | — |
| `SPELL` | caster | the first target | —, —, the DC | the number of targets | the spell row |
| `OPPORTUNITY` | attacker | provoker | — | — | — |
| `TEMP_HP` | actor | target | — | the points | the source |
| `ITEM` | user | target | — | — | the item row |

A reader that formats the kinds it knows and ignores the rest never breaks when a new kind is
added. The first events of any fight, as `EncounterTest`'s seven-creature fight writes them (seed
1), make the shape concrete:

```
seq  kind           actor  target  natural  total  against  amount  ref
0    condition on   0      0       0        0      0        -1      1     (flat-footed, indefinite)
...  (one per participant)
7    round          -1     -1      0        0      0        1       0
8    condition off  4      4       0        0      0        0       1     (warrior B's first turn)
9    turn           4      4       0        0      0        0       0
10   miss           4      0       8        12     19       0       -1    (8 + 4 = 12 vs Aldric's flat-footed AC 19)
11   condition off  2      2       0        0      0        0       1
12   turn           2      2       0        0      0        0       0     (Dara)
13   opportunity    4      2       0        0      0        0       0     (she walked away from warrior B)
14   hit            4      2       18       22     20       0       -1
15   damage         4      2       0        0      2        6       -1    (6 slashing)
16   critical       2      3       19       27     15       0       2     (Dara's first dagger on warrior A, flat-footed)
17   damage         2      3       0        0      1        5       2
```

Aldric's AC on line 10 is 19, not 20: he had not acted yet, so his +1 Dexterity was gone. Dara's
critical on line 16 confirms the §4 arithmetic: a natural 19 on a dagger (threat 19–20) that hit,
then a confirmation that also hit, and 5 piercing from `1d4-1` rolled twice.

---
## 18. Step 7: `Rules`, building creatures from content

`Rules` is the engine proper: 1,200 lines that build creatures, interpret operations, resolve
attacks, spells, damage and death, tick durations and write themselves into a save. It is built
here in five steps (§18–§22). Create `Rules.java` with its fields and constructor:

```java
public final class Rules {
    private static final Logger LOG = Log.logger("rules");

    /** Saving throws, as {@link RulesSchema#SAVES} and the {@code saveKind} parameters: 1, 2, 3. */
    public static final int SAVE_FORTITUDE = 1;

    public static final int SAVE_REFLEX = 2;
    public static final int SAVE_WILL = 3;

    /** The result of an attack roll. */
    public static final int MISS = 0;

    public static final int HIT = 1;
    public static final int CRIT = 2;

    /** Attack flags. */
    public static final int ATTACK_OPPORTUNITY = 1;

    public static final int ATTACK_TOUCH = 2;
    public static final int ATTACK_RANGED = 4;

    private final Content content;
    private final Creatures creatures;
    private final Modifiers modifiers;
    private final Conditions conditions;
    private final Events events;
    private final Rng rng;
    private final int flatFootedRow;
    private final int dyingRow;
    private final int deadRow;
    private final int unconsciousRow;
    private final int[] touchScratch = new int[BonusType.COUNT];
    private int lastNatural;
    private int lastTotal;

    public Rules(Content content, int capacity, long seed) {
        this.content = content;
        this.modifiers = new Modifiers(Math.max(64, capacity * 16));
        this.conditions = new Conditions(Math.max(32, capacity * 4));
        this.creatures = new Creatures(modifiers, conditions, capacity);
        this.events = new Events(1024);
        this.rng = new Rng(seed);
        flatFootedRow = content.find(RulesSchema.CONDITIONS, "flatFooted");
        dyingRow = content.find(RulesSchema.CONDITIONS, "dying");
        deadRow = content.find(RulesSchema.CONDITIONS, "dead");
        unconsciousRow = content.find(RulesSchema.CONDITIONS, "unconscious");
    }
```

- **Sizing.** One `capacity` sizes all three stores: sixteen modifiers and four condition
  instances per creature, with floors of 64 and 32. The numbers come from the content: the
  busiest character carries ten modifiers and a fight adds a few (a buff, a condition's
  penalties), and a creature rarely has more than *flat-footed*, one buff, one debuff and
  *dead* at once. The demo passes `Party.SIZE + enemies.count + 8` (§27.1).
- **The four condition rows** the engine must know are looked up by their ids once: *flat-footed*
  (removed at a turn's start), *dying* and *dead* (set by damage), *unconscious* (set when a dying
  creature stabilizes). A content set without one of them gets −1 and the engine skips that
  behaviour; the engine knows these rows, not what the conditions do, which stays in the content
  (their flags and effects).
- **`touchScratch`** is declared for touch-attack arithmetic; the current `armorClass` uses
  `totalOfType`, which needs no scratch, so the field is unused (a leftover, reported in the
  notes).
- **`lastNatural`, `lastTotal`** are the check's out-parameters (L02 §17).

### 18.1 A character

```java
public int buildCharacter(int characterRow, int team) {
    int c = creatures.create(content.name(RulesSchema.CHARACTERS, characterRow), team);
    fillCharacter(c, characterRow);
    return c;
}

/** Fills a freshly created creature {@code c} from character template {@code characterRow}. */
public void fillCharacter(int c, int characterRow) {
    creatures.setCharacterRow(c, characterRow);
    for (int a = 0; a < 6; a++) {
        creatures.setBaseAbility(c, a, content.characterAbility(characterRow, a));
    }
    int ancestry = content.characterAncestry(characterRow);
    if (ancestry >= 0) {
        applyAncestry(c, ancestry);
    }
    int level = content.characterLevel(characterRow);
    int cls = content.characterClass(characterRow);
    creatures.setLevel(c, level);
    if (cls >= 0) {
        applyClass(c, cls, level);
    }
    int feats = content.characterFeatsStart(characterRow);
    for (int i = 0; i < content.characterFeatsCount(characterRow); i++) {
        learnFeat(c, content.pool(feats + i));
    }
    equip(c, Creatures.SLOT_WEAPON, content.characterWeapon(characterRow));
    equip(c, Creatures.SLOT_ARMOR, content.characterArmor(characterRow));
    equip(c, Creatures.SLOT_SHIELD, content.characterShield(characterRow));
    int spells = content.characterSpellsStart(characterRow);
    for (int i = 0; i < content.characterSpellsCount(characterRow); i++) {
        creatures.spells(c).add(content.pool(spells + i));
    }
    applyEffects(
            c,
            content.characterEffectsStart(characterRow),
            content.characterEffectsCount(characterRow),
            Modifiers.source(Modifiers.SOURCE_CHARACTER, characterRow),
            Modifiers.PERMANENT,
            level,
            c,
            false);
    creatures.setHp(c, creatures.stat(c, Stat.MAX_HP));
}
```

The order is the order of §6: scores, then the ancestry's adjustments and size (so that the class
step sees the adjusted Constitution), then the class (BAB, saves, hit points, casting, features),
then feats, equipment, known spells, and the character's own always-on effects, each with the
source that will later identify its modifiers. Hit points are set *last*, from the resolved
maximum, because the maximum depends on everything above (Toughness, the class, Constitution).
`build` is split from `fill` for the save (§28): a load creates the creature at a given handle
and fills it from the *current* content.

### 18.2 A monster

```java
public void fillMonster(int c, int monsterRow) {
    creatures.setMonsterRow(c, monsterRow);
    for (int a = 0; a < 6; a++) {
        creatures.setBaseAbility(c, a, content.monsterAbility(monsterRow, a));
    }
    int ancestry = content.monsterAncestry(monsterRow);
    if (ancestry >= 0) {
        applyAncestry(c, ancestry);
    }
    creatures.setSize(c, content.monsterSize(monsterRow));
    creatures.setLevel(c, content.monsterLevel(monsterRow));
    creatures.setBaseAttackBonus(c, content.monsterBab(monsterRow));
    for (int s = 0; s < 3; s++) {
        creatures.setBaseSave(c, s, content.monsterSave(monsterRow, s));
    }
    creatures.setBaseMaxHp(c, content.monsterHp(monsterRow));
    creatures.setBaseSpeed(c, content.monsterSpeed(monsterRow));
    int natural = content.monsterNaturalArmor(monsterRow);
    if (natural != 0) {
        creatures.addModifier(
                c,
                Stat.AC,
                BonusType.NATURAL,
                natural,
                Modifiers.source(Modifiers.SOURCE_MONSTER, monsterRow),
                Modifiers.PERMANENT);
    }
    int feats = content.monsterFeatsStart(monsterRow);
    for (int i = 0; i < content.monsterFeatsCount(monsterRow); i++) {
        learnFeat(c, content.pool(feats + i));
    }
    creatures.setMonsterAttacks(
            c,
            content.monsterAttacksStart(monsterRow),
            content.monsterAttacksCount(monsterRow));
    applyEffects(
            c,
            content.monsterEffectsStart(monsterRow),
            content.monsterEffectsCount(monsterRow),
            Modifiers.source(Modifiers.SOURCE_MONSTER, monsterRow),
            Modifiers.PERMANENT,
            creatures.level(c),
            c,
            false);
    creatures.setDiesAtZero(c, true);
    creatures.setHp(c, creatures.stat(c, Stat.MAX_HP));
}
```

A stat block states what a class derives: BAB, the three base saves, hit points and speed are
copied, the size is set after the ancestry (a monster's own size overrides its lineage's), natural
armor becomes a *natural* modifier, and the attacks are a range of rows the monster's `NESTED`
field points at. `setDiesAtZero(c, true)` is what makes every monster of M15 undead-like (§7);
a living monster would need a field in the schema, which "Not in M15" leaves for later.

### 18.3 Ancestry and class

```java
private void applyAncestry(int c, int row) {
    creatures.setAncestryRow(c, row);
    for (int a = 0; a < 6; a++) {
        creatures.setBaseAbility(
                c, a, creatures.baseAbility(c, a) + content.ancestryAbility(row, a));
    }
    creatures.setSize(c, content.ancestrySize(row));
    creatures.setBaseSpeed(c, content.ancestrySpeed(row));
    applyEffects(
            c,
            content.ancestryEffectsStart(row),
            content.ancestryEffectsCount(row),
            Modifiers.source(Modifiers.SOURCE_ANCESTRY, row),
            Modifiers.PERMANENT,
            1,
            c,
            false);
}
```

The ancestry's adjustments are added to the *base* scores (a hearthkin's +2 Dexterity is part of
Dara's 20, not a modifier), which is why `CreaturesTest`'s comments quote the adjusted scores. Its
always-on effects (a stoneborn's +2 racial to Fortitude) are modifiers with the ancestry as source.
The size comes from the ancestry and the engine adds the size modifier itself in `stat`, which is
the pitfall of §32: the first version of the hearthkin content also carried a `size` bonus to AC,
so Dara got +2 for being small until the hand-computed test caught it.

```java
private void applyClass(int c, int row, int level) {
    creatures.setClassRow(c, row);
    creatures.setBaseAttackBonus(c, Content.baseAttack(content.classBab(row), level));
    for (int s = 0; s < 3; s++) {
        creatures.setBaseSave(c, s, Content.baseSave(content.classSave(row, s), level));
    }
    int die = content.classHitDie(row);
    int con = creatures.abilityModifier(c, Stat.CON);
    int hp = die + (level - 1) * (die / 2 + 1) + Math.max(1 - level, con * level);
    creatures.setBaseMaxHp(c, Math.max(level, hp));
    creatures.setCasting(c, content.classCasting(row), content.classCastingAbility(row));
    int features = content.classFeaturesStart(row);
    for (int i = 0; i < content.classFeaturesCount(row); i++) {
        int f = features + i;
        if (content.featureLevel(f) <= level) {
            applyEffects(
                    c,
                    content.featureEffectsStart(f),
                    content.featureEffectsCount(f),
                    Modifiers.source(Modifiers.SOURCE_CLASS, f),
                    Modifiers.PERMANENT,
                    level,
                    c,
                    false);
        }
    }
}
```

§6.1 and §6.2 as code. `die / 2 + 1` is the rounded-up average of the die; `con * level` the
Constitution per level; the two `max`es the floors. A class feature applies when its level is
reached, with the *feature row* as source (not the class), so that two features of one class are
two sources.

### 18.4 Feats and prerequisites

```java
public void learnFeat(int c, int row) {
    if (row < 0 || creatures.feats(c).contains(row)) {
        return;
    }
    creatures.feats(c).add(row);
    int source = Modifiers.source(Modifiers.SOURCE_FEAT, row);
    applyEffects(
            c,
            content.featEffectsStart(row),
            content.featEffectsCount(row),
            source,
            Modifiers.PERMANENT,
            creatures.level(c),
            c,
            false);
    if ((content.featFlags(row) & Content.FEAT_REFLEXES) != 0) {
        int dex = creatures.abilityModifier(c, Stat.DEX);
        if (dex > 0) {
            creatures.addModifier(
                    c, Stat.AOO, BonusType.UNTYPED, dex, source, Modifiers.PERMANENT);
        }
    }
}
```

A feat is its operations, applied once with the feat as source; learning it twice is a no-op. The
*Reflexes* flag is the one feat behaviour the engine computes: "extra attacks of opportunity equal
to the Dexterity modifier" depends on the creature, so it cannot be a constant in the content, and
becomes a modifier to the `AOO` statistic (note: computed when learned, so a later Dexterity change
does not update it; a limitation worth an exercise).

```java
public boolean meetsPrerequisites(int c, int row) {
    if (creatures.baseAttackBonus(c) < content.featMinBab(row)) {
        return false;
    }
    for (int a = 0; a < 6; a++) {
        if (creatures.ability(c, a) < content.featMinAbility(row, a)) {
            return false;
        }
    }
    int req = content.featRequiresStart(row);
    for (int i = 0; i < content.featRequiresCount(row); i++) {
        if (!creatures.feats(c).contains(content.pool(req + i))) {
            return false;
        }
    }
    return true;
}
```

Minimum BAB, minimum scores (the six `minStr`…`minCha` fields, consecutive in the schema) and
required feats. `learnFeat` does *not* call it: the content may grant a feat the character does not
qualify for (a monster's stat block, a class feature's `grantFeat`), and it is the level-up UI's
job to ask. The test `itemsHealAndFeatsHavePrerequisites` shows Cleave refused until Power attack
is learned, and Power attack refused to Fenna (Strength 12 < 13).

### 18.5 Equipment

```java
public void equip(int c, int slot, int row) {
    int source = Modifiers.source(Modifiers.SOURCE_ITEM, slot);
    creatures.removeModifiers(c, source);
    creatures.setEquipmentRow(c, slot, row);
    if (row >= 0) {
        int kind = content.itemKind(row);
        if (kind == Content.ITEM_ARMOR) {
            creatures.addModifier(
                    c,
                    Stat.AC,
                    BonusType.ARMOR,
                    content.itemArmor(row),
                    source,
                    Modifiers.PERMANENT);
        } else if (kind == Content.ITEM_SHIELD) {
            creatures.addModifier(
                    c,
                    Stat.AC,
                    BonusType.SHIELD,
                    content.itemArmor(row),
                    source,
                    Modifiers.PERMANENT);
        }
        applyEffects(
                c,
                content.itemEffectsStart(row),
                content.itemEffectsCount(row),
                source,
                Modifiers.PERMANENT,
                creatures.level(c),
                c,
                false);
    }
    int weapon = creatures.equipment(c, Creatures.SLOT_WEAPON);
    int armor = creatures.equipment(c, Creatures.SLOT_ARMOR);
    creatures.setWeaponTraits(
            c,
            weapon >= 0 && content.itemFinesse(weapon),
            weapon >= 0 && content.itemHands(weapon) >= 2,
            armor >= 0 ? content.itemMaxDex(armor) : 99);
}
```

The source is the *slot*, so the first line removes whatever the slot held, and `equip(c, slot,
-1)` is "unequip". An armor's or shield's AC bonus is typed `ARMOR` or `SHIELD` so that §6.6's rule
applies (a *shield* spell's +4 shield bonus does not add to a heavy shield's +2; the larger wins);
any other effects the item declares (a ring's deflection bonus) are operations. The last statement
recomputes the three weapon switches from whatever is now in the weapon and armor slots, whichever
slot changed.

## 19. Step 8: `Rules`, effects and conditions

### 19.1 The interpreter

Every definition's behaviour arrives here as a range of operations (§11.2). One method, one
`switch`:

```java
public void applyEffects(
        int target,
        int start,
        int count,
        int source,
        int rounds,
        int casterLevel,
        int actor,
        boolean halfDamage) {
    for (int i = start; i < start + count; i++) {
        int op = content.op(i, RulesSchema.OP_OP);
        int value = content.op(i, RulesSchema.OP_VALUE);
        int dice = content.op(i, RulesSchema.OP_DICE);
        int opRounds = content.op(i, RulesSchema.OP_ROUNDS);
        int duration = opRounds > 0 ? opRounds : rounds;
        switch (op) {
            case Content.OP_MODIFY ->
                    creatures.addModifier(
                            target,
                            content.op(i, RulesSchema.OP_STAT),
                            content.op(i, RulesSchema.OP_TYPE),
                            value,
                            source,
                            duration);
            case Content.OP_DAMAGE -> {
                int amount = Dice.roll(dice, rng);
                if (halfDamage) {
                    amount /= 2;
                }
                damage(
                        target,
                        amount,
                        content.op(i, RulesSchema.OP_DAMAGE_TYPE),
                        actor,
                        source);
            }
            case Content.OP_HEAL -> heal(target, Dice.roll(dice, rng), actor, source);
            case Content.OP_TEMP_HP -> {
                int amount = Dice.roll(dice, rng);
                if (amount > creatures.tempHp(target)) {
                    creatures.setTempHp(target, amount, duration);
                    events.add(Events.TEMP_HP, actor, target, 0, 0, 0, amount, source);
                }
            }
            case Content.OP_CONDITION ->
                    applyCondition(
                            target,
                            Content.refRow(content.op(i, RulesSchema.OP_REF)),
                            duration > 0 ? duration : Conditions.INDEFINITE);
            case Content.OP_REMOVE_CONDITION ->
                    removeCondition(target, Content.refRow(content.op(i, RulesSchema.OP_REF)));
            case Content.OP_GRANT_FEAT ->
                    learnFeat(target, Content.refRow(content.op(i, RulesSchema.OP_REF)));
            case Content.OP_EXTRA_ATTACK ->
                    creatures.addModifier(
                            target,
                            Stat.EXTRA_ATTACKS,
                            BonusType.UNTYPED,
                            value,
                            source,
                            duration);
            case Content.OP_SET_SPEED -> creatures.setBaseSpeed(target, value);
            default -> {}
        }
    }
}
```

The parameters are the *context* the operations lack: who it happens to (`target`), why
(`source`), for how long (`rounds`, overridden by an operation's own `rounds` when it has one),
the caster level (kept for the day dice scale with it; the content writes `5d6` out for now), who
did it (`actor`, for the events) and whether a successful save halves the damage. Reading the
cases:

- `MODIFY` and `EXTRA_ATTACK` add a modifier with the given source and duration; a feat's
  `PERMANENT` becomes "until the feat is removed", a spell's `rounds` becomes a countdown.
- `DAMAGE` rolls here, halves with integer division when told to (L02 §9 computes what that
  rounding costs), and delegates to `damage` (§20), which handles temporary hit points, dying and
  death.
- `TEMP_HP` does not stack: the larger grant replaces the smaller, the tabletop rule.
- `CONDITION` applies the referenced condition for the duration, or indefinitely when there is
  none (*haste* at caster level 5 is 5 rounds; *dead* has none).
- `GRANT_FEAT` lets a class feature teach a feat.
- `default -> {}`: an unknown operation (a newer file read by an older engine) is skipped, not an
  error, because the cooker and `RulesData.validate` already refused anything outside the schema.

`removeEffects(c, source)` is the inverse for everything `MODIFY` did: `creatures.removeModifiers`.
Damage and conditions, once applied, are history.

### 19.2 Conditions on a creature

```java
public int applyCondition(int c, int row, int rounds) {
    IntList list = creatures.conditionHandles(c);
    for (int i = 0; i < list.size(); i++) {
        int h = list.get(i);
        if (conditions.condition(h) == row) {
            int left = conditions.rounds(h);
            if (rounds == Conditions.INDEFINITE
                    || (left != Conditions.INDEFINITE && rounds > left)) {
                conditions.setRounds(h, rounds);
            }
            return h;
        }
    }
    int h = conditions.add(c, row, rounds);
    list.add(h);
    applyEffects(
            c,
            content.conditionEffectsStart(row),
            content.conditionEffectsCount(row),
            Modifiers.source(Modifiers.SOURCE_CONDITION, h),
            Modifiers.PERMANENT,
            creatures.level(c),
            c,
            false);
    refreshFlags(c);
    events.add(Events.CONDITION_ON, c, c, 0, 0, 0, rounds, row);
    return h;
}
```

A condition already present is not applied twice: its instance keeps the longer duration (an
indefinite one always wins; a timed one wins over a shorter timed one), and its effects are not
doubled, which `conditionsApplyTheirEffectsForTheirDuration` checks by applying *shaken* three
times and asserting one −2. A new instance gets its effects with the *instance handle* as source
and `PERMANENT` as duration: the modifiers live exactly as long as the instance, whose rounds are
the only countdown.

```java
private void endCondition(int c, int index) {
    IntList list = creatures.conditionHandles(c);
    int h = list.get(index);
    int row = conditions.condition(h);
    creatures.removeModifiers(c, Modifiers.source(Modifiers.SOURCE_CONDITION, h));
    conditions.remove(h);
    list.removeAt(index);
    refreshFlags(c);
    events.add(Events.CONDITION_OFF, c, c, 0, 0, 0, 0, row);
}

// ... hasCondition(c, row): the same search over the instance list, returning a boolean

private void refreshFlags(int c) {
    int f = 0;
    IntList list = creatures.conditionHandles(c);
    for (int i = 0; i < list.size(); i++) {
        f |= content.conditionFlags(conditions.condition(list.get(i)));
    }
    creatures.setFlags(c, f);
}
```

(Condensed: `hasCondition`, which sits between the two in the file, is described below.)
`endCondition` undoes `applyCondition` in the same order, and `refreshFlags` recomputes the OR of
§8 from the remaining instances. `removeCondition(c, row)` finds the instance and calls
`endCondition`; `hasCondition(c, row)` is the same search. The two predicates the rest of the
module uses are then two bit tests:

```java
/** Returns whether {@code c} can take actions (alive, conscious, not stunned). */
public boolean canAct(int c) {
    return creatures.isAlive(c) && !creatures.hasFlag(c, Content.FLAG_CANNOT_ACT);
}

/** Returns whether {@code c} is dead. */
public boolean isDead(int c) {
    return !creatures.isAlive(c) || creatures.hasFlag(c, Content.FLAG_DEAD);
}
```

## 20. Step 9: `Rules`, damage, healing and death

```java
public int damage(int target, int amount, int type, int actor, int ref) {
    if (amount <= 0 || isDead(target)) {
        return 0;
    }
    int temp = creatures.tempHp(target);
    if (temp > 0) {
        int absorbed = Math.min(temp, amount);
        creatures.setTempHp(target, temp - absorbed, creatures.tempHpRounds(target));
        amount -= absorbed;
    }
    int before = creatures.hp(target);
    creatures.setHp(target, before - amount);
    events.add(Events.DAMAGE, actor, target, 0, 0, type, amount, ref);
    checkDeath(target, actor);
    return amount;
}
```

§7 in order: nothing happens to the dead; temporary hit points absorb up to their value and keep
their rounds; the rest comes off the hit points, which may go negative; the event records the
points *actually lost* from hit points (so a fully absorbed hit logs 0); then the state check:

```java
private void checkDeath(int target, int actor) {
    int hp = creatures.hp(target);
    if (hp > 0) {
        return;
    }
    boolean dies = creatures.diesAtZero(target) || hp <= -creatures.ability(target, Stat.CON);
    if (dies) {
        if (deadRow >= 0 && !hasCondition(target, deadRow)) {
            if (dyingRow >= 0) {
                removeCondition(target, dyingRow);
            }
            applyCondition(target, deadRow, Conditions.INDEFINITE);
        }
        events.add(Events.DEATH, actor, target, 0, 0, 0, hp, 0);
    } else if (dyingRow >= 0 && !hasCondition(target, dyingRow)) {
        applyCondition(target, dyingRow, Conditions.INDEFINITE);
        events.add(Events.DYING, actor, target, 0, 0, 0, hp, 0);
    }
}
```

`-creatures.ability(target, Stat.CON)` is minus the *score* (14 for Aldric: dead at −14), with
its modifiers, so a *raging* berserker (+4 morale to Constitution) dies four points later. *Dead*
replaces *dying*; both are indefinite conditions whose flags (`cannotAct`, `helpless`,
`loseDexToAc`, `dying` or `dead`) do the rest through `canAct`, `isDead` and `stat`.

```java
public int heal(int target, int amount, int actor, int ref) {
    if (amount <= 0 || isDead(target)) {
        return 0;
    }
    int max = creatures.stat(target, Stat.MAX_HP);
    int before = creatures.hp(target);
    int after = Math.min(max, before + amount);
    creatures.setHp(target, after);
    events.add(Events.HEAL, actor, target, 0, 0, 0, after - before, ref);
    if (after > 0) {
        if (dyingRow >= 0) {
            removeCondition(target, dyingRow);
        }
        if (unconsciousRow >= 0) {
            removeCondition(target, unconsciousRow);
        }
    }
    return after - before;
}
```

Healing is capped at the maximum and returns what it actually restored (`temporaryHitPointsAbsorbFirstAndExpire`
calls `heal(c, 10, -1, 0)` on a creature 3 below its maximum and asserts 3); above zero it ends *dying* and *unconscious*.
`dyingLosesAPointARoundUntilStabilizedOrHealed` (§29) walks Aldric from 47 to −2 (dying), −3 (a
turn without a 20), stabilized on a 20 (unconscious, no longer losing), healed to 1 (awake), and
to −14 (dead, and neither heal nor damage touches him again).

## 21. Step 10: `Rules`, checks, saves, attacks and spells

`check` and `attack` are built in Lesson 2 (§17 and §18 there); only what surrounds them is new
here.

### 21.1 Saves

```java
public boolean save(int c, int saveKind, int dc, int actor, int ref) {
    int stat =
            switch (saveKind) {
                case SAVE_FORTITUDE -> Stat.FORTITUDE;
                case SAVE_REFLEX -> Stat.REFLEX;
                default -> Stat.WILL;
            };
    boolean ok = check(creatures.stat(c, stat), dc);
    events.add(
            ok ? Events.SAVE_SUCCESS : Events.SAVE_FAIL,
            actor,
            c,
            lastNatural,
            lastTotal,
            dc,
            saveKind,
            ref);
    return ok;
}
```

The save kinds 1, 2, 3 are the schema's `SAVES` order (`none` is 0), so a spell's `save` field
can be passed straight in. The save is `check` with the stat picked by the kind, and the event
carries the natural, the total, the DC and the kind (in `amount`).

### 21.2 Full attacks and the attack bonus

```java
public int fullAttackCount(int c) {
    int bab = creatures.baseAttackBonus(c);
    int n = 1 + Math.max(0, (bab - 1) / 5);
    return Math.min(6, n + creatures.stat(c, Stat.EXTRA_ATTACKS));
}

/** The attack bonus of attack {@code index} of a full attack: {@code -5} per iterative. */
public int attackBonus(int c, int index) {
    int bab = creatures.baseAttackBonus(c);
    int iteratives = 1 + Math.max(0, (bab - 1) / 5);
    int penalty = index < iteratives ? 5 * index : 0; // extras from effects come at full bonus
    return creatures.stat(c, Stat.ATTACK) - penalty;
}
```

§5 as code: the iterative count from the BAB, extras from the statistic, a cap of six, and the −5
per index only while the index names an iterative. Dara (BAB 3, one extra): `iteratives = 1`, so
index 1 is an extra and `penalty = 0`: +8/+8.

### 21.3 The armor class an attack faces

```java
public int armorClass(int target, int flags) {
    int ac = creatures.stat(target, Stat.AC);
    if ((flags & ATTACK_TOUCH) != 0) {
        IntList h = creatures.modifierHandles(target);
        ac -=
                modifiers.totalOfType(h, Stat.AC, BonusType.ARMOR)
                        + modifiers.totalOfType(h, Stat.AC, BonusType.SHIELD)
                        + modifiers.totalOfType(h, Stat.AC, BonusType.NATURAL);
    }
    return ac;
}
```

The flat-footed and helpless cases need nothing here: they are condition flags that `stat`
already honours. A touch attack subtracts the three physical bonus types, each as the stacking
rule computed it (the largest of its type), which is why `totalOfType` exists (§13.4). The
skeletal warrior: 17 − 3 (armor) − 0 − 2 (natural) = 12.

### 21.4 The attack: where the numbers come from, and the open decision

Lesson 2 §18 walks the whole of `attack`. Its first stage decides where the dice and the bonus
come from, and one line of it is the owner's open decision of §5:

```java
if (monsterAttack >= 0 && creatures.monsterAttackCount(attacker) > 0) {
    int row = monsterAttack + index % creatures.monsterAttackCount(attacker);
    dice = content.attackDamage(row);
    threat = content.attackThreat(row);
    multiplier = content.attackCritical(row);
    type = content.attackDamageType(row);
    // A stat block's attack bonus already includes Strength and size: use it plus
    // modifiers.
    attackBonus =
            content.attackBonus(row)
                    + creatures.bonus(attacker, Stat.ATTACK)
                    - (index > 0 ? 5 * index : 0);
    damageBonus = creatures.bonus(attacker, Stat.DAMAGE); // the dice carry Strength
} else if (weapon >= 0) {
    dice = content.itemDamage(weapon);
    threat = content.itemThreat(weapon);
    multiplier = content.itemCritical(weapon);
    type = content.itemDamageType(weapon);
    attackBonus = attackBonus(attacker, index);
} else {
    dice = Dice.pack(1, 3, 0); // an unarmed strike
    threat = 20;
    multiplier = 2;
    type = 0;
    attackBonus = attackBonus(attacker, index);
}
```

A monster's $i$th attack uses its $(i \bmod \text{attacks})$th attack row, so a creature with one
row and two attacks swings the same knife twice. Its bonus is the row's, plus modifiers only
(`creatures.bonus`, not `stat`: Strength and size are already in the printed number), **minus
5 × index for every attack after the first**, whereas a character's `attackBonus(attacker, index)`
subtracts only for iteratives. That is the asymmetry: the skeletal stalker (row bonus +4, feat
−2) attacks at +2 and −3; Dara at +8 and +8. Whether to change the monster branch to the
character's rule is the owner's decision and it has not been made; L02 §18.5 records the
behaviour, L02 exercise 3 prices it, and exercise 7 below shows the one-line change and the test
it would alter.

### 21.5 Spells

```java
/** The DC of {@code caster}'s spell {@code row}: {@code 10 + spell level + casting ability}. */
public int spellDc(int caster, int row) {
    int level = Math.max(0, content.spellLevel(row, creatures.casting(caster)));
    return 10 + level + creatures.abilityModifier(caster, creatures.castingAbility(caster));
}

public boolean hasSlot(int caster, int row) {
    int cls = creatures.classRow(caster);
    if (cls < 0) {
        return true;
    }
    int level = content.spellLevel(row, creatures.casting(caster));
    if (level < 0) {
        return false;
    }
    int slots = content.classSlots(cls, creatures.level(caster), level);
    return creatures.slotsUsed(caster, level) < slots;
}
```

§6.7 as code. A spell has a level on the arcane list and one on the divine list (−1 when absent:
*cure light wounds* is divine 1 and not arcane), and `creatures.casting(caster)` says which list
the caster reads; a creature without a class (a monster caster made by hand) casts for free, and
a spell not on the caster's list cannot be cast at all. Then the cast:

```java
public boolean castSpell(int caster, int row, int[] targets, int n) {
    if (!canAct(caster) || !hasSlot(caster, row)) {
        return false;
    }
    int cls = creatures.classRow(caster);
    int level = content.spellLevel(row, creatures.casting(caster));
    if (cls >= 0 && level >= 0) {
        creatures.setSlotsUsed(caster, level, creatures.slotsUsed(caster, level) + 1);
    }
    int casterLevel = creatures.level(caster);
    int dc = spellDc(caster, row);
    int rounds = content.spellRounds(row, casterLevel);
    int source = Modifiers.source(Modifiers.SOURCE_SPELL, row);
    events.add(Events.SPELL, caster, n > 0 ? targets[0] : caster, 0, 0, dc, n, row);
    int attackKind = content.spellAttack(row);
    int save = content.spellSave(row);
    boolean halves = content.spellSaveResult(row) == Content.SAVE_HALF;
    for (int i = 0; i < n; i++) {
        int t = targets[i];
        if (!creatures.isAlive(t)) {
            continue;
        }
        if (attackKind != Content.SPELL_ATTACK_NONE) {
            int ac = armorClass(t, ATTACK_TOUCH);
            boolean hit = check(creatures.stat(caster, Stat.ATTACK), ac);
            events.add(
                    hit ? Events.ATTACK_HIT : Events.ATTACK_MISS,
                    caster,
                    t,
                    lastNatural,
                    lastTotal,
                    ac,
                    0,
                    row);
            if (!hit) {
                continue;
            }
        }
        boolean half = false;
        if (save != Content.SPELL_SAVE_NONE) {
            boolean saved = save(t, save, dc, caster, row);
            if (saved) {
                if (!halves) {
                    continue; // negated
                }
                half = true;
            }
        }
        creatures.removeModifiers(t, source); // recasting replaces the previous instance
        applyEffects(
                t,
                content.spellEffectsStart(row),
                content.spellEffectsCount(row),
                source,
                rounds > 0 ? rounds : Modifiers.PERMANENT,
                casterLevel,
                caster,
                half);
    }
    return true;
}
```

The game resolves range and area (the demo's fireball is a cylinder overlap in the crowd, §27.5)
and hands over the targets; the engine does the rest per target, in the tabletop's order: the slot
is spent whether or not anything is hit (the test `magicMissileNeverMissesAndRayOfFrostNeedsATouchAttack`
asserts that a missed ray still cost its slot); a spell with an attack roll (a ray) rolls a touch
attack against the touch AC and skips targets it misses; a spell with a save rolls it, and a
success either *negates* (skip the target) or *halves* (pass `half` to the effects); the spell's
duration from the content (`rounds + roundsPerLevel × casterLevel`: *haste* is 0 + 1 × 5) becomes
the effects' duration, or `PERMANENT` for an instantaneous one whose damage is already done; and
recasting removes the previous cast's modifiers first, so a *shield* cast again gives one +4 with
a fresh ten rounds, never two (`buffsLastTheirRoundsAndRecastingReplaces`).

`useItem(user, row, target)` is the consumable's case: the item's `use` operations applied at
once, with an item source whose id is `0xFFFF` (not a slot, so unequipping never removes a
potion's effect).

## 22. Step 11: `Rules`, turns and attacks of opportunity

```java
public void startTurn(int c) {
    creatures.setActed(c, true);
    creatures.setAooUsed(c, 0);
    if (flatFootedRow >= 0) {
        removeCondition(c, flatFootedRow);
    }
    events.add(Events.TURN, c, c, 0, 0, 0, 0, 0);
}
```

Three things happen at the start of a creature's turn: it has now acted in this fight; its attacks
of opportunity for the round are back; and it is no longer flat-footed. That last line is the 14 →
15 of §1.

```java
public void endTurn(int c) {
    modifiers.tick(creatures.modifierHandles(c));
    IntList list = creatures.conditionHandles(c);
    for (int i = list.size() - 1; i >= 0; i--) {
        int h = list.get(i);
        int r = conditions.rounds(h);
        if (r > 0) {
            conditions.setRounds(h, r - 1);
            if (r - 1 == 0) {
                endCondition(c, i);
            }
        }
    }
    int tr = creatures.tempHpRounds(c);
    if (creatures.tempHp(c) > 0 && tr > 0) {
        if (tr == 1) {
            creatures.setTempHp(c, 0, 0);
        } else {
            creatures.setTempHp(c, creatures.tempHp(c), tr - 1);
        }
    }
    if (dyingRow >= 0 && hasCondition(c, dyingRow) && !isDead(c)) {
        if (rng.d20() == 20) {
            removeCondition(c, dyingRow);
            if (unconsciousRow >= 0) {
                applyCondition(c, unconsciousRow, Conditions.INDEFINITE);
            }
            events.add(Events.STABLE, c, c, 20, 0, 0, 0, 0);
        } else {
            creatures.setHp(c, creatures.hp(c) - 1);
            events.add(Events.DAMAGE, c, c, 0, 0, 10, 1, 0);
            checkDeath(c, c);
        }
    }
}
```

The end of a turn is where every duration of §8 ticks: the creature's own modifiers (through
`Modifiers.tick`), its condition instances (the same backwards loop, calling `endCondition` at
zero, which removes the instance's modifiers and refreshes the flags), its temporary hit points,
and then the dying roll: a natural 20 stabilizes (the creature becomes *unconscious* instead of
*dying*), anything else costs a hit point, logged as a `DAMAGE` with actor equal to target and
type 10 (*untyped*) so the demo's log can tell it apart from a blow (§27.8), and may kill.

```java
public int opportunityAttacksLeft(int c) {
    if (!canAct(c) || creatures.hasFlag(c, Content.FLAG_FLAT_FOOTED)) {
        return 0;
    }
    return Math.max(0, creatures.stat(c, Stat.AOO) - creatures.aooUsed(c));
}

public int attackOfOpportunity(int attacker, int provoker) {
    if (opportunityAttacksLeft(attacker) == 0 || !canAct(attacker)) {
        return MISS;
    }
    creatures.setAooUsed(attacker, creatures.aooUsed(attacker) + 1);
    events.add(Events.OPPORTUNITY, attacker, provoker, 0, 0, 0, 0, 0);
    return attack(attacker, provoker, 0, 0, ATTACK_OPPORTUNITY);
}

/** Rolls initiative for {@code c}: {@code d20 + initiative}. */
public int rollInitiative(int c) {
    return rng.d20() + creatures.stat(c, Stat.INITIATIVE);
}
```

An attack of opportunity is a normal attack at index 0 (full bonus) with a flag the game may
inspect, counted against the `AOO` statistic (1, plus *Reflexes*) and reset by `startTurn`.
`setFlatFooted(c)` applies the flat-footed condition indefinitely; the encounter calls it at the
start of a fight and when a creature joins. `rollInitiative` is the §9.1 roll.

---
## 23. Step 12: `ActionBudget`

The budget of §9.2 is four bits per creature plus one flag:

```java
public final class ActionBudget {
    /** A standard action: an attack, a spell, an item. */
    public static final int STANDARD = 1;

    /** A move action: moving up to the speed, standing up, drawing. */
    public static final int MOVE = 2;

    /** A swift action: one quick thing a round. */
    public static final int SWIFT = 4;

    /** A full-round action: a full attack, a charge; spends standard and move. */
    public static final int FULL_ROUND = STANDARD | MOVE;

    /** A 5-foot step: free once a turn when no other movement is taken. */
    public static final int STEP = 8;

    /** The whole budget of a fresh turn. */
    public static final int ALL = STANDARD | MOVE | SWIFT | STEP;

    private int[] left;
    private boolean[] moved;
```

`FULL_ROUND` is not a fourth bit but the OR of two (L00 §6.2): spending it clears both, and
`has(c, FULL_ROUND)` asks whether *both* are still there. `left[c]` is what creature `c` may
still do; `moved[c]` whether it has moved on the ground this turn, which forbids the step.

```java
public boolean has(int c, int action) {
    if (c >= left.length) {
        return false;
    }
    if (action == STEP) {
        return (left[c] & STEP) != 0 && !moved[c];
    }
    return (left[c] & action) == action;
}

boolean use(int c, int action) {
    if (!has(c, action)) {
        if (action == MOVE && has(c, STANDARD)) {
            left[c] &= ~STANDARD; // the second move action comes out of the standard one
            return true;
        }
        return false;
    }
    left[c] &= ~action;
    if (action == STEP) {
        moved[c] = true; // no further movement after a step
    }
    return true;
}

void movedThisTurn(int c) {
    ensure(c);
    moved[c] = true;
    left[c] &= ~STEP;
}
```

- `(left[c] & action) == action` tests all the bits of a composite action at once.
- `use` is where "a standard action may be traded for a move action" lives: a second `MOVE` when
  the move bit is gone is paid with the standard bit. The reverse (`STANDARD` when only `MOVE` is
  left) fails.
- A step counts as movement (`moved`), and movement forbids a step: the two directions of the
  tabletop's "5-foot step only when you take no other movement".
- `reset(c)` sets `ALL` and clears `moved`; `clear(c)` sets 0 for a creature that cannot act.
  Both and `use` are package-private: the game goes through `Encounter.useAction`, which first
  asks `canAct`.

`write` and `read` serialize `left[0, highWaterMark)` and `moved` for the save (§28). The arrays
grow with `ensure(c)` when a creature handle is past them; `Encounter` sizes the budget from the
creature capacity so that never happens in a fight.

## 24. Step 13: `Encounter`

### 24.1 The fight's state

```java
public final class Encounter {
    private final Rules rules;
    private final ActionBudget budget;
    private final IntList order; // creatures in initiative order
    private final IntList initiative; // parallel to order
    private final IntList joined; // parallel: join sequence, the last tie-break
    private int joinCounter;
    private int round;
    private int turnIndex = -1;
    private boolean active;
    private boolean currentLeft; // the creature at turnIndex left; do not end its turn again
    private int[] teamScratch = new int[8];

    public Encounter(Rules rules) {
        this.rules = rules;
        int n = Math.max(16, rules.creatures().capacity());
        budget = new ActionBudget(n);
        order = new IntList(n);
        initiative = new IntList(n);
        joined = new IntList(n);
    }
```

Three parallel `IntList`s hold the order: the creature, its initiative total, and the sequence
number it joined with (the last tie-break of §9.1). `turnIndex` is a position in `order`, −1 before
the first turn; `current()` is `order.get(turnIndex)` or −1. The lists and the budget are sized
from the creature capacity, for the reason §30 tells (a default-sized list grew from 133 to 200
entries when the 134th skeleton joined a perf-scene fight, and that growth was an allocation on
the game thread).

### 24.2 Starting, joining, leaving

```java
public void add(int creature) {
    if (active) {
        join(creature);
        return;
    }
    if (!contains(creature)) {
        order.add(creature);
        initiative.add(0);
        joined.add(joinCounter++);
    }
}

public void start() {
    for (int i = 0; i < order.size(); i++) {
        int c = order.get(i);
        initiative.set(i, rules.rollInitiative(c));
        rules.setFlatFooted(c);
        budget.clear(c);
    }
    sort();
    round = 1;
    turnIndex = -1;
    currentLeft = false;
    active = true;
    rules.events().add(Events.ROUND, -1, -1, 0, 0, 0, round, 0);
}
```

`start` rolls for everyone in the order they were added (which fixes the sequence of random
numbers a test's oracle must reproduce), makes everyone flat-footed with an empty budget, sorts,
and begins round 1 with no current turn: the first `nextTurn` starts the first creature.

```java
private boolean before(int init, int dex, int seq, int i) {
    int oi = initiative.get(i);
    if (init != oi) {
        return init > oi;
    }
    int od = rules.creatures().ability(order.get(i), Stat.DEX);
    if (dex != od) {
        return dex > od;
    }
    return seq < joined.get(i);
}

/** Insertion sort of the three parallel lists (small fights; no allocation). */
private void sort() {
    for (int i = 1; i < order.size(); i++) {
        int c = order.get(i);
        int init = initiative.get(i);
        int seq = joined.get(i);
        int dex = rules.creatures().ability(c, Stat.DEX);
        int j = i - 1;
        while (j >= 0 && before(init, dex, seq, j)) {
            order.set(j + 1, order.get(j));
            initiative.set(j + 1, initiative.get(j));
            joined.set(j + 1, joined.get(j));
            j--;
        }
        order.set(j + 1, c);
        initiative.set(j + 1, init);
        joined.set(j + 1, seq);
    }
}
```

`before` is the three-level comparison of §9.1 (higher initiative, then higher Dexterity score,
then earlier join), and `sort` is an insertion sort over the three lists at once: $O(n^2)$ moves,
which for a fight of ten is at most a few hundred `int` copies, and no comparator object, no boxing, no
allocation, which `Arrays.sort` on an `Integer[]` or a `List` would cost. The same `before` places
a latecomer:

```java
public void join(int creature) {
    if (contains(creature)) {
        return;
    }
    int init = rules.rollInitiative(creature);
    int seq = joinCounter++;
    int at = order.size();
    for (int i = 0; i < order.size(); i++) {
        if (before(init, rules.creatures().ability(creature, Stat.DEX), seq, i)) {
            at = i;
            break;
        }
    }
    order.insert(at, creature);
    initiative.insert(at, init);
    joined.insert(at, seq);
    if (at <= turnIndex) {
        turnIndex++; // the current creature keeps its turn
    }
    rules.setFlatFooted(creature);
    budget.clear(creature);
}
```

A creature that slots in *before* the current turn index shifts the index by one so that the
creature whose turn it is stays current; it will act next round. One that slots in after acts
this round when its turn comes, flat-footed until then. `leave` is the mirror image (remove from
the three lists, shift the index down when the removed position was at or before it) with one
subtlety: when the creature that left *was* the current one, `currentLeft` tells the next
`nextTurn` not to call `endTurn` on it, because its turn should not tick durations twice and the
creature may have been destroyed by the game.

### 24.3 Turns and rounds

```java
public int nextTurn() {
    if (!active || order.isEmpty()) {
        return -1;
    }
    int c = current();
    if (c >= 0 && !currentLeft) {
        rules.endTurn(c);
    }
    currentLeft = false;
    turnIndex++;
    if (turnIndex >= order.size()) {
        turnIndex = 0;
        round++;
        rules.events().add(Events.ROUND, -1, -1, 0, 0, 0, round, 0);
    }
    c = order.get(turnIndex);
    rules.startTurn(c);
    if (rules.canAct(c)) {
        budget.reset(c);
    } else {
        budget.clear(c);
    }
    return c;
}
```

This is the whole state machine: end the current turn (durations tick, the dying roll), advance,
wrap into a new round at the end of the order, start the next creature's turn (flat-footed off,
attacks of opportunity back) and grant its budget, or none when it cannot act. A creature that
cannot act still *gets* a turn, so that its durations tick and the dying get their roll to
stabilize; the drivers (§25) just do not ask it for orders.

```java
public boolean hasAction(int creature, int action) {
    return rules.canAct(creature) && budget.has(creature, action);
}

public boolean useAction(int creature, int action) {
    return rules.canAct(creature) && budget.use(creature, action);
}

public void reportMovement(int creature) {
    budget.movedThisTurn(creature);
}
```

### 24.4 Provoking

```java
public int provoke(int provoker, int[] threateners, int n) {
    int made = 0;
    int team = rules.creatures().team(provoker);
    for (int i = 0; i < n; i++) {
        int t = threateners[i];
        if (t == provoker || rules.creatures().team(t) == team || !rules.canAct(t)) {
            continue;
        }
        if (rules.opportunityAttacksLeft(t) > 0) {
            rules.attackOfOpportunity(t, provoker);
            made++;
        }
    }
    return made;
}
```

The game passes the creatures that threaten the square the provoker left or the action it took
(§27.6); the encounter filters out the provoker itself, its allies and anyone who cannot act,
and lets each remaining enemy with an attack of opportunity left make one. The test
`provokingDrawsOneAttackPerThreateningEnemy` passes all four participants as threateners of
Aldric: none attack while everyone is flat-footed; after three of them have had a turn, exactly
the two enemies attack; a second provocation in the same round draws nothing.

### 24.5 Outcome

`teamsStanding()` counts the distinct teams that still have a creature able to act, using a
small scratch array of team ids (grown only if a fight has more than eight teams); `isOver()` is
"at most one"; `winner()` is that team, or −1 when nobody stands or the fight is not over. A dying
creature cannot act, so a party of six that is all dying has lost even if nobody is dead
(`theFightIsOverWhenOneTeamStands`).

## 25. Step 14: the two drivers

The drivers decide *when* `nextTurn` happens and tell the game whose turn it is through one
interface:

```java
@FunctionalInterface
public interface TurnListener {
    /** {@code creature}'s turn started in {@code encounter}. */
    void onTurn(Encounter encounter, int creature);
}
```

### 25.1 Turn-based

```java
public int endTurn() {
    if (!encounter.isActive() || encounter.isOver()) {
        return -1;
    }
    int n = encounter.size();
    for (int i = 0; i <= n; i++) { // at most one full round of passes
        int c = encounter.nextTurn();
        if (c < 0) {
            return -1;
        }
        if (encounter.rules().canAct(c)) {
            if (listener != null) {
                listener.onTurn(encounter, c);
            }
            return c;
        }
        if (encounter.isOver()) {
            return -1;
        }
    }
    return -1;
}
```

Called when the player presses Enter or the skeleton AI finishes: it advances to the next
creature that can act, passing over the dying, the stunned and the dead (whose turns still happen
in `nextTurn`, so their durations tick). The loop is bounded by one full round of passes, so a
fight in which nobody can act (everyone dying) returns −1 instead of spinning; `isOver` is checked
after each pass because a dying creature's turn can kill it and end the fight. Nothing happens
between calls: the player can think as long as they like.

### 25.2 Real time

```java
public final class RealTimeDriver {
    /** The length of a round. */
    public static final float ROUND_SECONDS = 6f;

    private final Encounter encounter;
    private TurnListener listener;
    private float clock; // seconds into the current round

    // ... the constructor, encounter(), setListener(), clock() and setClock()

    /** When turn {@code i} of the order is due within a round. */
    public float offset(int i) {
        int n = encounter.size();
        return n == 0 ? 0 : i * ROUND_SECONDS / n;
    }

    /** Sets the clock to the current turn's offset (after turn-based play moved the turn along). */
    public void sync() {
        clock = encounter.turnIndex() < 0 ? 0 : offset(encounter.turnIndex());
    }

    public int advance(float dt) {
        if (!encounter.isActive() || encounter.isOver()) {
            return 0;
        }
        clock += dt;
        int started = 0;
        while (encounter.isActive() && !encounter.isOver()) {
            int next = encounter.turnIndex() + 1;
            boolean newRound = next >= encounter.size();
            float due = newRound ? ROUND_SECONDS : offset(next);
            if (clock < due) {
                break;
            }
            if (newRound) {
                clock -= ROUND_SECONDS;
            }
            int c = encounter.nextTurn();
            if (c < 0) {
                break;
            }
            started++;
            if (listener != null && encounter.rules().canAct(c)) {
                listener.onTurn(encounter, c);
            }
        }
        return started;
    }
}
```

(Condensed: the constructor and the four one-line accessors between the fields and `offset` are
left out.) §10 as code. `offset(i)` is $iR/n$ in `float` (`i * ROUND_SECONDS / n` multiplies first, so the
integer `i` is promoted and nothing is truncated). `advance` moves the clock and starts every turn
that has become due, in a loop, so that a large `dt` (a hitch, or the test's 0.75 s step) starts
several turns in their order rather than losing them: the test `bothDriversPlayTheSameFight` gets
identical events at 1/60 s and at 0.75 s because of this loop. The next turn's due time is its
offset, or `ROUND_SECONDS` when the order is exhausted, in which case the clock is wound back by
one round *before* the turn starts, so the new round's turn 0 is due at 0 and starts at once.
`sync` is the mode switch of §10.4; `setClock` is for a load.

Why offsets rather than "real time is turn-based with instant turns"? Because a creature's
attacks must spread over the six seconds for the animations and the player to follow (D100), and
because the turn-based driver's "wait for orders" has no meaning on a clock. Why not two rule
sets? Two sources of truth for the same numbers: every attack bonus and every duration would
have to be kept equal by hand.

## 26. Step 15: the content pipeline

Three classes carry a definition from a JSON file to `Content`'s typed reads: the schema that
describes the tables, the tables themselves, and the importer that fills them.

### 26.1 `RulesSchema`: kinds, fields, offsets

A field is a record of a name, a type, the names of an enum or flag set, the kind a reference
points at, a default and a documentation string; its **width** is one or two ints:

```java
public record Field(
        String name,
        Type type,
        String[] names,
        String refKind,
        int defaultInt,
        String defaultString,
        String doc) {
    /** Returns how many ints the field takes in a row (2 for strings and ranges). */
    public int width() {
        return switch (type) {
            case STRING, REF_LIST, INT_LIST, OPS, NESTED -> 2;
            default -> 1;
        };
    }
```

A kind is a list of fields with a **builder** of one method per type (`str`, `integer`, `bool`,
`enumeration`, `flags`, `dice`, `ref`, `refList`, `intList`, `ops`, `nested`), and `finish`
computes each field's `int` offset within a row and the row's stride:

```java
private void finish(int index) {
    this.index = index;
    offsets = new int[fields.size()];
    int o = 0;
    for (int i = 0; i < fields.size(); i++) {
        offsets[i] = o;
        o += fields.get(i).width();
    }
    stride = o;
}
```

The eleven kinds are declared in a static initializer in file order. Every top-level kind starts
with `id`, `name` and `description` (three strings, six ints); the feats kind, for instance,
continues with seven `INT`s, a `REF_LIST`, a `FLAGS` and an `OPS`:

```java
FEATS =
        top(kinds, "feats", "A feat: prerequisites and effects while known")
                .integer("minBab", 0, "base attack bonus required")
                .integer("minStr", 0, "Strength required")
                .integer("minDex", 0, "Dexterity required")
                .integer("minCon", 0, "Constitution required")
                .integer("minInt", 0, "Intelligence required")
                .integer("minWis", 0, "Wisdom required")
                .integer("minCha", 0, "Charisma required")
                .refList("requires", "feats", "feats required first")
                .flags("flags", FEAT_FLAGS, "behaviors the engine implements")
                .ops("effects", "modifiers while the feat is known");
```

which gives the offsets `id@0 name@2 description@4 minBab@6 … minCha@12 requires@13 flags@15
effects@16` and a stride of 18. The operation record's eight fields are declared the same way in
`OP_FIELDS`, so the importer and the editor treat an operation as a tiny row. `kind(name)` and
`field(name)` are linear searches over a dozen entries and allocate nothing: the editor's panel
calls them every frame.

### 26.2 `RulesData`: the tables in memory and on disk

In memory the file is `int[][] cells` (one `int[]` per kind, `rows × stride`), `int[] pool`,
`int[] ops`, `String[] strings` and a map from id to row per kind, built once in the constructor.
Every read is an index computation:

```java
public int getInt(Kind kind, int row, int field) {
    return cells[kind.index()][row * kind.stride() + kind.offset(field)];
}

public int start(Kind kind, int row, int field) {
    return cells[kind.index()][row * kind.stride() + kind.offset(field)];
}

public int count(Kind kind, int row, int field) {
    return cells[kind.index()][row * kind.stride() + kind.offset(field) + 1];
}

public int op(int i, int k) {
    return ops[i * RulesSchema.OP_INTS + k];
}
```

The `Builder` adds rows with every field at its default, sets cells, appends lists to the pool and
operations to the ops array, and `build()` trims every array to its used size. `toBytes` writes the
header (magic `JRUL` as the little-endian `int` `0x4C55524A`, version 1, the kind count, the
number of ints in the operation array and in the pool, the string count, four section offsets and the file size), then each kind's table as a
row count and its ints padded to 16 bytes, then the ops, the pool and the strings as an (offset,
length) table followed by UTF-8 bytes. `read` checks the magic, the version, that the file's kind
count equals the schema's (a file and a build that disagree fail loudly rather than misread a
column), every section against the file's size, and then `validate` checks every string index,
reference, range and enum value against the table sizes. Lesson [L04](lessons/04-binary-formats.md)
is the general theory of such files; the reason this one is binary rather than JSON is §11.3.

### 26.3 `RulesImporter`: two passes and a validation

The cooker (chapter [10](10-assets.md), on the asset pipeline) hands the importer a manifest
`rules/<set>.rules.json`; the importer claims the kind files next to it as dependencies (editing
`feats.json` re-cooks the set) and builds the data in two passes:

```java
public static RulesData build(Map<String, List<Map<String, Object>>> sources) {
    RulesData.Builder b = new RulesData.Builder();
    Map<String, Map<String, Integer>> ids = new HashMap<>();
    // Pass 1: rows and ids of every top-level kind.
    for (Kind k : RulesSchema.KINDS) {
        if (!k.hasId()) {
            continue;
        }
        Map<String, Integer> index = new HashMap<>();
        List<Map<String, Object>> rows = sources.getOrDefault(k.name(), List.of());
        for (int i = 0; i < rows.size(); i++) {
            Object id = rows.get(i).get("id");
            if (!(id instanceof String s) || s.isEmpty()) {
                throw new IllegalArgumentException(k.name() + " entry " + i + " has no id");
            }
            if (index.put(s, b.addRow(k)) != null) {
                throw new IllegalArgumentException(k.name() + " has two entries with id " + s);
            }
        }
        ids.put(k.name(), index);
    }
    // Pass 2: fields, nested rows and operations.
    for (Kind k : RulesSchema.KINDS) {
        if (!k.hasId()) {
            continue;
        }
        List<Map<String, Object>> rows = sources.getOrDefault(k.name(), List.of());
        for (int r = 0; r < rows.size(); r++) {
            fill(b, ids, k, r, rows.get(r), k.name() + " '" + rows.get(r).get("id") + "'");
        }
    }
    return b.build();
}
```

The first pass creates every row and records its id, so that in the second pass a reference can
point forwards or across kinds (a character names a class that is defined in another file). `fill`
rejects any key the kind does not have, then converts each field by its type: an enum name to its
index, a flag array to its bits (`bits |= 1 << enumValue(...)`), a dice string through
`Dice.parse`, an id through the index of the referenced kind, a nested array into rows of the
nested kind, an `effects` array into operations through `op`, which additionally checks that a
`condition` or `grantFeat` names a ref of the right kind, that `modify` names a stat, and that
`damage`, `heal` and `tempHp` have dice. Every message names the kind, the id and the field
(`feats 'x'.requires: no feats entry with id 'nope'`), which `RulesImporterTest.validationNamesTheProblem`
pins down case by case. This is the public static entry point the editor and the tests share;
`cook` wraps it with the file I/O and writes `rules/<set>.jrules`.

### 26.4 The editor's panel

The editor module does not depend on the cooker, so `RulesSource` (chapter [14](14-editor.md),
on the editor's panels and commands) keeps the JSON value trees, edits them through undoable
commands, repeats the importer's checks in its own `validate` (unknown fields, enum names, dice,
dangling references, duplicate ids, with messages of the same shape) and writes the files back;
`RulesSourceTest.savedFilesCookToTheSameBinary` cooks what it saved and asserts the same bytes as
the hand-written files give. The cooker's watcher re-cooks and the game hot-reloads (§27.9). The panel generates its property grid from `RulesSchema`, so a field added to
the schema appears in the editor with no further work.

---
# Part C — Using it and proving it

## 27. The demo: `Combat`

[`Combat`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/Combat.java) is the glue
between the rules and the world the earlier milestones built: the party of chapter 01's demo,
the skeletons that chapter 22's `SkeletonAi` drives, the crowd of chapter 11, the animations of
chapter 09 and the UI of chapter 13. It owns one `Rules`, one `Encounter`, both drivers and the
`CombatLog`, and it implements `TurnListener`. The rules speak in feet and the crowd in meters;
the crypt's 5-foot square is 1.5 m, so `METERS_PER_FOOT = 0.3f`, and a skeleton's reach is the
demo's `Enemies.REACH = 1.5f` meters.

### 27.1 Building and binding creatures

```java
Combat(
        Content content,
        long seed,
        Party party,
        Enemies enemies,
        Animator animator,
        Crowd crowd,
        CombatLog log) {
    ...
    int capacity = Party.SIZE + enemies.count + 8;
    rules = new Rules(content, capacity, seed);
    creatures = rules.creatures();
    encounter = new Encounter(rules);
    realTime = new RealTimeDriver(encounter);
    turnBased = new TurnBasedDriver(encounter);
    realTime.setListener(this);
    turnBased.setListener(this);
    attacksLeft = new int[capacity * 2];
    attackIndex = new int[capacity * 2];
    moveLeft = new float[capacity * 2];
    enemyCreature = new int[enemies.count];
    monsterRow = new int[Characters.ENEMIES.length];
    spellTargets = new int[capacity];
    threatScratch = new int[capacity];
    threatCircles = new float[capacity * 3];
    for (int i = 0; i < Party.SIZE; i++) {
        int row = content.find(RulesSchema.CHARACTERS, Characters.PARTY_IDS[i]);
        if (row < 0) {
            throw new IllegalStateException("no character " + Characters.PARTY_IDS[i]);
        }
        int c = rules.buildCharacter(row, TEAM_PARTY);
        partyCreature[i] = c;
        bind(c, party.agent[i], i, -1);
    }
    for (int m = 0; m < monsterRow.length; m++) {
        monsterRow[m] = content.find(RulesSchema.MONSTERS, Characters.ENEMY_IDS[m]);
        ...
    }
    for (int i = 0; i < enemies.count; i++) {
        enemyCreature[i] = -1;
        spawnEnemy(i);
    }
    fireballRow = content.find(RulesSchema.SPELLS, "fireball");
    flatFootedRow = content.find(RulesSchema.CONDITIONS, "flatFooted");
    deadRow = content.find(RulesSchema.CONDITIONS, "dead");
    dyingRow = content.find(RulesSchema.CONDITIONS, "dying");
    eventsRead = rules.events().next();
}
```

(Condensed: the field assignments and an error check are left out.) The capacity is the party,
every skeleton the scene holds (64 by default, 294 in the perf scene) and a margin; every
per-creature array of this class is sized from it too, so nothing here grows in a fight. The six
party members are built from the `characters` kind by the ids in `Characters.PARTY_IDS`
(`"aldric"`, …), the four skeleton models map to the four `monsters` ids, and each skeleton's
creature is built when it spawns:

```java
void spawnEnemy(int i) {
    if (enemyCreature[i] >= 0) {
        creatures.destroy(enemyCreature[i]);
    }
    int c = rules.buildMonster(monsterRow[enemies.model(i)], TEAM_UNDEAD);
    enemyCreature[i] = c;
    bind(c, enemies.agent(i), -1, i);
}
```

A respawn destroys the old creature (giving its modifiers and conditions back, §15.3) and builds
a fresh one with full hit points. `bind` keeps three reverse maps indexed by creature handle: the
crowd agent, the party member index and the skeleton index, so that an event about creature `c`
can find the body to flash and the portrait to grey out.

### 27.2 Engagement

```java
void engage(int i) {
    int c = enemyCreature[i];
    if (encounter.contains(c)) {
        return;
    }
    if (!encounter.isActive()) {
        for (int m = 0; m < Party.SIZE; m++) {
            encounter.add(partyCreature[m]);
        }
        encounter.add(c);
        encounter.start();
        realTime.sync();
        rounds = 1;
        log.start().add("Combat begins").end();
    } else {
        encounter.join(c);
    }
    engaged++;
}
```

The skeleton AI calls `engage` when a skeleton notices the party (chapter [22](22-components.md),
on the demo's behaviours: `SkeletonAi.think` moves it from `IDLE` to `CHASE`, and `flinch`
engages a skeleton that was hit first). The first skeleton to engage starts the fight with the
whole party; later ones join it. `disengage` (a skeleton that gave up the chase or fell) takes
the creature out of the order, and the last one to leave ends the fight:

```java
private void endCombat() {
    encounter.end();
    aiCreature = -1;
    log.start().add("Combat ends after ").add(rounds).add(" rounds").end();
    // The crypt is forgiving: the fallen come to with one hit point, slots refill.
    for (int m = 0; m < Party.SIZE; m++) {
        int c = partyCreature[m];
        if (rules.isDead(c)) {
            rules.removeCondition(c, deadRow);
            log.start().add(creatures.name(c)).add(" rises again").end();
        }
        if (creatures.hp(c) < 1) {
            rules.heal(c, 1 - creatures.hp(c), c, 0);
        }
        for (int level = 0; level < Creatures.SPELL_LEVELS; level++) {
            creatures.setSlotsUsed(c, level, 0);
        }
    }
}
```

The crypt is a demo, not a campaign: the dead rise, the dying are healed to 1 (which also ends
*dying* and *unconscious*, §20), and spell slots refill. Out of combat, `tick` heals every member
one point every two seconds.

### 27.3 Ticking in two tempos

```java
void tick(float dt) {
    if (encounter.isActive()) {
        if (turnBasedMode) {
            if (encounter.current() < 0) {
                turnBased.endTurn(); // the first turn
            }
            aiTick(dt);
        } else {
            realTime.advance(dt);
        }
        rounds = encounter.round();
    } else {
        regenTimer += dt;
        ...
    }
    drainEvents();
}
```

`DemoGame.fixedUpdate` calls this once per tick, after the skeletons have thought and the party
has fought (§27.4) and before the crowd moves. In real time the driver advances by `dt`, the tick
length; in turn-based mode the AI's turn is stepped and the player's waits. Either way the
listener fires:

```java
@Override
public void onTurn(Encounter e, int c) {
    attacksLeft[c] = rules.fullAttackCount(c);
    attackIndex[c] = 0;
    moveLeft[c] = creatures.stat(c, Stat.SPEED) * METERS_PER_FOOT;
    if (turnBasedMode) {
        int member = memberOf(c);
        if (member >= 0) {
            party.select(member, false);
        } else {
            aiCreature = c;
            aiPhase = 0;
            aiTimer = 0;
        }
    }
}
```

A turn hands the creature its attacks for the round and its movement in meters (30 feet × 0.3 =
9 m for a move action; Dara's 20 feet are 6 m, Brunhild's 40 with *fast movement* are 12 m). In turn-based mode
a member's turn selects it so the player's next click is its order, and a skeleton's turn starts
the AI (§27.7).

### 27.4 Strikes

In real time, `Party.fight` runs once per tick: a member who is not a caster, is not down, is not
on cooldown, is standing still and has a living skeleton within reach turns to it, plays its
attack animation, and spends one of its round's attacks:

```java
int target = enemies.nearestAlive(crowd.x(a), crowd.z(a), Enemies.REACH + 0.4f);
if (target >= 0 && combat.canStrike(combat.partyCreature(i))) {
    sync.faceToward(a, crowd.x(target), crowd.z(target));
    animator.action(c, attack);
    cooldown[i] = 0.9f + rng.nextFloat() * 0.8f;
    combat.strike(
            combat.partyCreature(i), combat.enemyCreature(enemies.enemyOf(target)));
}
```

The roll is resolved when the animation *starts*, in the same tick, not at the frame where the
blade lands: a hit's flash and damage number therefore appear at the start of the swing. The
cooldown of 0.9 to 1.7 s spreads a member's attacks over its turn; a creature with two attacks a
round makes them a second or so apart. The skeletons' `SkeletonAi.think` does the same from the
other side. Both go through:

```java
boolean canStrike(int c) {
    if (!encounter.isActive() || !rules.canAct(c) || !encounter.contains(c)) {
        return false;
    }
    if (turnBasedMode) {
        return encounter.current() == c
                && (attacksLeft[c] > 0 || encounter.hasAction(c, ActionBudget.STANDARD));
    }
    return attacksLeft[c] > 0;
}

int strike(int c, int target) {
    if (!canStrike(c)) {
        return Rules.MISS;
    }
    if (attackIndex[c] == 0) {
        if (turnBasedMode) {
            boolean full =
                    !encounter.budget().moved(c)
                            && encounter.useAction(c, ActionBudget.FULL_ROUND);
            if (!full) {
                if (!encounter.useAction(c, ActionBudget.STANDARD)) {
                    return Rules.MISS;
                }
                attacksLeft[c] = 1;
            }
        } else {
            encounter.useAction(c, ActionBudget.FULL_ROUND);
        }
    }
    if (attacksLeft[c] <= 0) {
        return Rules.MISS;
    }
    attacksLeft[c]--;
    return rules.attack(c, target, attackIndex[c]++, 0, 0);
}
```

The first strike of a turn decides which action it is. In turn-based mode a creature that has
not moved makes a full attack (all its attacks, `FULL_ROUND`); one that has moved gets one attack
for its standard action; one with neither left cannot strike. In real time the whole round is a
full attack whose swings the animations pace. `attackIndex` is the $i$ of §5, so the second swing
of an iterative attacker comes at −5. In turn-based mode the player orders a strike by clicking a
skeleton in reach (`Party.strikeAt`), which makes all the remaining swings at once.

### 27.5 The fireball

`Spells` (chapter [08](08-effects-and-post.md), on effects, for the projectile, the light and the
particles) launches a fireball entity on **F** plus a click, and when it explodes queries the
collision world for every unit in a cylinder of radius 4 m and hands them to:

```java
boolean fireball(int caster, IntList hit, int n) {
    if (unlimitedSlots) {
        int level = content.spellLevel(fireballRow, creatures.casting(caster));
        if (level >= 0) {
            creatures.setSlotsUsed(caster, level, 0);
        }
    }
    int targets = 0;
    for (int i = 0; i < n && targets < spellTargets.length; i++) {
        int c = creatureOfAgent(hit.get(i));
        if (c >= 0) {
            spellTargets[targets++] = c;
        }
    }
    if (turnBasedMode && encounter.isActive() && encounter.current() == caster) {
        encounter.useAction(caster, ActionBudget.STANDARD);
    }
    return rules.castSpell(caster, fireballRow, spellTargets, targets);
}
```

The crowd agents in the burst are mapped to creatures (party members included: a fireball is
`"targets": "all"`), the standard action is spent in turn-based mode, and `castSpell` (§21.5)
does the slot, the Reflex save per creature and the `5d6` fire damage, halved on a save. The perf
scene sets `unlimitedSlots` so that its scripted fireballs never run dry. `DemoGame.castFireball`
refuses before launch when Cassius is down, has no slot, or in turn-based mode when it is not his
turn or his standard action is spent, with a log line for each.

### 27.6 Orders in turn-based play: moving and provoking

When a member's turn is on, `DemoGame.turnOrders` turns a click on the ground into a walk whose
cost chapter [11](11-physics-navigation-crowds.md)'s `TurnBasedNav` priced (the flooded movement
range drawn on the floor is the same query), and `Combat.move` spends it:

```java
boolean move(int c, float x, float y, float z, float cost) {
    if (!turnBasedMode || encounter.current() != c || !rules.canAct(c) || cost < 0) {
        return false;
    }
    if (cost > moveLeft[c]) {
        if (!encounter.useAction(c, ActionBudget.MOVE)) {
            return false;
        }
        moveLeft[c] += creatures.stat(c, Stat.SPEED) * METERS_PER_FOOT;
        if (cost > moveLeft[c]) {
            return false;
        }
    } else if (!encounter.budget().moved(c)) {
        encounter.useAction(c, ActionBudget.MOVE);
    }
    moveLeft[c] -= cost;
    encounter.reportMovement(c);
    provokeAlongPath(c, x, z);
    crowd.requestMoveTarget(agentOf[c], x, y, z);
    return true;
}
```

A walk that fits in what is left of the current move action spends nothing new (but marks the
first one as used); one that does not fit buys a second move action, which §23's `use` pays with
the standard action, and fails if that is gone too ("Too far to walk this turn" in the log).
`movementBudget(c)` gives the UI the same number: what is left plus a second move if the standard
action is still free. Then the attacks of opportunity:

```java
private void provokeAlongPath(int c, float toX, float toZ) {
    int agent = agentOf[c];
    segment[0] = crowd.x(agent);
    segment[1] = 0;
    segment[2] = crowd.z(agent);
    segment[3] = toX;
    segment[4] = 0;
    segment[5] = toZ;
    int team = creatures.team(c);
    int n = 0;
    for (int i = 0; i < encounter.size() && n < threatScratch.length; i++) {
        int other = encounter.participant(i);
        int a = agentOf[other];
        if (a < 0 || creatures.team(other) == team || !rules.canAct(other)) {
            continue;
        }
        threatScratch[n] = other;
        threatCircles[n * 3] = crowd.x(a);
        threatCircles[n * 3 + 1] = crowd.z(a);
        threatCircles[n * 3 + 2] = Enemies.REACH + 0.4f;
        n++;
    }
    int crossings =
            TurnBasedNav.threatCrossings(
                    segment,
                    2,
                    threatCircles,
                    n,
                    crossingEnemy,
                    crossingDistance,
                    crossingKind);
    for (int k = 0; k < crossings; k++) {
        if (crossingKind[k] == TurnBasedNav.EXIT) {
            one[0] = threatScratch[crossingEnemy[k]];
            if (encounter.provoke(c, one, 1) > 0) {
                int e = enemyOf(one[0]);
                if (e >= 0) {
                    animator.action(
                            enemies.character(e), Characters.ENEMY_ATTACK[enemies.model(e)]);
                }
            }
        }
    }
}
```

This is D100's division of labour in one method: the *game* knows positions, so it builds a
straight segment from where the creature stands to where it was told to go and a threat circle of
radius 1.9 m around every enemy that can act, asks the navigation module where the segment
*exits* a circle, and reports each exit to `Encounter.provoke` one enemy at a time; the *rules*
know whether that enemy has an attack of opportunity left and resolve it. The game then plays the
enemy's attack animation. (The segment is straight although the walk may bend: a simplification
the chapter on navigation discusses.)

### 27.7 The skeletons' turn

In turn-based mode the skeleton whose turn it is runs a three-phase script in `aiTick`, one
phase per tick at most:

```java
if (aiPhase == 0) {
    // Pick the nearest party member that is still up.
    int best = -1;
    float bestD2 = Float.MAX_VALUE;
    for (int m = 0; m < Party.SIZE; m++) {
        int pc = partyCreature[m];
        if (!rules.canAct(pc)) {
            continue;
        }
        int pa = party.agent[m];
        float dx = crowd.x(pa) - crowd.x(agent);
        float dz = crowd.z(pa) - crowd.z(agent);
        float d2 = dx * dx + dz * dz;
        if (d2 < bestD2) {
            bestD2 = d2;
            best = m;
        }
    }
    if (best < 0) {
        aiCreature = -1;
        turnBased.endTurn();
        return;
    }
    aiVictim = best;
    float d = (float) Math.sqrt(bestD2);
    if (d <= Enemies.REACH) {
        aiPhase = 2;
    } else {
        float budget = moveLeft[c];
        float walk = Math.min(d - Enemies.REACH * 0.7f, budget);
        int pa = party.agent[best];
        float t = walk / d;
        aiTargetX = crowd.x(agent) + (crowd.x(pa) - crowd.x(agent)) * t;
        aiTargetZ = crowd.z(agent) + (crowd.z(pa) - crowd.z(agent)) * t;
        moveLeft[c] -= walk;
        encounter.useAction(c, ActionBudget.MOVE);
        encounter.reportMovement(c);
        crowd.requestMoveTarget(agent, aiTargetX, crowd.y(agent), aiTargetZ);
        aiPhase = 1;
        aiTimer = 0;
    }
}
```

Phase 0 picks the nearest member who can act (distances compared squared, the square root taken
once for the one that won), and either goes straight to striking (phase 2) when it is already in
reach, or walks toward it: as far as the move budget allows, stopping 70 % of a reach short so the
crowd's separation does not push it back out, with the point of aim computed by linear
interpolation along the line to the victim ($P = A + (B - A)\,t$ with $t = \text{walk}/d$). Phase 1
waits for the crowd to arrive (within 0.6 m, or stalled for half a second, or three seconds at
most). Phase 2 strikes while it can (`canStrike`, at most six swings), faces the victim, plays the
attack animation, and ends the turn. Nothing in the three phases rolls a die: the rules do that
inside `strike`.

### 27.8 From events to log lines

Every tick ends with `drainEvents`, which reads the ring from where it stopped last time:

```java
private void drainEvents() {
    Events ev = rules.events();
    long end = ev.next();
    if (eventsRead < ev.oldest()) {
        eventsRead = ev.oldest();
    }
    for (long s = eventsRead; s < end; s++) {
        int kind = ev.kind(s);
        int actor = ev.actor(s);
        int target = ev.target(s);
        switch (kind) {
            case Events.ATTACK_HIT, Events.CRITICAL -> {
                log.start()
                        .add(creatures.name(actor))
                        .add(kind == Events.CRITICAL ? " crits " : " hits ")
                        .add(creatures.name(target))
                        .add(" (")
                        .add(ev.total(s))
                        .add(" vs AC ")
                        .add(ev.against(s))
                        .add(")")
                        .end();
            }
            ...
            case Events.DAMAGE -> {
                if (ev.ref(s) != 0 || actor != target) { // not the dying's point a round
                    log.start()
                            .add("  ")
                            .add(creatures.name(target))
                            .add(" takes ")
                            .add(ev.amount(s))
                            ...
                            .end();
                }
                int e = enemyOf(target);
                if (e >= 0) {
                    enemies.flinch(e);
                }
            }
            ...
            case Events.CONDITION_ON, Events.CONDITION_OFF -> {
                if (ev.ref(s) != flatFootedRow) { // everyone starts a fight flat-footed
                    log.start()
                            .add(creatures.name(target))
                            .add(kind == Events.CONDITION_ON ? " is " : " is no longer ")
                            .add(content.name(RulesSchema.CONDITIONS, ev.ref(s)))
                            .end();
                }
            }
            case Events.DYING, Events.DEATH -> {
                int m = memberOf(target);
                int e = enemyOf(target);
                if (m >= 0 && invulnerableParty) {
                    rules.removeCondition(target, content.find(RulesSchema.CONDITIONS, "dead"));
                    rules.removeCondition(
                            target, content.find(RulesSchema.CONDITIONS, "dying"));
                    creatures.setHp(target, maxHp(target));
                } else if (m >= 0) {
                    animator.die(party.character[m]);
                    log.start()
                            .add(creatures.name(target))
                            .add(kind == Events.DEATH ? " dies" : " falls")
                            .end();
                } else if (e >= 0) {
                    enemies.kill(e);
                    disengage(e);
                    log.start()
                            .add(creatures.name(target))
                            .add(" is destroyed (")
                            .add(enemies.alive)
                            .add(" left)")
                            .end();
                }
            }
            ...
        }
    }
    eventsRead = end;
}
```

(Condensed: the cases for misses, heals, saves, stabilizing, rounds, spells, opportunity attacks
and temporary hit points follow the same pattern.) This is where the lines of §1 come from, and
where the rules' numbers become the world's reactions: a `DAMAGE` on a skeleton raises the `HURT`
signal its `flinch` behaviour answers; a `DEATH` on a skeleton plays its death and takes it out of
the fight, on a member plays the fall; a `HEAL` that brings a member back above zero plays the
revival. Two filters keep the log readable: the flat-footed condition that everyone starts with
and loses on their first turn is not logged, and the dying's point-a-round (a `DAMAGE` whose actor
is its target with ref 0) is not either. The perf scene's `invulnerableParty` undoes a member's
death as soon as it is reported, so the scripted march never stops.

`CombatLog` keeps the lines as fixed `char[]`s written in place, so a line costs no allocation;
its `add(int)` formats a number without `Integer.toString`:

```java
CombatLog add(int v) {
    if (v < 0) {
        add('-');
        v = -v;
    }
    int p = 1;
    while (v / p >= 10) {
        p *= 10;
    }
    while (p > 0) {
        add((char) ('0' + v / p % 10));
        p /= 10;
    }
    return this;
}
```

Find the largest power of ten not above `v`, then emit the digits from the most significant:
`v / p % 10` is the digit at that place (L00 §2.4). The UI of chapter [13](13-ui.md) draws the
lines and scrolls to the newest when `version()` changes.

### 27.9 `DemoGame`: keys, orders, hot reload and scripts

- **Keys.** **T** toggles the mode through `setTurnBased` (the encounter keeps its state; going
  back to real time calls `realTime.sync()`, and going to turn-based gives the current creature
  its budget again through `onTurn`); **Enter** ends the turn; **F** toggles fireball aiming; the
  HUD's *End turn* button does what Enter does.
- **Orders in a member's turn** (`turnOrders`): a click on a skeleton is `Party.strikeAt`, which
  fails with "Out of reach or no attack left" beyond 1.9 m; a click on the ground is `Combat.move`
  with the navigation cost of the walk.
- **Hot reload.** `DemoGame` loads the `.jrules` as an asset; when the cooker's watcher re-cooks
  it after the editor's *Save*, the asset's version changes and `buildCombat` runs again: a new
  `Combat`, every creature rebuilt from the new content, the mode kept, and a log line "Rules
  reloaded: everyone is rebuilt from the content". A fight in progress is abandoned by this, which
  is right for an editing session and would be wrong for a player; the patch path for players is
  the save (§28).
- **Scripts.** `--auto-turns` plays turn-based fights without a player: after one second of a
  member's turn it strikes the nearest skeleton in reach and ends the turn, and between fights it
  marches the party along the perf tour's waypoints; `--echo-combat` prints every new log line to
  the console (through `LOG.info`, so it allocates: a debugging option, not a game path);
  `--turn-based` starts in turn-based mode; `--seed N` seeds the dice. The perf capture (§30)
  combines them with the party invulnerable and slots unlimited.

## 28. Saving the rules state

Chapter [16](16-saves.md), on save games, builds the byte streams and the container and walks
through the rules' part in full; this section says what the rules write and why, in the terms of
this chapter.

`Rules.write` stores the generator's four `long`s (so the next roll after a load is the roll
that would have come, L02 §12.3) and then every live creature at its handle: its team and name,
the **id string** of the character or monster it was built from (never a row: a rulebook edit
renumbers rows, D115), and only the state that play changed and a rebuild would not recreate:
hit points, temporary hit points and their rounds, spell slots used, attacks of opportunity used,
whether it acted, the feats and spells it knows and its equipment as ids, the active conditions
as (id, rounds), and the modifiers whose source is a spell or the game (a condition's modifiers
are re-applied with the condition; a feat's, an item's or a class feature's with the rebuild).
`keptInSave` is that filter; a creature made by hand (no content row) stores its base fields
verbatim. `read` clears the store, then for each saved creature calls `Creatures.createAt` at the
saved handle, `fillCharacter` or `fillMonster` from the *current* content, lays the saved values
over the result, re-applies the conditions through `applyCondition` (so their effects and events
happen as in play), and sets the hit points last, because the rebuild and the equipment set them
to the maximum. `buildCharacter` and `buildMonster` were split into a create and a fill for this.
The result is that a patched stat block reaches a creature the player has already met: the test
`creaturesAreRebuiltFromThePatchedContentWithTheirOldWounds` raises the warrior's hit points from
16 to 30 in a copy of the content and loads a save made with the old one; the maximum is 14 higher
and the wound is still there.

`Encounter.write` keeps the three order lists, the round, the turn index, the `currentLeft` flag,
the join counter and the action budget; `Combat.write` adds the real-time clock, the mode, the
rounds, who is engaged, each creature's attacks and movement left this turn, and the skeleton
AI's phase. All of it allocates nothing (`SavesTest.creaturesEncounterAndGeneratorRoundTrip`
asserts that), and a fixture of format 1 is kept under test so that a later format still reads
it (`formatOneFixtureStillReads`, D117).

## 29. The tests, walked through

All of them run with `./gradlew :rules:test`, and all build the demo's content from its JSON
files through the importer (`DemoContent`), so a content edit that breaks a hand-computed number
fails a test. Run from the repository, because the files are found relative to the module.

**`CreaturesTest`: every number by hand.** One test per archetype builds the character and
asserts each derived statistic against the arithmetic in its comments: Aldric's 47 hit points
(`10 + 4 * 6 + 2 * 5` plus 3), AC 20 and touch AC 12, attack 10, damage 4, saves 6/2/3; Brunhild's
two-handed 9 damage and chain-shirt-capped AC 15; Dara's small, finesse, two-weapon numbers and
Reflex 12; Cassius's slots (one third-level at level 5, none at level 3) and DCs 18 and 16; the
skeletal warrior's AC 17 from Dex, natural armor and the hauberk in its effects. The kind of test
that only works because the arithmetic is small enough to do on paper, and the one that caught the
doubled size bonus of §32. `creaturesAreRecycledWithTheirModifiers` destroys Aldric, checks that
the modifier store is empty, builds Fenna into the same slot and asserts *her* numbers.
`statResolutionAllocatesNothing` is the allocation proof of §15.4.

**`ModifiersTest`: the stacking rule.** Armor 4 and 6, two dodges, an untyped, two penalties →
6 + 2 + 1 − 3 (§13.4); `removeBySource` compacts the list; durations tick and expire and freed
handles are reused; growth past the capacity works; `total` allocates nothing.

**`RulesTest`: exact rolls.** L02 §19 explains the oracle technique.
`anAttackRollIsD20PlusBonusAgainstArmorClass` replays forty seeds of Aldric against the warrior
and asserts natural, total, AC, hit or miss, the confirmation on a 19 or 20, the damage dice (and
their second roll on a critical) and the death at 16. `naturalOneMissesAndNaturalTwentyHits`
chooses seeds 9 and 73. `aConfirmedThreatMultipliesTheDamage` chooses seed 765 (two 20s) for
Brunhild's ×3 greataxe. `monstersAttackWithTheirStatBlockAndPartyMembersFallDying` sets Cassius
to 3 hit points and lets a warrior's natural 20 (seed 10; confirmed or not) put him to dying, or
past −10 to dead.
`dyingLosesAPointARoundUntilStabilizedOrHealed` is §20's walk; `temporaryHitPointsAbsorbFirstAndExpire`
§20's first lines; `savingThrowsUseTheRightSaveAndTheNaturals` twenty seeds of Fortitude 15 and
Will 12; `conditionsApplyTheirEffectsForTheirDuration` and `flatFootedLosesDexterityUntilTheFirstTurn`
§8 and §22; `fireballSpendsASlotAndReflexHalves` twenty seeds of the cast of §21.5 against a
warrior and a minion, event by event; `magicMissileNeverMissesAndRayOfFrostNeedsATouchAttack`,
`buffsLastTheirRoundsAndRecastingReplaces` and `slowIsNegatedByAWillSave` the other spell
paths; `itemsHealAndFeatsHavePrerequisites` §18.4 and the potion; `attacksOfOpportunityAreLimitedPerRound`
§22; `aSeedReplaysTheSameFight` hashes the ring of a twenty-round fight for seeds 42 and 43. The
three allocation tests are §30's.

**`EncounterTest`: the order and the two tempos.** `initiativeOrdersByRollThenDexterityThenJoinOrder`
replays thirty seeds of four initiative rolls on an oracle and checks every pair's order;
`turnsCycleAndRoundsAdvance`, `theBudgetHasStandardMoveSwiftAndAStep` (every line of §23: the
step, the trade, the full round, no budget while stunned), `provokingDrawsOneAttackPerThreateningEnemy`
(§24.4), `creaturesJoinAndLeaveMidFight` (§24.2), `theFightIsOverWhenOneTeamStands` (§24.5).
`bothDriversPlayTheSameFight` runs one scripted fight (walk up, provoke, full attack) through
`TurnBasedDriver` and through `RealTimeDriver` at 1/60 s and 0.75 s, for five seeds, and asserts
the same event hash, the same hit points and the same round count: the property of §10.4 and
D100. `realTimeTurnsAreSpreadOverTheRound` is §10.2's arithmetic, `sync` included.

**`SavesTest`** is §28's. **`RulesImporterTest`** builds a small set with references, nesting and
operations and checks every cell, pins the validation messages, cooks a manifest through the real
`Cooker` with the kind files as dependencies, and cooks the demo's set. The editor's
`RulesSourceTest` and `RulesCommandsTest` (chapter [14](14-editor.md), on the editor's panels)
cover the panel's model.

## 30. Performance: zero allocation, and what the capture caught

Nothing in this module runs per frame; a round of a fight is a few hundred calls. The rule still
applies (DESIGN §4), for two reasons: the game thread's tick budget is shared with the crowd, the
navigation and the animations, and a store that doubles in the middle of a fight is a
multi-kilobyte allocation at an unpredictable moment. Three tests prove the steady state:

- `CreaturesTest.statResolutionAllocatesNothing`: all seventeen statistics of a creature with
  eight modifiers, 0 bytes.
- `RulesTest.aRoundOfAttacksAllocatesNothing`: starts and ends four turns with attacks, an attack
  of opportunity, a condition, a save and a fireball, 0 bytes.
- `EncounterTest.aTurnAllocatesNothing`: a second of the real-time driver with the scripted fight,
  0 bytes.

The cost model: a stat is $O(\text{modifiers} + \text{types})$ with no allocation (§13.4);
creatures cache nothing, so every rule change is visible at once; an attack is two or three stat
resolutions, a handful of content reads and a few event writes.

**What the capture caught.** The demo's perf capture (`./gradlew :iso-demo:run
--args="--headless --perf 20 --warmup 6 --warmup-frames 150 --alloc-probe"`; CI runs 120 s with
`--perf 120 --warmup 30 --alloc-probe --report build/perf --fail-on alloc,flags`) runs the
scripted march and fights with the party invulnerable and slots unlimited, so the rules run the
whole time, and reports the game thread's allocation per frame. The first runs of the finished
demo were not at zero, and each miss was a store growing:

1. `Conditions` doubling from 32 to 64 instances (896 B) when a fireball left enough skeletons
   dead at once: three `int[64]` at 272 bytes each plus a `boolean[64]` at 80 (a 12-byte array
   header plus the elements, rounded up to 8; measured on JDK 27 with
   `ThreadMXBean.getThreadAllocatedBytes`) is exactly 896.
2. The encounter's three order lists growing from 133 to 200 entries (2,448 B: three `int[200]`
   at 816 bytes) when the 134th skeleton joined the perf scene's fight. An `IntList` grows by half
   plus one, so a default list of 16 passes through 25, 38, 58, 88, 133 and 200; the earlier
   growths had happened during the warm-up.
3. `Conditions` doubling again, at 1,232 instances (32 KB: three `int[2464]` at 9,872 bytes plus a
   `boolean[2464]` at 2,480: 32,096 bytes), because `Creatures.destroy` did not release condition
   instances, so every respawned skeleton leaked its *dead*. 1,232 is 4 × 308: the capacity the
   perf scene's 294 skeletons give the engine.

The fixes have the same shape: `Rules` sizes every store from its creature capacity (sixteen
modifiers, four condition instances, one budget slot and one order slot per creature, §18) and
`Creatures.destroy` gives both modifiers and condition instances back (§15.3). The tests
`aCrowdedFightWithinCapacityAllocatesNothing` (48 skeletons each carrying three conditions and
attacking), `destroyedCreaturesReleaseTheirConditionsSoRespawnsDoNotGrowTheStore` (ten
generations of death and rebuild, the capacity asserted unchanged at 4 × 48) and
`everyCreatureJoiningAndLeavingAllocatesNothing` (160 creatures joining and leaving a fight)
keep each one fixed. The lesson: a growable store is an allocation waiting for a longer run; size
it from a number the game knows, and test the long run.

## 31. Why it is built this way

- **A PF1e-shaped core with original content (D96).** The mechanics are the point of the genre
  and are not protected; the published text and names are, and are not reproduced. A simpler
  original d20 would have shipped faster and been too flat to grow toward WotR-style builds; the
  full SRD (skills, size categories, maneuvers, multiclassing) would have tripled the milestone.
  DESIGN §20's "Not in M15" lists what waits.
- **Content as JSON cooked to binary and edited in the editor (D97).** The owner's choice.
  Loading JSON at runtime would parse at load and validate nothing before play; content as Java
  builders would need a recompile per tweak and a programmer per designer. The schema-driven
  panel costs one description of the kinds and gives the editor, the importer and the file for
  free.
- **Effects as operation lists (D98).** An effect class hierarchy would make every new feat Java,
  and data files could not express it; a scripting language is M17's graphs, which will *call*
  operations, not replace them.
- **Checks exactly as the tabletop states them (D99).** Natural 20/1 clipping, confirmation rolls,
  iteratives from the BAB, `10 + level + modifier` DCs, temporary hit points first, dying between
  0 and −Con, durations ticked at the end of the affected creature's turn. Fidelity here is cheap
  and makes the numbers in the log recognizable to anyone who has played.
- **One encounter, two drivers (D100).** Two rule sets would be two sources of truth for the
  same numbers; "real time as turn-based with instant turns" would clump every action at the
  round boundary, which neither the animations nor the player could follow. Movement stays in
  `physics`; the rules consume distances and crossings the game reports.
- **Stores, handles and scratch arrays, not objects.** The engine owns no strings and allocates
  nothing in a fight: events are `int` records; the log formats them on the game thread into
  reused `char[]`s; `stat` and the modifier loops use scratch arrays; the stores are sized from
  one number. This is L03's argument applied to a module whose work per tick is small: the cost of
  an allocation is not the bytes but the pause they eventually buy.
- **Saved by content id, rebuilt from the current rulebook (D115).** So that a patch reaches
  creatures the player has already met; chapter [16](16-saves.md) has the alternatives.

## 32. Pitfalls met on the way

- **Which natural?** After a confirmed threat, `lastNatural` is the confirmation roll; the event
  carries the attack's own natural, copied out before the confirmation (L02 §18.2). The first
  version of `naturalOneMissesAndNaturalTwentyHits` asserted `lastNatural` and failed on a
  confirmed critical, which is how the event record got its own natural.
- **Size counted twice.** `Creatures.stat` adds the size modifier itself; the hearthkin ancestry's
  content also carried a size bonus to AC, so Dara had +2 for being small. The content was wrong,
  the engine right; the hand-computed test caught it (§18.3).
- **Flat-footed everywhere.** Everyone is flat-footed until their first turn, so the first
  seconds of a real-time fight favour whoever won initiative: correct, but surprising in the log
  until you know; the demo drops the condition events for it (§27.8).
- **A locked crypt.** The scripted march never reached the skeletons: every doorway in the crypt
  was a closed door, because the dungeon kit's doorway prefab carries a door leaf as a child node
  and the cooker rightly bakes it as collision. A navmesh probe found the gap; a glTF import
  setting excludes the leaf (D104, chapter [10](10-assets.md), on import settings); doors become
  interactables in M23.
- **The panel's per-frame allocation.** A schema-driven property grid is easy to write with
  strings and lists per frame; the Rules panel keeps a path stack, a text-buffer pool and cached id
  lists per content version instead (chapter [14](14-editor.md)'s rules for panels apply).
- **A leaked `dead`.** The third allocation of §30: a destroy that released half of what a
  creature owned. Two stores, two loops in `release`.
- **The float clock.** One tick late at the first round boundary (§10.3), harmless, and
  explained once so nobody chases it.

## 33. Things to run

```
# the rulebook's tests: hand-computed creatures, checks with chosen seeds, encounters in both tempos
./gradlew :rules:test
# the cooker's importer and the editor's panel
./gradlew :cooker:test --tests 'dev.jeddy.cooker.rules.*'
./gradlew :editor:test --tests 'dev.jeddy.editor.rules.*'
# the demo: T toggles turn-based, Enter ends a turn, F casts a fireball, I opens the inventory
./gradlew :iso-demo:run
# scripted play with the combat log echoed to the console
./gradlew :iso-demo:run --args="--auto-turns --echo-combat"
./gradlew :iso-demo:run --args="--turn-based --auto-turns --echo-combat"
# the allocation capture of §30
./gradlew :iso-demo:run --args="--headless --perf 20 --warmup 6 --warmup-frames 150 --alloc-probe"
```

- In the editor (**F10** in the demo), the Rules panel (`panel Rules left` in the console) lists
  every kind; edit a feat's bonus, *Save rules*, and the fight picks it up on the next hit (the
  log says "Rules reloaded").
- With `--turn-based --echo-combat`, every attack prints "Aldric hits Skeletal warrior (21 vs AC
  17)": subtract the attacker's bonus (§6.4) to get the natural roll, count hits over a few
  rounds, and compare with L02 §6's table.
- In `jshell`, after a build, with the class outputs of `rules` and the modules it depends on
  (`core`, `compress`, `math`, `asset`: each `modules/<name>/build/classes/java/main`) joined by
  `:` in `--class-path`: `dev.jeddy.rules.Stat.modifier(9)` gives −1,
  `dev.jeddy.rules.Content.baseAttack(1, 5)` gives 3, and `dev.jeddy.rules.Modifiers.source(2, 7)`
  gives 33554439 (`0x02000007`).

## Exercises

**1. Hit chance table.** Write the probability of L02 §6.2 as a function and tabulate it for $n$
from −2 to 22; then run 20,000 `Rules.check(0, dc)` calls with one seed for three DCs and
compare.

<details><summary>Hint</summary>`new Rules(DemoContent.get(), 4, 1)` gives you a `check`; with modifier 0, $n = DC$. The natural 20/1 clipping shows at both ends.</details>
<details><summary>Solution</summary>

```java
static double p(int n) {
    if (n <= 1) return 19 / 20.0;
    if (n >= 21) return 1 / 20.0;
    return (21 - n) / 20.0;
}
```

tabulates 0.95 for $n \le 1$, then 0.95, 0.90, …, 0.05 for $n = 2 \dots 20$, and 0.05 beyond.
Measured with seed 1 and 20,000 calls (computed for this chapter): DC 1 → 0.9508, DC 2 → 0.9494,
DC 5 → 0.7984, DC 11 → 0.5008, DC 20 → 0.0510, DC 21 → 0.0485, DC 25 → 0.0498. The standard
deviation of a count of 20,000 at $p = 0.5$ is $\sqrt{20000 \cdot 0.25} = 70.7$, or 0.0035 as a
fraction (L02 §20.1), so every measurement is within two of those of the table, and the two
clipped ends agree with 0.95 and 0.05.
</details>

**2. Expected damage.** Reproduce the 6.545 of §4 with a loop over 100,000 `attack` calls of
Aldric against a skeletal warrior (give it a billion hit points so it never dies). Then change
the longsword's threat range to 18–20 in `items.json` and predict the new mean before measuring
it.

<details><summary>Hint</summary>The damage dealt is the hit points lost; `RulesTest` shows how to build the two creatures. For the content change, copy the JSON directory and build it with `DemoContent.build(dir)` as `SavesTest` does.</details>
<details><summary>Solution</summary>

With seed 1, 100,000 attacks took 655,246 hit points: **6.55** per swing, with 70.1 % hits and 7.0 %
criticals (the prediction is 70 % and $0.10 \cdot 0.70 = 7$ %). With threat 18 the first-roll
threat chance $q_1$ becomes $3/20 = 0.15$, so $E = 0.70 \cdot 8.5 + 0.15 \cdot 0.70 \cdot 8.5 =
5.95 + 0.8925 = 6.8425$; measured: **6.85**, with 10.6 % criticals ($0.15 \cdot 0.70 = 10.5$ %).
The wider range is worth 0.3 damage per swing, 4.6 % more.
</details>

**3. A new feat.** Add *Weapon specialization* (+2 damage, needs *Weapon focus* and BAB 4) in
`feats.json` with operations only; give it to Aldric; show the prerequisite check rejects it on
Dara. No Java should change.

<details><summary>Hint</summary>`requires` is a list of feat ids, `minBab` an int; §11.1 shows a feat's shape.</details>
<details><summary>Solution</summary>

```json
{"id": "weaponSpecialization", "name": "Weapon specialization", "description": "+2 damage with the chosen weapon.",
 "minBab": 4, "requires": ["weaponFocus"],
 "effects": [{"op": "modify", "stat": "damage", "type": "untyped", "value": 2}]},
```

and `"feats": ["weaponFocus", "toughness", "weaponSpecialization"]` on Aldric. Built through the
importer (computed for this chapter): Aldric's damage bonus becomes 6 (Strength 3, weapon training
1, specialization 2) and `hasFeat` is true; `meetsPrerequisites(dara, weaponSpecialization)` is
false, because her BAB is 3 (she also lacks Weapon focus). The untyped +2 stacks with weapon
training's untyped +1 by §6.6; as an *enhancement* bonus it would still count (no other
enhancement to damage), but two enhancement bonuses would not.
</details>

**4. Stacking bug hunt.** Give Brunhild a second armor bonus to AC from a spell in a test and
assert the AC is the larger, not the sum; then make the bonus *dodge* and assert the sum.

<details><summary>Hint</summary>`creatures.addModifier(c, Stat.AC, BonusType.ARMOR, 2, Modifiers.source(Modifiers.SOURCE_SPELL, 0), 10)`.</details>
<details><summary>Solution</summary>

Brunhild's AC is 15 with her chain shirt's armor +4. An armor +2 leaves it at 15 (`max(4, 2)`); an
armor +6 makes it 17 (the spell replaces the shirt while it lasts); a dodge +2 makes it 17 as
well, because dodge stacks with everything (all three computed for this chapter).
`ModifiersTest.sameTypeTakesTheLargestAndStackingTypesAdd` is the same test on a bare store. One
trap while writing it: remove the spell's modifier between cases with
`creatures.removeModifiers(c, source)`, not with `modifiers().remove(h)`. The latter frees the
handle without taking it out of the creature's list; the next `addModifier` reuses the handle,
the list then holds it twice, and `total` counts it twice (a first attempt at this exercise got
21 for the dodge case that way: the freed handle had been reused twice). `Modifiers.remove` is
public because `Creatures.release` needs it; the creature-level methods are the ones to call.
</details>

**5. All turns at the round boundary.** `RealTimeDriver.offset` spreads turns evenly. Change it
to start every turn at the round boundary (return 0) and watch the demo: explain, with the log's
timestamps, why the even spread was chosen.

<details><summary>Hint</summary>`advance` starts every turn that is due in one call (§25.2).</details>
<details><summary>Solution</summary>

With `offset` returning 0, every turn of the round is due at once: the loop in `advance` starts
all $n$ of them in the tick that begins the round, `onTurn` hands every creature its attacks in
the same tick, every attack animation starts on the same frame, and the log shows $n$ blocks of
lines with the same timestamp followed by six seconds of silence (measured on the seven-creature
test fight: with the even spread, 14 of the 360 ticks of two rounds start a turn and never more
than one each; with every offset 0, exactly two ticks, 1 and 181, start seven turns each). Neither the player nor the
animations can follow a fight that happens in bursts; the even spread keeps the order of
initiative and makes each creature act once per six seconds, which is what D100 asks for.
</details>

**6. Determinism.** Record the event ring of a scripted fight (`EncounterTest`'s script) with a
seed, replay it with the same seed, and assert the rings are identical; then change one creature's
Dexterity and find the first event that differs.

<details><summary>Hint</summary>Copy each record's eight fields out of the ring, then compare twice: field by field, and on the *outcome* fields only (kind, actor, target, natural roll, amount), ignoring the total and the AC or DC. Apply the Dexterity change before `start()`, so initiative sees it. Raising Dara's Dexterity 20 by one changes nothing (§3): the modifier moves only on an even score. Raise it by two.</details>
<details><summary>Solution</summary>

Two runs with seed 1 give 96 identical events (computed for this chapter). With Dara's base
Dexterity raised by 2 (score 22, modifier +6) the fight has 90 events and the initiative order
does not change. Field by field, the first difference is event 14, warrior B's attack of
opportunity on her: the same natural 18 and total 22, but against AC 21 instead of 20 (her AC
rose by one). Her own attacks' totals differ from event 16 on (her first critical: 27 becomes
28, because her attack bonus is now +9). None of those changed an outcome. Compared on the outcome
fields only (kind, actor, target, natural, amount), the first difference is event 59, her first
dagger against warrior B: natural 8, total 16 against AC 17 (a miss) becomes total 17 (a hit), and
the fights diverge from there. For seeds 4, 5 and 6 the first outcome differences are events 28,
12 and 12 (field by field: 26, 12 and 10; for seed 5 the order itself changed, so event 12 already
names a different actor). This is also why the engine rolls from one generator in one order: any
extra roll anywhere would shift every event after it, which `aSeedReplaysTheSameFight` would
catch.
</details>

**7. The monster's second knife.** Change the monster branch of `Rules.attack` so that a monster's
extra attacks come at full bonus, like a character's (the open decision of §5). Which test changes,
and by how much does the skeletal stalker's expected damage per round rise?

<details><summary>Hint</summary>The character branch uses `attackBonus(attacker, index)`, which knows the iterative count; a monster's iterative count is `1 + max(0, (bab - 1) / 5)` from its stat block's BAB.</details>
<details><summary>Solution</summary>

Replace `- (index > 0 ? 5 * index : 0)` with a penalty computed as in `attackBonus`:
`int iteratives = 1 + Math.max(0, (creatures.baseAttackBonus(attacker) - 1) / 5); int penalty =
index < iteratives ? 5 * index : 0;`. The stalker (BAB 1) then attacks at +2/+2. No current test
asserts the −3 directly, but `aSeedReplaysTheSameFight` and `bothDriversPlayTheSameFight` hash the
events of fights in which the stalker attacks twice, so their expected hashes would change if they
were golden values; they are not (they compare two runs with each other), so the suite still
passes, which is itself worth noticing: the behaviour is unpinned, which is why it is open. The
damage: L02 exercise 3 computes 0.761 per round at +2/−3 against AC 20 and 1.155 at +2/+2, a rise
of about a half.
</details>

**8. A one-round self-buff.** Write a condition with `"rounds": 1` applied by a self-targeted
spell, cast it in a test during the caster's turn, and show with `endTurn` when it expires. Then
make it last through the caster's next turn.

<details><summary>Hint</summary>§8: durations tick at the end of the affected creature's turn; a condition applied during that creature's own turn ticks that same turn.</details>
<details><summary>Solution</summary>

`applyCondition(c, row, 1)` during `c`'s turn, then `endTurn(c)`: the instance's rounds go 1 → 0
and `endCondition` removes it; `hasCondition` is false before the next turn starts. Writing
`"rounds": 2` makes it survive the first `endTurn` (2 → 1) and expire at the end of the next turn,
which is the tabletop's "lasts one round". A cleaner engine fix would be to tick a creature's
durations at the *start* of its turn instead, or to skip the tick in the turn the instance was
created in; either changes the three duration tests, which is why it is an exercise and not a
patch.
</details>

**9. Reflexes after a buff.** The *Reflexes* feat adds the Dexterity modifier to attacks of
opportunity when it is learned (§18.4). Give Dara the feat, then *cat's grace* (+4 enhancement to
Dexterity, write it as a spell) and show that her attacks of opportunity do not change. Propose a
fix.

<details><summary>Hint</summary>`opportunityAttacksLeft` reads `stat(c, AOO)`, which is 1 plus modifiers; the feat's modifier was computed from the Dexterity at learning time.</details>
<details><summary>Solution</summary>

`learnFeat` adds a permanent untyped +5 to `AOO` for Dara (modifier +5). After +4 Dexterity her
modifier is +7 but the stored modifier is still +5, so `opportunityAttacksLeft` is still 6. A fix
in the spirit of `stat`: do not store a number at all; in the `AOO` case of `Creatures.stat`, add
`abilityModifier(c, DEX)` when the creature has a feat with the `reflexes` flag (which needs the
feat flags visible to `Creatures`, or a per-creature cached flag set like the condition flags).
Then `itemsHealAndFeatsHavePrerequisites`'s `1 + 5` still holds and a buffed Dara gets 8.
</details>

**10. The ring overflows.** Create a `Rules` and call `applyCondition` and `removeCondition` 600
times on one creature without draining the events. What do `oldest()`, `next()` and `dropped()`
return, and what does reading event 0 do? Then write a reader that never throws.

<details><summary>Hint</summary>Count the records each pair writes (`applyCondition` adds a `CONDITION_ON`, `removeCondition` a `CONDITION_OFF`), remember that `Rules` creates its ring with 1,024 records, and read `Events.oldest` and `slot` (§17.2).</details>
<details><summary>Solution</summary>

Each apply/remove pair writes two records, so 1,200 records went into a ring of 1,024: `next()` is
1200, `oldest()` is 176, `dropped()` is 176, and `kind(0)` throws `IndexOutOfBoundsException("event
0")`. A safe reader does what `Combat.drainEvents` does: `if (eventsRead < ev.oldest())
eventsRead = ev.oldest();` before its loop, and accepts that it lost the records in between.
</details>

**11. Joining in the middle.** In a test, start a fight of three creatures, advance to the second
turn, and `join` a creature whose initiative (choose the seed) puts it first in the order. Assert
that the current creature keeps its turn, that the newcomer's index is 0, that it is flat-footed,
and that it acts first in the next round.

<details><summary>Hint</summary>`seedWhereFirstD20Is(20)` makes the *next* roll a 20; set the engine's seed with `r.rng().setSeed(...)` just before `join`.</details>
<details><summary>Solution</summary>

After `e.start()` and two `nextTurn()` calls, `turnIndex` is 1. Reseed so that `rollInitiative`
rolls a 20, `join(newcomer)`: `before` places it at 0 (20 + its modifier beats the others for any
reasonable seed), `turnIndex` becomes 2 and `current()` is unchanged; `hasFlag(newcomer,
FLAG_FLAT_FOOTED)` is true; after the remaining turn of this round, the next `nextTurn()` wraps to
index 0, increments the round and returns the newcomer. `creaturesJoinAndLeaveMidFight` checks
the same things without forcing the order.
</details>

## Glossary

- **Ability score, modifier:** one of six whole numbers describing a creature; $\lfloor (s -
  10)/2 \rfloor$, what it adds to rolls (§3).
- **Action budget, standard / move / swift / full-round / 5-foot step:** what a turn allows (§9.2,
  §23).
- **Attack of opportunity, provoke, threaten:** a free attack outside one's turn against a creature
  that leaves one's reach or casts next to it (§9.3, §24.4).
- **Base attack bonus (BAB), progression:** the class-and-level part of the attack bonus; how it
  grows (full, three quarters, half) (§6.1).
- **Check, DC, AC, natural, total:** $d20 + b \ge DC$; the number to reach; the DC of an attack;
  the die's face; face plus bonus (§4; L02 §6).
- **Condition, instance, flag:** a state with effects; one creature's copy of it with a duration;
  the engine-known behaviours (§8).
- **Definition, kind, row, field:** one piece of content; its table; its index; its column
  (§11).
- **Driver:** what decides when the next turn starts: turn-based or real-time (§25).
- **Dying, dead, stable, dies at zero:** below zero and losing points; at $-\text{Con}$; stabilized
  on a 20; a monster destroyed at 0 (§7).
- **Effect, operation:** what a definition does; one of its nine instructions (§11.1–§11.2).
- **Encounter, round, turn, initiative:** one fight; six seconds in which everyone acts; one
  creature's share; the roll that orders them (§9.1, §24).
- **Event ring:** the fixed-record log the rules write and the UI drains (§17).
- **Finesse, two-handed:** Dexterity on the attack roll; one and a half times Strength on damage
  (§6.4).
- **Flat-footed:** before one's first turn: no Dexterity to AC, no attacks of opportunity (§1,
  §8).
- **Full attack, iterative attack, extra attack:** all of a creature's swings in a full-round
  action; the ones a high BAB grants at −5 each; the ones effects grant at full bonus (§5).
- **Handle, store, scratch:** an `int` naming a row; parallel arrays; a reused array for a
  computation's temporaries (§13, §15; L03).
- **Offset (real time):** $iR/n$, when the $i$th turn of a round is due (§10.1).
- **Saving throw, Fortitude / Reflex / Will, negates / half:** the target's check against a
  spell's DC; its three kinds; what a success does (§4, §21.1).
- **Schema, stride, width:** the description of the kinds; ints per row; ints per field (§11.3,
  §26.1).
- **Slot (spell):** one casting of a spell level per day (§6.7).
- **Source:** the packed (kind, id) a modifier remembers so that it can be removed with its cause
  (§13.2).
- **Stacking, bonus type:** which modifiers add and which take the largest; the label that decides
  it (§6.6).
- **Stat block:** a creature's bases and the numbers derived from them (§6).
- **Temporary hit points:** a buffer absorbed before hit points (§7).
- **Threat range, confirmation, multiplier, critical:** L02 §8.
- **Touch attack, touch AC:** an attack that ignores armor, shield and natural bonuses; the AC it
  faces (§6.3, §21.3).

---

*Code this chapter builds or quotes: `rules.Stat`, `BonusType`, `Modifiers`, `Conditions`,
`Creatures`, `Content`, `Events`, `Rules`, `ActionBudget`, `Encounter`, `TurnListener`,
`TurnBasedDriver`, `RealTimeDriver`; `asset.format.RulesSchema`, `RulesData`;
`cooker.rules.RulesImporter`; `editor.rules.RulesSource` and `panel.RulesPanel` (by reference);
`math.Dice`, `Rng` (built in L02); the demo's `Combat`, `CombatLog`, `Spells`, `Party`,
`SkeletonAi`, `DemoGame`; the content under `samples/iso-demo/assets/rules/`; the tests
`CreaturesTest`, `ModifiersTest`, `RulesTest`, `EncounterTest`, `SavesTest`, `DemoContent`,
`RulesImporterTest`, `RulesSourceTest`. Decisions: D96, D97, D98, D99, D100, D104, D115, D117 in
[DESIGN §17](../DESIGN.md), and DESIGN §20 (the rules), §21 (saves), §4 (the performance rules),
§8.3 (the generator); the format in [formats/jrules.md](../formats/jrules.md). Lessons:
[L00](lessons/00-foundations.md), [L02](lessons/02-dice-and-probability.md) (dice, the generator,
`check`, `attack` and all the probability), [L03](lessons/03-data-layout.md) (stores and handles),
[L04](lessons/04-binary-formats.md) (binary files). Chapters: [01](01-architecture.md) (threads,
the game loop, the fixed step), [11](11-physics-navigation-crowds.md) (the crowd, movement
budgets, threat crossings), [13](13-ui.md) (the combat log and the HUD), [14](14-editor.md) (the
Rules panel), [16](16-saves.md) (the save code in full), [22](22-components.md) (the skeletons'
behaviours).*

[← 14 The level editor](14-editor.md) · [Index](README.md) · [22 Components →](22-components.md)
