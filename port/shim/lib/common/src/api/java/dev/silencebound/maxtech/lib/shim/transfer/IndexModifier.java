/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
/*
 * Modified for MaxTech Lib's 1.21.1 build (port/NOTICE-transfer.md): vendored from NeoForge 26.1.2, package
 * relocated to dev.silencebound.maxtech.lib.shim.transfer, adapted to Minecraft 1.21.1.
 */

package dev.silencebound.maxtech.lib.shim.transfer;

import dev.silencebound.maxtech.lib.shim.transfer.resource.Resource;

/**
 * Represents a function to directly mutate the resource and amount at a specific index of a {@link ResourceHandler}.
 */
@FunctionalInterface
public interface IndexModifier<T extends Resource> {
    /**
     * Overrides the resource and amount at the given index.
     *
     * @param index    The index to set the resource at.
     * @param resource The resource to set.
     * @param amount   The amount of the resource to set.
     */
    void set(int index, T resource, int amount);
}
