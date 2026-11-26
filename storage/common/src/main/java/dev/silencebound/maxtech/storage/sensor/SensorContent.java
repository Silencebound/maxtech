package dev.silencebound.maxtech.storage.sensor;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockLoot;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.part.ComponentCopyingRecipe;
import dev.silencebound.maxtech.storage.part.PartItem;
import dev.silencebound.maxtech.storage.part.PartType;
import dev.silencebound.maxtech.storage.part.PartTypes;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.inventory.MenuType;
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
 * The sensor (M7L area D): block, block item, part and part item, block entity type, settings component, menu and
 * recipes (the block from an observer, the forms into each other keeping their settings). Craftable without research in
 * the prototype. The block's models are written by Storage's model provider; this area's own registrars keep the shared
 * {@code ModDataComponents} and {@code ModMenus} untouched.
 */
public final class SensorContent {
    public static final TagKey<Item> SENSOR_TAG = MaterialTags.component("sensor");
    public static final TagKey<Item> SENSOR_PART_TAG = MaterialTags.component("sensor_part");

    private static final RegistryHelper.Registrar<DataComponentType<?>> COMPONENTS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.DATA_COMPONENT_TYPE);

    /** A sensor item's settings, left out when they are a new sensor's. Synced for its tooltip. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<SensorSettings>> SETTINGS = COMPONENTS
            .register("sensor", () -> DataComponentType.<SensorSettings>builder().persistent(SensorSettings.CODEC)
                    .networkSynchronized(SensorSettings.STREAM_CODEC).build());

    /** The sensor's screen: its values and polling. */
    public static final RegistryEntry<MenuType<?>, MenuType<SensorMenu>> MENU = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.MENU)
            .register("sensor", () -> Platform.get().menus().menuType(SensorMenu::fromNetwork));

    public static final BlockEntry<SensorBlock, SensorItem> SENSOR = ModContent.CONTENT
            .block("sensor", SensorBlock::new)
            .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(2.0F, 6.0F).sound(SoundType.METAL))
            .item(SensorItem::new).lang("Sensor").loot(BlockLoot.SELF).tag(BlockTags.MINEABLE_WITH_PICKAXE)
            .itemTag(SENSOR_TAG)
            // An observer watches the target, a logic processor reads it, signal alloy plates case it.
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.REDSTONE, item).pattern("POP").pattern("WLW")
                    .pattern("PPP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE).define('O', Items.OBSERVER)
                    .define('W', MaterialTags.component("insulated_copper_wire"))
                    .define('L', MaterialTags.component("basic_logic_processor"))
                    .unlockedBy("has_logic_processor", recipes.has(MaterialTags.component("basic_logic_processor")))
                    .save(recipes.output()))
            .recipe((recipes, item) -> ComponentCopyingRecipe.save(recipes.output(),
                    ResourceKey.create(Registries.RECIPE, MaxtechStorage.id("sensor_from_part")),
                    RecipeCategory.REDSTONE, new ItemStackTemplate(item), recipes.ingredient(SENSOR_PART_TAG)))
            .creativeTab().register();

    /** The sensor as a part on a cable's face; Storage's model provider draws its item. */
    public static final RegistryEntry<Item, PartItem> SENSOR_PART_ITEM = ModContent.CONTENT
            .item("sensor_part", p -> new PartItem(p, () -> SensorContent.SENSOR_PART)).lang("Sensor Part")
            .tag(SENSOR_PART_TAG)
            .recipe((recipes, item) -> ComponentCopyingRecipe.save(recipes.output(),
                    ResourceKey.create(Registries.RECIPE, MaxtechStorage.id("sensor_part")), RecipeCategory.REDSTONE,
                    new ItemStackTemplate(item), recipes.ingredient(SENSOR_TAG)))
            .creativeTab().register();

    /** An 8 x 8 x 3 px plate, deeper and narrower than the condition switch so the two read apart. */
    public static final PartType SENSOR_PART = PartTypes
            .register(new PartType(MaxtechStorage.id("sensor"), SENSOR_PART_ITEM, List.of(new AABB(4, 4, 0, 12, 12, 3)),
                    SensorPart::new).tooltip(SensorTexts::appendTooltip));

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<SensorBlockEntity>> BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE)
            .register("sensor", () -> new BlockEntityType<>(SensorBlockEntity::new, Set.of(SENSOR.get())));

    private SensorContent() {
    }

    /** Loads the class during mod construction so the entries above are queued, and registers the values payload. */
    public static void init() {
        SensorTexts.init();
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, SensorView.TYPE, SensorView.STREAM_CODEC,
                SensorView::handle);
    }

    /** The block's item, with the polling in its tooltip. */
    public static final class SensorItem extends BlockItem {
        public SensorItem(Block block, Properties properties) {
            super(block, properties);
        }

        @Override
        @SuppressWarnings("deprecation") // overriding is how items add tooltip lines; only calling it is deprecated
        public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                Consumer<Component> lines, TooltipFlag flag) {
            super.appendHoverText(stack, context, display, lines, flag);
            SensorTexts.appendTooltip(stack, lines);
        }
    }
}
