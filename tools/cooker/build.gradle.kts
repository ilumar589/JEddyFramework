plugins {
    id("jeddy.application")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":math"))
    implementation(project(":compress"))
    implementation(project(":asset"))
    implementation(project(":physics"))
}

application {
    mainModule = "dev.jeddy.cooker"
    mainClass = "dev.jeddy.cooker.CookerMain"
}
