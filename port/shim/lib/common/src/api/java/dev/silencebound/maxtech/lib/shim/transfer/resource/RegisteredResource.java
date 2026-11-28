/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
/*
 * Modified for MaxTech Lib's 1.21.1 build (port/NOTICE-transfer.md): vendored from NeoForge 26.1.2, package
 * relocated to dev.silencebound.maxtech.lib.shim.transfer, adapted to Minecraft 1.21.1.
 */

package dev.silencebound.maxtech.lib.shim.transfer.resource;

import java.util.function.Predicate;
import net.minecraft.core.Holder;
import dev.silencebound.maxtech.lib.shim.transfer.port.TypedInstance;
import org.jetbrains.annotations.ApiStatus;

/**
 * Helper interface for resources backed by a {@linkplain #value registry entry}.
 *
 * @param <T> The type of the backing registry entry.
 */
public interface RegisteredResource<T> extends Resource, TypedInstance<T> {
    /**
     * {@return the backing instance of the resource}
     */
    T value();

    /**
     * @param predicate The predicate to perform the test.
     * @return {@code true} if the predicate's test returns {@code true} for the holder from {@link #typeHolder()}.
     */
    @ApiStatus.NonExtendable
    default boolean is(Predicate<Holder<T>> predicate) {
        return predicate.test(typeHolder());
    }
}
