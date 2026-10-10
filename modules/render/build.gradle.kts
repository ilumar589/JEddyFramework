plugins {
    id("jeddy.java-library")
}

dependencies {
    api(project(":core"))
    api(project(":math"))
    implementation(project(":gl"))
    implementation(project(":window"))
    implementation(project(":asset"))
}
