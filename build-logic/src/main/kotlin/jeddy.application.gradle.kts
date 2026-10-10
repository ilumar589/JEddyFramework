// Convention for runnable Jeddy programs (samples, cooker CLI). Sets the launcher JVM flags
// from DESIGN §4.6 and §6.3. Module scripts set application.mainModule and mainClass.

plugins {
    application
    id("jeddy.java-base")
}

/** Every Jeddy module that calls restricted FFM methods. Unknown names only produce a warning. */
val nativeAccessModules = listOf(
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

dependencies {
    // Native libraries of the platform running the build; distributions per platform come in M11.
    "runtimeOnly"(project(":natives-" + dev.jeddy.gradle.JeddyPlatforms.current()))
}

extensions.configure<JavaApplication> {
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=" + nativeAccessModules.joinToString(","),
        "-XX:+UseZGC",
        "-XX:+AlwaysPreTouch",
    )
}
