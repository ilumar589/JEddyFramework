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

// Packaging (TASKS M11): ./gradlew :iso-demo:distImage (runnable image with launchers and an AOT
// cache), :iso-demo:distImageZip, :iso-demo:jpackage (-Pjeddy.package.type=deb|msi|...).
jeddyPackaging {
    name = "iso-demo"
    appContent.from(tasks.named<dev.jeddy.gradle.PackAssets>("packAssets").flatMap { it.pakDir })
    // The training run loads the crypt and plays a few seconds without a window, so it runs on
    // build machines; renderer classes outside it still load normally.
    trainingArguments = listOf("--headless", "--quit-after", "4", "--no-audio")
    trainingEnvironment = mapOf("SDL_VIDEO_DRIVER" to "dummy", "SDL_AUDIO_DRIVER" to "dummy")
}
