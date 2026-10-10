// Shared Java conventions for every Jeddy project (DESIGN §14). Applied through
// jeddy.java-library, jeddy.application or jeddy.jmh, never directly by module scripts.

import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
    id("com.diffplug.spotless")
}

group = "dev.jeddy"
version = providers.gradleProperty("jeddy.version").get()

val javaRelease = 27
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

extensions.configure<JavaPluginExtension> {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaRelease)
    }
}

extensions.configure<BasePluginExtension> {
    archivesName = "jeddy-${project.name}"
}

tasks.withType<JavaCompile>().configureEach {
    options.release = javaRelease
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(
        listOf(
            // "module" is off: qualified exports name downstream modules that are not on the
            // module path yet when the exporting module compiles (DESIGN §17, D15).
            "-Xlint:all,-module,-processing",
            "-Werror",
            "-parameters",
        ),
    )
}

// Archives are reproducible by default since Gradle 9.0, so no archive configuration is needed.

// Formatting (M0-05): google-java-format, AOSP style (4-space indent, 100 columns), enforced by
// `check`. Fix locally with `./gradlew spotlessApply`. Generated bindings are excluded.
extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
    java {
        target("src/*/java/**/*.java")
        targetExclude("src/ffi/**")
        googleJavaFormat(catalog.findVersion("google-java-format").get().requiredVersion)
            .aosp()
            .reflowLongStrings(true)
            .reorderImports(true)
    }
}

dependencies {
    "testImplementation"(platform(catalog.findLibrary("junit-bom").get()))
    "testImplementation"(catalog.findLibrary("junit-jupiter").get())
    "testRuntimeOnly"(catalog.findLibrary("junit-platform-launcher").get())
    if (project.path != ":testing") {
        // Shared test helpers, e.g. Allocations.assertNoAllocation (DESIGN §4.7).
        "testImplementation"(project(":testing"))
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        // GPU tests need a real GL 4.6 driver; run them with -Pjeddy.gpuTests=true.
        if (!providers.gradleProperty("jeddy.gpuTests").map { it.toBoolean() }.getOrElse(false)) {
            excludeTags("gpu")
        }
    }
    // Whitebox tests (no module-info in src/test) run on the classpath, so native access is
    // granted to the unnamed module (DESIGN §6.3).
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    maxHeapSize = "1g"
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
