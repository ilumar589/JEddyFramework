# `.jquest` quests, version 1

Cooked quests (DESIGN §24, D123): every quest of a `.quest` file with its title and its stages —
a number, a kind and the journal text. `dev.jeddy.asset.format.QuestData` reads and writes it;
`dev.jeddy.script.compile.QuestCompiler` produces it; `dev.jeddy.script.Quests` keeps the stage
each quest is at and the journal.

Little-endian; sections at 16-byte boundaries.

## Header (48 bytes)

| Offset | Type | Field | Notes |
|---|---|---|---|
| 0 | u32 | magic | `0x5453514A` (`"JQST"` in file order) |
| 4 | u32 | version | 1 |
| 8 | u32 | questCount | |
| 12 | u32 | stageCount | |
| 16 | u32 | stringCount | |
| 20 | u32 | reserved | 0 |
| 24 | u64 | questsAt | |
| 32 | u64 | stagesAt | |
| 40 | u64 | stringsAt | |

## Quests (at `questsAt`, 5 ints each)

`id` (string), `title` (string), `stageStart`, `stageCount`, `sourceLine`.

## Stages (at `stagesAt`, 4 ints each)

`number` (1 and up; 0 means "not started"), `kind` (0 open, 1 done, 2 failed), `text` (string:
the journal entry), `sourceLine`.

## Strings (at `stringsAt`)

`stringCount × (u32 offset, u32 length)` then the UTF-8 bytes.

A quest is found at run time by the `StrId` of its id, so a script's `set quest ghost 2` and the
table meet on a `long`; saves store ids as strings and survive a re-cooked file.
