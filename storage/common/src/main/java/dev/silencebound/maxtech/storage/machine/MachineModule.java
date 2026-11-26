package dev.silencebound.maxtech.storage.machine;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.research.ResearchGates;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * What an upgrade module does in a machine's module slot (PRD 7.2: machines grow through modules instead of new tiers).
 * A module fits only the machines it names, and the slots refuse it elsewhere. A processing machine has two module
 * slots but takes one void module and one other module at most, so it runs with one of precision, speed or efficiency
 * (owner decision, M6d) plus, optionally, the void module (M6f, F7). How much a module does is server config.
 * Immutable.
 */
public enum MachineModule {
    /** The lithograph's print runs make {@code machines.modules.precisionPrintYield} times their dies. */
    PRECISION(Sets.immutableEnumSet(MachineKind.LITHOGRAPH), "print"),
    /**
     * Runs {@code machines.modules.speedBonusPercent} per cent faster and cost {@code speedEnergyPercent} per cent more
     * FE in all, in every mode.
     */
    SPEED(Sets.immutableEnumSet(MachineKind.LITHOGRAPH, MachineKind.FABRICATOR, MachineKind.ASSEMBLER), null),
    /** Runs cost {@code machines.modules.efficiencySavingPercent} per cent less FE, in every mode. */
    EFFICIENCY(Sets.immutableEnumSet(MachineKind.LITHOGRAPH, MachineKind.FABRICATOR, MachineKind.ASSEMBLER), null),
    /**
     * Does up to {@code machines.modules.voidParallel} runs at once, as far as inputs and output room allow, for
     * {@code voidEnergyPercent} per cent more FE per run, in every mode (M6f). It goes into a machine beside one of the
     * other modules, so it stacks with an ember module.
     */
    VOID(Sets.immutableEnumSet(MachineKind.LITHOGRAPH, MachineKind.FABRICATOR, MachineKind.ASSEMBLER), null);

    private static final int PERCENT = 100;

    private final ImmutableSet<MachineKind> machines;
    private final @Nullable String mode;

    MachineModule(ImmutableSet<MachineKind> machines, @Nullable String mode) {
        this.machines = machines;
        this.mode = mode;
    }

    /** The research unlock a machine's owner needs before the module goes in. */
    public Identifier research() {
        return switch (this) {
            case PRECISION -> ResearchGates.PRECISION_MODULE;
            case SPEED -> ResearchGates.SPEED_MODULE;
            case EFFICIENCY -> ResearchGates.EFFICIENCY_MODULE;
            case VOID -> ResearchGates.VOID_MODULE;
        };
    }

    /**
     * Whether this module and {@code other} may sit in one machine together: only the void module and one of the
     * others.
     */
    public boolean stacksWith(MachineModule other) {
        return (this == VOID) != (other == VOID);
    }

    /**
     * Runs a machine of {@code kind} does at once in {@code mode} with this module fitted, at most; 1 where the module
     * does nothing. Server thread only.
     */
    public int parallel(MachineKind kind, String mode) {
        return this == VOID && acts(kind, mode) ? StorageServerConfig.VOID_MODULE_PARALLEL.getAsInt() : 1;
    }

    /** Whether the module goes into {@code kind}'s module slot. */
    public boolean fits(MachineKind kind) {
        return machines.contains(kind);
    }

    /** Whether it acts on a run in {@code mode} on {@code kind}. */
    private boolean acts(MachineKind kind, String mode) {
        return fits(kind) && (this.mode == null || this.mode.equals(mode));
    }

    /**
     * How many times its outputs a run in {@code mode} on {@code kind} makes with this module fitted; 1 where the
     * module does nothing. Reads the server config, so server thread only.
     */
    public int outputMultiplier(MachineKind kind, String mode) {
        return this == PRECISION && acts(kind, mode) ? StorageServerConfig.PRECISION_PRINT_YIELD.getAsInt() : 1;
    }

    /**
     * Per cent faster a run in {@code mode} on {@code kind} goes with this module fitted; 0 where the module does not
     * change the time. Server thread only.
     */
    public int speedBonusPercent(MachineKind kind, String mode) {
        return this == SPEED && acts(kind, mode) ? StorageServerConfig.SPEED_MODULE_BONUS_PERCENT.getAsInt() : 0;
    }

    /**
     * Per cent of its plain FE a run in {@code mode} on {@code kind} costs with this module fitted: 100 where the
     * module does not change it. Server thread only.
     */
    public int energyPercent(MachineKind kind, String mode) {
        if (!acts(kind, mode)) {
            return PERCENT;
        }
        return switch (this) {
            case PRECISION -> PERCENT;
            case SPEED -> PERCENT + StorageServerConfig.SPEED_MODULE_ENERGY_PERCENT.getAsInt();
            case EFFICIENCY -> PERCENT - StorageServerConfig.EFFICIENCY_MODULE_SAVING_PERCENT.getAsInt();
            case VOID -> PERCENT + StorageServerConfig.VOID_MODULE_ENERGY_PERCENT.getAsInt();
        };
    }
}
