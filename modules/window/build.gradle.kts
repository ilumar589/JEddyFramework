plugins {
    id("jeddy.java-library")
}

dependencies {
    api(project(":core"))
    implementation(project(":sdl"))
}
