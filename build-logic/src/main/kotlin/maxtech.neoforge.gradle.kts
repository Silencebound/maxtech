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
// The client screenshot harness (runScreenshots below) lives in Lib and is compiled into every mod's GameTest source
// set, so each mod's scenes can use it and no jar contains it.
gametest.java.srcDir(rootProject.file("lib/neoforge/src/screenshots/java"))

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
        // ./gradlew :<mod>:neoforge:runScreenshots -Pscene=<name>: a client starts, creates a fresh flat world, builds the
        // scene, saves its shots to runs/<mod>-neoforge/screenshots/auto/<scene>/<shot>.png and quits (the client-check
        // skill; the scenes are in each mod's GameTest source set). -Pscreenshots.width/height set the window size.
        register("screenshots") {
            client()
            systemProperty("maxtech.screenshots.scene", providers.gradleProperty("scene").orElse("").get())
            systemProperty("maxtech.screenshots.dir", runDirectory("screenshots").dir("auto").asFile.absolutePath)
            programArguments.addAll(
                "--width", providers.gradleProperty("screenshots.width").orElse("1280").get(),
                "--height", providers.gradleProperty("screenshots.height").orElse("720").get(),
            )
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
        // GuideME is Lib's required dependency, bundled in Lib's jar; in dev runs it is a plain jar on the classpath.
        runtimeOnly(libs.findLibrary("guideme").get())
    }
}

// ---- Guides (lib.neoforge.api.guide.Guides) ------------------------------------------------------------------------
// A mod with pages under assets/<modid>/guides/<modid>/guide gets guide hot reload in runClient and the guide website
// (docs/milestones/MGM/web-export.md); release tags publish Storage's through .github/workflows/guide-site.yml:
//   ./gradlew :<mod>:neoforge:runExportGuide        a client starts, GuideME exports the guide, the game quits
//   ./gradlew :<mod>:neoforge:createGuideWebsite    GuideME's generator turns the export into a static site
//   ./gradlew :<mod>:neoforge:serveGuideWebsite     serves the site on http://localhost:8000 until stopped
// The export needs a client with an OpenGL context (it renders item icons and scenes). GuideME 26.1.15-beta has no
// title, logo or favicon arguments and calls every guide "Applied Energistics 2", so the site uses our copy of its page
// template (build-logic/guide-web/templates/layout.html) with the mod's name and its logo from docs/branding.
val guidePages = common.file("src/main/resources/assets/$modId/guides/$modId/guide")
if (guidePages.isDirectory) {
    val guideId = "$modId:guide"
    val guideExportDir = layout.buildDirectory.dir("exportedGuide")
    val guideWebsiteDir = layout.buildDirectory.dir("guideWebsite")
    // Hot reload for writing pages: in the dev client GuideME reads the pages from the source folder and reloads a page
    // as soon as its file is saved.
    neoForge.runs.named("client") {
        systemProperty("guideme.$modId.guide.sources", guidePages.absolutePath)
    }
    // GuideME writes icons and scene pictures as WebP through FFmpeg (bytedeco), which it does not ship. MDG 2.0.147 has
    // no per-run classpath, so FFmpeg (this machine's natives only) joins the runtime classpath only when the export
    // runs; never the jar or other runs. The version is the one GuideME 26.1.15-beta builds against.
    if (gradle.startParameter.taskNames.any { it.contains("ExportGuide") }) {
        val osName = System.getProperty("os.name").lowercase()
        val arch = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "arm64" else "x86_64"
        val platform = when {
            osName.contains("win") -> "windows-$arch"
            osName.contains("mac") -> "macosx-$arch"
            else -> "linux-$arch"
        }
        dependencies {
            runtimeOnly("org.bytedeco:ffmpeg:6.0-1.5.9")
            runtimeOnly("org.bytedeco:ffmpeg:6.0-1.5.9:$platform")
            runtimeOnly("org.bytedeco:javacpp:1.5.9:$platform")
        }
    }
    neoForge.runs.register("exportGuide") {
        client()
        systemProperty("guideme.exportOnStartupAndExit", guideId)
        systemProperty("guideme.exportDestination.$modId.guide", guideExportDir.get().asFile.absolutePath)
        systemProperty("guideme.exportModVersion.$modId.guide", project.version.toString())
    }
    tasks.matching { it.name == "runExportGuide" }.configureEach {
        outputs.dir(guideExportDir)
        // The export is the game's output: always run it, never restore it from a cache.
        outputs.upToDateWhen { false }
        val dir = guideExportDir.get().asFile
        doFirst { dir.deleteRecursively() }
    }
    // The generator's --web-assets: our page template with the mod's name, plus the logo and favicon.
    // Local copies, so the filter captures plain strings instead of the script (configuration cache).
    val modName = "MaxTech " + modGroupName.replaceFirstChar { it.uppercase() }
    val guideModId = modId
    val guideWebAssets = tasks.register<Sync>("guideWebAssets") {
        into(layout.buildDirectory.dir("guideWebAssets"))
        from(rootProject.file("build-logic/guide-web")) {
            filter { line: String -> line.replace("@MOD_NAME@", modName).replace("@MOD_ID@", guideModId) }
        }
        from(rootProject.file("docs/branding/${guideModId}_icon_256.png")) {
            into("assets")
            rename { "${guideModId}_logo.png" }
        }
    }
    val createGuideWebsite = tasks.register<JavaExec>("createGuideWebsite") {
        group = "guide"
        description = "Builds the static guide website from the last runExportGuide."
        dependsOn(guideWebAssets)
        inputs.dir(guideExportDir)
        inputs.files(guideWebAssets)
        outputs.dir(guideWebsiteDir)
        classpath = sourceSets.main.get().runtimeClasspath
        // A package-private class with a public main, which the launcher accepts. GuideME's docs name
        // guideme.web.WebSiteGenerator (with --title, --logo, --clean, ...), which 26.1.15-beta does not have yet.
        mainClass = "guideme.internal.web.StaticSiteGenerator"
        val output = guideWebsiteDir.get().asFile
        doFirst { output.deleteRecursively() } // no --clean in 26.1.15-beta
        args(
            "--data", guideExportDir.get().asFile.absolutePath,
            "--output", output.absolutePath,
            "--web-assets", layout.buildDirectory.dir("guideWebAssets").get().asFile.absolutePath,
        )
    }
    // GuideME 26.1.15-beta has no preview server (its docs' WebSiteServer came later); the JDK's jwebserver serves the
    // folder instead.
    tasks.register<Exec>("serveGuideWebsite") {
        group = "guide"
        description = "Serves the guide website on http://localhost:8000 until stopped."
        mustRunAfter(createGuideWebsite)
        val javaHome = the<JavaToolchainService>().launcherFor(the<JavaPluginExtension>().toolchain)
            .map { it.metadata.installationPath }
        val windows = System.getProperty("os.name").lowercase().contains("win")
        executable = javaHome.get().file(if (windows) "bin/jwebserver.exe" else "bin/jwebserver").asFile.absolutePath
        args("-d", guideWebsiteDir.get().asFile.absolutePath, "-p", "8000")
    }
}
// ---- end guides ----------------------------------------------------------------------------------------------------
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
