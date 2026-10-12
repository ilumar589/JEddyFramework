plugins {
    id("jeddy.java-library")
}

dependencies {
    api(project(":core"))
    implementation(project(":compress"))
    // The tests compress real payloads: they need the platform's zstd (as the compress tests do).
    testRuntimeOnly(project(":natives-" + dev.jeddy.gradle.JeddyPlatforms.current()))
}
