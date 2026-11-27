package dev.silencebound.maxtech.gradle

import org.gradle.api.Project

/** Mods are laid out as `:<mod>:common` and `:<mod>:neoforge`; core is the only mod whose ID has no suffix. */
val Project.modGroupName: String
    get() = requireNotNull(parent) { "$path is not a mod subproject" }.name

val Project.maxtechModId: String
    get() = modIdFor(modGroupName)

fun modIdFor(modGroupName: String): String = if (modGroupName == "core") "maxtech" else "maxtech_$modGroupName"

/** Runs live under the root so `git clean` of one place resets all of them; the root is per worktree. */
fun Project.runDirectory(runName: String) =
    rootProject.layout.projectDirectory.dir("runs/${path.removePrefix(":").replace(':', '-')}/$runName")

/**
 * Mods that build on Lib's public api only, like a third-party addon (Farms, FM1): they compile against the
 * `maxtech_lib-api` jar and never see Lib's internals. Storage still uses Lib's internals and pins one Lib minor.
 */
private val libApiOnlyMods = setOf("farms")

val Project.usesLibApiOnly: Boolean
    get() = modGroupName in libApiOnlyMods
