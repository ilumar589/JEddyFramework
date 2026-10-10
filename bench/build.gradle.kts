plugins {
    id("jeddy.jmh")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":math"))
    implementation(project(":gl"))
    implementation(project(":window"))
    implementation(project(":compress"))
    implementation(project(":asset"))
    implementation(project(":scene"))
    implementation(project(":render"))
    implementation(project(":cooker"))
    implementation(project(":physics"))
    implementation(project(":audio"))
    implementation(project(":animation"))
}
