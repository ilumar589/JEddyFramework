/**
 * Save games: the {@code .jsave} container (tagged, versioned chunks; a meta block; zstd and a
 * CRC), the background job and the slots. Knows no game system: each writes itself into {@code
 * core.io.ByteWriter}. See §21 of docs/DESIGN.md.
 */
module dev.jeddy.save {
    requires transitive dev.jeddy.core;
    requires dev.jeddy.compress;

    exports dev.jeddy.save;
}
