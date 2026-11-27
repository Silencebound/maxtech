import dev.silencebound.maxtech.gradle.CheckApiJavadoc
import dev.silencebound.maxtech.gradle.MaxtechNeoForgeExtension
import dev.silencebound.maxtech.gradle.hasNeoForgeApi
import dev.silencebound.maxtech.gradle.maxtechModId
import dev.silencebound.maxtech.gradle.modGroupName
import dev.silencebound.maxtech.gradle.runDirectory
import dev.silencebound.maxtech.gradle.usesLibApiOnly

plugins {
    id("maxtech.java")
    id("net.neoforged.moddev")
}

val libs = the<VersionCatalogsExtension>().named("libs")
val modId = maxtechModId

version = providers.gradleProperty("${modGroupName}_version").get()
base.archivesName = modId

val maxtech = extensions.create<MaxtechNeoForgeExtension>("maxtech", project)
val common = maxtech.commonProject(project.parent!!.path)

// GameTests (CLAUDE.md, Testing). Loaded as part of this project's mod in dev runs, never packed into the jar.
val gametest = sourceSets.create("gametest")

configurations.named(gametest.compileOnlyConfigurationName) {
    extendsFrom(configurations.compileOnly.get())
}

dependencies {
    // compileOnly: at dev runtime common is loaded as part of the mod through fml.modFolders (ADR-0001).
    compileOnly(project(common.path))
    "gametestImplementation"(sourceSets.main.get().output)
}

// A NeoForge-side public api (hasNeoForgeApi, Lib only so far): types an addon needs that name NeoForge classes,
// which common's api must not (ADR-0002); client-only vanilla packages may live in common's api (ADR-0016). It
// compiles against the mod's common api and NeoForge only, never against common main or this project's main, so a
// use of the mod's internals fails to compile (checkCommonImports does not run here). Main, GameTests and unit tests
// see it; it is part of the mod jar and of dev runs, and is also built as its own jar.
val neoforgeApi = if (hasNeoForgeApi) sourceSets.create("api") else null

if (neoforgeApi != null) {
    dependencies {
        neoforgeApi.compileOnlyConfigurationName(project(common.path, "modApi"))
        // compileOnlyApi, so mods compiling against this one (Storage) see the api too; at dev runtime the api is part
        // of the mod's folders (MaxtechNeoForgeExtension).
        compileOnlyApi(neoforgeApi.output)
        testImplementation(neoforgeApi.output)
    }

    // Same Javadoc rules as common's api (maxtech.common).
    tasks.named<JavaCompile>(neoforgeApi.compileJavaTaskName) {
        options.compilerArgs.addAll(listOf("-Xdoclint:all/protected", "-Xdoclint:-missing"))
    }

    val checkApiJavadoc = tasks.register<CheckApiJavadoc>("checkApiJavadoc") {
        projectDir = layout.projectDirectory
        sources.from(neoforgeApi.java)
        marker = layout.buildDirectory.file("checkApiJavadoc/ok")
    }

    tasks.check {
        dependsOn(checkApiJavadoc)
    }

    val apiJar = tasks.register<Jar>("apiJar") {
        group = "build"
        description = "Assembles the $modId-neoforge-api jar."
        archiveBaseName = "$modId-neoforge-api"
        from(neoforgeApi.output)
    }

    val apiSourcesJar = tasks.register<Jar>("apiSourcesJar") {
        group = "build"
        archiveBaseName = "$modId-neoforge-api"
        archiveClassifier = "sources"
        from(neoforgeApi.allSource)
    }

    tasks.assemble {
        dependsOn(apiJar)
    }

    // Addons compile against this next to common's api: compileOnly(project(":<mod>:neoforge", "modApi")).
    configurations.consumable("modApi") {
        outgoing.artifact(apiJar)
    }

    pluginManager.apply("maven-publish")
    extensions.configure<PublishingExtension> {
        publications.register<MavenPublication>("api") {
            // The shared group of the published APIs (maxtech.common).
            groupId = "dev.silencebound.maxtech"
            artifactId = "$modId-neoforge-api"
            artifact(apiJar)
            artifact(apiSourcesJar)
        }
    }
}

neoForge {
    version = libs.findVersion("neoforge").get().requiredVersion
    addModdingDependenciesTo(gametest)
    neoforgeApi?.let { addModdingDependenciesTo(it) }

    runs {
        register("client") {
            client()
        }
        register("server") {
            server()
            programArgument("--nogui")
        }
        register("gameTestServer") {
            type = "gameTestServer"
            // Vanilla's GameTest server options, for iterating on or re-running one test instead of the whole suite:
            // -Pgametest=<modid>:foo (wildcards allowed, e.g. maxtech_storage:cable_*) and -Pgametest.repeat=25, which
            // the game only applies together with a selector.
            // CI passes neither and runs everything.
            programArguments.addAll(providers.gradleProperty("gametest").map { listOf("--tests", it) }
                .orElse(emptyList()))
            programArguments.addAll(providers.gradleProperty("gametest.repeat").map { listOf("--repeatCount", it) }
                .orElse(emptyList()))
            // -Pgametest.jfr=<file.jfr> records a Java Flight Recording of the run for profiling (the perf skill).
            jvmArguments.addAll(providers.gradleProperty("gametest.jfr")
                .map { listOf("-XX:StartFlightRecording=settings=profile,filename=$it") }
                .orElse(emptyList()))
        }
        register("data") {
            clientData()
            programArguments.addAll(
                "--mod", modId, "--all",
                "--output", common.file("src/generated/resources").absolutePath,
                "--existing", common.file("src/main/resources").absolutePath,
            )
        }
        configureEach {
            // MDG's default is "<project name> - <Run>", and every mod's project is called "neoforge".
            ideName.convention("${modGroupName.replaceFirstChar { it.uppercase() }} ${name.replaceFirstChar { it.uppercase() }}")
            gameDirectory = runDirectory(name)
            logLevel = org.slf4j.event.Level.INFO
        }
    }
}

// Every other mod builds on Lib, so every run loads it and every neoforge project compiles against Lib's
// loader glue (datagen helpers, the Platform implementation's lookups). compileOnly: at dev runtime lib/neoforge is
// loaded as the Lib mod, and in a pack it is Lib's jar.
// A mod on Lib's api only compiles against the api jars instead, so a use of Lib's internals fails to compile; its
// neoforge project also sees Lib's NeoForge-side api, its common project does not (maxtech.common).
if (modGroupName != "lib") {
    maxtech.loadMod(":lib")
    dependencies {
        if (usesLibApiOnly) {
            compileOnly(project(":lib:common", "modApi"))
            compileOnly(project(":lib:neoforge", "modApi"))
        } else {
            compileOnly(project(":lib:neoforge"))
        }
    }
}
maxtech.loadMod(project.parent!!.path)
neoForge.mods.named(modId) {
    sourceSet(gametest)
}

// The GameTest source set is part of the mod in every dev run, so every run must compile it first; otherwise a client
// run loads stale GameTest classes compiled against old main code (NoSuchFieldError at startup).
tasks.matching { it.name.startsWith("run") || (it.name.startsWith("prepare") && it.name.endsWith("Run")) }.configureEach {
    dependsOn(tasks.named(gametest.classesTaskName))
}

// Keep GameTest code under Error Prone/NullAway even when only `build` runs.
tasks.check {
    dependsOn(tasks.named(gametest.classesTaskName))
}

// The LGPL requires the license texts to travel with the binaries.
val licenseFiles = rootProject.files("LICENSE", "COPYING", "COPYING.LESSER")

tasks.jar {
    from(maxtech.sourceSetsOf(common).map { it.output })
    neoforgeApi?.let { from(it.output) }
    from(licenseFiles) { into("META-INF") }
}

tasks.named<Jar>("sourcesJar") {
    from(maxtech.sourceSetsOf(common).map { it.allSource })
    neoforgeApi?.let { from(it.allSource) }
}

val versionProperties = mapOf(
    "mod_version" to version.toString(),
    "minecraft_version_range" to libs.findVersion("minecraft-range").get().requiredVersion,
    "neoforge_version_range" to libs.findVersion("neoforge-range").get().requiredVersion,
    "loader_version_range" to libs.findVersion("javafml-range").get().requiredVersion,
    "lib_version_range" to providers.gradleProperty("lib_version_range").get(),
    "public_repo_url" to providers.gradleProperty("public_repo_url").get(),
)

tasks.processResources {
    // A local copy, so the action below captures a plain map instead of the script (configuration cache).
    val properties = versionProperties
    inputs.properties(properties)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(properties)
    }
}
