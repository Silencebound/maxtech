package dev.silencebound.maxtech.storage.material;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockModel;
import dev.silencebound.maxtech.lib.content.ModelLayer;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.DropExperienceBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;

/**
 * Ember quartz (PRD 7.2, tier 4 "Reach"): a Nether ore in netherrack whose gem dopes elite wafers and powers the speed
 * and efficiency modules. The ore smelts to the gem; the machine steps that use it are data in
 * {@code machine.MachineRecipes}.
 */
public final class EmberMaterialContent {
    private static final float ORE_XP = 0.7F;

    /** Netherrack only, as nether quartz; experience as nether quartz ore. */
    public static final BlockEntry<DropExperienceBlock, BlockItem> EMBER_QUARTZ_ORE = ModContent.CONTENT
            .block("ember_quartz_ore", p -> new DropExperienceBlock(UniformInt.of(2, 5), p))
            .properties(p -> p.mapColor(MapColor.NETHER).instrument(NoteBlockInstrument.BASEDRUM)
                    .requiresCorrectToolForDrops().strength(3.0F, 3.0F).sound(SoundType.NETHER_ORE))
            .simpleItem()
            .model(BlockModel.layeredCube(List.of(ModelLayer.plain(Identifier.withDefaultNamespace("block/netherrack")),
                    ModelLayer.plain(MaxtechStorage.id("block/ember_quartz_ore_overlay")))))
            .lang("Ember Quartz Ore")
            // Loot is the neoforge datagen's StorageOreLoot: one ember quartz with fortune, the ore with silk touch.
            .tag(BlockTags.MINEABLE_WITH_PICKAXE).tag(BlockTags.NEEDS_STONE_TOOL)
            .tag(MaterialTags.EMBER_QUARTZ_ORE_BLOCKS).tag(MaterialTags.ORE_BLOCKS).tag(MaterialTags.ORES_IN_NETHERRACK)
            .itemTag(MaterialTags.EMBER_QUARTZ_ORES).itemTag(MaterialTags.ORES_IN_NETHERRACK_ITEMS).creativeTab()
            .register();
    public static final RegistryEntry<Item, Item> EMBER_QUARTZ = ModContent.CONTENT.item("ember_quartz", Item::new)
            .model(MaterialLooks.tinted(MaterialLooks.Form.GEM, MaterialLooks.EMBER)).lang("Ember Quartz")
            .tag(MaterialTags.EMBER_QUARTZ).recipe((recipes, item) -> {
                Ingredient ores = recipes.ingredient(MaterialTags.EMBER_QUARTZ_ORES);
                MaterialContent.cook(recipes, ores, MaterialTags.EMBER_QUARTZ_ORES, item, ORE_XP,
                        "ember_quartz_from_ore");
            }).creativeTab().register();

    static {
        ModContent.CONTENT.itemTag(MaterialTags.ORES, MaterialTags.EMBER_QUARTZ_ORES);
        ModContent.CONTENT.itemTag(MaterialTags.GEMS, MaterialTags.EMBER_QUARTZ);
    }

    private EmberMaterialContent() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }
}
