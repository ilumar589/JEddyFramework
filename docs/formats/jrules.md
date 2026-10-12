# `.jrules` rules content, version 1

The cooked rulebook (DESIGN §20, D97): every definition the d20 engine reads — conditions,
ancestries, classes with their spell slots and features, feats, spells, items, monsters with their
attacks, and the demo's characters — as **one table per kind** of fixed-stride `int` rows, plus a
pool of list ints, one flat array of **effect operations** and a string table. The source is JSON
under `assets/rules/` (one file per kind) that the editor's Rules panel edits and the cooker's
`RulesImporter` validates and writes; `dev.jeddy.asset.format.RulesData` reads it (and writes it,
for the cooker). The `rules` module resolves the field indices it needs by name once, from
`RulesSchema`, and then reads ints.

The schema is the single source of truth: `RulesSchema` lists the kinds in file order, and for
each kind its fields with a type, so the importer, the file, the engine and the editor's property
grid all agree without a second description. A field's **width** is 1 int (`INT`, `BOOL`,
`ENUM`, `FLAGS`, `DICE`, `REF`) or 2 ints (`STRING`, `REF_LIST`, `INT_LIST`, `OPS`, `NESTED`:
an offset and a count into the string table, the pool, the ops array or another kind's table);
a kind's **stride** is the sum of its fields' widths.

Little-endian; offsets are from the start of the file; every section starts at a 16-byte
boundary.

## Header (64 bytes)

| Offset | Type | Field | Notes |
|---|---|---|---|
| 0 | u32 | magic | `0x4C55524A` (`"JRUL"` in file order) |
| 4 | u32 | version | 1 |
| 8 | u32 | kindCount | must equal `RulesSchema.KINDS.size()`: a file and a build disagree loudly |
| 12 | u32 | opInts | ints in the effect-operation array |
| 16 | u32 | poolInts | ints in the list pool |
| 20 | u32 | stringCount | entries in the string table |
| 24 | u64 | tablesAt | the kind tables, in schema order |
| 32 | u64 | opsAt | the effect operations |
| 40 | u64 | poolAt | the list pool |
| 48 | u64 | stringsAt | the string table |
| 56 | u64 | fileSize | checked against the file |

## Kind tables (at `tablesAt`)

For each kind in schema order: `u32 rowCount`, then `rowCount × stride` ints, then padding to
16 bytes. A cell is read as `(kind, row, field)` through the schema's field offsets. Values per
type:

| Type | Int(s) | Meaning |
|---|---|---|
| `INT`, `BOOL` | 1 | the number; 0/1 |
| `ENUM` | 1 | index into the field's name list (`Field.names`) |
| `FLAGS` | 1 | one bit per name |
| `DICE` | 1 | `math.Dice` packing: count in bits 24–31, sides in 16–23, signed bonus in 0–15; 0 is "no dice" |
| `REF` | 1 | a row of the field's `refKind`, or −1 |
| `STRING` | 2 | string-table index, 0 (the index alone identifies it; the second int is reserved) |
| `REF_LIST`, `INT_LIST` | 2 | offset and count into the pool: rows of `refKind`, or plain ints |
| `OPS` | 2 | offset and count into the ops array: the definition's effect operations |
| `NESTED` | 2 | offset and count of rows in another kind's table that belong to this row (a class's slot rows, a monster's attack rows) |

The kinds, in order: `conditions`, `ancestries`, `classes`, `classSlots`, `classFeatures`,
`feats`, `spells`, `items`, `monsters`, `monsterAttacks`, `characters`. The fields of each are
listed by `RulesSchema` (and shown by the editor's Rules panel with their documentation).

## Effect operations (at `opsAt`)

Every effect is a run of operations in one flat array, `RulesSchema.OP_INTS = 8` ints each
(D98):

| Index | Field | Notes |
|---|---|---|
| 0 | op | `MODIFY` 0, `DAMAGE` 1, `HEAL` 2, `TEMP_HP` 3, `CONDITION` 4, `REMOVE_CONDITION` 5, `GRANT_FEAT` 6, `EXTRA_ATTACK` 7, `SET_SPEED` 8 |
| 1 | stat | a `Stat` index (`MODIFY`, `SET_SPEED`) |
| 2 | type | a `BonusType` index (`MODIFY`): decides stacking |
| 3 | value | the amount (`MODIFY`, `EXTRA_ATTACK`, `SET_SPEED`) |
| 4 | dice | a packed dice expression (`DAMAGE`, `HEAL`, `TEMP_HP`) |
| 5 | rounds | a duration in rounds; 0 means "while the source is active" |
| 6 | ref | `kind << 24 | row` of the referenced definition (`CONDITION`, `REMOVE_CONDITION`, `GRANT_FEAT`), or −1 |
| 7 | damageType | an index into the damage-type names (`DAMAGE`) |

A feat is "while known, these operations"; a condition is "while it lasts, these"; a spell is
"on cast, against this save, these"; a class feature is "from this level, these". The engine
(`rules.Rules.applyEffects`) interprets the run with the source and the target it is given; the
ops never name a creature.

## Pool and strings

The pool (at `poolAt`) is `poolInts` ints that `REF_LIST` and `INT_LIST` fields slice. The
string table (at `stringsAt`) is `stringCount` pairs of `(u32 offset, u32 length)` followed by
the UTF-8 bytes; offsets are relative to the end of the pair table. String 0 is the empty
string; definitions' `id` strings are unique within a kind, which is what `Content.find(kind,
id)` searches.

## Source JSON

One file per kind under `assets/rules/`, an array of objects whose keys are the schema's field
names; `effects` is an array of operation objects (`{"op": "modify", "stat": "ac", "type":
"dodge", "value": 1}`), references are ids (`"requires": ["dodge"]`), dice are strings
(`"2d4+2"`), enums and flags are their names. `assets/rules/core.rules.json` names the files
that make one rulebook. The importer validates every reference, dice expression and enum name
and fails the cook with the file, the definition and the field; the editor's Rules panel runs
the same validation before saving.
