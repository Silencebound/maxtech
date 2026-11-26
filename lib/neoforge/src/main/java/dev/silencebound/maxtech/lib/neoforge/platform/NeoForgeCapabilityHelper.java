package dev.silencebound.maxtech.lib.neoforge.platform;

import dev.silencebound.maxtech.lib.platform.BlockLookup;
import dev.silencebound.maxtech.lib.platform.CapabilityHelper;
import dev.silencebound.maxtech.lib.platform.ItemLookup;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.ItemCapability;

final class NeoForgeCapabilityHelper implements CapabilityHelper {
    @Override
    public <T> BlockLookup<T> sided(Identifier name, Class<T> type) {
        return new NeoForgeBlockLookup<>(BlockCapability.createSided(name, type));
    }

    @Override
    public <T> ItemLookup<T> item(Identifier name, Class<T> type) {
        return new NeoForgeItemLookup<>(ItemCapability.createVoid(name, type));
    }
}
