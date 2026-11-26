package dev.silencebound.maxtech.storage.condition;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockLoot;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.part.ComponentCopyingRecipe;
import dev.silencebound.maxtech.storage.part.PartItem;
import dev.silencebound.maxtech.storage.part.PartType;
import dev.silencebound.maxtech.storage.part.PartTypes;
import dev.silencebound.maxtech.storage.registry.ModContent;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.research.Research;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;

/**
 * The condition switch: block, block item, part and part item, block entity type, recipes (the block from a comparator,
 * the forms into each other keeping their settings) and the research hook. The block's models are written by Storage's
 * model provider, the menu type sits in {@code registry.ModMenus}.
 */
public final class ConditionContent {
    public static final TagKey<Item> SWITCH_TAG = MaterialTags.component("condition_switch");
    public static final TagKey<Item> SWITCH_PART_TAG = MaterialTags.component("condition_switch_part");

    public static final BlockEntry<ConditionSwitchBlock, SwitchItem> SWITCH = ModContent.CONTENT
            .block("condition_switch", ConditionSwitchBlock::new)
            .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(2.0F, 6.0F).sound(SoundType.METAL))
            .item(SwitchItem::new).lang("Condition Switch").loot(BlockLoot.SELF).tag(BlockTags.MINEABLE_WITH_PICKAXE)
            .itemTag(SWITCH_TAG)
            // A comparator reads the condition, a logic processor works it out, signal alloy plates case it.
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.REDSTONE, item).pattern("PCP").pattern("WLW")
                    .pattern("PPP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE).define('C', Items.COMPARATOR)
                    .define('W', MaterialTags.component("insulated_copper_wire"))
                    .define('L', MaterialTags.component("basic_logic_processor"))
                    .unlockedBy("has_logic_processor", recipes.has(MaterialTags.component("basic_logic_processor")))
                    .save(recipes.output()))
            .recipe((recipes, item) -> ComponentCopyingRecipe.save(recipes.output(),
                    ResourceKey.create(Registries.RECIPE, MaxtechStorage.id("condition_switch_from_part")),
                    RecipeCategory.REDSTONE, new ItemStackTemplate(item), recipes.ingredient(SWITCH_PART_TAG)))
            .creativeTab().register();

    /** The switch as a part on a cable's face; Storage's model provider draws its item. */
    public static final RegistryEntry<Item, PartItem> SWITCH_PART_ITEM = ModContent.CONTENT
            .item("condition_switch_part", p -> new PartItem(p, () -> ConditionContent.SWITCH_PART))
            .lang("Condition Switch Part").tag(SWITCH_PART_TAG)
            .recipe((recipes, item) -> ComponentCopyingRecipe.save(recipes.output(),
                    ResourceKey.create(Registries.RECIPE, MaxtechStorage.id("condition_switch_part")),
                    RecipeCategory.REDSTONE, new ItemStackTemplate(item), recipes.ingredient(SWITCH_TAG)))
            .creativeTab().register();

    /** A 10 x 10 x 2 px plate; smaller than the terminal panel so it reads as a different part. */
    public static final PartType SWITCH_PART = PartTypes.register(
            new PartType(MaxtechStorage.id("condition_switch"), SWITCH_PART_ITEM, List.of(new AABB(3, 3, 0, 13, 13, 2)),
                    ConditionSwitchPart::new).tooltip(ConditionTexts::appendTooltip));

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<ConditionSwitchBlockEntity>> BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE)
            .register("condition_switch",
                    () -> new BlockEntityType<>(ConditionSwitchBlockEntity::new, Set.of(SWITCH.get())));

    private ConditionContent() {
    }

    /**
     * Loads the class during mod construction so the entries above are queued, and makes the switches of the completing
     * team look again when research they depend on completes (research conditions, and the switch's own unlock), and
     * the switches in while-crafting mode when a crafting job changes.
     */
    public static void init() {
        Research.onCompleted(ConditionWorker::researchCompleted);
        JobQueue.listen(ConditionWorker::jobChanged);
        Platform.get().lifecycle().onServerStopped(server -> ConditionWorker.clear());
        ConditionTexts.init();
    }

    /** The block's item, with the settings summary in its tooltip. */
    public static final class SwitchItem extends BlockItem {
        public SwitchItem(Block block, Properties properties) {
            super(block, properties);
        }

        @Override
        @SuppressWarnings("deprecation") // overriding is how items add tooltip lines; only calling it is deprecated
        public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                Consumer<Component> lines, TooltipFlag flag) {
            super.appendHoverText(stack, context, display, lines, flag);
            ConditionTexts.appendTooltip(stack, lines);
        }
    }
}
