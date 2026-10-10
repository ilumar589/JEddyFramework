plugins {
    id("jeddy.application")
}

dependencies {
    implementation(project(":app"))
    implementation(project(":gl"))
}

application {
    mainModule = "dev.jeddy.samples.triangle"
    mainClass = "dev.jeddy.samples.triangle.HelloTriangle"
}
