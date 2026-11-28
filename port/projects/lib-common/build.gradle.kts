import java.util.Properties

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

// The vendored NeoForge transfer api (port/NOTICE-transfer.md) as its own jar under LGPL-2.1-only, nested in Lib's jar
// (lib-neoforge's jarJar) and kept out of Lib's classes. bridge/legacy is Lib's own code and stays in Lib's jar. A game
// library, not a mod: FML loads it in the game layer with Lib, so its classes and Lib's see each other both ways (it
// uses Lib's shim.saving interfaces; Lib uses it); no package is in both jars.
val transferRoot = "dev/silencebound/maxtech/lib/shim/transfer/"
val transferVersion = Properties()
    .apply { rootProject.file("../gradle.properties").reader().use { load(it) } }
    .getProperty("lib_version") + "+mc1.21.1"
val transferJar = tasks.register<Jar>("transferJar") {
    archiveBaseName = "maxtech_lib-transfer"
    archiveVersion = transferVersion
    from(apiSourceSet.output) {
        include("$transferRoot**")
        exclude("${transferRoot}bridge/legacy/**")
    }
    includeEmptyDirs = false
    from(rootProject.files("NOTICE-transfer.md", "LICENSE-transfer.txt")) { into("META-INF") }
    manifest {
        attributes(
            "FMLModType" to "GAMELIBRARY",
            "Automatic-Module-Name" to "dev.silencebound.maxtech.lib.transfer",
            "Implementation-Title" to "MaxTech Lib vendored NeoForge transfer api",
            "Implementation-Version" to transferVersion,
        )
    }
}

configurations.consumable("transferElements") {
    outgoing.artifact(transferJar)
    outgoing.capability("dev.silencebound.maxtech.lib:maxtech_lib-transfer:$transferVersion")
}

apply(from = rootProject.file("projects/error-map.gradle.kts"))
