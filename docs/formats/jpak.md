# `.jpak` archive format, version 1

A `.jpak` holds cooked assets for the runtime (DESIGN §10.1). It is designed to be memory-mapped:
the table of contents (TOC) is a structure of arrays that loads into primitive Java arrays with a
few bulk copies, lookups are a binary search on 64-bit `StrId`s, and entry data is aligned so it can
be decompressed or uploaded straight from the mapping.

All integers are little-endian. Offsets are absolute file offsets.

## Layout

```
+---------------------------+ 0
| header (64 bytes)         |
+---------------------------+ 64
| TOC (structure of arrays) |
+---------------------------+ (optional) name table
| names                     |
+---------------------------+ dataOffset, aligned to 4096
| entry data                |
+---------------------------+ fileSize
```

## Header

| Offset | Type | Field | Notes |
|---|---|---|---|
| 0 | u32 | magic | `0x4B41504A` (`"JPAK"` in file order) |
| 4 | u16 | version | 1 |
| 6 | u16 | flags | bit 0: entries have CRC32C; bit 1: name table present |
| 8 | u32 | entryCount | `n` |
| 12 | i32 | dictionaryIndex | index of the entry holding the pak's zstd dictionary, or -1 |
| 16 | u64 | tocOffset | always 64 in version 1 |
| 24 | u64 | namesOffset | 0 when there is no name table |
| 32 | u64 | dataOffset | first data byte, a multiple of 4096 |
| 40 | u64 | fileSize | total size, for truncation checks |
| 48 | u32 | tocCrc32c | CRC32C of the TOC bytes (`tocOffset` to `tocOffset + tocSize`) |
| 52 | 12 bytes | reserved | zero |

## TOC

Columns follow each other in this order; each column starts at a multiple of 8.

| Column | Type | Content |
|---|---|---|
| ids | i64[n] | `StrId` of the asset path, sorted ascending as **signed** 64-bit integers, no duplicates |
| offsets | u64[n] | where each entry's stored bytes start |
| storedSizes | u32[n] | bytes stored in the file |
| rawSizes | u32[n] | bytes after decompression (equal to `storedSizes` for stored entries) |
| crcs | u32[n] | CRC32C of the stored bytes (0 when flag bit 0 is clear) |
| codecs | u8[n] | 0 = stored, 1 = zstd frame, 2 = zstd frame using the pak dictionary |

`tocSize` is the sum of the column sizes including padding: `8n + 8n + 4n + 4n + 4n + n`, each
column rounded up to a multiple of 8.

## Name table (optional, development builds)

For each entry in TOC order: a `u16` byte length and that many bytes of the UTF-8 asset path
(`/`-separated, relative to the cook root). Tools use it to list a pak; the runtime does not need
it.

## Entry data

- Every entry starts at a multiple of 64 (a cache line; enough for GPU upload alignment).
  Entries of 1 MiB or more start at a multiple of 4096, so they can be read page by page.
- A zstd entry is exactly one zstd frame. Frames carry their own content checksum; the TOC CRC
  covers the stored bytes so corruption is caught before decompressing.
- The dictionary entry (if any) is stored, not compressed, and has the id
  `StrId.of("__jpak/dictionary")`.

## Compatibility

Readers reject other magic values and versions. A later version bumps `version` and may use the
reserved header bytes; version-1 readers refuse it rather than guess.
