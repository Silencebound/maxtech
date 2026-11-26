/**
 * Refined Storage compat (optional, MIT; compiled against only, never bundled): the network bridge reaches a Refined
 * Storage network through the RS block in front of it ({@link RsFinder}, {@link RsNetwork}) and mounts its Storage
 * network into that one ({@link RsExport}). {@code MaxtechStorageNeoForge} calls {@link RefinedStorageCompat#init()},
 * which declares the lang key and touches the RS classes only behind {@code ModList.isLoaded("refinedstorage")}.
 */
@NullMarked
package dev.silencebound.maxtech.storage.neoforge.compat.refinedstorage;

import org.jspecify.annotations.NullMarked;
