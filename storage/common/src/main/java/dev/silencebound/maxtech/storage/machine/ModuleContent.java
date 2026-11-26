package dev.silencebound.maxtech.storage.machine;

import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.storage.config.StorageModules;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * Upgrade modules for the machines' module slot (PRD 7.2): the precision module, the tantalum one, doubles the dies a
 * lithograph prints per wafer; the ember modules (M6d) make the lithograph, fabricator and assembler faster at a higher
 * FE cost (speed) or cheaper to run (efficiency); the void module (M6f) runs several recipes at once and goes in beside
 * one of the others. They are crafted; research gates fitting them ({@code ResearchGates.PRECISION_MODULE},
 * {@code SPEED_MODULE}, {@code EFFICIENCY_MODULE}, {@code VOID_MODULE}).
 */
public final class ModuleContent {
    /** Lang key of the module slot's tooltip while it is empty. */
    public static final String SLOT_KEY = "gui.maxtech_storage.machine.module_slot";
    private static final String PRECISION_KEY = "tooltip.maxtech_storage.precision_module";
    private static final String SPEED_KEY = "tooltip.maxtech_storage.speed_module";
    private static final String EFFICIENCY_KEY = "tooltip.maxtech_storage.efficiency_module";
    private static final String VOID_KEY = "tooltip.maxtech_storage.void_module";

    public static final RegistryEntry<Item, MachineModuleItem> PRECISION_MODULE = ModContent.CONTENT
            .item("precision_module", p -> new MachineModuleItem(MachineModule.PRECISION, PRECISION_KEY, p))
            .model(ItemModel.flat(MaxtechStorage.id("item/precision_module"))).lang("Precision Module")
            .tag(MaterialTags.component("precision_module")).recipe((recipes, item) -> {
                TagKey<Item> capacitor = MaterialTags.component("tantalum_capacitor");
                recipes.shaped(RecipeCategory.MISC, item).pattern("TCT").pattern("CAC").pattern("TCT")
                        .define('T', MaterialTags.TANTALUM_INGOT).define('C', capacitor)
                        .define('A', MaterialTags.component("advanced_logic_processor"))
                        .unlockedBy("has_tantalum_capacitor", recipes.has(capacitor)).save(recipes.output());
            }).creativeTab().register();
    /** Ember quartz round an advanced signal processor, with superconductor wire and tantalum capacitors. */
    public static final RegistryEntry<Item, MachineModuleItem> SPEED_MODULE = ember("speed_module", "Speed Module",
            MachineModule.SPEED, SPEED_KEY, "advanced_signal_processor", MaterialTags.component("superconductor_wire"));
    /** Ember quartz round an advanced I/O processor, with niobium ingots and tantalum capacitors. */
    public static final RegistryEntry<Item, MachineModuleItem> EFFICIENCY_MODULE = ember("efficiency_module",
            "Efficiency Module", MachineModule.EFFICIENCY, EFFICIENCY_KEY, "advanced_io_processor",
            MaterialTags.NIOBIUM_INGOT);

    /**
     * The void module (M6f): {@code VLV / SPS / VLV}, void crystal and void lattices round an elite logic processor,
     * with superconductor wire.
     */
    public static final RegistryEntry<Item, MachineModuleItem> VOID_MODULE = ModContent.CONTENT
            .item("void_module", p -> new MachineModuleItem(MachineModule.VOID, VOID_KEY, p))
            .model(ItemModel.flat(MaxtechStorage.id("item/void_module"))).lang("Void Module")
            .tag(MaterialTags.component("void_module")).recipe((recipes, item) -> {
                TagKey<Item> lattice = MaterialTags.component("void_lattice");
                recipes.shaped(RecipeCategory.MISC, item).pattern("VLV").pattern("SPS").pattern("VLV")
                        .define('V', MaterialTags.VOID_CRYSTAL).define('L', lattice)
                        .define('S', MaterialTags.component("superconductor_wire"))
                        .define('P', MaterialTags.component("elite_logic_processor"))
                        .unlockedBy("has_void_lattice", recipes.has(lattice))
                        .save(StorageModules.gate(recipes.output(), StorageModules.VOID_GEODES));
            }).creativeTab().register();

    static {
        ModContent.CONTENT.translation(SLOT_KEY, "Upgrade module");
        ModContent.CONTENT.translation(VOID_KEY,
                "Lithograph, fabricator, assembler: several runs at once for more MU; fits beside another module");
        ModContent.CONTENT.translation(PRECISION_KEY, "Lithograph: more dies from each printed wafer");
        ModContent.CONTENT.translation(SPEED_KEY, "Lithograph, fabricator, assembler: faster runs for more MU");
        ModContent.CONTENT.translation(EFFICIENCY_KEY, "Lithograph, fabricator, assembler: runs cost less MU");
    }

    private ModuleContent() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }

    /** An ember module: {@code EXE / CPC / EXE}, ember quartz, {@code side}, tantalum capacitors and a processor. */
    private static RegistryEntry<Item, MachineModuleItem> ember(String name, String english, MachineModule module,
            String tooltipKey, String processor, TagKey<Item> side) {
        return ModContent.CONTENT.item(name, p -> new MachineModuleItem(module, tooltipKey, p))
                .model(ItemModel.flat(MaxtechStorage.id("item/" + name))).lang(english)
                .tag(MaterialTags.component(name)).recipe((recipes, item) -> {
                    TagKey<Item> capacitor = MaterialTags.component("tantalum_capacitor");
                    recipes.shaped(RecipeCategory.MISC, item).pattern("EXE").pattern("CPC").pattern("EXE")
                            .define('E', MaterialTags.EMBER_QUARTZ).define('X', side).define('C', capacitor)
                            .define('P', MaterialTags.component(processor))
                            .unlockedBy("has_ember_quartz", recipes.has(MaterialTags.EMBER_QUARTZ))
                            .save(recipes.output());
                }).creativeTab().register();
    }
}
