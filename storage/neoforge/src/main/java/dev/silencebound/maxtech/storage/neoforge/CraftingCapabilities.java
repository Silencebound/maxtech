package dev.silencebound.maxtech.storage.neoforge;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.assembler.AssemblerContent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** The crafting assembler's result buffer, extract only, on every side (M7c): providers and hoppers take from it. */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class CraftingCapabilities {
    private CraftingCapabilities() {
    }

    @SubscribeEvent
    static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Item.BLOCK, AssemblerContent.BLOCK_ENTITY.get(),
                (entity, side) -> entity.output());
    }
}
