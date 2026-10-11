plugins {
    id("jeddy.native-library")
}

dependencies {
    api(project(":core"))
    api(project(":math"))
    api(project(":window"))
    api(project(":input"))
    api(project(":render"))
    api(project(":audio"))
    api(project(":physics"))
    api(project(":scene"))
    api(project(":asset"))
    api(project(":animation"))
    api(project(":ui"))
    implementation(project(":sdl"))
}
