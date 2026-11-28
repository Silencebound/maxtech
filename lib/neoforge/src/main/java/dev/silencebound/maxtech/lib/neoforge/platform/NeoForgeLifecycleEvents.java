package dev.silencebound.maxtech.lib.neoforge.platform;

import dev.silencebound.maxtech.lib.platform.LifecycleEvents;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ChunkTicketLevelUpdatedEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

final class NeoForgeLifecycleEvents implements LifecycleEvents {
    @Override
    public void onCommonSetup(String modId, Runnable action) {
        ModContainers.eventBus(modId).addListener(FMLCommonSetupEvent.class, event -> action.run());
    }

    @Override
    public void onServerStarting(Consumer<MinecraftServer> action) {
        NeoForge.EVENT_BUS.addListener(ServerStartingEvent.class, event -> action.accept(event.getServer()));
    }

    @Override
    public void onServerStopped(Consumer<MinecraftServer> action) {
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> action.accept(event.getServer()));
    }

    @Override
    public void onServerLevelUnloaded(Consumer<ServerLevel> action) {
        NeoForge.EVENT_BUS.addListener(LevelEvent.Unload.class, event -> {
            if (event.getLevel() instanceof ServerLevel level) {
                action.accept(level);
            }
        });
    }

    @Override
    public void onServerTagsReloaded(Runnable action) {
        NeoForge.EVENT_BUS.addListener(TagsUpdatedEvent.ServerDataLoad.class, event -> action.run());
    }

    @Override
    public void onServerTickEnd(Consumer<MinecraftServer> action) {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> action.accept(event.getServer()));
    }

    @Override
    public void onServerLevelTickEnd(Consumer<ServerLevel> action) {
        NeoForge.EVENT_BUS.addListener(LevelTickEvent.Post.class, event -> {
            if (event.getLevel() instanceof ServerLevel level) {
                action.accept(level);
            }
        });
    }

    @Override
    public void onBlockChanged(BiConsumer<ServerLevel, BlockPos> action) {
        // receiveCanceled: a mod that cancels the notification does not undo the block change a multiblock must see.
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, true, BlockEvent.NeighborNotifyEvent.class, event -> {
            if (event.getLevel() instanceof ServerLevel level) {
                action.accept(level, event.getPos());
            }
        });
    }

    @Override
    public void onChunkLoaded(ChunkListener action) {
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Load.class, event -> {
            if (event.getLevel() instanceof ServerLevel level) {
                action.accept(level, event.getChunk().getPos().pack());
            }
        });
    }

    @Override
    public void onChunkLevelChanged(ChunkLevelListener action) {
        NeoForge.EVENT_BUS.addListener(ChunkTicketLevelUpdatedEvent.class, event -> action.accept(event.getLevel(),
                event.getChunkPos(), event.getOldTicketLevel(), event.getNewTicketLevel()));
    }
}
