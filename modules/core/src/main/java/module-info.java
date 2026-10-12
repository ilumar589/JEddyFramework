/**
 * Primitive collections, handles, pools, off-heap memory, job system, native loader, profiler,
 * logging, IDs and settings. See §7 of docs/DESIGN.md.
 */
module dev.jeddy.core {
    requires jdk.jfr;

    exports dev.jeddy.core;
    exports dev.jeddy.core.collections;
    exports dev.jeddy.core.concurrent;
    exports dev.jeddy.core.config;
    exports dev.jeddy.core.handle;
    exports dev.jeddy.core.id;
    exports dev.jeddy.core.io;
    exports dev.jeddy.core.job;
    exports dev.jeddy.core.json;
    exports dev.jeddy.core.log;
    exports dev.jeddy.core.memory;
    exports dev.jeddy.core.natives;
    exports dev.jeddy.core.pool;
    exports dev.jeddy.core.profile;
    exports dev.jeddy.core.time;
}
