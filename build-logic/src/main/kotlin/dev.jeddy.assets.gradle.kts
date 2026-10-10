// Asset cooking for game projects (DESIGN §10.3, TASKS M6-09):
//
//   plugins { id("dev.jeddy.assets") }
//   jeddyAssets { sourceDir = layout.projectDirectory.dir("assets") }  // the default
//
// adds `cookAssets` (source tree -> loose cooked tree, incremental through the cooker's cache)
// and `packAssets` (cooked tree -> .jpak; a `paks.json` next to the build script sets the
// layout, see dev.jeddy.cooker.pak.PakBuilder). Inside this repository the cooker comes from the
// `:cooker` project; elsewhere declare it: dependencies { jeddyCooker("dev.jeddy:jeddy-cooker:<v>") }.
// Every task input is a provider or a file collection, so the configuration cache can be reused.

import dev.jeddy.gradle.CookAssets
import dev.jeddy.gradle.JeddyAssetsExtension
import dev.jeddy.gradle.PackAssets

plugins {
    `java-base`
}

val extension = extensions.create<JeddyAssetsExtension>("jeddyAssets")
extension.sourceDir.convention(layout.projectDirectory.dir("assets"))
extension.cookedDir.convention(layout.buildDirectory.dir("cooked"))
extension.cacheDir.convention(layout.buildDirectory.dir("cook-cache"))
extension.pakDir.convention(layout.buildDirectory.dir("paks"))
extension.pakLayout.convention(layout.projectDirectory.file("paks.json").let { f -> providers.provider { f }.filter { it.asFile.isFile } })
extension.pakLevel.convention(19)
extension.threads.convention(Runtime.getRuntime().availableProcessors())
extension.validateShaders.convention(true)

val cookerDependencies = configurations.dependencyScope("jeddyCooker")
val cookerRuntime = configurations.resolvable("jeddyCookerClasspath") {
    extendsFrom(cookerDependencies.get())
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}
if (rootProject.findProject(":cooker") != null) {
    dependencies { add("jeddyCooker", project(":cooker")) }
}

val cookerLauncher = extensions.getByType<JavaToolchainService>().launcherFor {
    languageVersion = JavaLanguageVersion.of(27)
}

val cookAssets = tasks.register<CookAssets>("cookAssets") {
    group = "jeddy"
    description = "Cooks the source assets into the loose cooked tree (incremental)."
    sourceDir = extension.sourceDir
    cookedDir = extension.cookedDir
    cacheDir = extension.cacheDir
    shaderIncludeDirs.from(extension.shaderIncludeDirs)
    validateShaders = extension.validateShaders
    threads = extension.threads
    extraArguments = providers.gradleProperty("jeddy.cook.args").map { it.split(' ').filter(String::isNotBlank) }.orElse(emptyList())
    cookerClasspath.from(cookerRuntime)
    launcher = cookerLauncher
}

tasks.register<PackAssets>("packAssets") {
    group = "jeddy"
    description = "Packs the cooked tree into .jpak archives."
    cookedDir = cookAssets.flatMap { it.cookedDir }
    layoutFile = extension.pakLayout
    level = extension.pakLevel
    pakDir = extension.pakDir
    threads = extension.threads
    cookerClasspath.from(cookerRuntime)
    launcher = cookerLauncher
}
