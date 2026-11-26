package dev.silencebound.maxtech.gradle

import net.neoforged.moddevgradle.dsl.ModModel
import net.neoforged.moddevgradle.dsl.NeoForgeExtension
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.getByType
import javax.inject.Inject

/**
 * `maxtech { }` in a `neoforge` project. Adds other mods of this repo to this project's dev runs; each mod is
 * its `neoforge` main source set plus its `common` main and api source sets, grouped as one mod folder set.
 *
 * MDG's `mods {}` takes `SourceSet` objects, so this reads other projects' source sets after
 * `evaluationDependsOn`. That is configuration-cache safe but rules out Gradle's Isolated Projects mode; revisit if
 * MDG gains a way to reference mod folders across projects without it.
 */
abstract class MaxtechNeoForgeExtension @Inject constructor(private val project: Project) {

    /** Loads the mod `<modGroupPath>` (e.g. `:lib`) in this project's runs. */
    fun loadMod(modGroupPath: String) {
        val classesTasks = registerMod(modGroupPath).second
        project.extensions.getByType<NeoForgeExtension>().runs.configureEach {
            classesTasks.forEach { taskBefore(it) }
        }
    }

    /**
     * Adds a `suiteClient` run ("Suite Client" in the IDE) that also loads the mods in [modGroupPaths]. All other runs
     * keep loading only the mods from [loadMod], so they still show this mod working without the extra ones.
     */
    fun suiteClient(vararg modGroupPaths: String) {
        val neoForge = project.extensions.getByType<NeoForgeExtension>()
        val extras = modGroupPaths.map { registerMod(it) }
        val extraModIds = extras.map { it.first.name }.toSet()
        neoForge.runs.configureEach {
            if (name != SUITE_CLIENT) {
                loadedMods.set(this@MaxtechNeoForgeExtension.project.provider { neoForge.mods.filter { it.name !in extraModIds } })
            }
        }
        neoForge.runs.register(SUITE_CLIENT) {
            client()
            ideName.set("Suite Client")
            extras.forEach { (_, classesTasks) -> classesTasks.forEach { taskBefore(it) } }
        }
    }

    /**
     * Adds the mod to this project's `mods {}` and returns it with the tasks that build it. Another project's mod is
     * only a mod folder here, not a compile dependency, so an IDE run config (which builds just this module) would
     * launch its stale classes unless the runs that load it list these tasks in `taskBefore`. Gradle runs already
     * compile it. For this project's own mod only the `common` tasks: building this module compiles `common` as a
     * compileOnly dependency, which yields its classes but never runs its `processResources`, so an IDE run would miss
     * `common`'s resources (its preset JSON, which config construction needs) or load a stale copy of them.
     */
    private fun registerMod(modGroupPath: String): Pair<ModModel, List<TaskProvider<Task>>> {
        val neoforgePath = "$modGroupPath:neoforge"
        val neoforge = if (neoforgePath == project.path) project else project.evaluationDependsOn(neoforgePath)
        val common = commonProject(modGroupPath)
        val modId = modIdFor(neoforge.modGroupName)
        val mod = project.extensions.getByType<NeoForgeExtension>().mods.maybeCreate(modId).apply {
            sourceSet(neoforge.extensions.getByType<SourceSetContainer>().getByName(SourceSet.MAIN_SOURCE_SET_NAME))
            sourceSetsOf(common).forEach { sourceSet(it) }
        }
        val commonTasks = listOf(common.tasks.named("classes"), common.tasks.named("apiClasses"))
        val classesTasks = if (neoforge == project) commonTasks else listOf(neoforge.tasks.named("classes")) + commonTasks
        return mod to classesTasks
    }

    internal fun commonProject(modGroupPath: String): Project = project.evaluationDependsOn("$modGroupPath:common")

    private companion object {
        const val SUITE_CLIENT = "suiteClient"
    }

    internal fun sourceSetsOf(common: Project): List<SourceSet> {
        val sourceSets = common.extensions.getByType<SourceSetContainer>()
        return listOf(sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME), sourceSets.getByName("api"))
    }
}
