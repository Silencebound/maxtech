package dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy;

/**
 * {@link LegacyDirect} for energy, which has no resource: FE moves on a 1.21.1 {@code IEnergyStorage} that happen at
 * once, for {@link LegacyForeignLast}. Same rules: refused while the ledger has pending changes, results clamped to
 * {@code 0..amount}.
 */
public interface LegacyDirectEnergy {
    /** {@return how much of {@code amount} FE the storage would take now, changing nothing} */
    int simulateInsertNow(int amount);

    /** {@return how much of {@code amount} FE the storage would give now, changing nothing} */
    int simulateExtractNow(int amount);

    /** Inserts for real, at once; {@return the FE the storage reports it took} */
    int insertNow(int amount);

    /** Extracts for real, at once; {@return the FE the storage reports it gave} */
    int extractNow(int amount);
}
