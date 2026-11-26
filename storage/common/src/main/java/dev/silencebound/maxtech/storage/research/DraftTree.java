package dev.silencebound.maxtech.storage.research;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.machine.MachineKind;
import dev.silencebound.maxtech.storage.research.ResearchOption.Points;
import dev.silencebound.maxtech.storage.research.ResearchOption.Puzzle;
import dev.silencebound.maxtech.storage.research.ResearchOption.Task;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * The research tree Storage ships (M6b draft tree, owner-approved 2026-10-03), written to data by datagen and named in
 * the lang file. Numbers are the standard preset's; the preset scales them. Data, not config: a pack edits the tree
 * through its data pack (or KubeJS), which replaces these files.
 */
public final class DraftTree {
    /** One shipped node. */
    public record Draft(Identifier id, String name, ResearchNode node) {
    }

    private static final Identifier SIGNAL_ROUTING = MaxtechStorage.id("signal_routing");
    private static final Identifier MASK_ALIGNMENT = MaxtechStorage.id("mask_alignment");
    private static final Identifier BYTE_PACKING = MaxtechStorage.id("byte_packing");
    private static final Identifier WAVEFORM_TUNING = MaxtechStorage.id("waveform_tuning");
    private static final TagKey<Item> EMBER_QUARTZ = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath("c", "gems/ember_quartz"));
    private static final TagKey<Item> DATA_CRYSTAL = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath("c", "gems/data_crystal"));
    private static final TagKey<Item> VOID_CRYSTAL = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath("c", "gems/void_crystal"));

    public static final List<Draft> NODES = List.of(
            node("logic_mask", "Logic Mask", "processors", 0, 1, "logic_mask", List.of(), List.of(),
                    List.of(MaxtechStorage.id("logic_mask"))),
            node("family_masks", "Family Masks", "processors", 1, 2, "logic_mask", List.of("logic_mask"),
                    List.of(Task.runs(MachineKind.LITHOGRAPH, 32), new Puzzle(MASK_ALIGNMENT, 0), new Points(100)),
                    List.of(ResearchGates.MEMORY_MASK, ResearchGates.SIGNAL_MASK, ResearchGates.IO_MASK)),
            node("advanced_doping", "Advanced Doping", "processors", 2, 2, "doped_wafer", List.of("family_masks"),
                    List.of(Task.of(TaskType.PROCESSORS_MADE, 16), new Puzzle(SIGNAL_ROUTING, 1), new Points(250)),
                    List.of(ResearchGates.ADVANCED_PROCESSORS)),
            node("precision_module", "Precision Module", "processors", 3, 2, "oscillator", List.of("advanced_doping"),
                    List.of(Task.of(TaskType.MACHINE_RUNS, 256), Task.of(TaskType.REFINE_AMOUNT, 64), new Points(400)),
                    List.of(ResearchGates.PRECISION_MODULE)),
            node("memory_2", "Memory II", "memory", 0, 2, "memory_module_1", List.of("logic_mask"),
                    List.of(Task.of(TaskType.STORE_ITEMS, 4096), new Puzzle(BYTE_PACKING, 0), new Points(150)),
                    List.of(ResearchGates.MEMORY_MODULE_2)),
            node("medium_casing", "Medium Casing", "memory", 1, 2, "casing_plate", List.of("memory_2"),
                    List.of(Task.kinds(0.01, 100), Task.of(TaskType.CELL_FILL, 75), new Points(150)),
                    List.of(ResearchGates.MEDIUM_CASING)),
            node("memory_3", "Memory III", "memory", 2, 2, "memory_module_1",
                    List.of("medium_casing", "advanced_doping"),
                    List.of(Task.of(TaskType.STORE_ITEMS, 32_768), new Puzzle(BYTE_PACKING, 2), new Points(400)), List
                            .of(ResearchGates.MEMORY_MODULE_3)),
            node("large_casings", "Large and Bulk Casings", "memory", 3, 3, "casing_plate", List.of("memory_3"),
                    List.of(Task.kinds(0.03, 300), Task.of(TaskType.DRIVES_RUNNING, 4), new Points(500)),
                    List.of(ResearchGates.LARGE_CASING, ResearchGates.BULK_CASING)),
            node("partition", "Partition", "cells", 0, 2, "small_storage_cell", List.of("memory_2"),
                    List.of(Task.items(TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("c", "ores")),
                            256), Task.of(TaskType.CELL_FILL, 90), new Points(150)),
                    List.of(ResearchGates.PARTITION_ADDON)),
            node("void_and_lock", "Void and Lock", "cells", 1, 2, "small_storage_cell", List.of("partition"),
                    List.of(Task.of(TaskType.PORTS_THROUGHPUT, 10_000), new Puzzle(WAVEFORM_TUNING, 1),
                            new Points(300)),
                    List.of(ResearchGates.VOID_ADDON, ResearchGates.LOCK_ADDON)),
            // The id predates the priority add-on's retirement and stays, so saved research progress keeps it.
            node("priority_and_portable", "Portable Cells", "cells", 2, 3, "small_storage_cell",
                    List.of("void_and_lock"),
                    List.of(Task.of(TaskType.DRIVES_RUNNING, 3), Task.of(TaskType.STORE_ITEMS, 65_536),
                            new Points(400)),
                    List.of(ResearchGates.PORTABLE_CASING)),
            node("port_tier_2", "Port Tier 2", "network", 0, 2, "storage_port_tier_1", List.of("logic_mask"),
                    List.of(Task.of(TaskType.PORTS_THROUGHPUT, 2048), new Puzzle(SIGNAL_ROUTING, 0), new Points(150)),
                    List.of(ResearchGates.PORT_TIER_2)),
            node("subnets", "Subnets", "network", 1, 4, "storage_controller", List.of("port_tier_2"),
                    List.of(Task.of(TaskType.DRIVES_RUNNING, 4), Task.of(TaskType.PORTS_THROUGHPUT, 50_000),
                            new Points(500)),
                    List.of(ResearchGates.SUBNET_CONTROLLER)),
            node("antennas", "Antennas", "network", 2, 3, "antenna_tier_1", List.of("port_tier_2"),
                    List.of(Task.kinds(0.04, 400), new Puzzle(WAVEFORM_TUNING, 2), new Points(600)),
                    List.of(ResearchGates.ANTENNA)),
            node("wireless", "Wireless", "network", 3, 3, "wireless_terminal", List.of("antennas"),
                    List.of(Task.of(TaskType.PORTS_THROUGHPUT, 100_000), new Points(800)),
                    List.of(ResearchGates.WIRELESS_TERMINAL)),
            node("port_tier_3", "Port Tier 3", "network", 4, 2, "storage_port_tier_2",
                    List.of("port_tier_2", "advanced_doping"),
                    List.of(Task.of(TaskType.PORTS_THROUGHPUT, 20_000), new Puzzle(SIGNAL_ROUTING, 2), new Points(500)),
                    List.of(ResearchGates.PORT_TIER_3)),
            node("condition_switch", "Condition Switch", "network", 12, 2, "condition_switch", List.of("logic_mask"),
                    List.of(Task.of(TaskType.PORTS_THROUGHPUT, 1024), new Puzzle(SIGNAL_ROUTING, 0), new Points(100)),
                    List.of(ResearchGates.CONDITION_SWITCH)),
            node("interface", "Interface", "network", 5, 2, "storage_interface", List.of("port_tier_2"),
                    List.of(Task.of(TaskType.PORTS_THROUGHPUT, 5000), new Puzzle(SIGNAL_ROUTING, 1), new Points(250)),
                    List.of(ResearchGates.INTERFACE)),
            node("energy_cells_2", "Energy Cells II", "network", 10, 2, "energy_cell_tier_1", List.of("logic_mask"),
                    List.of(Task.of(TaskType.PORTS_THROUGHPUT, 4096), Task.of(TaskType.DRIVES_RUNNING, 2),
                            new Points(200)),
                    List.of(ResearchGates.ENERGY_CELL_TIER_2)),
            node("energy_cells_3", "Energy Cells III", "network", 11, 3, "energy_cell_tier_2",
                    List.of("energy_cells_2", "advanced_doping"),
                    List.of(Task.of(TaskType.PORTS_THROUGHPUT, 50_000), Task.of(TaskType.DRIVES_RUNNING, 4),
                            new Points(500)),
                    List.of(ResearchGates.ENERGY_CELL_TIER_3)),
            // The multiblock controller (M7R): controller.size.base blocks without research, then step1 to step3
            // after these three nodes, each a tier above the last, asking for a busier network than the one before.
            // The steps stay in the network branch: each needs only the step before (and step II port tier 2).
            node("controller_size_1", "Controller Size I", "network", 5, 2, "storage_controller", List.of("logic_mask"),
                    List.of(Task.of(TaskType.DRIVES_RUNNING, 2), Task.of(TaskType.PORTS_THROUGHPUT, 4096),
                            new Points(200)),
                    List.of(ResearchGates.CONTROLLER_SIZE_1)),
            node("controller_size_2", "Controller Size II", "network", 6, 3, "storage_controller",
                    List.of("controller_size_1", "port_tier_2"),
                    List.of(Task.of(TaskType.DRIVES_RUNNING, 4), Task.of(TaskType.PORTS_THROUGHPUT, 25_000),
                            new Puzzle(SIGNAL_ROUTING, 1), new Points(400)),
                    List.of(ResearchGates.CONTROLLER_SIZE_2)),
            node("controller_size_3", "Controller Size III", "network", 7, 4, "storage_controller",
                    List.of("controller_size_2"),
                    List.of(Task.of(TaskType.DRIVES_RUNNING, 8), Task.of(TaskType.PORTS_THROUGHPUT, 100_000),
                            new Points(700)),
                    List.of(ResearchGates.CONTROLLER_SIZE_3)),
            // The controller's card slots (M7R item 2), on a par with the tier-2 ports: the batch amplifier card needs
            // nothing more, the efficiency core and surge regulator cards Efficient Power.
            node("controller_cards", "Upgrade Cards", "network", 19, 2, "batch_amplifier_card", List.of("logic_mask"),
                    List.of(Task.of(TaskType.PORTS_THROUGHPUT, 4096), new Puzzle(MASK_ALIGNMENT, 0), new Points(150)),
                    List.of(ResearchGates.CONTROLLER_CARDS)),
            node("p2p_links", "P2P Links", "network", 8, 3, "p2p_item_link", List.of("antennas"),
                    List.of(Task.of(TaskType.PORTS_THROUGHPUT, 25_000), new Puzzle(WAVEFORM_TUNING, 1),
                            new Points(500)),
                    List.of(ResearchGates.P2P_LINKS)),
            node("world_devices", "World Devices", "network", 13, 2, "storage_chest", List.of("logic_mask"),
                    List.of(Task.of(TaskType.PORTS_THROUGHPUT, 4096), new Puzzle(SIGNAL_ROUTING, 0), new Points(150)),
                    List.of(ResearchGates.NETWORK_CHEST, ResearchGates.COLLECTOR)),
            node("placer_and_breaker", "Placer and Breaker", "network", 14, 2, "storage_port_tier_1",
                    List.of("world_devices"),
                    List.of(Task.of(TaskType.PORTS_THROUGHPUT, 16_384), Task.kinds(0.02, 200), new Points(300)),
                    List.of(ResearchGates.PLACER, ResearchGates.BREAKER)),
            node("holo_displays", "Holographic Displays", "network", 15, 4, "display_panel",
                    List.of("port_tier_2", "memory_3"),
                    List.of(Task.of(TaskType.DRIVES_RUNNING, 4), Task.of(TaskType.STORE_ITEMS, 65_536),
                            new Points(600)),
                    List.of(MaxtechStorage.id("holo_graphs"))),
            // The controller's built-in views (M7R item 3), a node each: telemetry first, then the ledger and the
            // redstone alarm rules.
            node("network_telemetry", "Network Telemetry", "network", 20, 2, "network_analyzer", List.of("logic_mask"),
                    List.of(Task.of(TaskType.DRIVES_RUNNING, 2), Task.of(TaskType.PORTS_THROUGHPUT, 2048),
                            new Points(150)),
                    List.of(ResearchGates.TELEMETRY_RECORDER)),
            node("network_ledger", "Network Ledger", "network", 21, 2, "storage_terminal", List.of("network_telemetry"),
                    List.of(Task.of(TaskType.PORTS_THROUGHPUT, 10_000), Task.of(TaskType.STORE_ITEMS, 16_384),
                            new Points(250)),
                    List.of(ResearchGates.LEDGER)),
            // The id predates the alarm rules (it unlocked the alarm module) and stays, so saved progress keeps it.
            node("network_tuning", "Redstone Alarms", "network", 16, 2, "condition_switch",
                    List.of("network_telemetry", "port_tier_2"),
                    List.of(Task.of(TaskType.PORTS_THROUGHPUT, 10_000), new Puzzle(SIGNAL_ROUTING, 1), new Points(250)),
                    List.of(ResearchGates.ALARM_MODULE)),
            // Power shaping once the controller has card slots and the network runs on energy cells.
            node("efficient_power", "Efficient Power", "network", 17, 2, "efficiency_core_card",
                    List.of("controller_cards", "energy_cells_2"),
                    List.of(Task.of(TaskType.DRIVES_RUNNING, 4), Task.of(TaskType.PORTS_THROUGHPUT, 25_000),
                            new Points(350)),
                    List.of(ResearchGates.EFFICIENCY_CORE, ResearchGates.SURGE_REGULATOR)),
            // Keeping a network running unattended and deciding who may use it: the anchor and security module.
            node("network_oversight", "Network Oversight", "network", 18, 3, "controller_security_module",
                    List.of("network_tuning"),
                    List.of(Task.of(TaskType.DRIVES_RUNNING, 4), Task.of(TaskType.PORTS_THROUGHPUT, 50_000),
                            new Points(500)),
                    List.of(ResearchGates.NETWORK_ANCHOR, ResearchGates.SECURITY_MODULE)),
            // M6d materials: ember quartz from the Nether opens the machine modules beyond precision; deep data
            // crystal from the geodes opens elite processors, and those memory IV.
            node("ember_modules", "Ember Modules", "processors", 4, 4, "ember_quartz", List.of("precision_module"),
                    List.of(Task.items(EMBER_QUARTZ, 256), new Puzzle(WAVEFORM_TUNING, 1), new Points(500)),
                    List.of(ResearchGates.SPEED_MODULE, ResearchGates.EFFICIENCY_MODULE)),
            node("elite_processors", "Elite Processors", "processors", 5, 5, "data_crystal", List.of("advanced_doping"),
                    List.of(Task.items(DATA_CRYSTAL, 64), new Puzzle(SIGNAL_ROUTING, 2), new Points(800)),
                    List.of(ResearchGates.ELITE_PROCESSORS)),
            node("memory_4", "Memory IV", "memory", 4, 5, "memory_module_4", List.of("memory_3", "elite_processors"),
                    List.of(Task.of(TaskType.STORE_ITEMS, 262_144), new Puzzle(BYTE_PACKING, 2), new Points(1000)),
                    List.of(ResearchGates.MEMORY_MODULE_4)),
            // M6f, tier 5 "Endgame": void crystal from the outer End islands opens the Void branch; everything in it
            // asks the hard waveform tuning or byte packing puzzle as one way through.
            node("void_crystal", "Void Crystal", "void", 0, 5, "void_crystal", List.of("elite_processors"),
                    List.of(Task.items(VOID_CRYSTAL, 64), new Puzzle(WAVEFORM_TUNING, 2), new Points(1200)),
                    List.of(ResearchGates.VOID_LATTICE)),
            node("memory_5", "Memory V", "void", 1, 5, "memory_module_5", List.of("void_crystal", "memory_4"),
                    List.of(Task.of(TaskType.STORE_ITEMS, 1_048_576), new Puzzle(BYTE_PACKING, 2), new Points(1500)),
                    List.of(ResearchGates.MEMORY_MODULE_5)),
            node("memory_6", "Memory VI", "void", 2, 5, "memory_module_6", List.of("memory_5"),
                    List.of(Task.of(TaskType.STORE_ITEMS, 8_388_608), new Puzzle(BYTE_PACKING, 2), new Points(2500)),
                    List.of(ResearchGates.MEMORY_MODULE_6)),
            node("bulk_drives", "Bulk Drives", "void", 3, 5, "void_lattice", List.of("void_crystal", "large_casings"),
                    List.of(Task.of(TaskType.DRIVES_RUNNING, 8), new Puzzle(BYTE_PACKING, 2), new Points(1500)),
                    List.of(ResearchGates.BULK_DRIVE)),
            node("dimension_bridge", "Dimension Bridge", "void", 4, 5, "void_lattice",
                    List.of("void_crystal", "wireless"),
                    List.of(Task.of(TaskType.PORTS_THROUGHPUT, 250_000), new Puzzle(WAVEFORM_TUNING, 2),
                            new Points(1500)),
                    List.of(ResearchGates.DIMENSION_BRIDGE)),
            node("void_modules", "Void Modules", "void", 5, 5, "void_module", List.of("void_crystal", "ember_modules"),
                    List.of(Task.of(TaskType.MACHINE_RUNS, 2048), new Puzzle(WAVEFORM_TUNING, 2), new Points(1500)),
                    List.of(ResearchGates.VOID_MODULE)),
            // M7, Crafting: patterns and the first CPU tier are open from the start (the root grants the tier-1 cap,
            // so a pack can gate it by giving the node options); the rest asks the mask or byte puzzle as one way.
            node("autocrafting", "Autocrafting", "crafting", 0, 2, "blank_pattern", List.of("logic_mask"), List.of(),
                    List.of(ResearchGates.CPU_TIER_1)),
            node("crafting_card", "Crafting Card", "crafting", 1, 2, "crafting_card",
                    List.of("autocrafting", "port_tier_2"),
                    List.of(Task.of(TaskType.PORTS_THROUGHPUT, 8192), new Puzzle(MASK_ALIGNMENT, 1), new Points(300)),
                    List.of(ResearchGates.CRAFTING_CARD)),
            node("provider_slots", "Larger Providers", "crafting", 2, 3, "blank_pattern", List.of("autocrafting"),
                    List.of(Task.of(TaskType.MACHINE_RUNS, 512), new Puzzle(BYTE_PACKING, 1), new Points(400)),
                    List.of(ResearchGates.PROVIDER_SLOTS)),
            node("stock_keeping", "Stock Keeping", "crafting", 3, 3, "stock_keeper",
                    List.of("autocrafting", "interface"),
                    List.of(Task.of(TaskType.STORE_ITEMS, 65_536), new Puzzle(BYTE_PACKING, 1), new Points(500)),
                    List.of(ResearchGates.STOCK_KEEPER)),
            node("cpu_tier_2", "CPU Tier 2", "crafting", 4, 4, "data_crystal",
                    List.of("autocrafting", "elite_processors"),
                    List.of(Task.items(DATA_CRYSTAL, 128), new Puzzle(MASK_ALIGNMENT, 2), new Points(900)),
                    List.of(ResearchGates.CPU_TIER_2)),
            node("cpu_tier_3", "CPU Tier 3", "crafting", 5, 5, "void_crystal", List.of("cpu_tier_2", "void_crystal"),
                    List.of(Task.items(VOID_CRYSTAL, 128), new Puzzle(BYTE_PACKING, 2), new Points(1500)),
                    List.of(ResearchGates.CPU_TIER_3)));

    /** The branch names, in screen order. */
    public static final List<String> BRANCHES = List.of("processors", "memory", "cells", "network", "void", "crafting");

    private DraftTree() {
    }

    private static Draft node(String path, String name, String branch, int order, int tier, String icon,
            List<String> requires, List<ResearchOption> options, List<Identifier> unlocks) {
        return new Draft(MaxtechStorage.id(path), name,
                new ResearchNode(branch, order, tier, Optional.of(MaxtechStorage.id(icon)),
                        requires.stream().map(MaxtechStorage::id).toList(), options, unlocks));
    }

    /** The tier ladder's names and materials (PRD 7.2), for the progression panel. */
    public static final List<String> TIERS = List.of("Local", "Network", "Base", "Reach", "Big Storage", "Endgame");
    public static final List<String> TIER_MATERIALS = List.of("Lumen quartz and copper", "Lumen quartz and silicon",
            "Tantalite", "Ember quartz and sculk", "Deep data crystal and niobium", "Void crystal");

    /** The English names of the unlock ids the shipped tree grants. */
    public static String unlockName(Identifier unlock) {
        String path = unlock.getPath();
        // Ids that kept the name of what they unlocked before the multiblock controller (M7R).
        switch (path) {
            case "telemetry_recorder" -> {
                return "Telemetry Graphs";
            }
            case "ledger" -> {
                return "Network Ledger";
            }
            case "alarm_module" -> {
                return "Redstone Alarm Rules";
            }
            case "controller_cards" -> {
                return "Upgrade Card Slots";
            }
            case "efficiency_core" -> {
                return "Efficiency Core Card";
            }
            case "surge_regulator" -> {
                return "Surge Regulator Card";
            }
            default -> {
            }
        }
        StringBuilder name = new StringBuilder();
        for (String word : path.split("_", -1)) {
            if (!name.isEmpty()) {
                name.append(' ');
            }
            name.append(word.equals("io") ? "I/O" : Character.toUpperCase(word.charAt(0)) + word.substring(1));
        }
        return name.toString().replace("Module 2", "Module II").replace("Module 3", "Module III")
                .replace("Module 4", "Module IV").replace("Module 5", "Module V").replace("Module 6", "Module VI")
                .replace("Addon", "Add-on").replace("Cpu", "CPU").replace("Size 1", "Size I")
                .replace("Size 2", "Size II").replace("Size 3", "Size III");
    }

    /** The English name of a branch. */
    public static String branchName(String branch) {
        return switch (branch) {
            case "processors" -> "Processors";
            case "memory" -> "Memory";
            case "cells" -> "Cells";
            case "network" -> "Network and Signal";
            case "void" -> "Void";
            case "crafting" -> "Crafting";
            default -> branch;
        };
    }
}
