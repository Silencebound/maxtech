package dev.silencebound.maxtech.storage.dev;

import dev.silencebound.maxtech.storage.energy.ItemEnergy;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.lib.facade.Facades;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.bulk.BulkContent;
import dev.silencebound.maxtech.storage.bulk.BulkDriveBlockEntity;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.cable.CableLook;
import dev.silencebound.maxtech.storage.cable.StorageCableBlock;
import dev.silencebound.maxtech.storage.cell.CellCasing;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.cell.CellData;
import dev.silencebound.maxtech.storage.cell.StorageCellItem;
import dev.silencebound.maxtech.storage.condition.ConditionContent;
import dev.silencebound.maxtech.storage.condition.ConditionSettings;
import dev.silencebound.maxtech.storage.condition.ConditionSwitchBlockEntity;
import dev.silencebound.maxtech.storage.condition.ConditionType;
import dev.silencebound.maxtech.storage.controller.ClusterStats;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.card.CardContent;
import dev.silencebound.maxtech.storage.controller.card.CardKind;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.creative.CreativeEnergyContent;
import dev.silencebound.maxtech.storage.energy.EnergyContent;
import dev.silencebound.maxtech.storage.crafting.assembler.AssemblerContent;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.job.CraftLimits;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlockEntity;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;
import dev.silencebound.maxtech.storage.crafting.stock.StockKeeperBlockEntity;
import dev.silencebound.maxtech.storage.crafting.stock.StockKeeperContent;
import dev.silencebound.maxtech.storage.crafting.stock.StockRule;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternTerminalContent;
import dev.silencebound.maxtech.storage.display.DisplayContent;
import dev.silencebound.maxtech.storage.display.DisplayMode;
import dev.silencebound.maxtech.storage.display.DisplayPanelBlockEntity;
import dev.silencebound.maxtech.storage.display.DisplaySettings;
import dev.silencebound.maxtech.storage.display.DisplaySourceKind;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.drive.DriveContent;
import dev.silencebound.maxtech.storage.hub.HubContent;
import dev.silencebound.maxtech.storage.iface.InterfaceBlockEntity;
import dev.silencebound.maxtech.storage.iface.InterfaceContent;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.controller.LogicContent;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.machine.MachineContent;
import dev.silencebound.maxtech.storage.machine.ModuleContent;
import dev.silencebound.maxtech.storage.machine.StorageMachineBlockEntity;
import dev.silencebound.maxtech.storage.material.MaterialContent;
import dev.silencebound.maxtech.storage.material.VoidCrystalContent;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.NetworkContent;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import dev.silencebound.maxtech.storage.p2p.P2PBlockEntity;
import dev.silencebound.maxtech.storage.p2p.P2PContent;
import dev.silencebound.maxtech.storage.p2p.P2PKind;
import dev.silencebound.maxtech.storage.p2p.P2PRole;
import dev.silencebound.maxtech.storage.p2p.P2PSettings;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.part.PartType;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortMode;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import dev.silencebound.maxtech.storage.research.ResearchTree;
import dev.silencebound.maxtech.storage.sensor.SensorContent;
import dev.silencebound.maxtech.storage.signal.AntennaLinking;
import dev.silencebound.maxtech.storage.signal.SignalContent;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalItem;
import dev.silencebound.maxtech.storage.storagechest.StorageChestContent;
import dev.silencebound.maxtech.storage.subnet.SubnetBlockEntity;
import dev.silencebound.maxtech.storage.subnet.SubnetContent;
import dev.silencebound.maxtech.storage.subnet.SubnetMode;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import dev.silencebound.maxtech.storage.terminal.crafting.CraftingTerminalContent;
import dev.silencebound.maxtech.storage.tool.belt.BeltContent;
import dev.silencebound.maxtech.storage.tool.blueprint.BlueprintContent;
import dev.silencebound.maxtech.storage.tool.cartridge.CartridgeContent;
import dev.silencebound.maxtech.storage.tool.goggles.GogglesContent;
import dev.silencebound.maxtech.storage.tool.goggles.NetworkGogglesItem;
import dev.silencebound.maxtech.storage.tool.layer.CableLayerItem;
import dev.silencebound.maxtech.storage.tool.layer.LayerContent;
import dev.silencebound.maxtech.storage.tool.probe.ProbeContent;
import dev.silencebound.maxtech.storage.tool.quarantine.QuarantineContent;
import dev.silencebound.maxtech.storage.tool.tablet.CraftingTabletContent;
import dev.silencebound.maxtech.storage.tool.tablet.CraftingTabletItem;
import dev.silencebound.maxtech.storage.tool.tracepen.TracePenContent;
import dev.silencebound.maxtech.storage.tool.uplink.UplinkContent;
import dev.silencebound.maxtech.storage.tool.usage.UsageContent;
import dev.silencebound.maxtech.storage.tool.wrench.WrenchContent;
import dev.silencebound.maxtech.storage.tool.zone.ZonePainterContent;
import dev.silencebound.maxtech.storage.world.WorldContent;
import dev.silencebound.maxtech.storage.world.WorldDeviceBlockEntity;
import dev.silencebound.maxtech.storage.dev.ShowcaseBridges.Foreign;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
// PLATFORM-LEAK: transfer types to stock networks and machines; allowed in common (ADR-0002).
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * Builds the dev showcase: one fenced 9 x 9 pad per {@link ShowcaseStation} on a floor it lays itself, each wired,
 * powered by a creative energy source and stocked. Dev tooling only; the NeoForge glue registers the command outside
 * production. Everything is placed with plain {@code setBlock} calls and configured through the same block-entity
 * methods the GameTests use, so a station works without a player touching it.
 */
public final class Showcase {
    /** Pad size along x and z. */
    public static final int PAD = 9;
    /** Distance between the pads of a full row. */
    public static final int STEP = PAD + 2;
    /** Air cleared above the floor. */
    private static final int HEIGHT = 6;
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    /** The areas of every build since the server started, per level, so {@code clear} finds them all. */
    private static final Map<ServerLevel, List<AABB>> BUILDS = Collections.synchronizedMap(new WeakHashMap<>());

    /** Ticks a queued stock waits for its network before it is dropped. */
    private static final int STOCK_PATIENCE = 200;
    /** Ticks a queued task (a foreign network booting, a controller placed later) may take. */
    private static final int TASK_PATIENCE = 600;
    private static final List<Stock> PENDING = new ArrayList<>();
    private static final List<Graphs> PENDING_GRAPHS = new ArrayList<>();
    private static final List<Pending> PENDING_TASKS = new ArrayList<>();

    private Showcase() {
    }

    /** Stock waiting for its network to form; networks are built on the level tick, not when a block is placed. */
    private static final class Stock {
        final ServerLevel level;
        final BlockPos node;
        final StackKey key;
        final long amount;
        int age;

        Stock(ServerLevel level, BlockPos node, StackKey key, long amount) {
            this.level = level;
            this.node = node;
            this.key = key;
            this.amount = amount;
        }
    }

    /**
     * Graphs for a Logic Controller, saved once its network has formed and its stock is in, so a graph that acts on its
     * first look (a craft when sticks are short) sees the stock.
     */
    private static final class Graphs {
        final ServerLevel level;
        final BlockPos controller;
        final List<String> names;
        final List<LogicGraph<LogicKey>> graphs;
        int age;

        Graphs(ServerLevel level, BlockPos controller, List<String> names, List<LogicGraph<LogicKey>> graphs) {
            this.level = level;
            this.controller = controller;
            this.names = names;
            this.graphs = graphs;
        }
    }

    /** Work at a spot of a station that waits for later ticks; dropped when its spot is cleared. */
    private static final class Pending {
        final ServerLevel level;
        final BlockPos pos;
        final ShowcaseBridges.Task task;
        int age;

        Pending(ServerLevel level, BlockPos pos, ShowcaseBridges.Task task) {
            this.level = level;
            this.pos = pos.immutable();
            this.task = task;
        }
    }

    /** Whether stock, logic graphs or tasks are still waiting. */
    public static boolean stocking() {
        return !PENDING.isEmpty() || !PENDING_GRAPHS.isEmpty() || !PENDING_TASKS.isEmpty();
    }

    /**
     * Stores queued stock whose network has formed; called at the end of each server tick by the NeoForge glue. Cheap
     * when nothing is queued. Server thread only.
     */
    public static void tick() {
        ShowcaseBoards.tick();
        if (PENDING.isEmpty() && PENDING_GRAPHS.isEmpty() && PENDING_TASKS.isEmpty()) {
            return;
        }
        for (int i = PENDING_TASKS.size() - 1; i >= 0; i--) {
            Pending pending = PENDING_TASKS.get(i);
            if (pending.task.tick() || ++pending.age > TASK_PATIENCE) {
                PENDING_TASKS.remove(i);
            }
        }
        for (int i = PENDING.size() - 1; i >= 0; i--) {
            Stock stock = PENDING.get(i);
            NetworkState network = StorageNetworks.network(stock.level, StorageNetworks.ID, stock.node);
            if (network == null || !network.isPowered()) {
                if (++stock.age > STOCK_PATIENCE) {
                    PENDING.remove(i);
                }
                continue;
            }
            try (Transaction transaction = Transaction.openRoot()) {
                network.insert(stock.key, stock.amount, transaction);
                transaction.commit();
            }
            PENDING.remove(i);
        }
        for (int i = PENDING_GRAPHS.size() - 1; i >= 0; i--) {
            Graphs pending = PENDING_GRAPHS.get(i);
            if (!(pending.level.getBlockEntity(pending.controller) instanceof LogicControllerBlockEntity controller)
                    || ++pending.age > STOCK_PATIENCE) {
                PENDING_GRAPHS.remove(i);
                continue;
            }
            NetworkState network = controller.network();
            if (network == null || !network.isPowered() || stockWaiting(pending.level, network)) {
                continue;
            }
            for (int g = 0; g < pending.graphs.size(); g++) {
                controller.worker().setGraph(g, pending.names.get(g), pending.graphs.get(g));
            }
            PENDING_GRAPHS.remove(i);
        }
    }

    /**
     * Forgets the step walls' boards, the builds and queued tasks; called by the NeoForge glue when the server stops.
     */
    public static void forget() {
        ShowcaseBoards.forget();
        BUILDS.clear();
        PENDING_TASKS.clear();
        ShowcaseScenes.forget();
    }

    /** Runs {@code task} after each server tick until it is done or {@link #TASK_PATIENCE} ticks have passed. */
    static void later(ServerLevel level, BlockPos pos, ShowcaseBridges.Task task) {
        PENDING_TASKS.add(new Pending(level, pos, task));
    }

    /** Saves {@code graphs} into the Logic Controller at {@code controller} once its network has its stock. */
    static void graphsLater(ServerLevel level, BlockPos controller, List<String> names,
            List<LogicGraph<LogicKey>> graphs) {
        PENDING_GRAPHS.add(new Graphs(level, controller.immutable(), names, graphs));
    }

    /** Queues {@code amount} of {@code key} for the network at {@code node}, stored once it has formed. */
    static void stockLater(ServerLevel level, BlockPos node, StackKey key, long amount) {
        PENDING.add(new Stock(level, node.immutable(), key, amount));
    }

    /** Drops the queued tasks inside {@code bounds}; their blocks are about to be replaced. */
    static void dropTasks(ServerLevel level, AABB bounds) {
        PENDING_TASKS.removeIf(pending -> pending.level.equals(level) && bounds.contains(pending.pos.getCenter()));
    }

    /** Whether stock queued for {@code network} has not arrived yet. */
    static boolean stockPending(ServerLevel level, NetworkState network) {
        return stockWaiting(level, network);
    }

    /**
     * Fills {@code machine}'s energy buffer and inserts {@code inputs}, noting in {@code warnings} what it did not
     * take.
     */
    static void fillMachine(StorageMachineBlockEntity machine, List<String> warnings, ItemStack... inputs) {
        // The handler caps each insert; a generator accepts none and leaves at once.
        for (int i = 0; i < 1_000; i++) {
            try (Transaction transaction = Transaction.openRoot()) {
                int moved = machine.energyHandler().insert(Integer.MAX_VALUE, transaction);
                transaction.commit();
                if (moved == 0) {
                    break;
                }
            }
        }
        for (ItemStack input : inputs) {
            try (Transaction transaction = Transaction.openRoot()) {
                int moved = ResourceHandlerUtil.insertStacking(machine.items(), ItemResource.of(input),
                        input.getCount(), transaction);
                transaction.commit();
                if (moved < input.getCount()) {
                    warnings.add(machine.getBlockState().getBlock().getName().getString() + " took " + moved + " of "
                            + input);
                }
            }
        }
    }

    private static boolean stockWaiting(ServerLevel level, NetworkState network) {
        for (Stock stock : PENDING) {
            if (stock.level.equals(level)
                    && StorageNetworks.network(level, StorageNetworks.ID, stock.node) == network) {
                return true;
            }
        }
        return false;
    }

    /** Sign lines of the bridges station for a mod that is not loaded. */
    static final String BRIDGE_MISSING = "showcase." + MaxtechStorage.MOD_ID + ".bridges.missing";
    static final String BRIDGE_RUN_WITH = "showcase." + MaxtechStorage.MOD_ID + ".bridges.run_with";

    /** Lang lines for the language provider: sign texts, summaries and command messages. */
    public static Map<String, String> translations() {
        Map<String, String> out = new LinkedHashMap<>();
        for (ShowcaseStation station : ShowcaseStation.values()) {
            out.put(station.nameKey(), station.english());
            for (int line = 1; line <= 3; line++) {
                out.put(station.hintKey(line), station.hints().get(line - 1));
            }
            out.put(station.summaryKey(), station.summary());
        }
        out.put(BRIDGE_MISSING, "is not loaded.");
        out.put(BRIDGE_RUN_WITH, "Run the game with");
        out.put(Messages.BUILT, "Built %s showcase station(s) from %s; all research done for your team.");
        out.put(Messages.STATION, "%s: %s");
        out.put(Messages.LIST, "Showcase stations (click one to build it here):");
        out.put(Messages.CLEARED, "Removed %s showcase build(s).");
        out.put(Messages.NOTHING, "No showcase build to remove in this dimension.");
        out.put(Messages.WARNING, "Showcase: %s");
        out.put(Messages.TICK, "[tick]");
        out.put(Messages.UNTICK, "[untick]");
        out.put(Messages.NO_STEP, "Step %s of this wall is not ticked by hand, or the wall is gone.");
        out.put(Messages.STEPS_RESET, "Opened every step on %s step wall(s) again.");
        out.put(Messages.NO_STEPS, "No showcase step wall in this dimension.");
        out.put(Messages.SHOOT_BUSY, "A shoot run is already going.");
        out.put(Messages.SHOOT_SKIPPED, "Skipped %s: it needs AE2 and Refined Storage (-PwithAe2=true -PwithRs=true).");
        out.put(Messages.SHOOT_START, "Shooting %s station(s); leave the mouse and keys alone until it is done.");
        out.put(Messages.SHOOT_MISSING, "%s shot(s) were not saved; the log says why.");
        out.put(Messages.SHOOT_DONE, "Saved %s scene shot(s):");
        out.put(Messages.SHOOT_SCENES, "Shooting %s scene(s); leave the mouse and keys alone until it is done.");
        out.put(Messages.SCENE_PLACED, "Placed scene %s with its corner at %s, from %s.");
        out.put(Messages.SCENE_FROM_FILE, "its saved file");
        out.put(Messages.SCENE_FROM_DRAFT, "the code draft");
        out.put(Messages.SCENE_SAVED, "Saved scene %s to %s.");
        out.put(Messages.SCENE_SAVE_FAILED, "Could not save scene %s: %s");
        out.put(Messages.SCENE_NO_REPO,
                "No repository checkout above %s; scene files live in <repo>/docs/release/scenes of a dev run.");
        out.put(Messages.SCENE_NOT_PLACED,
                "Scene %s has not been placed since the server started; place it first or give its corner.");
        return out;
    }

    /** Lang keys of the command's messages. */
    public static final class Messages {
        public static final String BUILT = key("built");
        public static final String STATION = key("station");
        public static final String LIST = key("list");
        public static final String CLEARED = key("cleared");
        public static final String NOTHING = key("nothing");
        public static final String WARNING = key("warning");
        public static final String TICK = key("tick");
        public static final String UNTICK = key("untick");
        public static final String NO_STEP = key("no_step");
        public static final String STEPS_RESET = key("steps_reset");
        public static final String NO_STEPS = key("no_steps");
        public static final String SHOOT_BUSY = key("shoot_busy");
        public static final String SHOOT_SKIPPED = key("shoot_skipped");
        public static final String SHOOT_START = key("shoot_start");
        public static final String SHOOT_MISSING = key("shoot_missing");
        public static final String SHOOT_DONE = key("shoot_done");
        public static final String SHOOT_SCENES = key("shoot_scenes");
        public static final String SCENE_PLACED = key("scene_placed");
        public static final String SCENE_FROM_FILE = key("scene_from_file");
        public static final String SCENE_FROM_DRAFT = key("scene_from_draft");
        public static final String SCENE_SAVED = key("scene_saved");
        public static final String SCENE_SAVE_FAILED = key("scene_save_failed");
        public static final String SCENE_NO_REPO = key("scene_no_repo");
        public static final String SCENE_NOT_PLACED = key("scene_not_placed");

        private Messages() {
        }

        private static String key(String path) {
            return "commands." + MaxtechStorage.MOD_ID + ".showcase." + path;
        }
    }

    /**
     * What one build placed: the area, each station's pad corner and the controllers (or, for the machines station, the
     * creative source) the test checks, plus anything that did not set up.
     */
    public record Build(AABB bounds, Map<ShowcaseStation, BlockPos> pads, Map<ShowcaseStation, BlockPos> hubs,
            List<String> warnings) {
    }

    /**
     * Builds {@code stations} in a row along +x, the first pad's north-west corner at {@code origin} (the floor is one
     * below). Completes all research for {@code player}'s team and makes the controllers theirs. Server thread only.
     */
    public static Build build(ServerLevel level, BlockPos origin, List<ShowcaseStation> stations,
            @Nullable ServerPlayer player) {
        OwnerRecord owner = player == null ? OwnerRecord.UNOWNED : OwnerRecord.of(player);
        if (player != null) {
            completeResearch(player);
        }
        AABB bounds = new AABB(origin.getX(), origin.getY() - 1, origin.getZ(),
                origin.getX() + (stations.size() - 1) * STEP + PAD, origin.getY() + HEIGHT, origin.getZ() + PAD);
        // Boards and tasks of an older build here would write over the new stations.
        ShowcaseBoards.remove(level, bounds);
        PENDING_TASKS.removeIf(pending -> pending.level.equals(level) && bounds.contains(pending.pos.getCenter()));
        Map<ShowcaseStation, BlockPos> pads = new LinkedHashMap<>();
        Map<ShowcaseStation, BlockPos> hubs = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        for (int i = 0; i < stations.size(); i++) {
            ShowcaseStation station = stations.get(i);
            Pad pad = new Pad(level, origin.offset(i * STEP, 0, 0), owner, warnings);
            pad.prepare(station);
            BlockPos hub = switch (station) {
                case CORE -> core(pad);
                case PRIORITY -> priority(pad);
                case PORTS -> ports(pad);
                case CABLES -> cables(pad);
                case CHANNELS -> channels(pad);
                case SUBNET -> subnet(pad);
                case BRIDGES -> bridges(pad);
                case MACHINES -> machines(pad);
                case ENERGY -> energy(pad);
                case CRAFTING -> crafting(pad);
                case TOOLS -> tools(pad);
                case LOGIC -> logic(pad);
            };
            pads.put(station, pad.origin);
            hubs.put(station, hub);
            if (!ShowcaseBoards.start(level, station, pad.origin, hub)) {
                warnings.add("no step wall for " + station.id());
            }
        }
        List<AABB> builds = BUILDS.computeIfAbsent(level, key -> new ArrayList<>());
        builds.removeIf(bounds::equals);
        builds.add(bounds);
        return new Build(bounds, pads, hubs, warnings);
    }

    /**
     * Removes the blocks of every build in {@code level} since the server started (rows and single stations alike)
     * above their floors, without drops. Returns how many builds there were.
     */
    public static int clear(ServerLevel level) {
        List<AABB> builds = BUILDS.remove(level);
        if (builds == null) {
            return 0;
        }
        for (AABB bounds : builds) {
            clear(level, bounds);
        }
        return builds.size();
    }

    /**
     * Removes the blocks inside {@code bounds} above its bottom layer (the floor), without drops, and forgets a build
     * with exactly these bounds and the queued tasks inside them.
     */
    public static void clear(ServerLevel level, AABB bounds) {
        List<AABB> builds = BUILDS.get(level);
        if (builds != null) {
            builds.removeIf(bounds::equals);
        }
        PENDING_TASKS.removeIf(pending -> pending.level.equals(level) && bounds.contains(pending.pos.getCenter()));
        ShowcaseBoards.remove(level, bounds);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // Twice: parts and cables first notice their neighbours leaving, then whatever they left behind goes.
        for (int pass = 0; pass < 2; pass++) {
            for (int x = (int) bounds.minX; x < (int) bounds.maxX; x++) {
                for (int z = (int) bounds.minZ; z < (int) bounds.maxZ; z++) {
                    for (int y = (int) bounds.minY + 1; y < (int) bounds.maxY; y++) {
                        pos.set(x, y, z);
                        if (level.getBlockEntity(pos) instanceof Container container) {
                            container.clearContent();
                        }
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL
                                | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS);
                    }
                }
            }
        }
        // Devices that spill their contents on removal (drives, parts) and the placer's work.
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, bounds.inflate(1))) {
            item.discard();
        }
    }

    /** The cards the core station's controller gets (M7R item 2): two amplifiers, an efficiency core, a regulator. */
    public static final List<CardKind> CORE_CARDS = List.of(CardKind.BATCH_AMPLIFIER, CardKind.BATCH_AMPLIFIER,
            CardKind.EFFICIENCY_CORE, CardKind.SURGE_REGULATOR);

    /**
     * Puts {@link #CORE_CARDS} into the slots of the controller at {@code controller} once its fill has opened them (a
     * block tick after placing). Cards no slot takes are not made.
     */
    private static void installCards(ServerLevel level, BlockPos controller) {
        PENDING_TASKS.add(new Pending(level, controller, () -> {
            if (!(level.getBlockEntity(controller) instanceof ControllerBlockEntity block)) {
                // Cleared or rebuilt meanwhile.
                return true;
            }
            ControllerBlockEntity primary = block.primary();
            if (primary == null || primary.blockCount() < 2 || primary.cards().usable() < CORE_CARDS.size()) {
                return false;
            }
            ClusterStats.Cards limits = ClusterStats.Cards.fromConfig();
            for (CardKind kind : CORE_CARDS) {
                primary.cards().insert(new ItemStack(CardContent.item(kind)), limits);
            }
            return true;
        }));
    }

    /** Completes every research node for the player's team; a node waits until its prerequisites are done. */
    static void completeResearch(ServerPlayer player) {
        var server = player.level().getServer();
        ResearchTeam team = Research.team(player, true);
        boolean progress = true;
        while (progress) {
            progress = false;
            for (ResearchTree.Entry entry : Research.tree(server).entries()) {
                if (Research.isComplete(team, entry)) {
                    continue;
                }
                if (team.tokens() <= 0) {
                    Research.addToken(server, team);
                }
                if (Research.useToken(server, team, entry.id()) == Research.Result.DONE) {
                    progress = true;
                }
            }
        }
    }

    private static BlockPos core(Pad pad) {
        BlockPos controller = pad.hub(1, 5, 1, 3, 1, 4, 7);
        // The multiblock controller (M7R): a second block on top, and upgrade cards once its fill opened the slots.
        pad.owned(pad.place(1, 1, 5, ControllerContent.CONTROLLER.get(), null));
        installCards(pad.level, controller);
        // A network device on the spine, away from the controller (M7R item 4).
        pad.device(2, 1, 4, ExpansionContent.SECURITY_MODULE.get());
        // The capacitor bank folded into the energy cells (M7R item 5).
        pad.place(1, 0, 6, EnergyContent.TIER_1.get(), null);
        pad.drive(3, 5, cell(CellCasing.SMALL, CellContent.ITEM_TYPE.id()),
                cell(CellCasing.MEDIUM, CellContent.ITEM_TYPE.id()), cell(CellCasing.LARGE, CellContent.ITEM_TYPE.id()),
                cell(CellCasing.BULK, CellContent.ITEM_TYPE.id()));
        pad.drive(4, 5, cell(CellCasing.MEDIUM, CellContent.FLUID_TYPE.id()),
                cell(CellCasing.LARGE, CellContent.ITEM_TYPE.id()));
        pad.place(5, 0, 5, TerminalContent.TERMINAL.get(), Direction.NORTH);
        pad.place(6, 0, 5, PatternTerminalContent.TERMINAL.get(), Direction.NORTH);
        pad.place(7, 0, 5, CraftingTerminalContent.TERMINAL.get(), Direction.NORTH);
        pad.place(5, 0, 3, WorldContent.NETWORK_CHEST.get(), Direction.NORTH);
        pad.place(3, 0, 2, StorageChestContent.STORAGE_CHEST.get(), Direction.NORTH);
        pad.stock(controller, COBBLE, 2_000);
        pad.stock(controller, ItemKey.of(Items.IRON_INGOT), 256);
        pad.stock(controller, ItemKey.of(Items.OAK_PLANKS), 512);
        pad.stock(controller, ItemKey.of(Items.REDSTONE), 300);
        pad.stock(controller, ItemKey.of(Items.DIAMOND), 40);
        pad.stock(controller, FluidKey.of(Fluids.WATER), 16_000);
        pad.stock(controller, FluidKey.of(Fluids.LAVA), 8_000);
        return controller;
    }

    private static BlockPos ports(Pad pad) {
        BlockPos controller = pad.hub(1, 5, 1, 3, 1, 4, 7);
        pad.drive(1, 6, cell(CellCasing.LARGE, CellContent.ITEM_TYPE.id()));
        pad.stock(controller, COBBLE, 500);
        // Block port importing from the chest behind it.
        PortBlockEntity port = pad.entity(pad.place(2, 0, 5, PortContent.PORT.get(), Direction.SOUTH),
                PortBlockEntity.class);
        if (port != null) {
            port.setMode(PortMode.IMPORT);
        }
        pad.chest(2, 0, 6, new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.ANDESITE, 64));
        // Port part on the cable, importing from the chest in front.
        pad.part(3, 0, 4, Direction.NORTH, PortContent.PORT_PART, new ItemStack(PortContent.PORT_PART_ITEM.get()));
        pad.chest(3, 0, 3, new ItemStack(Items.IRON_INGOT, 32));
        InterfaceBlockEntity iface = pad.entity(pad.place(4, 0, 5, InterfaceContent.INTERFACE.get(), Direction.NORTH),
                InterfaceBlockEntity.class);
        if (iface != null) {
            iface.worker().config().setItem(0, new ItemStack(Items.COBBLESTONE, 16));
        }
        pad.chest(4, 0, 6);
        // Item P2P: a hopper under a chest feeds the input through its front (the only side that takes items), the
        // output fills the chest in front of it.
        P2PBlockEntity in = pad.entity(pad.place(5, 0, 3, P2PContent.block(P2PKind.ITEM).get(), Direction.NORTH),
                P2PBlockEntity.class);
        P2PBlockEntity out = pad.entity(pad.place(6, 0, 3, P2PContent.block(P2PKind.ITEM).get(), Direction.NORTH),
                P2PBlockEntity.class);
        if (in != null && out != null) {
            in.endpoint().link(new P2PSettings(P2PRole.INPUT, 4242));
            out.endpoint().link(new P2PSettings(P2PRole.OUTPUT, 4242));
        }
        pad.set(5, 0, 2,
                Blocks.HOPPER.defaultBlockState().setValue(BlockStateProperties.FACING_HOPPER, Direction.SOUTH));
        pad.chest(5, 1, 2, new ItemStack(Items.OAK_LOG, 64));
        pad.chest(6, 0, 2);
        ConditionSwitchBlockEntity condition = pad.entity(pad.place(7, 0, 3, ConditionContent.SWITCH.get(), null),
                ConditionSwitchBlockEntity.class);
        if (condition != null) {
            condition.worker().setSettings(ConditionSettings.DEFAULT
                    .withItem(Optional.of(ItemKey.of(Items.COBBLESTONE))).withAbove(true).withThreshold(64));
        }
        pad.set(7, 0, 2, Blocks.REDSTONE_LAMP.defaultBlockState());
        for (int x = 5; x <= 6; x++) {
            for (int y = 0; y <= 1; y++) {
                pad.place(x, y, 5, DisplayContent.PANEL.get(), Direction.NORTH);
            }
        }
        DisplayPanelBlockEntity panel = pad.entity(pad.pos(5, 0, 5), DisplayPanelBlockEntity.class);
        if (panel != null) {
            panel.applyToWall(DisplaySettings.DEFAULT.withMode(DisplayMode.LINE)
                    .withSource(DisplaySourceKind.ITEM_COUNT).withItem(COBBLE).withWindow(60));
        }
        // Placer and breaker in continuous mode (1): in pulse mode (0) they wait for a redstone edge nothing here
        // gives.
        WorldDeviceBlockEntity placer = pad.entity(pad.place(2, 1, 4, WorldContent.PLACER.get(), Direction.NORTH),
                WorldDeviceBlockEntity.class);
        if (placer != null) {
            placer.worker().filter().setItem(0, new ItemStack(Items.COBBLESTONE));
            placer.worker().setMode(1);
        }
        WorldDeviceBlockEntity breaker = pad.entity(pad.place(3, 1, 4, WorldContent.BREAKER.get(), Direction.NORTH),
                WorldDeviceBlockEntity.class);
        if (breaker != null) {
            breaker.worker().setMode(1);
        }
        pad.set(3, 1, 3, Blocks.STONE.defaultBlockState());
        pad.place(4, 1, 4, WorldContent.COLLECTOR.get(), Direction.NORTH);
        return controller;
    }

    private static BlockPos cables(Pad pad) {
        BlockPos controller = pad.place(1, 0, 4, ControllerContent.CONTROLLER.get(), null);
        pad.owned(controller);
        pad.place(1, 0, 3, CreativeEnergyContent.CREATIVE_ENERGY_SOURCE.get(), null);
        for (int z = 2; z <= 6; z++) {
            pad.cable(2, 0, z, CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
        }
        // One row per listed look; the retired glass look draws like plain.
        int row = 0;
        for (CableLook look : CableLook.values()) {
            if (!look.listed()) {
                continue;
            }
            for (int tier = 1; tier <= 4; tier++) {
                pad.cable(2 + tier, 0, 2 + row, CableContent.cable(look, tier).type().get(), null);
            }
            row++;
        }
        pad.cable(7, 0, 2, CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
        pad.facade(7, 0, 2, Blocks.STONE_BRICKS.defaultBlockState());
        pad.cable(7, 0, 3, CableContent.STORAGE_CABLE_TIER_1.type().get(), DyeColor.RED);
        return controller;
    }

    private static BlockPos subnet(Pad pad) {
        BlockPos controller = pad.hub(1, 5, 1, 3, 1, 4, 4);
        pad.device(2, 1, 4, ExpansionContent.WIRELESS_ACCESS_MOUNT.get());
        pad.device(3, 1, 4, ExpansionContent.ANTENNA_MOUNT.get());
        pad.drive(2, 5, cell(CellCasing.MEDIUM, CellContent.ITEM_TYPE.id()));
        pad.place(3, 0, 5, TerminalContent.TERMINAL.get(), Direction.NORTH);
        pad.stock(controller, COBBLE, 300);
        SubnetBlockEntity subnet = pad.entity(pad.place(5, 0, 4, SubnetContent.SUBNET.get(), Direction.WEST),
                SubnetBlockEntity.class);
        if (subnet != null) {
            subnet.setMode(SubnetMode.MERGED);
            subnet.setOwner(pad.owner);
        }
        pad.cable(6, 0, 4, CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
        pad.cable(7, 0, 4, CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
        pad.drive(6, 5, cell(CellCasing.SMALL, CellContent.ITEM_TYPE.id()));
        pad.place(7, 0, 5, TerminalContent.TERMINAL.get(), Direction.NORTH);
        BlockPos near = pad.place(2, 0, 3, SignalContent.ANTENNA_TIER_1.get(), null);
        BlockPos far = pad.place(7, 0, 7, SignalContent.ANTENNA_TIER_1.get(), null);
        pad.place(6, 0, 7, TerminalContent.TERMINAL.get(), Direction.NORTH);
        String refused = AntennaLinking.pair(pad.level, near, far);
        if (refused != null) {
            pad.warnings.add("antennas not paired: " + refused);
        }
        ItemStack terminal = new ItemStack(SignalContent.WIRELESS_TERMINAL.get());
        terminal.set(ModDataComponents.WIRELESS_LINK.get(), GlobalPos.of(pad.level.dimension(), controller));
        ItemEnergy.setInt(terminal, ModDataComponents.WIRELESS_ENERGY.get(), WirelessTerminalItem.capacity());
        pad.chest(3, 0, 2, terminal);
        return controller;
    }

    /**
     * MaxTech energy (MLE): a network with no creative source, run by a fuel generator on the spine, which like the
     * cells uses no channel. The pass fills the controller's buffer first; the energy cells take leftovers once it is
     * full.
     */
    private static BlockPos energy(Pad pad) {
        for (int x = 1; x <= 7; x++) {
            pad.cable(x, 0, 4, CableContent.STORAGE_CABLE_TIER_4.type().get(), null);
        }
        BlockPos controller = pad.place(1, 0, 5, ControllerContent.CONTROLLER.get(), null);
        pad.owned(controller);
        // North of the spine, touching only the cable: its MU reaches the buffers through the network's pass.
        pad.machine(3, 0, 3, MachineContent.FUEL_GENERATOR.get(), new ItemStack(Items.COAL, 64));
        pad.place(3, 0, 5, EnergyContent.TIER_1.get(), null);
        pad.place(4, 0, 5, EnergyContent.TIER_2.get(), null);
        pad.place(5, 0, 5, EnergyContent.TIER_3.get(), null);
        pad.place(6, 0, 5, TerminalContent.TERMINAL.get(), Direction.NORTH);
        pad.drive(7, 5, cell(CellCasing.MEDIUM, CellContent.ITEM_TYPE.id()));
        pad.chest(1, 0, 2, new ItemStack(ProbeContent.SIGNAL_PROBE.get()),
                new ItemStack(NetworkContent.NETWORK_ANALYZER.get()), new ItemStack(Items.COAL, 64));
        pad.stock(controller, COBBLE, 256);
        return controller;
    }

    private static BlockPos machines(Pad pad) {
        // Storage's machines take MU pushed into them, and the creative source only gives energy to whoever pulls:
        // the generator in the middle feeds the three around it, and each starts with a full buffer.
        BlockPos generator = pad.machine(4, 0, 4, MachineContent.FUEL_GENERATOR.get(), new ItemStack(Items.COAL, 64));
        pad.machine(3, 0, 4, MachineContent.LITHOGRAPH.get(), new ItemStack(MaterialContent.SILICON.get(), 16));
        pad.machine(5, 0, 4, MachineContent.FABRICATOR.get(), new ItemStack(Items.COPPER_INGOT, 16));
        pad.machine(4, 0, 5, MachineContent.ASSEMBLER.get(), new ItemStack(MaterialContent.LOGIC_DIE.get(), 4),
                new ItemStack(MaterialContent.INSULATED_COPPER_WIRE.get(), 8),
                new ItemStack(MaterialContent.OSCILLATOR.get(), 4),
                new ItemStack(MaterialContent.CIRCUIT_BOARD.get(), 4));
        pad.chest(2, 0, 2, new ItemStack(Items.COAL, 64), new ItemStack(MaterialContent.SILICON.get(), 64),
                new ItemStack(Items.COPPER_INGOT, 64));
        return generator;
    }

    private static BlockPos crafting(Pad pad) {
        BlockPos controller = pad.hub(1, 5, 1, 3, 1, 4, 7);
        pad.place(1, 1, 4, PatternTerminalContent.TERMINAL.get(), Direction.NORTH);
        pad.place(2, 1, 4, TerminalContent.TERMINAL.get(), Direction.NORTH);
        pad.drive(4, 5, cell(CellCasing.LARGE, CellContent.ITEM_TYPE.id()));
        pad.place(3, 0, 5, CpuContent.CORE_1.get(), null);
        pad.place(3, 0, 6, CpuContent.STORAGE.get(), null);
        pad.place(3, 1, 5, CpuContent.LANE.get(), null);
        pad.place(5, 0, 5, CpuContent.CORE_2.get(), null);
        pad.place(5, 0, 6, CpuContent.STORAGE.get(), null);
        pad.place(5, 1, 5, CpuContent.LANE.get(), null);
        pad.place(5, 0, 7, CpuContent.CACHE.get(), null);
        pad.place(7, 0, 5, CpuContent.CORE_3.get(), null);
        pad.place(7, 0, 6, CpuContent.STORAGE.get(), null);
        pad.place(7, 1, 5, CpuContent.LANE.get(), null);
        pad.provider(2, 3, PatternItem
                .encode(pattern(CraftingPattern.Kind.PROCESSING, Items.RAW_IRON, 1, Items.IRON_INGOT, 1, null)));
        pad.set(2, 0, 2,
                Blocks.FURNACE.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        if (pad.level.getBlockEntity(pad.pos(2, 0, 2)) instanceof Container furnace) {
            furnace.setItem(1, new ItemStack(Items.COAL, 64));
        }
        pad.provider(4, 3, PatternItem
                .encode(pattern(CraftingPattern.Kind.CRAFTING, Items.OAK_PLANKS, 2, Items.STICK, 4, "stick")));
        pad.place(4, 0, 2, AssemblerContent.ASSEMBLER.get(), null);
        StockKeeperBlockEntity keeper = pad.entity(pad.place(5, 0, 3, StockKeeperContent.KEEPER.get(), null),
                StockKeeperBlockEntity.class);
        if (keeper != null) {
            keeper.worker().setRule(0,
                    StockRule.EMPTY.withKey(Optional.of(ItemKey.of(Items.STICK))).withKeep(32).withBatch(16));
        }
        PortBlockEntity export = pad.entity(pad.place(7, 0, 3, PortContent.PORT.get(), Direction.NORTH),
                PortBlockEntity.class);
        if (export != null) {
            export.setMode(PortMode.EXPORT);
            // Not sticks: the port exports all it sees, so the stock keeper's sticks would leave as fast as they come
            // and both would craft until the planks ran out. The furnace is slow enough to keep the raw iron.
            export.filter().setItem(0, new ItemStack(Items.IRON_INGOT));
            export.worker().cards().setItem(0, new ItemStack(PortContent.CRAFTING_CARD.get()));
        }
        pad.chest(7, 0, 2);
        // The card asks for crafting.cardMaxRequest (64) ingots at once, and a job hands its ingots over only when it
        // ends: at ten seconds an ingot the chest would stay empty for over ten minutes. Automated jobs of 4 fill it
        // within a minute; the stock keeper's sticks come in jobs of 4 too.
        ControllerBlockEntity core = pad.entity(controller, ControllerBlockEntity.class);
        if (core != null) {
            core.setCraftLimits(new CraftLimits(4, CraftLimits.FOLLOW_CONFIG));
        }
        pad.stock(controller, ItemKey.of(Items.OAK_PLANKS), 256);
        pad.stock(controller, ItemKey.of(Items.RAW_IRON), 256);
        pad.stock(controller, ItemKey.of(Items.COAL), 64);
        return controller;
    }

    private static BlockPos tools(Pad pad) {
        BlockPos controller = pad.hub(1, 5, 1, 3, 1, 4, 5);
        pad.device(2, 1, 4, ExpansionContent.WIRELESS_ACCESS_MOUNT.get());
        pad.drive(2, 5, cell(CellCasing.LARGE, CellContent.ITEM_TYPE.id()));
        pad.place(3, 0, 5, BulkContent.BULK_DRIVE.get(), Direction.NORTH);
        pad.place(4, 0, 5, TerminalContent.TERMINAL.get(), Direction.NORTH);
        pad.stock(controller, COBBLE, 1_000);
        GlobalPos link = GlobalPos.of(pad.level.dimension(), controller);
        ItemStack uplink = bound(UplinkContent.POCKET_UPLINK.get(), link);
        ItemStack belt = bound(BeltContent.RESTOCK_BELT.get(), link);
        ItemStack layer = bound(LayerContent.CABLE_LAYER.get(), link);
        ItemEnergy.setInt(layer, LayerContent.ENERGY.get(), CableLayerItem.capacity());
        ItemStack tablet = bound(CraftingTabletContent.TABLET.get(), link);
        ItemEnergy.setInt(tablet, ModDataComponents.WIRELESS_ENERGY.get(), CraftingTabletItem.capacity());
        ItemStack terminal = bound(SignalContent.WIRELESS_TERMINAL.get(), link);
        ItemEnergy.setInt(terminal, ModDataComponents.WIRELESS_ENERGY.get(), WirelessTerminalItem.capacity());
        ItemStack goggles = new ItemStack(GogglesContent.NETWORK_GOGGLES.get());
        ItemEnergy.setInt(goggles, ModDataComponents.GOGGLES_ENERGY.get(), NetworkGogglesItem.capacity());
        pad.chest(3, 0, 2, new ItemStack(WrenchContent.WRENCH.get()), new ItemStack(BlueprintContent.CARD.get()),
                goggles, new ItemStack(TracePenContent.TRACE_PEN.get()),
                new ItemStack(ZonePainterContent.ZONE_PAINTER.get()), uplink, belt,
                new ItemStack(ProbeContent.SIGNAL_PROBE.get()), new ItemStack(CartridgeContent.PUZZLE_CARTRIDGE.get()),
                new ItemStack(UsageContent.USAGE_REPORT_BOOK.get()),
                new ItemStack(QuarantineContent.QUARANTINE_TAG.get(), 8), layer,
                new ItemStack(CableContent.STORAGE_CABLE_TIER_1.item().get(), 64), tablet, terminal,
                new ItemStack(NetworkContent.NETWORK_ANALYZER.get()));
        pad.chest(5, 0, 2, new ItemStack(BulkContent.BULK_DRIVE.get(), 2),
                cell(CellCasing.BULK, CellContent.ITEM_TYPE.id()),
                new ItemStack(VoidCrystalContent.VOID_CRYSTAL.get(), 32),
                new ItemStack(VoidCrystalContent.VOID_LATTICE.get(), 16),
                new ItemStack(ModuleContent.VOID_MODULE.get()),
                new ItemStack(CellContent.VOID_OVERFLOW_ADDON.get(), 2));
        return controller;
    }

    /**
     * The logic station. Spine along z 4; south of it the controller, the Logic Controller (lever on top), the drive
     * "bank", a terminal and a CPU; north of it the ports "outbox" (button on top) and "intake", the sensor "chest
     * sensor" on a chest, a 2 x 2 display wall on logic notices and a pattern provider for sticks; above it the
     * condition switches "lamp" and "latch lamp" with their lamps, and a lever hanging over the sensor part "lever
     * sensor". The graphs are saved once the network has stock (see {@link Graphs}).
     */
    private static BlockPos logic(Pad pad) {
        BlockPos controller = pad.hub(1, 5, 1, 3, 1, 4, 7);
        LogicControllerBlockEntity logic = pad.entity(
                pad.place(2, 0, 5, LogicContent.CONTROLLER.get(), Direction.NORTH), LogicControllerBlockEntity.class);
        if (logic != null) {
            logic.setOwner(pad.owner);
        }
        pad.attached(2, 1, 5, Blocks.LEVER, AttachFace.FLOOR);
        DriveBlockEntity bank = pad.drive(3, 5, cell(CellCasing.LARGE, CellContent.ITEM_TYPE.id()));
        if (bank != null) {
            bank.setLabel(DeviceLabel.of("bank", DyeColor.PURPLE));
        }
        pad.place(4, 0, 5, TerminalContent.TERMINAL.get(), Direction.NORTH);
        pad.place(7, 0, 5, CpuContent.CORE_1.get(), null);
        pad.place(7, 0, 6, CpuContent.STORAGE.get(), null);
        pad.place(7, 1, 5, CpuContent.LANE.get(), null);
        // An export port with an empty filter moves nothing on its own: only the move graph uses it.
        PortBlockEntity outbox = pad.entity(pad.place(2, 0, 3, PortContent.PORT.get(), Direction.NORTH),
                PortBlockEntity.class);
        if (outbox != null) {
            outbox.setMode(PortMode.EXPORT);
            outbox.setLabel(DeviceLabel.of("outbox", DyeColor.ORANGE));
        }
        pad.chest(2, 0, 2);
        pad.attached(2, 1, 3, Blocks.STONE_BUTTON, AttachFace.FLOOR);
        PortBlockEntity intake = pad.entity(pad.place(3, 0, 3, PortContent.PORT.get(), Direction.NORTH),
                PortBlockEntity.class);
        if (intake != null) {
            intake.setMode(PortMode.IMPORT);
            intake.setLabel(DeviceLabel.of("intake", DyeColor.LIME));
            // Off from the start, as its graph keeps it while the lever is off, so the chest stays full until then.
            intake.setSwitchedOff(true);
        }
        pad.chest(3, 0, 2, new ItemStack(Items.COBBLESTONE, 64));
        BlockEntity sensor = pad.level.getBlockEntity(pad.place(4, 0, 3, SensorContent.SENSOR.get(), Direction.NORTH));
        if (sensor instanceof NetworkDevice device) {
            device.setLabel(DeviceLabel.of("chest sensor", DyeColor.LIGHT_BLUE));
        } else {
            pad.warnings.add("no sensor at " + pad.pos(4, 0, 3).toShortString());
        }
        pad.chest(4, 0, 2, new ItemStack(Items.COBBLESTONE, 20));
        for (int x = 5; x <= 6; x++) {
            for (int y = 0; y <= 1; y++) {
                pad.place(x, y, 3, DisplayContent.PANEL.get(), Direction.NORTH);
            }
        }
        DisplayPanelBlockEntity panel = pad.entity(pad.pos(5, 0, 3), DisplayPanelBlockEntity.class);
        if (panel != null) {
            panel.applyToWall(DisplaySettings.DEFAULT.withSource(DisplaySourceKind.LOGIC_NOTICES));
        }
        pad.provider(7, 3, PatternItem
                .encode(pattern(CraftingPattern.Kind.CRAFTING, Items.OAK_PLANKS, 2, Items.STICK, 4, "stick")));
        pad.place(7, 0, 2, AssemblerContent.ASSEMBLER.get(), null);
        pad.logicSwitch(5, 1, 4, "lamp", DyeColor.YELLOW);
        pad.logicSwitch(6, 1, 4, "latch lamp", DyeColor.RED);
        // A lever hanging from a block over the cable; the sensor part on the cable's top face reads it.
        pad.set(1, 2, 4, Blocks.POLISHED_ANDESITE.defaultBlockState());
        pad.attached(1, 1, 4, Blocks.LEVER, AttachFace.CEILING);
        pad.part(1, 0, 4, Direction.UP, SensorContent.SENSOR_PART, new ItemStack(SensorContent.SENSOR_PART_ITEM.get()));
        CableParts parts = StorageCableBlock.parts(pad.level, pad.pos(1, 0, 4));
        CablePart part = parts == null ? null : parts.part(Direction.UP);
        if (part != null) {
            part.setLabel(DeviceLabel.of("lever sensor", DyeColor.CYAN));
        }
        pad.stock(controller, COBBLE, 50);
        pad.stock(controller, ItemKey.of(Items.OAK_PLANKS), 256);
        if (logic != null) {
            PENDING_GRAPHS.add(new Graphs(pad.level, logic.getBlockPos(), LogicExamples.NAMES, LogicExamples.graphs()));
        }
        return controller;
    }

    /**
     * The priority station. Spine along z 4; south of it the controller, a drive at the highest priority (x 3), a bulk
     * drive at 0 (x 4), a drive at -100 (x 5) and a terminal; north of it an import port fed by a chest with a hopper
     * on top.
     */
    private static BlockPos priority(Pad pad) {
        BlockPos controller = pad.hub(1, 5, 1, 3, 1, 4, 7);
        DriveBlockEntity first = pad.drive(3, 5, cell(CellCasing.LARGE, CellContent.ITEM_TYPE.id()));
        if (first != null) {
            first.setPriority(Integer.MAX_VALUE);
            first.setLabel(DeviceLabel.of("priority max", DyeColor.LIME));
        }
        BulkDriveBlockEntity bulk = pad.entity(pad.place(4, 0, 5, BulkContent.BULK_DRIVE.get(), Direction.NORTH),
                BulkDriveBlockEntity.class);
        if (bulk != null) {
            bulk.setPriority(0);
            bulk.setLabel(DeviceLabel.of("priority 0", DyeColor.YELLOW));
        }
        DriveBlockEntity last = pad.drive(5, 5, cell(CellCasing.LARGE, CellContent.ITEM_TYPE.id()));
        if (last != null) {
            last.setPriority(-100);
            last.setLabel(DeviceLabel.of("priority -100", DyeColor.RED));
        }
        pad.place(6, 0, 5, TerminalContent.TERMINAL.get(), Direction.NORTH);
        PortBlockEntity port = pad.entity(pad.place(3, 0, 3, PortContent.PORT.get(), Direction.NORTH),
                PortBlockEntity.class);
        if (port != null) {
            port.setMode(PortMode.IMPORT);
        }
        pad.chest(3, 0, 2, new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.ANDESITE, 64));
        pad.set(3, 1, 2,
                Blocks.HOPPER.defaultBlockState().setValue(BlockStateProperties.FACING_HOPPER, Direction.DOWN));
        return controller;
    }

    /**
     * The channels station, three rows east of a tier-4 spine along x 2 (z 3 to 5) with the controller at its west:
     * <ul>
     * <li>z 3, the full cable: a tier-1 cable from x 3 to 7 with two terminals on each block, ten in all, so the two
     * that joined last (the highest positions) are offline;</li>
     * <li>z 5, the hubs: a tier-3, a tier-2 and a tier-1 hub at x 3, 5 and 7 joined by tier-4 cable, so each hub sits
     * behind the one before it, with three terminals stacked on each hub;</li>
     * <li>z 7, a network without a controller: a creative source (no channel) and a tier-2 cable with ten terminals for
     * its 8 channels.</li>
     * </ul>
     * A second controller at (2, 2) touches the spine; it is placed a tick after the first, so the first stays in
     * charge and it goes on standby.
     */
    private static BlockPos channels(Pad pad) {
        for (int z = 3; z <= 5; z++) {
            pad.cable(2, 0, z, CableContent.STORAGE_CABLE_TIER_4.type().get(), null);
        }
        BlockPos controller = pad.place(1, 0, 4, ControllerContent.CONTROLLER.get(), null);
        pad.owned(controller);
        pad.place(1, 0, 5, CreativeEnergyContent.CREATIVE_ENERGY_SOURCE.get(), null);
        for (int x = 3; x <= 7; x++) {
            pad.cable(x, 0, 3, CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
            for (int y = 1; y <= 2; y++) {
                pad.place(x, y, 3, TerminalContent.TERMINAL.get(), Direction.NORTH);
            }
        }
        pad.place(3, 0, 5, HubContent.TIER_3.get(), null);
        pad.cable(4, 0, 5, CableContent.STORAGE_CABLE_TIER_4.type().get(), null);
        pad.place(5, 0, 5, HubContent.TIER_2.get(), null);
        pad.cable(6, 0, 5, CableContent.STORAGE_CABLE_TIER_4.type().get(), null);
        pad.place(7, 0, 5, HubContent.TIER_1.get(), null);
        for (int x = 3; x <= 7; x += 2) {
            for (int y = 1; y <= 3; y++) {
                pad.place(x, y, 5, TerminalContent.TERMINAL.get(), Direction.NORTH);
            }
        }
        pad.place(2, 0, 7, CreativeEnergyContent.CREATIVE_ENERGY_SOURCE.get(), null);
        for (int x = 3; x <= 7; x++) {
            pad.cable(x, 0, 7, CableContent.STORAGE_CABLE_TIER_2.type().get(), null);
            for (int y = 1; y <= 2; y++) {
                pad.place(x, y, 7, TerminalContent.TERMINAL.get(), Direction.NORTH);
            }
        }
        // Controllers placed in the same tick tie on their placing time; a tick later this one is the newer.
        BlockPos standby = pad.pos(2, 0, 2);
        ServerLevel level = pad.level;
        OwnerRecord owner = pad.owner;
        PENDING_TASKS.add(new Pending(level, standby, () -> {
            if (!level.getBlockState(controller).is(ControllerContent.CONTROLLER.get())) {
                // Cleared or rebuilt meanwhile.
                return true;
            }
            level.setBlockAndUpdate(standby, ControllerContent.CONTROLLER.get().defaultBlockState());
            if (level.getBlockEntity(standby) instanceof ControllerBlockEntity entity) {
                entity.setOwner(owner);
            }
            return true;
        }));
        return controller;
    }

    /**
     * The bridges station: the Storage network along a spine at z 4 (controller, drive, terminal) and, south of it, a
     * bridge spot for each foreign mod facing south (AE2 at x 3, Refined Storage at x 6). A mod's compat builds its
     * network in the three by two blocks in front ({@link ShowcaseBridges.Site}) and places the bridge once that
     * network is up and stocked; a mod that is not loaded gets a sign there instead.
     */
    private static BlockPos bridges(Pad pad) {
        BlockPos controller = pad.hub(1, 5, 1, 3, 1, 4, 7);
        pad.drive(2, 5, cell(CellCasing.LARGE, CellContent.ITEM_TYPE.id()));
        pad.place(4, 0, 5, TerminalContent.TERMINAL.get(), Direction.NORTH);
        pad.stock(controller, COBBLE, 500);
        pad.stock(controller, ItemKey.of(Items.IRON_INGOT), 64);
        for (Foreign foreign : Foreign.values()) {
            int x = foreign == Foreign.AE2 ? 3 : 6;
            Optional<ShowcaseBridges.Builder> builder = ShowcaseBridges.builder(foreign);
            if (builder.isPresent()) {
                ShowcaseBridges.Site site = new ShowcaseBridges.Site(pad.level, pad.pos(x, 0, 5), Direction.SOUTH,
                        Direction.EAST, pad.warnings);
                PENDING_TASKS.add(new Pending(pad.level, pad.pos(x, 0, 5), builder.get().build(site)));
            } else {
                pad.sign(x, 6, Component.literal(foreign.english()), Component.translatable(BRIDGE_MISSING),
                        Component.translatable(BRIDGE_RUN_WITH), Component.literal(foreign.flag()));
            }
        }
        return controller;
    }

    private static ItemStack bound(Item item, GlobalPos link) {
        ItemStack stack = new ItemStack(item);
        stack.set(ModDataComponents.WIRELESS_LINK.get(), link);
        return stack;
    }

    /**
     * A one-input pattern. A crafting pattern names its vanilla {@code recipe}: without it the assembler lays the
     * inputs out row by row, which matches no shaped recipe such as sticks.
     */
    static CraftingPattern pattern(CraftingPattern.Kind kind, Item input, long in, Item output, long out,
            @Nullable String recipe) {
        return new CraftingPattern(kind,
                List.of(new PatternInput(new PatternIngredient.Exact(ItemKey.of(input)), in, Optional.empty(), false)),
                List.of(new PatternOutput(ItemKey.of(output), out, 1f)),
                recipe == null ? Optional.empty() : Optional.of(Identifier.withDefaultNamespace("crafting")),
                Optional.ofNullable(recipe).map(Identifier::withDefaultNamespace));
    }

    /** A cell of {@code casing} for {@code type} with as many memory modules of the casing's own tier as fit. */
    static ItemStack cell(CellCasing casing, Identifier type) {
        StorageCellItem item = CellContent.cellItem(casing);
        Item module = switch (casing) {
            case SMALL, PORTABLE -> CellContent.MEMORY_MODULE_1.get();
            case MEDIUM -> CellContent.MEMORY_MODULE_2.get();
            case LARGE -> CellContent.MEMORY_MODULE_3.get();
            case BULK -> CellContent.MEMORY_MODULE_4.get();
        };
        ItemStack stack = new ItemStack(item);
        stack.set(ModDataComponents.CELL.get(), CellData.of(type, Collections.nCopies(casing.memorySlots(), module)));
        return stack;
    }

    /** One pad being built: local coordinates are relative to its north-west corner, y 0 is the first air layer. */
    static final class Pad {
        final ServerLevel level;
        final BlockPos origin;
        final OwnerRecord owner;
        final List<String> warnings;

        Pad(ServerLevel level, BlockPos origin, OwnerRecord owner, List<String> warnings) {
            this.level = level;
            this.origin = origin;
            this.owner = owner;
            this.warnings = warnings;
        }

        BlockPos pos(int x, int y, int z) {
            return origin.offset(x, y, z);
        }

        /** Floor, air, a fence with a gap at the middle of the north side, and the station's sign. */
        void prepare(ShowcaseStation station) {
            for (int x = 0; x < PAD; x++) {
                for (int z = 0; z < PAD; z++) {
                    set(x, -1, z, Blocks.SMOOTH_STONE.defaultBlockState());
                    for (int y = 0; y < HEIGHT; y++) {
                        set(x, y, z, Blocks.AIR.defaultBlockState());
                    }
                    boolean edge = x == 0 || z == 0 || x == PAD - 1 || z == PAD - 1;
                    boolean wall = z == 0 && x >= ShowcaseBoards.WALL_X
                            && x < ShowcaseBoards.WALL_X + ShowcaseBoards.WALL_WIDTH;
                    if (edge && !wall && !(z == 0 && x == PAD / 2)) {
                        set(x, 0, z, Blocks.OAK_FENCE.defaultBlockState());
                    }
                }
            }
            stepWall();
            // Fences placed without neighbour shapes: let each one connect to the next.
            for (int x = 0; x < PAD; x++) {
                for (int z = 0; z < PAD; z++) {
                    BlockPos pos = pos(x, 0, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.is(Blocks.OAK_FENCE)) {
                        level.setBlock(pos, Block.updateFromNeighbourShapes(state, level, pos), Block.UPDATE_CLIENTS);
                    }
                }
            }
            sign(PAD / 2 - 1, 1, Component.translatable(station.nameKey()), Component.translatable(station.hintKey(1)),
                    Component.translatable(station.hintKey(2)), Component.translatable(station.hintKey(3)));
        }

        /** A waxed standing sign on the floor at ({@code x}, {@code z}) with four lines, facing north. */
        void sign(int x, int z, Component line1, Component line2, Component line3, Component line4) {
            // Rotation 8 faces north, towards a player walking up to the gap.
            set(x, 0, z, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 8));
            if (level.getBlockEntity(pos(x, 0, z)) instanceof SignBlockEntity sign) {
                SignText text = new SignText().setMessage(0, line1).setMessage(1, line2).setMessage(2, line3)
                        .setMessage(3, line4);
                sign.setText(text, true);
                sign.setWaxed(true);
            } else {
                warnings.add("no sign at " + pos(x, 0, z).toShortString());
            }
        }

        /**
         * The step wall ({@link ShowcaseBoards}) on the fence line east of the gap, facing north towards a player
         * walking up, on a network of its own: a controller and a creative source behind it, a block up so they touch
         * nothing of the station's.
         */
        void stepWall() {
            for (int x = 0; x < ShowcaseBoards.WALL_WIDTH; x++) {
                for (int y = 0; y < ShowcaseBoards.WALL_HEIGHT; y++) {
                    place(ShowcaseBoards.WALL_X + x, y, 0, DisplayContent.PANEL.get(), Direction.NORTH);
                }
            }
            owned(place(ShowcaseBoards.WALL_X + 1, 1, 1, ControllerContent.CONTROLLER.get(), null));
            place(ShowcaseBoards.WALL_X, 1, 1, CreativeEnergyContent.CREATIVE_ENERGY_SOURCE.get(), null);
        }

        void set(int x, int y, int z, BlockState state) {
            level.setBlock(pos(x, y, z), state, Block.UPDATE_ALL);
        }

        /** Places {@code block} facing {@code facing} where it has a facing property; returns the world position. */
        BlockPos place(int x, int y, int z, Block block, @Nullable Direction facing) {
            BlockState state = block.defaultBlockState();
            if (facing != null) {
                if (state.hasProperty(BlockStateProperties.FACING)) {
                    state = state.setValue(BlockStateProperties.FACING, facing);
                } else if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                        && facing.getAxis().isHorizontal()) {
                    state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
                }
            }
            BlockPos pos = pos(x, y, z);
            level.setBlockAndUpdate(pos, state);
            return pos;
        }

        <T> @Nullable T entity(BlockPos pos, Class<T> type) {
            BlockEntity entity = level.getBlockEntity(pos);
            if (type.isInstance(entity)) {
                return type.cast(entity);
            }
            warnings.add("no " + type.getSimpleName() + " at " + pos.toShortString());
            return null;
        }

        /**
         * Controller at ({@code cx}, {@code cz}), creative source at ({@code sx}, {@code sz}) and a tier-4 cable spine
         * along x at z = {@code spineZ} from x = 1 to {@code spineEnd}, whose 64 channels carry every device of a
         * station (M7N). Returns the controller.
         */
        BlockPos hub(int cx, int cz, int sx, int sz, int spineFrom, int spineZ, int spineEnd) {
            for (int x = spineFrom; x <= spineEnd; x++) {
                cable(x, 0, spineZ, CableContent.STORAGE_CABLE_TIER_4.type().get(), null);
            }
            BlockPos controller = place(cx, 0, cz, ControllerContent.CONTROLLER.get(), null);
            owned(controller);
            place(sx, 0, sz, CreativeEnergyContent.CREATIVE_ENERGY_SOURCE.get(), null);
            return controller;
        }

        void owned(BlockPos controller) {
            ControllerBlockEntity entity = entity(controller, ControllerBlockEntity.class);
            if (entity != null) {
                entity.setOwner(owner);
            }
        }

        /** A network device (M7R item 4) on top of a cable of the station's network. */
        void device(int x, int y, int z, Block block) {
            place(x, y, z, block, null);
        }

        void cable(int x, int y, int z, ConduitType type, @Nullable DyeColor colour) {
            if (!ConduitBlock.placeConduit(level, pos(x, y, z), type, colour)) {
                warnings.add("cable not placed at " + pos(x, y, z).toShortString());
            }
        }

        void facade(int x, int y, int z, BlockState facade) {
            BlockPos pos = pos(x, y, z);
            ConduitBlockEntity cable = entity(pos, ConduitBlockEntity.class);
            if (cable != null && !Facades.set(level, pos, cable, facade)) {
                warnings.add("facade refused at " + pos.toShortString());
            }
        }

        void part(int x, int y, int z, Direction side, PartType type, ItemStack stack) {
            CableParts parts = StorageCableBlock.parts(level, pos(x, y, z));
            if (parts == null || !parts.place(side, type, null, stack)) {
                warnings.add("part not placed at " + pos(x, y, z).toShortString());
            }
        }

        @Nullable
        DriveBlockEntity drive(int x, int z, ItemStack... cells) {
            return drive(x, 0, z, Direction.NORTH, cells);
        }

        @Nullable
        DriveBlockEntity drive(int x, int y, int z, Direction facing, ItemStack... cells) {
            DriveBlockEntity drive = entity(place(x, y, z, DriveContent.DRIVE.get(), facing), DriveBlockEntity.class);
            if (drive == null) {
                return null;
            }
            for (ItemStack cell : cells) {
                if (!drive.insertCell(cell)) {
                    warnings.add("drive refused " + cell.getHoverName().getString());
                }
            }
            return drive;
        }

        /** A lever or button on the floor or ceiling, facing north; the block it hangs on must be there first. */
        void attached(int x, int y, int z, Block block, AttachFace face) {
            BlockState state = block.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, face)
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
            set(x, y, z, state);
        }

        /** A condition switch in "driven by logic" mode with {@code label}, and a redstone lamp on top. */
        void logicSwitch(int x, int y, int z, String label, DyeColor colour) {
            ConditionSwitchBlockEntity entity = entity(place(x, y, z, ConditionContent.SWITCH.get(), null),
                    ConditionSwitchBlockEntity.class);
            if (entity != null) {
                entity.worker().setSettings(ConditionSettings.DEFAULT.withType(ConditionType.LOGIC));
                entity.setLabel(DeviceLabel.of(label, colour));
            }
            set(x, y + 1, z, Blocks.REDSTONE_LAMP.defaultBlockState());
        }

        void provider(int x, int z, ItemStack pattern) {
            provider(x, 0, z, Direction.NORTH, pattern);
        }

        /** A pattern provider facing the machine it feeds, holding {@code pattern}. */
        void provider(int x, int y, int z, Direction facing, ItemStack pattern) {
            PatternProviderBlockEntity provider = entity(place(x, y, z, ProviderContent.PROVIDER.get(), facing),
                    PatternProviderBlockEntity.class);
            if (provider != null) {
                provider.logic().slots().setItem(0, pattern);
            }
        }

        void chest(int x, int y, int z, ItemStack... stacks) {
            set(x, y, z, Blocks.CHEST.defaultBlockState());
            if (level.getBlockEntity(pos(x, y, z)) instanceof Container chest) {
                for (int i = 0; i < stacks.length && i < chest.getContainerSize(); i++) {
                    chest.setItem(i, stacks[i]);
                }
            }
        }

        BlockPos machine(int x, int y, int z, Block block, ItemStack... inputs) {
            return machine(x, y, z, Direction.NORTH, block, inputs);
        }

        BlockPos machine(int x, int y, int z, Direction facing, Block block, ItemStack... inputs) {
            BlockPos pos = place(x, y, z, block, facing);
            StorageMachineBlockEntity machine = entity(pos, StorageMachineBlockEntity.class);
            if (machine != null) {
                fillMachine(machine, warnings, inputs);
            }
            return pos;
        }

        /** Fills the box between the two corners, both included, with {@code state}. */
        void fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        set(x, y, z, state);
                    }
                }
            }
        }

        /** Queues {@code amount} of {@code key} for the network at {@code node}, stored once it has formed. */
        void stock(BlockPos node, StackKey key, long amount) {
            PENDING.add(new Stock(level, node.immutable(), key, amount));
        }
    }
}
