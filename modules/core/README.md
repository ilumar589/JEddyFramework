# jeddy-core

Module `dev.jeddy.core`. Foundations every other module uses: primitive collections, handles and pools, off-heap memory, the job system, the native library loader, profiling, logging, IDs, JSON and settings.

## Key types

- `JobSystem`, `JobGraph`, `PerWorker`: data-parallel jobs over index ranges (atomic chunk claiming, the caller helps), dependent jobs, per-worker scratch
- `IntList`, `IntIntMap`, `SparseSet`, `IntHeap`, `BitSet64`, ...: collections without boxing
- `HandlePool`, `SlotMap`, `Handles`: generation-checked `int` handles
- `SpscIntRing`, `MpmcIntQueue`, `TripleBuffer`: lock-free hand-offs between threads
- `NativeLoader`, `Platform`: verified loading of the bundled natives (§6.3)
- `FrameStats`, `Profiler`, `Log`, `Settings`, `Json`, `StrId`, `FixedStepper`

## Threading

Each type states its own thread ownership; the job system is shared by the main and game threads.

## More

- Design: [DESIGN.md](../../docs/DESIGN.md) §4, §7
- API: `./gradlew :core:javadoc`, or the Javadoc site (`./gradlew :site:javadocSite`)
- Tests: `./gradlew :core:test`
