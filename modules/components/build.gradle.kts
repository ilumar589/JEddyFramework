plugins {
    id("jeddy.java-library")
}

dependencies {
    api(project(":core"))
    api(project(":math"))
    api(project(":scene"))
    api(project(":asset"))
    // The tests declare components and behaviours of their own and let the processor generate
    // the stores and the registry for them (DESIGN §22): the generated code is what is tested.
    testAnnotationProcessor(project(":component-processor"))
}
