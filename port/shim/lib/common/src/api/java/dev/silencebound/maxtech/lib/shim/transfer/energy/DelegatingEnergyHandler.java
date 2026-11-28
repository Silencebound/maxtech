/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
/*
 * Modified for MaxTech Lib's 1.21.1 build (port/NOTICE-transfer.md): vendored from NeoForge 26.1.2, package
 * relocated to dev.silencebound.maxtech.lib.shim.transfer, adapted to Minecraft 1.21.1.
 */

package dev.silencebound.maxtech.lib.shim.transfer.energy;

import java.util.Objects;
import java.util.function.Supplier;
import dev.silencebound.maxtech.lib.shim.transfer.TransferPreconditions;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.TransactionContext;

/**
 * An energy handler that delegates all calls to another handler.
 */
public class DelegatingEnergyHandler implements EnergyHandler {
    protected final Supplier<EnergyHandler> delegate;

    public DelegatingEnergyHandler(EnergyHandler delegate) {
        Objects.requireNonNull(delegate);
        this.delegate = () -> delegate;
    }

    public DelegatingEnergyHandler(Supplier<EnergyHandler> delegate) {
        this.delegate = delegate;
    }

    @Override
    public long getAmountAsLong() {
        return getDelegate().getAmountAsLong();
    }

    @Override
    public long getCapacityAsLong() {
        return getDelegate().getCapacityAsLong();
    }

    @Override
    public int insert(int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonNegative(amount);
        return getDelegate().insert(amount, transaction);
    }

    @Override
    public int extract(int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonNegative(amount);
        return getDelegate().extract(amount, transaction);
    }

    public EnergyHandler getDelegate() {
        return delegate.get();
    }
}
