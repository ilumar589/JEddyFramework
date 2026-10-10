plugins {
    id("jeddy.java-library")
}

dependencies {
    api(project(":core"))
    api(project(":math"))
    implementation(project(":sdl"))
    implementation(project(":asset"))
}
