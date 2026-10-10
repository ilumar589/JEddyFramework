package dev.jeddy.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
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
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject

/**
 * `jeddyPackaging { ... }` (TASKS M11-01/02): how an application becomes a runnable image and an
 * installer. Defaults come from the `application` extension.
 */
abstract class JeddyPackagingExtension {
    /** Launcher and package name (default: the project name). */
    abstract val name: Property<String>

    /** Directories placed next to `bin/` in the image, each under its own name (paks, configs). */
    abstract val appContent: ConfigurableFileCollection

    /**
     * Program arguments of the AOT training run (DESIGN §4.6, JEP 483/514/515): the image is run
     * once with `-XX:AOTCacheOutput` and its launchers then use the cache. Empty: no AOT cache.
     * `-Pjeddy.aot=false` skips training.
     */
    abstract val trainingArguments: ListProperty<String>

    /** Extra JVM flags of the training run only (for example SDL or asset locations). */
    abstract val trainingJvmArguments: ListProperty<String>

    /** Environment of the training run (for example `SDL_VIDEO_DRIVER=dummy` on build machines). */
    abstract val trainingEnvironment: MapProperty<String, String>

    /** `jpackage --type` (default `app-image`; `deb`, `rpm`, `msi`, `exe` or `dmg` need host tools). */
    abstract val packageType: Property<String>

    /** Vendor shown by installers. */
    abstract val vendor: Property<String>

    /** Version of the package (default: the project version, without a `-SNAPSHOT` suffix). */
    abstract val appVersion: Property<String>
}

/**
 * Links a trimmed Java runtime holding the application's modules with `jlink`, and puts the
 * platform's native libraries in `lib/jeddy/natives`, where `NativeLoader` finds them.
 */
@DisableCachingByDefault(because = "Large output that is cheap to rebuild")
abstract class JlinkRuntime : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    /** The application jar and its runtime classpath (modular jars plus the natives jars). */
    @get:Classpath
    abstract val modulePath: ConfigurableFileCollection

    @get:Input
    abstract val mainModule: Property<String>

    @get:Input
    abstract val platform: Property<String>

    /** Supplies `jlink` (the toolchain the application is compiled for). */
    @get:Nested
    abstract val launcher: Property<JavaLauncher>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun link() {
        val out = outputDir.get().asFile
        fileSystem.delete { delete(out) }
        val (natives, modules) = modulePath.files.filter { it.exists() }.partition {
            it.name.startsWith("jeddy-natives-")
        }
        val jlink = launcher.get().metadata.installationPath.file("bin/" + exe("jlink")).asFile
        execOperations.exec {
            executable = jlink.absolutePath
            args(
                "--module-path", modules.joinToString(File.pathSeparator) { it.absolutePath },
                "--add-modules", mainModule.get(),
                "--output", out.absolutePath,
                "--strip-debug",
                "--no-header-files",
                "--no-man-pages",
                "--compress", "zip-6",
            )
        }
        val prefix = "natives/" + platform.get() + "/"
        val target = File(out, "lib/jeddy/natives")
        for (jar in natives) {
            ZipFile(jar).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith(prefix) }
                    .forEach { entry ->
                        val file = File(target, entry.name.removePrefix(prefix))
                        file.parentFile.mkdirs()
                        zip.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } }
                    }
            }
        }
        if (!File(target, "natives.json").isFile) {
            throw GradleException("no natives for ${platform.get()} on the runtime classpath")
        }
    }
}

/**
 * Assembles the runnable image `<name>/`: the linked runtime, launchers (`bin/<name>` and
 * `bin/<name>.bat` with the application's JVM flags), the application content, and, when training
 * arguments are set, an AOT cache trained by one run of the image (`aot/<name>.aot`, used by the
 * launchers when present).
 */
@DisableCachingByDefault(because = "Large output; the AOT training run depends on the machine")
abstract class DistImage : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runtimeDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val appContent: ConfigurableFileCollection

    @get:Input
    abstract val launcherName: Property<String>

    @get:Input
    abstract val mainModule: Property<String>

    @get:Input
    abstract val mainClass: Property<String>

    @get:Input
    abstract val jvmArguments: ListProperty<String>

    @get:Input
    abstract val trainingArguments: ListProperty<String>

    @get:Input
    abstract val trainingJvmArguments: ListProperty<String>

    @get:Input
    abstract val trainingEnvironment: MapProperty<String, String>

    @get:Input
    abstract val train: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Internal
    val aotFile: String
        get() = "aot/" + launcherName.get() + ".aot"

    @TaskAction
    fun assemble() {
        val out = outputDir.get().asFile
        val name = launcherName.get()
        fileSystem.delete { delete(out) }
        fileSystem.copy {
            from(runtimeDir)
            into(File(out, "runtime"))
        }
        for (dir in appContent.files.filter { it.isDirectory }) {
            fileSystem.copy {
                from(dir)
                into(File(out, dir.name))
            }
        }
        // The same flags for the training run and the launchers: an AOT cache only loads when
        // they match (the native-access list is checked).
        val flags = imageFlags(jvmArguments.get(), File(out, "runtime"))
        val target = mainModule.get() + "/" + mainClass.get()
        val sh = File(out, "bin/$name")
        sh.parentFile.mkdirs()
        sh.writeText(
            buildString {
                append("#!/bin/sh\n")
                append("# Launches $name from this image (generated by the jeddy.application plugin).\n")
                append("DIR=\"\$(cd \"\$(dirname \"\$0\")/..\" && pwd)\"\n")
                append("AOT=\"\"\n")
                append("if [ -f \"\$DIR/$aotFile\" ]; then AOT=\"-XX:AOTCache=\$DIR/$aotFile\"; fi\n")
                append("exec \"\$DIR/runtime/bin/java\" ")
                append(flags.joinToString(" ") { shellQuote(it) })
                append(" \${AOT:+\"\$AOT\"} \"-Djeddy.app.dir=\$DIR\" -m $target \"\$@\"\n")
            },
        )
        sh.setExecutable(true, false)
        File(out, "bin/$name.bat").writeText(
            buildString {
                append("@echo off\r\n")
                append("rem Launches $name from this image (generated by the jeddy.application plugin).\r\n")
                append("setlocal\r\n")
                append("set \"DIR=%~dp0..\"\r\n")
                append("set AOT=\r\n")
                append("if exist \"%DIR%\\${aotFile.replace('/', '\\')}\" set AOT=\"-XX:AOTCache=%DIR%\\${aotFile.replace('/', '\\')}\"\r\n")
                append("\"%DIR%\\runtime\\bin\\java.exe\" ")
                append(flags.joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it })
                append(" %AOT% \"-Djeddy.app.dir=%DIR%\" -m $target %*\r\n")
            },
        )
        if (train.get() && trainingArguments.get().isNotEmpty()) {
            val java = File(out, "runtime/bin/" + exe("java"))
            val cache = File(out, aotFile)
            cache.parentFile.mkdirs()
            execOperations.exec {
                executable = java.absolutePath
                workingDir = out
                environment(trainingEnvironment.get())
                args(flags)
                args(trainingJvmArguments.get())
                args("-XX:AOTCacheOutput=" + cache.absolutePath, "-Djeddy.app.dir=" + out.absolutePath)
                args("-m", target)
                args(trainingArguments.get())
            }
            if (!cache.isFile) {
                throw GradleException("the AOT training run wrote no cache")
            }
        }
    }

    private fun shellQuote(s: String): String =
        if (s.all { it.isLetterOrDigit() || it in "-_=:.,/+@%" }) s else "'" + s.replace("'", "'\\''") + "'"
}

/**
 * Wraps the image into a platform package with `jpackage` (`app-image` by default; `deb`/`rpm` on
 * Linux, `msi`/`exe` on Windows with WiX installed).
 */
@DisableCachingByDefault(because = "Installer builds are not worth caching")
abstract class JpackageApp : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val imageDir: DirectoryProperty

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val mainModule: Property<String>

    @get:Input
    abstract val mainClass: Property<String>

    @get:Input
    abstract val jvmArguments: ListProperty<String>

    @get:Input
    abstract val type: Property<String>

    @get:Input
    abstract val appVersion: Property<String>

    @get:Input
    @get:Optional
    abstract val vendor: Property<String>

    @get:Nested
    abstract val launcher: Property<JavaLauncher>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun pack() {
        val image = imageDir.get().asFile
        val out = outputDir.get().asFile
        val name = packageName.get()
        fileSystem.delete { delete(out) }
        out.mkdirs()
        val jpackage = launcher.get().metadata.installationPath.file("bin/" + exe("jpackage")).asFile
        // Everything but the runtime and the launchers is application content: paks, the AOT cache.
        val content = image.listFiles()!!.filter { it.isDirectory && it.name != "runtime" && it.name != "bin" }
        val aot = File(image, "aot/$name.aot")
        execOperations.exec {
            executable = jpackage.absolutePath
            args("--type", type.get(), "--name", name, "--dest", out.absolutePath)
            args("--app-version", appVersion.get())
            vendor.orNull?.let { args("--vendor", it) }
            args("--runtime-image", File(image, "runtime").absolutePath)
            args("--module", mainModule.get() + "/" + mainClass.get())
            for (dir in content) {
                args("--app-content", dir.absolutePath)
            }
            for (flag in imageFlags(jvmArguments.get(), File(image, "runtime"))) {
                args("--java-options", flag)
            }
            args("--java-options", "-Djeddy.app.dir=\$APPDIR")
            if (aot.isFile) {
                args("--java-options", "-XX:AOTCache=\$APPDIR/aot/$name.aot")
            }
        }
    }
}

/**
 * The application's JVM flags for a linked runtime: `--enable-native-access` keeps only modules
 * the runtime holds (from its `release` file), so launchers print no unknown-module warnings.
 */
internal fun imageFlags(flags: List<String>, runtime: File): List<String> {
    val release = File(runtime, "release")
    if (!release.isFile) {
        return flags
    }
    val modules = release.readLines()
        .firstOrNull { it.startsWith("MODULES=") }
        ?.removePrefix("MODULES=")?.trim('"')?.split(' ')?.toSet()
        ?: return flags
    return flags.mapNotNull { flag ->
        if (flag.startsWith("--enable-native-access=")) {
            val kept = flag.removePrefix("--enable-native-access=").split(',').filter { it in modules }
            if (kept.isEmpty()) null else "--enable-native-access=" + kept.joinToString(",")
        } else {
            flag
        }
    }
}

private fun exe(tool: String): String =
    if (System.getProperty("os.name").lowercase().contains("win")) "$tool.exe" else tool
