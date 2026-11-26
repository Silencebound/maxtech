package dev.silencebound.maxtech.lib.neoforge.datagen;

import dev.silencebound.maxtech.lib.content.BlockDeclaration;
import dev.silencebound.maxtech.lib.content.BlockLoot;
import dev.silencebound.maxtech.lib.content.Content;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.block.Block;

/**
 * Loot tables for the blocks a {@link Content} declared loot for, its materials' included
 * ({@link ContentDatagen#declarations}); the others get none.
 */
public final class ContentBlockLoot extends BlockLootSubProvider {
    private final Content content;

    public ContentBlockLoot(Content content, HolderLookup.Provider registries) {
        super(Set.of(), FeatureFlags.DEFAULT_FLAGS, registries);
        this.content = content;
    }

    @Override
    protected void generate() {
        for (BlockDeclaration<?> declaration : ContentDatagen.blocks(content)) {
            BlockLoot loot = declaration.loot();
            if (loot == null) {
                continue;
            }
            Block block = declaration.entry().get();
            switch (loot) {
                case BlockLoot.Self() -> dropSelf(block);
                case BlockLoot.Table(var table) -> add(block, table.get());
            }
        }
    }

    @Override
    protected Iterable<Block> getKnownBlocks() {
        List<Block> blocks = new ArrayList<>();
        for (BlockDeclaration<?> declaration : ContentDatagen.blocks(content)) {
            if (declaration.loot() != null) {
                blocks.add(declaration.entry().get());
            }
        }
        return blocks;
    }
}
