# 15 — Rules: a d20 core, content as data, encounters in two tempos

*Milestone M15. Read [01 Architecture](01-architecture.md) (SoA stores, handles) and
[11 Physics, navigation and crowds](11-physics-navigation-crowds.md) (where movement lives) first;
[14 The level editor](14-editor.md) explains the panel machinery the Rules panel reuses.*

## 1. Goal and result

Before M15 the demo's fights were a hit-point counter in `Enemies`: a swing landed when the
animation said so and took a fixed amount. A party CRPG needs a **rulebook**: characters with
abilities, classes and feats; attack rolls against an armor class made of typed bonuses; damage
dice, critical hits and saving throws; conditions that last a number of rounds; spells with slots;
and an encounter structure that works both **turn by turn** and in **real time with pause**, the
two ways Pathfinder: Wrath of the Righteous lets you play the same fight.

M15 built that as the `rules` module (pure Java, data-oriented, deterministic), the content
pipeline around it (JSON → `.jrules` → the editor's Rules panel), and the demo on top: the six
adventurers and the crypt's skeletons are creatures built from the content, every swing in the
crypt is an attack roll, fireballs are a spell with a Reflex save, **T** switches to turn-based
play with an initiative order and an action budget, and the combat log at the bottom right reads
the engine's event ring.

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

Brunhild's AC is 14 on the first line and 15 on the second: she was flat-footed (no Dexterity
bonus) until her first turn. That one number is most of this chapter.

## 2. Background: the mathematics of a d20 game

A d20 system is a probability model simple enough to work out by hand, which is exactly why it
is testable to the last point. Everything below is derived from three definitions.

### 2.1 Modifiers from scores

An ability score $s$ (3–18 for mortals, 10 average) gives a modifier

$$m(s) = \left\lfloor \frac{s - 10}{2} \right\rfloor,$$

so 16 → +3, 8 → −1, 9 → −1 (floor, not truncation: `Math.floorDiv(score - 10, 2)` in
`Stat.modifier`, because `(9 - 10) / 2` in Java is 0). Every number a creature rolls with is a
sum of such modifiers and flat bonuses.

### 2.2 The check and its probability

A **check** is $d20 + b \ge DC$ where $b$ is the bonus and $DC$ the difficulty class, with two
exceptions: a natural 20 always succeeds and a natural 1 always fails. Let $n = DC - b$ be the
roll needed. The die is uniform, so

$$P(\text{success}) = \begin{cases} 19/20 & n \le 1 \\ (21 - n)/20 & 2 \le n \le 20 \\ 1/20 & n \ge 21 \end{cases}$$

The middle line is the one to remember: **each point of bonus or DC is 5 %**, and it is linear.
Aldric's longsword attack is +10 and a skeletal warrior's AC is 17, so $n = 7$ and he hits
$14/20 = 70\%$ of the time. Against AC 20 it is 55 %, against a flat-footed warrior (AC 15)
80 %. The exceptions clip the line at 5 % and 95 %: a +100 bonus still misses on a 1 (a test
asserts exactly that).

### 2.3 Expected damage and critical hits

Dice `NdS+B` have mean $N(S+1)/2 + B$: a longsword's `1d8` averages 4.5, `5d6` averages 17.5.
A hit deals the weapon dice plus the damage bonus (Strength, feats): Aldric's `1d8+4` averages
8.5.

A **critical** needs two things: the natural roll lands in the weapon's threat range (19–20 for
a longsword: $P = 2/20$ when those rolls also hit, which they do here since $n = 7$), and a
*second* roll, the confirmation, also hits ($P = 0.70$). A confirmed critical multiplies the
damage (×2 for the longsword). So per swing against AC 17:

$$E[\text{damage}] = P(\text{hit}) \cdot 8.5 + P(\text{threat}) \cdot P(\text{confirm}) \cdot 8.5 = 0.70 \cdot 8.5 + 0.10 \cdot 0.70 \cdot 8.5 = 5.95 + 0.595 \approx 6.5$$

The critical adds only 0.6 of the 6.5: in d20 games criticals are flavour, the hit chance is
the economy. A threat range of 18–20 (a scimitar) raises the second term to 0.89; a ×3 weapon
raises it to 1.19. The engine's `attack` does precisely this computation with real dice:
`check(attackBonus, ac)`, then if `natural >= threat` a second `check`, then the damage dice
and the damage bonus rolled `multiplier` times and added (rolling twice, not doubling one
roll: the two have the same mean but the sum has less spread, which [lesson L02](lessons/02-dice-and-probability.md)
quantifies).

**Iterative attacks.** A base attack bonus of 6 or more grants a second attack at −5 (`+6/+1`),
11 a third (`+11/+6/+1`): $1 + \lfloor (BAB - 1)/5 \rfloor$ attacks. Each iterative's hit chance
drops by 25 points, so the second attack of a +6 fighter against AC 17 hits 20 % of the time: a
full attack is worth it only when the target is easy to hit, which is the trade-off the action
economy (§2.5) is built around.

### 2.4 Saving throws and the DC of a spell

A spell's DC is $10 + \text{spell level} + m(\text{casting ability})$: Cassius (Int 20, +5)
casting *fireball* (level 3) sets DC 18. A target rolls $d20 + \text{Reflex}$; Dara (Reflex
+12) needs a 6, saves 75 % of the time and then takes half. Expected damage from `5d6` (17.5):

$$E = 0.25 \cdot 17.5 + 0.75 \cdot 8.75 \approx 10.9,$$

against 14.4 for a skeletal warrior with Reflex +4 (needs 14: saves 35 %). The engine's `save` is the
same `check` with the stat picked by the save kind, and the spell's `saveResult` (`half`,
`negates`) decides what the operations do with a success.

### 2.5 Stacking

Bonuses have **types** (armor, shield, natural, dodge, enhancement, morale, untyped, penalty,
...). The rule that keeps the numbers sane: bonuses of the same type do not stack, the largest
applies — except untyped, dodge and circumstance bonuses, which add, and penalties, which
always add. For a statistic with modifiers $v_i$ of types $t_i$:

$$\text{total} = \sum_{t \in \text{stacking}} \sum_{t_i = t} v_i + \sum_{t \in \text{non-stacking}} \max_{t_i = t} v_i.$$

Dara's AC: 10 + size +1 (small) + leather armor +2 (armor) + Dex +5 (capped by the armor at
+6) + Dodge feat +1 (dodge) + uncanny dodge +1 (dodge) = 20: the two dodge bonuses both count. A
second suit of armor would not, and neither would a second +2 armor spell. `Modifiers.total`
implements the formula with a scratch array per type (§3.2).

### 2.6 Hit points, dying and death

Damage subtracts temporary hit points first, then hit points. At 0 or below a creature is
**dying**: unconscious, losing a point a round, with a chance to stabilize each round; at
$-\text{Con}$ (the score, not the modifier) it dies. Mindless undead "die at zero": the
skeletons are destroyed outright. The log above shows both: Cassius falls to dying and
stabilizes, the adept at 0 is destroyed.

### 2.7 Rounds, initiative and the real-time clock

Combat runs in **rounds** of six seconds. At the start everyone rolls initiative, $d20 +$
initiative modifier, and acts in descending order each round; ties go to the higher Dexterity,
then to whoever joined first. Each turn grants an **action budget**: a standard action (an
attack, a spell), a move action, a swift action and one free 5-foot step; a full-round action
(a full attack with iteratives) spends standard and move together.

Real time with pause runs the *same* rounds on a clock. If every creature acted at the instant a
round began, the fight would look like a flip-book. The driver instead starts the $i$th of $n$
creatures' turn at

$$t_i = \frac{i}{n} \cdot 6\ \text{s}$$

into the round: with six party members and four skeletons, a turn starts every 0.6 s, each
creature still acts once per six seconds, and the order is still the initiative order. Switching
to turn-based mid-fight keeps the round, the turn index and each creature's remaining budget;
switching back resyncs the clock to the current turn's offset (`RealTimeDriver.sync`).

## 3. The code

### 3.1 Dice in an `int`

[`math.Dice`](../../modules/math/src/main/java/dev/jeddy/math/Dice.java) packs `NdS+B` into one
`int` so that content tables hold damage expressions as plain cells and rolling allocates
nothing:

```java
public static int pack(int count, int sides, int bonus) {
    ...
    return count << 24 | sides << 16 | (bonus & 0xFFFF);
}

public static int roll(int dice, Rng rng) {
    int n = count(dice);
    int s = sides(dice);
    int total = bonus(dice);
    if (s > 0) {
        for (int i = 0; i < n; i++) {
            total += rng.nextInt(s) + 1;
        }
    }
    return total;
}
```

Bits 24–31 hold the count, 16–23 the sides, 0–15 the signed bonus (`(short) (dice & 0xFFFF)`
sign-extends it back). `times(dice, 2)` doubles the count and the bonus, which is how a
critical multiplies. The `Rng` is xoshiro256** seeded through SplitMix64 (chapter 11): the
bounded `nextInt(bound)` uses Lemire's multiply-and-shift with a rejection loop so every face is
exactly as likely as the others — a biased die would make §2's arithmetic wrong by a fraction
of a percent, which the tests would catch only by luck.

### 3.2 Creatures and modifiers: two SoA stores

[`Creatures`](../../modules/rules/src/main/java/dev/jeddy/rules/Creatures.java) holds everything
per creature in parallel arrays (abilities, level, class row, base attack bonus, base saves,
hit points, size, speed, equipment, condition bits and rounds, feats, slots, a team) behind
generational `int` handles, and resolves a statistic by adding its base and the stacked bonus:

```java
case Stat.AC -> {
    int dex = hasFlag(c, Content.FLAG_LOSE_DEX) ? 0 : Math.max(0, abilityModifier(c, Stat.DEX));
    return 10 + Content.sizeModifier(size[c]) + Math.min(dex, maxDexToAc[c]) + bonus(c, Stat.AC);
}
case Stat.ATTACK -> {
    int str = abilityModifier(c, Stat.STR);
    int dex = abilityModifier(c, Stat.DEX);
    int best = finesse[c] ? Math.max(str, dex) : str;
    return bab[c] + best + Content.sizeModifier(size[c]) + bonus(c, Stat.ATTACK);
}
```

`FLAG_LOSE_DEX` is what *flat-footed* sets: that is Brunhild's 14 against 15. `bonus(c, stat)`
asks [`Modifiers`](../../modules/rules/src/main/java/dev/jeddy/rules/Modifiers.java), the second
store (stat, type, value, source, remaining rounds per modifier, with the creature's modifier
handles in an `IntList`), for the stacked total of §2.5:

```java
int t = type[h];
int v = value[h];
if (BonusType.stacks(t)) {
    stacked += v;
} else if (v > scratch[t]) {
    scratch[t] = v; // the largest bonus of a type; a same-type penalty would be PENALTY
}
...
for (int t = 0; t < BonusType.COUNT; t++) {
    if (!BonusType.stacks(t)) {
        stacked += scratch[t];
    }
}
```

A scratch array of one slot per bonus type (cleared before the loop) is the whole data structure:
no map, no sorting, no allocation. `CreaturesTest` builds each of the six characters from the
content and asserts every derived number against the hand computation in its comments — the
kind of test that only works because the arithmetic is small enough to do on paper.

### 3.3 Checks, attacks and damage

[`Rules`](../../modules/rules/src/main/java/dev/jeddy/rules/Rules.java) is the engine proper.
The check is three lines and leaves the natural roll and the total for the event:

```java
public boolean check(int modifier, int dc) {
    lastNatural = rng.d20();
    lastTotal = lastNatural + modifier;
    return lastNatural == 20 || (lastNatural != 1 && lastTotal >= dc);
}
```

`attack(attacker, target, index, bonus, flags)` gathers the dice, threat range and multiplier
from the equipped weapon or the monster's attack row, computes the attack bonus of the `index`th
iterative, asks `armorClass(target, flags)` for the AC the attack faces (touch attacks subtract
the armor, shield and natural totals, which is why `Modifiers.totalOfType` exists), rolls,
confirms a threat with a second roll, and deals damage:

```java
boolean hit = check(attackBonus, ac);
int natural = lastNatural;
int total = lastTotal;
if (!hit) {
    events.add(Events.ATTACK_MISS, attacker, target, natural, total, ac, index, weapon);
    return MISS;
}
int result = HIT;
if (natural >= threat && natural != 1) {
    if (check(attackBonus, ac)) {   // confirm: a second roll against the same AC
        result = CRIT;
    }
}
```

Note `natural` captured before the confirmation: after it, `lastNatural` is the *confirmation*
roll. The first version of the test asserted `lastNatural` and failed on a confirmed critical,
which is how the event record got its own natural.

`damage` absorbs temporary hit points first, then subtracts and calls `checkDeath`:

```java
boolean dies = creatures.diesAtZero(target) || hp <= -creatures.ability(target, Stat.CON);
if (dies) { ... applyCondition(target, deadRow, Conditions.INDEFINITE); ... }
else if (dyingRow >= 0 && !hasCondition(target, dyingRow)) { applyCondition(target, dyingRow, ...); }
```

*Dead* and *dying* are conditions from the content like any other (§3.5), found by id at
construction; the engine knows their rows, not their behaviour.

### 3.4 Events: the log without strings

Every outcome is appended to [`Events`](../../modules/rules/src/main/java/dev/jeddy/rules/Events.java),
a ring of fixed-width `int` records (kind, actor, target, natural, total, DC or AC, and two
payload ints). The engine never builds a string; the demo's `CombatLog` drains the ring on the
game thread into its character buffer ("Skeletal warrior hits Brunhild (14 vs AC 14)") and the UI
draws that. The same ring is what a turn-based UI reads to animate, what the save system will
not need (events are transient), and what tests assert on.

### 3.5 Content as operations

Feats, spells, items, class features and conditions are **definitions** in JSON, cooked into the
tables of [`.jrules`](../formats/jrules.md). None of them is a Java class. A definition's
behaviour is a list of operations the engine interprets (D98),
[`assets/rules/feats.json`](../../samples/iso-demo/assets/rules/feats.json):

```json
{"id": "dodge", "name": "Dodge", "description": "+1 dodge bonus to AC.", "minDex": 13,
 "effects": [{"op": "modify", "stat": "ac", "type": "dodge", "value": 1}]},
{"id": "twoWeaponFighting", "name": "Two-weapon fighting", "minDex": 15,
 "effects": [{"op": "extraAttack", "value": 1},
             {"op": "modify", "stat": "attack", "type": "penalty", "value": -2}]}
```

and a spell adds the delivery around its operations:

```json
{"id": "fireball", "arcaneLevel": 3, "school": "evocation", "action": "standard", "range": 400,
 "shape": "burst", "radius": 20, "targets": "all", "save": "reflex", "saveResult": "half",
 "effects": [{"op": "damage", "dice": "5d6", "damageType": "fire"}]}
```

In the file every operation is eight ints (`op, stat, type, value, dice, rounds, ref,
damageType`) in one flat array that a definition's `OPS` field slices; `Rules.applyEffects`
runs the slice with a source and a target. `MODIFY` adds a modifier whose source is the
definition (so removing the feat or expiring the spell removes exactly its modifiers,
`removeBySource`); `CONDITION` applies a condition row for `rounds`; `DAMAGE` rolls the dice.
The whole vocabulary is nine operations, and every rule in the demo's content is written in
them. Some feats carry only a *flag* (`powerAttack`, `reflexes`): a rule the engine implements
in code but the content switches on — the escape hatch for the few things an operation list
cannot say.

[`RulesSchema`](../../modules/asset/src/main/java/dev/jeddy/asset/format/RulesSchema.java) is
the single description of all this: the kinds, their fields and types. The cooker's
`RulesImporter` validates against it (every reference resolves, dice parse, enums exist, feat
prerequisites name known feats) and writes the tables; the engine's `Content` resolves the
field indices it needs once by name; the editor's Rules panel generates its property grid from
the same schema and validates the same way before writing the JSON back, which the cooker's
watcher then re-cooks and the game hot-reloads (`DemoGame.buildCombat` rebuilds the fight's
creatures when the asset's version changes).

### 3.6 Encounters, budgets and the two drivers

[`Encounter`](../../modules/rules/src/main/java/dev/jeddy/rules/Encounter.java) holds the
participants in initiative order (three parallel `IntList`s: creature, initiative, join
sequence), the round, the turn index and one [`ActionBudget`](../../modules/rules/src/main/java/dev/jeddy/rules/ActionBudget.java)
— a bit set per creature:

```java
public static final int STANDARD = 1;
public static final int MOVE = 2;
public static final int SWIFT = 4;
public static final int FULL_ROUND = STANDARD | MOVE;
public static final int STEP = 8;
public static final int ALL = STANDARD | MOVE | SWIFT | STEP;
```

`useAction(creature, action)` clears bits; a move action may be paid with the standard bit
when the move bit is spent (you can always trade a standard for a move), a full-round action
needs both. `nextTurn` ticks the current creature's durations (so a one-round effect lasts until
its caster's next turn, the tabletop convention), advances, and on wrapping starts a new round
and re-grants budgets. Attacks of opportunity are the engine's reaction to what the *game*
reports: `provoke(provoker, threateners, n)` is called by the demo when `TurnBasedNav` says a
creature crossed a threat zone or when it casts next to an enemy; each threatening creature that
can act and still has an attack of opportunity this round (`opportunityAttacksLeft`, more with
the *Reflexes* feat) makes one attack at full bonus.

The two drivers only decide *when* `nextTurn` happens.
[`TurnBasedDriver.endTurn`](../../modules/rules/src/main/java/dev/jeddy/rules/TurnBasedDriver.java)
is called when the player presses Enter or the AI finishes; it skips creatures that cannot act
(their turn still happens, so their durations tick and the dying roll to stabilize).
[`RealTimeDriver.advance(dt)`](../../modules/rules/src/main/java/dev/jeddy/rules/RealTimeDriver.java)
moves a clock and starts every turn that is due:

```java
public float offset(int i) {
    int n = encounter.size();
    return n == 0 ? 0 : i * ROUND_SECONDS / n;
}
```

`EncounterTest` runs the same scripted fight through both drivers and asserts the same numbers,
which is the property the owner asked for: one fight, two tempos, no second rule set.

### 3.7 The demo: `Combat`

[`Combat`](../../samples/iso-demo/src/main/java/dev/jeddy/samples/iso/Combat.java) is the glue
between the rules and the world the earlier milestones built. It builds the party from the
`characters` kind and each skeleton from `monsters` when they spawn, keeps the creature handle
per party member and per enemy, and translates:

- an attack animation's hit frame into `rules.attack` (and the result into `Enemies.flinch`, since M22 the `HURT` signal `SkeletonAi.flinch` answers,
  `kill`, the party's `down`);
- `Spells.cast` into a slot check (`hasSlot`), a Reflex save per creature in the burst and
  `applyEffects` with the spell's operations;
- the crowd's distances (meters, `METERS_PER_FOOT = 0.3`) into reach and threat;
- the engine's events into the `CombatLog` lines, dropping the noisy flat-footed condition
  events.

In real time the `RealTimeDriver` runs inside `fixedUpdate`; on **T** the game stops the crowd,
and `TurnBasedDriver` waits for the player (click to move within the budget, drawn as a range
on the floor; attack by clicking a skeleton; Enter ends the turn) or the skeleton AI (move to the
nearest member, attack, end). `--auto-turns` scripts both so the perf capture and the smoke
test play without a player. Engagement and disengagement are events to the rules
(`engage`/`disengage`) so attacks of opportunity fire when a member walks away.

## 4. Why it is built this way

- **A PF1e-shaped core with original content (D96).** The mechanics are the point of the
  genre and are not protected; the published text and names are, and are not reproduced. A
  simpler original d20 would have shipped faster and been too flat to grow toward WotR-style
  builds; the full SRD (skills, size categories, maneuvers, multiclassing) would have tripled
  the milestone. §20's "Not in M15" lists what waits.
- **Content as JSON cooked to binary and edited in the editor (D97).** The owner's choice.
  Loading JSON at runtime would parse at load and validate nothing before play; content as Java
  builders would need a recompile per tweak and a programmer per designer. The schema-driven
  panel costs one description of the kinds and gives the editor, the importer and the file for
  free.
- **Effects as operation lists (D98).** An effect class hierarchy would make every new feat
  Java, and data files could not express it; a scripting language is M17's graphs, which will
  *call* operations, not replace them.
- **Checks exactly as the tabletop states them (D99).** Natural 20/1 clipping, confirmation
  rolls, iteratives from BAB, `10 + level + modifier` DCs, temporary hit points first, dying
  between 0 and −Con. Fidelity here is cheap and makes the numbers in the log recognizable to
  anyone who has played.
- **One encounter, two drivers (D100).** Two rule sets would be two sources of truth for the
  same numbers; "real time as turn-based with instant turns" would clump every action at the
  round boundary, which neither the animations nor the player could follow. Movement stays in
  `physics`; the rules consume distances and crossings the game reports.
- **The engine owns no strings and allocates nothing in a fight.** Events are int records; the
  log formats them on the game thread into a reused buffer; `stat` resolution and the modifier
  loops use scratch arrays. `CreaturesTest.statResolutionAllocatesNothing` proves the hot
  query, and the demo's perf capture stays at 0 B/frame with a fight on.

## 5. Pitfalls met on the way

- **Which natural?** After a confirmed threat, `lastNatural` is the confirmation roll; the
  event carries the attack's own natural (§3.3).
- **Size counted twice.** `Creatures.stat` adds the size modifier itself; the hearthkin
  ancestry's content also carried a size bonus, so Dara had +2 for being small. The content
  was wrong, the engine right; the hand-computed test caught it.
- **Flat-footed everywhere.** Everyone is flat-footed until their first turn, so the first
  seconds of a real-time fight favour whoever won initiative — correct, but surprising in the
  log until you know; the demo drops the condition events for it.
- **A locked crypt.** The scripted march never reached the skeletons: every doorway in the
  crypt was a closed door, because the dungeon kit's doorway prefab carries a door leaf as a
  child node and the cooker rightly bakes it as collision. A navmesh probe found the gap, a
  glTF import setting excludes the leaf (D104, chapter 10), and doors become interactables in
  M23.
- **The panel's per-frame allocation.** A schema-driven property grid is easy to write with
  strings and lists per frame; the Rules panel keeps a path stack, a text-buffer pool and
  cached id lists per content version instead (chapter 14's rules for panels apply).

## 6. Performance

- `CreaturesTest.statResolutionAllocatesNothing`: every `Stat` of a creature with feats and
  modifiers, 0 bytes.
- The modifier total is $O(\text{modifiers} + \text{types})$ per stat with no allocation;
  creatures cache nothing, which keeps every rule change instantly visible and is cheap at the
  sizes of a CRPG fight (tens of creatures, tens of modifiers each).
- The perf capture (`--headless --perf 20 --warmup 6 --warmup-frames 150 --alloc-probe`; CI
  runs 120 s) runs the scripted march and fights with the party invulnerable and slots
  unlimited, so the rules run the whole time; the game thread stays at 0 B/frame.
- **What the capture caught.** The first runs of the finished demo were not at zero, and each
  miss was a store growing: `Conditions` doubling 32 → 64 (896 B) when a fireball left enough
  skeletons dead; the encounter's order lists growing 133 → 200 entries (2448 B, three
  `IntList`s) when the 134th skeleton joined the fight; and `Conditions` doubling again at
  1232 (32 KB) because a destroyed creature kept its instances, so every respawned skeleton
  leaked its `dead`. The fixes are the same shape: `Rules` sizes every store from its creature
  capacity (sixteen modifiers, four condition instances, one budget and one order slot per
  creature) and `Creatures.destroy` gives both modifiers and condition instances back. The
  tests `aCrowdedFightWithinCapacityAllocatesNothing`,
  `destroyedCreaturesReleaseTheirConditionsSoRespawnsDoNotGrowTheStore` and
  `everyCreatureJoiningAndLeavingAllocatesNothing` keep each one fixed. The lesson for the
  reader: a growable store is an allocation waiting for a longer run; size it from a number the
  game knows, and test the long run.

## 7. Try it

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
```

In the editor (F10), the Rules panel (`panel Rules left` in the console) lists every kind; edit a
feat's bonus, *Save rules*, and the fight picks it up on the next hit.

## 8. Exercises

1. **Hit chance table.** Write the probability of §2.2 as a function and tabulate it for $n$
   from −2 to 22; then run 20 000 `Rules.check(0, dc)` calls with one seed for three DCs and
   compare. Hint: `Rng(seed)`, and the natural 20/1 clipping shows at both ends.
2. **Expected damage.** Reproduce the 6.5 of §2.3 with a loop over 100 000 `attack` calls of
   Aldric against a skeletal warrior (reset the target's hit points each time or give it
   millions). Then change the longsword's threat range to 18–20 in `items.json` and predict
   the new mean before measuring it.
3. **A new feat.** Add *Weapon specialization* (+2 damage, needs *Weapon focus* and BAB 4) in
   `feats.json` with operations only; give it to Aldric; show the prerequisite check rejects it
   on Dara. No Java should change.
4. **Stacking bug hunt.** Give Brunhild a second armor bonus to AC from a spell in a test and
   assert the AC is the larger, not the sum; then make the bonus *dodge* and assert the sum.
5. **A fourth tempo.** `RealTimeDriver.offset` spreads turns evenly. Change it to start every
   turn at the round boundary and watch the demo: explain, with the log's timestamps, why the
   even spread was chosen.
6. **Determinism.** Record the event ring of a scripted fight (`EncounterTest`'s script) with a
   seed, replay it with the same seed, and assert the rings are identical; then change one
   creature's Dexterity by one and find the first event that differs. Hint:
   `seedWhereD20sAre(a, b)` in `RulesTest` finds seeds for the rolls you want.

---

[← 14 The level editor](14-editor.md) · [Index](README.md) · [22 Components →](22-components.md)
