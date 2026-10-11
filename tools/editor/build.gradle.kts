plugins {
    id("jeddy.application")
}

dependencies {
    implementation(project(":editor"))
    implementation(project(":app"))
    implementation(project(":gl"))
    implementation(project(":cooker"))
}

application {
    mainModule = "dev.jeddy.editortool"
    mainClass = "dev.jeddy.editortool.EditorMain"
}

// `./gradlew :editor-tool:run --args="--project samples/iso-demo/assets --level levels/crypt.jlevel"`
// takes paths relative to the repository root, like the cooker's own run task.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
