/**
 * Curios compat (optional, LGPL-3.0-or-later; compiled against only, never bundled): Storage's carried gadgets go into
 * Curios slots and keep working from there. {@code MaxtechStorageNeoForge} references these classes only behind
 * {@code ModList.isLoaded("curios")}; the slot tags and the player's slot assignment are plain data
 * ({@link dev.silencebound.maxtech.storage.neoforge.compat.curios.CuriosSlots}), read only by Curios.
 */
@NullMarked
package dev.silencebound.maxtech.storage.neoforge.compat.curios;

import org.jspecify.annotations.NullMarked;
