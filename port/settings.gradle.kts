// The 1.21.1 build of MaxTech Lib (port/README.md). It compiles the GENERATED sources in port/out (convert.py), never
// main's lib/ directly; main's build (../settings.gradle.kts) stays the 26.1.2 build and knows nothing of this one.
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    // Same MDG as main (../gradle/libs.versions.toml); MDG 2.x builds NeoForge 21.1.x too.
    id("net.neoforged.moddev.repositories") version "2.0.147"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        exclusiveContent {
            forRepository { maven("https://api.modrinth.com/maven") { name = "Modrinth" } }
            filter { includeGroup("maven.modrinth") }
        }
        exclusiveContent {
            forRepository { maven("https://maven.ftb.dev/releases") { name = "FTB" } }
            filter { includeGroup("dev.ftb.mods") }
        }
    }
}

rootProject.name = "maxtech-port-1.21.1"

// The project scripts live in port/projects; their sources are the generated tree (port/out/lib/<piece>/src).
include(":lib:common", ":lib:neoforge")
project(":lib").projectDir = file("projects")
project(":lib:common").projectDir = file("projects/lib-common")
project(":lib:neoforge").projectDir = file("projects/lib-neoforge")

// Storage (port/storage-errors.md) is only measured so far: its projects join with -PwithStorage=true, after
// `convert.py --mod storage`, so Lib's own commands (compileJava, test) stay green while Storage has errors.
if (providers.gradleProperty("withStorage").map { it.trim().toBoolean() }.getOrElse(false)) {
    include(":storage:common", ":storage:neoforge")
    project(":storage").projectDir = file("projects/storage")
    project(":storage:common").projectDir = file("projects/storage-common")
    project(":storage:neoforge").projectDir = file("projects/storage-neoforge")
}
