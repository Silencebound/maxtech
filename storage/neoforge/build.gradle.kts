plugins {
    id("maxtech.neoforge")
}

dependencies {
    // GuideME (bundled in Lib's jar since Lib 0.15.0) draws Storage's guide through lib.neoforge.api.guide.Guides. The
    // guide_guideme_* GameTests parse the pages with GuideME's own parser; at runtime Lib's GuideME is there.
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
    // (-PwithAe2=true -Pgametest='maxtech_storage:ae2_*'); its POM's GuideME resolves to Lib's. Never
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

// Guide hot reload and the guide website (runExportGuide, createGuideWebsite, serveGuideWebsite) come from
// maxtech.neoforge, since Storage has guide pages; the website is published by .github/workflows/guide-site.yml.
