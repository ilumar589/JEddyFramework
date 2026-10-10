package dev.jeddy.gradle

import org.gradle.api.Project
import org.gradle.api.plugins.JavaApplication
import org.gradle.api.tasks.bundling.Zip
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

/** Every Jeddy module that calls restricted FFM methods (DESIGN §6.3). Unknown names only warn. */
val JEDDY_NATIVE_ACCESS_MODULES: List<String> = listOf(
    "dev.jeddy.core",
    "dev.jeddy.compress",
    "dev.jeddy.sdl",
    "dev.jeddy.gl",
    "dev.jeddy.window",
    "dev.jeddy.input",
    "dev.jeddy.asset",
    "dev.jeddy.audio",
    "dev.jeddy.render",
    "dev.jeddy.cooker",
)

/** The JVM flags every Jeddy program runs with (DESIGN §4.6 and §6.3). */
val JEDDY_JVM_ARGUMENTS: List<String> = listOf(
    "--enable-native-access=" + JEDDY_NATIVE_ACCESS_MODULES.joinToString(","),
    "-XX:+UseZGC",
    "-XX:+AlwaysPreTouch",
    // No method-handle customization: it compiles a hidden class (tens of KB of garbage) when a
    // downcall handle reaches its 127th call from interpreted or C1 code, which for rarely
    // used natives can be minutes into a game. C2 inlines our constant handles anyway (D69).
    "-Djava.lang.invoke.MethodHandle.CUSTOMIZE_THRESHOLD=-1",
)

/**
 * What `jeddy.application` (programs in this repository) and `dev.jeddy.game` (game projects)
 * share: the launcher flags, this platform's natives on the runtime and test classpaths, and the
 * packaging tasks `jlinkRuntime` -> `distImage` -> `distImageZip` / `jpackage`, configured with
 * `jeddyPackaging { ... }` (DESIGN §14). The project must apply the `application` plugin first.
 *
 * The natives go into their own `jeddyNatives` configuration rather than `runtimeOnly`, so
 * published metadata (the cooker's POM) never names one platform's natives.
 *
 * @param natives dependency notation of the natives jar for the platform running the build
 */
fun Project.configureJeddyApplication(natives: Any) {
    val app = extensions.getByType<JavaApplication>()
    app.applicationDefaultJvmArgs = JEDDY_JVM_ARGUMENTS

    val nativesScope = configurations.dependencyScope("jeddyNatives").get()
    configurations.named("runtimeClasspath") { extendsFrom(nativesScope) }
    configurations.named("testRuntimeClasspath") { extendsFrom(nativesScope) }
    dependencies.add(nativesScope.name, natives)

    val packaging = extensions.create<JeddyPackagingExtension>("jeddyPackaging")
    packaging.name.convention(name)
    packaging.packageType.convention(providers.gradleProperty("jeddy.package.type").orElse("app-image"))
    packaging.vendor.convention("Jeddy")
    packaging.appVersion.convention(provider { version.toString().removeSuffix("-SNAPSHOT") })
    packaging.trainingArguments.convention(emptyList())
    packaging.trainingJvmArguments.convention(emptyList())
    packaging.trainingEnvironment.convention(emptyMap())

    val toolchainLauncher = extensions.getByType<JavaToolchainService>().launcherFor {
        languageVersion.set(JavaLanguageVersion.of(JEDDY_JAVA_RELEASE))
    }
    val platformId = JeddyPlatforms.current()

    val jlinkRuntime = tasks.register<JlinkRuntime>("jlinkRuntime") {
        group = "distribution"
        description = "Links a trimmed Java runtime with the application's modules and natives."
        modulePath.from(tasks.named("jar"), configurations.named("runtimeClasspath"))
        mainModule.set(app.mainModule)
        platform.set(platformId)
        launcher.set(toolchainLauncher)
        outputDir.set(layout.buildDirectory.dir("jlink/runtime"))
    }

    val distImage = tasks.register<DistImage>("distImage") {
        group = "distribution"
        description = "Assembles the runnable image: runtime, launchers, content and AOT cache."
        runtimeDir.set(jlinkRuntime.flatMap { it.outputDir })
        appContent.from(packaging.appContent)
        launcherName.set(packaging.name)
        mainModule.set(app.mainModule)
        mainClass.set(app.mainClass)
        jvmArguments.set(JEDDY_JVM_ARGUMENTS)
        trainingArguments.set(packaging.trainingArguments)
        trainingJvmArguments.set(packaging.trainingJvmArguments)
        trainingEnvironment.set(packaging.trainingEnvironment)
        train.set(providers.gradleProperty("jeddy.aot").map { it != "false" }.orElse(true))
        outputDir.set(packaging.name.flatMap { layout.buildDirectory.dir("dist/$it") })
    }

    tasks.register<Zip>("distImageZip") {
        group = "distribution"
        description = "Zips the runnable image for this platform."
        from(distImage)
        into(packaging.name)
        archiveFileName.set(packaging.name.zip(packaging.appVersion) { n, v -> "$n-$v-$platformId.zip" })
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    }

    tasks.register<JpackageApp>("jpackage") {
        group = "distribution"
        description = "Builds a platform package with jpackage (-Pjeddy.package.type=deb, msi, ...)."
        imageDir.set(distImage.flatMap { it.outputDir })
        packageName.set(packaging.name)
        mainModule.set(app.mainModule)
        mainClass.set(app.mainClass)
        jvmArguments.set(JEDDY_JVM_ARGUMENTS)
        type.set(packaging.packageType)
        appVersion.set(packaging.appVersion)
        vendor.set(packaging.vendor)
        launcher.set(toolchainLauncher)
        outputDir.set(layout.buildDirectory.dir("jpackage"))
    }
}

/** The Java release Jeddy compiles for and runs on. */
const val JEDDY_JAVA_RELEASE = 27
