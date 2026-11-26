package dev.silencebound.maxtech.storage.neoforge;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * Loader glue for the research sources: the research notes loot modifier's codec. The librarian trade is data (a
 * {@code minecraft:villager_trade} entry tagged into the librarian's level-3 trade set, written by datagen); 26.1 has
 * no villager trades event.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class ResearchSourceSetup {
    private ResearchSourceSetup() {
    }

    @SubscribeEvent
    static void register(RegisterEvent event) {
        event.<MapCodec<? extends IGlobalLootModifier>>register(
                NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, MaxtechStorage.id("research_notes"),
                () -> ResearchNotesLootModifier.CODEC);
    }
}
