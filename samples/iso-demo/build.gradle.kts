plugins {
    id("jeddy.application")
}

dependencies {
    implementation(project(":app"))
}

application {
    mainModule = "dev.jeddy.samples.iso"
    mainClass = "dev.jeddy.samples.iso.IsoDemo"
}
