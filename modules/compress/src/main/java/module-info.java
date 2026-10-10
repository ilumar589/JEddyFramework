/**
 * zstd through FFM (one-shot, streaming, dictionaries) and checksums. See §6.4, §10 of
 * docs/DESIGN.md.
 */
module dev.jeddy.compress {
    requires transitive dev.jeddy.core;

// Exported together with the first public type (javac rejects exporting a package with no types):
// exports dev.jeddy.compress;
}
