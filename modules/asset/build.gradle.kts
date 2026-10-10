plugins {
    id("jeddy.native-library")
}

dependencies {
    api(project(":core"))
    // Pak's API takes ZstdDecompressor and returns ZstdDictionary.
    api(project(":compress"))
}
