# Jeddy Framework

A Java 27 framework for building 3D isometric RPGs in the style of *Pathfinder: Wrath of the Righteous*.

- **Performance first:** data-oriented (struct-of-arrays on primitive arrays), a job system, zero allocation per frame in steady state.
- **Native through FFM only:** SDL3, SDL3_mixer, OpenGL 4.6 core, zstd, libktx and meshoptimizer through `java.lang.foreign`. No JNI.
- **Targets:** Windows x64 and Linux x64.
- **Build:** Gradle 9.8.1 multi-module, one JPMS module per subsystem.

## Documents
- [Architecture and design](docs/DESIGN.md)
- [Task plan](docs/TASKS.md)
- [Agent guide](CLAUDE.md)

## Status
Pre-alpha: milestone **M0** (repository, build and natives) is in progress.
