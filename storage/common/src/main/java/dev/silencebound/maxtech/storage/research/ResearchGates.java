package dev.silencebound.maxtech.storage.research;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import net.minecraft.resources.Identifier;

/**
 * The unlock ids Storage's own content checks with {@link ResearchUnlocks}. A research node lists the ids it unlocks in
 * its {@code unlocks}, so a data pack can move an unlock to another node or drop it; an id no loaded node lists is
 * unlocked for everyone.
 */
public final class ResearchGates {
    /** The lithograph's memory mask (and printing with it). */
    public static final Identifier MEMORY_MASK = MaxtechStorage.id("memory_mask");
    /** The lithograph's signal mask. */
    public static final Identifier SIGNAL_MASK = MaxtechStorage.id("signal_mask");
    /** The lithograph's I/O mask. */
    public static final Identifier IO_MASK = MaxtechStorage.id("io_mask");
    /** Advanced processors (advanced doping, tantalum capacitors). */
    public static final Identifier ADVANCED_PROCESSORS = MaxtechStorage.id("advanced_processors");
    /** The lithograph's precision module. */
    public static final Identifier PRECISION_MODULE = MaxtechStorage.id("precision_module");
    /** Memory module II in the cell workbench. */
    public static final Identifier MEMORY_MODULE_2 = MaxtechStorage.id("memory_module_2");
    /** Memory module III in the cell workbench. */
    public static final Identifier MEMORY_MODULE_3 = MaxtechStorage.id("memory_module_3");
    /** The medium cell casing. */
    public static final Identifier MEDIUM_CASING = MaxtechStorage.id("medium_casing");
    /** The large cell casing. */
    public static final Identifier LARGE_CASING = MaxtechStorage.id("large_casing");
    /** The bulk cell casing. */
    public static final Identifier BULK_CASING = MaxtechStorage.id("bulk_casing");
    /** The portable cell casing. */
    public static final Identifier PORTABLE_CASING = MaxtechStorage.id("portable_casing");
    /** The partition add-on, fuzzy mode included. */
    public static final Identifier PARTITION_ADDON = MaxtechStorage.id("partition_addon");
    /** The void overflow add-on. */
    public static final Identifier VOID_ADDON = MaxtechStorage.id("void_addon");
    /** The read-only lock add-on. */
    public static final Identifier LOCK_ADDON = MaxtechStorage.id("lock_addon");
    /** Tier-2 ports (M6c). */
    public static final Identifier PORT_TIER_2 = MaxtechStorage.id("port_tier_2");
    /** Tier-3 ports (M6c). */
    public static final Identifier PORT_TIER_3 = MaxtechStorage.id("port_tier_3");
    /** The subnet controller (M6d). */
    public static final Identifier SUBNET_CONTROLLER = MaxtechStorage.id("subnet_controller");
    /** Antennas and the antenna mount, placing and pairing them (M6d). */
    public static final Identifier ANTENNA = MaxtechStorage.id("antenna");
    /** The wireless terminal and the wireless access mount (M6d). */
    public static final Identifier WIRELESS_TERMINAL = MaxtechStorage.id("wireless_terminal");
    /** The interface, block and part (M6c): without it an interface neither stocks nor answers its neighbours. */
    public static final Identifier INTERFACE = MaxtechStorage.id("interface");
    /** P2P links of every kind, block and part (M6c): without it a link carries nothing. */
    public static final Identifier P2P_LINKS = MaxtechStorage.id("p2p_links");
    /** The network chest (M6c). */
    public static final Identifier NETWORK_CHEST = MaxtechStorage.id("network_chest");
    /** The collector (M6c). */
    public static final Identifier COLLECTOR = MaxtechStorage.id("collector");
    /** The placer port (M6c). */
    public static final Identifier PLACER = MaxtechStorage.id("placer_port");
    /** The breaker port (M6c). */
    public static final Identifier BREAKER = MaxtechStorage.id("breaker_port");

    /** Condition switches give a redstone output (M6c). */
    public static final Identifier CONDITION_SWITCH = MaxtechStorage.id("condition_switch");
    /** Tier-2 energy cells (M6c). */
    public static final Identifier ENERGY_CELL_TIER_2 = MaxtechStorage.id("energy_cell_tier_2");
    /** Tier-3 energy cells (M6c). */
    public static final Identifier ENERGY_CELL_TIER_3 = MaxtechStorage.id("energy_cell_tier_3");
    /** The multiblock controller's first size step ({@code controller.size.step1}, M7R). */
    public static final Identifier CONTROLLER_SIZE_1 = MaxtechStorage.id("controller_size_1");
    /** The multiblock controller's second size step ({@code controller.size.step2}). */
    public static final Identifier CONTROLLER_SIZE_2 = MaxtechStorage.id("controller_size_2");
    /** The multiblock controller's third size step ({@code controller.size.step3}). */
    public static final Identifier CONTROLLER_SIZE_3 = MaxtechStorage.id("controller_size_3");
    /** The controller's upgrade card slots (M7R item 2): without it a controller has none. */
    public static final Identifier CONTROLLER_CARDS = MaxtechStorage.id("controller_cards");

    /** The efficiency core upgrade card (M6c2 area G; a controller expansion before M7R). */
    public static final Identifier EFFICIENCY_CORE = MaxtechStorage.id("efficiency_core");
    /** The surge regulator upgrade card (M6c2 area G; a controller expansion before M7R). */
    public static final Identifier SURGE_REGULATOR = MaxtechStorage.id("surge_regulator");
    /** The network anchor device (M6c2 area G; a controller expansion before M7R). */
    public static final Identifier NETWORK_ANCHOR = MaxtechStorage.id("network_anchor");
    /** The security module device (M6c2 area G; a controller expansion before M7R). */
    public static final Identifier SECURITY_MODULE = MaxtechStorage.id("security_module");
    /**
     * The controller's telemetry graphs (M7R item 3; the telemetry recorder expansion before it): its History tab's
     * graphs and the terminal's. The controller records without it; the owner's team needs it to see them.
     */
    public static final Identifier TELEMETRY_RECORDER = MaxtechStorage.id("telemetry_recorder");
    /**
     * The controller's redstone alarm rules (M7R item 3; the alarm module expansion before it): seeing and setting them
     * up needs it for the owner's team.
     */
    public static final Identifier ALARM_MODULE = MaxtechStorage.id("alarm_module");
    /** The controller's ledger view (M7R item 3): who put in and took out what; the owner's team needs it to see it. */
    public static final Identifier LEDGER = MaxtechStorage.id("ledger");

    /** The speed module (M6d): fitting it into a machine's module slot. */
    public static final Identifier SPEED_MODULE = MaxtechStorage.id("speed_module");
    /** The efficiency module (M6d): fitting it into a machine's module slot. */
    public static final Identifier EFFICIENCY_MODULE = MaxtechStorage.id("efficiency_module");
    /** Elite processors (M6d): ember doping, printing elite dies and assembling elite processors. */
    public static final Identifier ELITE_PROCESSORS = MaxtechStorage.id("elite_processors");
    /** Memory module IV in the cell workbench (M6d). */
    public static final Identifier MEMORY_MODULE_4 = MaxtechStorage.id("memory_module_4");

    /** The void lattice (M6f): doping a data crystal wafer with void crystal in the lithograph. */
    public static final Identifier VOID_LATTICE = MaxtechStorage.id("void_lattice");
    /** Memory module V in the cell workbench (M6f). */
    public static final Identifier MEMORY_MODULE_5 = MaxtechStorage.id("memory_module_5");
    /** Memory module VI in the cell workbench (M6f). */
    public static final Identifier MEMORY_MODULE_6 = MaxtechStorage.id("memory_module_6");
    /** The void module (M6f): fitting it into a machine's module slot. */
    public static final Identifier VOID_MODULE = MaxtechStorage.id("void_module");
    /** Bulk drives (M6f area B); the Void branch grants it. */
    public static final Identifier BULK_DRIVE = MaxtechStorage.id("bulk_drive");
    /** The dimension bridge device (M6f area C); the Void branch grants it. */
    public static final Identifier DIMENSION_BRIDGE = MaxtechStorage.id("dimension_bridge");

    /** The first crafting CPU tier (M7 area A); the Crafting branch's root grants it from the start. */
    public static final Identifier CPU_TIER_1 = MaxtechStorage.id("cpu_tier_1");
    /** The second crafting CPU tier, deep data crystal (M7 area A). */
    public static final Identifier CPU_TIER_2 = MaxtechStorage.id("cpu_tier_2");
    /** The third crafting CPU tier, void crystal (M7 area A). */
    public static final Identifier CPU_TIER_3 = MaxtechStorage.id("cpu_tier_3");
    /** The pattern provider's slots past its first nine (M7 area B). */
    public static final Identifier PROVIDER_SLOTS = MaxtechStorage.id("provider_slots");
    /** The stock keeper: without it a stock keeper requests nothing (M7 area D). */
    public static final Identifier STOCK_KEEPER = MaxtechStorage.id("stock_keeper");
    /** The crafting card: without it a port's card requests nothing (M7 area D). */
    public static final Identifier CRAFTING_CARD = MaxtechStorage.id("crafting_card");

    private ResearchGates() {
    }
}
