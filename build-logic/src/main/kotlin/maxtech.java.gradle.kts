import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
    id("net.ltgt.errorprone")
    id("com.diffplug.spotless")
}

val libs = the<VersionCatalogsExtension>().named("libs")

// Per mod, because every mod has projects named "common" and "neoforge"; a shared group would make Gradle treat
// :core:common and :machines:common as the same module and substitute one for the other.
group = "dev.silencebound.maxtech.${project.parent?.name ?: "root"}"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
}

dependencies {
    "errorprone"(libs.findLibrary("errorprone-core").get())
    "errorprone"(libs.findLibrary("nullaway").get())
    // JSpecify comes with Minecraft (strictly pinned by minecraft-dependencies), so it is not declared here.
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly(libs.findLibrary("junit-launcher").get())
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Error Prone warnings fail the build too (CLAUDE.md, Static analysis). This includes javac's default [removal]
    // lint, deliberately: an API marked forRemoval gets replaced when we notice, not at the next version bump.
    // EnumOrdinal stays on as well; index per-side arrays with Direction.get3DDataValue(), not ordinal().
    options.compilerArgs.add("-Werror")
    options.errorprone {
        disableWarningsInGeneratedCode = true
        excludedPaths = ".*/build/generated/.*"
        check("NullAway", CheckSeverity.ERROR)
        option("NullAway:AnnotatedPackages", "dev.silencebound.maxtech")
        option("NullAway:JSpecifyMode", "true")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Minecraft's logger writes logs/ into the working directory; keep that out of the source tree.
    val testWorkDir = layout.buildDirectory.dir("test-work")
    workingDir(testWorkDir)
    doFirst {
        testWorkDir.get().asFile.mkdirs()
    }
}

spotless {
    java {
        target("src/*/java/**/*.java")
        eclipse().configFile(rootProject.file("config/spotless/eclipse-formatter.prefs"))
        trimTrailingWhitespace()
        endWithNewline()
    }
}
