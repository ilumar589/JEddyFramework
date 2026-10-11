plugins {
    id("jeddy.java-library")
}

dependencies {
    api(project(":core"))
    api(project(":math"))
    api(project(":asset"))
    api(project(":ui"))
    api(project(":physics"))
    api(project(":app"))
}
