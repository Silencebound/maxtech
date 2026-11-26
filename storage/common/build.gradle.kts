plugins {
    id("maxtech.common")
}

dependencies {
    // Storage builds on Lib only (ADR-0014).
    api(project(":lib:common"))
    // The api source set does not see main's dependencies; Storage's api names Lib's api types (StackKey, OwnerRecord).
    "apiCompileOnly"(project(":lib:common", "modApi"))
}

// TextureKitTest runs the texture generators with --check; rerun it when a generator changes, not only its PNGs.
tasks.named<Test>("test") {
    inputs.dir(rootProject.file("storage/neoforge/src/textures"))
        .withPropertyName("textureGenerators")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
