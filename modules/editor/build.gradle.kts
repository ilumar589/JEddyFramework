plugins {
    // Pure Java, but its tests put a level into a headless world through the asset manager,
    // which decompresses with zstd: the native-library convention puts the platform natives on
    // the test runtime path.
    id("jeddy.native-library")
}

dependencies {
    api(project(":core"))
    api(project(":math"))
    api(project(":asset"))
    api(project(":ui"))
    api(project(":physics"))
    api(project(":app"))
    // The Rules panel tests cook what the panel saved, to prove the files it writes are the
    // files the importer reads.
    testImplementation(project(":cooker"))
}
