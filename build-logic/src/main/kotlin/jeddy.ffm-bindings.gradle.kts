// Convention for modules with jextract-generated FFM bindings (DESIGN §6.4, TASKS M0-10).
//
// Generated sources live in src/ffi/java and are committed. Regenerate with
//   ./gradlew :<module>:regenerateBindings [-Pjextract.home=<jextract dir>]
// which needs jextract (and libclang) only on the machine that regenerates.

import dev.jeddy.gradle.BindingsExtension
import java.io.File
import dev.jeddy.gradle.RegenerateBindingsTask

plugins {
    `java-library`
    id("jeddy.java-library")
}

val bindings = extensions.create<BindingsExtension>("jeddyBindings")
val ffiSourceDir = layout.projectDirectory.dir("src/ffi/java")

extensions.getByType<SourceSetContainer>().named("main") {
    java.srcDir(ffiSourceDir)
}

// Resolved eagerly to a plain String so no lambda captures the script object (configuration cache).
val launcherName = if (System.getProperty("os.name").lowercase().startsWith("windows")) "jextract.bat" else "jextract"
val jextractLauncher: String = providers.gradleProperty("jextract.home").orNull
    ?.let { home -> File(home, "bin/$launcherName").absolutePath }
    ?: launcherName

val regenerateAll = tasks.register("regenerateBindings") {
    group = "jeddy"
    description = "Regenerates every jextract binding of this module into src/ffi/java."
}

bindings.libraries.all {
    val library = this
    val taskName = "regenerate" + library.name.replaceFirstChar { it.uppercase() } + "Bindings"
    val task = tasks.register<RegenerateBindingsTask>(taskName) {
        group = "jeddy"
        description = "Regenerates the ${library.name} jextract bindings."
        jextractExecutable = jextractLauncher
        header = library.header
        includeDirs.from(library.includeDirs)
        includesFile = library.includesFile
        targetPackage = library.targetPackage
        headerClassName = library.headerClassName
        extraArgs = library.extraArgs
        outputDir = ffiSourceDir
    }
    regenerateAll.configure { dependsOn(task) }
}
