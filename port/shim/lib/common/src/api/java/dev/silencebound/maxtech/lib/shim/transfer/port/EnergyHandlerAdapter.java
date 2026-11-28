/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
/*
 * Modified for MaxTech Lib's 1.21.1 build (port/NOTICE-transfer.md): vendored from NeoForge 26.1.2's
 * net.neoforged.neoforge.energy.EnergyHandlerAdapter, package relocated to
 * dev.silencebound.maxtech.lib.shim.transfer.port, made public, adapted to NeoForge 21.1's IEnergyStorage.
 */

package dev.silencebound.maxtech.lib.shim.transfer.port;

import dev.silencebound.maxtech.lib.shim.transfer.energy.EnergyHandler;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.Transaction;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * Presents an {@link EnergyHandler} as a legacy {@link IEnergyStorage}. Every call opens its own root transaction, so
 * this wrapper cannot be used from inside a transaction.
 */
public class EnergyHandlerAdapter implements IEnergyStorage {
    private final EnergyHandler handler;

    public EnergyHandlerAdapter(EnergyHandler handler) {
        this.handler = handler;
    }

    /** {@return the wrapped handler} */
    public EnergyHandler handler() {
        return handler;
    }

    @Override
    public int receiveEnergy(int toReceive, boolean simulate) {
        try (var tx = Transaction.openRoot()) {
            int inserted = handler.insert(toReceive, tx);
            if (!simulate) tx.commit();
            return inserted;
        }
    }

    @Override
    public int extractEnergy(int toExtract, boolean simulate) {
        try (var tx = Transaction.openRoot()) {
            int extracted = handler.extract(toExtract, tx);
            if (!simulate) tx.commit();
            return extracted;
        }
    }

    @Override
    public int getEnergyStored() {
        return handler.getAmountAsInt();
    }

    @Override
    public int getMaxEnergyStored() {
        return handler.getCapacityAsInt();
    }

    @Override
    public boolean canExtract() {
        // Make a best guess here
        return handler.getCapacityAsLong() > 0;
    }

    @Override
    public boolean canReceive() {
        // Make a best guess here
        return handler.getCapacityAsLong() > 0;
    }
}
