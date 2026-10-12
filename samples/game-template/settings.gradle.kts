// A game project built on Jeddy (docs/getting-started.md). Copy this directory to start a game.
//
// This copy builds the framework from source: a composite build of the checkout two levels up.
// In your own repository, point both includeBuild paths at your Jeddy checkout, or drop them and
// use published artifacts (see the guide).

pluginManagement {
    // dev.jeddy.game and dev.jeddy.assets
    includeBuild("../../build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Provisions JDK 27 when Gradle runs on another JDK.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "my-game"

// The framework's projects stand in for the dev.jeddy:jeddy-<name> artifacts. Gradle matches an
// included build's projects by project name, so the jeddy- prefix needs these rules.
includeBuild("../..") {
    dependencySubstitution {
        for (name in listOf(
            "core", "math", "compress", "sdl", "gl", "window", "input", "render", "asset", "audio",
            "physics", "animation", "ui", "scene", "app", "editor", "rules", "components", "save",
            "component-processor", "testing", "cooker", "natives-linux-x64", "natives-windows-x64",
        )) {
            substitute(module("dev.jeddy:jeddy-$name")).using(project(":$name"))
        }
    }
}
