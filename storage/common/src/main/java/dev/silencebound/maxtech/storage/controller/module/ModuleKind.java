package dev.silencebound.maxtech.storage.controller.module;

import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.storage.research.ResearchGates;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;
import org.jspecify.annotations.Nullable;

/**
 * The kinds of controller expansion (PRD 7.2, Controller). Each expansion block is one kind; what a kind adds to the
 * controller it is attached to is summed in {@link dev.silencebound.maxtech.storage.controller.ClusterStats}. Since M7R
 * item 1 the {@link #retired()} kinds no longer attach: the multiblock controller's own blocks replaced the channel
 * array, capacitor bank and device bus, and since item 2 the batch amplifier, efficiency core and surge regulator are
 * upgrade cards in the controller's slots ({@link #card()}), and since item 3 the telemetry recorder, ledger module and
 * alarm module are built into the controller ({@link #builtIn()}). Since item 4 the security module, network anchor,
 * antenna mount, wireless access mount and dimension bridge are standalone network devices ({@link #device()},
 * {@code ServiceDeviceBlock}) placed anywhere on the network; they keep their kind for their research gate and their
 * saved index. No kind attaches any more. An old save's module list names its kinds by {@link #index()}: a new kind
 * takes the next free index, and an index is never reused. Indices 4 and 5 belonged to the priority router and burst
 * buffer, deleted after M7N, and 12 and 13 to the status crown and light strip, removed on 2026-10-18; {@link #byIndex}
 * answers null for them, and a controller drops such a module when it loads.
 */
public enum ModuleKind implements StringRepresentable {
    /** Retired (M7R item 1): every controller block face is a root link now. Inert, kept so old saves load. */
    // The id keeps the bandwidth-era name, so saved clusters keep their arrays.
    CHANNEL_ARRAY("bandwidth_array", 0),
    /**
     * Retired (M7R items 1 and 5): each controller block adds FE buffer and input, and the bank folded into the energy
     * cells; its block id loads as a tier-1 energy cell. The index stays so old module lists load.
     */
    CAPACITOR_BANK("capacitor_bank", 1),
    /**
     * Retired (M7R item 2): the batch amplifier card. Its index counts the installed cards in a count array; a block of
     * this kind that a controller's fill meets turns into the card.
     */
    BATCH_AMPLIFIER("batch_amplifier", 2),
    /** Retired (M7R item 1): networks have no device limit, only channels. Inert, kept so old saves load. */
    DEVICE_BUS("device_bus", 3),
    /** Retired (M7R item 2): the efficiency core card, as {@link #BATCH_AMPLIFIER}. */
    EFFICIENCY_CORE("efficiency_core", 6),
    /** Retired (M7R item 2): the surge regulator card, as {@link #BATCH_AMPLIFIER}. */
    SURGE_REGULATOR("surge_regulator", 7),
    /**
     * A network device (M7R item 4): while one is on the network, the controller keeps the chunks of its blocks, the
     * drives, energy cells and anchors, and of the cables between them, loaded, up to the owner's team's chunk cap.
     */
    NETWORK_ANCHOR("network_anchor", 8),
    /**
     * A network device (M7R item 4): while one is on the network, the controller's per-player access levels (none,
     * view, deposit, craft, full) and trusted teams apply to every device of the network.
     */
    SECURITY_MODULE("security_module", 9),
    /** Retired (M7R item 3): every controller records its telemetry history itself. Inert. */
    TELEMETRY_RECORDER("telemetry_recorder", 10),
    /**
     * Retired (M7R item 3): the controller's redstone alarm rules replaced it. A block from an old world keeps its
     * settings until a controller's fill moves them into a rule.
     */
    ALARM_MODULE("alarm_module", 11),
    /**
     * A network device (M7R item 4) that pairs with an antenna through the linking tool; the pair is one link of the
     * network, so the devices at the antenna's site join it (it replaced M6d's remote cluster pieces).
     */
    ANTENNA_MOUNT("antenna_mount", 14),
    /**
     * A network device (M7R item 4) that lets wireless terminals reach the network: 64 blocks from each mount and from
     * the network's antennas with one mount, 32 more per further mount, at most 256 (M6d).
     */
    WIRELESS_ACCESS_MOUNT("wireless_access_mount", 15),
    /**
     * Retired (M7R item 3): the controller keeps the ledger itself. A block from an old world keeps its rows until a
     * controller's fill moves them into the controller's ledger.
     */
    LEDGER_MODULE("ledger_module", 16),
    /**
     * A network device (M7R item 4) that lets wireless terminals, pocket uplinks and restock belts reach the network
     * from other dimensions, and its mail ports and request boards send across dimensions, for a constant FE drain and
     * a surcharge per crossing (M6f).
     */
    DIMENSION_BRIDGE("dimension_bridge", 17);

    public static final Codec<ModuleKind> CODEC = StringRepresentable.fromEnum(ModuleKind::values);
    private static final ModuleKind[] BY_INDEX = byIndexTable();

    private final String name;
    private final int index;

    ModuleKind(String name, int index) {
        this.name = name;
        this.index = index;
    }

    /** The kind's saved index, also its slot in a count array. */
    public int index() {
        return index;
    }

    /**
     * Whether this kind was retired by the multiblock controller (M7R items 1 to 3): it no longer attaches to a
     * controller and adds nothing as a block; the block stays registered so old worlds load it.
     */
    public boolean retired() {
        return this == CHANNEL_ARRAY || this == CAPACITOR_BANK || this == DEVICE_BUS || card() || builtIn();
    }

    /**
     * Whether this kind is a standalone network device now (M7R item 4): security module, network anchor, antenna
     * mount, wireless access mount and dimension bridge. It never attaches to a controller.
     */
    public boolean device() {
        return this == SECURITY_MODULE || this == NETWORK_ANCHOR || this == ANTENNA_MOUNT
                || this == WIRELESS_ACCESS_MOUNT || this == DIMENSION_BRIDGE;
    }

    /**
     * Whether this kind is built into the controller now (M7R item 3): telemetry recorder, ledger module and alarm
     * module. Their blocks are inert; an old ledger's rows and an old alarm's settings move into the controller once.
     */
    public boolean builtIn() {
        return this == TELEMETRY_RECORDER || this == ALARM_MODULE || this == LEDGER_MODULE;
    }

    /**
     * Whether this kind became an upgrade card (M7R item 2): batch amplifier, efficiency core and surge regulator. In a
     * count array its index counts the controller's installed cards of the kind.
     */
    public boolean card() {
        return this == BATCH_AMPLIFIER || this == EFFICIENCY_CORE || this == SURGE_REGULATOR;
    }

    /**
     * The research unlock a player's team needs to place an expansion of this kind, or null when it needs none (the
     * four core modules).
     */
    public @Nullable Identifier unlock() {
        return switch (this) {
            case CHANNEL_ARRAY, CAPACITOR_BANK, BATCH_AMPLIFIER, DEVICE_BUS -> null;
            case EFFICIENCY_CORE -> ResearchGates.EFFICIENCY_CORE;
            case SURGE_REGULATOR -> ResearchGates.SURGE_REGULATOR;
            case NETWORK_ANCHOR -> ResearchGates.NETWORK_ANCHOR;
            case SECURITY_MODULE -> ResearchGates.SECURITY_MODULE;
            case TELEMETRY_RECORDER -> ResearchGates.TELEMETRY_RECORDER;
            case ALARM_MODULE -> ResearchGates.ALARM_MODULE;
            case ANTENNA_MOUNT -> ResearchGates.ANTENNA;
            case WIRELESS_ACCESS_MOUNT -> ResearchGates.WIRELESS_TERMINAL;
            case LEDGER_MODULE -> ResearchGates.LEDGER;
            case DIMENSION_BRIDGE -> DimensionBridge.UNLOCK;
        };
    }

    /**
     * The kind with {@code index}, or null for an unknown one (a deleted kind or a save from a later version).
     */
    public static @Nullable ModuleKind byIndex(int index) {
        return index >= 0 && index < BY_INDEX.length ? BY_INDEX[index] : null;
    }

    /** One more than the highest index; the length of a count array. */
    public static int count() {
        return BY_INDEX.length;
    }

    private static ModuleKind[] byIndexTable() {
        int length = 0;
        for (ModuleKind kind : values()) {
            length = Math.max(length, kind.index + 1);
        }
        ModuleKind[] table = new ModuleKind[length];
        for (ModuleKind kind : values()) {
            table[kind.index] = kind;
        }
        return table;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
