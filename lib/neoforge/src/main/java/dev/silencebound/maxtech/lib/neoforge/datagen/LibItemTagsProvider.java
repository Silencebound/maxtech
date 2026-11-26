package dev.silencebound.maxtech.lib.neoforge.datagen;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.tool.ToolTags;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.ItemTagsProvider;

/**
 * The tool tag Lib's wrench checks read (PRD 5.5). The wrenches themselves are content mods', which put them into
 * {@code c:tools/wrench} or {@code maxtech_lib:acts_as/wrench}.
 */
final class LibItemTagsProvider extends ItemTagsProvider {
    LibItemTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries, MaxtechLib.MOD_ID);
    }

    @Override
    protected void addTags(HolderLookup.Provider registries) {
        // In-world wrench actions check this; every wrench acts, and other items may join.
        tag(ToolTags.ACTS_AS_WRENCH).addTag(ToolTags.WRENCH);
    }
}
