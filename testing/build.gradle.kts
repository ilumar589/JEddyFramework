plugins {
    id("jeddy.java-library")
}

dependencies {
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter.api)
}
