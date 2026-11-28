// Storage's NeoForge side on 1.21.1, for the error map only so far (port/storage-errors.md): main's source sets (main,
// gametest) from port/out/storage/neoforge, against the generated Lib. Included with -PwithStorage=true. No runs yet.
// Optional mods stay off as in main's default build; only those with a 1.21.1 line in the port's catalog (KubeJS, FTB
// Teams) are compiled against, so compat for JEI, REI, Curios, AE2, RS and FTB Library shows as missing packages.
plugins {
    `java-library`
    id("net.neoforged.moddev")
}

val gen = rootProject.file("out/storage/neoforge/src")
val common = project(":storage:common")
evaluationDependsOn(":lib:neoforge")
val libGametest = project(":lib:neoforge").extensions.getByType<SourceSetContainer>()["gametest"]

group = "dev.silencebound.maxtech.storage"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

sourceSets {
    main {
        java.setSrcDirs(listOf(gen.resolve("main/java")))
        resources.setSrcDirs(listOf(gen.resolve("main/resources")))
    }
    create("gametest") {
        java.setSrcDirs(listOf(gen.resolve("gametest/java")))
        resources.setSrcDirs(listOf(gen.resolve("gametest/resources")))
    }
}
val gametest = sourceSets["gametest"]

configurations.named(gametest.compileOnlyConfigurationName) {
    extendsFrom(configurations.compileOnly.get())
}

dependencies {
    compileOnly(project(common.path))
    compileOnly(project(":lib:neoforge"))
    compileOnly(project(":lib:common"))
    "gametestImplementation"(sourceSets.main.get().output)
    // Lib's GameTest shims (shim.gametest: the 26.1 test registries, TestData, RegisterGameTestsEvent) are in Lib's
    // gametest source set, which main's Storage cannot see; the measurement assumes they become visible to Storage.
    "gametestCompileOnly"(libGametest.output)
    compileOnly(libs.jspecify)
    // Lib's guide api and the guide_guideme_* GameTests name GuideME's types (bundled in Lib's jar on main).
    compileOnly(libs.guideme)
    compileOnly(libs.kubejs)
    compileOnly(libs.rhino)
    compileOnly(libs.ftb.teams.neoforge) { isTransitive = false }
}

neoForge {
    version = libs.versions.neoforge.asProvider().get()
    addModdingDependenciesTo(gametest)
}

evaluationDependsOn(common.path)

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xmaxerrs", "100000", "-Xmaxwarns", "0"))
}

tasks.matching { it.name == "errorMapInputs" }.configureEach {
    dependsOn(":lib:common:classes", ":lib:common:apiClasses", ":lib:neoforge:classes", ":lib:neoforge:apiClasses",
        ":lib:neoforge:gametestClasses")
}

apply(from = rootProject.file("projects/error-map.gradle.kts"))
