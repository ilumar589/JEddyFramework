# jeddy-rules

Module `dev.jeddy.rules`. The d20 game-mechanics layer, shaped like Pathfinder 1e with original names and content: creatures as SoA statistics, typed modifiers that stack by the d20 rules, dice and checks, effects as data-driven operation lists, conditions with durations, encounters with initiative and an action economy, a turn-based and a real-time driver over the same encounter, and an event log without strings. Pure Java, deterministic for a seed, and allocation-free once the stores are sized (`Rules` sizes them from its creature capacity).

A game creates a `Rules` over a `Content` (a cooked `.jrules` read by `asset.format.RulesData`), builds creatures from character and monster definitions, and calls `attack`, `castSpell`, `save`, `applyCondition`, `damage`, `heal`; an `Encounter` runs the rounds and one of the two drivers decides when each creature's turn starts. The iso demo's `Combat` is the reference integration.

## Key types

- `Rules`: the engine: `buildCharacter`/`buildMonster`, `check` (d20 + modifier against a DC, naturals clipped), `attack` (threat ranges confirmed by a second roll, iteratives, attacks of opportunity), `spellDc`/`hasSlot`/`castSpell`, `save`, `damage`/`heal`/`applyCondition`/`removeCondition`, `startTurn`/`endTurn`, `applyEffects` (the operation interpreter), `canAct`/`isDead`
- `Content`: typed access to the cooked definitions by `(kind, row, field)`: `find(kind, id)`, `name`, `spellLevel`, `itemDamage`, `attackBonus`, the effect operation ranges
- `Creatures`: the SoA creature store (abilities, level, class, base attack bonus, saves, hit points, equipment, feats, slots, team) and `stat(creature, Stat)`, which adds the base and the stacked modifiers
- `Modifiers`: typed bonuses with a source and a duration; `total` implements stacking (same type: the largest; untyped, dodge and circumstance add; penalties always add)
- `Conditions`: condition instances with remaining rounds; `Creatures.flags` caches the union of the active ones (`CANNOT_ACT`, `FLAT_FOOTED`, `PRONE`, `HELPLESS`)
- `Stat`, `BonusType`: the statistic and bonus-type indices the content and the engine share
- `Events`: a ring of resolutions (`ATTACK_HIT`, `CRITICAL`, `DAMAGE`, `SAVE_FAIL`, `CONDITION_ON`, `TURN`, `DEATH`, ...) with actor, target, natural, total and DC; the UI formats them
- `Encounter`: participants, initiative (d20 + modifier; ties by Dexterity, then join order), rounds, the `ActionBudget` per turn (standard, move, swift, 5-foot step), attacks of opportunity; `TurnListener` tells the game whose turn starts
- `TurnBasedDriver`, `RealTimeDriver`: the two ways to advance an encounter; the real-time driver starts the *i*th of *n* turns *i/n* of a six-second round in, and `sync` resumes the clock after a switch

Content is JSON under `assets/rules/` (one file per kind) cooked by `cooker.rules.RulesImporter` into a `.jrules` ([docs/formats/jrules.md](../../docs/formats/jrules.md)); the editor's Rules panel edits the JSON through `asset.format.RulesSchema`, so the editor does not depend on this module. [docs/learn/15-rules.md](../../docs/learn/15-rules.md) derives the mathematics and walks through the code; [lesson L02](../../docs/learn/lessons/02-dice-and-probability.md) goes deeper into the probability.

## Threading

Everything runs on the game thread. A round of attacks, spells, saves and conditions allocates nothing (`RulesTest.aRoundOfAttacksAllocatesNothing`, `aCrowdedFightWithinCapacityAllocatesNothing`); building creatures and growing a store past the capacity given to `Rules` do allocate and are cold.

## More

- Design: [DESIGN.md](../../docs/DESIGN.md) §20, D96–D100
- API: `./gradlew :rules:javadoc`, or the Javadoc site (`./gradlew :site:javadocSite`)
- Tests: `./gradlew :rules:test` (they cook the demo's `assets/rules` in memory, so run them from the repository)
