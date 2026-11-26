package dev.silencebound.maxtech.storage.crafting.terminal;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockLoot;
import dev.silencebound.maxtech.lib.content.BlockModel;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.part.PartItem;
import dev.silencebound.maxtech.storage.part.PartType;
import dev.silencebound.maxtech.storage.part.PartTypes;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;

/**
 * The pattern terminal (M7 C20): block, part, block entity type, GUI texts, recipes (the two forms convert 1:1) and its
 * payloads. Its menu is a {@code TerminalMenu} with the {@link PatternPanel}; the menu type sits in
 * {@code registry.ModMenus}.
 */
public final class PatternTerminalContent {
    public static final TagKey<Item> TERMINAL_TAG = MaterialTags.component("pattern_terminal");
    public static final TagKey<Item> TERMINAL_PART_TAG = MaterialTags.component("pattern_terminal_part");

    public static final BlockEntry<PatternTerminalBlock, BlockItem> TERMINAL = ModContent.CONTENT
            .block("pattern_terminal", PatternTerminalBlock::new)
            .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(3.0F, 6.0F).sound(SoundType.METAL)).simpleItem()
            .model(BlockModel.orientable(MaxtechStorage.id("block/pattern_terminal_front"),
                    MaxtechStorage.id("block/terminal/bezel"), MaxtechStorage.id("block/terminal/bezel")))
            .lang("Pattern Terminal").loot(BlockLoot.SELF).tag(BlockTags.MINEABLE_WITH_PICKAXE).itemTag(TERMINAL_TAG)
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.MISC, item).pattern("PDP").pattern("MCM")
                    .pattern("PWP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .define('D', MaterialTags.component("display_glass"))
                    .define('M', MaterialTags.component("basic_memory_processor")).define('C', Items.CRAFTING_TABLE)
                    .define('W', MaterialTags.component("insulated_copper_wire"))
                    .unlockedBy("has_basic_memory_processor",
                            recipes.has(MaterialTags.component("basic_memory_processor")))
                    .save(recipes.output()))
            .creativeTab().register();

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<PatternTerminalBlockEntity>> BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE)
            .register("pattern_terminal",
                    () -> new BlockEntityType<>(PatternTerminalBlockEntity::new, Set.of(TERMINAL.get())));

    /** The pattern terminal as a part on a cable's face; Storage's model provider draws it. */
    public static final RegistryEntry<Item, PartItem> TERMINAL_PART_ITEM = ModContent.CONTENT
            .item("pattern_terminal_part", p -> new PartItem(p, () -> PatternTerminalContent.TERMINAL_PART))
            .lang("Pattern Terminal Part").tag(TERMINAL_PART_TAG)
            .recipe((recipes, item) -> recipes.shapeless(RecipeCategory.MISC, item).requires(TERMINAL_TAG)
                    .unlockedBy("has_pattern_terminal", recipes.has(TERMINAL_TAG)).save(recipes.output()))
            .recipe((recipes, item) -> recipes.shapeless(RecipeCategory.MISC, TERMINAL.item().get())
                    .requires(TERMINAL_PART_TAG).unlockedBy("has_pattern_terminal_part", recipes.has(TERMINAL_PART_TAG))
                    .save(recipes.output(),
                            ResourceKey.create(Registries.RECIPE, MaxtechStorage.id("pattern_terminal_from_part"))))
            .creativeTab().register();
    public static final PartType TERMINAL_PART = PartTypes.register(new PartType(MaxtechStorage.id("pattern_terminal"),
            TERMINAL_PART_ITEM, List.of(new AABB(2, 2, 0, 14, 14, 2)), PatternTerminalPart::new));

    public static final String DENIED_KEY = "message." + MaxtechStorage.MOD_ID + ".pattern_terminal.denied";
    public static final String INPUTS_KEY = key("inputs");
    public static final String OUTPUTS_KEY = key("outputs");
    public static final String CRAFTING_KEY = key("crafting");
    public static final String PROCESSING_KEY = key("processing");
    public static final String STONECUTTING_KEY = key("stonecutting");
    public static final String SMITHING_KEY = key("smithing");
    public static final String STONECUTTING_HINT_KEY = key("stonecutting.hint");
    public static final String SCALE_UP_KEY = key("scale.up");
    public static final String SCALE_DOWN_KEY = key("scale.down");
    public static final String SCALE_UP_HINT_KEY = key("scale.up.hint");
    public static final String SCALE_DOWN_HINT_KEY = key("scale.down.hint");
    public static final String SCALE_NOT_WHOLE_KEY = key("scale.not_whole");
    public static final String SCALE_TOO_LARGE_KEY = key("scale.too_large");
    public static final String SCALE_EMPTY_KEY = key("scale.empty");
    public static final String ENCODE_KEY = key("encode");
    public static final String CLEAR_KEY = key("clear");
    public static final String HINT_KEY = key("hint");
    public static final String SELECT_HINT_KEY = key("select_hint");
    public static final String SELECTED_KEY = key("selected");
    public static final String INPUT_HINT_KEY = key("input_hint");
    public static final String FACE_KEY = key("face");
    public static final String FACE_PROVIDER_KEY = key("face.provider");
    public static final String RETURNED_KEY = key("returned");
    public static final String TAG_KEY = key("tag");
    public static final String EXACT_KEY = key("exact");
    public static final String LOAD_HINT_KEY = key("load_hint");
    public static final String SUBSTITUTIONS_ON_KEY = key("substitutions.on");
    public static final String SUBSTITUTIONS_OFF_KEY = key("substitutions.off");
    public static final String SUBSTITUTIONS_HINT_KEY = key("substitutions.hint");
    public static final String ADVANCED_ON_KEY = key("advanced.on");
    public static final String ADVANCED_OFF_KEY = key("advanced.off");
    public static final String ADVANCED_SELECT_KEY = key("advanced.select");
    public static final String ADVANCED_SLOT_KEY = key("advanced.slot");
    public static final String ADVANCED_CRAFTING_KEY = key("advanced.crafting");
    public static final String ADVANCED_HINT_KEY = key("advanced.hint");
    public static final String RETURNED_ON_KEY = key("returned.on");
    public static final String RETURNED_OFF_KEY = key("returned.off");
    public static final String TAG_ANY_KEY = key("tag.any");
    public static final String TAG_LOCKED_KEY = key("tag.locked");
    public static final String TAG_NONE_KEY = key("tag.none");

    static {
        ModContent.CONTENT.translation(DENIED_KEY, "You need craft access to this network");
        ModContent.CONTENT.translation(INPUTS_KEY, "Inputs");
        ModContent.CONTENT.translation(OUTPUTS_KEY, "Outputs");
        ModContent.CONTENT.translation(CRAFTING_KEY, "Crafting");
        ModContent.CONTENT.translation(PROCESSING_KEY, "Processing");
        ModContent.CONTENT.translation(STONECUTTING_KEY, "Stonecutting");
        ModContent.CONTENT.translation(SMITHING_KEY, "Smithing: template, base, addition");
        ModContent.CONTENT.translation(STONECUTTING_HINT_KEY, "Right-click: the next stonecutter recipe for the input");
        ModContent.CONTENT.translation(SCALE_UP_KEY, "x%s");
        ModContent.CONTENT.translation(SCALE_DOWN_KEY, "÷%s");
        ModContent.CONTENT.translation(SCALE_UP_HINT_KEY, "Multiply every amount by 2 (shift: by 8)");
        ModContent.CONTENT.translation(SCALE_DOWN_HINT_KEY, "Divide every amount by 2 (shift: by 8)");
        ModContent.CONTENT.translation(SCALE_NOT_WHOLE_KEY, "Not every amount divides by %s into a whole number");
        ModContent.CONTENT.translation(SCALE_TOO_LARGE_KEY, "An amount would go above %s");
        ModContent.CONTENT.translation(SCALE_EMPTY_KEY, "Nothing to scale");
        ModContent.CONTENT.translation(ENCODE_KEY, "Encode");
        ModContent.CONTENT.translation(CLEAR_KEY, "Clear");
        ModContent.CONTENT.translation(HINT_KEY,
                "Click with a stack to set, right-click for one more, right-click empty-handed for one less");
        ModContent.CONTENT.translation(SELECT_HINT_KEY,
                "Empty-handed click: select, then click a stored item or drag one here to set it. Again: clear");
        ModContent.CONTENT.translation(SELECTED_KEY, "Selected: a click on a stored item sets this slot");
        ModContent.CONTENT.translation(INPUT_HINT_KEY, "Shift-click: a bucket's fluid. Middle-click: tag lock");
        ModContent.CONTENT.translation(FACE_KEY, "Goes into: %s");
        ModContent.CONTENT.translation(FACE_PROVIDER_KEY, "the provider's face");
        ModContent.CONTENT.translation(RETURNED_KEY, "Returned after the craft");
        ModContent.CONTENT.translation(TAG_KEY, "Any of #%s");
        ModContent.CONTENT.translation(EXACT_KEY, "Locked to this item");
        ModContent.CONTENT.translation(LOAD_HINT_KEY, "Click with an encoded pattern to edit it");
        ModContent.CONTENT.translation(SUBSTITUTIONS_ON_KEY, "Substitutions: On");
        ModContent.CONTENT.translation(SUBSTITUTIONS_OFF_KEY, "Substitutions: Off");
        ModContent.CONTENT.translation(SUBSTITUTIONS_HINT_KEY,
                "On: tag inputs take any matching item. Off: every input is locked to the item shown");
        ModContent.CONTENT.translation(ADVANCED_ON_KEY, "Advanced: On");
        ModContent.CONTENT.translation(ADVANCED_OFF_KEY, "Advanced: Off");
        ModContent.CONTENT.translation(ADVANCED_SELECT_KEY, "Pick an input");
        ModContent.CONTENT.translation(ADVANCED_SLOT_KEY, "Input %s");
        ModContent.CONTENT.translation(ADVANCED_CRAFTING_KEY, "Processing only");
        ModContent.CONTENT.translation(ADVANCED_HINT_KEY,
                "Empty-handed click: select. Ctrl-click: face. Alt-click: returned");
        ModContent.CONTENT.translation(RETURNED_ON_KEY, "Returned: Yes");
        ModContent.CONTENT.translation(RETURNED_OFF_KEY, "Returned: No");
        ModContent.CONTENT.translation(TAG_ANY_KEY, "Tag: Any");
        ModContent.CONTENT.translation(TAG_LOCKED_KEY, "Tag: Locked");
        ModContent.CONTENT.translation(TAG_NONE_KEY, "No tag");
        for (PatternEncoder.Result result : PatternEncoder.Result.values()) {
            ModContent.CONTENT.translation(resultKey(result), switch (result) {
                case ENCODED -> "Pattern encoded";
                case EMPTY -> "Needs at least one input and one output";
                case NO_RECIPE -> "No recipe matches the grid";
                case NO_BLANK -> "No blank pattern in your inventory";
            });
        }
    }

    private PatternTerminalContent() {
    }

    /** Loads the class during mod construction and registers the panel's payloads. */
    public static void init() {
        PatternPanel.registerPayloads();
    }

    /** The lang key of an encode result. */
    public static String resultKey(PatternEncoder.Result result) {
        return key("result." + result.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static String key(String name) {
        return "gui." + MaxtechStorage.MOD_ID + ".pattern_terminal." + name;
    }
}
