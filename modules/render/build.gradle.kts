plugins {
    id("jeddy.native-library")
}

dependencies {
    api(project(":core"))
    api(project(":math"))
    // Public signatures use GLCapabilities, Window and asset readers.
    api(project(":gl"))
    api(project(":window"))
    api(project(":asset"))
}
