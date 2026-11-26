package dev.silencebound.maxtech.storage.config;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.guide.GuideConfigValues;
import dev.silencebound.maxtech.lib.config.PresetConfig;
import dev.silencebound.maxtech.lib.platform.ConfigBuilder;
import dev.silencebound.maxtech.lib.platform.ConfigFile;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.job.CraftLimits;
import dev.silencebound.maxtech.storage.crafting.job.CraftingMode;
import dev.silencebound.maxtech.storage.crafting.provider.DispatchStrategy;
import dev.silencebound.maxtech.storage.logic.ForEach;
import dev.silencebound.maxtech.storage.logic.controller.GraphSlot;
import dev.silencebound.maxtech.storage.logic.controller.HistoryEntry;
import dev.silencebound.maxtech.storage.logic.controller.SharedVariable;
import dev.silencebound.maxtech.storage.network.ChannelMode;
import dev.silencebound.maxtech.storage.network.Channels;
import dev.silencebound.maxtech.storage.port.PortCards;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import net.minecraft.resources.Identifier;
import dev.silencebound.maxtech.storage.research.ResearchPreset;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Storage's server config. Defaults are the built-in {@code standalone} preset in {@code maxtech_storage/presets/};
 * values are declared on {@code BUILDER} in the static initialiser, before {@link #register()}. Every value is read on
 * the server thread and may change on reload; while the file is not loaded (a client outside a world, such as the
 * guide's scenes) every value reads as its default ({@link LoadedOrDefault}).
 */
public final class StorageServerConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final PresetConfig CONFIG = new PresetConfig(MaxtechStorage.MOD_ID, StorageServerConfig.class);
    private static final ConfigBuilder BUILDER = new LoadedOrDefault(CONFIG.builder(), StorageServerConfig::loaded);

    public static final Supplier<String> PRESET = CONFIG.preset();

    /** Channels a tier-1 storage cable carries ({@code network.channels.tier1}). */
    public static final IntSupplier CABLE_CHANNELS_TIER_1;
    /** Channels a tier-2 storage cable carries ({@code network.channels.tier2}). */
    public static final IntSupplier CABLE_CHANNELS_TIER_2;
    /** Channels each root link of a controller carries ({@code network.channels.rootLink}). */
    public static final IntSupplier ROOT_LINK_CHANNELS;
    /** Channels a network without a controller has in all ({@code network.channels.adHoc}). */
    public static final IntSupplier AD_HOC_CHANNELS;
    /** Most items one operation moves ({@code network.batch.items}). */
    public static final IntSupplier BATCH_ITEMS;
    /** Most mB of fluid one operation moves ({@code network.batch.fluidMb}). */
    public static final IntSupplier BATCH_FLUID_MB;
    /** FE every operation costs on top of what it moves ({@code network.energy.perOperation}). */
    public static final LongSupplier ENERGY_PER_OPERATION;
    /** FE per item moved, or per {@link #ENERGY_FLUID_MB_PER_UNIT} mB ({@code network.energy.perUnit}). */
    public static final LongSupplier ENERGY_PER_UNIT;
    /** mB of fluid that count as one unit for {@link #ENERGY_PER_UNIT} ({@code network.energy.fluidMbPerUnit}). */
    public static final IntSupplier ENERGY_FLUID_MB_PER_UNIT;
    /** Most nodes in one zone ({@code network.zoneSize}). */
    public static final IntSupplier ZONE_SIZE;
    /**
     * MU a network without a controller can hold ({@code network.adHoc.bufferMu}). This and the other keys MLE item 4
     * renamed from FE read their old key while it is set ({@link MovedKeys}); the raw new key is the {@code *_KEY}
     * value.
     */
    public static final LongSupplier AD_HOC_BUFFER_MU;
    public static final LongSupplier AD_HOC_BUFFER_MU_KEY;
    /** MU each controller block holds ({@code controller.bufferMu}). */
    public static final LongSupplier CONTROLLER_BUFFER_MU;
    public static final LongSupplier CONTROLLER_BUFFER_MU_KEY;
    /** FE each controller block takes in per tick, all sides together ({@code controller.inputPerTick}). */
    public static final LongSupplier CONTROLLER_INPUT_PER_TICK;

    static {
        BUILDER.push("network");
        BUILDER.push("channels");
        CABLE_CHANNELS_TIER_1 = BUILDER
                .comment("Channels a tier-1 storage cable carries, and the link between two devices that touch.",
                        "Every device uses one channel on each link of its route to the controller; over a limit, the",
                        "devices that joined last go offline. Energy, display, sensing and support blocks use none.")
                .defineInRange("tier1", CONFIG.intDefault("network.channels.tier1"), 1, 1_000_000);
        CABLE_CHANNELS_TIER_2 = BUILDER.comment("Channels a tier-2 storage cable carries.").defineInRange("tier2",
                CONFIG.intDefault("network.channels.tier2"), 1, 1_000_000);
        ROOT_LINK_CHANNELS = BUILDER.comment("Channels each root link of a controller carries.")
                .defineInRange("rootLink", CONFIG.intDefault("network.channels.rootLink"), 1, 1_000_000);
        AD_HOC_CHANNELS = BUILDER.comment("Channels a network without a controller has in all: its device limit.")
                .defineInRange("adHoc", CONFIG.intDefault("network.channels.adHoc"), 1, 1_000);
        BUILDER.pop();
        BUILDER.push("batch");
        BATCH_ITEMS = BUILDER.comment("Most items of one kind that one operation moves.").defineInRange("items",
                CONFIG.intDefault("network.batch.items"), 1, 1_000_000);
        BATCH_FLUID_MB = BUILDER.comment("Most mB of one fluid that one operation moves.").defineInRange("fluidMb",
                CONFIG.intDefault("network.batch.fluidMb"), 1, 1_000_000_000);
        BUILDER.pop();
        BUILDER.push("energy");
        ENERGY_PER_OPERATION = BUILDER
                .comment("MU every operation costs, whatever it moves. Idle networks pay nothing.")
                .defineInRange("perOperation", CONFIG.longDefault("network.energy.perOperation"), 0L, 1_000_000L);
        ENERGY_PER_UNIT = BUILDER.comment("MU per item an operation moves, or per fluidMbPerUnit mB of fluid.")
                .defineInRange("perUnit", CONFIG.longDefault("network.energy.perUnit"), 0L, 1_000_000L);
        ENERGY_FLUID_MB_PER_UNIT = BUILDER.comment("mB of fluid that cost as much as one item.")
                .defineInRange("fluidMbPerUnit", CONFIG.intDefault("network.energy.fluidMbPerUnit"), 1, 1_000_000);
        BUILDER.pop();
        ZONE_SIZE = BUILDER
                .comment("Most nodes (cables and devices) in one zone. A larger network is split into",
                        "several zones, and a removed node rebuilds the routes of its network at most every few ticks.")
                .defineInRange("zoneSize", CONFIG.intDefault("network.zoneSize"), 8, 1_000_000);
        BUILDER.push("adHoc");
        AD_HOC_BUFFER_MU_KEY = BUILDER.comment("MU a network without a controller holds; it is not saved.")
                .defineInRange("bufferMu", CONFIG.longDefault("network.adHoc.bufferMu"), 1L, 1_000_000_000L);
        AD_HOC_BUFFER_MU = moved(BUILDER, "network.adHoc.bufferFe", "bufferFe", "network.adHoc.bufferMu",
                AD_HOC_BUFFER_MU_KEY);
        BUILDER.pop();
        BUILDER.pop();
        BUILDER.push("controller");
        CONTROLLER_BUFFER_MU_KEY = BUILDER
                .comment("MU each block of a controller holds for its network; the multiblock holds the sum.")
                .defineInRange("bufferMu", CONFIG.longDefault("controller.bufferMu"), 1L, 1_000_000_000L);
        CONTROLLER_BUFFER_MU = moved(BUILDER, "controller.bufferFe", "bufferFe", "controller.bufferMu",
                CONTROLLER_BUFFER_MU_KEY);
        CONTROLLER_INPUT_PER_TICK = BUILDER
                .comment("MU each block of a controller takes in per tick; the multiblock takes the sum, from its",
                        "energy network (generators) and every side and device of its network together.")
                .defineInRange("inputPerTick", CONFIG.longDefault("controller.inputPerTick"), 1L, 1_000_000_000L);
        BUILDER.pop();
    }

    /** Lumen quartz veins tried per chunk; 0 turns the ore off. Read while chunks generate. */
    public static final IntSupplier LUMEN_QUARTZ_VEINS;
    /** Blocks per lumen quartz vein, as vanilla's ore size. */
    public static final IntSupplier LUMEN_QUARTZ_VEIN_SIZE;
    /** Lowest and highest y a lumen quartz vein centre can have; clamped to the dimension's height. */
    public static final IntSupplier LUMEN_QUARTZ_MIN_Y;
    public static final IntSupplier LUMEN_QUARTZ_MAX_Y;
    /** FE a machine's buffer holds. */
    public static final IntSupplier MACHINE_ENERGY_CAPACITY;
    /** FE a machine takes from a neighbour per transfer. */
    public static final IntSupplier MACHINE_MAX_ENERGY_INPUT;
    /** FE the fuel generator makes per tick of burning. */
    public static final IntSupplier GENERATOR_ENERGY_PER_TICK;
    /** FE the fuel generator pushes into each neighbour per tick at most. */
    public static final IntSupplier GENERATOR_MAX_ENERGY_OUTPUT;
    /**
     * The preset defaults of {@link #MACHINE_ENERGY_CAPACITY}, {@link #MACHINE_MAX_ENERGY_INPUT} and
     * {@link #GENERATOR_MAX_ENERGY_OUTPUT}, for machine block entities built where no server config is loaded (guide
     * scenes outside a world).
     */
    public static final int MACHINE_ENERGY_CAPACITY_DEFAULT;
    public static final int MACHINE_MAX_ENERGY_INPUT_DEFAULT;
    public static final int GENERATOR_MAX_ENERGY_OUTPUT_DEFAULT;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("worldgen");
        builder.push("lumenQuartz");
        LUMEN_QUARTZ_VEINS = builder
                .comment("Lumen quartz veins tried per chunk in the Overworld; 0 turns the ore off.",
                        "Applies to chunks generated after a change.")
                .defineInRange("veinsPerChunk", CONFIG.intDefault("worldgen.lumenQuartz.veinsPerChunk"), 0, 64);
        LUMEN_QUARTZ_VEIN_SIZE = builder.comment("Blocks per vein, as vanilla's ore size.").defineInRange("veinSize",
                CONFIG.intDefault("worldgen.lumenQuartz.veinSize"), 1, 64);
        LUMEN_QUARTZ_MIN_Y = builder
                .comment("Lowest y of a vein's centre. Stone above y 0 holds the stone ore, deepslate",
                        "below it the deepslate ore.")
                .defineInRange("minY", CONFIG.intDefault("worldgen.lumenQuartz.minY"), -2032, 2031);
        LUMEN_QUARTZ_MAX_Y = builder.comment("Highest y of a vein's centre; below minY turns the ore off.")
                .defineInRange("maxY", CONFIG.intDefault("worldgen.lumenQuartz.maxY"), -2032, 2031);
        builder.pop();
        builder.pop();

        builder.push("machines");
        MACHINE_ENERGY_CAPACITY_DEFAULT = CONFIG.intDefault("machines.energyCapacity");
        MACHINE_MAX_ENERGY_INPUT_DEFAULT = CONFIG.intDefault("machines.maxEnergyInput");
        GENERATOR_MAX_ENERGY_OUTPUT_DEFAULT = CONFIG.intDefault("machines.fuelGenerator.maxEnergyOutput");
        MACHINE_ENERGY_CAPACITY = builder
                .comment("MU each Storage machine's buffer holds, the fuel generator's included.")
                .defineInRange("energyCapacity", MACHINE_ENERGY_CAPACITY_DEFAULT, 1, 1_000_000);
        MACHINE_MAX_ENERGY_INPUT = builder.comment("MU a lithograph, fabricator or assembler takes per transfer.")
                .defineInRange("maxEnergyInput", MACHINE_MAX_ENERGY_INPUT_DEFAULT, 1, 1_000_000);
        builder.push("fuelGenerator");
        GENERATOR_ENERGY_PER_TICK = builder
                .comment("MU made per tick of burning; a fuel burns for its vanilla furnace time (coal: 1,600 ticks).")
                .defineInRange("energyPerTick", CONFIG.intDefault("machines.fuelGenerator.energyPerTick"), 1, 100_000);
        GENERATOR_MAX_ENERGY_OUTPUT = builder.comment("MU pushed into each neighbour per tick at most.")
                .defineInRange("maxEnergyOutput", GENERATOR_MAX_ENERGY_OUTPUT_DEFAULT, 1, 1_000_000);
        builder.pop();
        builder.pop();
    }

    /** Items one byte of a cell holds ({@code cells.itemsPerByte}). */
    public static final LongSupplier CELL_ITEMS_PER_BYTE;
    /** mB of fluid one byte of a cell holds ({@code cells.fluidMbPerByte}). */
    public static final LongSupplier CELL_FLUID_MB_PER_BYTE;
    /** Bytes every kind a cell holds costs on top of its amount ({@code cells.kindBytes}). */
    public static final LongSupplier CELL_KIND_BYTES;
    /** Bytes a kind with data components costs instead ({@code cells.componentKindBytes}). */
    public static final LongSupplier CELL_COMPONENT_KIND_BYTES;
    /** Bytes a memory module I adds to its cell ({@code cells.memoryBytes.tier1}). */
    public static final LongSupplier MEMORY_1_BYTES;
    /** Bytes a memory module II adds to its cell ({@code cells.memoryBytes.tier2}). */
    public static final LongSupplier MEMORY_2_BYTES;
    /** Bytes a memory module III adds to its cell ({@code cells.memoryBytes.tier3}). */
    public static final LongSupplier MEMORY_3_BYTES;
    /** How many times its memory a bulk cell holds ({@code cells.bulkMultiplier}). */
    public static final LongSupplier BULK_MULTIPLIER;
    /** Memory module slots per casing, small to portable ({@code cells.casings.<casing>.memorySlots}). */
    public static final IntSupplier SMALL_MEMORY_SLOTS;
    public static final IntSupplier MEDIUM_MEMORY_SLOTS;
    public static final IntSupplier LARGE_MEMORY_SLOTS;
    public static final IntSupplier BULK_MEMORY_SLOTS;
    public static final IntSupplier PORTABLE_MEMORY_SLOTS;
    /** Add-on slots per casing, small to portable ({@code cells.casings.<casing>.addonSlots}). */
    public static final IntSupplier SMALL_ADDON_SLOTS;
    public static final IntSupplier MEDIUM_ADDON_SLOTS;
    public static final IntSupplier LARGE_ADDON_SLOTS;
    public static final IntSupplier BULK_ADDON_SLOTS;
    public static final IntSupplier PORTABLE_ADDON_SLOTS;
    /** FE a portable cell holds ({@code cells.portable.energyCapacity}). */
    public static final IntSupplier PORTABLE_ENERGY_CAPACITY;
    /** FE per transfer a charger puts into a portable cell ({@code cells.portable.maxInput}). */
    public static final IntSupplier PORTABLE_MAX_INPUT;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("cells");
        CELL_ITEMS_PER_BYTE = builder.comment("Items one byte of a storage cell holds.").defineInRange("itemsPerByte",
                CONFIG.longDefault("cells.itemsPerByte"), 1L, 1_000_000L);
        CELL_FLUID_MB_PER_BYTE = builder.comment("mB of fluid one byte of a storage cell holds.")
                .defineInRange("fluidMbPerByte", CONFIG.longDefault("cells.fluidMbPerByte"), 1L, 1_000_000_000L);
        CELL_KIND_BYTES = builder
                .comment("Bytes every kind of item or fluid in a cell costs, whatever its amount.",
                        "Changes apply to cells inserted into a drive afterwards.")
                .defineInRange("kindBytes", CONFIG.longDefault("cells.kindBytes"), 0L, 1_000_000L);
        CELL_COMPONENT_KIND_BYTES = builder
                .comment("Bytes a kind with data components (damage, enchantments, a name) costs instead.")
                .defineInRange("componentKindBytes", CONFIG.longDefault("cells.componentKindBytes"), 0L, 1_000_000L);
        builder.push("memoryBytes");
        MEMORY_1_BYTES = builder.comment("Bytes a memory module I adds to its cell (1 KB = 1,024 bytes).")
                .defineInRange("tier1", CONFIG.longDefault("cells.memoryBytes.tier1"), 1L, 1L << 40);
        MEMORY_2_BYTES = builder.comment("Bytes a memory module II adds to its cell.").defineInRange("tier2",
                CONFIG.longDefault("cells.memoryBytes.tier2"), 1L, 1L << 40);
        MEMORY_3_BYTES = builder.comment("Bytes a memory module III adds to its cell.").defineInRange("tier3",
                CONFIG.longDefault("cells.memoryBytes.tier3"), 1L, 1L << 40);
        builder.pop();
        BULK_MULTIPLIER = builder
                .comment(
                        "How many times the bytes of its memory modules a bulk cell holds. A bulk cell holds one kind.")
                .defineInRange("bulkMultiplier", CONFIG.longDefault("cells.bulkMultiplier"), 1L, 1024L);
        builder.comment("Slots each cell casing has in the cell workbench. The workbench shows at most",
                "8 memory module and 4 add-on slots; a cell keeps parts beyond a lowered count.").push("casings");
        SMALL_MEMORY_SLOTS = memorySlots(builder, "small");
        SMALL_ADDON_SLOTS = addonSlots(builder, "small");
        MEDIUM_MEMORY_SLOTS = memorySlots(builder, "medium");
        MEDIUM_ADDON_SLOTS = addonSlots(builder, "medium");
        LARGE_MEMORY_SLOTS = memorySlots(builder, "large");
        LARGE_ADDON_SLOTS = addonSlots(builder, "large");
        BULK_MEMORY_SLOTS = memorySlots(builder, "bulk");
        BULK_ADDON_SLOTS = addonSlots(builder, "bulk");
        PORTABLE_MEMORY_SLOTS = memorySlots(builder, "portable");
        PORTABLE_ADDON_SLOTS = addonSlots(builder, "portable");
        builder.pop();
        builder.push("portable");
        PORTABLE_ENERGY_CAPACITY = builder
                .comment("MU a portable cell's battery holds. Each item or fluid moved through its terminal costs",
                        "energy like a network operation (network.energy.*).")
                .defineInRange("energyCapacity", CONFIG.intDefault("cells.portable.energyCapacity"), 1,
                        Integer.MAX_VALUE);
        PORTABLE_MAX_INPUT = builder
                .comment("MU per transfer a charger may put into a portable cell; a storage controller charges",
                        "it in one go when a sneaking player uses the cell on it.")
                .defineInRange("maxInput", CONFIG.intDefault("cells.portable.maxInput"), 1, Integer.MAX_VALUE);
        builder.pop();
        builder.pop();
    }

    /** Items a tier-1 port moves per second ({@code ports.tier1.itemsPerSecond}). */
    public static final IntSupplier PORT_TIER_1_ITEMS;
    /** mB of fluid a tier-1 port moves per second ({@code ports.tier1.fluidMbPerSecond}). */
    public static final IntSupplier PORT_TIER_1_FLUID_MB;
    /** Items a tier-2 port moves per second ({@code ports.tier2.itemsPerSecond}). */
    public static final IntSupplier PORT_TIER_2_ITEMS;
    /** mB of fluid a tier-2 port moves per second ({@code ports.tier2.fluidMbPerSecond}). */
    public static final IntSupplier PORT_TIER_2_FLUID_MB;
    /** Items a tier-3 port moves per second ({@code ports.tier3.itemsPerSecond}). */
    public static final IntSupplier PORT_TIER_3_ITEMS;
    /** mB of fluid a tier-3 port moves per second ({@code ports.tier3.fluidMbPerSecond}). */
    public static final IntSupplier PORT_TIER_3_FLUID_MB;
    /** What each speed card multiplies a port's rates by ({@code ports.speedCards.multiplier}). */
    public static final DoubleSupplier PORT_SPEED_CARD_MULTIPLIER;
    /** Speed card slots every port has ({@code ports.speedCards.slots}). */
    public static final IntSupplier PORT_SPEED_CARD_SLOTS;
    /** Fewest ticks between two looks of a storage bus at its inventory ({@code ports.storageBus.minScanTicks}). */
    public static final IntSupplier STORAGE_BUS_MIN_SCAN_TICKS;
    /** Most ticks between two looks of an idle storage bus ({@code ports.storageBus.maxScanTicks}). */
    public static final IntSupplier STORAGE_BUS_MAX_SCAN_TICKS;
    /** Most keys an export operation tries ({@code ports.filter.exportKeysPerOperation}). */
    public static final IntSupplier PORT_EXPORT_KEYS_PER_OPERATION;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("ports");
        builder.push("tier1");
        PORT_TIER_1_ITEMS = builder
                .comment("Items a tier-1 port moves per second. A port moves them in as few operations as the",
                        "batch size allows (network.batch.items), each paying energy once.")
                .defineInRange("itemsPerSecond", CONFIG.intDefault("ports.tier1.itemsPerSecond"), 1, 1_000_000);
        PORT_TIER_1_FLUID_MB = builder.comment("mB of fluid a tier-1 port moves per second.")
                .defineInRange("fluidMbPerSecond", CONFIG.intDefault("ports.tier1.fluidMbPerSecond"), 1, 1_000_000_000);
        builder.pop();
        builder.push("tier2");
        PORT_TIER_2_ITEMS = builder.comment("Items a tier-2 port moves per second.").defineInRange("itemsPerSecond",
                CONFIG.intDefault("ports.tier2.itemsPerSecond"), 1, 1_000_000);
        PORT_TIER_2_FLUID_MB = builder.comment("mB of fluid a tier-2 port moves per second.")
                .defineInRange("fluidMbPerSecond", CONFIG.intDefault("ports.tier2.fluidMbPerSecond"), 1, 1_000_000_000);
        builder.pop();
        builder.push("tier3");
        PORT_TIER_3_ITEMS = builder.comment("Items a tier-3 port moves per second.").defineInRange("itemsPerSecond",
                CONFIG.intDefault("ports.tier3.itemsPerSecond"), 1, 1_000_000);
        PORT_TIER_3_FLUID_MB = builder.comment("mB of fluid a tier-3 port moves per second.")
                .defineInRange("fluidMbPerSecond", CONFIG.intDefault("ports.tier3.fluidMbPerSecond"), 1, 1_000_000_000);
        builder.pop();
        builder.push("speedCards");
        PORT_SPEED_CARD_MULTIPLIER = builder
                .comment("What each speed card in a port multiplies its item and fluid rates by. More operations",
                        "per second follow, since an operation never moves more than one batch.")
                .defineInRange("multiplier", CONFIG.doubleDefault("ports.speedCards.multiplier"), 1.0, 16.0);
        PORT_SPEED_CARD_SLOTS = builder.comment("Speed card slots every port has, block and part alike.")
                .defineInRange("slots", CONFIG.intDefault("ports.speedCards.slots"), 0, PortCards.MAX_SLOTS);
        builder.pop();
        builder.push("storageBus");
        STORAGE_BUS_MIN_SCAN_TICKS = builder
                .comment("A port in storage mode looks at the inventory it mounts at most once per this many ticks,",
                        "however often the inventory changes. Its own stores and takes count at once.")
                .defineInRange("minScanTicks", CONFIG.intDefault("ports.storageBus.minScanTicks"), 1, 1200);
        STORAGE_BUS_MAX_SCAN_TICKS = builder
                .comment("While nothing changes, the wait between looks doubles up to this many ticks. Inventories",
                        "that send no change events are seen this late at worst.")
                .defineInRange("maxScanTicks", CONFIG.intDefault("ports.storageBus.maxScanTicks"), 1, 12000);
        builder.pop();
        builder.push("filter");
        PORT_EXPORT_KEYS_PER_OPERATION = builder
                .comment("Most kinds of item or fluid an export port tries in one operation, counting only kinds",
                        "the network holds and the target has room for. A fuzzy, tag or mod filter entry can match",
                        "many kinds; the rest wait for a later operation (round robin gets to them in turn).")
                .defineInRange("exportKeysPerOperation", CONFIG.intDefault("ports.filter.exportKeysPerOperation"), 1,
                        4096);
        builder.pop();
        builder.pop();
    }

    /** How hard research is, or {@code off} for everything unlocked ({@code research.preset}). */
    public static final Supplier<ResearchPreset> RESEARCH_PRESET;
    /** What the relaxed preset multiplies task numbers and point costs by ({@code research.relaxedScale}). */
    public static final DoubleSupplier RESEARCH_RELAXED_SCALE;
    /** What the expert preset multiplies task numbers and point costs by ({@code research.expertScale}). */
    public static final DoubleSupplier RESEARCH_EXPERT_SCALE;
    /**
     * Points a team earns for each kind that enters its networks for the first time
     * ({@code research.points.discovery}).
     */
    public static final LongSupplier RESEARCH_DISCOVERY_POINTS;
    /**
     * Points a team earns the first time one of its players earns an advancement ({@code research.points.advancement}).
     */
    public static final LongSupplier RESEARCH_ADVANCEMENT_POINTS;
    /**
     * Points earned per skip token at the standard preset, scaled like costs
     * ({@code research.skipTokens.pointsPerToken}).
     */
    public static final LongSupplier RESEARCH_POINTS_PER_TOKEN;
    /** Chance that a structure chest holds a skip token ({@code research.skipTokens.lootChance}). */
    public static final DoubleSupplier RESEARCH_TOKEN_LOOT_CHANCE;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("research");
        RESEARCH_PRESET = builder.comment(
                "How hard research is: off unlocks everything; relaxed, standard and expert scale task numbers,",
                "point costs and the points per skip token by relaxedScale, 1 and expertScale.")
                .defineEnum("preset", CONFIG.enumDefault("research.preset", ResearchPreset.class));
        RESEARCH_RELAXED_SCALE = builder.comment("Multiplier of task numbers and point costs at the relaxed preset.")
                .defineInRange("relaxedScale", CONFIG.doubleDefault("research.relaxedScale"), 0.01, 100.0);
        RESEARCH_EXPERT_SCALE = builder.comment("Multiplier of task numbers and point costs at the expert preset.")
                .defineInRange("expertScale", CONFIG.doubleDefault("research.expertScale"), 0.01, 100.0);
        builder.push("points");
        RESEARCH_DISCOVERY_POINTS = builder.comment(
                "Points a team earns for each item or fluid that enters one of its networks for the first time.")
                .defineInRange("discovery", CONFIG.longDefault("research.points.discovery"), 0L, 1_000_000L);
        RESEARCH_ADVANCEMENT_POINTS = builder
                .comment("Points a team earns the first time one of its players earns an advancement (recipe",
                        "unlocks and other hidden advancements without a display give none).")
                .defineInRange("advancement", CONFIG.longDefault("research.points.advancement"), 0L, 1_000_000L);
        builder.pop();
        builder.push("skipTokens");
        RESEARCH_POINTS_PER_TOKEN = builder
                .comment("Points earned per skip token at the standard preset; scaled by the preset like point costs.",
                        "A skip token completes any node whose requirements are done.")
                .defineInRange("pointsPerToken", CONFIG.longDefault("research.skipTokens.pointsPerToken"), 1L,
                        1_000_000_000L);
        RESEARCH_TOKEN_LOOT_CHANCE = builder.comment("Chance that a generated structure chest holds a skip token.")
                .defineInRange("lootChance", CONFIG.doubleDefault("research.skipTokens.lootChance"), 0.0, 1.0);
        builder.pop();
        builder.pop();
    }

    /** Tantalite veins tried per chunk; 0 turns the ore off. Read while chunks generate. */
    public static final IntSupplier TANTALITE_VEINS;
    /** Blocks per tantalite vein, as vanilla's ore size. */
    public static final IntSupplier TANTALITE_VEIN_SIZE;
    /** Lowest and highest y a tantalite vein centre can have; clamped to the dimension's height. */
    public static final IntSupplier TANTALITE_MIN_Y;
    public static final IntSupplier TANTALITE_MAX_Y;
    /** Times its dies a lithograph print run makes with a precision module fitted. */
    public static final IntSupplier PRECISION_PRINT_YIELD;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("worldgen");
        builder.push("tantalite");
        TANTALITE_VEINS = builder
                .comment("Tantalite veins tried per chunk in the Overworld; 0 turns the ore off.",
                        "Tantalite replaces deepslate only. Applies to chunks generated after a change.")
                .defineInRange("veinsPerChunk", CONFIG.intDefault("worldgen.tantalite.veinsPerChunk"), 0, 64);
        TANTALITE_VEIN_SIZE = builder.comment("Blocks per vein, as vanilla's ore size.").defineInRange("veinSize",
                CONFIG.intDefault("worldgen.tantalite.veinSize"), 1, 64);
        TANTALITE_MIN_Y = builder.comment("Lowest y of a vein's centre.").defineInRange("minY",
                CONFIG.intDefault("worldgen.tantalite.minY"), -2032, 2031);
        TANTALITE_MAX_Y = builder.comment("Highest y of a vein's centre; below minY turns the ore off.")
                .defineInRange("maxY", CONFIG.intDefault("worldgen.tantalite.maxY"), -2032, 2031);
        builder.pop();
        builder.pop();
        builder.push("machines");
        builder.push("modules");
        PRECISION_PRINT_YIELD = builder
                .comment("Times its dies a lithograph print run makes with a precision module in its module slot.")
                .defineInRange("precisionPrintYield", CONFIG.intDefault("machines.modules.precisionPrintYield"), 1, 16);
        builder.pop();
        builder.pop();
    }

    /** What the relaxed preset multiplies source points by ({@code research.sources.relaxedPointScale}). */
    public static final DoubleSupplier SOURCE_RELAXED_POINT_SCALE;
    /** What the expert preset multiplies source points by ({@code research.sources.expertPointScale}). */
    public static final DoubleSupplier SOURCE_EXPERT_POINT_SCALE;
    /** Runs a machine observer watches per point ({@code research.sources.observer.runsPerPoint}). */
    public static final IntSupplier OBSERVER_RUNS_PER_POINT;
    /** Most points one machine observer gives per in-game day ({@code research.sources.observer.dailyCap}). */
    public static final IntSupplier OBSERVER_DAILY_CAP;
    /** Points the first scan of a block type gives a team ({@code research.sources.scanner.points}). */
    public static final LongSupplier SCANNER_POINTS;
    /** Ticks between two scans ({@code research.sources.scanner.cooldownTicks}). */
    public static final IntSupplier SCANNER_COOLDOWN;
    /** Fewest and most points one research note gives ({@code research.sources.notes.minPoints/maxPoints}). */
    public static final IntSupplier NOTES_MIN_POINTS;
    public static final IntSupplier NOTES_MAX_POINTS;
    /** Chance that a structure chest holds research notes ({@code research.sources.notes.lootChance}). */
    public static final DoubleSupplier NOTES_LOOT_CHANCE;
    /**
     * Points the first analysis of a processor or cell item gives a team ({@code research.sources.analyzer.points}).
     */
    public static final LongSupplier ANALYZER_POINTS;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("research");
        builder.push("sources");
        SOURCE_RELAXED_POINT_SCALE = builder
                .comment("Multiplier of the points the observer, scanner, notes and analyzer give at the relaxed",
                        "preset (and of the observer's daily cap).")
                .defineInRange("relaxedPointScale", CONFIG.doubleDefault("research.sources.relaxedPointScale"), 0.01,
                        100.0);
        SOURCE_EXPERT_POINT_SCALE = builder.comment("The same at the expert preset.").defineInRange("expertPointScale",
                CONFIG.doubleDefault("research.sources.expertPointScale"), 0.01, 100.0);
        builder.push("observer");
        OBSERVER_RUNS_PER_POINT = builder.comment("Finished runs of the watched machine per point.")
                .defineInRange("runsPerPoint", CONFIG.intDefault("research.sources.observer.runsPerPoint"), 1, 10_000);
        OBSERVER_DAILY_CAP = builder
                .comment("Most points one observer gives per in-game day (24,000 ticks of game time).")
                .defineInRange("dailyCap", CONFIG.intDefault("research.sources.observer.dailyCap"), 0, 1_000_000);
        builder.pop();
        builder.push("scanner");
        SCANNER_POINTS = builder.comment("Points the first scan of a block type gives the scanning player's team.")
                .defineInRange("points", CONFIG.longDefault("research.sources.scanner.points"), 0L, 1_000_000L);
        SCANNER_COOLDOWN = builder.comment("Ticks before the scanner can be used again.").defineInRange("cooldownTicks",
                CONFIG.intDefault("research.sources.scanner.cooldownTicks"), 0, 1200);
        builder.pop();
        builder.push("notes");
        NOTES_MIN_POINTS = builder.comment("Fewest points one research note gives when read.")
                .defineInRange("minPoints", CONFIG.intDefault("research.sources.notes.minPoints"), 0, 1_000_000);
        NOTES_MAX_POINTS = builder.comment("Most points one research note gives; below minPoints counts as minPoints.")
                .defineInRange("maxPoints", CONFIG.intDefault("research.sources.notes.maxPoints"), 0, 1_000_000);
        NOTES_LOOT_CHANCE = builder.comment("Chance that a generated structure chest holds research notes.")
                .defineInRange("lootChance", CONFIG.doubleDefault("research.sources.notes.lootChance"), 0.0, 1.0);
        builder.pop();
        builder.push("analyzer");
        ANALYZER_POINTS = builder
                .comment("Points the first analysis of a processor or storage cell item gives the player's team.")
                .defineInRange("points", CONFIG.longDefault("research.sources.analyzer.points"), 0L, 1_000_000L);
        builder.pop();
        builder.pop();
        builder.pop();
    }

    /** Controller blocks a multiblock may have without research ({@code controller.size.base}). */
    public static final IntSupplier SIZE_BASE;
    /** Controller blocks after the first size research node ({@code controller.size.step1}). */
    public static final IntSupplier SIZE_STEP_1;
    /** Controller blocks after the second size research node ({@code controller.size.step2}). */
    public static final IntSupplier SIZE_STEP_2;
    /** Controller blocks after the third size research node ({@code controller.size.step3}). */
    public static final IntSupplier SIZE_STEP_3;
    /** Controller blocks a multiblock never exceeds, whatever research allows ({@code controller.size.max}). */
    public static final IntSupplier SIZE_MAX;
    /** Card slots of a one-block controller ({@code controller.cards.base}). */
    public static final IntSupplier CARD_SLOTS_BASE;
    /** Card slots each further active controller block adds ({@code controller.cards.perBlock}). */
    public static final IntSupplier CARD_SLOTS_PER_BLOCK;
    /** Most card slots of a controller, at most 12 ({@code controller.cards.max}). */
    public static final IntSupplier CARD_SLOTS_MAX;
    /** Batch amplifier cards that count ({@code controller.cards.batchAmplifiers}). */
    public static final IntSupplier CARD_LIMIT_AMPLIFIERS;
    /** Efficiency core cards that count ({@code controller.cards.efficiencyCores}). */
    public static final IntSupplier CARD_LIMIT_EFFICIENCY;
    /** Surge regulator cards that count ({@code controller.cards.surgeRegulators}). */
    public static final IntSupplier CARD_LIMIT_SURGE;
    /** Whether controllers record their telemetry history ({@code controller.recorders.telemetry}, M7R item 3). */
    public static final BooleanSupplier RECORD_TELEMETRY;
    /** Whether controllers keep a ledger of moves ({@code controller.recorders.ledger}, M7R item 3). */
    public static final BooleanSupplier RECORD_LEDGER;
    /** Whether controllers run redstone alarm rules ({@code controller.recorders.alarms}, M7R item 3). */
    public static final BooleanSupplier RECORD_ALARMS;
    /** Redstone alarm rules one controller holds at most ({@code controller.recorders.alarmRules}). */
    public static final IntSupplier ALARM_RULES;
    /**
     * Percent one batch amplifier card adds to the batch size ({@code controller.cards.batchAmplifier.batchPercent}).
     * This and the other values moved by M7R item 8 read their old key while it is set ({@link MovedKeys}); the raw new
     * key is the {@code *_KEY} value next to it.
     */
    public static final IntSupplier AMPLIFIER_BATCH_PERCENT;
    public static final IntSupplier AMPLIFIER_BATCH_PERCENT_KEY;
    /** Most percent all batch amplifier cards add ({@code controller.cards.batchAmplifier.maxBatchPercent}). */
    public static final IntSupplier AMPLIFIER_MAX_BATCH_PERCENT;
    public static final IntSupplier AMPLIFIER_MAX_BATCH_PERCENT_KEY;
    /** Percent each efficiency core card saves ({@code controller.cards.efficiencyCore.percent}). */
    public static final IntSupplier EFFICIENCY_PERCENT;
    public static final IntSupplier EFFICIENCY_PERCENT_KEY;
    /** Most percent all efficiency core cards save ({@code controller.cards.efficiencyCore.maxPercent}). */
    public static final IntSupplier EFFICIENCY_MAX_PERCENT;
    public static final IntSupplier EFFICIENCY_MAX_PERCENT_KEY;
    /** FE level below which a surge regulator holds automation ({@code controller.cards.surgeRegulator...}). */
    public static final IntSupplier SURGE_RESERVE_PERCENT;
    public static final IntSupplier SURGE_RESERVE_PERCENT_KEY;
    /** Ticks between telemetry samples ({@code controller.recorders.telemetrySampleTicks}). */
    public static final IntSupplier TELEMETRY_SAMPLE_TICKS;
    public static final IntSupplier TELEMETRY_SAMPLE_TICKS_KEY;
    /** Samples the telemetry recorder keeps ({@code controller.recorders.telemetrySamples}). */
    public static final IntSupplier TELEMETRY_SAMPLES;
    public static final IntSupplier TELEMETRY_SAMPLES_KEY;
    /** Keys the telemetry recorder tracks at most ({@code controller.recorders.telemetryMaxKeys}). */
    public static final IntSupplier TELEMETRY_MAX_KEYS;
    public static final IntSupplier TELEMETRY_MAX_KEYS_KEY;
    /** Days the ledger keeps ({@code controller.recorders.ledgerDays}). */
    public static final IntSupplier LEDGER_DAYS;
    public static final IntSupplier LEDGER_DAYS_KEY;
    /** Rows (one per key and day) the ledger keeps at most ({@code controller.recorders.ledgerMaxRows}). */
    public static final IntSupplier LEDGER_MAX_ROWS;
    public static final IntSupplier LEDGER_MAX_ROWS_KEY;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("controller");
        builder.push("size");
        SIZE_BASE = builder.comment("Controller blocks one multiblock takes without research; more stay dark.")
                .defineInRange("base", CONFIG.intDefault("controller.size.base"), 1, 4096);
        SIZE_STEP_1 = builder.comment("Controller blocks once the owner's team has the first size research node.")
                .defineInRange("step1", CONFIG.intDefault("controller.size.step1"), 1, 4096);
        SIZE_STEP_2 = builder.comment("Controller blocks once the owner's team has the second size research node.")
                .defineInRange("step2", CONFIG.intDefault("controller.size.step2"), 1, 4096);
        SIZE_STEP_3 = builder.comment("Controller blocks once the owner's team has the third size research node.")
                .defineInRange("step3", CONFIG.intDefault("controller.size.step3"), 1, 4096);
        SIZE_MAX = builder
                .comment("Controller blocks a multiblock never exceeds, whatever research allows; also bounds the",
                        "search for its blocks (and for the modules attached to it) when one is placed or broken.")
                .defineInRange("max", CONFIG.intDefault("controller.size.max"), 1, 4096);
        builder.pop();
        builder.push("cards");
        CARD_SLOTS_BASE = builder.comment("Upgrade card slots of a controller of one block.").defineInRange("base",
                CONFIG.intDefault("controller.cards.base"), 0, 12);
        CARD_SLOTS_PER_BLOCK = builder.comment("Card slots each further active controller block adds.")
                .defineInRange("perBlock", CONFIG.intDefault("controller.cards.perBlock"), 0, 12);
        CARD_SLOTS_MAX = builder.comment("Most card slots one controller has, however many blocks it has.")
                .defineInRange("max", CONFIG.intDefault("controller.cards.max"), 0, 12);
        CARD_LIMIT_AMPLIFIERS = builder.comment("Batch amplifier cards one controller takes; each adds batchPercent.")
                .defineInRange("batchAmplifiers", CONFIG.intDefault("controller.cards.batchAmplifiers"), 0, 12);
        CARD_LIMIT_EFFICIENCY = builder.comment("Efficiency core cards one controller takes.")
                .defineInRange("efficiencyCores", CONFIG.intDefault("controller.cards.efficiencyCores"), 0, 12);
        CARD_LIMIT_SURGE = builder.comment("Surge regulator cards one controller takes; more than one adds nothing.")
                .defineInRange("surgeRegulators", CONFIG.intDefault("controller.cards.surgeRegulators"), 0, 12);
        builder.push("batchAmplifier");
        AMPLIFIER_BATCH_PERCENT_KEY = builder
                .comment("Percent each batch amplifier card adds to the items or mB one device operation moves.")
                .defineInRange("batchPercent", CONFIG.intDefault("controller.cards.batchAmplifier.batchPercent"), 0,
                        1_000);
        AMPLIFIER_MAX_BATCH_PERCENT_KEY = builder.comment("Most percent all batch amplifier cards add together.")
                .defineInRange("maxBatchPercent", CONFIG.intDefault("controller.cards.batchAmplifier.maxBatchPercent"),
                        0, 10_000);
        builder.pop();
        builder.push("efficiencyCore");
        EFFICIENCY_PERCENT_KEY = builder
                .comment("Percent each efficiency core card takes off every operation's MU cost.")
                .defineInRange("percent", CONFIG.intDefault("controller.cards.efficiencyCore.percent"), 0, 99);
        EFFICIENCY_MAX_PERCENT_KEY = builder.comment("Most percent all efficiency core cards take off together.")
                .defineInRange("maxPercent", CONFIG.intDefault("controller.cards.efficiencyCore.maxPercent"), 0, 99);
        builder.pop();
        builder.push("surgeRegulator");
        SURGE_RESERVE_PERCENT_KEY = builder
                .comment("With a surge regulator card, automation waits while the network holds less than this",
                        "percent of its MU capacity, so the rest stays for players; it resumes at twice this level.")
                .defineInRange("reservePercent", CONFIG.intDefault("controller.cards.surgeRegulator.reservePercent"), 1,
                        50);
        builder.pop();
        builder.pop();
        builder.push("recorders");
        RECORD_TELEMETRY = builder
                .comment("Whether every controller records its network's history (operations, MU, storage) for its",
                        "History tab, display panels and the analyzer. Off, controllers keep no history.")
                .define("telemetry", CONFIG.booleanDefault("controller.recorders.telemetry"));
        RECORD_LEDGER = builder
                .comment("Whether every controller keeps a ledger of what each player and automation put in and took",
                        "out per in-game day (ledgerDays and ledgerMaxRows). Off, nothing is recorded.")
                .define("ledger", CONFIG.booleanDefault("controller.recorders.ledger"));
        RECORD_ALARMS = builder
                .comment("Whether controllers run their redstone alarm rules; off, the rules stay but give no signal.")
                .define("alarms", CONFIG.booleanDefault("controller.recorders.alarms"));
        ALARM_RULES = builder.comment("Redstone alarm rules one controller holds at most.").defineInRange("alarmRules",
                CONFIG.intDefault("controller.recorders.alarmRules"), 1, 8);
        TELEMETRY_SAMPLE_TICKS_KEY = builder.comment("Ticks between two samples of a controller's telemetry history.")
                .defineInRange("telemetrySampleTicks", CONFIG.intDefault("controller.recorders.telemetrySampleTicks"),
                        1, 72_000);
        TELEMETRY_SAMPLES_KEY = builder
                .comment("Telemetry samples a controller keeps; with telemetrySampleTicks this is how far back its",
                        "history goes.")
                .defineInRange("telemetrySamples", CONFIG.intDefault("controller.recorders.telemetrySamples"), 1, 8640);
        TELEMETRY_MAX_KEYS_KEY = builder
                .comment("Most item or fluid keys a controller's telemetry tracks for display panels.")
                .defineInRange("telemetryMaxKeys", CONFIG.intDefault("controller.recorders.telemetryMaxKeys"), 0, 256);
        LEDGER_DAYS_KEY = builder.comment("In-game days a controller's ledger keeps.").defineInRange("ledgerDays",
                CONFIG.intDefault("controller.recorders.ledgerDays"), 1, 365);
        LEDGER_MAX_ROWS_KEY = builder
                .comment("Rows a controller's ledger keeps at most, one per item or fluid and day; past it the oldest",
                        "day goes first.")
                .defineInRange("ledgerMaxRows", CONFIG.intDefault("controller.recorders.ledgerMaxRows"), 0, 1_000_000);
        builder.pop();
        builder.pop();
    }

    /** Fewest ticks between two evaluations of one condition switch ({@code condition.minIntervalTicks}). */
    public static final IntSupplier CONDITION_MIN_INTERVAL;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("condition");
        CONDITION_MIN_INTERVAL = builder
                .comment("Fewest ticks between two evaluations of one condition switch, so a value that swings",
                        "round its threshold cannot make the redstone output flap faster than this.")
                .defineInRange("minIntervalTicks", CONFIG.intDefault("condition.minIntervalTicks"), 1, 1200);
        builder.pop();
    }

    /** Most nodes one logic graph may hold ({@code logic.maxNodes}, M7L). */
    public static final IntSupplier LOGIC_MAX_NODES;
    /** Most graphs one Logic Controller holds ({@code logic.maxGraphs}, M7L). */
    public static final IntSupplier LOGIC_MAX_GRAPHS;
    /** Checks per second the logic of one team may use ({@code logic.teamChecksPerSecond}, M7L). */
    public static final IntSupplier LOGIC_TEAM_CHECKS;
    /** Action points per second the logic of one team may use ({@code logic.teamActionPointsPerSecond}, M7L2). */
    public static final IntSupplier LOGIC_TEAM_ACTION_POINTS;
    /** Extra checks per read of another network ({@code logic.crossNetworkChecks}, M7L2 row 13). */
    public static final IntSupplier LOGIC_CROSS_NETWORK_CHECKS;
    /** Extra action points per action on another network ({@code logic.crossNetworkActionPoints}, M7L2 row 13). */
    public static final IntSupplier LOGIC_CROSS_NETWORK_POINTS;
    /** Longest graph text a player may import ({@code logic.maxImportLength}, M7L2 sharing). */
    public static final IntSupplier LOGIC_MAX_IMPORT_LENGTH;
    /** Fewest ticks between two writes of logic to one display ({@code logic.displayWriteTicks}, M7L2). */
    public static final IntSupplier LOGIC_DISPLAY_WRITE_TICKS;
    /** Most shared variables one Logic Controller holds ({@code logic.maxVariables}, M7L2 batch 4). */
    public static final IntSupplier LOGIC_MAX_VARIABLES;
    /** Most graphs deep one chain of "Run another rule" goes ({@code logic.maxRuleDepth}, M7L2 batch 4). */
    public static final IntSupplier LOGIC_MAX_RULE_DEPTH;
    /** Ticks between two looks for players near a device ({@code logic.playerCheckTicks}, M7L2 batch 4). */
    public static final IntSupplier LOGIC_PLAYER_CHECK_TICKS;
    /** Largest radius a "Player nearby" node looks around ({@code logic.playerRadiusMax}, M7L2 batch 4). */
    public static final IntSupplier LOGIC_PLAYER_RADIUS_MAX;
    /** Most node values one rule pins to the controller screen ({@code logic.maxPins}, M7L2 debugging). */
    public static final IntSupplier LOGIC_MAX_PINS;
    /** Change history entries kept per rule ({@code logic.historyEntries}, M7L2 multiplayer). */
    public static final IntSupplier LOGIC_HISTORY_ENTRIES;
    /** Most items one rule run goes through over all its For each nodes ({@code logic.forEachMaxItems}, M7L2). */
    public static final IntSupplier LOGIC_FOR_EACH_MAX_ITEMS;
    /** FE a logic remote holds ({@code logic.remote.energyCapacity}, M7L2). */
    public static final IntSupplier LOGIC_REMOTE_ENERGY_CAPACITY;
    /** FE per transfer a charger puts into a logic remote ({@code logic.remote.maxInput}, M7L2). */
    public static final IntSupplier LOGIC_REMOTE_MAX_INPUT;
    /** FE a logic remote spends per pause or resume ({@code logic.remote.energyPerOperation}, M7L2). */
    public static final IntSupplier LOGIC_REMOTE_ENERGY_PER_OPERATION;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("logic");
        LOGIC_MAX_NODES = builder
                .comment("Most nodes one graph of a Logic Controller may hold. A larger graph is kept but paused",
                        "with an error until it is made smaller.")
                .defineInRange("maxNodes", CONFIG.intDefault("logic.maxNodes"), 1, 1024);
        LOGIC_MAX_GRAPHS = builder
                .comment("Graphs one Logic Controller holds and runs. Graphs past the limit are kept but do not run.")
                .defineInRange("maxGraphs", CONFIG.intDefault("logic.maxGraphs"), 1, 64);
        LOGIC_TEAM_CHECKS = builder
                .comment("Checks per second the logic of one team may use: the nodes rules evaluate and sensor reads.",
                        "Work over the budget waits for the next second; player work is never held back.")
                .defineInRange("teamChecksPerSecond", CONFIG.intDefault("logic.teamChecksPerSecond"), 1, 1_000_000);
        LOGIC_TEAM_ACTION_POINTS = builder.comment(
                "Action points per second the logic of one team may use, apart from its checks: each action",
                "a rule sends costs about 1 point per microsecond it takes the server (a switch 2, a move of",
                "items 12, so 600 allow 50 moves a second). A rule that can act waits for the next second when they are used up.")
                .defineInRange("teamActionPointsPerSecond", CONFIG.intDefault("logic.teamActionPointsPerSecond"), 1,
                        1_000_000);
        LOGIC_CROSS_NETWORK_CHECKS = builder.comment(
                "Extra checks a rule pays for each read of another network (a label like intake@North Base), on",
                "top of the read's own cost: finding the network by name and its access answer.")
                .defineInRange("crossNetworkChecks", CONFIG.intDefault("logic.crossNetworkChecks"), 0, 64);
        LOGIC_CROSS_NETWORK_POINTS = builder.comment(
                "Extra action points a rule pays for each action on another network, on top of the action's own:",
                "finding the network by name, its access answer and the devices' access checks there.")
                .defineInRange("crossNetworkActionPoints", CONFIG.intDefault("logic.crossNetworkActionPoints"), 0, 64);
        LOGIC_MAX_IMPORT_LENGTH = builder
                .comment(
                        "Longest graph text (mtlogic1:...) a player may import into a Logic Controller, in characters.",
                        "A 64-node graph is about 2,000 characters; longer texts are refused before they are read.")
                .defineInRange("maxImportLength", CONFIG.intDefault("logic.maxImportLength"), 256, 65_536);
        LOGIC_DISPLAY_WRITE_TICKS = builder
                .comment("Fewest ticks between two texts a Logic Controller writes to one display, so a value that",
                        "changes every tick cannot flood players with updates. A text written sooner waits and",
                        "shows when the time is up.")
                .defineInRange("displayWriteTicks", CONFIG.intDefault("logic.displayWriteTicks"), 1, 1200);
        LOGIC_MAX_VARIABLES = builder
                .comment("Shared variables one Logic Controller holds for its graphs. A graph setting a new one past",
                        "the limit is refused (the why line says so); existing ones still change.")
                .defineInRange("maxVariables", CONFIG.intDefault("logic.maxVariables"), 1, SharedVariable.HARD_MAX);
        LOGIC_MAX_RULE_DEPTH = builder
                .comment("How deep graphs may run each other (\"Run another rule\") in one chain: a graph run by a",
                        "graph that was itself run counts two, and so on. Circles are refused before they run.")
                .defineInRange("maxRuleDepth", CONFIG.intDefault("logic.maxRuleDepth"), 1, 64);
        LOGIC_PLAYER_CHECK_TICKS = builder
                .comment("Ticks between two looks for players near a device, for graphs with a \"Player nearby\"",
                        "node. No event says a player moved, so a controller looks on this timer while such a graph",
                        "runs, and not at all otherwise.")
                .defineInRange("playerCheckTicks", CONFIG.intDefault("logic.playerCheckTicks"), 20, 1200);
        LOGIC_PLAYER_RADIUS_MAX = builder
                .comment("Largest radius in blocks a \"Player nearby\" node looks around; a larger setting is cut",
                        "to this.")
                .defineInRange("playerRadiusMax", CONFIG.intDefault("logic.playerRadiusMax"), 1, 64);
        LOGIC_MAX_PINS = builder
                .comment("Node values one rule may pin in the node editor; pinned values show in the rule's detail",
                        "on the controller screen while it is open. 0 turns pinning off.")
                .defineInRange("maxPins", CONFIG.intDefault("logic.maxPins"), 0, GraphSlot.MAX_PINS);
        LOGIC_HISTORY_ENTRIES = builder
                .comment("Change history entries kept per rule: who saved, reverted, switched or added it, when, and",
                        "what changed. Older entries are dropped; saved with the controller. 0 keeps no history.")
                .defineInRange("historyEntries", CONFIG.intDefault("logic.historyEntries"), 0, HistoryEntry.HARD_MAX);
        LOGIC_FOR_EACH_MAX_ITEMS = builder
                .comment("Most items one run of a rule goes through over all its \"For each\" nodes. Each item costs",
                        "the checks of the nodes it runs; a run stops here, or earlier when the team's checks run",
                        "out, and its why line says so.")
                .defineInRange("forEachMaxItems", CONFIG.intDefault("logic.forEachMaxItems"), 1,
                        ForEach.HARD_MAX_ITEMS);
        builder.push("remote");
        LOGIC_REMOTE_ENERGY_CAPACITY = builder
                .comment("MU a logic remote holds. It charges in any FE charger; one that cannot pay an operation",
                        "shows only its last known notices.")
                .defineInRange("energyCapacity", CONFIG.intDefault("logic.remote.energyCapacity"), 1,
                        Integer.MAX_VALUE);
        LOGIC_REMOTE_MAX_INPUT = builder.comment("MU per transfer a charger may put into a logic remote.")
                .defineInRange("maxInput", CONFIG.intDefault("logic.remote.maxInput"), 1, Integer.MAX_VALUE);
        LOGIC_REMOTE_ENERGY_PER_OPERATION = builder
                .comment("MU a logic remote spends each time it pauses or resumes a rule. Reading notices and rules",
                        "is free while the remote holds at least this much.")
                .defineInRange("energyPerOperation", CONFIG.intDefault("logic.remote.energyPerOperation"), 0,
                        Integer.MAX_VALUE);
        builder.pop();
        builder.pop();
    }

    /**
     * Checks a sensor read costs on top of the plain read when it also reads the target's saved data for pack readers
     * ({@code sensor.savedDataCost}, M7L2).
     */
    public static final IntSupplier SENSOR_SAVED_DATA_COST;
    /** Fewest ticks between two saved-data reads of one sensor ({@code sensor.savedDataMinIntervalTicks}, M7L2). */
    public static final IntSupplier SENSOR_SAVED_DATA_INTERVAL;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("sensor");
        SENSOR_SAVED_DATA_COST = builder
                .comment("Extra checks of the team's logic budget a sensor read costs when a pack reader makes it",
                        "read the target's saved data. Plain reads cost 2 checks.")
                .defineInRange("savedDataCost", CONFIG.intDefault("sensor.savedDataCost"), 0, 10_000);
        SENSOR_SAVED_DATA_INTERVAL = builder
                .comment("Fewest ticks between two saved-data reads of one sensor. Reads in between update the other",
                        "values at once and the pack reader values when the interval is over.")
                .defineInRange("savedDataMinIntervalTicks", CONFIG.intDefault("sensor.savedDataMinIntervalTicks"), 1,
                        1200);
        builder.pop();
    }

    /** FE an energy storage block of tier 1, 2 or 3 holds ({@code energy.cells.tier<n>.capacity}). */
    public static final LongSupplier ENERGY_CELL_CAPACITY_TIER_1;
    public static final LongSupplier ENERGY_CELL_CAPACITY_TIER_2;
    public static final LongSupplier ENERGY_CELL_CAPACITY_TIER_3;
    /**
     * MU one transfer into or out of an energy storage block may move through its FE capability, and one fill from its
     * network ({@code energy.cells.tier<n>.maxTransfer}).
     */
    public static final IntSupplier ENERGY_CELL_TRANSFER_TIER_1;
    public static final IntSupplier ENERGY_CELL_TRANSFER_TIER_2;
    public static final IntSupplier ENERGY_CELL_TRANSFER_TIER_3;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("energy");
        builder.push("cells");
        ENERGY_CELL_CAPACITY_TIER_1 = cellCapacity(builder, 1);
        ENERGY_CELL_TRANSFER_TIER_1 = cellTransfer(builder, 1);
        ENERGY_CELL_CAPACITY_TIER_2 = cellCapacity(builder, 2);
        ENERGY_CELL_TRANSFER_TIER_2 = cellTransfer(builder, 2);
        ENERGY_CELL_CAPACITY_TIER_3 = cellCapacity(builder, 3);
        ENERGY_CELL_TRANSFER_TIER_3 = cellTransfer(builder, 3);
        builder.pop();
        builder.pop();
    }

    /** Blocks around a collector it takes item entities from ({@code world.collector.radius}). */
    public static final IntSupplier COLLECTOR_RADIUS;
    /** Ticks between a collector's runs while items it could not take are in range. */
    public static final IntSupplier COLLECTOR_INTERVAL;
    /** Ticks between a continuous placer's placements ({@code world.placer.intervalTicks}). */
    public static final IntSupplier PLACER_INTERVAL;
    /** Item entities in front of a dropping placer at which it pauses ({@code world.placer.dropEntityCap}). */
    public static final IntSupplier PLACER_DROP_ENTITY_CAP;
    /** Ticks between a continuous breaker's breaks ({@code world.breaker.intervalTicks}). */
    public static final IntSupplier BREAKER_INTERVAL;
    /** The hardest block a breaker breaks ({@code world.breaker.maxHardness}; obsidian is 50). */
    public static final DoubleSupplier BREAKER_MAX_HARDNESS;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("world");
        builder.push("collector");
        COLLECTOR_RADIUS = builder.comment("Blocks around a collector it takes item entities from.")
                .defineInRange("radius", CONFIG.intDefault("world.collector.radius"), 1, 8);
        COLLECTOR_INTERVAL = builder
                .comment("Ticks between a collector's runs while items it could not take yet are in range. A",
                        "collector with nothing in range sleeps until an item entity appears near it.")
                .defineInRange("intervalTicks", CONFIG.intDefault("world.collector.intervalTicks"), 1, 200);
        builder.pop();
        builder.push("placer");
        PLACER_INTERVAL = builder.comment("Ticks between a placer's placements in continuous mode.")
                .defineInRange("intervalTicks", CONFIG.intDefault("world.placer.intervalTicks"), 1, 200);
        PLACER_DROP_ENTITY_CAP = builder
                .comment("Item entities lying in the space in front of a placer in drop mode at which it pauses, so",
                        "it cannot flood the world with entities. It drops again once fewer lie there.")
                .defineInRange("dropEntityCap", CONFIG.intDefault("world.placer.dropEntityCap"), 1, 256);
        builder.pop();
        builder.push("breaker");
        BREAKER_INTERVAL = builder.comment("Ticks between a breaker's breaks in continuous mode.")
                .defineInRange("intervalTicks", CONFIG.intDefault("world.breaker.intervalTicks"), 1, 200);
        BREAKER_MAX_HARDNESS = builder
                .comment("The hardest block a breaker breaks (stone 1.5, iron ore 3, obsidian 50). Unbreakable",
                        "blocks and the maxtech_storage:breaker_blacklist tag are never broken.")
                .defineInRange("maxHardness", CONFIG.doubleDefault("world.breaker.maxHardness"), 0.0, 100.0);
        builder.pop();
        builder.pop();
    }

    /** Whether devices ask land claims before using the block they face ({@code claims.enabled}). */
    public static final BooleanSupplier CLAIMS_ENABLED;
    /** Whether devices nobody owns ask too ({@code claims.checkUnowned}). */
    public static final BooleanSupplier CLAIMS_CHECK_UNOWNED;
    /** Seconds a device keeps a claim answer before asking again on its next use ({@code claims.cacheSeconds}). */
    public static final IntSupplier CLAIMS_CACHE_SECONDS;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("claims");
        CLAIMS_ENABLED = builder
                .comment("Whether ports, sensors, pattern providers and P2P outputs ask land claim and protection",
                        "mods before using the block they face, as their owner right-clicking it. A refused device",
                        "leaves that block alone. Only blocks with a block entity or an item, fluid or FE handler are",
                        "asked about, since other mods also react to the right-click; plain blocks are always allowed.",
                        "Placers and breakers always ask through the place and break events.")
                .define("enabled", CONFIG.booleanDefault("claims.enabled"));
        CLAIMS_CHECK_UNOWNED = builder
                .comment("Whether devices nobody owns ask too, as an anonymous player. Off lets them work as before.")
                .define("checkUnowned", CONFIG.booleanDefault("claims.checkUnowned"));
        CLAIMS_CACHE_SECONDS = builder
                .comment("Seconds a device keeps an answer before asking again on its next use. A refused device",
                        "asks again after this time, so a claim lifted or granted is picked up without polling.")
                .defineInRange("cacheSeconds", CONFIG.intDefault("claims.cacheSeconds"), 1, 3600);
        builder.pop();
    }

    private static LongSupplier cellCapacity(ConfigBuilder builder, int tier) {
        return builder.comment("MU a tier-" + tier + " energy storage block holds for its network.").defineInRange(
                "tier" + tier + ".capacity", CONFIG.longDefault("energy.cells.tier" + tier + ".capacity"), 1L,
                1_000_000_000_000L);
    }

    private static IntSupplier cellTransfer(ConfigBuilder builder, int tier) {
        return builder
                .comment("MU one transfer into or out of a tier-" + tier + " energy storage block may move, from",
                        "neighbours and from its network's surplus. The network itself drains it without a limit.")
                .defineInRange("tier" + tier + ".maxTransfer",
                        CONFIG.intDefault("energy.cells.tier" + tier + ".maxTransfer"), 1, Integer.MAX_VALUE);
    }

    /** Fewest ticks between two samples of a recorded value ({@code history.minIntervalTicks}, M7L2 charts). */
    public static final IntSupplier HISTORY_MIN_INTERVAL_TICKS;
    /** Most recorded value series on one network ({@code history.maxSeriesPerNetwork}, M7L2 charts). */
    public static final IntSupplier HISTORY_MAX_SERIES;
    /** Most pinned values one Logic Controller records ({@code history.pinSeriesPerController}, M7L2 charts). */
    public static final IntSupplier HISTORY_PIN_SERIES;
    /** Ticks between two samples of a recorded pinned value ({@code history.pinIntervalTicks}, M7L2 charts). */
    public static final IntSupplier HISTORY_PIN_INTERVAL_TICKS;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("history");
        HISTORY_MIN_INTERVAL_TICKS = builder
                .comment("Fewest ticks between two samples of a recorded value (history chart displays, pinned",
                        "values). A chart set to a shorter interval records at this one. Each sample is one read on",
                        "the network's scheduler; nothing runs between samples.")
                .defineInRange("minIntervalTicks", CONFIG.intDefault("history.minIntervalTicks"), 20, 72_000);
        HISTORY_MAX_SERIES = builder
                .comment("Most values recorded on one network at a time: each history chart display and each",
                        "recorded pinned value is one. A series keeps 480 numbers (3.75 KiB in memory and in the",
                        "save); recorders past the limit record nothing and say so.")
                .defineInRange("maxSeriesPerNetwork", CONFIG.intDefault("history.maxSeriesPerNetwork"), 0, 1024);
        HISTORY_PIN_SERIES = builder
                .comment("Most pinned values one Logic Controller records for the sparklines in its rule detail,",
                        "in rule order; they count against maxSeriesPerNetwork. 0 records none.")
                .defineInRange("pinSeriesPerController", CONFIG.intDefault("history.pinSeriesPerController"), 0, 64);
        HISTORY_PIN_INTERVAL_TICKS = builder
                .comment("Ticks between two samples of a Logic Controller's recorded pinned values; 1200 (a minute)",
                        "keeps four hours in full and 24 hours in six-minute steps. Never below minIntervalTicks.")
                .defineInRange("pinIntervalTicks", CONFIG.intDefault("history.pinIntervalTicks"), 20, 72_000);
        builder.pop();
    }

    /** Stored FE in percent below which power priority 5 devices pause ({@code loadShedding.optionalPercent}). */
    public static final IntSupplier SHED_OPTIONAL_PERCENT;
    /** The same for power priority 4 ({@code loadShedding.lowPercent}). */
    public static final IntSupplier SHED_LOW_PERCENT;
    /** The same for power priority 3 ({@code loadShedding.normalPercent}). */
    public static final IntSupplier SHED_NORMAL_PERCENT;
    /** The same for power priority 2 ({@code loadShedding.highPercent}); 0 by default, so they never pause. */
    public static final IntSupplier SHED_HIGH_PERCENT;
    /** Percent above a threshold the FE must reach before its devices resume ({@code loadShedding.resumeMargin}). */
    public static final IntSupplier SHED_RESUME_MARGIN;

    static {
        ConfigBuilder builder = BUILDER;
        builder.comment("Load shedding: when a network's stored MU falls below a threshold, in percent of its",
                "capacity, its devices of that power priority and every less important one pause until the MU is",
                "back at the threshold plus resumeMargin. Essential devices (priority 1) never pause; 0 turns a",
                "threshold off. Networks with a creative energy source never shed.").push("loadShedding");
        SHED_OPTIONAL_PERCENT = builder.comment("Below this, devices of power priority 5 (optional) pause.")
                .defineInRange("optionalPercent", CONFIG.intDefault("loadShedding.optionalPercent"), 0, 100);
        SHED_LOW_PERCENT = builder.comment("Below this, devices of power priority 4 (low) pause.")
                .defineInRange("lowPercent", CONFIG.intDefault("loadShedding.lowPercent"), 0, 100);
        SHED_NORMAL_PERCENT = builder.comment("Below this, devices of power priority 3 (normal) pause.")
                .defineInRange("normalPercent", CONFIG.intDefault("loadShedding.normalPercent"), 0, 100);
        SHED_HIGH_PERCENT = builder.comment("Below this, devices of power priority 2 (high) pause; 0 never.")
                .defineInRange("highPercent", CONFIG.intDefault("loadShedding.highPercent"), 0, 100);
        SHED_RESUME_MARGIN = builder
                .comment("Paused devices resume once the MU is this many percent above their threshold, so they",
                        "do not flicker on and off around it.")
                .defineInRange("resumeMargin", CONFIG.intDefault("loadShedding.resumeMargin"), 0, 100);
        builder.pop();
    }

    /** Fewest ticks between two value updates a screen sends its viewers ({@code display.updateTicks}). */
    public static final IntSupplier DISPLAY_UPDATE_TICKS;
    /** Most panels along each side of one wall screen ({@code display.maxWallSize}). */
    public static final IntSupplier DISPLAY_MAX_WALL_SIZE;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("display");
        DISPLAY_UPDATE_TICKS = builder
                .comment("Fewest ticks between two value updates a display screen sends to the players who see it.",
                        "Updates go only to players within view distance and only when the value changed.")
                .defineInRange("updateTicks", CONFIG.intDefault("display.updateTicks"), 1, 1200);
        DISPLAY_MAX_WALL_SIZE = builder
                .comment("Most display panels along each side of one wall screen; larger groups split into several.")
                .defineInRange("maxWallSize", CONFIG.intDefault("display.maxWallSize"), 1, 16);
        builder.pop();
    }

    /** The network's stored FE at or below this percent raises the low power alarm ({@code alarms.lowPowerPercent}). */
    public static final IntSupplier ALARM_LOW_POWER_PERCENT;
    /** Bytes used at or above this percent raise the storage alarm ({@code alarms.storageFullPercent}). */
    public static final IntSupplier ALARM_STORAGE_FULL_PERCENT;
    /** Ticks between two looks at stuck crafts while jobs run ({@code alarms.recheckTicks}). */
    public static final IntSupplier ALARM_RECHECK_TICKS;
    /** Ticks a cleared alarm stays listed ({@code alarms.clearedKeepTicks}). */
    public static final IntSupplier ALARM_CLEARED_KEEP_TICKS;
    /**
     * Percent past its threshold a held low power or storage alarm must go to clear
     * ({@code alarms.clearMarginPercent}).
     */
    public static final IntSupplier ALARM_CLEAR_MARGIN;
    /** Ticks after a raise in which raising the same alarm again is not announced ({@code alarms.reannounceTicks}). */
    public static final IntSupplier ALARM_REANNOUNCE_TICKS;
    /** Whether a raised alarm is also sent to chat ({@code alarms.chat}). */
    public static final BooleanSupplier ALARM_CHAT;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("alarms");
        ALARM_LOW_POWER_PERCENT = builder
                .comment("A network's alarm panel raises low power when its stored MU is at or below this percent of",
                        "its capacity.")
                .defineInRange("lowPowerPercent", CONFIG.intDefault("alarms.lowPowerPercent"), 0, 100);
        ALARM_STORAGE_FULL_PERCENT = builder
                .comment("The alarm panel raises storage nearly full when the cells' bytes in use reach this percent.")
                .defineInRange("storageFullPercent", CONFIG.intDefault("alarms.storageFullPercent"), 1, 100);
        ALARM_RECHECK_TICKS = builder
                .comment("While crafting jobs run, ticks between two looks for stuck crafts; no event says a machine",
                        "filled up, so the panel looks on this cadence and stops once no job runs. Content changes",
                        "look at how full storage is at most this often too.")
                .defineInRange("recheckTicks", CONFIG.intDefault("alarms.recheckTicks"), 20, 12000);
        ALARM_CLEARED_KEEP_TICKS = builder.comment("Ticks an alarm that cleared stays listed, dimmed, before it goes.")
                .defineInRange("clearedKeepTicks", CONFIG.intDefault("alarms.clearedKeepTicks"), 0, 288000);
        ALARM_CLEAR_MARGIN = builder
                .comment("A held low power or storage nearly full alarm clears only once the value is this many",
                        "percent past its threshold, so a value swinging round the threshold does not raise it again",
                        "and again.")
                .defineInRange("clearMarginPercent", CONFIG.intDefault("alarms.clearMarginPercent"), 0, 50);
        ALARM_REANNOUNCE_TICKS = builder
                .comment("An alarm raised again within this many ticks of its last raise is listed as new but not",
                        "sent to the network's log, logic remotes and chat again.")
                .defineInRange("reannounceTicks", CONFIG.intDefault("alarms.reannounceTicks"), 0, 288000);
        ALARM_CHAT = builder
                .comment("Whether a raised alarm is also sent as a chat line to the online players who may see the",
                        "network. It always goes to the network's log and its logic remotes.")
                .define("chat", CONFIG.booleanDefault("alarms.chat"));
        builder.pop();
    }

    /**
     * Ticks void overflow and destroyed cells stay in a network's recovery bin; 0 turns the bin off
     * ({@code recovery.keepTicks}).
     */
    public static final IntSupplier RECOVERY_KEEP_TICKS;
    /** Rows a network's recovery bin holds at most; the oldest go first ({@code recovery.maxEntries}). */
    public static final IntSupplier RECOVERY_MAX_ENTRIES;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("recovery");
        RECOVERY_KEEP_TICKS = builder
                .comment("Ticks that items and fluids destroyed by a cell's void overflow add-on, or lost with a",
                        "destroyed storage cell, stay in the network controller's recovery bin, where the network's",
                        "owner or an admin can put them back. 0 turns the bin off.")
                .defineInRange("keepTicks", CONFIG.intDefault("recovery.keepTicks"), 0, 72000);
        RECOVERY_MAX_ENTRIES = builder
                .comment("Rows a network's recovery bin holds at most (one per item and quarter of the keep time);",
                        "past it the oldest rows are dropped.")
                .defineInRange("maxEntries", CONFIG.intDefault("recovery.maxEntries"), 1, 256);
        builder.pop();
    }

    /**
     * Ticks a pasted setup blueprint ghost stays saved if never finished; 0 keeps them until removed
     * ({@code setup.ghostKeepTicks}).
     */
    public static final IntSupplier SETUP_GHOST_KEEP_TICKS;
    /** Ghosts saved for all players together; the oldest go first ({@code setup.maxGhostsTotal}). */
    public static final IntSupplier SETUP_MAX_GHOSTS_TOTAL;
    /**
     * Blocks and parts an auto-build places per server tick, over all players together ({@code setup.buildPerTick}).
     */
    public static final IntSupplier SETUP_BUILD_PER_TICK;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("setup");
        SETUP_GHOST_KEEP_TICKS = builder
                .comment("Ticks a pasted setup blueprint ghost stays saved if it is never finished (72000 is three",
                        "in-game days). 0 keeps ghosts until the player removes them.")
                .defineInRange("ghostKeepTicks", CONFIG.intDefault("setup.ghostKeepTicks"), 0, 100_000_000);
        SETUP_MAX_GHOSTS_TOTAL = builder
                .comment("Setup blueprint ghosts saved for all players together; past it the oldest are dropped.")
                .defineInRange("maxGhostsTotal", CONFIG.intDefault("setup.maxGhostsTotal"), 1, 10_000);
        SETUP_BUILD_PER_TICK = builder
                .comment("Blocks and cable parts an auto-build from a setup blueprint ghost places per server tick,",
                        "over all players together.")
                .defineInRange("buildPerTick", CONFIG.intDefault("setup.buildPerTick"), 1, 64);
        builder.pop();
    }

    /** Network snapshots kept per network ({@code snapshots.maxPerNetwork}); a save past it is refused. */
    public static final IntSupplier SNAPSHOTS_MAX_PER_NETWORK;
    /** Network snapshots kept on the server in all ({@code snapshots.maxTotal}); a save past it is refused. */
    public static final IntSupplier SNAPSHOTS_MAX_TOTAL;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("snapshots");
        SNAPSHOTS_MAX_PER_NETWORK = builder
                .comment("Settings snapshots an admin may keep of one storage network (/maxtech network snapshot);",
                        "past it a new save is refused until an older one is deleted.")
                .defineInRange("maxPerNetwork", CONFIG.intDefault("snapshots.maxPerNetwork"), 1, 64);
        SNAPSHOTS_MAX_TOTAL = builder
                .comment("Settings snapshots kept on the server for all networks together; past it a new save is",
                        "refused until an older one is deleted.")
                .defineInRange("maxTotal", CONFIG.intDefault("snapshots.maxTotal"), 1, 4096);
        builder.pop();
    }

    private static volatile @Nullable ConfigFile file;

    private static IntSupplier memorySlots(ConfigBuilder builder, String casing) {
        String path = "cells.casings." + casing + ".memorySlots";
        return builder.comment("Memory module slots of the " + casing + " casing.")
                .defineInRange(casing + ".memorySlots", CONFIG.intDefault(path), 1, 8);
    }

    private static IntSupplier addonSlots(ConfigBuilder builder, String casing) {
        String path = "cells.casings." + casing + ".addonSlots";
        return builder.comment("Add-on slots of the " + casing + " casing.").defineInRange(casing + ".addonSlots",
                CONFIG.intDefault(path), 0, 4);
    }

    private StorageServerConfig() {
    }

    /** mB each of an interface's three fluid stock tanks holds ({@code interface.fluidTankMb}). */
    public static final IntSupplier INTERFACE_FLUID_TANK_MB;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("interface");
        // At most 32,000: the interface's GUI syncs tank amounts in vanilla data slots, which travel as shorts.
        INTERFACE_FLUID_TANK_MB = builder
                .comment("mB each of an interface's three fluid stock tanks holds; a fluid stock slot keeps at most",
                        "this much filled.")
                .defineInRange("fluidTankMb", CONFIG.intDefault("interface.fluidTankMb"), 1000, 32_000);
        builder.pop();
    }

    /** Per cent of the FE a P2P energy link loses on the way ({@code p2p.energyLossPercent}). */
    public static final IntSupplier P2P_ENERGY_LOSS_PERCENT;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("p2p");
        P2P_ENERGY_LOSS_PERCENT = builder.comment(
                "Per cent of the energy a P2P energy link loses between its input and its outputs, whatever the",
                "distance.").defineInRange("energyLossPercent", CONFIG.intDefault("p2p.energyLossPercent"), 0, 50);
        builder.pop();
    }

    /** Per puzzle path ({@code signal_routing}): {@code research.puzzles.<name>.enabled}. */
    private static final Map<String, BooleanSupplier> PUZZLE_ENABLED = new HashMap<>();
    /** Per puzzle path: {@code research.puzzles.<name>.difficultyOffset}. */
    private static final Map<String, IntSupplier> PUZZLE_OFFSET = new HashMap<>();

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("research");
        builder.push("puzzles");
        // The four puzzle types of M6b area D, by registry path; config must be declared before the types register.
        for (String path : List.of("signal_routing", "mask_alignment", "byte_packing", "waveform_tuning")) {
            String name = camel(path);
            String prefix = "research.puzzles." + name + ".";
            builder.push(name);
            PUZZLE_ENABLED.put(path,
                    builder.comment(
                            "Whether research nodes offer this puzzle; off leaves those nodes to their other options.")
                            .define("enabled", CONFIG.booleanDefault(prefix + "enabled")));
            PUZZLE_OFFSET.put(path,
                    builder.comment("Added to the difficulty a node asks for (easy 0, medium 1, hard 2), then clamped.")
                            .defineInRange("difficultyOffset", CONFIG.intDefault(prefix + "difficultyOffset"), -2, 2));
            builder.pop();
        }
        builder.pop();
        builder.pop();
    }

    private static String camel(String path) {
        StringBuilder out = new StringBuilder(path.length());
        boolean upper = false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '_') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }

    /** Whether research offers the puzzle {@code id}; true for a puzzle no config names (another mod's). */
    public static boolean puzzleEnabled(Identifier id) {
        BooleanSupplier value = MaxtechStorage.MOD_ID.equals(id.getNamespace())
                ? PUZZLE_ENABLED.get(id.getPath())
                : null;
        return value == null || value.getAsBoolean();
    }

    /** The difficulty offset of the puzzle {@code id}; 0 for a puzzle no config names. */
    public static int puzzleDifficultyOffset(Identifier id) {
        IntSupplier value = MaxtechStorage.MOD_ID.equals(id.getNamespace()) ? PUZZLE_OFFSET.get(id.getPath()) : null;
        return value == null ? 0 : value.getAsInt();
    }

    /** Channels a tier-3 storage cable carries ({@code network.channels.tier3}; M6d cables). */
    public static final IntSupplier CABLE_CHANNELS_TIER_3;
    /** Channels a tier-4 storage cable carries ({@code network.channels.tier4}; M6d cables). */
    public static final IntSupplier CABLE_CHANNELS_TIER_4;
    /** Channels the devices behind a tier-1 hub share ({@code network.channels.hubTier1}; M7N item 2). */
    public static final IntSupplier HUB_CHANNELS_TIER_1;
    /** Channels the devices behind a tier-2 hub share ({@code network.channels.hubTier2}). */
    public static final IntSupplier HUB_CHANNELS_TIER_2;
    /** Channels the devices behind a tier-3 hub share ({@code network.channels.hubTier3}). */
    public static final IntSupplier HUB_CHANNELS_TIER_3;
    /** Whether channels limit networks ({@code network.channels.mode}; B09 item 12), read through {@link Channels}. */
    public static final Supplier<ChannelMode> CHANNEL_MODE;

    static {
        BUILDER.push("network");
        BUILDER.push("channels");
        CABLE_CHANNELS_TIER_3 = BUILDER.comment("Channels a tier-3 storage cable carries.").defineInRange("tier3",
                CONFIG.intDefault("network.channels.tier3"), 1, 1_000_000);
        CABLE_CHANNELS_TIER_4 = BUILDER.comment("Channels a tier-4 storage cable carries.").defineInRange("tier4",
                CONFIG.intDefault("network.channels.tier4"), 1, 1_000_000);
        HUB_CHANNELS_TIER_1 = BUILDER
                .comment("Channels the devices behind a tier-1 hub share. They count against the hub instead of the",
                        "links beyond it; the hub itself uses one channel towards the controller. Read at rebuilds.")
                .defineInRange("hubTier1", CONFIG.intDefault("network.channels.hubTier1"), 1, 1_000_000);
        HUB_CHANNELS_TIER_2 = BUILDER.comment("Channels the devices behind a tier-2 hub share.")
                .defineInRange("hubTier2", CONFIG.intDefault("network.channels.hubTier2"), 1, 1_000_000);
        HUB_CHANNELS_TIER_3 = BUILDER.comment("Channels the devices behind a tier-3 hub share.")
                .defineInRange("hubTier3", CONFIG.intDefault("network.channels.hubTier3"), 1, 1_000_000);
        CHANNEL_MODE = BUILDER.comment(
                "limited: cables, hubs, root links and networks without a controller carry the channels above.",
                "infinite: no channel limits; tiers still connect and show load. The zone size and the team caps",
                "still apply. Networks count their channels again once when this changes.")
                .defineEnum("mode", CONFIG.enumDefault("network.channels.mode", ChannelMode.class));
        BUILDER.pop();
        BUILDER.pop();
    }

    /**
     * Where the built-in presets and the declared values disagree; empty when they agree. Energy keys
     * ({@link #isEnergyKey}) must be in every preset.
     */
    public static List<String> presetProblems() {
        return CONFIG.presetProblems(StorageServerConfig::isEnergyKey);
    }

    /**
     * Whether a dotted config path is an energy number (MU amounts, rates, costs, the FE keys read for one version,
     * load shedding), going by the naming rules those keys follow. Pure.
     */
    public static boolean isEnergyKey(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        String last = lower.substring(lower.lastIndexOf('.') + 1);
        return lower.contains("energy") || lower.contains("power") || lower.startsWith("loadshedding.")
                || last.startsWith("muper") || last.startsWith("feper") || last.endsWith("buffermu")
                || last.endsWith("bufferfe") || last.equals("inputpertick") || last.equals("maxinput")
                || last.startsWith("surcharge") || last.startsWith("drain");
    }

    /**
     * The values Storage's guide pages show with {@code <Config key="..." />}; see {@link PresetConfig#guideSource()}.
     */
    public static GuideConfigValues.Source guideSource() {
        return CONFIG.guideSource();
    }

    /**
     * The registered server config file, empty before {@link #register()}. The guide's {@code <Config>} tag reads its
     * paths and values through it ({@code guide.GuideConfigText}). Any thread.
     */
    public static Optional<ConfigFile> configFile() {
        return Optional.ofNullable(file);
    }

    /**
     * Registers the file and preset handling; called once from {@link MaxtechStorage#init()}. Loading the server's file
     * (after the preset applied) caches the moved values and warns once about every moved key that is still set
     * ({@link MovedKeys}), and again only when its value changes.
     */
    public static void register() {
        ConfigFile registered = CONFIG.register();
        Set<String> warned = ConcurrentHashMap.newKeySet();
        registered.onLoad(path -> {
            MovedKeys.refresh();
            Channels.modeLoaded(CHANNEL_MODE.get());
            if (path != null) {
                for (String warning : MovedKeys.warnings()) {
                    if (warned.add(warning)) {
                        LOGGER.warn(warning);
                    }
                }
            }
        });
        file = registered;
    }

    /** Whether the server config file is loaded: on a server, or on a client in a world. Any thread. */
    static boolean loaded() {
        ConfigFile current = file;
        return current != null && current.isLoaded();
    }

    /**
     * {@code value} as configured, or {@code fallback} while no server config is loaded: on a client outside a world,
     * or when the world closes between the check and the read. Any thread; a client in a world reads the values the
     * server sent it.
     */
    public static long loadedOr(LongSupplier value, long fallback) {
        ConfigFile current = file;
        if (current == null || !current.isLoaded()) {
            return fallback;
        }
        try {
            return value.getAsLong();
        } catch (IllegalStateException | NullPointerException e) {
            // Unloaded after isLoaded() answered (singleplayer's server closing the world): reading throws
            // IllegalStateException, or unboxing a value cleared between ConfigValue.get()'s two reads throws
            // NullPointerException, as in Lib's ServerConfig.loadedOr.
            return fallback;
        }
    }

    /** Ember quartz veins tried per chunk in the Nether; 0 turns the ore off. Read while chunks generate. */
    public static final IntSupplier EMBER_QUARTZ_VEINS;
    /** Blocks per ember quartz vein, as vanilla's ore size. */
    public static final IntSupplier EMBER_QUARTZ_VEIN_SIZE;
    /** Lowest and highest y an ember quartz vein centre can have; clamped to the dimension's height. */
    public static final IntSupplier EMBER_QUARTZ_MIN_Y;
    public static final IntSupplier EMBER_QUARTZ_MAX_Y;
    /** One deep data geode per this many chunks on average; 0 turns geodes off. */
    public static final IntSupplier DEEP_DATA_GEODE_RARITY;
    /** Lowest and highest y a deep data geode's origin can have; clamped to the dimension's height. */
    public static final IntSupplier DEEP_DATA_GEODE_MIN_Y;
    public static final IntSupplier DEEP_DATA_GEODE_MAX_Y;
    /** A budding data crystal grows a bud one random tick in this many, as budding amethyst's 1 in 5. */
    public static final IntSupplier DATA_CRYSTAL_GROWTH_CHANCE;
    /** Per cent faster a machine runs with a speed module ({@code machines.modules.speedBonusPercent}). */
    public static final IntSupplier SPEED_MODULE_BONUS_PERCENT;
    /** Per cent more FE a run costs with a speed module ({@code machines.modules.speedEnergyPercent}). */
    public static final IntSupplier SPEED_MODULE_ENERGY_PERCENT;
    /** Per cent less FE a run costs with an efficiency module ({@code machines.modules.efficiencySavingPercent}). */
    public static final IntSupplier EFFICIENCY_MODULE_SAVING_PERCENT;
    /** Bytes a memory module IV adds to its cell ({@code cells.memoryBytes.tier4}). */
    public static final LongSupplier MEMORY_4_BYTES;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("worldgen");
        builder.push("emberQuartz");
        EMBER_QUARTZ_VEINS = builder
                .comment("Ember quartz veins tried per chunk in the Nether; 0 turns the ore off.",
                        "Ember quartz replaces netherrack only. Applies to chunks generated after a change.")
                .defineInRange("veinsPerChunk", CONFIG.intDefault("worldgen.emberQuartz.veinsPerChunk"), 0, 64);
        EMBER_QUARTZ_VEIN_SIZE = builder.comment("Blocks per vein, as vanilla's ore size.").defineInRange("veinSize",
                CONFIG.intDefault("worldgen.emberQuartz.veinSize"), 1, 64);
        EMBER_QUARTZ_MIN_Y = builder.comment("Lowest y of a vein's centre.").defineInRange("minY",
                CONFIG.intDefault("worldgen.emberQuartz.minY"), -2032, 2031);
        EMBER_QUARTZ_MAX_Y = builder.comment("Highest y of a vein's centre; below minY turns the ore off.")
                .defineInRange("maxY", CONFIG.intDefault("worldgen.emberQuartz.maxY"), -2032, 2031);
        builder.pop();
        builder.push("deepDataGeode");
        DEEP_DATA_GEODE_RARITY = builder
                .comment("One deep data geode per this many chunks on average, in the biomes of the",
                        "maxtech_storage:has_deep_data_geode biome tag and the dimensions of the dimension type tag of",
                        "the same name; 0 turns geodes off. Applies to chunks generated after a change.")
                .defineInRange("rarity", CONFIG.intDefault("worldgen.deepDataGeode.rarity"), 0, 10_000);
        DEEP_DATA_GEODE_MIN_Y = builder.comment("Lowest y of a geode's origin.").defineInRange("minY",
                CONFIG.intDefault("worldgen.deepDataGeode.minY"), -2032, 2031);
        DEEP_DATA_GEODE_MAX_Y = builder.comment("Highest y of a geode's origin; below minY turns geodes off.")
                .defineInRange("maxY", CONFIG.intDefault("worldgen.deepDataGeode.maxY"), -2032, 2031);
        builder.pop();
        builder.pop();
        builder.push("materials");
        builder.push("dataCrystal");
        DATA_CRYSTAL_GROWTH_CHANCE = builder
                .comment("A budding data crystal grows or enlarges a bud on one random tick in this many",
                        "(budding amethyst: 5).")
                .defineInRange("growthChance", CONFIG.intDefault("materials.dataCrystal.growthChance"), 1, 1000);
        builder.pop();
        builder.pop();
        builder.push("machines");
        builder.push("modules");
        SPEED_MODULE_BONUS_PERCENT = builder
                .comment("Per cent faster a lithograph, fabricator or assembler runs with a speed module; 100 halves",
                        "a run's time.")
                .defineInRange("speedBonusPercent", CONFIG.intDefault("machines.modules.speedBonusPercent"), 0, 1000);
        SPEED_MODULE_ENERGY_PERCENT = builder.comment("Per cent more MU a run costs in all with a speed module.")
                .defineInRange("speedEnergyPercent", CONFIG.intDefault("machines.modules.speedEnergyPercent"), 0, 1000);
        EFFICIENCY_MODULE_SAVING_PERCENT = builder.comment("Per cent less MU a run costs with an efficiency module.")
                .defineInRange("efficiencySavingPercent", CONFIG.intDefault("machines.modules.efficiencySavingPercent"),
                        0, 90);
        builder.pop();
        builder.pop();
        builder.push("cells");
        builder.push("memoryBytes");
        MEMORY_4_BYTES = builder.comment("Bytes a memory module IV adds to its cell.").defineInRange("tier4",
                CONFIG.longDefault("cells.memoryBytes.tier4"), 1L, 1L << 40);
        builder.pop();
        builder.pop();
    }

    /** MU a subnet controller holds for its subnet ({@code network.subnet.bufferMu}); see {@link MovedKeys}. */
    public static final LongSupplier SUBNET_BUFFER_MU;
    public static final LongSupplier SUBNET_BUFFER_MU_KEY;
    /** FE per insert a subnet controller's buffer takes ({@code network.subnet.inputPerTick}). */
    public static final LongSupplier SUBNET_INPUT_PER_TICK;
    /** Devices a subnet holds besides its controller ({@code network.subnet.maxDevices}). */
    public static final IntSupplier SUBNET_MAX_DEVICES;
    /** Blocks a tier-1 antenna reaches ({@code signal.antenna.rangeTier1}); tier 3 reaches the whole dimension. */
    public static final IntSupplier ANTENNA_RANGE_TIER_1;
    /** Blocks a tier-2 antenna reaches ({@code signal.antenna.rangeTier2}). */
    public static final IntSupplier ANTENNA_RANGE_TIER_2;
    /** Channels of a tier-1 antenna link ({@code signal.antenna.channelsTier1}). */
    public static final IntSupplier ANTENNA_CHANNELS_TIER_1;
    /** Channels of a tier-2 antenna link ({@code signal.antenna.channelsTier2}). */
    public static final IntSupplier ANTENNA_CHANNELS_TIER_2;
    /** Channels of a tier-3 antenna link ({@code signal.antenna.channelsTier3}). */
    public static final IntSupplier ANTENNA_CHANNELS_TIER_3;
    /** Wireless range of the first wireless access mount ({@code signal.wireless.baseRange}). */
    public static final IntSupplier WIRELESS_BASE_RANGE;
    /** Wireless range each further mount adds ({@code signal.wireless.rangePerMount}). */
    public static final IntSupplier WIRELESS_RANGE_PER_MOUNT;
    /** Most wireless range a cluster reaches ({@code signal.wireless.maxRange}). */
    public static final IntSupplier WIRELESS_MAX_RANGE;
    /** FE a wireless terminal holds ({@code signal.wireless.energyCapacity}). */
    public static final IntSupplier WIRELESS_ENERGY_CAPACITY;
    /** FE per transfer a charger puts into a wireless terminal ({@code signal.wireless.maxInput}). */
    public static final IntSupplier WIRELESS_MAX_INPUT;
    /** FE a wireless terminal spends per insert or extract ({@code signal.wireless.energyPerOperation}). */
    public static final IntSupplier WIRELESS_ENERGY_PER_OPERATION;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("signal");
        builder.push("antenna");
        ANTENNA_RANGE_TIER_1 = builder.comment("Blocks between two linked antennas when either is tier 1.")
                .defineInRange("rangeTier1", CONFIG.intDefault("signal.antenna.rangeTier1"), 1, 30_000_000);
        ANTENNA_RANGE_TIER_2 = builder
                .comment("Blocks between two linked antennas when the lower is tier 2; tier 3 reaches the whole",
                        "dimension.")
                .defineInRange("rangeTier2", CONFIG.intDefault("signal.antenna.rangeTier2"), 1, 30_000_000);
        ANTENNA_CHANNELS_TIER_1 = builder
                .comment("Channels of a link whose lower antenna is tier 1; a paired link is one route link.")
                .defineInRange("channelsTier1", CONFIG.intDefault("signal.antenna.channelsTier1"), 1, 1_000_000);
        ANTENNA_CHANNELS_TIER_2 = builder.comment("Channels of a link whose lower antenna is tier 2.")
                .defineInRange("channelsTier2", CONFIG.intDefault("signal.antenna.channelsTier2"), 1, 1_000_000);
        ANTENNA_CHANNELS_TIER_3 = builder.comment("Channels of a link between two tier-3 antennas.")
                .defineInRange("channelsTier3", CONFIG.intDefault("signal.antenna.channelsTier3"), 1, 1_000_000);
        builder.pop();
        builder.push("wireless");
        WIRELESS_BASE_RANGE = builder
                .comment("Blocks a wireless terminal reaches from the controller core or an antenna of its network",
                        "with one wireless access mount in the cluster.")
                .defineInRange("baseRange", CONFIG.intDefault("signal.wireless.baseRange"), 1, 30_000_000);
        WIRELESS_RANGE_PER_MOUNT = builder.comment("Blocks each further wireless access mount adds.")
                .defineInRange("rangePerMount", CONFIG.intDefault("signal.wireless.rangePerMount"), 0, 30_000_000);
        WIRELESS_MAX_RANGE = builder.comment("Most blocks a wireless terminal reaches, however many mounts.")
                .defineInRange("maxRange", CONFIG.intDefault("signal.wireless.maxRange"), 1, 30_000_000);
        WIRELESS_ENERGY_CAPACITY = builder
                .comment("MU a wireless terminal holds. It charges in any FE charger; an empty terminal does not open.")
                .defineInRange("energyCapacity", CONFIG.intDefault("signal.wireless.energyCapacity"), 1,
                        Integer.MAX_VALUE);
        WIRELESS_MAX_INPUT = builder.comment("MU per transfer a charger may put into a wireless terminal.")
                .defineInRange("maxInput", CONFIG.intDefault("signal.wireless.maxInput"), 1, Integer.MAX_VALUE);
        WIRELESS_ENERGY_PER_OPERATION = builder
                .comment("MU a wireless terminal spends on each insert or extract through it, on top of the",
                        "network's own cost.")
                .defineInRange("energyPerOperation", CONFIG.intDefault("signal.wireless.energyPerOperation"), 0,
                        Integer.MAX_VALUE);
        builder.pop();
        builder.pop();
    }

    /** Devices a team's networks may hold together in one level; 0 for no cap ({@code caps.devicesPerTeam}). */
    public static final IntSupplier CAP_DEVICES_PER_TEAM;
    /** Zones a team's networks may have together in one level; 0 for no cap ({@code caps.zonesPerTeam}). */
    public static final IntSupplier CAP_ZONES_PER_TEAM;
    /** Cell bytes a team's networks may use together in one level; 0 for no cap ({@code caps.bytesPerTeam}). */
    public static final LongSupplier CAP_BYTES_PER_TEAM;

    /** Seconds of history the signal probe's graph shows ({@code tools.probe.windowSeconds}). */
    public static final IntSupplier PROBE_WINDOW_SECONDS;
    /** Ticks between two probe samples while its screen is open ({@code tools.probe.sampleTicks}). */
    public static final IntSupplier PROBE_SAMPLE_TICKS;
    /** Percent of a skip token's points a solved puzzle cartridge gives ({@code tools.cartridge.creditPercent}). */
    public static final IntSupplier CARTRIDGE_CREDIT_PERCENT;
    /** Difficulty (0 easy to 2 hard) of the puzzle a new cartridge carries ({@code tools.cartridge.difficulty}). */
    public static final IntSupplier CARTRIDGE_DIFFICULTY;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("network");
        builder.push("subnet");
        SUBNET_BUFFER_MU_KEY = builder.comment("MU a subnet controller holds for its subnet.").defineInRange("bufferMu",
                CONFIG.longDefault("network.subnet.bufferMu"), 0L, Long.MAX_VALUE);
        SUBNET_BUFFER_MU = moved(builder, "network.subnet.bufferFe", "bufferFe", "network.subnet.bufferMu",
                SUBNET_BUFFER_MU_KEY);
        SUBNET_INPUT_PER_TICK = builder.comment("Most MU a subnet controller's buffer takes in one insert.")
                .defineInRange("inputPerTick", CONFIG.longDefault("network.subnet.inputPerTick"), 0L, Long.MAX_VALUE);
        SUBNET_MAX_DEVICES = builder.comment("Devices a subnet holds besides its controller; more wait for room.")
                .defineInRange("maxDevices", CONFIG.intDefault("network.subnet.maxDevices"), 0, 100_000);
        builder.pop();
        builder.pop();
        builder.push("caps");
        CAP_DEVICES_PER_TEAM = builder
                .comment("Devices the networks of one team (research team, else owner) may hold together in one",
                        "level; devices over it are turned away until room frees. 0 turns the cap off.")
                .defineInRange("devicesPerTeam", CONFIG.intDefault("caps.devicesPerTeam"), 0, 10_000_000);
        CAP_ZONES_PER_TEAM = builder
                .comment("Zones the networks of one team may have together in one level; nodes past it get no",
                        "route and their devices wait. 0 turns the cap off.")
                .defineInRange("zonesPerTeam", CONFIG.intDefault("caps.zonesPerTeam"), 0, 1_000_000);
        CAP_BYTES_PER_TEAM = builder
                .comment("Cell bytes the networks of one team may use together in one level; at the cap, storing",
                        "into the networks stops (taking out still works). 0 turns the cap off.")
                .defineInRange("bytesPerTeam", CONFIG.longDefault("caps.bytesPerTeam"), 0L, Long.MAX_VALUE);
        builder.pop();
        builder.push("tools");
        builder.push("probe");
        PROBE_WINDOW_SECONDS = builder.comment("Seconds of history the signal probe's graph shows.")
                .defineInRange("windowSeconds", CONFIG.intDefault("tools.probe.windowSeconds"), 10, 600);
        PROBE_SAMPLE_TICKS = builder.comment("Ticks between two samples while a probe's screen is open.")
                .defineInRange("sampleTicks", CONFIG.intDefault("tools.probe.sampleTicks"), 5, 200);
        builder.pop();
        builder.push("cartridge");
        CARTRIDGE_CREDIT_PERCENT = builder
                .comment("Percent of one skip token's research points a solved puzzle cartridge gives its team.")
                .defineInRange("creditPercent", CONFIG.intDefault("tools.cartridge.creditPercent"), 0, 1000);
        CARTRIDGE_DIFFICULTY = builder.comment("Difficulty of the puzzle a new cartridge carries: 0 easy to 2 hard.")
                .defineInRange("difficulty", CONFIG.intDefault("tools.cartridge.difficulty"), 0, 2);
        builder.pop();
        builder.pop();
    }

    /** Most devices one sneak-use of the blueprint card pastes onto along a row ({@code tools.blueprint.rowLength}). */
    public static final IntSupplier BLUEPRINT_ROW_LENGTH;
    /** FE the network goggles hold ({@code tools.goggles.energyCapacity}). */
    public static final IntSupplier GOGGLES_ENERGY_CAPACITY;
    /** FE per transfer a charger puts into the goggles ({@code tools.goggles.maxInput}). */
    public static final IntSupplier GOGGLES_MAX_INPUT;
    /** FE the goggles spend each second they show a network ({@code tools.goggles.energyPerSecond}). */
    public static final IntSupplier GOGGLES_ENERGY_PER_SECOND;
    /** Most items on a pocket uplink's filter ({@code tools.uplink.filterSize}). */
    public static final IntSupplier UPLINK_FILTER_SIZE;
    /** Ticks between two restock belt passes ({@code tools.belt.intervalTicks}). */
    public static final IntSupplier BELT_INTERVAL_TICKS;
    /** Most slots one restock belt marks ({@code tools.belt.maxMarks}). */
    public static final IntSupplier BELT_MAX_MARKS;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("tools");
        builder.push("blueprint");
        BLUEPRINT_ROW_LENGTH = builder
                .comment("Most devices one sneak-use of the blueprint card pastes onto along a row, the clicked one",
                        "included.")
                .defineInRange("rowLength", CONFIG.intDefault("tools.blueprint.rowLength"), 1, 256);
        builder.pop();
        builder.push("goggles");
        GOGGLES_ENERGY_CAPACITY = builder.comment("MU the network goggles hold. They charge in any FE charger.")
                .defineInRange("energyCapacity", CONFIG.intDefault("tools.goggles.energyCapacity"), 1,
                        Integer.MAX_VALUE);
        GOGGLES_MAX_INPUT = builder.comment("MU per transfer a charger may put into the network goggles.")
                .defineInRange("maxInput", CONFIG.intDefault("tools.goggles.maxInput"), 1, Integer.MAX_VALUE);
        GOGGLES_ENERGY_PER_SECOND = builder
                .comment("MU the network goggles spend each second they show the network the wearer looks at.",
                        "0 makes them free.")
                .defineInRange("energyPerSecond", CONFIG.intDefault("tools.goggles.energyPerSecond"), 0,
                        Integer.MAX_VALUE);
        builder.pop();
        builder.push("uplink");
        UPLINK_FILTER_SIZE = builder.comment("Most items a pocket uplink's filter holds.").defineInRange("filterSize",
                CONFIG.intDefault("tools.uplink.filterSize"), 1, 64);
        builder.pop();
        builder.push("belt");
        BELT_INTERVAL_TICKS = builder
                .comment("Ticks between two refills of the slots a restock belt marks; every belt refills in the",
                        "same pass.")
                .defineInRange("intervalTicks", CONFIG.intDefault("tools.belt.intervalTicks"), 1, 1200);
        BELT_MAX_MARKS = builder.comment("Most inventory slots one restock belt marks.").defineInRange("maxMarks",
                CONFIG.intDefault("tools.belt.maxMarks"), 1, 36);
        builder.pop();
        builder.pop();
    }

    /**
     * MU a mail port takes from its network per stack sent ({@code services.mail.muPerStack}); see {@link MovedKeys}.
     */
    public static final LongSupplier MAIL_MU_PER_STACK;
    public static final LongSupplier MAIL_MU_PER_STACK_KEY;
    /** Stacks one player's inbox holds at most. */
    public static final IntSupplier MAIL_INBOX_STACKS;
    /** Open requests one team's request board holds at most. */
    public static final IntSupplier BOARD_MAX_REQUESTS;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("services");
        builder.push("mail");
        MAIL_MU_PER_STACK_KEY = builder.comment("MU a mail port takes from its network for each stack it sends.")
                .defineInRange("muPerStack", CONFIG.longDefault("services.mail.muPerStack"), 0L, Long.MAX_VALUE);
        MAIL_MU_PER_STACK = moved(builder, "services.mail.fePerStack", "fePerStack", "services.mail.muPerStack",
                MAIL_MU_PER_STACK_KEY);
        MAIL_INBOX_STACKS = builder.comment("Stacks a player's inbox holds; mail to a full inbox is refused.")
                .defineInRange("inboxStacks", CONFIG.intDefault("services.mail.inboxStacks"), 1, 1024);
        builder.pop();
        builder.push("board");
        BOARD_MAX_REQUESTS = builder.comment("Open requests a team's request board holds.").defineInRange("maxRequests",
                CONFIG.intDefault("services.board.maxRequests"), 1, 256);
        builder.pop();
        builder.pop();
    }

    /** FE the cable layer holds ({@code tools.layer.energyCapacity}). */
    public static final IntSupplier LAYER_ENERGY_CAPACITY;
    /** FE per transfer a charger puts into the cable layer ({@code tools.layer.maxInput}). */
    public static final IntSupplier LAYER_MAX_INPUT;
    /** FE the cable layer spends per cable laid ({@code tools.layer.energyPerCable}). */
    public static final IntSupplier LAYER_ENERGY_PER_CABLE;
    /** Most positions in one cable layer run ({@code tools.layer.maxLength}). */
    public static final IntSupplier LAYER_MAX_LENGTH;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("tools");
        builder.push("layer");
        LAYER_ENERGY_CAPACITY = builder.comment("MU the cable layer holds. It charges in any FE charger.")
                .defineInRange("energyCapacity", CONFIG.intDefault("tools.layer.energyCapacity"), 1, Integer.MAX_VALUE);
        LAYER_MAX_INPUT = builder.comment("MU per transfer a charger may put into the cable layer.")
                .defineInRange("maxInput", CONFIG.intDefault("tools.layer.maxInput"), 1, Integer.MAX_VALUE);
        LAYER_ENERGY_PER_CABLE = builder.comment("MU the cable layer spends for each cable it lays. 0 makes it free.")
                .defineInRange("energyPerCable", CONFIG.intDefault("tools.layer.energyPerCable"), 0, Integer.MAX_VALUE);
        LAYER_MAX_LENGTH = builder.comment("Most blocks one cable layer run covers, both ends included.")
                .defineInRange("maxLength", CONFIG.intDefault("tools.layer.maxLength"), 1, 256);
        builder.pop();
        builder.pop();
    }

    /** Chunks one team's network anchors keep loaded ({@code devices.networkAnchor.chunks}). */
    public static final IntSupplier ANCHOR_CHUNKS;
    public static final IntSupplier ANCHOR_CHUNKS_KEY;
    /** FE the network pays per tick for each active dimension bridge ({@code devices.dimensionBridge.drainPerTick}). */
    public static final LongSupplier BRIDGE_DRAIN_PER_TICK;
    public static final LongSupplier BRIDGE_DRAIN_PER_TICK_KEY;
    /** FE a dimension crossing costs on top ({@code devices.dimensionBridge.surchargePerOperation}). */
    public static final LongSupplier BRIDGE_SURCHARGE;
    public static final LongSupplier BRIDGE_SURCHARGE_KEY;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("devices");
        builder.push("networkAnchor");
        ANCHOR_CHUNKS_KEY = builder
                .comment("Chunks the network anchors of one team keep loaded together: their networks' controllers,",
                        "drives, energy cells and anchors and the cables between them. 0 turns anchors off.")
                .defineInRange("chunks", CONFIG.intDefault("devices.networkAnchor.chunks"), 0, 4096);
        builder.pop();
        builder.push("dimensionBridge");
        BRIDGE_DRAIN_PER_TICK_KEY = builder
                .comment("MU each dimension bridge takes from its network every tick while it is active.")
                .defineInRange("drainPerTick", CONFIG.longDefault("devices.dimensionBridge.drainPerTick"), 0L,
                        Long.MAX_VALUE);
        BRIDGE_SURCHARGE_KEY = builder
                .comment("MU every operation that crosses a dimension through a dimension bridge costs the network",
                        "on top of its usual cost: each wireless insert or extract, uplink store, belt refill, mail",
                        "stack and request board delivery.")
                .defineInRange("surchargePerOperation",
                        CONFIG.longDefault("devices.dimensionBridge.surchargePerOperation"), 0L, Long.MAX_VALUE);
        builder.pop();
        builder.pop();

        // The keys these values had before M7R item 8, read for one version while set (MovedKeys).
        builder.push("controller");
        builder.comment("Deprecated: these keys moved to controller.cards, controller.recorders and devices.",
                "A value other than -1 here still applies, over the new key, and logs a warning; the keys go in",
                "the next version.");
        builder.push("modules");
        builder.push("batchAmplifier");
        AMPLIFIER_BATCH_PERCENT = moved(builder, "controller.modules.batchAmplifier.batchPercent", "batchPercent",
                1_000, "controller.cards.batchAmplifier.batchPercent", AMPLIFIER_BATCH_PERCENT_KEY);
        AMPLIFIER_MAX_BATCH_PERCENT = moved(builder, "controller.modules.batchAmplifier.maxBatchPercent",
                "maxBatchPercent", 10_000, "controller.cards.batchAmplifier.maxBatchPercent",
                AMPLIFIER_MAX_BATCH_PERCENT_KEY);
        builder.pop();
        builder.push("efficiencyCore");
        EFFICIENCY_PERCENT = moved(builder, "controller.modules.efficiencyCore.percent", "percent", 99,
                "controller.cards.efficiencyCore.percent", EFFICIENCY_PERCENT_KEY);
        EFFICIENCY_MAX_PERCENT = moved(builder, "controller.modules.efficiencyCore.maxPercent", "maxPercent", 99,
                "controller.cards.efficiencyCore.maxPercent", EFFICIENCY_MAX_PERCENT_KEY);
        builder.pop();
        builder.push("surgeRegulator");
        SURGE_RESERVE_PERCENT = moved(builder, "controller.modules.surgeRegulator.reservePercent", "reservePercent", 50,
                "controller.cards.surgeRegulator.reservePercent", SURGE_RESERVE_PERCENT_KEY);
        builder.pop();
        builder.push("networkAnchor");
        ANCHOR_CHUNKS = moved(builder, "controller.modules.networkAnchor.chunks", "chunks", 4096,
                "devices.networkAnchor.chunks", ANCHOR_CHUNKS_KEY);
        builder.pop();
        builder.push("telemetryRecorder");
        TELEMETRY_SAMPLE_TICKS = moved(builder, "controller.modules.telemetryRecorder.sampleTicks", "sampleTicks",
                72_000, "controller.recorders.telemetrySampleTicks", TELEMETRY_SAMPLE_TICKS_KEY);
        TELEMETRY_SAMPLES = moved(builder, "controller.modules.telemetryRecorder.samples", "samples", 8640,
                "controller.recorders.telemetrySamples", TELEMETRY_SAMPLES_KEY);
        TELEMETRY_MAX_KEYS = moved(builder, "controller.modules.telemetryRecorder.maxKeys", "maxKeys", 256,
                "controller.recorders.telemetryMaxKeys", TELEMETRY_MAX_KEYS_KEY);
        builder.pop();
        builder.push("dimensionBridge");
        BRIDGE_DRAIN_PER_TICK = moved(builder, "controller.modules.dimensionBridge.drainPerTick", "drainPerTick",
                "devices.dimensionBridge.drainPerTick", BRIDGE_DRAIN_PER_TICK_KEY);
        BRIDGE_SURCHARGE = moved(builder, "controller.modules.dimensionBridge.surchargePerOperation",
                "surchargePerOperation", "devices.dimensionBridge.surchargePerOperation", BRIDGE_SURCHARGE_KEY);
        builder.pop();
        builder.pop();
        builder.pop();
        builder.push("services");
        builder.comment("Deprecated: moved to controller.recorders.ledgerDays and ledgerMaxRows; a value other",
                "than -1 here still applies and logs a warning.");
        builder.push("ledger");
        LEDGER_DAYS = moved(builder, "services.ledger.days", "days", 365, "controller.recorders.ledgerDays",
                LEDGER_DAYS_KEY);
        LEDGER_MAX_ROWS = moved(builder, "services.ledger.maxRows", "maxRows", 1_000_000,
                "controller.recorders.ledgerMaxRows", LEDGER_MAX_ROWS_KEY);
        builder.pop();
        builder.pop();
    }

    /**
     * Declares the old int key {@code name} (full path {@code oldKey}, unset by default, up to {@code max}) in the
     * builder's current section and returns the value in effect: the old key's while set, else {@code current}'s.
     */
    private static IntSupplier moved(ConfigBuilder builder, String oldKey, String name, int max, String newKey,
            IntSupplier current) {
        IntSupplier old = builder.comment("Deprecated: use " + newKey + ". -1 leaves it to the new key.")
                .defineInRange(name, CONFIG.intDefault(oldKey), MovedKeys.UNSET, max);
        return MovedKeys.moved(oldKey, old, newKey, current);
    }

    /** {@link #moved(ConfigBuilder, String, String, int, String, IntSupplier)} for a long key. */
    private static LongSupplier moved(ConfigBuilder builder, String oldKey, String name, String newKey,
            LongSupplier current) {
        LongSupplier old = builder.comment("Deprecated: use " + newKey + ". -1 leaves it to the new key.")
                .defineInRange(name, CONFIG.longDefault(oldKey), (long) MovedKeys.UNSET, Long.MAX_VALUE);
        return MovedKeys.moved(oldKey, old, newKey, current);
    }

    /** Items one bulk drive holds at most, of its one kind (M6f area B). */
    public static final LongSupplier BULK_DRIVE_MAX_COUNT;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("bulkDrive");
        BULK_DRIVE_MAX_COUNT = builder
                .comment("Items of its one kind a bulk drive holds at most. Bulk drives use no bytes; lowering this",
                        "never deletes items, it only stops inserts into drives above it.")
                .defineInRange("maxCount", CONFIG.longDefault("bulkDrive.maxCount"), 1L, Long.MAX_VALUE);
        builder.pop();
    }

    /**
     * Bytes a storage block holds, tiers I to III ({@code cells.storageBlockBytes.tier<N>}, B09 item 27); read when the
     * block's cell is mounted (placing, chunk load).
     */
    public static final LongSupplier STORAGE_BLOCK_1_BYTES;
    public static final LongSupplier STORAGE_BLOCK_2_BYTES;
    public static final LongSupplier STORAGE_BLOCK_3_BYTES;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("cells");
        builder.comment("Bytes a storage block holds, as a cell's memory does: items and fluids take bytes as in a",
                "cell. A lowered value never deletes contents; a block above it takes nothing more in. Applies when",
                "the block is next placed or loaded.").push("storageBlockBytes");
        STORAGE_BLOCK_1_BYTES = builder.comment("Bytes a storage block I holds (1 KB = 1,024 bytes).")
                .defineInRange("tier1", CONFIG.longDefault("cells.storageBlockBytes.tier1"), 1L, 1L << 40);
        STORAGE_BLOCK_2_BYTES = builder.comment("Bytes a storage block II holds.").defineInRange("tier2",
                CONFIG.longDefault("cells.storageBlockBytes.tier2"), 1L, 1L << 40);
        STORAGE_BLOCK_3_BYTES = builder.comment("Bytes a storage block III holds.").defineInRange("tier3",
                CONFIG.longDefault("cells.storageBlockBytes.tier3"), 1L, 1L << 40);
        builder.pop();
        builder.pop();
    }

    /** One void geode per this many chunks on average ({@code worldgen.voidGeode.rarity}); 0 turns them off. */
    public static final IntSupplier VOID_GEODE_RARITY;
    /** Least and most blocks a void geode's origin sits below the island's surface. */
    public static final IntSupplier VOID_GEODE_MIN_DEPTH;
    public static final IntSupplier VOID_GEODE_MAX_DEPTH;
    /** A budding void crystal grows a bud one random tick in this many ({@code materials.voidCrystal.growthChance}). */
    public static final IntSupplier VOID_CRYSTAL_GROWTH_CHANCE;
    /** Runs a machine with a void module does at once ({@code machines.modules.voidParallel}). */
    public static final IntSupplier VOID_MODULE_PARALLEL;
    /** Per cent more FE a run costs with a void module ({@code machines.modules.voidEnergyPercent}). */
    public static final IntSupplier VOID_MODULE_ENERGY_PERCENT;
    /** Bytes a memory module V adds to its cell ({@code cells.memoryBytes.tier5}). */
    public static final LongSupplier MEMORY_5_BYTES;
    /** Bytes a memory module VI adds to its cell ({@code cells.memoryBytes.tier6}). */
    public static final LongSupplier MEMORY_6_BYTES;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("worldgen");
        builder.push("voidGeode");
        VOID_GEODE_RARITY = builder
                .comment("One void geode per this many chunks on average, under the surface of the islands in the",
                        "biomes of the maxtech_storage:has_void_geode biome tag (the outer End islands) and the",
                        "dimensions of the dimension type tag of the same name; 0 turns them off. A chunk without an",
                        "island gets none. Applies to chunks generated after a change.")
                .defineInRange("rarity", CONFIG.intDefault("worldgen.voidGeode.rarity"), 0, 10_000);
        VOID_GEODE_MIN_DEPTH = builder.comment("Fewest blocks a geode's origin sits below the island's surface.")
                .defineInRange("minDepth", CONFIG.intDefault("worldgen.voidGeode.minDepth"), 0, 256);
        VOID_GEODE_MAX_DEPTH = builder
                .comment("Most blocks a geode's origin sits below the island's surface; below minDepth uses minDepth.")
                .defineInRange("maxDepth", CONFIG.intDefault("worldgen.voidGeode.maxDepth"), 0, 256);
        builder.pop();
        builder.pop();
        builder.push("materials");
        builder.push("voidCrystal");
        VOID_CRYSTAL_GROWTH_CHANCE = builder
                .comment("A budding void crystal grows or enlarges a bud on one random tick in this many",
                        "(budding amethyst: 5).")
                .defineInRange("growthChance", CONFIG.intDefault("materials.voidCrystal.growthChance"), 1, 1000);
        builder.pop();
        builder.pop();
        builder.push("machines");
        builder.push("modules");
        VOID_MODULE_PARALLEL = builder
                .comment("Runs a lithograph, fabricator or assembler with a void module does at once, as far as its",
                        "inputs and output room allow.")
                .defineInRange("voidParallel", CONFIG.intDefault("machines.modules.voidParallel"), 1, 64);
        VOID_MODULE_ENERGY_PERCENT = builder
                .comment("Per cent more MU a run costs in all with a void module, however many runs it does at once.")
                .defineInRange("voidEnergyPercent", CONFIG.intDefault("machines.modules.voidEnergyPercent"), 0, 10_000);
        builder.pop();
        builder.pop();
        builder.push("cells");
        builder.push("memoryBytes");
        MEMORY_5_BYTES = builder.comment("Bytes a memory module V adds to its cell.").defineInRange("tier5",
                CONFIG.longDefault("cells.memoryBytes.tier5"), 1L, 1L << 40);
        MEMORY_6_BYTES = builder.comment("Bytes a memory module VI adds to its cell.").defineInRange("tier6",
                CONFIG.longDefault("cells.memoryBytes.tier6"), 1L, 1L << 40);
        builder.pop();
        builder.pop();
    }

    /** Most tree nodes one crafting plan may visit before it fails as too large ({@code crafting.plannerNodeCap}). */
    public static final IntSupplier CRAFTING_PLANNER_NODE_CAP;
    /** Most milliseconds one crafting plan may take on its worker thread ({@code crafting.plannerTimeLimitMs}). */
    public static final IntSupplier CRAFTING_PLANNER_TIME_LIMIT_MS;
    /**
     * How many times its guaranteed count a step with a chance main output may repeat at most
     * ({@code crafting.chanceRepeatCap}).
     */
    public static final IntSupplier CRAFTING_CHANCE_REPEAT_CAP;
    /**
     * Blocks from a job's network node within which a finished craft can go into its requester's inventory
     * ({@code crafting.deliveryRange}).
     */
    public static final IntSupplier CRAFTING_DELIVERY_RANGE;
    /**
     * Whether the online team members of a job's requester get its finished and stopped toasts too
     * ({@code crafting.teamToasts}, B09 item 21d).
     */
    public static final BooleanSupplier CRAFTING_TEAM_TOASTS;
    /** FE a crafting tablet holds ({@code crafting.tablet.energyCapacity}). */
    public static final IntSupplier CRAFTING_TABLET_ENERGY_CAPACITY;
    /** FE per transfer a charger puts into a crafting tablet ({@code crafting.tablet.maxInput}). */
    public static final IntSupplier CRAFTING_TABLET_MAX_INPUT;
    /**
     * FE a crafting tablet spends per network pull, return or job request ({@code crafting.tablet.energyPerOperation}).
     */
    public static final IntSupplier CRAFTING_TABLET_ENERGY_PER_OPERATION;
    /** Planned jobs one player's tablet requests may have waiting ({@code crafting.tablet.maxPlannedJobs}). */
    public static final IntSupplier CRAFTING_TABLET_MAX_PLANNED_JOBS;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("crafting");
        CRAFTING_PLANNER_NODE_CAP = builder
                .comment("Tree nodes one crafting plan may visit at most; a larger request fails with a clear error.")
                .defineInRange("plannerNodeCap", CONFIG.intDefault("crafting.plannerNodeCap"), 16, 10_000_000);
        CRAFTING_PLANNER_TIME_LIMIT_MS = builder
                .comment("Milliseconds one crafting plan may take on its worker thread before it fails.")
                .defineInRange("plannerTimeLimitMs", CONFIG.intDefault("crafting.plannerTimeLimitMs"), 10, 60_000);
        CRAFTING_CHANCE_REPEAT_CAP = builder
                .comment("A step whose main output has a chance repeats until enough came out, at most this many",
                        "times the crafts a guaranteed output would need.")
                .defineInRange("chanceRepeatCap", CONFIG.intDefault("crafting.chanceRepeatCap"), 1, 1000);
        CRAFTING_DELIVERY_RANGE = builder.comment(
                "Blocks from the network's controller (the node a crafting job is anchored at) within which its",
                "finished items can go into the requester's inventory; farther away they stay in storage.")
                .defineInRange("deliveryRange", CONFIG.intDefault("crafting.deliveryRange"), 1, 30_000_000);
        CRAFTING_TEAM_TOASTS = builder.comment(
                "Whether the online members of a requester's team also get the toast when a crafting job the",
                "requester started finishes or stops. The requester always gets it (unless they turned the toasts off).")
                .define("teamToasts", CONFIG.booleanDefault("crafting.teamToasts"));
        builder.push("tablet");
        CRAFTING_TABLET_ENERGY_CAPACITY = builder
                .comment("MU a crafting tablet holds. It charges in any FE charger; an empty tablet does not open.")
                .defineInRange("energyCapacity", CONFIG.intDefault("crafting.tablet.energyCapacity"), 1,
                        Integer.MAX_VALUE);
        CRAFTING_TABLET_MAX_INPUT = builder.comment("MU per transfer a charger may put into a crafting tablet.")
                .defineInRange("maxInput", CONFIG.intDefault("crafting.tablet.maxInput"), 1, Integer.MAX_VALUE);
        CRAFTING_TABLET_ENERGY_PER_OPERATION = builder
                .comment("MU a crafting tablet spends each time it takes items from the network, stores items in it",
                        "or requests a crafting job, on top of the network's own cost.")
                .defineInRange("energyPerOperation", CONFIG.intDefault("crafting.tablet.energyPerOperation"), 0,
                        Integer.MAX_VALUE);
        CRAFTING_TABLET_MAX_PLANNED_JOBS = builder
                .comment("Jobs one player's tablet requests may have planned and waiting for a CPU at once; asking",
                        "again for the same item replaces its job, and requests beyond this are refused.")
                .defineInRange("maxPlannedJobs", CONFIG.intDefault("crafting.tablet.maxPlannedJobs"), 1, 1000);
        builder.pop();
        builder.pop();
    }

    /** Rules one stock keeper offers ({@code crafting.stockKeeperRules}). */
    public static final IntSupplier CRAFTING_STOCK_KEEPER_RULES;
    /** Ticks a stock rule waits after a refused or failed craft ({@code crafting.stockRetryTicks}). */
    public static final IntSupplier CRAFTING_STOCK_RETRY_TICKS;
    /** Fewest ticks between two crafts one crafting card requests ({@code crafting.cardIntervalTicks}). */
    public static final IntSupplier CRAFTING_CARD_INTERVAL_TICKS;
    /** Most items (or mB) one crafting card request asks for ({@code crafting.cardMaxRequest}). */
    public static final IntSupplier CRAFTING_CARD_MAX_REQUEST;
    /**
     * The default most items (or mB) one automated crafting job asks for, 0 for no limit
     * ({@code crafting.automationMaxPerJob}); a network's controller can set its own.
     */
    public static final IntSupplier CRAFTING_AUTOMATION_MAX_PER_JOB;
    /**
     * The default number of free crafting CPUs automated jobs leave for players
     * ({@code crafting.automationCpusKeptFree}); a network's controller can set its own.
     */
    public static final IntSupplier CRAFTING_AUTOMATION_CPUS_KEPT_FREE;
    /** How a crafting step is shared among providers that have its pattern ({@code crafting.dispatchStrategy}). */
    public static final Supplier<DispatchStrategy> CRAFTING_DISPATCH_STRATEGY;
    /** Pattern slots a provider gains with its research ({@code crafting.providerExtraSlots}). */
    public static final IntSupplier CRAFTING_PROVIDER_EXTRA_SLOTS;
    /** Ticks between a provider's looks at its machine's output while it waits ({@code crafting.providerPullTicks}). */
    public static final IntSupplier CRAFTING_PROVIDER_PULL_TICKS;
    /** FE the network pays for each craft of a crafting assembler ({@code crafting.assemblerEnergyPerCraft}). */
    public static final LongSupplier CRAFTING_ASSEMBLER_ENERGY_PER_CRAFT;
    /** MU the network pays for each craft of a crafter ({@code crafting.crafterEnergyPerCraft}; B09 item 29). */
    public static final LongSupplier CRAFTING_CRAFTER_ENERGY_PER_CRAFT;
    /**
     * Ticks a generator provider keeps its machine on with nothing arriving before it names the step stuck and gives
     * the order up ({@code crafting.generatorTimeoutTicks}).
     */
    public static final IntSupplier CRAFTING_GENERATOR_TIMEOUT_TICKS;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("crafting");
        CRAFTING_STOCK_KEEPER_RULES = builder.comment("Rules one stock keeper offers.")
                .defineInRange("stockKeeperRules", CONFIG.intDefault("crafting.stockKeeperRules"), 1, 16);
        CRAFTING_STOCK_RETRY_TICKS = builder
                .comment("Ticks a stock keeping rule waits before asking again after its craft was refused or failed.")
                .defineInRange("stockRetryTicks", CONFIG.intDefault("crafting.stockRetryTicks"), 20, 72_000);
        CRAFTING_CARD_INTERVAL_TICKS = builder
                .comment("Fewest ticks between two crafts one export port's crafting card requests.")
                .defineInRange("cardIntervalTicks", CONFIG.intDefault("crafting.cardIntervalTicks"), 20, 72_000);
        CRAFTING_CARD_MAX_REQUEST = builder.comment("Most items, or mB of fluid, one crafting card request asks for.")
                .defineInRange("cardMaxRequest", CONFIG.intDefault("crafting.cardMaxRequest"), 1, 1_000_000);
        CRAFTING_AUTOMATION_MAX_PER_JOB = builder.comment(
                "Most items, or mB of fluid, one automated crafting job (stock keeper, logic rule, crafting card) asks",
                "for; a larger request is capped and the requester asks again for the rest. 0 for no limit. The",
                "default for networks whose controller has no setting of its own.").defineInRange("automationMaxPerJob",
                        CONFIG.intDefault("crafting.automationMaxPerJob"), 0, CraftLimits.MAX_PER_JOB);
        CRAFTING_AUTOMATION_CPUS_KEPT_FREE = builder.comment(
                "Free crafting CPUs automated jobs never take, so a player's request finds one. The default for",
                "networks whose controller has no setting of its own.").defineInRange("automationCpusKeptFree",
                        CONFIG.intDefault("crafting.automationCpusKeptFree"), 0, CraftLimits.MAX_KEPT_FREE);
        CRAFTING_DISPATCH_STRATEGY = builder.comment(
                "How a crafting step is shared among pattern providers that have its pattern: fastest (earliest",
                "estimated finish), round_robin (even shares in turn) or lowest_load (fewest crafts in flight).")
                .defineEnum("dispatchStrategy",
                        CONFIG.enumDefault("crafting.dispatchStrategy", DispatchStrategy.class));
        CRAFTING_PROVIDER_EXTRA_SLOTS = builder
                .comment("Pattern slots a pattern provider gains on top of its nine once its owner has the research.")
                .defineInRange("providerExtraSlots", CONFIG.intDefault("crafting.providerExtraSlots"), 0, 27);
        CRAFTING_PROVIDER_PULL_TICKS = builder
                .comment("Ticks between a pattern provider's looks at its machine's output while it waits for results;",
                        "the machine's own change events wake it sooner.")
                .defineInRange("providerPullTicks", CONFIG.intDefault("crafting.providerPullTicks"), 1, 1200);
        CRAFTING_ASSEMBLER_ENERGY_PER_CRAFT = builder
                .comment("MU the network pays for every craft a crafting assembler makes, in the batch's transaction.")
                .defineInRange("assemblerEnergyPerCraft", CONFIG.longDefault("crafting.assemblerEnergyPerCraft"), 0L,
                        1_000_000L);
        CRAFTING_CRAFTER_ENERGY_PER_CRAFT = builder
                .comment("MU the network pays for every craft a crafter makes with its own patterns, in the batch's",
                        "transaction.")
                .defineInRange("crafterEnergyPerCraft", CONFIG.longDefault("crafting.crafterEnergyPerCraft"), 0L,
                        1_000_000L);
        CRAFTING_GENERATOR_TIMEOUT_TICKS = builder
                .comment("Ticks a generator provider keeps its machine switched on while nothing arrives before it",
                        "reports the craft as stuck and gives the order up; the running job orders again.")
                .defineInRange("generatorTimeoutTicks", CONFIG.intDefault("crafting.generatorTimeoutTicks"), 20,
                        72_000);
        builder.pop();
    }

    /** Extended or classic autocrafting ({@code crafting.autocraftingMode}, M7 C6, C29). */
    public static final Supplier<CraftingMode> CRAFTING_MODE;
    /** Steps a crafting CPU core holds before any storage block ({@code crafting.cpu.coreSteps}). */
    public static final IntSupplier CPU_CORE_STEPS;
    /** Steps each CPU storage block adds ({@code crafting.cpu.stepsPerStorage}). */
    public static final IntSupplier CPU_STEPS_PER_STORAGE;
    /** Crafts per dispatch each CPU cache block adds ({@code crafting.cpu.batchPerCache}). */
    public static final IntSupplier CPU_BATCH_PER_CACHE;
    /** Most part blocks one CPU cluster counts ({@code crafting.cpu.maxParts}). */
    public static final IntSupplier CPU_MAX_PARTS;
    /** Per CPU tier (index 0 to 2): most steps of one job ({@code crafting.cpu.tierN.maxSteps}). */
    public static final List<IntSupplier> CPU_MAX_STEPS;
    /** Per CPU tier: most lanes ({@code crafting.cpu.tierN.maxLanes}). */
    public static final List<IntSupplier> CPU_MAX_LANES;
    /** Per CPU tier: most crafts per dispatch ({@code crafting.cpu.tierN.maxBatch}). */
    public static final List<IntSupplier> CPU_MAX_BATCH;
    /** Per CPU tier: MU per active lane per tick ({@code crafting.cpu.tierN.muPerLane}); see {@link MovedKeys}. */
    public static final List<LongSupplier> CPU_MU_PER_LANE;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("crafting");
        CRAFTING_MODE = builder
                .comment("extended: tiered CPUs with partial results, resume and full job reports.",
                        "classic: a job starts only when everything can be made and shows only its status.")
                .defineEnum("autocraftingMode", CONFIG.enumDefault("crafting.autocraftingMode", CraftingMode.class));
        builder.push("cpu");
        CPU_CORE_STEPS = builder.comment("Job steps a CPU core holds on its own.").defineInRange("coreSteps",
                CONFIG.intDefault("crafting.cpu.coreSteps"), 1, 1_000_000);
        CPU_STEPS_PER_STORAGE = builder.comment("Job steps each CPU storage block adds, up to the tier's maxSteps.")
                .defineInRange("stepsPerStorage", CONFIG.intDefault("crafting.cpu.stepsPerStorage"), 0, 1_000_000);
        CPU_BATCH_PER_CACHE = builder
                .comment("Crafts each CPU cache block adds to one dispatch (a core alone sends one craft at a",
                        "time), up to the tier's maxBatch.")
                .defineInRange("batchPerCache", CONFIG.intDefault("crafting.cpu.batchPerCache"), 0, 1_000_000);
        CPU_MAX_PARTS = builder.comment("Part blocks one CPU cluster counts at most; more are ignored.")
                .defineInRange("maxParts", CONFIG.intDefault("crafting.cpu.maxParts"), 1, 4096);
        List<IntSupplier> steps = new ArrayList<>(3);
        List<IntSupplier> lanes = new ArrayList<>(3);
        List<IntSupplier> batch = new ArrayList<>(3);
        List<LongSupplier> fe = new ArrayList<>(3);
        for (int tier = 1; tier <= 3; tier++) {
            String prefix = "crafting.cpu.tier" + tier + ".";
            builder.push("tier" + tier);
            steps.add(builder.comment("Most steps of one job a tier-" + tier + " CPU runs.").defineInRange("maxSteps",
                    CONFIG.intDefault(prefix + "maxSteps"), 1, 1_000_000));
            lanes.add(builder.comment("Most lanes (steps waiting on a machine at once); each lane block adds one.")
                    .defineInRange("maxLanes", CONFIG.intDefault(prefix + "maxLanes"), 1, 4096));
            batch.add(builder.comment("Most crafts the CPU sends in one dispatch.").defineInRange("maxBatch",
                    CONFIG.intDefault(prefix + "maxBatch"), 1, 1_000_000));
            LongSupplier muPerLane = builder
                    .comment("MU per active lane per tick; an idle CPU costs nothing, an unpowered one stalls.")
                    .defineInRange("muPerLane", CONFIG.longDefault(prefix + "muPerLane"), 0L, 1_000_000L);
            fe.add(moved(builder, prefix + "fePerLane", "fePerLane", prefix + "muPerLane", muPerLane));
            builder.pop();
        }
        CPU_MAX_STEPS = List.copyOf(steps);
        CPU_MAX_LANES = List.copyOf(lanes);
        CPU_MAX_BATCH = List.copyOf(batch);
        CPU_MU_PER_LANE = List.copyOf(fe);
        builder.pop();
        builder.pop();
    }

    /**
     * Operations a drive's cell transfer (M7C item 12) makes per tick at most, over all its slots
     * ({@code cells.transferOperations}); each moves one batch of one kind.
     */
    public static final IntSupplier CELL_TRANSFER_OPERATIONS;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("cells");
        CELL_TRANSFER_OPERATIONS = builder
                .comment("Operations a drive makes per tick at most while it empties a cell into its network or fills",
                        "one from it; each moves one batch (network.batch) of one kind.")
                .defineInRange("transferOperations", CONFIG.intDefault("cells.transferOperations"), 1, 1024);
        builder.pop();
    }

    /**
     * Shortest gap in ticks between two reads of a bridged foreign network's contents after it reported a change
     * ({@code bridges.refreshTicks}, M7b).
     */
    public static final IntSupplier BRIDGE_REFRESH_TICKS;
    /**
     * Ticks between two reads of a bridged foreign network that cannot report its changes
     * ({@code bridges.pollIntervalTicks}, M7b).
     */
    public static final IntSupplier BRIDGE_POLL_TICKS;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("bridges");
        BRIDGE_REFRESH_TICKS = builder
                .comment("Shortest gap in ticks between two reads of a bridged AE2 or Refined Storage network's",
                        "contents after it reported a change. Higher is cheaper on busy networks; terminals lag more.")
                .defineInRange("refreshTicks", CONFIG.intDefault("bridges.refreshTicks"), 1, 1200);
        BRIDGE_POLL_TICKS = builder
                .comment("Ticks between two reads of a bridged network that cannot report its changes.")
                .defineInRange("pollIntervalTicks", CONFIG.intDefault("bridges.pollIntervalTicks"), 20, 12000);
        builder.pop();
    }

    /**
     * Operations an IO port makes per tick while it works, before speed cards ({@code ioPort.operationsPerTick}, B09
     * item 11); each moves one batch (network.batch) of one kind.
     */
    public static final IntSupplier IO_PORT_OPERATIONS;
    /**
     * What each speed card in an IO port multiplies its operations per tick by ({@code ioPort.speedCardMultiplier}).
     */
    public static final DoubleSupplier IO_PORT_SPEED_CARD_MULTIPLIER;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("ioPort");
        IO_PORT_OPERATIONS = builder
                .comment("Operations an IO port makes per tick while it unloads or loads a cell, before speed cards;",
                        "each moves one batch (network.batch) of one kind.")
                .defineInRange("operationsPerTick", CONFIG.intDefault("ioPort.operationsPerTick"), 1, 1024);
        IO_PORT_SPEED_CARD_MULTIPLIER = builder
                .comment("What each speed card in an IO port multiplies its operations per tick by.")
                .defineInRange("speedCardMultiplier", CONFIG.doubleDefault("ioPort.speedCardMultiplier"), 1.0, 16.0);
        builder.pop();
    }

    /**
     * The fewest ticks between two switches of one toggle bus ({@code network.toggleBus.minIntervalTicks}, B09 item 19,
     * at least 5); each switch is a topology change, so a redstone clock may not rebuild a network every tick.
     */
    public static final IntSupplier TOGGLE_BUS_MIN_INTERVAL;

    static {
        ConfigBuilder builder = BUILDER;
        builder.push("network");
        builder.push("toggleBus");
        TOGGLE_BUS_MIN_INTERVAL = builder
                .comment("The fewest ticks between two switches of one toggle bus. Each switch links or cuts a",
                        "network and rebuilds it, which costs more the larger the network is, so this keeps a redstone",
                        "clock from rebuilding networks every tick. A signal that changes sooner is followed once this",
                        "has passed. At least 5.")
                .defineInRange("minIntervalTicks", CONFIG.intDefault("network.toggleBus.minIntervalTicks"), 5, 1200);
        builder.pop();
        builder.pop();
    }
}
