// JMH wired by hand, without a third-party Gradle plugin (DESIGN §17, D14).
//
//   ./gradlew :bench:jmh -Pjmh.includes=<regex> [-Pjmh.args="-f 1 -wi 3 -i 5"]
//
// Results are written to build/reports/jmh/results.json.

import dev.jeddy.gradle.JmhArguments

plugins {
    java
    id("jeddy.java-base")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "implementation"(catalog.findLibrary("jmh-core").get())
    "annotationProcessor"(catalog.findLibrary("jmh-annprocess").get())
}

// JMH-generated sources are not ours to keep warning-free.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.removeAll { it == "-Werror" }
}

tasks.register<JavaExec>("jmh") {
    group = "benchmark"
    description = "Runs JMH benchmarks. Filter with -Pjmh.includes=<regex>, pass extra options with -Pjmh.args."
    classpath = project.extensions.getByType<SourceSetContainer>().getByName("main").runtimeClasspath
    mainClass = "org.openjdk.jmh.Main"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    outputs.upToDateWhen { false }

    val jmhArguments = project.objects.newInstance<JmhArguments>()
    jmhArguments.includes = project.providers.gradleProperty("jmh.includes").orElse(".*")
    jmhArguments.extraArgs = project.providers.gradleProperty("jmh.args").orElse("")
    jmhArguments.resultsFile = project.layout.buildDirectory.file("reports/jmh/results.json")
    argumentProviders.add(jmhArguments)
}
