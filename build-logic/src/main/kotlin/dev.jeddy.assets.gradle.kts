// Asset cooking for game projects (DESIGN §10.3, TASKS M6-09):
//
//   plugins { id("dev.jeddy.assets") }
//   jeddyAssets { sourceDir = layout.projectDirectory.dir("assets") }  // the default
//
// adds `cookAssets` (source tree -> loose cooked tree, incremental through the cooker's cache)
// and `packAssets` (cooked tree -> .jpak; a `paks.json` next to the build script sets the
// layout, see dev.jeddy.cooker.pak.PakBuilder). Inside this repository the cooker comes from the
// `:cooker` project, elsewhere from dev.jeddy:jeddy-cooker at the plugin's version. Every task
// input is a provider or a file collection, so the configuration cache can be reused. Game
// projects normally apply it through dev.jeddy.game.

import dev.jeddy.gradle.CookAssets
import dev.jeddy.gradle.GenerateLevelBindings
import dev.jeddy.gradle.JEDDY_VERSION
import dev.jeddy.gradle.JeddyAssetsExtension
import dev.jeddy.gradle.JeddyPlatforms
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
extension.bindingsDir.convention(layout.buildDirectory.dir("generated/sources/jeddy/java/main"))
extension.componentsSchema.convention(layout.buildDirectory.file("classes/java/main/META-INF/jeddy/components.json"))

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
// The cooker and this platform's natives: this repository's projects when the plugin runs inside
// it, otherwise the published artifacts of the plugin's own Jeddy version (which a composite build
// substitutes with the framework's projects).
val cookerPlatform = JeddyPlatforms.current()
dependencies {
    if (rootProject.findProject(":cooker") != null) {
        add("jeddyCooker", project(":cooker"))
        add("jeddyCooker", project(":natives-$cookerPlatform"))
    } else {
        add("jeddyCooker", "dev.jeddy:jeddy-cooker:$JEDDY_VERSION")
        add("jeddyCooker", "dev.jeddy:jeddy-natives-$cookerPlatform:$JEDDY_VERSION")
    }
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
    componentsSchema = extension.componentsSchema
    cookerClasspath.from(cookerRuntime)
    launcher = cookerLauncher
}

// Level bindings (DESIGN §22, D110): only when jeddyAssets.bindingsPackage is set; dev.jeddy.game
// (and the demo) add the output directory to the main source set.
// A local, so the onlyIf lambda captures a Property and no script object (configuration cache).
val bindingsPackage = extension.bindingsPackage
tasks.register<GenerateLevelBindings>("generateLevelBindings") {
    group = "jeddy"
    description = "Writes <Name>Level.java for every .jlevel, with the named objects' ids."
    sourceDir = extension.sourceDir
    bindingsPackage = extension.bindingsPackage
    outputDir = extension.bindingsDir
    threads = extension.threads
    cookerClasspath.from(cookerRuntime)
    launcher = cookerLauncher
    onlyIf("jeddyAssets.bindingsPackage is set") { bindingsPackage.isPresent }
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
