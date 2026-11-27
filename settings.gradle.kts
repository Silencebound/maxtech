pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    // Keep in sync with moddev in gradle/libs.versions.toml.
    id("net.neoforged.moddev.repositories") version "2.0.147"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        // JEI (M2 task 10); limited to its groups so nothing else resolves from here.
        exclusiveContent {
            forRepository { maven("https://maven.blamejared.com") { name = "BlameJared" } }
            // JEI's runtime jar depends on mezz_config, published only there too.
            filter {
                includeGroup("mezz.jei")
                includeGroup("net.mezzdev.config")
            }
        }
        // Roughly Enough Items and Cloth Config (M7C item 25), from shedaniel's maven; Architectury from its own.
        exclusiveContent {
            forRepository { maven("https://maven.shedaniel.me") { name = "Shedaniel" } }
            filter {
                includeGroup("me.shedaniel")
                includeGroup("me.shedaniel.cloth")
            }
        }
        exclusiveContent {
            forRepository { maven("https://maven.architectury.dev") { name = "Architectury" } }
            filter { includeGroup("dev.architectury") }
        }
        // Jade (M3 task 11), published on Modrinth's maven; limited to its group.
        exclusiveContent {
            forRepository { maven("https://api.modrinth.com/maven") { name = "Modrinth" } }
            filter { includeGroup("maven.modrinth") }
        }
        // FTB Teams (optional team compat in Lib, compileOnly); limited to FTB's group.
        exclusiveContent {
            forRepository { maven("https://maven.ftb.dev/releases") { name = "FTB" } }
            filter { includeGroup("dev.ftb.mods") }
        }
    }
}

rootProject.name = "maxtech"

include(":lib:common", ":lib:neoforge")
include(":storage:common", ":storage:neoforge")
include(":farms:common", ":farms:neoforge")
