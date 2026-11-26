/**
 * KubeJS compat: recipe schemas for Storage's machine recipes, and the {@code MaxtechLogicEvents} group for logic
 * script node types, notices and rule runs ({@link LogicKubeEvents}). KubeJS is an optional dependency: only KubeJS
 * loads {@link StorageKubeJSPlugin} (through the {@code kubejs.plugins.txt} of the mod jar), and only classes in this
 * package import KubeJS. {@link StorageRecipeSchemas} imports nothing from KubeJS, so GameTests can check it without
 * KubeJS; the logic side is tested through the KubeJS-free {@code ScriptNodes} registry and {@code LogicEvents}.
 */
@NullMarked
package dev.silencebound.maxtech.storage.neoforge.compat.kubejs;

import org.jspecify.annotations.NullMarked;
