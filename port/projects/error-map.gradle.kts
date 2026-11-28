// The error map (port/README.md): tools/errormap.sh runs ONE javac over every Lib source set at once, so an error in a
// lower source set (common api) does not hide the ones above it (neoforge gametest). This task, applied to both Lib
// projects, only writes that javac's inputs to build/errorMap: this project's source directories and the external jars
// on its classpaths. javac runs outside Gradle, which keeps thousands of diagnostics out of the daemon's heap.
val sets = project.the<SourceSetContainer>()
// This build's own jars (build/libs) are left out; its Minecraft artifacts (build/moddev) are needed.
val portProjects = rootProject.file("projects")

tasks.register("errorMapInputs") {
    group = "port"
    description = "Writes this project's javac inputs for tools/errormap.sh."
    dependsOn(tasks.named("createMinecraftArtifacts"))
    val dir = layout.buildDirectory.dir("errorMap").get().asFile
    val javac = project.the<JavaToolchainService>().compilerFor { languageVersion = JavaLanguageVersion.of(21) }
        .map { it.executablePath.asFile.absolutePath }
    outputs.upToDateWhen { false }
    doLast {
        dir.mkdirs()
        // Listing the files does not run the producing tasks (the source sets' own compiles, which fail while errors
        // remain); this project's and the other Lib project's outputs are left out, since their sources are compiled.
        val jars = sets.flatMap { it.compileClasspath.files }
            .filter { it.isFile && it.name.endsWith(".jar") }
            .filter { !(it.startsWith(portProjects) && it.parentFile.name == "libs") }
            .distinct()
        dir.resolve("classpath.txt").writeText(jars.joinToString("\n") + "\n")
        val java = sets.flatMap { it.java.srcDirs }.filter { it.isDirectory }
            .flatMap { d -> d.walkTopDown().filter { it.isFile && it.name.endsWith(".java") }.toList() }
        dir.resolve("sources.txt").writeText(java.joinToString("\n") { it.absolutePath } + "\n")
        dir.resolve("javac.txt").writeText(javac.get())
    }
}
