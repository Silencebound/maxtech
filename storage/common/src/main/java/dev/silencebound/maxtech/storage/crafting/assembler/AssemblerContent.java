package dev.silencebound.maxtech.storage.crafting.assembler;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockLoot;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;

/**
 * The crafting assembler (M7 C1): block, block entity type and recipe. Its id is {@code crafting_assembler}, since
 * Storage's FE machine "assembler" already has {@code assembler}.
 */
public final class AssemblerContent {
    public static final BlockEntry<CraftingAssemblerBlock, BlockItem> ASSEMBLER = ModContent.CONTENT
            .block("crafting_assembler", CraftingAssemblerBlock::new)
            // Glass on four sides (B09 item 29): neighbours keep their faces, and light reaches the item inside. The
            // model
            // comes from Storage's datagen (CrafterModels).
            .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(3.0F, 6.0F).sound(SoundType.METAL).noOcclusion())
            .simpleItem().lang("Crafting Assembler").loot(BlockLoot.SELF).tag(BlockTags.MINEABLE_WITH_PICKAXE)
            // A crafting table behind glass, run by a memory processor.
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.MISC, item).pattern("PGP").pattern("MCM")
                    .pattern("PWP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .define('G', MaterialTags.component("display_glass"))
                    .define('M', MaterialTags.component("basic_memory_processor")).define('C', Items.CRAFTING_TABLE)
                    .define('W', MaterialTags.component("insulated_copper_wire"))
                    .unlockedBy("has_basic_memory_processor",
                            recipes.has(MaterialTags.component("basic_memory_processor")))
                    .save(recipes.output()))
            .creativeTab().register();

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<CraftingAssemblerBlockEntity>> BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE)
            .register("crafting_assembler",
                    () -> new BlockEntityType<>(CraftingAssemblerBlockEntity::new, Set.of(ASSEMBLER.get())));

    private AssemblerContent() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }
}
