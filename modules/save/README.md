# jeddy-save

Module `dev.jeddy.save`. Save games as the **dynamic state only** (DESIGN §21): the level, the models and the rulebook come back from the paks, and the save holds what play changed. This module knows no game system; it owns the `.jsave` container ([docs/formats/jsave.md](../../docs/formats/jsave.md)), the capture buffers, the background job that compresses and writes, and the slots in the user's data directory. Every system with state writes itself into `core.io.ByteWriter` and reads it back from `ByteReader` (`Crowd.write`, `Rules.write`, the generated registry's `write(w, ids)`, …); the game composes them into chunks. Depends on `core` and `compress` only.

A save is two halves. The **capture** runs on the game thread between ticks: `SaveWriter.reset()`, a `meta` entry per thing the slot list shows, then `chunk(tag, version)` per system and that system's `write` into the returned `ByteWriter`; the buffers are kept, so a capture that fits the previous one allocates nothing. The **job** (`SaveJob.submit`, or `SaveSlots.save`) copies the bytes and wakes a worker thread parked between saves, which encodes the file (zstd, two CRC-32Cs) and moves a temporary file over the slot atomically; the game polls `isDone()` and `error()`. A load is synchronous: `SaveSlots.load(slot)` returns a `SaveFile` whose chunks the game applies in dependency order.

## Key types

- `SaveWriter`: the capture: `meta(key, value)` (a `CharSequence`, so a kept `StringBuilder` formats without allocating), `chunk(tag, version)` → `ByteWriter`, `finish`; chunks are closed by patching their length and padding to 8
- `SaveFile`: the container: `encode(writer, level)` builds the file image; `read(path | bytes)` checks the magic, both checksums, every size and the format version before handing out `Chunk`s (`tag`, `version`, `data` as a bounded `ByteReader`); `peek` reads the header and the meta block only; `tag("COMP")` and `tagName`
- `SaveFormatException`: what a damaged, truncated, foreign or newer file fails with, naming the problem
- `SaveJob`: the worker: `submit(writer, path, captureNanos)` (waits for a write in progress, copies into kept buffers, unparks), `isDone`, `await`, `error`, `bytesWritten`, `writeNanos`, `close`; `write(bytes, path)` is the atomic replace by itself
- `SaveSlots`: `QUICK` (`quick.jsave`), `AUTO` (`auto.jsave`) and named slots (`slot-<name>.jsave`, the name reduced to `[A-Za-z0-9_-]`); `list()` reads each file's meta (newest first), `save`, `load`, `delete`, `exists`
- `SaveDirectory.of(game)`: `%APPDATA%\<game>\saves`, `$XDG_DATA_HOME/<game>/saves` or `~/.local/share/<game>/saves`; `-Djeddy.saves.dir` overrides

Entities cross a save as the game's stable ids (`components.EntityIds`, D114), not as handles; the rules save creatures by content id and rebuild them from the current rulebook at the same handles (D115). [docs/learn/16-saves.md](../../docs/learn/16-saves.md) walks through the whole save of the demo; [lesson L04](../../docs/learn/lessons/04-binary-formats.md) reads the bytes of a real file.

## Threading

`SaveWriter`, `SaveSlots` and the job's `submit`/`await`/`isDone` belong to one thread (the game thread); the job's encoding and file write run on its own daemon thread, which parks between saves. The hand-over is a copy, a volatile write and an unpark: 0 B once the buffers are sized (`SaveSlotsTest.submitAllocatesNothingOnceTheBuffersFit`). A platform thread rather than a virtual one, because unparking a virtual thread allocates a scheduler task (24 B per save, measured).

## More

- Design: [DESIGN.md](../../docs/DESIGN.md) §21, D112–D119
- Format: [docs/formats/jsave.md](../../docs/formats/jsave.md); a golden file of every format version under `src/test/resources`
- API: `./gradlew :save:javadoc`
- Tests: `./gradlew :save:test`
