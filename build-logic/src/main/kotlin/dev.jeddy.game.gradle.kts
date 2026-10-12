// Plugin for game projects built on Jeddy (docs/getting-started.md, TASKS M11-04):
//
//   plugins { id("dev.jeddy.game") }
//   dependencies { implementation("dev.jeddy:jeddy-app:<version>") }
//   application { mainModule = "com.example.mygame"; mainClass = "com.example.mygame.MyGame" }
//
// It applies `application` and dev.jeddy.assets (cookAssets, packAssets), compiles for Java 27,
// puts this platform's natives at the plugin's Jeddy version on the runtime classpath, and runs
// with the Jeddy launcher flags. `run` packs the assets first and sets jeddy.app.dir to build/,
// so Vfs.contentDirectory() finds build/paks as it finds <image>/paks in a packaged game
// (-Pjeddy.assets.dir=<dir> runs on another content directory, such as build/cooked). The
// packaging tasks are those of the framework's own programs: distImage, distImageZip and jpackage,
// configured with jeddyPackaging { ... } (DESIGN §14); the paks go into the image.

import dev.jeddy.gradle.JEDDY_JAVA_RELEASE
import dev.jeddy.gradle.JEDDY_VERSION
import dev.jeddy.gradle.JeddyPackagingExtension
import dev.jeddy.gradle.JeddyPlatforms
import dev.jeddy.gradle.PackAssets
import dev.jeddy.gradle.configureJeddyApplication

plugins {
    application
    id("dev.jeddy.assets")
}

extensions.configure<JavaPluginExtension> {
    toolchain {
        languageVersion = JavaLanguageVersion.of(JEDDY_JAVA_RELEASE)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = JEDDY_JAVA_RELEASE
    options.encoding = "UTF-8"
}

configureJeddyApplication("dev.jeddy:jeddy-natives-${JeddyPlatforms.current()}:$JEDDY_VERSION")

// The component processor (DESIGN §22): @Component records and @Behaviour classes in the game
// become generated stores, views, loops and the registry. It does nothing in a game without them.
dependencies {
    "annotationProcessor"("dev.jeddy:jeddy-component-processor:$JEDDY_VERSION")
}

// Level bindings (D110): with jeddyAssets { bindingsPackage = "..." } the generated
// <Name>Level.java classes compile with the game.
val generateLevelBindings = tasks.named<dev.jeddy.gradle.GenerateLevelBindings>("generateLevelBindings")
extensions.configure<SourceSetContainer> {
    named("main") {
        java.srcDir(generateLevelBindings.flatMap { it.outputDir })
    }
}

val packAssets = tasks.named<PackAssets>("packAssets")

extensions.configure<JeddyPackagingExtension> {
    appContent.from(packAssets.flatMap { it.pakDir })
}

// -Pjeddy.assets.dir=build/cooked runs on the loose cooked tree instead (hot reload while
// `cookAssets -Pjeddy.cook.args=--watch` runs in another terminal), without packing first.
val assetsDir = providers.gradleProperty("jeddy.assets.dir")
tasks.named<JavaExec>("run") {
    if (assetsDir.isPresent) {
        systemProperty("jeddy.assets.dir", file(assetsDir.get()).absolutePath)
    } else {
        dependsOn(packAssets)
    }
    systemProperty("jeddy.app.dir", layout.buildDirectory.get().asFile.absolutePath)
}
