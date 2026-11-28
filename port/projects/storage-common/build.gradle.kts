// Storage common on 1.21.1, for the error map only so far (port/storage-errors.md): main's source sets (api, main, test)
// from the generated tree port/out/storage/common, against the generated Lib. Included with -PwithStorage=true.
plugins {
    `java-library`
    id("net.neoforged.moddev")
}

val gen = rootProject.file("out/storage/common/src")

group = "dev.silencebound.maxtech.storage"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

sourceSets {
    create("api") {
        java.setSrcDirs(listOf(gen.resolve("api/java")))
        resources.setSrcDirs(listOf(gen.resolve("api/resources")))
    }
    main {
        java.setSrcDirs(listOf(gen.resolve("main/java")))
        resources.setSrcDirs(listOf(gen.resolve("main/resources"), gen.resolve("generated/resources")))
    }
    test {
        java.setSrcDirs(listOf(gen.resolve("test/java")))
        resources.setSrcDirs(listOf(gen.resolve("test/resources")))
    }
}
val apiSourceSet = sourceSets["api"]

neoForge {
    version = libs.versions.neoforge.asProvider().get()
    addModdingDependenciesTo(apiSourceSet)
    addModdingDependenciesTo(sourceSets.test.get())
}

dependencies {
    api(apiSourceSet.output)
    // As main's storage/common: Lib only, and Storage's api names Lib's api types.
    api(project(":lib:common"))
    "apiCompileOnly"(project(":lib:common", "modApi"))
    "apiCompileOnly"(libs.jspecify)
    compileOnly(libs.jspecify)
    testCompileOnly(libs.jspecify)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xmaxerrs", "100000", "-Xmaxwarns", "0"))
}

// The error map compiles against Lib's classes, so Lib is built first.
tasks.matching { it.name == "errorMapInputs" }.configureEach {
    dependsOn(":lib:common:classes", ":lib:common:apiClasses")
}

apply(from = rootProject.file("projects/error-map.gradle.kts"))
