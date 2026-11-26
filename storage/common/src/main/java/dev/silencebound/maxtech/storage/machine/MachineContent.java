package dev.silencebound.maxtech.storage.machine;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.ConventionTags;
import dev.silencebound.maxtech.lib.content.RecipeContext;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

/**
 * Storage's four machines (PRD 7.2): fuel generator, lithograph, fabricator and assembler, each a block with its item
 * and block entity type, plus a recipe type and serializer for each processing machine and their recipe book category.
 * The machines' own crafting recipes are plain shaped recipes from iron, copper, lumen quartz, silicon and vanilla
 * parts, so the line can start from a crafting table.
 */
public final class MachineContent {
    /** The masks a lithograph's catalyst slot takes: the logic, memory, signal and I/O masks. */
    public static final TagKey<Item> MASKS = TagKey.create(Registries.ITEM, MaxtechStorage.id("masks"));

    private static final RegistryHelper.Registrar<RecipeType<?>> TYPES = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.RECIPE_TYPE);
    private static final RegistryHelper.Registrar<RecipeSerializer<?>> SERIALIZERS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.RECIPE_SERIALIZER);
    private static final RegistryHelper.Registrar<BlockEntityType<?>> BLOCK_ENTITY_TYPES = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE);
    private static final Map<MachineKind, BlockEntry<StorageMachineBlock, StorageMachineItem>> BLOCKS = new EnumMap<>(
            MachineKind.class);
    private static final Map<MachineKind, RegistryEntry<BlockEntityType<?>, BlockEntityType<StorageMachineBlockEntity>>> BLOCK_ENTITIES = new EnumMap<>(
            MachineKind.class);
    private static final Map<MachineKind, RegistryEntry<RecipeType<?>, RecipeType<StorageMachineRecipe>>> RECIPE_TYPES = new EnumMap<>(
            MachineKind.class);
    private static final Map<MachineKind, RegistryEntry<RecipeSerializer<?>, RecipeSerializer<StorageMachineRecipe>>> RECIPE_SERIALIZERS = new EnumMap<>(
            MachineKind.class);

    /** Every Storage machine recipe reports this category; none shows in the vanilla recipe book. */
    public static final RegistryEntry<RecipeBookCategory, RecipeBookCategory> BOOK_CATEGORY = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.RECIPE_BOOK_CATEGORY)
            .register("machine", RecipeBookCategory::new);

    public static final BlockEntry<StorageMachineBlock, StorageMachineItem> FUEL_GENERATOR = machine(
            MachineKind.FUEL_GENERATOR, "Fuel Generator", "ICI", "IFI", "IRI");
    public static final BlockEntry<StorageMachineBlock, StorageMachineItem> LITHOGRAPH = machine(MachineKind.LITHOGRAPH,
            "Lithograph", "IGI", "QSQ", "IRI");
    public static final BlockEntry<StorageMachineBlock, StorageMachineItem> FABRICATOR = machine(MachineKind.FABRICATOR,
            "Fabricator", "IPI", "QCQ", "IRI");
    public static final BlockEntry<StorageMachineBlock, StorageMachineItem> ASSEMBLER = machine(MachineKind.ASSEMBLER,
            "Assembler", "ITI", "SWS", "IRI");

    static {
        for (MachineKind kind : MachineKind.values()) {
            for (String mode : kind.modes()) {
                ModContent.CONTENT.translation(kind.modeKey(mode),
                        Character.toUpperCase(mode.charAt(0)) + mode.substring(1));
            }
            if (kind.processes()) {
                Identifier id = MaxtechStorage.id(kind.id());
                RECIPE_TYPES.put(kind, TYPES.register(kind.id(), () -> RecipeType.simple(id)));
                RECIPE_SERIALIZERS.put(kind,
                        SERIALIZERS.register(kind.id(), () -> new RecipeSerializer<>(StorageMachineRecipe.codec(kind),
                                StorageMachineRecipe.streamCodec(kind))));
            }
        }
        ModContent.CONTENT.itemTag(MASKS, MaterialTags.component("logic_mask"), MaterialTags.component("memory_mask"),
                MaterialTags.component("signal_mask"), MaterialTags.component("io_mask"));
        ModContent.CONTENT.translation(MachineState.IDLE.translationKey(), "Idle");
        ModContent.CONTENT.translation(MachineState.RUNNING.translationKey(), "Working");
        ModContent.CONTENT.translation(MachineState.NO_ENERGY.translationKey(), "Not enough energy");
        ModContent.CONTENT.translation(MachineState.OUTPUT_FULL.translationKey(), "Output full");
        ModContent.CONTENT.translation(MachineState.RESEARCH_NEEDED.translationKey(), "Needs research");
        ModContent.CONTENT.translation(StorageMachineItem.ENERGY_KEY, "Holds %s MU");
        ModContent.CONTENT.translation(StorageMachineItem.ITEMS_KEY, "Holds items");
        ModContent.CONTENT.translation(MachineTexts.ENERGY_KEY, "%s / %s MU");
        ModContent.CONTENT.translation(MachineTexts.PROGRESS_KEY, "%s / %s ticks");
        ModContent.CONTENT.translation(MachineTexts.MODE_KEY, "Mode: %s");
        ModContent.CONTENT.translation(MachineTexts.EXPORT_KEY, "Export pattern");
        ModContent.CONTENT.translation(MachineTexts.EXPORT_TOOLTIP_KEY,
                "Writes the recipe in the slots, or the last one run, onto a blank pattern from your inventory");
        for (MachineExport.Result result : MachineExport.Result.values()) {
            ModContent.CONTENT.translation(MachineTexts.exportKey(result), switch (result) {
                case EXPORTED -> "Pattern exported";
                case NO_RECIPE -> "The machine has no recipe to export";
                case NO_BLANK -> "No blank pattern in your inventory";
                case DENIED -> "You need craft access to this network, or to own the machine";
            });
        }
    }

    private MachineContent() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }

    public static BlockEntityType<StorageMachineBlockEntity> blockEntityType(MachineKind kind) {
        return entry(BLOCK_ENTITIES, kind).get();
    }

    public static StorageMachineBlock block(MachineKind kind) {
        return entry(BLOCKS, kind).get();
    }

    public static RecipeType<StorageMachineRecipe> recipeType(MachineKind kind) {
        return entry(RECIPE_TYPES, kind).get();
    }

    public static RecipeSerializer<StorageMachineRecipe> serializer(MachineKind kind) {
        return entry(RECIPE_SERIALIZERS, kind).get();
    }

    private static <T> T entry(Map<MachineKind, T> entries, MachineKind kind) {
        T entry = entries.get(kind);
        if (entry == null) {
            throw new IllegalArgumentException(kind.id() + " has no such entry");
        }
        return entry;
    }

    /**
     * Registers {@code kind}'s block, item and block entity type. Recipe keys: I iron, C copper ingot, R redstone, F
     * furnace, G glass, Q lumen quartz, S silicon, P piston, T crafting table, W copper wire.
     */
    private static BlockEntry<StorageMachineBlock, StorageMachineItem> machine(MachineKind kind, String english,
            String top, String middle, String bottom) {
        BlockEntry<StorageMachineBlock, StorageMachineItem> entry = ModContent.CONTENT
                .block(kind.id(), p -> new StorageMachineBlock(kind, p))
                .properties(p -> p.mapColor(MapColor.METAL).strength(3.5F, 6.0F).sound(SoundType.METAL))
                .item(StorageMachineItem::new).lang(english).tag(BlockTags.MINEABLE_WITH_PICKAXE)
                .recipe((recipes, item) -> shaped(recipes, item, top, middle, bottom)).creativeTab().register();
        BLOCKS.put(kind, entry);
        RegistryEntry<BlockEntityType<?>, BlockEntityType<StorageMachineBlockEntity>> type = BLOCK_ENTITY_TYPES
                .<BlockEntityType<StorageMachineBlockEntity>>register(kind.id(),
                        () -> new BlockEntityType<>(MachineContent::create, Set.of(entry.get())));
        BLOCK_ENTITIES.put(kind, type);
        return entry;
    }

    private static StorageMachineBlockEntity create(BlockPos pos, BlockState state) {
        return ((StorageMachineBlock) state.getBlock()).newBlockEntity(pos, state);
    }

    private static void shaped(RecipeContext recipes, Item item, String top, String middle, String bottom) {
        ShapedRecipeBuilder builder = recipes.shaped(RecipeCategory.MISC, item).pattern(top).pattern(middle)
                .pattern(bottom);
        String all = top + middle + bottom;
        define(builder, all, 'I', MaterialTags.IRON_INGOT);
        define(builder, all, 'C', MaterialTags.COPPER_INGOT);
        define(builder, all, 'R', MaterialTags.REDSTONE);
        define(builder, all, 'F', ConventionTags.FURNACES);
        define(builder, all, 'G', MaterialTags.GLASS_BLOCKS);
        define(builder, all, 'Q', MaterialTags.LUMEN_QUARTZ);
        define(builder, all, 'S', MaterialTags.SILICON);
        define(builder, all, 'W', MaterialTags.COPPER_WIRE);
        if (all.indexOf('P') >= 0) {
            builder.define('P', Items.PISTON);
        }
        if (all.indexOf('T') >= 0) {
            builder.define('T', Items.CRAFTING_TABLE);
        }
        builder.unlockedBy("has_lumen_quartz", recipes.has(MaterialTags.LUMEN_QUARTZ)).save(recipes.output());
    }

    private static void define(ShapedRecipeBuilder builder, String pattern, char key, TagKey<Item> tag) {
        if (pattern.indexOf(key) >= 0) {
            builder.define(key, tag);
        }
    }
}
