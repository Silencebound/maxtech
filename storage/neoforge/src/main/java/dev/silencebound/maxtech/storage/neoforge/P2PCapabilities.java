package dev.silencebound.maxtech.storage.neoforge;

import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.StorageCableBlock;
import dev.silencebound.maxtech.storage.p2p.P2PContent;
import dev.silencebound.maxtech.storage.p2p.P2PPart;
import dev.silencebound.maxtech.storage.part.CableParts;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.jspecify.annotations.Nullable;

/**
 * The handlers of P2P inputs: a block's on its front, a part's on the cable face it sits on. Registered at high
 * priority: NeoForge asks a block's providers in registration order and takes the first answer, and every storage
 * device block also offers its network's FE input on every side ({@code StorageCapabilities}), which must not win on an
 * energy input's front.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class P2PCapabilities {
    private P2PCapabilities() {
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Item.BLOCK, P2PContent.BLOCK_ENTITY.get(),
                (entity, side) -> entity.endpoint().itemHandler(side));
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, P2PContent.BLOCK_ENTITY.get(),
                (entity, side) -> entity.endpoint().fluidHandler(side));
        event.registerBlockEntity(Capabilities.Energy.BLOCK, P2PContent.BLOCK_ENTITY.get(),
                (entity, side) -> entity.endpoint().energyHandler(side));
        List<Block> cables = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block instanceof StorageCableBlock) {
                cables.add(block);
            }
        }
        if (cables.isEmpty()) {
            return;
        }
        Block[] blocks = cables.toArray(Block[]::new);
        event.registerBlock(Capabilities.Item.BLOCK, (level, pos, state, entity, side) -> {
            P2PPart part = part(entity, side);
            return part == null ? null : part.endpoint().itemHandler(side);
        }, blocks);
        event.registerBlock(Capabilities.Fluid.BLOCK, (level, pos, state, entity, side) -> {
            P2PPart part = part(entity, side);
            return part == null ? null : part.endpoint().fluidHandler(side);
        }, blocks);
        event.registerBlock(Capabilities.Energy.BLOCK, (level, pos, state, entity, side) -> {
            P2PPart part = part(entity, side);
            return part == null ? null : part.endpoint().energyHandler(side);
        }, blocks);
    }

    private static @Nullable P2PPart part(@Nullable BlockEntity entity, @Nullable Direction side) {
        if (side != null && entity instanceof ConduitBlockEntity cable
                && cable.conduit().state() instanceof CableParts parts && parts.part(side) instanceof P2PPart part
                && !part.isRemoved()) {
            return part;
        }
        return null;
    }
}
