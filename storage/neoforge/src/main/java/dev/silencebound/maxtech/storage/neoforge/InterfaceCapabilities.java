package dev.silencebound.maxtech.storage.neoforge;

import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.StorageCableBlock;
import dev.silencebound.maxtech.storage.iface.InterfaceContent;
import dev.silencebound.maxtech.storage.iface.InterfacePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.jspecify.annotations.Nullable;

/**
 * The interface's item and fluid handlers: the block offers them on every side; a storage cable offers an interface
 * part's on the face the part sits on, so only the block beyond that face reaches it.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class InterfaceCapabilities {
    private InterfaceCapabilities() {
    }

    @SubscribeEvent
    static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Item.BLOCK, InterfaceContent.BLOCK_ENTITY.get(),
                (entity, side) -> entity.worker().itemHandler());
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, InterfaceContent.BLOCK_ENTITY.get(),
                (entity, side) -> entity.worker().fluidHandler());
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
            InterfacePart part = part(entity, side);
            return part == null ? null : part.worker().itemHandler();
        }, blocks);
        event.registerBlock(Capabilities.Fluid.BLOCK, (level, pos, state, entity, side) -> {
            InterfacePart part = part(entity, side);
            return part == null ? null : part.worker().fluidHandler();
        }, blocks);
    }

    private static @Nullable InterfacePart part(@Nullable BlockEntity entity, @Nullable Direction side) {
        if (side != null && entity instanceof ConduitBlockEntity cable
                && cable.conduit().state() instanceof CableParts parts && parts.part(side) instanceof InterfacePart part
                && !part.isRemoved()) {
            return part;
        }
        return null;
    }
}
