package dev.silencebound.maxtech.storage.smartlabel;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockLoot;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.provider.StuckReason;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

/**
 * Smart labels (M7C item 19): the block and its item, the block entity type, the settings component, the values payload
 * and the texts. The block's models are written by Storage's model provider; this area's own registrars keep the shared
 * {@code ModDataComponents} untouched.
 */
public final class SmartLabelContent {
    private static final RegistryHelper.Registrar<DataComponentType<?>> COMPONENTS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.DATA_COMPONENT_TYPE);

    /** A label item's settings, left out when they are a new label's. Synced for its tooltip. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<LabelSettings>> SETTINGS = COMPONENTS
            .register("smart_label", () -> DataComponentType.<LabelSettings>builder().persistent(LabelSettings.CODEC)
                    .networkSynchronized(LabelSettings.STREAM_CODEC).build());

    public static final BlockEntry<SmartLabelBlock, LabelItem> LABEL = ModContent.CONTENT
            .block("smart_label", SmartLabelBlock::new)
            .properties(p -> p.mapColor(MapColor.METAL).strength(0.5F).sound(SoundType.METAL).noCollision()
                    .noOcclusion().pushReaction(PushReaction.DESTROY))
            .item(LabelItem::new).lang("Smart Label").loot(BlockLoot.SELF).tag(BlockTags.MINEABLE_WITH_PICKAXE)
            // A signal alloy plate as the plate, glowstone for the text, copper wire to read the block behind.
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.REDSTONE, item, 4).pattern(" G ").pattern("PWP")
                    .define('P', MaterialTags.SIGNAL_ALLOY_PLATE).define('G', MaterialTags.GLOWSTONE)
                    .define('W', MaterialTags.COPPER_WIRE)
                    .unlockedBy("has_signal_alloy_plate", recipes.has(MaterialTags.SIGNAL_ALLOY_PLATE))
                    .save(recipes.output()))
            .creativeTab().register();

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<SmartLabelBlockEntity>> BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE)
            .register("smart_label", () -> new BlockEntityType<>(SmartLabelBlockEntity::new, Set.of(LABEL.get())));

    /** Overlay after a click and tooltip line ({@code %s} the mode). */
    public static final String SHOWS_KEY = key("shows");
    /** Overlay after a click with an item ({@code %s} the item). */
    public static final String SHOWS_ITEM_KEY = key("shows_item");
    /** Tooltip line: the item whose count it shows ({@code %s}). */
    public static final String ITEM_KEY = key("item");
    /** On the label. */
    public static final String NO_ITEM_KEY = key("no_item");
    public static final String NOTHING_KEY = key("nothing");
    public static final String WORKING_KEY = key("working");
    public static final String IDLE_KEY = key("idle");
    public static final String STUCK_KEY = key("stuck");

    static {
        ModContent.CONTENT.translation(SHOWS_KEY, "Shows: %s");
        ModContent.CONTENT.translation(SHOWS_ITEM_KEY, "Shows the count of %s");
        ModContent.CONTENT.translation(ITEM_KEY, "Item: %s");
        ModContent.CONTENT.translation(NO_ITEM_KEY, "Click with an item");
        ModContent.CONTENT.translation(NOTHING_KEY, "Nothing to read");
        ModContent.CONTENT.translation(WORKING_KEY, "Working");
        ModContent.CONTENT.translation(IDLE_KEY, "Idle");
        ModContent.CONTENT.translation(STUCK_KEY, "Stuck");
        ModContent.CONTENT.translation(reasonKey(StuckReason.MACHINE_FULL), "Machine full");
        ModContent.CONTENT.translation(reasonKey(StuckReason.OUTPUT_BLOCKED), "Output blocked");
        ModContent.CONTENT.translation(reasonKey(StuckReason.NO_POWER), "No power");
        ModContent.CONTENT.translation(reasonKey(StuckReason.NOTHING_ARRIVED), "Nothing arrived");
        ModContent.CONTENT.translation(reasonKey(StuckReason.RECIPE_CHANGED), "Recipe changed");
        ModContent.CONTENT.translation(reasonKey(StuckReason.LOCKED_SIGNAL), "Locked by signal");
        ModContent.CONTENT.translation(reasonKey(StuckReason.LOCKED_NO_SIGNAL), "Locked, no signal");
        ModContent.CONTENT.translation(reasonKey(StuckReason.LOCKED_PULSE), "Waits for pulse");
        ModContent.CONTENT.translation(reasonKey(StuckReason.LOCKED_RESULT), "Waits for result");
    }

    private SmartLabelContent() {
    }

    private static String key(String name) {
        return "gui." + MaxtechStorage.MOD_ID + ".smart_label." + name;
    }

    /** The label's short text for a stuck reason. */
    public static String reasonKey(StuckReason reason) {
        return key("stuck." + reason.name().toLowerCase(Locale.ROOT));
    }

    /** The id a label update carries for {@code reason}; fixed per reason. */
    public static int reasonId(StuckReason reason) {
        return switch (reason) {
            case MACHINE_FULL -> 0;
            case OUTPUT_BLOCKED -> 1;
            case NO_POWER -> 2;
            case NOTHING_ARRIVED -> 3;
            case RECIPE_CHANGED -> 4;
            case LOCKED_SIGNAL -> 5;
            case LOCKED_NO_SIGNAL -> 6;
            case LOCKED_PULSE -> 7;
            case LOCKED_RESULT -> 8;
        };
    }

    /** The reason with {@link #reasonId} {@code id}; {@link StuckReason#MACHINE_FULL} for an unknown one. */
    public static StuckReason reasonById(long id) {
        for (StuckReason reason : StuckReason.values()) {
            if (reasonId(reason) == id) {
                return reason;
            }
        }
        return StuckReason.MACHINE_FULL;
    }

    /** Loads the class during mod construction so the entries above are queued, and registers the values payload. */
    public static void init() {
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, LabelUpdate.TYPE, LabelUpdate.STREAM_CODEC,
                LabelUpdate::handle);
    }

    /** The label's item, with its settings in the tooltip. */
    public static final class LabelItem extends BlockItem {
        public LabelItem(Block block, Properties properties) {
            super(block, properties);
        }

        @Override
        @SuppressWarnings("deprecation") // overriding is how items add tooltip lines; only calling it is deprecated
        public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                Consumer<Component> lines, TooltipFlag flag) {
            super.appendHoverText(stack, context, display, lines, flag);
            LabelSettings settings = stack.get(SETTINGS.get());
            if (settings == null) {
                return;
            }
            lines.accept(Component.translatable(SHOWS_KEY, Component.translatable(settings.mode().translationKey())));
            settings.item().ifPresent(item -> lines
                    .accept(Component.translatable(ITEM_KEY, Component.translatable(item.getDescriptionId()))));
        }
    }
}
