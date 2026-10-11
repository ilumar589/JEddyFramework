// The Javadoc site (TASKS M11-04): every library module's Javadoc under <module>/ plus an index
// page, in build/site. Module scripts list the modules as `javadocs(project(":core"))` etc.
//
//   ./gradlew :site:javadocSite      (published to GitHub Pages by .github/workflows/docs.yml)

plugins {
    base
}

val javadocs = configurations.dependencyScope("javadocs")
val javadocsPath = configurations.resolvable("javadocsPath") {
    extendsFrom(javadocs.get())
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, "jeddy-javadoc-site"))
    }
}

/** One line per module for the index (DESIGN §3). */
val descriptions = mapOf(
    "core" to "Logging, configuration, collections, handles, pools, the job system, natives",
    "math" to "Vectors, matrices, quaternions, the isometric camera, culling and intersection kernels",
    "compress" to "zstd through FFM: streaming, dictionaries",
    "sdl" to "SDL3 bindings and helpers (internal)",
    "gl" to "OpenGL 4.6 core bindings generated from gl.xml, the loader and capabilities",
    "window" to "Windows, display modes, the main-thread command queue",
    "input" to "Keyboard, mouse, gamepads, text input, actions and CRPG gestures",
    "asset" to "VFS, paks, the asynchronous asset manager and cooked formats",
    "render" to "The PBR renderer: frame graph, clustered lights, shadows, decals, particles, post",
    "audio" to "SDL3_mixer voices, buses, 3D emitters, music and ambience",
    "physics" to "Collision queries, navmesh baking and pathfinding, crowds, formations, visibility",
    "animation" to "Skeletons, baked clips, blended and crossfaded instances, skinning palettes",
    "ui" to "Immediate-mode interface: flex layout, SDF text, widgets, draw lists",
    "scene" to "Entities and transform hierarchies",
    "app" to "The application loop and world integration (scene, render, physics, animation, audio, UI)",
    "editor" to "The level editor as a library: commands with undo, picking, gizmos, docked panels",
    "testing" to "Test helpers: allocation assertions",
)

val jeddyVersion = providers.gradleProperty("jeddy.version")

tasks.register<Sync>("javadocSite") {
    group = "documentation"
    description = "Assembles the Javadoc of every module and an index page into build/site."
    from(javadocsPath)
    into(layout.buildDirectory.dir("site"))
    val site = layout.buildDirectory.dir("site")
    val known = descriptions
    val v = jeddyVersion
    doLast {
        val dir = site.get().asFile
        val modules = dir.listFiles()!!.filter { java.io.File(it, "index.html").isFile }.map { it.name }.sorted()
        val rows = modules.joinToString("\n") { m ->
            "<tr><td><a href=\"$m/index.html\"><code>dev.jeddy.$m</code></a></td><td>${known[m] ?: ""}</td></tr>"
        }
        java.io.File(dir, "index.html").writeText(
            """
            <!DOCTYPE html>
            <html lang="en"><head><meta charset="utf-8"><title>Jeddy ${v.get()} API</title>
            <style>body{font:16px/1.5 system-ui,sans-serif;max-width:56rem;margin:2rem auto;padding:0 1rem}
            td{padding:.3rem 1rem .3rem 0;vertical-align:top}code{font-size:.95em}</style></head>
            <body><h1>Jeddy ${v.get()}</h1>
            <p>A Java 27 framework for 3D isometric CRPGs. API documentation per module;
            see the <a href="https://github.com/ilumar589/JEddyFramework">repository</a> for the design,
            the getting-started guide and the iso demo.</p>
            <table>
            $rows
            </table></body></html>
            """.trimIndent(),
        )
    }
}
