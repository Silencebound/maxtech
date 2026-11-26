package dev.silencebound.maxtech.storage.neoforge;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.StorageCableBlock;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.world.NetworkChestPart;
import dev.silencebound.maxtech.storage.world.WorldContent;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * The network chest's item handler: on every side of the block, and on the face of a storage cable that carries the
 * chest as a part, so the hopper or pipe on the block beyond that face sees it. Other parts' faces answer nothing here;
 * NeoForge asks every provider of a block in turn until one answers.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class WorldCapabilities {
    private WorldCapabilities() {
    }

    @SubscribeEvent
    static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Item.BLOCK, WorldContent.NETWORK_CHEST_BLOCK_ENTITY.get(),
                (chest, side) -> chest.view().handler());
        List<Block> cables = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block instanceof StorageCableBlock) {
                cables.add(block);
            }
        }
        if (cables.isEmpty()) {
            return;
        }
        event.registerBlock(Capabilities.Item.BLOCK, (level, pos, state, blockEntity, side) -> {
            if (side == null) {
                return null;
            }
            CableParts parts = StorageCableBlock.parts(level, pos);
            return parts != null && parts.part(side) instanceof NetworkChestPart chest ? chest.view().handler() : null;
        }, cables.toArray(Block[]::new));
    }
}
