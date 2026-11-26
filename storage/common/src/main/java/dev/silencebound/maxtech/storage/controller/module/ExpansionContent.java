package dev.silencebound.maxtech.storage.controller.module;

import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockLoot;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.config.StorageModules;
import dev.silencebound.maxtech.storage.controller.ClusterStats;
import dev.silencebound.maxtech.storage.controller.card.CardContent;
import dev.silencebound.maxtech.storage.controller.card.CardKind;
import dev.silencebound.maxtech.storage.energy.EnergyCellBlock;
import dev.silencebound.maxtech.storage.energy.EnergyCellBlockEntity;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;

/**
 * The blocks that were controller expansions before the multiblock controller (M7R), and the network devices that grew
 * out of them: blocks, items, block entity types, recipes and info lines. Loaded right after the controller, from
 * {@code ControllerContent.init()}.
 *
 * <ul>
 * <li>Network devices ({@link ServiceDeviceBlock}, M7R item 4): network anchor, security module, antenna mount,
 * wireless access mount and dimension bridge, placed anywhere on the network; the mounts and the bridge use one channel
 * each, the anchor and the security module none.
 * <li>Retired blocks, registered only so old worlds load them: no recipe, no creative tab, inert. The channel array and
 * device bus (item 1) and the telemetry recorder, alarm module and ledger module (item 3) drop themselves; the batch
 * amplifier, efficiency core and surge regulator (item 2) drop their upgrade card, and a controller's fill that meets
 * one turns it into the card. The capacitor bank is not registered at all (item 5): its id loads as a tier-1 energy
 * cell ({@code RetiredIds}).
 * </ul>
 *
 * The status crown and light strip (M7R item 6) were removed on 2026-10-18 by owner decision; their ids are gone, so a
 * placed one turns into air. The ids keep their {@code controller_} prefix so old worlds keep the blocks.
 */
public final class ExpansionContent {
    /** A retired channel array's or device bus's info line. */
    public static final String RETIRED_KEY = "info." + MaxtechStorage.MOD_ID + ".expansion.retired";
    /** A retired batch amplifier's, efficiency core's or surge regulator's info line. */
    public static final String RETIRED_CARD_KEY = "info." + MaxtechStorage.MOD_ID + ".expansion.retired_card";
    public static final String DEVICE_ACTIVE_KEY = "info." + MaxtechStorage.MOD_ID + ".service_device.active";
    public static final String DEVICE_IDLE_KEY = "info." + MaxtechStorage.MOD_ID + ".service_device.idle";
    public static final String NEEDS_RESEARCH_KEY = "message." + MaxtechStorage.MOD_ID + ".expansion.needs_research";

    public static final String EFFICIENCY_KEY = "info." + MaxtechStorage.MOD_ID + ".controller.efficiency";
    public static final String SURGE_KEY = "info." + MaxtechStorage.MOD_ID + ".controller.surge";

    private static final TagKey<Item> PLATE = MaterialTags.SIGNAL_ALLOY_PLATE;

    // Retired (M7R item 1): every controller block face is a root link. The id keeps its bandwidth-era name.
    public static final BlockEntry<ExpansionBlock, ExpansionItem> CHANNEL_ARRAY = retired("bandwidth_array",
            "Controller Channel Array", ModuleKind.CHANNEL_ARRAY);
    // The capacitor bank is gone (M7R item 5): its id is an alias of the tier-1 energy cell (RetiredIds).
    // Retired (M7R item 2): the batch amplifier card replaced it.
    public static final BlockEntry<ExpansionBlock, ExpansionItem> BATCH_AMPLIFIER = retiredCard("batch_amplifier",
            "Controller Batch Amplifier", CardKind.BATCH_AMPLIFIER);
    // Retired (M7R item 1): networks have no device limit, only channels.
    public static final BlockEntry<ExpansionBlock, ExpansionItem> DEVICE_BUS = retired("device_bus",
            "Controller Device Bus", ModuleKind.DEVICE_BUS);
    // Retired (M7R item 2): the efficiency core and surge regulator cards replaced them.
    public static final BlockEntry<ExpansionBlock, ExpansionItem> EFFICIENCY_CORE = retiredCard("efficiency_core",
            "Controller Efficiency Core", CardKind.EFFICIENCY_CORE);
    public static final BlockEntry<ExpansionBlock, ExpansionItem> SURGE_REGULATOR = retiredCard("surge_regulator",
            "Controller Surge Regulator", CardKind.SURGE_REGULATOR);
    // An advanced signal processor keeps the far devices in touch; superconductor wire holds the chunks' tickets.
    public static final BlockEntry<ServiceDeviceBlock, ExpansionItem> NETWORK_ANCHOR = device("network_anchor",
            "Network Anchor", ModuleKind.NETWORK_ANCHOR, "advanced_signal_processor", "superconductor_wire");
    // An advanced logic processor checks every request; display glass shows the rules.
    public static final BlockEntry<ServiceDeviceBlock, ExpansionItem> SECURITY_MODULE = device("security_module",
            "Security Module", ModuleKind.SECURITY_MODULE, "advanced_logic_processor", "display_glass");
    // Retired (M7R item 3): built into the controller.
    public static final BlockEntry<ExpansionBlock, ExpansionItem> TELEMETRY_RECORDER = retired("telemetry_recorder",
            "Controller Telemetry Recorder", ModuleKind.TELEMETRY_RECORDER);
    public static final BlockEntry<ExpansionBlock, ExpansionItem> ALARM_MODULE = retired("alarm_module",
            "Controller Alarm Module", ModuleKind.ALARM_MODULE);

    // An advanced signal processor keeps the far link; superconductor wire feeds the antenna.
    public static final BlockEntry<ServiceDeviceBlock, ExpansionItem> ANTENNA_MOUNT = device("antenna_mount",
            "Antenna Mount", ModuleKind.ANTENNA_MOUNT, "advanced_signal_processor", "superconductor_wire");
    // An advanced I/O processor answers the terminals; an oscillator carries the signal.
    public static final BlockEntry<ServiceDeviceBlock, ExpansionItem> WIRELESS_ACCESS_MOUNT = device(
            "wireless_access_mount", "Wireless Access Mount", ModuleKind.WIRELESS_ACCESS_MOUNT, "advanced_io_processor",
            "oscillator");
    // Retired (M7R item 3): built into the controller.
    public static final BlockEntry<ExpansionBlock, ExpansionItem> LEDGER_MODULE = retired("ledger_module",
            "Controller Ledger Module", ModuleKind.LEDGER_MODULE);
    // An advanced I/O processor carries the operations across; superconductor wire holds the link open and void
    // lattice (endgame, tier 5) takes the place of the top casing plates.
    public static final BlockEntry<ServiceDeviceBlock, ExpansionItem> DIMENSION_BRIDGE = device("dimension_bridge",
            "Dimension Bridge", ModuleKind.DIMENSION_BRIDGE, "advanced_io_processor", "superconductor_wire");

    /** The retired expansion blocks, registered only so old worlds load them, in registration order. */
    public static final List<BlockEntry<ExpansionBlock, ExpansionItem>> ALL = List.of(CHANNEL_ARRAY, BATCH_AMPLIFIER,
            DEVICE_BUS, EFFICIENCY_CORE, SURGE_REGULATOR, TELEMETRY_RECORDER, ALARM_MODULE, LEDGER_MODULE);

    /** The standalone network devices that were expansions before M7R item 4, in creative tab order. */
    public static final List<BlockEntry<ServiceDeviceBlock, ExpansionItem>> DEVICES = List.of(NETWORK_ANCHOR,
            SECURITY_MODULE, ANTENNA_MOUNT, WIRELESS_ACCESS_MOUNT, DIMENSION_BRIDGE);

    public static final String ANCHOR_KEY = "info." + MaxtechStorage.MOD_ID + ".controller.anchor";
    public static final String TELEMETRY_KEY = "info." + MaxtechStorage.MOD_ID + ".controller.telemetry";
    public static final String ANCHOR_CAPPED_KEY = "info." + MaxtechStorage.MOD_ID + ".controller.anchor_capped";
    public static final String SECURITY_KEY = "info." + MaxtechStorage.MOD_ID + ".controller.security";
    public static final String SECURITY_EDIT_KEY = "message." + MaxtechStorage.MOD_ID + ".security.owner_only";
    public static final String SECURITY_DARK_KEY = "message." + MaxtechStorage.MOD_ID + ".security.dark";
    public static final String SECURITY_TOOLTIP_KEY = "tooltip." + MaxtechStorage.MOD_ID + ".controller.security";

    /**
     * The expansions' block entity type. A network device (M7R item 4) of an old world saved its data under this type's
     * id; vanilla then makes the block entity through this factory, which hands such a block its
     * {@link ServiceDeviceBlockEntity} (of {@link #SERVICE_DEVICE_BLOCK_ENTITY}), so it keeps its data and saves it
     * under the new type from then on.
     */
    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<StorageDeviceBlockEntity>> EXPANSION_BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE)
            .register("controller_expansion", () -> new BlockEntityType<>(ExpansionContent::expansionEntity, blocks()));
    /** The network devices' block entity type (M7R item 4). */
    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<ServiceDeviceBlockEntity>> SERVICE_DEVICE_BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE)
            .register("service_device", () -> new BlockEntityType<>(ServiceDeviceBlockEntity::create, devices()));

    static {
        ModContent.CONTENT.translation(RETIRED_KEY, "Retired: controller blocks do this now; this block does nothing");
        ModContent.CONTENT.translation(RETIRED_CARD_KEY,
                "Retired: break it for its upgrade card, or let the controller next to it take the card");
        ModContent.CONTENT.translation(DEVICE_ACTIVE_KEY, "Active on its network");
        ModContent.CONTENT.translation(DEVICE_IDLE_KEY, "Idle: on no network with a working controller");
        ModContent.CONTENT.translation(NEEDS_RESEARCH_KEY, "Your team needs the research %s to place this");

        ModContent.CONTENT.translation(EFFICIENCY_KEY, "Operations cost %s%% of their MU");
        ModContent.CONTENT.translation(SURGE_KEY, "Below %s%% MU, automation waits and players go first");
        ModContent.CONTENT.translation(TELEMETRY_KEY, "Telemetry: %s of %s samples, one every %s s");
        ModContent.CONTENT.translation(ANCHOR_KEY, "Anchor: %s of %s chunks kept loaded (team cap %s)");
        ModContent.CONTENT.translation(ANCHOR_CAPPED_KEY,
                "Anchor: %s of %s chunks kept loaded; the team's cap of %s is reached");
        ModContent.CONTENT.translation(SECURITY_KEY, "Security: %s player and team rules");
        ModContent.CONTENT.translation(SECURITY_EDIT_KEY, "Only the network's owner can change its security");
        ModContent.CONTENT.translation(SECURITY_DARK_KEY,
                "This security module is on no network with a working controller");
        ModContent.CONTENT.translation(SECURITY_TOOLTIP_KEY, "Security rules: %s");
        SecurityTexts.register();
        DimensionBridge.registerTexts();
    }

    private ExpansionContent() {
    }

    /**
     * The block entity for an expansion, for a network device that saved under the old type, or for a capacitor bank of
     * an old world that loads as a tier-1 energy cell (M7R item 5; it keeps its owner and label).
     */
    private static StorageDeviceBlockEntity expansionEntity(BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof ServiceDeviceBlock) {
            return ServiceDeviceBlockEntity.create(pos, state);
        }
        if (state.getBlock() instanceof EnergyCellBlock) {
            return new EnergyCellBlockEntity(pos, state);
        }
        return new ExpansionBlockEntity(pos, state);
    }

    private static java.util.Set<Block> devices() {
        java.util.Set<Block> blocks = new java.util.LinkedHashSet<>();
        for (BlockEntry<ServiceDeviceBlock, ExpansionItem> entry : DEVICES) {
            blocks.add(entry.get());
        }
        return blocks;
    }

    private static java.util.Set<Block> blocks() {
        java.util.Set<Block> blocks = new java.util.LinkedHashSet<>();
        for (BlockEntry<ExpansionBlock, ExpansionItem> entry : ALL) {
            blocks.add(entry.get());
        }
        return blocks;
    }

    /**
     * The controller's full-detail lines for what its modules change, one per module kind present: the FE cost and the
     * surge level. Nothing for a cluster without them.
     */
    public static void addModuleInfo(ClusterStats stats, BlockInfoSink sink) {
        if (stats.energyPercent() < 100) {
            sink.line(Component.translatable(EFFICIENCY_KEY, InfoFormat.number(stats.energyPercent())));
        }
        if (stats.surgeReservePercent() > 0) {
            sink.line(Component.translatable(SURGE_KEY, InfoFormat.number(stats.surgeReservePercent())));
        }
    }

    /**
     * A retired expansion block that became the upgrade card of {@code card} (M7R item 2): registered so old worlds
     * load, with no recipe and no place in the creative tab; broken, it drops the card, and a controller's fill that
     * meets it turns it into the card in a slot.
     */
    private static BlockEntry<ExpansionBlock, ExpansionItem> retiredCard(String name, String english, CardKind card) {
        return ModContent.CONTENT.block("controller_" + name, p -> new ExpansionBlock(card.module(), p))
                .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(3.0F, 6.0F).sound(SoundType.METAL)
                        .lightLevel(ExpansionBlock::light))
                .item((block, properties) -> new ExpansionItem(block, card.module(), properties)).lang(english)
                .loot(BlockLoot.table(() -> LootTable.lootTable()
                        .withPool(LootPool.lootPool().setRolls(ConstantValue.exactly(1.0F))
                                .add(LootItem.lootTableItem(CardContent.item(card))))))
                .tag(BlockTags.MINEABLE_WITH_PICKAXE).register();
    }

    /**
     * A retired expansion block (M7R items 1 and 3: the channel array and device bus, and the recorders built into the
     * controller now): registered so old worlds load, with no recipe and no place in the creative tab; inert, and
     * broken it drops itself.
     */
    private static BlockEntry<ExpansionBlock, ExpansionItem> retired(String name, String english, ModuleKind kind) {
        return ModContent.CONTENT.block("controller_" + name, p -> new ExpansionBlock(kind, p))
                .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(3.0F, 6.0F).sound(SoundType.METAL)
                        .lightLevel(ExpansionBlock::light))
                .item((block, properties) -> new ExpansionItem(block, kind, properties)).lang(english)
                .loot(BlockLoot.SELF).tag(BlockTags.MINEABLE_WITH_PICKAXE).register();
    }

    /**
     * A network device named {@code controller_<name>} (the id it had as an expansion, so old worlds keep it): casing
     * plates around a processor that does the device's job and the device's own part ({@code PXP / YXY / PXP}).
     */
    private static BlockEntry<ServiceDeviceBlock, ExpansionItem> device(String name, String english, ModuleKind kind,
            String processor, String part) {
        return ModContent.CONTENT.block("controller_" + name, p -> new ServiceDeviceBlock(kind, p))
                .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(3.0F, 6.0F).sound(SoundType.METAL)
                        .lightLevel(ExpansionBlock::light))
                .item((block, properties) -> new ExpansionItem(block, kind, properties)).lang(english)
                .loot(BlockLoot.SELF).tag(BlockTags.MINEABLE_WITH_PICKAXE).recipe((recipes, item) -> {
                    boolean lattice = kind == ModuleKind.DIMENSION_BRIDGE;
                    var recipe = recipes.shaped(RecipeCategory.REDSTONE, item).pattern(lattice ? "LXL" : "PXP")
                            .pattern("YXY").pattern("PXP").define('P', PLATE)
                            .define('X', MaterialTags.component(processor)).define('Y', MaterialTags.component(part));
                    if (lattice) {
                        recipe.define('L', MaterialTags.component("void_lattice"));
                    }
                    // The anchor's, the wireless mount's and the bridge's recipes load only while their module switch
                    // is on.
                    recipe.unlockedBy("has_" + processor, recipes.has(MaterialTags.component(processor)))
                            .save(gated(recipes.output(), kind));
                }).creativeTab().register();
    }

    /** {@code output}, behind the module switch of {@code kind} when it has one. */
    private static RecipeOutput gated(RecipeOutput output, ModuleKind kind) {
        return switch (kind) {
            case NETWORK_ANCHOR -> StorageModules.gate(output, StorageModules.NETWORK_ANCHOR);
            case WIRELESS_ACCESS_MOUNT -> StorageModules.gate(output, StorageModules.WIRELESS);
            case DIMENSION_BRIDGE ->
                StorageModules.gate(StorageModules.gate(output, StorageModules.WIRELESS), StorageModules.VOID_GEODES);
            default -> output;
        };
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }
}
