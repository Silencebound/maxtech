package dev.silencebound.maxtech.storage.crafting.generator;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockLoot;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import java.util.Set;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;

/**
 * The generator provider (M7C item 11): block, block entity type, GUI texts and recipe. The menu type sits in
 * {@code registry.ModMenus}.
 */
public final class GeneratorContent {
    private static final TagKey<Item> REDSTONE_DUSTS = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath("c", "dusts/redstone"));

    public static final BlockEntry<GeneratorProviderBlock, BlockItem> GENERATOR = ModContent.CONTENT
            .block("generator_provider", GeneratorProviderBlock::new)
            .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(3.0F, 6.0F).sound(SoundType.METAL)).simpleItem()
            .lang("Generator Provider").loot(BlockLoot.SELF).tag(BlockTags.MINEABLE_WITH_PICKAXE)
            // Redstone to switch the machine on, an I/O processor to collect what it makes.
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.MISC, item).pattern("PRP").pattern("ROR")
                    .pattern("PWP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE).define('R', REDSTONE_DUSTS)
                    .define('O', MaterialTags.component("basic_io_processor"))
                    .define('W', MaterialTags.component("insulated_copper_wire"))
                    .unlockedBy("has_basic_io_processor", recipes.has(MaterialTags.component("basic_io_processor")))
                    .save(recipes.output()))
            .creativeTab().register();

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<GeneratorProviderBlockEntity>> BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE)
            .register("generator_provider",
                    () -> new BlockEntityType<>(GeneratorProviderBlockEntity::new, Set.of(GENERATOR.get())));

    public static final String ITEM_KEY = key("item");
    public static final String FLUID_KEY = key("fluid");
    public static final String STRENGTH_KEY = key("strength");
    public static final String SIDES_FRONT_KEY = key("sides.front");
    public static final String SIDES_ALL_KEY = key("sides.all");
    public static final String SIDES_HINT_KEY = key("sides.hint");
    public static final String IDLE_KEY = key("idle");
    public static final String WORKING_KEY = key("working");
    public static final String RATE_KEY = key("rate");
    public static final String RATE_UNKNOWN_KEY = key("rate.unknown");
    public static final String STUCK_KEY = key("stuck");
    public static final String HINT_KEY = key("hint");

    static {
        ModContent.CONTENT.translation(ITEM_KEY, "Item");
        ModContent.CONTENT.translation(FLUID_KEY, "Fluid");
        ModContent.CONTENT.translation(STRENGTH_KEY, "Signal %s");
        ModContent.CONTENT.translation(SIDES_FRONT_KEY, "To the machine");
        ModContent.CONTENT.translation(SIDES_ALL_KEY, "All sides");
        ModContent.CONTENT.translation(SIDES_HINT_KEY, "Where the redstone signal goes while the machine should run");
        ModContent.CONTENT.translation(IDLE_KEY, "Idle: signal off");
        ModContent.CONTENT.translation(WORKING_KEY, "Signal on, waiting for %s");
        ModContent.CONTENT.translation(RATE_KEY, "Measured: about %s per minute");
        ModContent.CONTENT.translation(RATE_UNKNOWN_KEY, "Measured: nothing yet");
        ModContent.CONTENT.translation(STUCK_KEY, "Stuck: %s");
        ModContent.CONTENT.translation(HINT_KEY,
                "Set what the machine in front makes: an item, or a filled bucket for its fluid");
    }

    private GeneratorContent() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }

    static DataComponentType<GeneratorSettings> settingsType() {
        return ModDataComponents.GENERATOR_SETTINGS.get();
    }

    private static String key(String name) {
        return "gui." + MaxtechStorage.MOD_ID + ".generator_provider." + name;
    }
}
