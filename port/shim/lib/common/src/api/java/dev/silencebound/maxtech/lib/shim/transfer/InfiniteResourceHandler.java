/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
/*
 * Modified for MaxTech Lib's 1.21.1 build (port/NOTICE-transfer.md): vendored from NeoForge 26.1.2, package
 * relocated to dev.silencebound.maxtech.lib.shim.transfer, adapted to Minecraft 1.21.1.
 */

package dev.silencebound.maxtech.lib.shim.transfer;

import java.util.Objects;
import dev.silencebound.maxtech.lib.shim.transfer.resource.Resource;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.TransactionContext;

/**
 * A {@link ResourceHandler} that allows insertion and extraction of an unlimited amount of a specified resource.
 *
 * @param <T> The type of resource that this handler can accept.
 */
public class InfiniteResourceHandler<T extends Resource> implements ResourceHandler<T> {
    protected final T infiniteResource;

    /**
     * @param infiniteResource The resource to be treated as infinite.
     */
    public InfiniteResourceHandler(T infiniteResource) {
        TransferPreconditions.checkNonEmpty(infiniteResource);
        this.infiniteResource = infiniteResource;
    }

    @Override
    public int size() {
        return 1;
    }

    @Override
    public int insert(int index, T resource, int amount, TransactionContext transaction) {
        Objects.checkIndex(index, size());
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        // Accept insertion of the infinite resource only
        return resource.equals(infiniteResource) ? amount : 0;
    }

    @Override
    public int extract(int index, T resource, int amount, TransactionContext transaction) {
        Objects.checkIndex(index, size());
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        return resource.equals(infiniteResource) ? amount : 0;
    }

    @Override
    public T getResource(int index) {
        Objects.checkIndex(index, size());
        return infiniteResource;
    }

    @Override
    public long getAmountAsLong(int index) {
        Objects.checkIndex(index, size());
        return Long.MAX_VALUE;
    }

    @Override
    public long getCapacityAsLong(int index, T resource) {
        Objects.checkIndex(index, size());
        return Long.MAX_VALUE;
    }

    @Override
    public boolean isValid(int index, T resource) {
        Objects.checkIndex(index, size());
        return resource.equals(infiniteResource);
    }
}
