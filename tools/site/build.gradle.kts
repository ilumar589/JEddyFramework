plugins {
    id("jeddy.site")
}

dependencies {
    listOf(
        "core", "math", "compress", "sdl", "gl", "window", "input",
        "asset", "render", "audio", "physics", "animation", "ui", "scene", "app", "editor", "rules",
        "components", "testing",
    ).forEach { "javadocs"(project(":$it")) }
}
