package dev.silencebound.maxtech.storage.dev;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.bulk.BulkContent;
import dev.silencebound.maxtech.storage.bulk.BulkDriveBlockEntity;
import dev.silencebound.maxtech.storage.cell.CellInventory;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.condition.ConditionContent;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.stock.StockKeeperContent;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternTerminalContent;
import dev.silencebound.maxtech.storage.display.DisplayContent;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.drive.DriveContent;
import dev.silencebound.maxtech.storage.energy.EnergyCellBlockEntity;
import dev.silencebound.maxtech.storage.energy.EnergyContent;
import dev.silencebound.maxtech.storage.hub.HubContent;
import dev.silencebound.maxtech.storage.logic.controller.LogicContent;
import dev.silencebound.maxtech.storage.machine.MachineContent;
import dev.silencebound.maxtech.storage.machine.StorageMachineBlockEntity;
import dev.silencebound.maxtech.storage.network.NetworkContent;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.signal.SignalContent;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import dev.silencebound.maxtech.storage.tool.goggles.GogglesContent;
import dev.silencebound.maxtech.storage.tool.layer.LayerContent;
import dev.silencebound.maxtech.storage.tool.probe.ProbeContent;
import dev.silencebound.maxtech.storage.tool.tracepen.TracePenContent;
import dev.silencebound.maxtech.storage.tool.uplink.UplinkContent;
import dev.silencebound.maxtech.storage.tool.wrench.WrenchContent;
import dev.silencebound.maxtech.storage.world.WorldContent;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jspecify.annotations.Nullable;

/**
 * What each showcase station's step wall lists: what to do and what should happen, with a detector for the steps the
 * showcase can see for itself. The texts are display markup ({@code lib.text.RichText}) in plain English, data like a
 * sign's text or a logic notice, not lang keys. Positions are the pad-local ones {@link Showcase} builds with. Dev
 * tooling only.
 */
public final class ShowcaseSteps {
    /** Checks a detector makes before an online check turns red (about 15 seconds at one check a second). */
    static final int PATIENCE = 15;

    private ShowcaseSteps() {
    }

    /** What a detector sees. */
    public enum Check {
        /** Not done yet. */
        OPEN,
        /** Done; a step stays done once it was. */
        DONE,
        /** Something is wrong, such as a network that never came online. */
        PROBLEM
    }

    /**
     * Looks at a station for one step. A detector may keep state between checks (such as "the count was over 2,000
     * once"), so each build or reset makes new ones. Called about once a second while the step is not done; it must
     * stay cheap (a few block or block-entity reads). Server thread.
     */
    @FunctionalInterface
    public interface Detector {
        Check check(Spot spot);
    }

    /**
     * One line of a wall.
     *
     * @param action
     *            what to do, as markup (white on the wall)
     * @param expected
     *            what should happen, as markup (grey on the wall)
     * @param detector
     *            null for a step that is ticked by hand
     */
    public record Step(String action, String expected, @Nullable Detector detector) {
        public boolean manual() {
            return detector == null;
        }
    }

    /** A station as its detectors see it: the level, the pad's north-west corner and its hub. Server thread. */
    public static final class Spot {
        final ServerLevel level;
        final BlockPos origin;
        final BlockPos hub;

        Spot(ServerLevel level, BlockPos origin, BlockPos hub) {
            this.level = level;
            this.origin = origin;
            this.hub = hub;
        }

        BlockPos pos(int x, int y, int z) {
            return origin.offset(x, y, z);
        }

        @Nullable
        BlockEntity entity(int x, int y, int z) {
            return level.getBlockEntity(pos(x, y, z));
        }

        /** The hub's network, or null. */
        @Nullable
        NetworkState network() {
            return StorageNetworks.network(level, StorageNetworks.ID, hub);
        }

        long count(StackKey key) {
            NetworkState network = network();
            return network == null ? 0 : network.contents().amount(key);
        }
    }

    /** The steps of {@code station}, with new detectors. */
    public static List<Step> steps(ShowcaseStation station) {
        return switch (station) {
            case CORE -> core();
            case PRIORITY -> priority();
            case PORTS -> ports();
            case CABLES -> cables();
            case CHANNELS -> channels();
            case SUBNET -> subnet();
            case BRIDGES -> bridges();
            case MACHINES -> machines();
            case ENERGY -> energy();
            case CRAFTING -> crafting();
            case TOOLS -> tools();
            case LOGIC -> logic();
        };
    }

    private static List<Step> core() {
        return List.of(online(),
                auto("Open the " + icon(TerminalContent.TERMINAL.get()) + " terminal, take out "
                        + icon(Items.COBBLESTONE) + " cobblestone", "the count drops below 2,000",
                        drops(ItemKey.of(Items.COBBLESTONE), 2_000)),
                auto("Put " + icon(Items.DIRT) + " dirt into the " + icon(WorldContent.NETWORK_CHEST.get())
                        + " network chest", "dirt shows up in the terminal", rises(ItemKey.of(Items.DIRT), 0)),
                auto("Pull a cell from the west " + icon(DriveContent.DRIVE.get()) + " drive",
                        "its items leave the terminal",
                        spot -> spot.entity(3, 0, 5) instanceof DriveBlockEntity drive && drive.cellCount() < 4
                                ? Check.DONE
                                : Check.OPEN),
                manual("Look at the drives", "cells light by fill; the fluid cell is in the east drive"),
                manual("Fluid view of the terminal", "water and lava listed"),
                manual("Point Jade at the " + icon(ControllerContent.CONTROLLER.get()) + " controller",
                        "two blocks, root links, cards and recorders"),
                manual("Open the controller's Cards tab",
                        "two batch amplifiers, an efficiency core, a surge regulator"));
    }

    private static List<Step> priority() {
        return List.of(online(),
                auto("Watch the " + icon(PortContent.PORT.get()) + " import port",
                        "the chest's items all go to the west " + icon(DriveContent.DRIVE.get()) + " drive (max)",
                        spot -> spot.entity(3, 0, 2) instanceof Container chest && chest.isEmpty()
                                && spot.entity(3, 0, 5) instanceof DriveBlockEntity first && stores(first)
                                && spot.entity(5, 0, 5) instanceof DriveBlockEntity last && !stores(last)
                                        ? Check.DONE
                                        : Check.OPEN),
                manual("Open a drive's priority tab", "type any whole number, negative too"),
                auto("Pull the west drive's cell, drop " + icon(Items.COBBLESTONE) + " cobblestone in the "
                        + icon(Items.HOPPER) + " hopper",
                        "the " + icon(BulkContent.BULK_DRIVE.get()) + " bulk drive (0) takes it, not the drive at -100",
                        spot -> spot.entity(4, 0, 5) instanceof BulkDriveBlockEntity bulk && bulk.contents() != null
                                ? Check.DONE
                                : Check.OPEN),
                auto("Then drop " + icon(Items.DIRT) + " dirt in the hopper",
                        "the bulk drive keeps one kind: dirt goes to the drive at -100",
                        spot -> spot.entity(5, 0, 5) instanceof DriveBlockEntity last && stores(last)
                                ? Check.DONE
                                : Check.OPEN));
    }

    private static List<Step> channels() {
        int[] offline = {0};
        return List.of(online(),
                manual("Point Jade at the east terminals on the front cable",
                        "offline: the tier-1 cable is full, 8 of 8"),
                auto("Break a " + icon(TerminalContent.TERMINAL.get()) + " terminal on the front cable",
                        "an offline one comes online", spot -> {
                            NetworkState network = spot.network();
                            int now = network == null ? 0 : network.offlineDevices().size();
                            if (now >= 2) {
                                offline[0] = now;
                            }
                            return offline[0] >= 2 && now < offline[0] ? Check.DONE : Check.OPEN;
                        }),
                manual("Point Jade at each " + icon(HubContent.TIER_3.get()) + " hub",
                        "64, 32 and 16 channels; the terminals on it count there"),
                manual("Point Jade at the " + icon(ControllerContent.CONTROLLER.get()) + " controller by the entrance",
                        "on standby; the one placed first is in charge"),
                auto("Break the west controller", "the standby one takes over", spot -> {
                    NetworkState network = StorageNetworks.network(spot.level, StorageNetworks.ID, spot.pos(2, 0, 3));
                    return spot.level.getBlockState(spot.pos(1, 0, 4)).isAir() && network != null && !network.isAdHoc()
                            && network.status() == StorageNetwork.Status.ONLINE ? Check.DONE : Check.OPEN;
                }), manual("Point Jade at the back row", "no controller: 8 channels in all, two terminals offline"));
    }

    private static List<Step> bridges() {
        List<Step> steps = new ArrayList<>();
        steps.add(online());
        boolean any = false;
        for (ShowcaseBridges.Foreign foreign : ShowcaseBridges.Foreign.values()) {
            if (ShowcaseBridges.builder(foreign).isEmpty()) {
                continue;
            }
            any = true;
            steps.add(auto("Open the " + icon(TerminalContent.TERMINAL.get()) + " terminal",
                    foreign.english() + "'s " + icon(foreign.marker().item()) + " items are listed",
                    reaches(foreign.marker(), 1)));
            steps.add(manual("Open the " + foreign.english() + " terminal", "Storage's cobblestone is listed"));
        }
        if (any) {
            steps.add(manual("Take items through a bridge", "they leave the other network"));
        } else {
            steps.add(manual("Run with -PwithAe2=true or -PwithRs=true", "a bridge to a powered network for each"));
        }
        return steps;
    }

    private static List<Step> ports() {
        return List.of(online(),
                auto("Watch the " + icon(PortContent.PORT.get()) + " block port", "the chest south of it empties",
                        empty(2, 0, 6)),
                auto("Watch the port part on the cable", "the " + icon(Items.IRON_INGOT) + " iron chest empties",
                        empty(3, 0, 3)),
                auto("Watch the " + icon(Items.HOPPER) + " hopper feeding the P2P",
                        "logs arrive at the far P2P's chest", holds(6, 0, 2, Items.OAK_LOG)),
                auto("Watch the " + icon(ConditionContent.SWITCH.get()) + " condition switch",
                        "its " + icon(Items.REDSTONE_LAMP) + " lamp lights (over 64 cobblestone)", lit(7, 0, 2)),
                auto("Watch the placer, breaker and collector",
                        "cobblestone placed, the " + icon(Items.STONE) + " stone broken and collected",
                        spot -> spot.level.getBlockState(spot.pos(2, 1, 3)).is(Blocks.COBBLESTONE)
                                && spot.level.getBlockState(spot.pos(3, 1, 3)).isAir() ? Check.DONE : Check.OPEN),
                manual("Look at the " + icon(DisplayContent.PANEL.get()) + " display wall",
                        "a line graph of cobblestone"),
                manual("Open the interface", "16 cobblestone stocked"));
    }

    private static List<Step> cables() {
        return List.of(online(),
                manual("Rows north to south", "plain, covered, smart, fiber; tiers 1 to 4 west to east"),
                manual("Look along a row", "the lane gets brighter with the tier; covered joints show 1 to 4 pips"),
                manual("The red cable and the stone brick facade", "dyed red; the facade hides its cable"),
                auto("Break the " + icon(CableContent.STORAGE_CABLE_TIER_4.item().get()) + " cable east of the "
                        + "controller", "the rows go offline",
                        spot -> spot.level.getBlockState(spot.pos(2, 0, 4)).isAir() ? Check.DONE : Check.OPEN),
                auto("Put it back", "the rows come back online", backOnline()));
    }

    private static List<Step> subnet() {
        return List.of(online(), manual("Open the east terminal", "the merged subnet lists the main network's items"),
                manual("Open the terminal by the far " + icon(SignalContent.ANTENNA_TIER_1.get()) + " antenna",
                        "it reaches the main network over the pair"),
                auto("Take the " + icon(SignalContent.WIRELESS_TERMINAL.get()) + " wireless terminal from the chest",
                        "the chest is empty", empty(3, 0, 2)),
                manual("Use it off the pad", "it opens the main network in range"),
                manual("Point Jade at an antenna", "its pair and range"));
    }

    private static List<Step> machines() {
        return List.of(
                auto("Watch the " + icon(MachineContent.FUEL_GENERATOR.get()) + " fuel generator", "it burns coal",
                        spot -> spot.entity(4, 0, 4) instanceof StorageMachineBlockEntity generator
                                && (generator.duration() > 0 || generator.inputs().stack(0).getCount() < 64)
                                        ? Check.DONE
                                        : Check.OPEN),
                auto("Watch the " + icon(MachineContent.LITHOGRAPH.get()) + " lithograph", "it makes wafers",
                        made(3, 0, 4)),
                auto("Watch the " + icon(MachineContent.FABRICATOR.get()) + " fabricator", "it makes wire",
                        made(5, 0, 4)),
                auto("Watch the " + icon(MachineContent.ASSEMBLER.get()) + " assembler", "it makes a processor",
                        made(4, 0, 5)),
                manual("Open a machine", "progress, energy and slots"));
    }

    private static List<Step> energy() {
        return List.of(online(),
                auto("Watch the " + icon(MachineContent.FUEL_GENERATOR.get()) + " fuel generator",
                        "it burns coal and fills the controller's buffer",
                        spot -> spot.entity(3, 0, 3) instanceof StorageMachineBlockEntity generator
                                && (generator.duration() > 0 || generator.inputs().stack(0).getCount() < 64)
                                        ? Check.DONE
                                        : Check.OPEN),
                auto("Watch the " + icon(EnergyContent.TIER_1.get()) + " energy cells",
                        "they charge once the controller is full",
                        spot -> spot.entity(3, 0, 5) instanceof EnergyCellBlockEntity cell
                                && cell.energyEndpoint().stored() > 0 ? Check.DONE : Check.OPEN),
                manual("Point Jade at the generator", "MU/t it makes; the network's supply and demand"),
                manual("Use the " + icon(ProbeContent.SIGNAL_PROBE.get()) + " signal probe on the cable",
                        "supply, demand and delivered MU/t"),
                manual("Use the " + icon(NetworkContent.NETWORK_ANALYZER.get()) + " analyzer on the controller",
                        "energy lines: supply, demand, full in"),
                manual("Take the coal out", "the controller and cells keep the network running"));
    }

    private static List<Step> crafting() {
        return List.of(online(),
                auto("Watch the " + icon(StockKeeperContent.KEEPER.get()) + " stock keeper",
                        icon(Items.STICK) + " sticks reach 32", reaches(ItemKey.of(Items.STICK), 32)),
                auto("Watch the export port with its crafting card",
                        icon(Items.IRON_INGOT) + " iron ingots arrive in its chest", holds(7, 0, 2, Items.IRON_INGOT)),
                manual("Request 64 sticks in the " + icon(TerminalContent.TERMINAL.get()) + " terminal",
                        "the planner shows the steps, a CPU runs them"),
                manual("Open a " + icon(CpuContent.CORE_1.get()) + " CPU core", "tier, parts and the running job"),
                manual("Open the " + icon(PatternTerminalContent.TERMINAL.get()) + " pattern terminal",
                        "encode a pattern"));
    }

    private static List<Step> tools() {
        return List.of(
                online(),
                auto("Take the " + icon(WrenchContent.WRENCH.get()) + " wrench from the west chest", "it is in hand",
                        spot -> spot.entity(3, 0, 2) instanceof Container chest
                                && chest.countItem(WrenchContent.WRENCH.get()) == 0 ? Check.DONE : Check.OPEN),
                manual("Wear the " + icon(GogglesContent.NETWORK_GOGGLES.get()) + " goggles",
                        "cables and devices show the network"),
                manual("Use the " + icon(LayerContent.CABLE_LAYER.get()) + " cable layer", "it lays cable"),
                manual("Open the " + icon(UplinkContent.POCKET_UPLINK.get()) + " uplink, tablet and terminal",
                        "each opens this network"),
                manual("Try the " + icon(TracePenContent.TRACE_PEN.get()) + " trace pen and zone painter",
                        "a traced path, a painted zone"),
                auto("Put a stack into the " + icon(BulkContent.BULK_DRIVE.get()) + " bulk drive", "it holds the stack",
                        spot -> spot.entity(3, 0, 5) instanceof BulkDriveBlockEntity bulk && bulk.contents() != null
                                ? Check.DONE
                                : Check.OPEN));
    }

    private static List<Step> logic() {
        return List.of(
                auto("Flip the " + icon(Items.LEVER) + " lever on the controller",
                        "port \"intake\" turns on, \"bank\" gets priority 15",
                        spot -> spot.entity(3, 0, 3) instanceof PortBlockEntity intake && !intake.switchedOff()
                                ? Check.DONE
                                : Check.OPEN),
                auto("Then wait", "over 100 cobblestone lights the " + icon(Items.REDSTONE_LAMP) + " lamp",
                        lit(5, 2, 4)),
                auto("Press the " + icon(Items.STONE_BUTTON) + " button on \"outbox\"", "16 cobblestone in its chest",
                        spot -> spot.entity(2, 0, 2) instanceof Container chest
                                && chest.countItem(Items.COBBLESTONE) >= 16 ? Check.DONE : Check.OPEN),
                auto("Put 13 items into the sensed " + icon(Items.CHEST) + " chest",
                        "a notice on the wall, in the log and chat", notice(LogicExamples.CHEST_FULL)),
                auto("Flip the lever over the cable three times", "the latch lamp lights", lit(6, 2, 4)),
                auto("Watch the " + icon(DisplayContent.PANEL.get()) + " notice wall",
                        "\"Sticks are ready\", with a toast", notice(LogicExamples.STICKS_READY)),
                manual("Open the " + icon(LogicContent.CONTROLLER.get()) + " editor",
                        "it scales; live values and the why line"),
                manual("Open \"intake\" and look with Jade",
                        "label tab, \"Turned off by logic\", label colours; claims need withFtbChunks=true"));
    }

    /** Whether a cell mounted in {@code drive} stores anything. */
    public static boolean stores(DriveBlockEntity drive) {
        for (int slot = 0; slot < DriveBlockEntity.SLOTS; slot++) {
            CellInventory cell = drive.cell(slot);
            if (cell != null && cell.kinds() > 0) {
                return true;
            }
        }
        return false;
    }

    private static Step auto(String action, String expected, Detector detector) {
        return new Step(action, expected, detector);
    }

    private static Step manual(String action, String expected) {
        return new Step(action, expected, null);
    }

    /** The markup of {@code item}'s icon. */
    static String icon(ItemLike item) {
        return "[item:" + BuiltInRegistries.ITEM.getKey(item.asItem()) + "]";
    }

    /** The station's network is online and powered; red when it is not within {@link #PATIENCE} checks. */
    private static Step online() {
        int[] checks = {0};
        return auto("Walk in", "the controller is online and powered", spot -> {
            NetworkState network = spot.network();
            if (network != null && network.isPowered() && network.status() == StorageNetwork.Status.ONLINE) {
                return Check.DONE;
            }
            return ++checks[0] > PATIENCE ? Check.PROBLEM : Check.OPEN;
        });
    }

    /** After the cable next to the controller was broken, it is back and the far rows are online. */
    private static Detector backOnline() {
        boolean[] broken = {false};
        return spot -> {
            if (spot.level.getBlockState(spot.pos(2, 0, 4)).isAir()) {
                broken[0] = true;
                return Check.OPEN;
            }
            // The tier-4 cable of the last row (fiber; the retired glass row is gone).
            NetworkState network = StorageNetworks.network(spot.level, StorageNetworks.ID, spot.pos(6, 0, 5));
            return broken[0] && network != null && network.status() == StorageNetwork.Status.ONLINE
                    ? Check.DONE
                    : Check.OPEN;
        };
    }

    /** The network once held at least {@code from} of {@code key} and now holds less. */
    private static Detector drops(StackKey key, long from) {
        boolean[] seen = {false};
        return spot -> {
            long count = spot.count(key);
            if (count >= from) {
                seen[0] = true;
            }
            return seen[0] && count < from ? Check.DONE : Check.OPEN;
        };
    }

    /** The network once held {@code base} or less of {@code key} with the network up, and now holds more. */
    private static Detector rises(StackKey key, long base) {
        boolean[] seen = {false};
        return spot -> {
            NetworkState network = spot.network();
            if (network == null || !network.isPowered()) {
                return Check.OPEN;
            }
            long count = network.contents().amount(key);
            if (count <= base) {
                seen[0] = true;
            }
            return seen[0] && count > base ? Check.DONE : Check.OPEN;
        };
    }

    private static Detector reaches(StackKey key, long amount) {
        return spot -> spot.count(key) >= amount ? Check.DONE : Check.OPEN;
    }

    private static Detector empty(int x, int y, int z) {
        return spot -> spot.entity(x, y, z) instanceof Container container && container.isEmpty()
                ? Check.DONE
                : Check.OPEN;
    }

    private static Detector holds(int x, int y, int z, Item item) {
        return spot -> spot.entity(x, y, z) instanceof Container container && container.countItem(item) > 0
                ? Check.DONE
                : Check.OPEN;
    }

    private static Detector lit(int x, int y, int z) {
        return spot -> {
            BlockState state = spot.level.getBlockState(spot.pos(x, y, z));
            return state.hasProperty(BlockStateProperties.LIT) && state.getValue(BlockStateProperties.LIT)
                    ? Check.DONE
                    : Check.OPEN;
        };
    }

    private static Detector made(int x, int y, int z) {
        return spot -> {
            if (!(spot.entity(x, y, z) instanceof StorageMachineBlockEntity machine)) {
                return Check.OPEN;
            }
            for (int i = 0; i < machine.outputs().size(); i++) {
                if (!machine.outputs().stack(i).isEmpty()) {
                    return Check.DONE;
                }
            }
            return Check.OPEN;
        };
    }

    private static Detector notice(String text) {
        return spot -> {
            NetworkState network = spot.network();
            if (network == null) {
                return Check.OPEN;
            }
            for (NetworkNotices.Notice notice : network.displayNotices().lines()) {
                if (notice.text().equals(text)) {
                    return Check.DONE;
                }
            }
            return Check.OPEN;
        };
    }
}
