plugins {
    id("jeddy.native-library")
}

dependencies {
    api(project(":core"))
    api(project(":math"))
    implementation(project(":sdl"))
    implementation(project(":window"))
}
