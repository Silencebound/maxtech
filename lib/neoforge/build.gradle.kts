plugins {
    id("maxtech.neoforge")
}

neoForge {
    // JUnit for the Jade-free parts of compat code (BlockInfoLines) touches Minecraft types.
    addModdingDependenciesTo(sourceSets.test.get())
}

dependencies {
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
