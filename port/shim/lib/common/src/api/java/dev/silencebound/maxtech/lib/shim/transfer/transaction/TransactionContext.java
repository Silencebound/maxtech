/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
/*
 * Modified for MaxTech Lib's 1.21.1 build (port/NOTICE-transfer.md): vendored from NeoForge 26.1.2, package
 * relocated to dev.silencebound.maxtech.lib.shim.transfer, adapted to Minecraft 1.21.1.
 */

package dev.silencebound.maxtech.lib.shim.transfer.transaction;

/**
 * A subset of a {@link Transaction} that lets journals properly record, manage their state,
 * or open inner transactions, but does not allow them to close the transaction they are passed.
 * <p>
 * Recording changes to a transaction can be done with an implementation of {@link SnapshotJournal} and calling
 * {@link SnapshotJournal#updateSnapshots(TransactionContext) updateSnapshots(TransactionContext)} before having the state change.
 *
 * @see SnapshotJournal#updateSnapshots(TransactionContext)
 */
public sealed interface TransactionContext permits Transaction {
    /**
     * Gets the current depth of the transaction.
     * 
     * @return The depth of this transaction: 0 if it is the root and has no parent; 1 or more otherwise indicating how far away from the root the transaction is.
     * @throws IllegalStateException If this function is not called on the thread this transaction was opened in.
     */
    int depth();
}
