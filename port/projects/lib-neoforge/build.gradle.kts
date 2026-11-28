import java.util.Properties
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

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
    // The vendored transfer api's own LGPL-2.1 jar (lib-common's transferJar, port/NOTICE-transfer.md).
    jarJar(project(common.path, "transferElements"))
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
            // 1.21.1's GameTest server has no `--tests` filter (that is 26.1's). -Pgametest=<namespace> sets
            // NeoForge's neoforge.enabledGameTestNamespaces (GameTestHooks); the default is this mod's namespace.
            // Any `<ns>:<pattern>` is cut to `<ns>`; there is no per-test pattern.
            systemProperty("neoforge.enabledGameTestNamespaces",
                providers.gradleProperty("gametest").map { it.substringBefore(':') }.orElse(modId).get())
        }
        configureEach {
            gameDirectory = rootProject.file("run/$name")
        }
    }

    // JUnit inside FML (ModDevGradle's unit test support), so tests can bootstrap the game's registries.
    unitTest {
        enable()
        testedMod = mods[modId]
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

// Common's tests that need the game's registries (the legacy transfer bridge's) skip themselves in common's plain JUnit
// run and run here, inside FML. Common's test classes join this test source set's output, which ModDevGradle adds to
// the tested mod, so FML loads them in the game layer next to the code they test. Common's test resources stay out:
// their Platform service (the config outline) would replace NeoForge's in the mod.
val commonTest = common.the<SourceSetContainer>()["test"]
sourceSets.test {
    output.dir(mapOf("builtBy" to common.tasks.named(commonTest.classesTaskName)), commonTest.java.destinationDirectory)
}
tasks.test {
    testClassesDirs += commonTest.output.classesDirs
    include("dev/silencebound/maxtech/lib/neoforge/**", "dev/silencebound/maxtech/lib/shim/transfer/bridge/legacy/**")
}

tasks.jar {
    from(common.the<SourceSetContainer>()["main"].output)
    // The vendored transfer api is common's transferJar, nested below; only Lib's legacy bridge stays here.
    val transferRoot = "dev/silencebound/maxtech/lib/shim/transfer/"
    from(common.the<SourceSetContainer>()["api"].output) {
        exclude { !it.isDirectory && it.path.startsWith(transferRoot) && !it.path.startsWith("${transferRoot}bridge/legacy/") }
    }
    includeEmptyDirs = false
    from(apiSourceSet.output)
    from(rootProject.files("../LICENSE", "../COPYING", "../COPYING.LESSER", "NOTICE-transfer.md")) { into("META-INF") }
}

// FML loads Lib and the nested transfer jar into one module layer, which refuses a package that both jars hold.
val checkTransferJar = tasks.register("checkTransferJar") {
    group = "verification"
    description = "Fails when Lib's jar and its nested transfer jar share a package."
    val libJar = tasks.jar.flatMap { it.archiveFile }
    inputs.file(libJar)
    doLast {
        val packages = { entries: Sequence<String> ->
            entries.filter { it.endsWith(".class") }.map { it.substringBeforeLast('/') }.toSet()
        }
        ZipFile(libJar.get().asFile).use { lib ->
            val nestedEntry = lib.entries().asSequence().single { it.name.contains("maxtech_lib-transfer") }
            val nested = ZipInputStream(lib.getInputStream(nestedEntry)).use { zip ->
                packages(generateSequence { zip.nextEntry }.map { it.name }.toList().asSequence())
            }
            val shared = packages(lib.entries().asSequence().map { it.name }) intersect nested
            if (shared.isNotEmpty()) {
                throw GradleException("Packages in both Lib's jar and the nested transfer jar: $shared")
            }
        }
    }
}
tasks.check { dependsOn(checkTransferJar) }

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
