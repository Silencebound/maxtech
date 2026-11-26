import dev.silencebound.maxtech.gradle.CheckApiJavadoc
import dev.silencebound.maxtech.gradle.CheckCommonImports
import dev.silencebound.maxtech.gradle.maxtechModId
import dev.silencebound.maxtech.gradle.modGroupName

plugins {
    id("maxtech.java")
    id("net.neoforged.moddev")
    `maven-publish`
}

val libs = the<VersionCatalogsExtension>().named("libs")
val modId = maxtechModId

version = providers.gradleProperty("${modGroupName}_version").get()

val apiSourceSet = sourceSets.create("api")

// Compiles against NeoForge so common can use its transfer, fluid and capability types (ADR-0002). Every other
// net.neoforged reference is reported by checkCommonImports.
neoForge {
    version = libs.findVersion("neoforge").get().requiredVersion
    addModdingDependenciesTo(apiSourceSet)
    // MDG only covers main by default; unit tests in common touch Minecraft types (BlockPos, Direction ...).
    addModdingDependenciesTo(sourceSets.test.get())
}

dependencies {
    api(apiSourceSet.output)
}

// Api Javadoc (PRD 5.9, CLAUDE.md), api source set only; internals are documented where non-obvious. doclint reports
// malformed comments and broken links while compiling, and -Werror (maxtech.java) fails the build on them. Its
// "missing" group is off because it demands @param and @return on every method; checkApiJavadoc checks instead that
// every public type and member has a comment at all.
tasks.named<JavaCompile>(apiSourceSet.compileJavaTaskName) {
    options.compilerArgs.addAll(listOf("-Xdoclint:all/protected", "-Xdoclint:-missing"))
}

val checkApiJavadoc = tasks.register<CheckApiJavadoc>("checkApiJavadoc") {
    projectDir = layout.projectDirectory
    sources.from(apiSourceSet.java)
    marker = layout.buildDirectory.file("checkApiJavadoc/ok")
}

sourceSets.main {
    // neoforge runData writes here (CLAUDE.md: generated files land in common resources).
    resources.srcDir("src/generated/resources")
}

val apiJar = tasks.register<Jar>("apiJar") {
    group = "build"
    description = "Assembles the $modId-api jar."
    archiveBaseName = "$modId-api"
    from(apiSourceSet.output)
}

val apiSourcesJar = tasks.register<Jar>("apiSourcesJar") {
    group = "build"
    archiveBaseName = "$modId-api"
    archiveClassifier = "sources"
    from(apiSourceSet.allSource)
}

tasks.assemble {
    dependsOn(apiJar)
}

// Other mods compile against this, never against our internals: compileOnly(project(path, "modApi")).
configurations.consumable("modApi") {
    outgoing.artifact(apiJar)
}

publishing {
    publications.register<MavenPublication>("api") {
        // Project groups are per mod (see maxtech.java); the published API keeps one shared group.
        groupId = "dev.silencebound.maxtech"
        artifactId = "$modId-api"
        artifact(apiJar)
        artifact(apiSourcesJar)
    }
}

val checkCommonImports = tasks.register<CheckCommonImports>("checkCommonImports") {
    projectDir = layout.projectDirectory
    sources.from(sourceSets.matching { it.name != apiSourceSet.name }.map { it.java })
    apiSources.from(apiSourceSet.java)
}

tasks.check {
    dependsOn(checkCommonImports, checkApiJavadoc)
}
