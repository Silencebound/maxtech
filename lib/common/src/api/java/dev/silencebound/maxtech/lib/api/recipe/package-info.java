/**
 * Machine recipes (ADR-0019): {@link dev.silencebound.maxtech.lib.api.recipe.MachineRecipeType#define} gives a machine
 * a recipe type with codecs, checked against its {@link dev.silencebound.maxtech.lib.api.recipe.MachineRecipeShape};
 * recipes take items ({@link dev.silencebound.maxtech.lib.api.recipe.ItemInput}) and resources of any kind
 * ({@link dev.silencebound.maxtech.lib.api.recipe.KindInput}), and make items with a chance and resources. Lib's recipe
 * viewer layout and KubeJS recipe keys share the item inputs and outputs. Experimental; values are immutable and safe
 * from any thread unless a type says otherwise.
 */
@NullMarked
package dev.silencebound.maxtech.lib.api.recipe;

import org.jspecify.annotations.NullMarked;
