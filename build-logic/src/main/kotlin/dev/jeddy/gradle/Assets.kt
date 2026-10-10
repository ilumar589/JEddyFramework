package dev.jeddy.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

/**
 * `jeddyAssets { ... }`: where a game's source assets are and where cooked output and paks go
 * (DESIGN §10.3, TASKS M6-09).
 */
abstract class JeddyAssetsExtension {
    /** Source asset tree (default `assets/`). */
    abstract val sourceDir: DirectoryProperty

    /** Loose cooked tree, mounted by the VFS in development (default `build/cooked`). */
    abstract val cookedDir: DirectoryProperty

    /** The cooker's content-addressed cache (default `build/cook-cache`). */
    abstract val cacheDir: DirectoryProperty

    /** Pak output directory (default `build/paks`). */
    abstract val pakDir: DirectoryProperty

    /** Pak layout (default `paks.json` next to the build script, if it exists). */
    abstract val pakLayout: RegularFileProperty

    /** zstd level for paks (default 19). */
    abstract val pakLevel: Property<Int>

    /** Cooker threads (default: all cores). */
    abstract val threads: Property<Int>

    /** Extra `#include` roots for shader checks (engine shaders). */
    abstract val shaderIncludeDirs: ConfigurableFileCollection

    /** Compile shaders with `glslangValidator` when it is on the PATH (default true). */
    abstract val validateShaders: Property<Boolean>
}

/** Common launcher settings of the cooker tasks. */
@DisableCachingByDefault(because = "Abstract base")
abstract class CookerTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    /** The cooker and its runtime dependencies (module path). */
    @get:Classpath
    abstract val cookerClasspath: ConfigurableFileCollection

    @get:Nested
    abstract val launcher: Property<JavaLauncher>

    @get:Input
    abstract val threads: Property<Int>

    protected fun runCooker(arguments: List<String>) {
        val modulePath = cookerClasspath
        val javaExecutable = launcher.get().executablePath.asFile.absolutePath
        execOperations.javaexec {
            executable = javaExecutable
            classpath(modulePath)
            mainModule.set("dev.jeddy.cooker")
            mainClass.set("dev.jeddy.cooker.CookerMain")
            modularity.inferModulePath.set(true)
            jvmArgs(
                "--enable-native-access=dev.jeddy.core,dev.jeddy.compress,dev.jeddy.asset,dev.jeddy.cooker",
                "-XX:+UseZGC",
            )
            args(arguments)
        }
    }
}

/**
 * Cooks the source tree into the loose cooked tree with `jeddy-cook`. Gradle only decides whether
 * anything changed at all; the cooker's own cache makes the cook itself incremental per source.
 */
@DisableCachingByDefault(because = "The cooker keeps its own content-addressed cache")
abstract class CookAssets : CookerTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:IgnoreEmptyDirectories
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val cookedDir: DirectoryProperty

    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val shaderIncludeDirs: ConfigurableFileCollection

    @get:Input
    abstract val validateShaders: Property<Boolean>

    @get:Input
    abstract val extraArguments: ListProperty<String>

    @TaskAction
    fun cook() {
        val args = mutableListOf(
            "cook",
            "--source", sourceDir.get().asFile.absolutePath,
            "--out", cookedDir.get().asFile.absolutePath,
            "--cache", cacheDir.get().asFile.absolutePath,
            "--threads", threads.get().toString(),
        )
        shaderIncludeDirs.files.forEach { args += listOf("--shader-include", it.absolutePath) }
        if (!validateShaders.get()) {
            args += "--no-validate"
        }
        args += extraArguments.get()
        runCooker(args)
    }
}

/** Packs the cooked tree into `.jpak` archives (zstd 19, trained dictionaries). */
@DisableCachingByDefault(because = "Large outputs that are cheap to rebuild from the cooked tree")
abstract class PackAssets : CookerTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val cookedDir: DirectoryProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val layoutFile: RegularFileProperty

    @get:Input
    abstract val level: Property<Int>

    @get:OutputDirectory
    abstract val pakDir: DirectoryProperty

    @TaskAction
    fun pack() {
        val args = mutableListOf(
            "pak",
            "--cooked", cookedDir.get().asFile.absolutePath,
            "--out", pakDir.get().asFile.absolutePath,
            "--level", level.get().toString(),
            "--threads", threads.get().toString(),
        )
        if (layoutFile.isPresent) {
            args += listOf("--layout", layoutFile.get().asFile.absolutePath)
        }
        runCooker(args)
    }
}
