plugins {
    id("maxtech.neoforge")
}

dependencies {
    // GuideME draws Storage's guide (MGM) and is required. It is bundled in Storage's jar (NeoForge jar-in-jar), so
    // players install nothing extra; when another mod (AE2) brings a newer GuideME, FML loads only the newest one.
    // The range is GuideME's 26.1 line, the versions that support 26.1.2.
    implementation(libs.guideme)
    jarJar(libs.guideme) {
        version {
            strictly("[26.1.15-beta,26.2-)")
            prefer(libs.versions.guideme.get())
        }
    }
    // The guide_guideme_* GameTests parse the pages with GuideME's own parser; at runtime the mod's GuideME is there.
    "gametestCompileOnly"(libs.guideme)
    // JEI is optional (compat.jei, the terminal's search sync); only its API is compiled against.
    compileOnly(libs.jei.common.api)
    // REI is optional (compat.rei); only its API is compiled against. Its NeoForge jar also holds the default plugin and
    // the @REIPluginClient annotation. EMI has no 26.1.2 build (Modrinth lists up to 1.21.1 on NeoForge), so no compat.emi.
    compileOnly(libs.rei.neoforge)
    compileOnly(libs.basic.math)
    // KubeJS is optional (compat.kubejs, the machine recipe schemas); compiled against only, never bundled.
    compileOnly(libs.kubejs)
    compileOnly(libs.rhino)
    // FTB Teams is optional (compat.ftbteams: logic budgets and team notices) and All Rights Reserved: compiled against
    // only, never bundled. Not transitive, since only its API classes are used; FTB Library (same licence, same rule)
    // only for the base class of FTB Teams' NeoForge events.
    compileOnly(libs.ftb.teams.neoforge) { isTransitive = false }
    compileOnly(libs.ftb.library.neoforge) { isTransitive = false }
    // Curios is optional (compat.curios: gadgets in accessory slots); compiled against only, never bundled.
    compileOnly(libs.curios)
    // Refined Storage is optional (compat.refinedstorage, the network bridge; MIT). RS publishes no public maven, so this
    // is its NeoForge mod jar from Modrinth's maven, by version id (P12kjgPK = refinedstorage-neoforge-3.2.1; the plain
    // version number is shared with the Fabric file). Compiled against only, never bundled.
    val refinedStorage = "maven.modrinth:refined-storage:P12kjgPK"
    compileOnly(refinedStorage)
    // RS's API types carry @API(status = ...) from apiguardian; without the annotation class javac warns, and -Werror
    // fails the build.
    compileOnly("org.apiguardian:apiguardian-api:1.1.2")
    // JEI and Jade join the dev runs; never the jar.
    // With withRei=true, REI (plus the Architectury and Cloth Config it requires) replaces JEI in the dev runs, since two
    // viewers would draw their overlays over each other. Never GameTest or datagen, never the jar.
    val withRei = providers.gradleProperty("withRei").map { it.trim().toBoolean() }.getOrElse(false) &&
        gradle.startParameter.taskNames.none { it.contains("GameTest", ignoreCase = true) || it.contains("runData") }
    if (withRei) {
        runtimeOnly(libs.rei.neoforge)
        runtimeOnly(libs.architectury.neoforge)
        runtimeOnly(libs.cloth.config.neoforge)
    } else {
        runtimeOnly(libs.jei.neoforge)
    }
    runtimeOnly(libs.jade.neoforge)
    // KubeJS (LGPL-3.0), Rhino and Better Advanced Tooltips (MIT; KubeJS 8.0.6 fails to start without it, though its
    // mods.toml says optional) join the dev client and server only with withKubeJS=true (or withKubeJs), for trying script node types
    // and events by hand; never the jar. Never GameTest or datagen: with KubeJS loaded, mock players in GameTests get
    // KubeJS's sync payload and fail, so the logic compat is tested through fakes instead.
    // Both spellings work: -PwithKubeJS=true and -PwithKubeJs=true.
    val withKubeJs = (providers.gradleProperty("withKubeJS").orElse(providers.gradleProperty("withKubeJs")))
        .map { it.trim().toBoolean() }.getOrElse(false) &&
        gradle.startParameter.taskNames.none { it.contains("GameTest", ignoreCase = true) || it.contains("runData") }
    if (withKubeJs) {
        runtimeOnly(libs.kubejs)
        runtimeOnly(libs.rhino)
        runtimeOnly(libs.better.advanced.tooltips)
    }
    // Curios (LGPL-3.0-or-later) joins the dev runs only with withCurios=true, for trying the gadgets in its slots by
    // hand and for the curios_* GameTests (-PwithCurios=true -Pgametest='maxtech_storage:curios_*'); never the jar,
    // datagen or CI. Not the whole suite: like KubeJS, Curios sends its sync payload to mock players placed in the
    // level, so tests using them fail with it loaded. CI tests the lookup through a fake slot instead.
    val withCurios = providers.gradleProperty("withCurios").map { it.trim().toBoolean() }.getOrElse(false) &&
        gradle.startParameter.taskNames.none { it.contains("runData") }
    if (withCurios) {
        runtimeOnly(libs.curios)
    }
    // Applied Energistics 2 (LGPL-3.0) is optional (compat.ae2, the network bridge's AE2 side); compiled against only,
    // never bundled. From Maven Central, where AE2 publishes. The full jar, not the api classifier: the ae2_* GameTests
    // build an AE2 grid from its block entities. With withAe2=true it joins the dev client, server and GameTest runs
    // (-PwithAe2=true -Pgametest='maxtech_storage:ae2_*'); its POM's GuideME resolves to Storage's own. Never
    // datagen or the jar. CI runs the ae2_* GameTests in a step of their own (nightly, and on PRs touching the bridge).
    val ae2 = "org.appliedenergistics:appliedenergistics2:26.1.13-beta"
    compileOnly(ae2) { isTransitive = false }
    val withAe2 = providers.gradleProperty("withAe2").map { it.trim().toBoolean() }.getOrElse(false) &&
        gradle.startParameter.taskNames.none { it.contains("runData") }
    if (withAe2) {
        runtimeOnly(ae2)
    }
    // Refined Storage joins the dev runs only with withRs=true (or withRS), for bridging by hand and for the rs_*
    // GameTests (-PwithRs=true -Pgametest='maxtech_storage:rs_*'); never the jar or datagen. CI runs the rs_* GameTests in
    // a step of their own (nightly, and on PRs touching the bridge); the plain suite tests the bridge through a fake.
    val withRs = (providers.gradleProperty("withRs").orElse(providers.gradleProperty("withRS")))
        .map { it.trim().toBoolean() }.getOrElse(false) &&
        gradle.startParameter.taskNames.none { it.contains("runData") }
    if (withRs) {
        runtimeOnly(refinedStorage)
    }
    // FTB Chunks, FTB Library and FTB Teams are All Rights Reserved. Only with withFtbChunks=true (claim tests in the
    // dev client) do they join this project's runs, from FTB's maven; never bundled, published or run in CI. runtimeOnly
    // in a neoforge project reaches neither the jar nor a POM. FTB Backups 3 comes in through FTB Chunks' POM but is
    // optional there, so it is left out. GameTest and datagen tasks never load them, so a switch left on in a user's
    // gradle.properties can't change test or datagen results.
    val ftbChunks = providers.gradleProperty("withFtbChunks").map { it.trim().toBoolean() }.getOrElse(false) &&
        gradle.startParameter.taskNames.none { it.contains("GameTest", ignoreCase = true) || it.contains("runData") }
    if (ftbChunks) {
        runtimeOnly(libs.ftb.chunks.neoforge) { exclude(group = "dev.ftb.mods", module = "ftb-backups-3") }
        runtimeOnly(libs.ftb.library.neoforge)
        runtimeOnly(libs.ftb.teams.neoforge)
    }
}

// Hot reload for writing guide pages (storage/docs/guide.md): in the dev client GuideME reads Storage's pages from the
// source folder and reloads a page as soon as its file is saved.
neoForge {
    runs.named("client") {
        systemProperty("guideme.maxtech_storage.guide.sources",
            file("../common/src/main/resources/assets/maxtech_storage/guides/maxtech_storage/guide").absolutePath)
    }
}

// ---- Guide website (docs/milestones/MGM/web-export.md) ------------------------------------------------------------
// Release tags publish it through .github/workflows/guide-site.yml (xvfb-run on Linux). Three steps:
//   ./gradlew :storage:neoforge:runExportGuide        a client starts, GuideME exports the guide, the game quits
//   ./gradlew :storage:neoforge:createGuideWebsite    GuideME's generator turns the export into a static site
//   ./gradlew :storage:neoforge:serveGuideWebsite     serves the site on http://localhost:8000 until stopped
// The export needs a client with an OpenGL context (it renders item icons and scenes). GuideME 26.1.15-beta has no
// title, logo or favicon arguments and calls every guide "Applied Energistics 2", so the site uses our copy of its page
// template (guide-web/templates/layout.html) with Storage's name and logo from docs/branding.
val guideExportDir = layout.buildDirectory.dir("exportedGuide")
val guideWebsiteDir = layout.buildDirectory.dir("guideWebsite")
// GuideME writes icons and scene pictures as WebP through FFmpeg (bytedeco), which it does not ship. MDG 2.0.147 has no
// per-run classpath, so FFmpeg (this machine's natives only) joins the runtime classpath only when the export runs;
// never the jar or other runs. The version is the one GuideME 26.1.15-beta builds against.
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
neoForge {
    runs.register("exportGuide") {
        client()
        systemProperty("guideme.exportOnStartupAndExit", "maxtech_storage:guide")
        systemProperty("guideme.exportDestination.maxtech_storage.guide", guideExportDir.get().asFile.absolutePath)
        systemProperty("guideme.exportModVersion.maxtech_storage.guide", project.version.toString())
    }
}
tasks.matching { it.name == "runExportGuide" }.configureEach {
    outputs.dir(guideExportDir)
    // The export is the game's output: always run it, never restore it from a cache.
    outputs.upToDateWhen { false }
    val dir = guideExportDir.get().asFile
    doFirst { dir.deleteRecursively() }
}
// The generator's --web-assets: our page template, plus the logo and favicon.
val guideWebAssets = tasks.register<Sync>("guideWebAssets") {
    into(layout.buildDirectory.dir("guideWebAssets"))
    from("guide-web")
    from(rootProject.file("docs/branding/maxtech_storage_icon_256.png")) {
        into("assets")
        rename { "maxtech_storage_logo.png" }
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
    val javaHome = javaToolchains.launcherFor(java.toolchain).map { it.metadata.installationPath }
    val windows = System.getProperty("os.name").lowercase().contains("win")
    executable = javaHome.get().file(if (windows) "bin/jwebserver.exe" else "bin/jwebserver").asFile.absolutePath
    args("-d", guideWebsiteDir.get().asFile.absolutePath, "-p", "8000")
}
// ---- end guide website ---------------------------------------------------------------------------------------------
