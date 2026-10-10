// Convention for the per-platform natives jars (TASKS M0-08). The jar holds
// natives/<platform>/natives.json plus the libraries, read by dev.jeddy.core's NativeLoader.
//
// The project name is `natives-<platform>`. Inputs (gradle.properties):
//   jeddy.natives.version            release to download (natives-v<N>)
//   jeddy.natives.sha256.<platform>  pinned SHA-256 of natives-<platform>.zip
//   jeddy.natives.dir                optional local directory (output of natives/build.py) to use instead
//
// The jar deliberately has no Automatic-Module-Name, so Gradle puts it on the classpath, where
// the unencapsulated resource directory natives/<platform>/ is visible to every module.

import dev.jeddy.gradle.FetchNativesTask

plugins {
    `java-library`
    id("jeddy.publishing")
}

group = "dev.jeddy"
version = providers.gradleProperty("jeddy.version").get()

val targetPlatform = project.name.removePrefix("natives-")
val nativesVersion = providers.gradleProperty("jeddy.natives.version").get()
val repoUrl = providers.gradleProperty("jeddy.repo.url").get()

extensions.configure<BasePluginExtension> {
    archivesName = "jeddy-${project.name}"
}

val fetchNatives = tasks.register<FetchNativesTask>("fetchNatives") {
    group = "jeddy"
    description = "Downloads (or copies) the $targetPlatform native libraries."
    platform = targetPlatform
    url = "$repoUrl/releases/download/natives-v$nativesVersion/natives-$targetPlatform.zip"
    sha256 = providers.gradleProperty("jeddy.natives.sha256.$targetPlatform")
    providers.gradleProperty("jeddy.natives.dir").orNull?.let { dir ->
        // A local build only covers the platform it was built on.
        if (targetPlatform == dev.jeddy.gradle.JeddyPlatforms.current()) {
            localDir = layout.projectDirectory.dir(dir)
        }
    }
    outputDir = layout.buildDirectory.dir("natives")
}

extensions.getByType<SourceSetContainer>().named("main") {
    resources.srcDir(fetchNatives)
}
