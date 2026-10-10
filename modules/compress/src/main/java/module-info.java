/**
 * zstd through FFM (one-shot, streaming, dictionaries) and checksums. See §6.4, §10 of
 * docs/DESIGN.md.
 */
module dev.jeddy.compress {
    requires transitive dev.jeddy.core;

    exports dev.jeddy.compress;
    exports dev.jeddy.compress.ffi to
            dev.jeddy.asset,
            dev.jeddy.cooker;
}
