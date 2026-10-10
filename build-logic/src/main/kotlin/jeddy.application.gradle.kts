// Convention for runnable programs in this repository (samples, the cooker CLI): the Jeddy
// conventions plus the launcher flags of DESIGN §4.6 and §6.3, this platform's natives and the
// packaging tasks (jlinkRuntime -> distImage -> distImageZip / jpackage; DESIGN §14). Game
// projects outside the repository get the same through the dev.jeddy.game plugin. Module scripts
// set application.mainModule and mainClass, and configure packaging with jeddyPackaging { ... }.

import dev.jeddy.gradle.JeddyPlatforms
import dev.jeddy.gradle.configureJeddyApplication

plugins {
    application
    id("jeddy.java-base")
}

configureJeddyApplication(project(":natives-" + JeddyPlatforms.current()))
