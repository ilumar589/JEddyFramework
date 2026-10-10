# jeddy-compress

Module `dev.jeddy.compress`. zstd through FFM: one-shot and streaming compression, trained dictionaries, and checksums. Used by paks and the cooker.

## Key types

- `Zstd`, `ZstdCompressor`, `ZstdDecompressor`, `ZstdDictionary`
- `Checksums`: CRC32C over heap and off-heap data

## Threading

Compressor and decompressor contexts belong to one thread at a time (keep one per worker).

## More

- Design: [DESIGN.md](../../docs/DESIGN.md) §6.4, §10
- API: `./gradlew :compress:javadoc`, or the Javadoc site (`./gradlew :site:javadocSite`)
- Tests: `./gradlew :compress:test`
