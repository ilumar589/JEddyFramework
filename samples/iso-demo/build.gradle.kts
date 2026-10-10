plugins {
    id("jeddy.application")
    id("dev.jeddy.assets")
}

dependencies {
    implementation(project(":app"))
}

application {
    mainModule = "dev.jeddy.samples.iso"
    mainClass = "dev.jeddy.samples.iso.IsoDemo"
}

jeddyAssets {
    // Engine shader includes (#include "engine/...") for the cook-time shader checks.
    shaderIncludeDirs.from(rootProject.layout.projectDirectory.dir("modules/render/src/main/resources/dev/jeddy/render/shaders"))
}
