plugins {
    id("jeddy.ffm-bindings")
}

dependencies {
    api(project(":core"))
}

// Regenerate with ./gradlew :sdl:regenerateBindings -Pjextract.home=<dir> (DESIGN §6.4).
val bindingsDir = rootProject.layout.projectDirectory.dir("bindings")

jeddyBindings {
    libraries {
        register("sdl3") {
            header = bindingsDir.file("sdl3/include/SDL3/SDL.h")
            includeDirs.from(bindingsDir.dir("sdl3/include"))
            includesFile = bindingsDir.file("sdl3.includes")
            targetPackage = "dev.jeddy.sdl.ffi"
            headerClassName = "SDL3"
        }
        register("sdl3Mixer") {
            header = bindingsDir.file("sdl3_mixer/include/SDL3_mixer/SDL_mixer.h")
            includeDirs.from(bindingsDir.dir("sdl3/include"), bindingsDir.dir("sdl3_mixer/include"))
            includesFile = bindingsDir.file("sdl3_mixer.includes")
            targetPackage = "dev.jeddy.sdl.mixer.ffi"
            headerClassName = "SDL3Mixer"
        }
    }
}
