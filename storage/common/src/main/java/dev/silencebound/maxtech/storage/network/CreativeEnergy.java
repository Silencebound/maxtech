package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.energy.FeBuffer;
// PLATFORM-LEAK: FE is NeoForge's energy transfer type; Storage runs on it (PRD 7.2).
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * The energy store of a network with a {@link NetworkPowerSource}: it reports far more FE than any operation costs and
 * pays every cost without changing, so it never runs out and needs no saving or journal. Takes nothing in. One instance
 * serves every network; it holds no state.
 */
final class CreativeEnergy extends FeBuffer {
    /** Large enough that no sum of operation costs reaches it, small enough that adding two stays in a long. */
    static final long LIMITLESS = Long.MAX_VALUE / 4;
    static final CreativeEnergy INSTANCE = new CreativeEnergy();

    private CreativeEnergy() {
        super(LIMITLESS, 0, 0, () -> {
        });
    }

    @Override
    public long stored() {
        return LIMITLESS;
    }

    @Override
    public boolean tryConsume(long cost, TransactionContext transaction) {
        return true;
    }

    @Override
    public boolean tryConsume(long cost) {
        return true;
    }
}
