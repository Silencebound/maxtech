/*
 * SPDX-License-Identifier: LGPL-2.1-only
 */
/*
 * Added for MaxTech Lib's 1.21.1 build (port/NOTICE-transfer.md): stand-in for Minecraft 26.1's
 * net.minecraft.core.TypedInstance with NeoForge's TypedInstanceExtension, which 1.21.1 does not have.
 */

package dev.silencebound.maxtech.lib.shim.transfer.port;

import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.IWithData;
import org.jspecify.annotations.Nullable;

/**
 * An object backed by a registry entry, such as a resource. Same methods as 26.1's {@code TypedInstance}.
 *
 * @param <T> the registry entry type
 */
public interface TypedInstance<T> extends IWithData<T> {
    /** {@return the holder of the backing registry entry} */
    Holder<T> typeHolder();

    default Stream<TagKey<T>> tags() {
        return this.typeHolder().tags();
    }

    default boolean is(TagKey<T> tag) {
        return this.typeHolder().is(tag);
    }

    default boolean is(HolderSet<T> set) {
        return set.contains(this.typeHolder());
    }

    default boolean is(T rawType) {
        return this.typeHolder().value() == rawType;
    }

    default boolean is(Holder<T> type) {
        // Compare values so custom holders such as DeferredHolders match, as in NeoForge 26.1.
        return is(type.value());
    }

    default boolean is(ResourceKey<T> type) {
        return this.typeHolder().is(type);
    }

    @Override
    default <D> @Nullable D getData(DataMapType<T, D> type) {
        return this.typeHolder().getData(type);
    }
}
