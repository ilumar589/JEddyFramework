pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Auto-provisions the JDK 27 toolchain when Gradle itself runs on an older JDK.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "jeddy-framework"

// Short project paths (":core", ":physics", ...) mapped onto the nested folder layout (DESIGN §14).
fun module(path: String, dir: String) {
    include(":$path")
    project(":$path").projectDir = file(dir)
}

listOf(
    "core", "math", "compress", "sdl", "gl", "window", "input",
    "render", "asset", "audio", "physics", "animation", "ui", "scene", "app", "editor",
).forEach { module(it, "modules/$it") }

module("cooker", "tools/cooker")
module("site", "tools/site")
module("bench", "bench")
module("testing", "testing")
module("natives-linux-x64", "natives/linux-x64")
module("natives-windows-x64", "natives/windows-x64")
module("iso-demo", "samples/iso-demo")
module("hello-triangle", "samples/hello-triangle")
