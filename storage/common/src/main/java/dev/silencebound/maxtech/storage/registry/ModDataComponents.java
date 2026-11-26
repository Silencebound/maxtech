package dev.silencebound.maxtech.storage.registry;

import dev.silencebound.maxtech.storage.crafting.generator.GeneratorSettings;
import dev.silencebound.maxtech.storage.crafting.provider.LockMode;
import dev.silencebound.maxtech.storage.crafting.job.CraftLimits;
import dev.silencebound.maxtech.storage.crafting.stock.StockKeeperWorker;
import dev.silencebound.maxtech.storage.crafting.stock.StockRule;

import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternCodecs;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cell.CellData;
import dev.silencebound.maxtech.storage.save.KeptValue;
import dev.silencebound.maxtech.storage.save.StorageSaveFormats;
import dev.silencebound.maxtech.storage.controller.module.AlarmSettings;
import dev.silencebound.maxtech.storage.controller.recorder.ControllerRecorders;
import dev.silencebound.maxtech.storage.controller.module.SecuritySettings;
import dev.silencebound.maxtech.storage.iface.FluidStock;
import dev.silencebound.maxtech.storage.iface.InterfaceItems;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import dev.silencebound.maxtech.storage.logic.controller.GraphSlot;
import dev.silencebound.maxtech.storage.logic.controller.LogicCard;
import dev.silencebound.maxtech.storage.logic.controller.SharedVariable;
import dev.silencebound.maxtech.storage.p2p.P2PLinkerItem;
import dev.silencebound.maxtech.storage.p2p.P2PSettings;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import dev.silencebound.maxtech.storage.port.BusAccess;
import dev.silencebound.maxtech.storage.port.PortCards;
import dev.silencebound.maxtech.storage.condition.ConditionSettings;
import dev.silencebound.maxtech.storage.display.DisplaySettings;
import dev.silencebound.maxtech.storage.port.PortEntrySettings;
import dev.silencebound.maxtech.storage.port.PortMode;
import dev.silencebound.maxtech.storage.port.PortOrder;
import dev.silencebound.maxtech.storage.world.WorldSettings;
import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.storage.logic.NetworkRef;
import dev.silencebound.maxtech.storage.tool.cartridge.CartridgeData;
import net.minecraft.core.GlobalPos;
import dev.silencebound.maxtech.storage.redstone.RedstoneMode;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.StringRepresentable;

/** Storage's data component types. Shared by every Storage area; add entries, don't reorder them. */
public final class ModDataComponents {
    private static final RegistryHelper.Registrar<DataComponentType<?>> COMPONENTS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.DATA_COMPONENT_TYPE);

    /** A storage cell's parts, id and cached stats. Synced for its name and tooltip. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<CellData>> CELL = COMPONENTS.register(
            "cell",
            () -> DataComponentType.<CellData>builder()
                    .persistent(StorageSaveFormats.ITEMS.codec(MaxtechStorage.id("cell"), CellData.CODEC))
                    .networkSynchronized(CellData.STREAM_CODEC).build());
    /**
     * Set on an item whose energy components hold MaxTech Units (MLE item 4); an item without it is from before and
     * holds FE, converted when read ({@code ItemEnergy}). Synced, so tooltips and charge bars read the same.
     */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<Boolean>> ENERGY_IN_MU = COMPONENTS
            .register("energy_in_mu", () -> DataComponentType.<Boolean>builder().persistent(Codec.BOOL)
                    .networkSynchronized(ByteBufCodecs.BOOL).build());
    /** MU in a portable cell's battery. Synced for its charge bar. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<Integer>> PORTABLE_ENERGY = COMPONENTS
            .register("portable_energy", () -> DataComponentType.<Integer>builder().persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT).build());

    /** A port part item's mode, kept when the part comes off a cable (its filter is vanilla's container contents). */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<PortMode>> PORT_MODE = COMPONENTS
            .register("port_mode", () -> DataComponentType.<PortMode>builder()
                    .persistent(StringRepresentable.fromEnum(PortMode::values)).build());
    /** A port's storage access (B09 item 8), only when not the default "both". */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<BusAccess>> PORT_ACCESS = COMPONENTS
            .register("port_access", () -> DataComponentType.<BusAccess>builder()
                    .persistent(StringRepresentable.fromEnum(BusAccess::values)).build());
    /** A port's filter entry settings (B09 items 18 and 22: match modes, tags, keep amounts), only when set. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<List<PortEntrySettings>>> PORT_ENTRIES = COMPONENTS
            .register("port_entries", () -> DataComponentType.<List<PortEntrySettings>>builder()
                    .persistent(PortEntrySettings.LIST_CODEC).build());
    /** A port's export order (B09 item 18), only when not filter order. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<PortOrder>> PORT_ORDER = COMPONENTS
            .register("port_order", () -> DataComponentType.<PortOrder>builder()
                    .persistent(StringRepresentable.fromEnum(PortOrder::values)).build());
    /** A port's storage priority (B09 item 8), only when not 0. Synced for the tooltip. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<Integer>> PORT_PRIORITY = COMPONENTS
            .register("port_priority", () -> DataComponentType.<Integer>builder().persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT).build());
    /** The speed cards in a port's item, block or part (M6c). Synced for the tooltip. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<Integer>> PORT_SPEED_CARDS = COMPONENTS
            .register("port_speed_cards",
                    () -> DataComponentType.<Integer>builder().persistent(Codec.intRange(1, PortCards.MAX_SLOTS))
                            .networkSynchronized(ByteBufCodecs.VAR_INT).build());

    /** A condition switch item's settings, block and part alike; only when they differ from a new switch's. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<ConditionSettings>> CONDITION = COMPONENTS
            .register("condition",
                    () -> DataComponentType.<ConditionSettings>builder().persistent(ConditionSettings.CODEC).build());
    /** An interface's ghost stock slots: nine items with their target counts, three filled buckets. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<ItemContainerContents>> INTERFACE_CONFIG = COMPONENTS
            .register("interface_config",
                    () -> DataComponentType.<ItemContainerContents>builder().persistent(ItemContainerContents.CODEC)
                            .networkSynchronized(ItemContainerContents.STREAM_CODEC).build());
    /**
     * The fluid an interface holds in its three stock tanks (its items are vanilla's container contents); a tank whose
     * fluid no longer loads (a removed mod) is kept as saved.
     */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<List<KeptValue<FluidStock>>>> INTERFACE_FLUIDS = COMPONENTS
            .register("interface_fluids", () -> DataComponentType.<List<KeptValue<FluidStock>>>builder()
                    .persistent(FluidStock.LIST_CODEC).build());
    /**
     * An interface's stock slots whose item no longer loads (a removed mod), one entry per slot, the others empty; set
     * only while one is kept. The readable stock is in vanilla's container contents.
     */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<List<KeptValue<ItemStack>>>> INTERFACE_KEPT_ITEMS = COMPONENTS
            .register("interface_kept_items", () -> DataComponentType.<List<KeptValue<ItemStack>>>builder()
                    .persistent(InterfaceItems.KEPT_ITEMS_CODEC).build());
    /** MU a storage controller held when it was broken; placing it puts them back. Synced for its tooltip. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<Long>> CONTROLLER_ENERGY = COMPONENTS
            .register("controller_energy", () -> DataComponentType.<Long>builder().persistent(Codec.LONG)
                    .networkSynchronized(ByteBufCodecs.VAR_LONG).build());

    /** A display panel's or display part's screen settings, kept when it is broken. Synced for the tooltip. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<DisplaySettings>> DISPLAY_SETTINGS = COMPONENTS
            .register("display_settings", () -> DataComponentType.<DisplaySettings>builder()
                    .persistent(DisplaySettings.CODEC).networkSynchronized(DisplaySettings.STREAM_CODEC).build());
    /** A P2P end's role and frequency, block and part alike; only while linked. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<P2PSettings>> P2P_LINK = COMPONENTS
            .register("p2p_link", () -> DataComponentType.<P2PSettings>builder().persistent(P2PSettings.CODEC).build());
    /** The last input the P2P linking tool made: its kind and frequency. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<P2PLinkerItem.Held>> P2P_LINKER = COMPONENTS
            .register("p2p_linker",
                    () -> DataComponentType.<P2PLinkerItem.Held>builder().persistent(P2PLinkerItem.Held.CODEC).build());

    /**
     * A world device's filter, mode and tool, kept in its block and part items alike. Synced for the item's tooltip.
     */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<WorldSettings>> WORLD_SETTINGS = COMPONENTS
            .register("world_settings", () -> DataComponentType.<WorldSettings>builder().persistent(WorldSettings.CODEC)
                    .networkSynchronized(WorldSettings.STREAM_CODEC).build());

    /**
     * A storage controller's network name (M7L2 row 13), so a broken core placed again keeps its name when it is still
     * free. Synced for its tooltip.
     */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<String>> NETWORK_NAME = COMPONENTS
            .register("network_name",
                    () -> DataComponentType.<String>builder().persistent(Codec.string(1, NetworkRef.MAX_NAME))
                            .networkSynchronized(ByteBufCodecs.stringUtf8(NetworkRef.MAX_NAME * 4)).build());

    /**
     * A storage controller's security rules (M6c2 security module); only when not the defaults. Synced for its tooltip.
     */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<SecuritySettings>> NETWORK_SECURITY = COMPONENTS
            .register("network_security", () -> DataComponentType.<SecuritySettings>builder()
                    .persistent(SecuritySettings.CODEC).networkSynchronized(SecuritySettings.STREAM_CODEC).build());

    /**
     * A storage controller's limits for automated crafting jobs (M7L2 chunk 20); only when not the defaults. Synced
     * with the item like its other settings.
     */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<CraftLimits>> CRAFT_LIMITS = COMPONENTS
            .register("craft_limits", () -> DataComponentType.<CraftLimits>builder().persistent(CraftLimits.CODEC)
                    .networkSynchronized(CraftLimits.STREAM_CODEC).build());

    /** An alarm module's condition and threshold; only when not the defaults. Synced for its tooltip. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<AlarmSettings>> ALARM = COMPONENTS
            .register("alarm", () -> DataComponentType.<AlarmSettings>builder().persistent(AlarmSettings.CODEC)
                    .networkSynchronized(AlarmSettings.STREAM_CODEC).build());

    /**
     * A controller's redstone alarm rules (M7R item 3), carried by the item of a controller's last block and by network
     * snapshots; only when it has rules.
     */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<List<AlarmSettings>>> ALARM_RULES = COMPONENTS
            .register("alarm_rules", () -> DataComponentType.<List<AlarmSettings>>builder()
                    .persistent(AlarmSettings.CODEC.listOf())
                    .networkSynchronized(
                            AlarmSettings.STREAM_CODEC.apply(ByteBufCodecs.list(ControllerRecorders.MOST_RULES)))
                    .build());

    /** The antenna or antenna mount the linking tool was last sneak-used on; the next one used is paired with it. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<GlobalPos>> ANTENNA_LINKER = COMPONENTS
            .register("antenna_linker", () -> DataComponentType.<GlobalPos>builder().persistent(GlobalPos.CODEC)
                    .networkSynchronized(GlobalPos.STREAM_CODEC).build());

    /** The storage controller a wireless terminal is bound to. Synced for its tooltip. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<GlobalPos>> WIRELESS_LINK = COMPONENTS
            .register("wireless_link", () -> DataComponentType.<GlobalPos>builder().persistent(GlobalPos.CODEC)
                    .networkSynchronized(GlobalPos.STREAM_CODEC).build());

    /** MU in a wireless terminal, crafting tablet or logic remote. Synced for its charge bar and tooltip. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<Integer>> WIRELESS_ENERGY = COMPONENTS
            .register("wireless_energy", () -> DataComponentType.<Integer>builder().persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT).build());

    /** The storage block a signal probe is bound to. Synced for its tooltip. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<GlobalPos>> PROBE_TARGET = COMPONENTS
            .register("probe_target", () -> DataComponentType.<GlobalPos>builder().persistent(GlobalPos.CODEC)
                    .networkSynchronized(GlobalPos.STREAM_CODEC).build());

    /** The puzzle a written puzzle cartridge carries. Synced for its tooltip. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<CartridgeData>> CARTRIDGE = COMPONENTS
            .register("cartridge", () -> DataComponentType.<CartridgeData>builder().persistent(CartridgeData.CODEC)
                    .networkSynchronized(CartridgeData.STREAM_CODEC).build());

    /** MU in the network goggles. Synced for their charge bar and tooltip. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<Integer>> GOGGLES_ENERGY = COMPONENTS
            .register("goggles_energy", () -> DataComponentType.<Integer>builder().persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT).build());
    /** The block the trace pen traces from. Synced for its tooltip. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<GlobalPos>> TRACE_START = COMPONENTS
            .register("trace_start", () -> DataComponentType.<GlobalPos>builder().persistent(GlobalPos.CODEC)
                    .networkSynchronized(GlobalPos.STREAM_CODEC).build());

    /**
     * The pattern an encoded pattern carries (M7a), kept as saved when it no longer loads (an ingredient or output of a
     * removed mod). Synced for its tooltip.
     */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<KeptValue<CraftingPattern>>> ENCODED_PATTERN = COMPONENTS
            .register("encoded_pattern",
                    () -> DataComponentType.<KeptValue<CraftingPattern>>builder().persistent(PatternCodecs.KEPT_PATTERN)
                            .networkSynchronized(PatternCodecs.KEPT_STREAM_CODEC).build());

    /** Whether a port item holds a crafting card (M7c area D); only when it does. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<Boolean>> PORT_CRAFTING_CARD = COMPONENTS
            .register("port_crafting_card", () -> DataComponentType.<Boolean>builder().persistent(Codec.BOOL)
                    .networkSynchronized(ByteBufCodecs.BOOL).build());
    /** A stock keeper item's rules (M7c area D), up to the last used one; only when it has any. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<List<StockRule>>> STOCK_RULES = COMPONENTS
            .register("stock_rules", () -> DataComponentType.<List<StockRule>>builder()
                    .persistent(StockRule.CODEC.listOf(0, StockKeeperWorker.MAX_RULES)).build());
    /** A pattern provider item whose blocking mode is off (M7c); absent means on, the default. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<Boolean>> PATTERN_PROVIDER_NON_BLOCKING = COMPONENTS
            .register("pattern_provider_non_blocking", () -> DataComponentType.<Boolean>builder().persistent(Codec.BOOL)
                    .networkSynchronized(ByteBufCodecs.BOOL).build());
    /** A pattern provider's or crafter's lock mode (B09 item 26); only when it is not the default. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<LockMode>> PATTERN_PROVIDER_LOCK = COMPONENTS
            .register("pattern_provider_lock", () -> DataComponentType.<LockMode>builder().persistent(LockMode.CODEC)
                    .networkSynchronized(LockMode.STREAM_CODEC).build());
    /** A generator provider's output and signal settings (M7C item 11); only when they differ from the default. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<GeneratorSettings>> GENERATOR_SETTINGS = COMPONENTS
            .register("generator_settings", () -> DataComponentType.<GeneratorSettings>builder()
                    .persistent(GeneratorSettings.CODEC).networkSynchronized(GeneratorSettings.STREAM_CODEC).build());

    /** The card in an interface's card slot (M7c, used by the crafting card). */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<ItemContainerContents>> INTERFACE_CARD = COMPONENTS
            .register("interface_card",
                    () -> DataComponentType.<ItemContainerContents>builder().persistent(ItemContainerContents.CODEC)
                            .networkSynchronized(ItemContainerContents.STREAM_CODEC).build());

    /** A network device item's label (M7L), block and part alike; only when it has one. Synced for the tooltip. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<DeviceLabel>> DEVICE_LABEL = COMPONENTS
            .register("device_label", () -> DataComponentType.<DeviceLabel>builder().persistent(DeviceLabel.CODEC)
                    .networkSynchronized(DeviceLabel.STREAM_CODEC).build());

    /**
     * A network device item turned off by logic (M7L); only present, as true, while off. Synced for the tooltip. The
     * block or part reads it when placed, so the device comes back off.
     */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<Boolean>> SWITCHED_OFF = COMPONENTS
            .register("switched_off", () -> DataComponentType.<Boolean>builder().persistent(Codec.BOOL)
                    .networkSynchronized(ByteBufCodecs.BOOL).build());

    /**
     * A sheddable network device item's power priority (M7L2 load shedding, 1 to 5); only present when it differs from
     * the device's default. The block or part reads it when placed.
     */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<Integer>> POWER_PRIORITY = COMPONENTS
            .register("power_priority", () -> DataComponentType.<Integer>builder().persistent(Codec.intRange(1, 5))
                    .networkSynchronized(ByteBufCodecs.VAR_INT).build());

    /**
     * A port's, IO port's or interface's redstone mode (B09 item 9) by its fixed id, only when not "ignore". Synced for
     * the tooltip.
     */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<RedstoneMode>> REDSTONE_MODE = COMPONENTS
            .register("redstone_mode", () -> DataComponentType.<RedstoneMode>builder().persistent(RedstoneMode.CODEC)
                    .networkSynchronized(RedstoneMode.STREAM_CODEC).build());

    /** A Logic Controller's graphs with their memory (M7L area B); not synced, the client never reads them. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<List<GraphSlot>>> LOGIC_GRAPHS = COMPONENTS
            .register("logic_graphs",
                    () -> DataComponentType.<List<GraphSlot>>builder().persistent(GraphSlot.LIST_CODEC).build());
    /** The rule on a written logic card (M7L2 sharing). Synced for the tooltip. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<LogicCard>> LOGIC_CARD = COMPONENTS
            .register("logic_card", () -> DataComponentType.<LogicCard>builder().persistent(LogicCard.CODEC)
                    .networkSynchronized(LogicCard.STREAM_CODEC).build());

    /** A Logic Controller's shared variables (M7L2 batch 4); not synced, the client never reads them. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<List<SharedVariable>>> LOGIC_VARIABLES = COMPONENTS
            .register("logic_variables", () -> DataComponentType.<List<SharedVariable>>builder()
                    .persistent(SharedVariable.LIST_CODEC).build());

    /** Most notices a logic remote keeps ({@link #REMOTE_NOTICES}). */
    public static final int REMOTE_NOTICE_LIMIT = 16;

    /**
     * The last notices a logic remote saw of its network (M7L2), newest last, at most {@link #REMOTE_NOTICE_LIMIT};
     * shown read only while it is out of range. Synced, so a creative inventory does not drop it.
     */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<List<NetworkNotices.Notice>>> REMOTE_NOTICES = COMPONENTS
            .register("remote_notices",
                    () -> DataComponentType.<List<NetworkNotices.Notice>>builder()
                            .persistent(NetworkNotices.Notice.CODEC.sizeLimitedListOf(REMOTE_NOTICE_LIMIT))
                            .networkSynchronized(
                                    NetworkNotices.Notice.STREAM_CODEC.apply(ByteBufCodecs.list(REMOTE_NOTICE_LIMIT)))
                            .build());

    /**
     * An encoded pattern's priority (M7C item 2), -999 to 999; only when not 0. Among patterns making the same item the
     * planner tries the highest first. Synced for its tooltip.
     */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<Integer>> PATTERN_PRIORITY = COMPONENTS
            .register("pattern_priority",
                    () -> DataComponentType.<Integer>builder()
                            .persistent(Codec.intRange(PatternItem.MIN_PRIORITY, PatternItem.MAX_PRIORITY))
                            .networkSynchronized(ByteBufCodecs.VAR_INT).build());

    /**
     * The results a crafter (B09 item 29) still held in its buffer when it was broken; its patterns are vanilla's
     * container contents. Placing it puts them back.
     */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<ItemContainerContents>> CRAFTER_BUFFER = COMPONENTS
            .register("crafter_buffer",
                    () -> DataComponentType.<ItemContainerContents>builder().persistent(ItemContainerContents.CODEC)
                            .networkSynchronized(ItemContainerContents.STREAM_CODEC).build());

    private ModDataComponents() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }
}
