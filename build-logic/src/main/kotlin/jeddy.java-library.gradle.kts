// Convention for Jeddy runtime and tools library modules: sources and Javadoc jars, Maven
// publishing (M11-05), and the module's part of the Javadoc site (M11-04, :site).

plugins {
    `java-library`
    id("jeddy.java-base")
    id("jeddy.publishing")
}

extensions.configure<JavaPluginExtension> {
    withSourcesJar()
    withJavadocJar()
}

tasks.named<Javadoc>("javadoc") {
    (options as StandardJavadocDocletOptions).apply {
        // Prose documents behaviour; a @param per argument would mostly repeat the names.
        addBooleanOption("Xdoclint:all,-missing", true)
        addBooleanOption("quiet", true)
        encoding = "UTF-8"
        windowTitle = "Jeddy ${project.name} ${project.version}"
        docTitle = "Jeddy <code>${project.name}</code> ${project.version}"
    }
}

// The module's pages under <name>/ for the aggregated site (tools/site).
val javadocSitePart = tasks.register<Sync>("javadocSitePart") {
    from(tasks.named("javadoc"))
    into(layout.buildDirectory.dir("docs/site/${project.name}"))
}
configurations.consumable("javadocSiteElements") {
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, "jeddy-javadoc-site"))
    }
    outgoing.artifact(layout.buildDirectory.dir("docs/site")) {
        builtBy(javadocSitePart)
    }
}
