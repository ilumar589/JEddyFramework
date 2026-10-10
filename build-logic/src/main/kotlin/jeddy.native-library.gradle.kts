// Convention for library modules that call restricted FFM methods (downcalls, upcalls,
// MemorySegment.reinterpret, System.load). Only here is javac's `restricted` lint switched off;
// at runtime, access is still gated by --enable-native-access (DESIGN §6.3).
//
// Pure-Java modules (math, physics, scene) use jeddy.java-library and keep the lint, so any
// accidental native call there fails the build under -Werror (DESIGN §17, D17).

plugins {
    `java-library`
    id("jeddy.java-library")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.replaceAll { arg -> if (arg.startsWith("-Xlint:")) "$arg,-restricted" else arg }
}

dependencies {
    // Tests load the real native libraries of the platform running the build (M0-08).
    "testRuntimeOnly"(project(":natives-" + dev.jeddy.gradle.JeddyPlatforms.current()))
}
