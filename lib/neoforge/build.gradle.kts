plugins {
    id("maxtech.neoforge")
}

neoForge {
    // JUnit for the Jade-free parts of compat code (BlockInfoLines) touches Minecraft types.
    addModdingDependenciesTo(sourceSets.test.get())
}

dependencies {
    // GuideME draws every MaxTech mod's guide (lib.neoforge.api.guide.Guides) and is required. It is bundled in Lib's
    // jar (NeoForge jar-in-jar), so players install nothing extra; when another mod (AE2) brings a newer GuideME, FML
    // loads only the newest one. The range is GuideME's 26.1 line, the versions that support 26.1.2. Mods above Lib get
    // it on their dev runtime classpath from maxtech.neoforge.
    implementation(libs.guideme)
    jarJar(libs.guideme) {
        version {
            strictly("[26.1.15-beta,26.2-)")
            prefer(libs.versions.guideme.get())
        }
    }
    // KubeJS is optional (compat.kubejs, shared schema helpers); compile against it only, never bundled.
    compileOnly(libs.kubejs)
    compileOnly(libs.rhino)
    // Jade is optional too (compat.jade) and has no separate API artifact, so the mod jar is compiled against and joins
    // the dev runs the same way.
    compileOnly(libs.jade.neoforge)
    runtimeOnly(libs.jade.neoforge)
    // FTB Teams is optional (compat.ftbteams) and All Rights Reserved: compiled against only, never bundled. Not
    // transitive, since only its API classes are used; it stays out of dev runs because it needs FTB Library.
    compileOnly(libs.ftb.teams.neoforge) { isTransitive = false }
    // main sees common only as compileOnly (it loads through fml.modFolders); unit tests need it on their classpath.
    testImplementation(project(":lib:common"))
}
