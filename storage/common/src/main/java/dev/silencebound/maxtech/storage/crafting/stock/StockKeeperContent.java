package dev.silencebound.maxtech.storage.crafting.stock;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockLoot;
import dev.silencebound.maxtech.lib.content.BlockModel;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.iface.InterfaceCrafting;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import dev.silencebound.maxtech.storage.research.Research;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;

/**
 * The stock keeper (M7 C9): block, block item with its rules in the tooltip, block entity type, recipe and the research
 * hook. The menu type sits in {@code registry.ModMenus}.
 */
public final class StockKeeperContent {
    public static final BlockEntry<StockKeeperBlock, KeeperItem> KEEPER = ModContent.CONTENT
            .block("stock_keeper", StockKeeperBlock::new)
            .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(2.0F, 6.0F).sound(SoundType.METAL))
            .item(KeeperItem::new).model(BlockModel.cubeAll(MaxtechStorage.id("block/stock_keeper_front")))
            .lang("Stock Keeper").loot(BlockLoot.SELF).tag(BlockTags.MINEABLE_WITH_PICKAXE)
            // A comparator watches the level, memory remembers the rules, a logic processor decides.
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.MISC, item).pattern("PCP").pattern("MLM")
                    .pattern("PWP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE).define('C', Items.COMPARATOR)
                    .define('M', MaterialTags.component("basic_memory_processor"))
                    .define('L', MaterialTags.component("basic_logic_processor"))
                    .define('W', MaterialTags.component("insulated_copper_wire"))
                    .unlockedBy("has_basic_memory_processor",
                            recipes.has(MaterialTags.component("basic_memory_processor")))
                    .save(recipes.output()))
            .creativeTab().register();

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<StockKeeperBlockEntity>> BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE)
            .register("stock_keeper", () -> new BlockEntityType<>(StockKeeperBlockEntity::new, Set.of(KEEPER.get())));

    private StockKeeperContent() {
    }

    /** Loads the class during mod construction, and wakes the keepers a research completion can start. */
    public static void init() {
        StockTexts.init();
        Research.onCompleted(StockKeeperWorker::researchCompleted);
        InterfaceCrafting.setHandler(InterfaceCardCrafting.INSTANCE);
        StockPayloads.init();
        Platform.get().lifecycle().onServerStopped(server -> {
            StockKeeperWorker.clear();
            InterfaceCardCrafting.INSTANCE.clear();
            StockPayloads.clear();
        });
    }

    /** The keeper's item, with its rules in the tooltip. */
    public static final class KeeperItem extends BlockItem {
        public KeeperItem(Block block, Properties properties) {
            super(block, properties);
        }

        @Override
        @SuppressWarnings("deprecation") // overriding is how items add tooltip lines; only calling it is deprecated
        public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                Consumer<Component> lines, TooltipFlag flag) {
            super.appendHoverText(stack, context, display, lines, flag);
            StockTexts.appendTooltip(stack, lines);
        }
    }
}
