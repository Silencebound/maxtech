package dev.silencebound.maxtech.storage.neoforge.datagen;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.neoforge.SkipTokenLootModifier;
import dev.silencebound.maxtech.storage.research.DraftTree;
import dev.silencebound.maxtech.storage.research.ResearchNode;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.data.PackOutput;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.common.data.GlobalLootModifierProvider;

/**
 * Research data: the shipped tree ({@link DraftTree}) as datapack registry entries, and the skip token loot modifier.
 */
final class ResearchDatagen {
    private ResearchDatagen() {
    }

    /** Adds the tree to {@code builder}. */
    static RegistrySetBuilder add(RegistrySetBuilder builder) {
        return builder.add(ResearchNode.REGISTRY, ResearchDatagen::nodes);
    }

    private static void nodes(BootstrapContext<ResearchNode> context) {
        for (DraftTree.Draft draft : DraftTree.NODES) {
            context.register(ResourceKey.create(ResearchNode.REGISTRY, draft.id()), draft.node());
        }
    }

    /** {@code data/maxtech_storage/loot_modifiers/skip_token.json} and its entry in NeoForge's global list. */
    static final class LootModifiers extends GlobalLootModifierProvider {
        LootModifiers(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
            super(output, registries, MaxtechStorage.MOD_ID);
        }

        @Override
        protected void start() {
            add("skip_token", SkipTokenLootModifier.create());
        }
    }
}
