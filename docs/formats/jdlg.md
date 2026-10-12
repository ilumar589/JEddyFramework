# `.jdlg` conversation, version 1

A cooked conversation (DESIGN §24, D123): the graph the script compiler built from a `.dlg` file
— nodes with speaker lines, actions, choices and gotos — with every condition and action as a
program in one bytecode blob, and a string table (ids, speakers, texts). `dev.jeddy.asset.format.DialogueData`
reads and writes it; `dev.jeddy.script.compile.DialogueCompiler` produces it; `dev.jeddy.script.Conversation`
runs it. Everything is `int` columns, so the runtime reads a node without allocating.

Little-endian; offsets are from the start of the file; every section starts at a 16-byte boundary.

## Header (96 bytes)

| Offset | Type | Field | Notes |
|---|---|---|---|
| 0 | u32 | magic | `0x474C444A` (`"JDLG"` in file order) |
| 4 | u32 | version | 1 |
| 8 | u32 | id | string index of the dialogue's id |
| 12 | u32 | title | string index of the title (or −1) |
| 16 | u32 | nodeCount | |
| 20 | u32 | lineCount | |
| 24 | u32 | choiceCount | |
| 28 | u32 | gotoCount | |
| 32 | u32 | codeInts | words of bytecode |
| 36 | u32 | stringCount | |
| 40 | u64 | nodesAt | |
| 48 | u64 | linesAt | |
| 56 | u64 | choicesAt | |
| 64 | u64 | gotosAt | |
| 72 | u64 | codeAt | |
| 80 | u64 | stringsAt | |
| 88 | u64 | fileSize | |

## Nodes (at `nodesAt`, 11 ints each)

`id` (string), `sourceLine`, `lineStart`, `lineCount`, `actions` (program offset or −1),
`actionsLength`, `choiceStart`, `choiceCount`, `gotoStart`, `gotoCount`, `flags` (0). A node's
lines, choices and gotos are contiguous ranges of the tables below. The conversation starts at
the node whose id is `start`.

## Lines (at `linesAt`, 3 ints each)

`speaker` (string; `player` is the party), `text` (string), `sourceLine`.

## Choices (at `choicesAt`, 9 ints each)

`text` (string), `condition` (program offset or −1 for always), `conditionLength`, `checkAbility`
(0–5 or −1 for none), `checkDc`, `target` (node index or −1 for *end*), `else` (the target of a
failed check, or −1), `sourceLine`, `flags` (0).

## Gotos (at `gotosAt`, 4 ints each)

`target` (node index or −1 for *end*), `condition` (program offset or −1), `conditionLength`,
`sourceLine`. A node's gotos are tried in order; the first whose condition holds is taken.

## Code (at `codeAt`, `codeInts` × u32)

Instruction words of `dev.jeddy.script.Bytecode`: the opcode in the low 8 bits, a signed 24-bit
operand above it; `CALL` packs the function (bits 8–15) and the argument count (bits 16–23). A
*program* is `(offset, length)` into this array and ends with `RETURN`. Operands of `PUSH_STR`
are string indices; the interpreter resolves them to `StrId`s through the table.

| Op | Name | Effect |
|---|---|---|
| 1 | PUSH n | push the operand |
| 2 | PUSH_WIDE | push the next word |
| 3 | PUSH_STR i | push string index *i* |
| 4 | CALL f/a | pop *a* arguments, call function *f*, push the result (actions push nothing) |
| 5–6 | NEG, NOT | unary |
| 7–11 | ADD, SUB, MUL, DIV, MOD | binary (division by zero gives 0) |
| 12–17 | EQ, NE, LT, LE, GT, GE | comparisons, 1 or 0 |
| 18 | JFK t | jump to *t* if the top is 0, keeping it (`and`) |
| 19 | JTK t | jump to *t* if the top is not 0, keeping it (`or`) |
| 20 | POP | drop the top |
| 21 | RETURN | end; the top is the result |

Functions 0–7 read (`stat`, `level`, `quest`, `flag`, `item`, `gold`, `random`, `check`), 8–13
act (`setQuest`, `setFlag`, `give`, `take`, `addGold`, `do`).

## Strings (at `stringsAt`)

`stringCount × (u32 offset, u32 length)` then the UTF-8 bytes, as in `.jrules`.

`DialogueData.validate` checks every index and range when the file is read, so a file that
loads cannot make the interpreter read out of bounds.
