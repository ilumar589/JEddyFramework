plugins {
    id("jeddy.ffm-bindings")
}

dependencies {
    api(project(":core"))
}

// Regenerate with ./gradlew :compress:regenerateBindings -Pjextract.home=<dir> (DESIGN §6.4).
val bindingsDir = rootProject.layout.projectDirectory.dir("bindings")

jeddyBindings {
    libraries {
        register("zstd") {
            header = bindingsDir.file("zstd/zstd_all.h")
            includeDirs.from(bindingsDir.dir("zstd/include"))
            includesFile = bindingsDir.file("zstd.includes")
            targetPackage = "dev.jeddy.compress.ffi"
            headerClassName = "ZstdLib"
        }
    }
}
