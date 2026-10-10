package dev.jeddy.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.process.ExecOperations
import javax.inject.Inject

/** `jeddyBindings { libraries { register("sdl3") { ... } } }` (DESIGN §6.4). */
abstract class BindingsExtension @Inject constructor(objects: ObjectFactory) {
    val libraries: NamedDomainObjectContainer<BindingLibrary> =
        objects.domainObjectContainer(BindingLibrary::class.java)
}

/** One C library to bind with jextract. */
interface BindingLibrary : Named {
    /** Umbrella header passed to jextract, for example `SDL3/SDL.h`. */
    val header: RegularFileProperty

    /** `-I` include directories. */
    val includeDirs: ConfigurableFileCollection

    /** Curated include list (`bindings/<lib>.includes`), produced with `--dump-includes` and trimmed. */
    val includesFile: RegularFileProperty

    /** Target package, always a `*.ffi` package. */
    val targetPackage: Property<String>

    /** Name of the generated header class, for example `SDL3`. */
    val headerClassName: Property<String>

    /** Any extra jextract arguments. */
    val extraArgs: ListProperty<String>
}

/**
 * Runs jextract for one library, replaces its package under `src/ffi/java` and makes the output
 * portable across x64 targets with [BindingsPostProcessor]. The output is committed, so normal
 * builds never need jextract or libclang. Always runs when invoked.
 */
@UntrackedTask(because = "Regenerates committed sources on demand; needs jextract and libclang")
abstract class RegenerateBindingsTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @get:Input
    abstract val jextractExecutable: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val header: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val includeDirs: ConfigurableFileCollection

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val includesFile: RegularFileProperty

    @get:Input
    abstract val targetPackage: Property<String>

    @get:Input
    abstract val headerClassName: Property<String>

    @get:Input
    abstract val extraArgs: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun regenerate() {
        val pkg = targetPackage.get()
        require(pkg.endsWith(".ffi")) { "Generated bindings must live in a *.ffi package, got '$pkg'" }

        val outRoot = outputDir.get().asFile
        val packageDir = outRoot.resolve(pkg.replace('.', '/'))
        fileSystemOperations.delete { delete(packageDir) }

        val command = mutableListOf(
            "--output", outRoot.absolutePath,
            "--target-package", pkg,
            "--header-class-name", headerClassName.get(),
        )
        includeDirs.files.forEach { command += listOf("-I", it.absolutePath) }
        command += extraArgs.get()
        if (includesFile.isPresent) {
            command += "@" + includesFile.get().asFile.absolutePath
        }
        command += header.get().asFile.absolutePath

        execOperations.exec {
            executable = jextractExecutable.get()
            args(command)
        }
        BindingsPostProcessor.process(packageDir.toPath())
    }
}
