plugins {
    id("jeddy.application")
    id("jeddy.ffm-bindings")
    // Game projects resolve the cooker for their asset builds (dev.jeddy.assets plugin).
    id("jeddy.publishing")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":math"))
    implementation(project(":compress"))
    implementation(project(":asset"))
    implementation(project(":physics"))
}

// Regenerate with ./gradlew :cooker:regenerateBindings -Pjextract.home=<dir> (DESIGN §6.4).
val bindingsDir = rootProject.layout.projectDirectory.dir("bindings")

jeddyBindings {
    libraries {
        register("ktx") {
            header = bindingsDir.file("ktx/include/ktx.h")
            includeDirs.from(bindingsDir.dir("ktx/include"))
            includesFile = bindingsDir.file("ktx.includes")
            targetPackage = "dev.jeddy.cooker.ktx.ffi"
            headerClassName = "Ktx"
        }
        register("meshoptimizer") {
            header = bindingsDir.file("meshoptimizer/include/meshoptimizer.h")
            includeDirs.from(bindingsDir.dir("meshoptimizer/include"))
            includesFile = bindingsDir.file("meshoptimizer.includes")
            targetPackage = "dev.jeddy.cooker.meshopt.ffi"
            headerClassName = "MeshOpt"
        }
        register("jeddyTools") {
            header = bindingsDir.file("jeddy_tools/include/jeddy_tools_native.h")
            includeDirs.from(bindingsDir.dir("jeddy_tools/include"))
            includesFile = bindingsDir.file("jeddy_tools.includes")
            targetPackage = "dev.jeddy.cooker.shim.ffi"
            headerClassName = "JeddyTools"
        }
    }
}

application {
    mainModule = "dev.jeddy.cooker"
    mainClass = "dev.jeddy.cooker.CookerMain"
}
