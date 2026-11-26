package dev.silencebound.maxtech.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Warns about `net.neoforged` references in a `common` project (PRD 4.1, ADR-0002). In [sources], a reference is
 * accepted, and only counted, when it is into one of the [ALLOWED] packages and carries a `PLATFORM-LEAK:` marker,
 * either on the same line or on a comment-only line directly above. Every reference is judged on its own, so one
 * allowed reference does not cover another on the same line. In [apiSources] no reference is accepted: the api jars
 * stay loader-neutral.
 *
 * Text matching, not parsing: a `net.neoforged.` inside a string literal is reported as well.
 *
 * Deliberately a warning, not a failure, and deliberately without outputs so the warnings are
 * printed on every build instead of disappearing behind an up-to-date check.
 */
abstract class CheckCommonImports : DefaultTask() {
    @get:InputFiles
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:InputFiles
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apiSources: ConfigurableFileCollection

    @get:Internal
    abstract val projectDir: DirectoryProperty

    init {
        group = "verification"
        description = "Warns about net.neoforged references in common source sets."
        outputs.upToDateWhen { false }
    }

    private var accepted = 0
    private var violations = 0

    @TaskAction
    fun check() {
        val root = projectDir.get().asFile
        accepted = 0
        violations = 0
        scan(sources.files, root, api = false)
        scan(apiSources.files, root, api = true)
        if (violations > 0 || accepted > 0) {
            logger.warn("$path: $violations unmarked or disallowed and $accepted accepted net.neoforged reference(s)")
        }
    }

    private fun scan(files: Set<File>, root: File, api: Boolean) {
        for (file in files.filter { it.name.endsWith(".java") }.sorted()) {
            val lines = file.readLines()
            for ((index, line) in lines.withIndex()) {
                val code = line.trim()
                if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) continue
                val references = REFERENCE.findAll(code).map { it.value }.toList()
                if (references.isEmpty()) continue
                val previous = if (index > 0) lines[index - 1].trim() else ""
                val marked = line.contains(MARKER) || (previous.startsWith("//") && previous.contains(MARKER))
                val location = "${file.relativeTo(root)}:${index + 1}"
                for (reference in references) {
                    val allowed = ALLOWED.any { reference.startsWith(it) }
                    when {
                        api -> warn("$location: $reference in an api source set; api packages never expose NeoForge types")
                        allowed && marked -> accepted++
                        allowed -> warn("$location: $reference in common without a // $MARKER comment")
                        else -> warn("$location: $reference in common is outside the allowed packages; route it through Platform")
                    }
                }
            }
        }
    }

    private fun warn(message: String) {
        violations++
        logger.warn("w: $message")
    }

    private companion object {
        const val MARKER = "PLATFORM-LEAK:"
        val REFERENCE = Regex("""\bnet\.neoforged\.[\w.]*""")

        /** The packages common may use directly (ADR-0002); everything else goes through Platform. */
        val ALLOWED = listOf(
            "net.neoforged.neoforge.transfer.",
            "net.neoforged.neoforge.fluids.",
            "net.neoforged.neoforge.capabilities.",
            // A block entity can only hand render data to its model by overriding getModelData() (ADR-0002).
            "net.neoforged.neoforge.model.data.",
        )
    }
}
