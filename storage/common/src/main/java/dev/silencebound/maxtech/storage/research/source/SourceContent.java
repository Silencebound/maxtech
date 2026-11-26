package dev.silencebound.maxtech.storage.research.source;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.machine.MachineRuns;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;

/**
 * The research point sources' registrations (M6b area E): the machine observer (block, item, block entity type), the
 * research scanner, research notes and their texts. The notes have no recipe: they come from structure chests and
 * librarians (Storage's neoforge glue). The analyzer's analysis lives in {@link ItemAnalysis}; the item stays in
 * {@code network}.
 */
public final class SourceContent {
    public static final BlockEntry<MachineObserverBlock, MachineObserverItem> MACHINE_OBSERVER = ModContent.CONTENT
            .block("machine_observer", MachineObserverBlock::new)
            .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(3.0F, 6.0F).sound(SoundType.METAL))
            .item(MachineObserverItem::new).lang("Machine Observer").tag(BlockTags.MINEABLE_WITH_PICKAXE)
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.MISC, item).pattern("PDP").pattern("RMR")
                    .pattern("PPP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .define('D', MaterialTags.component("display_glass")).define('R', MaterialTags.REDSTONE)
                    .define('M', MaterialTags.component("basic_memory_processor"))
                    .unlockedBy("has_basic_memory_processor",
                            recipes.has(MaterialTags.component("basic_memory_processor")))
                    .save(recipes.output()))
            .creativeTab().register();

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<MachineObserverBlockEntity>> OBSERVER_BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE)
            .register("machine_observer",
                    () -> new BlockEntityType<>(MachineObserverBlockEntity::new, Set.of(MACHINE_OBSERVER.get())));

    public static final RegistryEntry<Item, ResearchScannerItem> RESEARCH_SCANNER = ModContent.CONTENT
            .item("research_scanner", p -> new ResearchScannerItem(p.stacksTo(1)))
            .model(ItemModel.flat(MaxtechStorage.id("item/research_scanner"))).lang("Research Scanner")
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.TOOLS, item).pattern("PDP").pattern("OSO")
                    .pattern("PWP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .define('D', MaterialTags.component("display_glass"))
                    .define('O', MaterialTags.component("oscillator"))
                    .define('S', MaterialTags.component("basic_signal_processor"))
                    .define('W', MaterialTags.component("insulated_copper_wire"))
                    .unlockedBy("has_basic_signal_processor",
                            recipes.has(MaterialTags.component("basic_signal_processor")))
                    .save(recipes.output()))
            .creativeTab().register();

    public static final RegistryEntry<Item, ResearchNotesItem> RESEARCH_NOTES = ModContent.CONTENT
            .item("research_notes", p -> new ResearchNotesItem(p.rarity(Rarity.UNCOMMON)))
            .model(ItemModel.flat(MaxtechStorage.id("item/research_notes"))).lang("Research Notes").creativeTab()
            .register();

    static {
        ModContent.CONTENT.translation(ResearchScannerItem.DESCRIPTION_KEY,
                "Use on any block: the first scan of each block type gives your team research points.");
        ModContent.CONTENT.translation(ResearchNotesItem.DESCRIPTION_KEY, "Read to give your team research points.");
        ModContent.CONTENT.translation(ResearchScannerItem.NEW_KEY, "Scanned %s: %s research points");
        ModContent.CONTENT.translation(ResearchScannerItem.KNOWN_KEY, "Your team has already scanned %s");
        ModContent.CONTENT.translation(ResearchScannerItem.OFF_KEY, "Research is turned off on this server");
        ModContent.CONTENT.translation(ResearchNotesItem.READ_KEY, "The notes give your team %s research points");
        ModContent.CONTENT.translation(ItemAnalysis.NEW_KEY, "Analysed %s: %s research points");
        ModContent.CONTENT.translation(ItemAnalysis.KNOWN_KEY, "Your team has already analysed %s");
        ModContent.CONTENT.translation(MachineObserverItem.POINTS_KEY, "%s points given on day %s");
        ModContent.CONTENT.translation(MachineObserverItem.RUNS_KEY, "%s runs towards the next point");
    }

    private SourceContent() {
    }

    /** Loads the class during mod construction so the entries above are queued, and hooks the observer into runs. */
    public static void init() {
        MachineRuns.listen(ObserverSource::finished);
    }
}
