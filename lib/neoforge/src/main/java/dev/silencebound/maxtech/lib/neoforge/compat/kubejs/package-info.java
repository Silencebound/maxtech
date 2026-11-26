/**
 * KubeJS recipe schema helpers. KubeJS is an optional dependency: only KubeJS loads these classes (through the
 * {@code kubejs.plugins.txt} of the mod jar), and only classes in a {@code compat.kubejs} package import KubeJS or
 * Rhino. Each mod's own {@code compat.kubejs} builds its schemas from {@link MachineRecipeKeys}, whose components read
 * and write Lib's recipe codecs, so a script recipe is decoded by the same serializer as a datapack one.
 */
@NullMarked
package dev.silencebound.maxtech.lib.neoforge.compat.kubejs;

import org.jspecify.annotations.NullMarked;
