package dev.jeddy.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject

/** Jeddy's platform identifiers (DESIGN §6.2). */
object JeddyPlatforms {
    val all = listOf("linux-x64", "windows-x64")

    /** The platform of the machine running Gradle. */
    fun current(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        require(arch == "amd64" || arch == "x86_64") { "Jeddy targets x64 only, not '$arch'" }
        return when {
            os.startsWith("windows") -> "windows-x64"
            os.startsWith("linux") -> "linux-x64"
            else -> throw GradleException("Jeddy targets Windows and Linux only, not '$os'")
        }
    }
}

/**
 * Puts one platform's prebuilt native libraries under `natives/<platform>/` in [outputDir].
 *
 * Normally it downloads the pinned `natives-<platform>.zip` from the GitHub release and checks its
 * SHA-256. With `-Pjeddy.natives.dir=<dir>` it copies a local build instead (a directory holding
 * `natives.json` and the libraries, as written by `natives/build.py`).
 */
@CacheableTask
abstract class FetchNativesTask : DefaultTask() {
    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @get:Inject
    abstract val archiveOperations: ArchiveOperations

    @get:Input
    abstract val platform: Property<String>

    @get:Input
    abstract val url: Property<String>

    @get:Input
    @get:Optional
    abstract val sha256: Property<String>

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val localDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val target = outputDir.get().asFile.resolve("natives/${platform.get()}")
        fileSystemOperations.delete { delete(outputDir) }
        target.mkdirs()

        if (localDir.isPresent) {
            val dir = localDir.get().asFile
            if (!dir.resolve("natives.json").isFile) {
                throw GradleException("jeddy.natives.dir=$dir has no natives.json (run natives/build.py first)")
            }
            fileSystemOperations.copy { from(dir); into(target) }
            return
        }

        val expected = sha256.orNull?.takeIf { it.isNotBlank() }
            ?: throw GradleException(
                "No SHA-256 pinned for natives-${platform.get()}.zip. Set jeddy.natives.sha256.${platform.get()} " +
                    "in gradle.properties, or build locally and pass -Pjeddy.natives.dir=<natives/out/<platform>>.",
            )
        val zip = File(temporaryDir, "natives-${platform.get()}.zip")
        logger.lifecycle("Downloading ${url.get()}")
        URI(url.get()).toURL().openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
        val actual = sha256Of(zip)
        if (!actual.equals(expected, ignoreCase = true)) {
            throw GradleException("SHA-256 mismatch for ${url.get()}: expected $expected, got $actual")
        }
        fileSystemOperations.copy { from(archiveOperations.zipTree(zip)); into(target) }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
