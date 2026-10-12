plugins {
    id("jeddy.java-library") // published as jeddy-component-processor for dev.jeddy.game
}

// JDK only: the processor matches the dev.jeddy.components annotations by name, so it has no
// dependency on the runtime module and no version to keep in step (DESIGN §22).
dependencies {
    // The tests compile small games through the Compiler API against the runtime's annotations.
    testImplementation(project(":components"))
}
