package dev.jeddy.gradle

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.process.CommandLineArgumentProvider

/**
 * Command line for `org.openjdk.jmh.Main`: include regex, extra options, JSON result file.
 * A class instead of a lambda so the configuration cache never captures the script object.
 */
abstract class JmhArguments : CommandLineArgumentProvider {
    @get:Input
    abstract val includes: Property<String>

    @get:Input
    abstract val extraArgs: Property<String>

    @get:OutputFile
    abstract val resultsFile: RegularFileProperty

    override fun asArguments(): Iterable<String> {
        val out = resultsFile.get().asFile
        out.parentFile.mkdirs()
        return listOf(includes.get()) +
            extraArgs.get().split(' ').filter { it.isNotBlank() } +
            listOf("-rf", "json", "-rff", out.absolutePath)
    }
}
