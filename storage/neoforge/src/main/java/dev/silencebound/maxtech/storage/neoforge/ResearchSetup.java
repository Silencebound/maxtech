package dev.silencebound.maxtech.storage.neoforge;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchNode;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * Loader glue for research: the tree's datapack registry (synced, so the research screen can draw it), the skip token
 * loot modifier's codec, advancement points, and a team check when a player logs in.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class ResearchSetup {
    private ResearchSetup() {
    }

    @SubscribeEvent
    static void registries(DataPackRegistryEvent.NewRegistry event) {
        event.dataPackRegistry(ResearchNode.REGISTRY, ResearchNode.CODEC, ResearchNode.CODEC);
    }

    @SubscribeEvent
    static void register(RegisterEvent event) {
        event.<MapCodec<? extends IGlobalLootModifier>>register(
                NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, MaxtechStorage.id("skip_token"),
                () -> SkipTokenLootModifier.CODEC);
    }

    @SubscribeEvent
    static void advancementEarned(AdvancementEvent.AdvancementEarnEvent event) {
        AdvancementHolder advancement = event.getAdvancement();
        // Recipe unlocks and other hidden bookkeeping advancements have no display and give no points.
        if (event.getEntity() instanceof ServerPlayer player && advancement.value().display().isPresent()) {
            Research.advancementEarned(player, advancement.id());
        }
    }

    @SubscribeEvent
    static void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Research.team(player, true);
        }
    }
}
