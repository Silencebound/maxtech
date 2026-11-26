package dev.silencebound.maxtech.storage.terminal;

import java.util.function.BooleanSupplier;
// PLATFORM-LEAK: a source's action cost is journaled in NeoForge transactions, like every Storage transfer (PRD 7.2).
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * Charges a terminal source for remote actions that move no items ({@link TerminalSource#payAction}): checks that it
 * can pay, runs the action outside any transaction (actions open their own) and pays only when the action went through,
 * so a refused request costs nothing. Server thread, with no transaction open.
 */
public final class RemoteCost {
    private RemoteCost() {
    }

    /** Whether {@code source} could pay one action now; tried in a transaction that is rolled back. */
    public static boolean canPay(@Nullable TerminalSource source) {
        if (source == null) {
            return false;
        }
        try (Transaction transaction = Transaction.openRoot()) {
            return source.payAction(transaction);
        }
    }

    /** Pays one action from {@code source}; false (nothing paid) when it cannot. */
    public static boolean pay(TerminalSource source) {
        try (Transaction transaction = Transaction.openRoot()) {
            if (!source.payAction(transaction)) {
                return false;
            }
            transaction.commit();
            return true;
        }
    }

    /**
     * Runs {@code action} when {@code source} can pay for it and pays once it returns true; false, without running it,
     * for a null source or one that cannot pay.
     */
    public static boolean run(@Nullable TerminalSource source, BooleanSupplier action) {
        if (source == null || !canPay(source)) {
            return false;
        }
        boolean done = action.getAsBoolean();
        if (done) {
            pay(source);
        }
        return done;
    }
}
