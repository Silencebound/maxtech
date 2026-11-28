/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
/*
 * Modified for MaxTech Lib's 1.21.1 build (port/NOTICE-transfer.md): vendored from NeoForge 26.1.2, package
 * relocated to dev.silencebound.maxtech.lib.shim.transfer, adapted to Minecraft 1.21.1.
 */

package dev.silencebound.maxtech.lib.shim.transfer.item;

import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.SnapshotJournal;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.TransactionContext;
import org.jetbrains.annotations.ApiStatus;

/**
 * A deterministic random source whose internal state is transactional,
 * ensuring determinism across transactions until one is committed.
 */
@ApiStatus.Internal
public class TransactionalRandom extends SnapshotJournal<Long> {
    private long seed = RandomSupport.generateUniqueSeed();

    @Override
    protected Long createSnapshot() {
        return seed;
    }

    @Override
    protected void revertToSnapshot(Long snapshot) {
        seed = snapshot;
    }

    public double nextDouble(TransactionContext transaction) {
        updateSnapshots(transaction);
        var random = new SingleThreadedRandomSource(seed);
        double rand = random.nextDouble();
        seed = random.nextLong();
        return rand;
    }
}
