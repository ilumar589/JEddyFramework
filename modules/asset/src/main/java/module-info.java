/**
 * Runtime asset pipeline: VFS, .jpak archives, async loading, format readers and reload hooks. See
 * §10.2 of docs/DESIGN.md.
 */
module dev.jeddy.asset {
    requires transitive dev.jeddy.core;
    requires transitive dev.jeddy.compress;

    exports dev.jeddy.asset;
    exports dev.jeddy.asset.format;
    exports dev.jeddy.asset.pak;
}
