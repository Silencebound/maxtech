package dev.silencebound.maxtech.storage.machine;

import dev.silencebound.maxtech.lib.api.recipe.ItemOutput;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.config.StorageModules;
import dev.silencebound.maxtech.storage.material.DataCrystalContent;
import dev.silencebound.maxtech.storage.material.MaterialContent;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.material.TierTwoMaterialContent;
import dev.silencebound.maxtech.storage.material.VoidCrystalContent;
import dev.silencebound.maxtech.storage.research.ResearchGates;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.HolderGetter;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * The machine recipes of the production lines (PRD 7.2), written by datagen to
 * {@code data/maxtech_storage/recipe/<machine>/}. Energy and time per run are recipe data: about 10 FE a tick, so one
 * fuel generator (20 FE a tick) keeps two machines busy; tier-2 steps draw a little more. Chances are recipe data too
 * (the niobium byproduct of refining is 25 %, M6b decision), so a datapack or KubeJS script tunes them per recipe.
 */
public final class MachineRecipes {
    private static final int SLOW = 200;
    private static final int NORMAL = 120;
    private static final int FAST = 60;
    private static final int NIOBIUM_CHANCE = ItemOutput.ALWAYS / 4;

    private MachineRecipes() {
    }

    /** Saves every machine recipe to {@code output}; {@code items} resolves tags. */
    public static void build(HolderGetter<Item> items, RecipeOutput output) {
        TagKey<Item> wafer = MaterialTags.component("silicon_wafer");
        TagKey<Item> doped = MaterialTags.component("doped_wafer");
        // Silicon line.
        recipe(items, MachineKind.LITHOGRAPH, "slice").input(MaterialTags.SILICON, 1)
                .output(MaterialContent.SILICON_WAFER.get(), 3)
                .output(MaterialContent.SILICON_KERF.get(), 1, ItemOutput.ALWAYS / 4).energy(8, NORMAL)
                .save(output, id(MachineKind.LITHOGRAPH, "silicon_wafer"));
        recipe(items, MachineKind.LITHOGRAPH, "dope").input(wafer, 1).input(MaterialTags.REDSTONE, 1)
                .output(MaterialContent.DOPED_WAFER.get(), 1).energy(10, NORMAL)
                .save(output, id(MachineKind.LITHOGRAPH, "doped_wafer"));
        recipe(items, MachineKind.LITHOGRAPH, "print").input(doped, 1).catalyst(MaterialTags.component("logic_mask"))
                .output(MaterialContent.LOGIC_DIE.get(), 2).energy(12, SLOW)
                .save(output, id(MachineKind.LITHOGRAPH, "logic_die"));
        // Crystal line.
        recipe(items, MachineKind.LITHOGRAPH, "slice").input(MaterialTags.LUMEN_QUARTZ, 1)
                .output(MaterialContent.OSCILLATOR.get(), 2).energy(8, NORMAL)
                .save(output, id(MachineKind.LITHOGRAPH, "oscillator"));
        // Metal line.
        recipe(items, MachineKind.FABRICATOR, "draw").input(MaterialTags.COPPER_INGOT, 1)
                .output(MaterialContent.COPPER_WIRE.get(), 3).energy(6, FAST)
                .save(output, id(MachineKind.FABRICATOR, "copper_wire"));
        recipe(items, MachineKind.FABRICATOR, "draw").input(MaterialTags.GLASS_BLOCKS, 1)
                .output(MaterialContent.GLASS_FIBRE.get(), 2).energy(6, FAST)
                .save(output, id(MachineKind.FABRICATOR, "glass_fibre"));
        recipe(items, MachineKind.FABRICATOR, "press").input(MaterialTags.SIGNAL_ALLOY_INGOT, 1)
                .output(MaterialContent.CASING_PLATE.get(), 1).energy(10, NORMAL)
                .save(output, id(MachineKind.FABRICATOR, "casing_plate"));
        recipe(items, MachineKind.FABRICATOR, "press").input(MaterialTags.LUMEN_QUARTZ, 1)
                .output(MaterialContent.LUMEN_QUARTZ_DUST.get(), 1).energy(6, FAST)
                .save(output, id(MachineKind.FABRICATOR, "lumen_quartz_dust"));
        TagKey<Item> fibre = MaterialTags.component("glass_fibre");
        recipe(items, MachineKind.FABRICATOR, "press").input(fibre, 2).input(MaterialTags.SLIME_BALLS, 1)
                .output(MaterialContent.CIRCUIT_BOARD.get(), 1).energy(10, NORMAL)
                .save(output, id(MachineKind.FABRICATOR, "circuit_board"));
        recipe(items, MachineKind.FABRICATOR, "press").input(fibre, 2).input(Items.HONEYCOMB, 1)
                .output(MaterialContent.CIRCUIT_BOARD.get(), 1).energy(10, NORMAL)
                .save(output, id(MachineKind.FABRICATOR, "circuit_board_from_honeycomb"));
        // Tantalum line: refining, then the furnace (TierTwoMaterialContent's chains), then capacitors and wire.
        recipe(items, MachineKind.FABRICATOR, "refine").input(MaterialTags.RAW_TANTALITE, 1)
                .output(TierTwoMaterialContent.TANTALUM_DUST.get(), 1)
                .output(TierTwoMaterialContent.NIOBIUM_DUST.get(), 1, NIOBIUM_CHANCE).energy(10, NORMAL)
                .save(output, id(MachineKind.FABRICATOR, "tantalum_dust"));
        // A silk-touched ore refines like raw tantalite, so silk touch gains nothing over fortune.
        recipe(items, MachineKind.FABRICATOR, "refine").input(MaterialTags.TANTALITE_ORES, 1)
                .output(TierTwoMaterialContent.TANTALUM_DUST.get(), 1)
                .output(TierTwoMaterialContent.NIOBIUM_DUST.get(), 1, NIOBIUM_CHANCE).energy(10, NORMAL)
                .save(output, id(MachineKind.FABRICATOR, "tantalum_dust_from_ore"));
        recipe(items, MachineKind.FABRICATOR, "press").input(MaterialTags.TANTALUM_INGOT, 1)
                .input(MaterialTags.COPPER_WIRE, 1).output(TierTwoMaterialContent.TANTALUM_CAPACITOR.get(), 2)
                .energy(12, NORMAL).research(ResearchGates.ADVANCED_PROCESSORS)
                .save(output, id(MachineKind.FABRICATOR, "tantalum_capacitor"));
        recipe(items, MachineKind.FABRICATOR, "draw").input(MaterialTags.NIOBIUM_INGOT, 1)
                .output(TierTwoMaterialContent.SUPERCONDUCTOR_WIRE.get(), 2).energy(12, NORMAL)
                .save(output, id(MachineKind.FABRICATOR, "superconductor_wire"));
        // Advanced doping: glowstone instead of redstone.
        recipe(items, MachineKind.LITHOGRAPH, "dope").input(wafer, 1).input(MaterialTags.GLOWSTONE, 1)
                .output(TierTwoMaterialContent.ADVANCED_DOPED_WAFER.get(), 1).energy(14, NORMAL)
                .research(ResearchGates.ADVANCED_PROCESSORS)
                .save(output, id(MachineKind.LITHOGRAPH, "advanced_doped_wafer"));
        TagKey<Item> advanced = MaterialTags.component("advanced_doped_wafer");
        // Dies and processors of each family; each family prints with its own mask. A family mask's research gates
        // printing with it (the advanced doped wafer it prints is gated already); advanced assembly needs advanced
        // processors.
        for (Family family : Family.ALL) {
            TagKey<Item> mask = MaterialTags.component(family.id + "_mask");
            if (!family.equals(Family.LOGIC)) {
                recipe(items, MachineKind.LITHOGRAPH, "print").input(doped, 1).catalyst(mask)
                        .output(family.die.get(), 2).energy(12, SLOW).research(family.gate)
                        .save(output, id(MachineKind.LITHOGRAPH, family.id + "_die"));
            }
            recipe(items, MachineKind.LITHOGRAPH, "print").input(advanced, 1).catalyst(mask)
                    .output(family.advancedDie.get(), 2).energy(16, SLOW).research(family.gate)
                    .save(output, id(MachineKind.LITHOGRAPH, "advanced_" + family.id + "_die"));
            processor(items, family.id + "_die", null).output(family.basic.get(), 1).energy(12, SLOW).save(output,
                    id(MachineKind.ASSEMBLER, "basic_" + family.id + "_processor"));
            processor(items, "advanced_" + family.id + "_die", MaterialTags.component("tantalum_capacitor"))
                    .output(family.advanced.get(), 1).energy(16, SLOW).research(ResearchGates.ADVANCED_PROCESSORS)
                    .save(output, id(MachineKind.ASSEMBLER, "advanced_" + family.id + "_processor"));
        }
        elite(items, output);
        // M6f: void crystal dopes a data crystal wafer into the void lattice, the base of memory V and VI and the void
        // module.
        recipe(items, MachineKind.LITHOGRAPH, "dope").input(MaterialTags.component("data_crystal_wafer"), 1)
                .input(MaterialTags.VOID_CRYSTAL, 2).output(VoidCrystalContent.VOID_LATTICE.get(), 1).energy(24, SLOW)
                .research(ResearchGates.VOID_LATTICE).save(StorageModules.gate(output, StorageModules.VOID_GEODES),
                        id(MachineKind.LITHOGRAPH, "void_lattice"));
    }

    /**
     * Elite processors (M6d): a data crystal shard slices into crystal wafers, ember quartz dopes them, each family's
     * mask prints elite dies, and an elite die with superconductor wire, a tantalum capacitor and a board makes the
     * elite processor. Every step past slicing needs the elite processors research.
     */
    private static void elite(HolderGetter<Item> items, RecipeOutput output) {
        recipe(items, MachineKind.LITHOGRAPH, "slice").input(MaterialTags.DATA_CRYSTAL, 1)
                .output(DataCrystalContent.DATA_CRYSTAL_WAFER.get(), 2).energy(16, NORMAL)
                .save(output, id(MachineKind.LITHOGRAPH, "data_crystal_wafer"));
        recipe(items, MachineKind.LITHOGRAPH, "dope").input(MaterialTags.component("data_crystal_wafer"), 1)
                .input(MaterialTags.EMBER_QUARTZ, 1).output(DataCrystalContent.EMBER_DOPED_WAFER.get(), 1)
                .energy(18, NORMAL).research(ResearchGates.ELITE_PROCESSORS)
                .save(output, id(MachineKind.LITHOGRAPH, "ember_doped_wafer"));
        TagKey<Item> ember = MaterialTags.component("ember_doped_wafer");
        for (Family family : Family.ALL) {
            recipe(items, MachineKind.LITHOGRAPH, "print").input(ember, 1)
                    .catalyst(MaterialTags.component(family.id + "_mask")).output(family.eliteDie.get(), 2)
                    .energy(20, SLOW).research(ResearchGates.ELITE_PROCESSORS)
                    .save(output, id(MachineKind.LITHOGRAPH, "elite_" + family.id + "_die"));
            recipe(items, MachineKind.ASSEMBLER, null).input(MaterialTags.component("elite_" + family.id + "_die"), 1)
                    .input(MaterialTags.component("superconductor_wire"), 2)
                    .input(MaterialTags.component("tantalum_capacitor"), 1)
                    .input(MaterialTags.component("circuit_board"), 1).output(family.elite.get(), 1).energy(20, SLOW)
                    .research(ResearchGates.ELITE_PROCESSORS)
                    .save(output, id(MachineKind.ASSEMBLER, "elite_" + family.id + "_processor"));
        }
        // Budding data crystal (M6d follow-up): a late-game way to farm crystal without finding a geode, priced so a
        // geode stays the cheap route. Off with the data geodes module, as the block itself.
        recipe(items, MachineKind.ASSEMBLER, null).input(MaterialTags.DATA_CRYSTAL, 32)
                .input(MaterialTags.component("elite_logic_processor"), 1)
                .input(MaterialTags.component("elite_memory_processor"), 1)
                .input(MaterialTags.component("elite_signal_processor"), 1)
                .input(MaterialTags.component("superconductor_wire"), 8).input(MaterialTags.EMBER_QUARTZ, 16)
                .output(DataCrystalContent.BUDDING_DATA_CRYSTAL.get().asItem(), 1).energy(40, SLOW * 5)
                .research(ResearchGates.ELITE_PROCESSORS).save(StorageModules.gate(output, StorageModules.DATA_GEODES),
                        id(MachineKind.ASSEMBLER, "budding_data_crystal"));
    }

    /** A processor's assembly: its die, two insulated wires, an oscillator, a board and, if given, one more part. */
    private static StorageMachineRecipeBuilder processor(HolderGetter<Item> items, String die,
            @Nullable TagKey<Item> extra) {
        StorageMachineRecipeBuilder builder = recipe(items, MachineKind.ASSEMBLER, null)
                .input(MaterialTags.component(die), 1).input(MaterialTags.component("insulated_copper_wire"), 2)
                .input(MaterialTags.component("oscillator"), 1).input(MaterialTags.component("circuit_board"), 1);
        if (extra != null) {
            builder.input(extra, 1);
        }
        return builder;
    }

    private static StorageMachineRecipeBuilder recipe(HolderGetter<Item> items, MachineKind kind,
            @Nullable String mode) {
        StorageMachineRecipeBuilder builder = StorageMachineRecipeBuilder.of(kind, items);
        return mode == null ? builder : builder.mode(mode);
    }

    private static Identifier id(MachineKind kind, String name) {
        return MaxtechStorage.id(kind.id() + "/" + name);
    }

    /**
     * One processor family: its basic, advanced and elite dies and processors, and the research its mask needs (the
     * logic mask's is advanced processors, as the logic mask itself is the start node).
     */
    private record Family(String id, Supplier<Item> die, Supplier<Item> advancedDie, Supplier<Item> basic,
            Supplier<Item> advanced, Identifier gate, Supplier<Item> eliteDie, Supplier<Item> elite) {
        static final Family LOGIC = new Family("logic", MaterialContent.LOGIC_DIE,
                TierTwoMaterialContent.ADVANCED_LOGIC_DIE, MaterialContent.BASIC_LOGIC_PROCESSOR,
                TierTwoMaterialContent.ADVANCED_LOGIC_PROCESSOR, ResearchGates.ADVANCED_PROCESSORS,
                DataCrystalContent.ELITE_LOGIC_DIE, DataCrystalContent.ELITE_LOGIC_PROCESSOR);
        static final List<Family> ALL = List.of(LOGIC,
                new Family("memory", TierTwoMaterialContent.MEMORY_DIE, TierTwoMaterialContent.ADVANCED_MEMORY_DIE,
                        MaterialContent.BASIC_MEMORY_PROCESSOR, TierTwoMaterialContent.ADVANCED_MEMORY_PROCESSOR,
                        ResearchGates.MEMORY_MASK, DataCrystalContent.ELITE_MEMORY_DIE,
                        DataCrystalContent.ELITE_MEMORY_PROCESSOR),
                new Family("signal", TierTwoMaterialContent.SIGNAL_DIE, TierTwoMaterialContent.ADVANCED_SIGNAL_DIE,
                        MaterialContent.BASIC_SIGNAL_PROCESSOR, TierTwoMaterialContent.ADVANCED_SIGNAL_PROCESSOR,
                        ResearchGates.SIGNAL_MASK, DataCrystalContent.ELITE_SIGNAL_DIE,
                        DataCrystalContent.ELITE_SIGNAL_PROCESSOR),
                new Family("io", TierTwoMaterialContent.IO_DIE, TierTwoMaterialContent.ADVANCED_IO_DIE,
                        MaterialContent.BASIC_IO_PROCESSOR, TierTwoMaterialContent.ADVANCED_IO_PROCESSOR,
                        ResearchGates.IO_MASK, DataCrystalContent.ELITE_IO_DIE, DataCrystalContent.ELITE_IO_PROCESSOR));
    }
}
