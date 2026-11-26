/**
 * Applied Energistics 2 compat (optional, LGPL-3.0; compiled against only, never bundled): the network bridge's AE2
 * side (M7b item 2). {@code MaxtechStorageNeoForge} calls
 * {@link dev.silencebound.maxtech.storage.neoforge.compat.ae2.Ae2Compat#install()} only behind
 * {@code ModList.isLoaded("ae2")}; {@link dev.silencebound.maxtech.storage.neoforge.compat.ae2.Ae2Lang} holds no AE2
 * classes, so datagen runs without AE2.
 */
@NullMarked
package dev.silencebound.maxtech.storage.neoforge.compat.ae2;

import org.jspecify.annotations.NullMarked;
