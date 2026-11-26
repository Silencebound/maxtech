package dev.silencebound.maxtech.storage.neoforge.datagen;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.neoforge.ResearchNotesLootModifier;
import dev.silencebound.maxtech.storage.research.source.SourceContent;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.KeyTagProvider;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.VillagerTradeTags;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.TradeCost;
import net.minecraft.world.item.trading.VillagerTrade;
import net.neoforged.neoforge.common.data.GlobalLootModifierProvider;

/**
 * Research source data: the librarian's research notes trade (a {@code villager_trade} entry plus the tag that puts it
 * into the level-3 librarian trade set) and the research notes loot modifier. The trade's price is data, not config:
 * villager trades are a datapack registry, which a pack overrides like any trade.
 */
public final class ResearchSourceDatagen {
    /** The librarian's trade: 5 emeralds for one research note, 4 uses, at the journeyman level. */
    public static final ResourceKey<VillagerTrade> NOTES_TRADE = ResourceKey.create(Registries.VILLAGER_TRADE,
            MaxtechStorage.id("librarian/3/emerald_research_notes"));

    private ResearchSourceDatagen() {
    }

    /** Adds the trade to {@code builder}. */
    static RegistrySetBuilder add(RegistrySetBuilder builder) {
        return builder.add(Registries.VILLAGER_TRADE, ResearchSourceDatagen::trades);
    }

    private static void trades(BootstrapContext<VillagerTrade> context) {
        context.register(NOTES_TRADE, new VillagerTrade(new TradeCost(Items.EMERALD, 5),
                new ItemStackTemplate(SourceContent.RESEARCH_NOTES.get()), 4, 10, 0.05F, Optional.empty(), List.of()));
    }

    /** {@code data/minecraft/tags/villager_trade/librarian/level_3.json}, added to vanilla's (not replacing it). */
    static final class TradeTags extends KeyTagProvider<VillagerTrade> {
        TradeTags(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
            super(output, Registries.VILLAGER_TRADE, registries, MaxtechStorage.MOD_ID);
        }

        @Override
        protected void addTags(HolderLookup.Provider registries) {
            tag(VillagerTradeTags.LIBRARIAN_LEVEL_3).add(NOTES_TRADE);
        }
    }

    /** {@code data/maxtech_storage/loot_modifiers/research_notes.json}. */
    static final class LootModifiers extends GlobalLootModifierProvider {
        LootModifiers(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
            super(output, registries, MaxtechStorage.MOD_ID);
        }

        @Override
        protected void start() {
            add("research_notes", ResearchNotesLootModifier.create());
        }

        /** The research data's loot modifier provider has the default name for this mod. */
        @Override
        public String getName() {
            return super.getName() + " (research sources)";
        }
    }
}
