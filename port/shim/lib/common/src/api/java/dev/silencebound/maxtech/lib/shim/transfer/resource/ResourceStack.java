/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
/*
 * Modified for MaxTech Lib's 1.21.1 build (port/NOTICE-transfer.md): vendored from NeoForge 26.1.2, package
 * relocated to dev.silencebound.maxtech.lib.shim.transfer, adapted to Minecraft 1.21.1.
 */

package dev.silencebound.maxtech.lib.shim.transfer.resource;

import dev.silencebound.maxtech.lib.shim.transfer.ResourceHandlerUtil;

/**
 * Creates a resource stack from a given {@link Resource resource} and {@code amount}.
 *
 * @param resource The resource to wrap the stack around.
 * @param amount   The amount of the resource the stack is holding.
 */
public record ResourceStack<T extends Resource>(T resource, int amount) {
    /**
     * Checks if the resource stack is empty, meaning that the amount is zero
     * or that the resource is {@link Resource#isEmpty() empty}.
     *
     * @return {@code true} if empty
     */
    public boolean isEmpty() {
        return ResourceHandlerUtil.isEmpty(resource(), amount());
    }

    @Override
    public String toString() {
        return amount + "x " + resource;
    }
}
