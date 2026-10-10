/**
 * Runtime asset pipeline: VFS, .jpak archives, async loading, format readers and reload hooks. See
 * §10.2 of docs/DESIGN.md.
 */
module dev.jeddy.asset {
    requires transitive dev.jeddy.core;
    requires dev.jeddy.compress;

// Exported together with the first public type (javac rejects exporting a package with no types):
// exports dev.jeddy.asset;
}
