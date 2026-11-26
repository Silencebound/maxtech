/**
 * The network bridge (M7b): a block that mounts another mod's storage network (AE2, Refined Storage) into a Storage
 * network and exports the Storage network into it. The foreign side is reached only through
 * {@link dev.silencebound.maxtech.storage.bridge.ForeignNetwork}, which compat code supplies through
 * {@link dev.silencebound.maxtech.storage.bridge.ForeignNetworks}; nothing here knows another mod.
 */
@NullMarked
package dev.silencebound.maxtech.storage.bridge;

import org.jspecify.annotations.NullMarked;
