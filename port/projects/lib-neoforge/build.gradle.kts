import java.util.Properties

// Lib's NeoForge side on 1.21.1: the source sets of main's maxtech.neoforge (api, main, gametest, test), compiled from
// the generated tree port/out/lib/neoforge, plus the dev runs and the `errorMap` task (port/README.md).
plugins {
    `java-library`
    id("net.neoforged.moddev")
}

val gen = rootProject.file("out/lib/neoforge/src")
val common = project(":lib:common")
val modId = "maxtech_lib"

// Main's version line, so the port never drifts from it.
val mainProperties = Properties().apply { rootProject.file("../gradle.properties").reader().use { load(it) } }
version = mainProperties.getProperty("lib_version") + "+mc1.21.1"
group = "dev.silencebound.maxtech.lib"
base.archivesName = modId

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
        resources.setSrcDirs(listOf(gen.resolve("main/resources")))
    }
    create("gametest") {
        java.setSrcDirs(listOf(gen.resolve("gametest/java")))
        resources.setSrcDirs(listOf(gen.resolve("gametest/resources")))
    }
    test {
        java.setSrcDirs(listOf(gen.resolve("test/java")))
        resources.setSrcDirs(listOf(gen.resolve("test/resources")))
    }
}
val apiSourceSet = sourceSets["api"]
val gametest = sourceSets["gametest"]

configurations.named(gametest.compileOnlyConfigurationName) {
    extendsFrom(configurations.compileOnly.get())
}

dependencies {
    "apiCompileOnly"(project(common.path, "modApi"))
    compileOnlyApi(apiSourceSet.output)
    compileOnly(project(common.path))
    "gametestImplementation"(sourceSets.main.get().output)
    "gametestImplementation"(apiSourceSet.output)
    for (configuration in listOf("apiCompileOnly", "compileOnly", "testCompileOnly")) {
        configuration(libs.jspecify)
    }

    implementation(libs.guideme)
    jarJar(libs.guideme) {
        version {
            strictly("[21.1,21.2)")
            prefer(libs.versions.guideme.get())
        }
    }
    compileOnly(libs.kubejs)
    compileOnly(libs.rhino)
    compileOnly(libs.jade.neoforge)
    runtimeOnly(libs.jade.neoforge)
    compileOnly(libs.ftb.teams.neoforge) { isTransitive = false }

    testImplementation(project(common.path))
    testImplementation(apiSourceSet.output)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

neoForge {
    version = libs.versions.neoforge.asProvider().get()
    addModdingDependenciesTo(apiSourceSet)
    addModdingDependenciesTo(gametest)
    addModdingDependenciesTo(sourceSets.test.get())

    mods {
        register(modId) {
            sourceSet(sourceSets.main.get())
            sourceSet(apiSourceSet)
            sourceSet(gametest)
            sourceSet(common.the<SourceSetContainer>()["main"])
            sourceSet(common.the<SourceSetContainer>()["api"])
        }
    }

    runs {
        register("client") { client() }
        register("server") {
            server()
            programArgument("--nogui")
        }
        register("gameTestServer") {
            type = "gameTestServer"
            programArguments.addAll(providers.gradleProperty("gametest").map { listOf("--tests", it) }
                .orElse(emptyList()))
        }
        configureEach {
            gameDirectory = rootProject.file("run/$name")
        }
    }
}

evaluationDependsOn(common.path)

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xmaxerrs", "100000", "-Xmaxwarns", "0"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.jar {
    from(common.the<SourceSetContainer>()["main"].output)
    from(common.the<SourceSetContainer>()["api"].output)
    from(apiSourceSet.output)
    from(rootProject.files("../LICENSE", "../COPYING", "../COPYING.LESSER", "NOTICE-transfer.md")) { into("META-INF") }
}

val versionProperties = mapOf(
    "mod_version" to version.toString(),
    "minecraft_version_range" to libs.versions.minecraft.range.get(),
    "neoforge_version_range" to libs.versions.neoforge.range.get(),
    "loader_version_range" to libs.versions.javafml.range.get(),
    "lib_version_range" to mainProperties.getProperty("lib_version_range"),
    "public_repo_url" to mainProperties.getProperty("public_repo_url"),
)

tasks.processResources {
    val properties = versionProperties
    inputs.properties(properties)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(properties)
    }
}


apply(from = rootProject.file("projects/error-map.gradle.kts"))
