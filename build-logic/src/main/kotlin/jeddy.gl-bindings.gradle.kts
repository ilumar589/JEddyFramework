// Convention for the gl module: OpenGL bindings generated at build time from the vendored
// Khronos registry (DESIGN §9.1, TASKS M3-01). The output is not committed.

import dev.jeddy.gradle.GenerateGlBindings

plugins {
    id("jeddy.native-library")
}

val bindingsDir = rootProject.layout.projectDirectory.dir("bindings/gl")

val generateGlBindings = tasks.register<GenerateGlBindings>("generateGlBindings") {
    group = "jeddy"
    description = "Generates the OpenGL bindings from bindings/gl/gl.xml."
    registry = bindingsDir.file("gl.xml")
    settings = bindingsDir.file("gl.properties")
    outputDir = layout.buildDirectory.dir("generated/sources/gl/java/main")
}

extensions.getByType<SourceSetContainer>().named("main") {
    java.srcDir(generateGlBindings)
}
