plugins {
    id("jeddy.application")
    id("dev.jeddy.assets")
}

dependencies {
    implementation(project(":app"))
    implementation(project(":gl"))
}

application {
    mainModule = "dev.jeddy.samples.iso"
    mainClass = "dev.jeddy.samples.iso.IsoDemo"
}

jeddyAssets {
    // Engine shader includes (#include "engine/...") for the cook-time shader checks.
    shaderIncludeDirs.from(rootProject.layout.projectDirectory.dir("modules/render/src/main/resources/dev/jeddy/render/shaders"))
}

// The demo mounts build/paks (relative to the project directory, the run task's working directory).
// Extra JVM flags for perf runs: -Pjeddy.jvmArgs="-Xlog:gc*:file=build/perf/gc.log" (a configuration
// cache input, so changing it reconfigures).
tasks.named<JavaExec>("run") {
    dependsOn("packAssets")
    providers.gradleProperty("jeddy.jvmArgs").orNull?.let { args -> jvmArgs(args.split(' ').filter(String::isNotBlank)) }
}
