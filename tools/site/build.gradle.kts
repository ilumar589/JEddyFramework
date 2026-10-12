plugins {
    id("jeddy.site")
}

dependencies {
    listOf(
        "core", "math", "compress", "sdl", "gl", "window", "input",
        "asset", "render", "audio", "physics", "animation", "ui", "scene", "app", "editor", "rules",
        "components", "save", "testing",
    ).forEach { "javadocs"(project(":$it")) }
}
