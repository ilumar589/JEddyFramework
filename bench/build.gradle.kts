plugins {
    id("jeddy.jmh")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":math"))
    implementation(project(":gl"))
    implementation(project(":window"))
}
