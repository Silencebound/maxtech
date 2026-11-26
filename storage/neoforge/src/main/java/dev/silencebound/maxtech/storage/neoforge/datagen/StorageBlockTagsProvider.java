package dev.silencebound.maxtech.storage.neoforge.datagen;

import dev.silencebound.maxtech.lib.neoforge.datagen.ContentBlockTagsProvider;
import dev.silencebound.maxtech.storage.registry.ModContent;
import dev.silencebound.maxtech.storage.world.WorldContent;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;

/**
 * Storage's block tags beyond what its content declares: the breaker's blacklist, which keeps it off blocks a farm or a
 * structure depends on (budding amethyst, spawners, vaults) and everything withers cannot break either; and the
 * placer's blacklist, empty for packs to fill.
 */
final class StorageBlockTagsProvider extends ContentBlockTagsProvider {
    StorageBlockTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries, ModContent.CONTENT);
    }

    @Override
    protected void addTags(HolderLookup.Provider registries) {
        super.addTags(registries);
        tag(WorldContent.BREAKER_BLACKLIST).addTag(BlockTags.WITHER_IMMUNE).add(Blocks.BUDDING_AMETHYST, Blocks.SPAWNER,
                Blocks.TRIAL_SPAWNER, Blocks.VAULT, Blocks.REINFORCED_DEEPSLATE);
        // Empty: written so packs can add to it.
        tag(WorldContent.PLACER_BLACKLIST);
    }
}
