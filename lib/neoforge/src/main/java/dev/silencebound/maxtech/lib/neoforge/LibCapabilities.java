package dev.silencebound.maxtech.lib.neoforge;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.info.BlockInfoProvider;
import dev.silencebound.maxtech.lib.info.ConduitInfo;
import dev.silencebound.maxtech.lib.info.InfoLookups;
import dev.silencebound.maxtech.lib.neoforge.platform.NeoForgeBlockLookup;
import dev.silencebound.maxtech.lib.registry.ModBlockEntities;
import net.minecraft.core.Direction;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.jspecify.annotations.Nullable;

/** Exposes conduit blocks through the block info capability. Content mods register their own blocks' capabilities. */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
final class LibCapabilities {
    private LibCapabilities() {
    }

    @SubscribeEvent
    static void register(RegisterCapabilitiesEvent event) {
        BlockCapability<BlockInfoProvider, @Nullable Direction> info = NeoForgeBlockLookup.of(InfoLookups.BLOCK)
                .capability();
        event.registerBlockEntity(info, ModBlockEntities.CONDUIT.get(), (blockEntity, side) -> ConduitInfo.INSTANCE);
    }
}
