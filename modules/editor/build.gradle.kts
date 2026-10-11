plugins {
    id("jeddy.java-library")
}

dependencies {
    api(project(":core"))
    api(project(":math"))
    api(project(":asset"))
    api(project(":ui"))
    api(project(":physics"))
    api(project(":app"))
    // The tests put a level into a headless world through the asset manager, which needs zstd.
    testRuntimeOnly(project(":natives-" + dev.jeddy.gradle.JeddyPlatforms.current()))
}
