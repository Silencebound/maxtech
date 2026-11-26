package dev.silencebound.maxtech.storage.controller.card;

import dev.silencebound.maxtech.storage.controller.module.ModuleKind;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The kinds of controller upgrade card (PRD 7.2, Controller; M7R item 2). Each replaces the expansion block of the same
 * {@link ModuleKind}, whose index it keeps in a count array, and needs the research that block needed to install.
 */
public enum CardKind {
    /** {@code controller.cards.batchAmplifier.batchPercent} more items or mB per device operation, per card. */
    BATCH_AMPLIFIER(ModuleKind.BATCH_AMPLIFIER),
    /** {@code controller.cards.efficiencyCore.percent} less FE for each tick's operations, per card. */
    EFFICIENCY_CORE(ModuleKind.EFFICIENCY_CORE),
    /** Automation waits below {@code controller.cards.surgeRegulator.reservePercent} of the network's FE. */
    SURGE_REGULATOR(ModuleKind.SURGE_REGULATOR);

    private static final CardKind[] VALUES = values();

    private final ModuleKind module;

    CardKind(ModuleKind module) {
        this.module = module;
    }

    /** The expansion kind this card replaced; its index counts this card in a count array. */
    public ModuleKind module() {
        return module;
    }

    /** The research unlock a player's team needs to install the card, or null for none (the batch amplifier). */
    public @Nullable Identifier unlock() {
        return module.unlock();
    }

    /** The card kind that replaced the expansion kind {@code module}, or null when it is no card. */
    public static @Nullable CardKind of(ModuleKind module) {
        for (CardKind kind : VALUES) {
            if (kind.module == module) {
                return kind;
            }
        }
        return null;
    }
}
