package dev.silencebound.maxtech.lib.save;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;

/**
 * Turns saved data of one {@link SaveFormat} version into the next. Steps are pure: they read and change only the tag
 * they get and touch no world or registry state, so they can be tested with plain JUnit.
 */
@FunctionalInterface
public interface MigrationStep {
    /**
     * Migrates one saved tag by one version.
     *
     * @param kind
     *            what the tag belongs to (a block entity type or saved data id), for steps that change only some kinds
     * @param tag
     *            a private copy of the saved data in the step's source version; the step may change it in place
     * @return the data in the next version (usually {@code tag} itself); the version key is set by the caller
     */
    CompoundTag apply(Identifier kind, CompoundTag tag);
}
