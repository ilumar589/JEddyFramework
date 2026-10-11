plugins {
    id("jeddy.java-library")
}

dependencies {
    api(project(":core"))
    api(project(":math"))
    api(project(":asset"))
    // The tests build the demo's content from its JSON sources with the cooker's importer, so
    // the hand-computed numbers in them follow the files the game ships (DESIGN §20, D97).
    testImplementation(project(":cooker"))
}
