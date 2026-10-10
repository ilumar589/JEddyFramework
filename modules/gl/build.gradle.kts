plugins {
    id("jeddy.gl-bindings")
}

dependencies {
    api(project(":core"))
    testImplementation(project(":window"))
}
