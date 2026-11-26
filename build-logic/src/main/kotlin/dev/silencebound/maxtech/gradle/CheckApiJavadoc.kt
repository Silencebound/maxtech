package dev.silencebound.maxtech.gradle

import com.sun.source.doctree.DocCommentTree
import com.sun.source.doctree.ParamTree
import com.sun.source.tree.ClassTree
import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.MethodTree
import com.sun.source.tree.Tree
import com.sun.source.tree.VariableTree
import com.sun.source.util.DocTrees
import com.sun.source.util.JavacTask
import com.sun.source.util.TreePath
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import javax.lang.model.element.Modifier as ElementModifier
import javax.tools.ToolProvider

/**
 * Fails the build when a type or member an addon can see in an api source set has no Javadoc (PRD 5.9, CLAUDE.md).
 * Checked: every package (its `package-info.java`), every public type, and in an exported type every public or
 * protected constructor, method, field and nested type, every enum constant, and every member of an interface that is
 * not private. A record's comment must name each component with `@param`. Methods annotated `@Override` inherit their
 * comment and are skipped.
 *
 * javac's doclint (`-Xdoclint`, set on the api compile task) checks that the comments are well formed and that links
 * resolve, but its `missing` group also demands `@param` and `@return` on every method, which the plain prose style of
 * this repository does not use; this task checks only that a comment is there. Parses the sources without compiling
 * them, so it needs no classpath.
 */
abstract class CheckApiJavadoc : DefaultTask() {
    @get:InputFiles
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val projectDir: DirectoryProperty

    /** An empty marker written on success, so the task is up to date while the sources are unchanged. */
    @get:OutputFile
    abstract val marker: RegularFileProperty

    init {
        group = "verification"
        description = "Checks that every public api type and member has Javadoc."
    }

    @TaskAction
    fun check() {
        val files = sources.files.filter { it.name.endsWith(".java") }.sorted()
        val problems = mutableListOf<String>()
        if (files.isNotEmpty()) {
            val compiler = ToolProvider.getSystemJavaCompiler()
                ?: throw GradleException("checkApiJavadoc needs a JDK, not a JRE, to run Gradle")
            compiler.getStandardFileManager(null, null, Charsets.UTF_8).use { fileManager ->
                val task = compiler.getTask(null, fileManager, {}, listOf("-proc:none"), null,
                    fileManager.getJavaFileObjectsFromFiles(files)) as JavacTask
                val trees = DocTrees.instance(task)
                val root = projectDir.get().asFile
                for (unit in task.parse()) {
                    Scanner(unit, trees, root, problems).scanUnit()
                }
            }
        }
        if (problems.isNotEmpty()) {
            problems.forEach { logger.error("e: $it") }
            throw GradleException("$path: ${problems.size} api element(s) without Javadoc; see the lines above")
        }
        marker.get().asFile.writeText("")
    }

    private class Scanner(
        val unit: CompilationUnitTree,
        val trees: DocTrees,
        val root: File,
        val problems: MutableList<String>,
    ) {
        private val file = File(unit.sourceFile.toUri())

        fun scanUnit() {
            if (file.name == "package-info.java") {
                val pkg = unit.packageName ?: return
                if (trees.getDocCommentTree(TreePath(TreePath(unit), unit.`package`)) == null) {
                    report(unit.`package`, "package $pkg")
                }
                return
            }
            for (type in unit.typeDecls) {
                if (type is ClassTree && ElementModifier.PUBLIC in type.modifiers.flags) {
                    scanType(TreePath(TreePath(unit), type), type)
                }
            }
        }

        private fun scanType(path: TreePath, type: ClassTree) {
            val doc = docOf(path)
            if (doc == null) {
                report(type, "type ${type.simpleName}")
            } else if (type.kind == Tree.Kind.RECORD) {
                val named = doc.blockTags.filterIsInstance<ParamTree>().filter { !it.isTypeParameter }
                    .map { it.name.name.toString() }.toSet()
                for (component in components(type)) {
                    if (component.name.toString() !in named) {
                        report(component, "record component ${type.simpleName}.${component.name} (no @param)")
                    }
                }
            }
            val inInterface = type.kind == Tree.Kind.INTERFACE || type.kind == Tree.Kind.ANNOTATION_TYPE
            for (member in type.members) {
                val memberPath = TreePath(path, member)
                val flags = when (member) {
                    is ClassTree -> member.modifiers.flags
                    is MethodTree -> member.modifiers.flags
                    is VariableTree -> member.modifiers.flags
                    else -> continue
                }
                val exported = ElementModifier.PUBLIC in flags || ElementModifier.PROTECTED in flags
                    || (inInterface && ElementModifier.PRIVATE !in flags)
                when (member) {
                    is ClassTree -> if (exported) scanType(memberPath, member)
                    is MethodTree -> if (exported && !overrides(member) && docOf(memberPath) == null) {
                        val name = if (member.name.contentEquals("<init>")) "constructor" else member.name.toString()
                        report(member, "${type.simpleName}.$name")
                    }
                    is VariableTree -> {
                        val enumConstant = type.kind == Tree.Kind.ENUM && isEnumConstant(type, member)
                        val component = type.kind == Tree.Kind.RECORD && ElementModifier.STATIC !in flags
                        if ((exported || enumConstant) && !component && docOf(memberPath) == null) {
                            report(member, "${type.simpleName}.${member.name}")
                        }
                    }
                }
            }
        }

        private fun docOf(path: TreePath): DocCommentTree? = trees.getDocCommentTree(path)

        private fun overrides(method: MethodTree): Boolean = method.modifiers.annotations.any {
            val name = it.annotationType.toString()
            name == "Override" || name == "java.lang.Override"
        }

        /** Enum constants parse as fields without modifiers whose type is the enum itself. */
        private fun isEnumConstant(type: ClassTree, field: VariableTree): Boolean =
            field.modifiers.flags.isEmpty() && field.type?.toString() == type.simpleName.toString()

        /** A record's components parse as its only instance fields. */
        private fun components(type: ClassTree): List<VariableTree> =
            type.members.filterIsInstance<VariableTree>().filter { ElementModifier.STATIC !in it.modifiers.flags }

        private fun report(tree: Tree, what: String) {
            val position = trees.sourcePositions.getStartPosition(unit, tree)
            val line = unit.lineMap.getLineNumber(position)
            problems.add("${file.relativeTo(root)}:$line: $what has no Javadoc")
        }
    }
}
