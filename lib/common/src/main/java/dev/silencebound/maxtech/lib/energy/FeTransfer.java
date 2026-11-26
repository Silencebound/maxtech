package dev.silencebound.maxtech.lib.energy;

// PLATFORM-LEAK: FE is NeoForge's energy transfer type (PRD 7.2).
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.energy.EnergyHandlerUtil;

/** FE moves between two handlers, such as a buffer and a neighbour found through {@link FeLookups}. */
public final class FeTransfer {
    private FeTransfer() {
    }

    /**
     * Moves up to {@code amount} FE from {@code from} to {@code to} in its own root transaction and returns what moved:
     * never more than {@code from} gives and {@code to} takes. A pull is the same call with the neighbour as
     * {@code from}. Amounts above {@link Integer#MAX_VALUE} move that much; 0 or less moves nothing. Server thread,
     * with no transaction open on it.
     */
    public static long move(EnergyHandler from, EnergyHandler to, long amount) {
        if (amount <= 0) {
            return 0;
        }
        return EnergyHandlerUtil.move(from, to, (int) Math.min(Integer.MAX_VALUE, amount), null);
    }
}
