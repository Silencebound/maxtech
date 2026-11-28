// Lib common on 1.21.1: the same source sets as main's maxtech.common (api, main, test), compiled from the generated
// tree port/out/lib/common. No Error Prone, NullAway, Spotless or -Werror here: the generated code is checked on main.
plugins {
    `java-library`
    id("net.neoforged.moddev")
}

val gen = rootProject.file("out/lib/common/src")

group = "dev.silencebound.maxtech.lib"

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

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

val apiJar = tasks.register<Jar>("apiJar") {
    archiveBaseName = "maxtech_lib-api"
    from(apiSourceSet.output)
}

configurations.consumable("modApi") {
    outgoing.artifact(apiJar)
}

apply(from = rootProject.file("projects/error-map.gradle.kts"))
