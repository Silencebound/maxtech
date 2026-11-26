package dev.silencebound.maxtech.lib.energy;

import dev.silencebound.maxtech.lib.platform.BlockLookup;
import dev.silencebound.maxtech.lib.platform.Platform;
import net.minecraft.resources.Identifier;
// PLATFORM-LEAK: FE is NeoForge's energy transfer type; Storage runs on it (PRD 7.2), the converters bridge it (5.1).
import net.neoforged.neoforge.transfer.energy.EnergyHandler;

/**
 * NeoForge's FE block capability ({@code Capabilities.Energy.BLOCK}) as one of our lookups; {@link FeTransfer} moves FE
 * between handlers. {@code createSided} is create-or-get, so the name and type below return NeoForge's own capability
 * without importing its {@code Capabilities} class. Its users are the mods that run on FE, such as Storage. Server
 * thread only.
 */
public final class FeLookups {
    /** The sided FE handler of a block; {@code neoforge:energy_handler}. */
    public static final BlockLookup<EnergyHandler> BLOCK = Platform.get().capabilities()
            .sided(Identifier.fromNamespaceAndPath("neoforge", "energy_handler"), EnergyHandler.class);

    private FeLookups() {
    }
}
