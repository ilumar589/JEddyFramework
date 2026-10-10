package dev.jeddy.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Generates the OpenGL bindings from the vendored Khronos registry (DESIGN §9.1, TASKS M3-01).
 * Cacheable and configuration-cache safe: a pure function of the two input files and the
 * generator code in build-logic.
 */
@CacheableTask
abstract class GenerateGlBindings : DefaultTask() {
    /** `bindings/gl/gl.xml`. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val registry: RegularFileProperty

    /** `bindings/gl/gl.properties`. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val settings: RegularFileProperty

    /** Source root the generated package is written into. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        GlGenerator.generate(registry.get().asFile.toPath(), settings.get().asFile.toPath(), out.toPath())
    }
}
