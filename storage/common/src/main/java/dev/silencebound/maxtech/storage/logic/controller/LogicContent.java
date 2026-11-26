package dev.silencebound.maxtech.storage.logic.controller;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockLoot;
import dev.silencebound.maxtech.lib.content.BlockModel;
import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.condition.ConditionContent;
import dev.silencebound.maxtech.storage.logic.controller.client.LogicToasts;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;

/**
 * The Logic Controller (M7L area B): block, item, block entity type and recipe. One tier in the prototype, craftable
 * without research; gating comes after testing. The menu type sits in {@code registry.ModMenus}, the graphs' item
 * component in {@code registry.ModDataComponents}.
 */
public final class LogicContent {
    public static final BlockEntry<LogicControllerBlock, BlockItem> CONTROLLER = ModContent.CONTENT
            .block("logic_controller", LogicControllerBlock::new)
            .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(2.0F, 6.0F).sound(SoundType.METAL)).simpleItem()
            .model(BlockModel.cubeAll(MaxtechStorage.id("block/logic_controller"))).lang("Logic Controller")
            .loot(BlockLoot.SELF).tag(BlockTags.MINEABLE_WITH_PICKAXE)
            // Logic processors work the graphs out, condition switches read the network, signal alloy cases it.
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.REDSTONE, item).pattern("PLP").pattern("SRS")
                    .pattern("PLP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .define('L', MaterialTags.component("basic_logic_processor"))
                    .define('S', ConditionContent.SWITCH_TAG).define('R', MaterialTags.REDSTONE)
                    .unlockedBy("has_condition_switch", recipes.has(ConditionContent.SWITCH_TAG))
                    .save(recipes.output()))
            .creativeTab().register();

    /**
     * The logic card (M7L2 sharing): cheap on purpose, since a card only carries a rule. Paper to write on, redstone
     * and an iron nugget for the chip; two per craft.
     */
    public static final RegistryEntry<Item, LogicCardItem> CARD = ModContent.CONTENT
            .item("logic_card", LogicCardItem::new).model(ItemModel.flat(MaxtechStorage.id("item/logic_card")))
            .lang("Logic Card")
            .recipe((recipes, item) -> recipes.shapeless(RecipeCategory.REDSTONE, item, 2).requires(Items.PAPER)
                    .requires(MaterialTags.REDSTONE).requires(MaterialTags.IRON_NUGGET)
                    .unlockedBy("has_logic_controller", recipes.has(CONTROLLER.get())).save(recipes.output()))
            .creativeTab().register();

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<LogicControllerBlockEntity>> BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE)
            .register("logic_controller",
                    () -> new BlockEntityType<>(LogicControllerBlockEntity::new, Set.of(CONTROLLER.get())));

    private LogicContent() {
    }

    /**
     * Loads the class during mod construction so the entries above are queued, with the lang lines and the budget, and
     * registers the notice toast's, the node editor's and the sharing packets.
     */
    public static void init() {
        ControllerTexts.init();
        LogicCardItem.init();
        TeamBudget.init();
        SkyWatch.init();
        CraftWatch.init();
        LogicEditing.init();
        GraphSharing.init();
        ControllerLog.register();
        CardPayloads.register();
        RuleHistory.register();
        PinSparks.register();
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, LogicNoticePayload.TYPE,
                LogicNoticePayload.STREAM_CODEC, (payload, player) -> LogicToasts.show(payload));
    }
}
