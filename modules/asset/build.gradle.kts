plugins {
    id("jeddy.native-library")
}

dependencies {
    api(project(":core"))
    implementation(project(":compress"))
}
