package dev.silencebound.maxtech.lib.inventory;

// PLATFORM-LEAK: the machine's own steps refuse to run inside NeoForge transfer transactions (ADR-0002).
import net.neoforged.neoforge.transfer.transaction.Transaction;

/** The check behind the inventories' direct changes, which are their own committed step. */
final class NoTransaction {
    private NoTransaction() {
    }

    /**
     * Throws {@link IllegalStateException} while a transaction is open or closing on this thread: a direct change there
     * would not roll back with it.
     */
    static void check(String what) {
        if (Transaction.getLifecycle() != Transaction.Lifecycle.NONE) {
            throw new IllegalStateException(what + " is not allowed while a transfer transaction is open");
        }
    }
}
