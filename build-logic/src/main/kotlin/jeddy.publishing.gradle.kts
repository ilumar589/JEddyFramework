// Maven publishing for Jeddy artifacts (TASKS M11-05): `jeddy-<project>` under group dev.jeddy at
// jeddy.version. `./gradlew publishToMavenLocal` for local game projects; the release workflow
// publishes tags to GitHub Packages (credentials from GITHUB_ACTOR / GITHUB_TOKEN, or the
// gpr.user / gpr.key Gradle properties).

plugins {
    `maven-publish`
}

val repoUrl = providers.gradleProperty("jeddy.repo.url")
val publishUrl = providers.gradleProperty("jeddy.publish.url")
    .orElse(repoUrl.map { it.replace("https://github.com/", "https://maven.pkg.github.com/") })
val publishUser = providers.environmentVariable("GITHUB_ACTOR").orElse(providers.gradleProperty("gpr.user"))
val publishKey = providers.environmentVariable("GITHUB_TOKEN").orElse(providers.gradleProperty("gpr.key"))

extensions.configure<PublishingExtension> {
    publications {
        register<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "jeddy-${project.name}"
            pom {
                name = "Jeddy ${project.name}"
                description = "Jeddy framework: ${project.name}"
                url = repoUrl
                scm {
                    url = repoUrl
                    connection = repoUrl.map { "scm:git:$it.git" }
                }
            }
        }
    }
    repositories {
        if (publishUser.isPresent && publishKey.isPresent) {
            maven {
                name = "GitHubPackages"
                url = uri(publishUrl.get())
                credentials {
                    username = publishUser.get()
                    password = publishKey.get()
                }
            }
        }
    }
}
