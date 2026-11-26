package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetwork;
import dev.silencebound.maxtech.lib.api.energy.EnergyReadout;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.energy.EnergyLookups;
import dev.silencebound.maxtech.lib.registry.ModDataComponents;
import dev.silencebound.maxtech.lib.save.SaveFormat;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.display.DisplayContent;
import dev.silencebound.maxtech.storage.display.DisplayPanelBlockEntity;
import dev.silencebound.maxtech.storage.energy.EnergyCellBlock;
import dev.silencebound.maxtech.storage.energy.EnergyCellBlockEntity;
import dev.silencebound.maxtech.storage.energy.EnergyContent;
import dev.silencebound.maxtech.storage.energy.FeView;
import dev.silencebound.maxtech.storage.machine.FuelGeneratorBlockEntity;
import dev.silencebound.maxtech.storage.machine.MachineContent;
import dev.silencebound.maxtech.storage.machine.MachineKind;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.signal.SignalContent;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalItem;
import dev.silencebound.maxtech.storage.tool.probe.ProbeSampler;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Storage on MaxTech energy (MLE item 4): a storage network hosts an energy network whose pass charges the controller's
 * buffer and the cells, operations still pay per operation inside their transactions, throughput follows the supply
 * linearly, load shedding follows energy that comes in through the pass, other mods' FE blocks feed the network through
 * the cells' edges and get only surplus under the export switch, readouts come from Storage's real endpoints, and saves
 * and items from before move from FE to MU exactly once, also at an uneven rate.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class MuEnergyGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> PLAIN = new ArrayList<>();
    private static final String MIGRATION = "mu_migration_once";
    private static final String EXPORT_OFF = "mu_export_off_sends_nothing";
    private static final String NO_CHANNEL = "mu_generator_feeds_full_ad_hoc_network";
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        P2PTestContent.init();
        plain("mu_nothing_moved_costs_nothing", MuEnergyGameTests::nothingMovedCostsNothing);
        plain("mu_brownout_linear", MuEnergyGameTests::brownoutLinear);
        plain("mu_shedding_follows_flows", MuEnergyGameTests::sheddingFollowsFlows);
        plain("mu_fe_block_feeds_through_edge", MuEnergyGameTests::feBlockFeeds);
        plain("mu_export_sends_surplus", helper -> exportSurplus(helper, true));
        plain("mu_readouts_real_endpoint", MuEnergyGameTests::readouts);
        plain("mu_flows_mark_blocks_changed", MuEnergyGameTests::flowsMarkChanged);
        plain("mu_merge_keeps_controller_in_network", MuEnergyGameTests::mergeKeepsController);
        plain("mu_generator_is_a_device_without_a_channel", MuEnergyGameTests::generatorTakesNoChannel);
        plain("mu_split_and_merge_keep_energy", MuEnergyGameTests::splitAndMerge);
        StorageTests.function(NO_CHANNEL, StorageTestEnvironment.clearing(MuEnergyGameTests::generatorOnFullNetwork));
        StorageTests.function(MIGRATION, StorageTestEnvironment.clearing(MuEnergyGameTests::migration));
        StorageTests.function(EXPORT_OFF, StorageTestEnvironment.clearing(helper -> exportSurplus(helper, false)));
    }

    private MuEnergyGameTests() {
    }

    private static void plain(String name, Consumer<GameTestHelper> test) {
        PLAIN.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechStorage.id("mu_energy"),
                StorageTestEnvironment.plain());
        for (String name : PLAIN) {
            StorageTests.test(event, plain, name, STRUCTURE, 600);
        }
        // 3 FE are worth 2 MU while these run.
        StorageTests.test(event,
                event.registerEnvironment(MaxtechStorage.id("mu_energy_rate"),
                        new StorageTestEnvironment("energy.feRateFe;energy.feRateMu", "3;2", false)),
                MIGRATION, STRUCTURE, 400);
        StorageTests.test(event,
                event.registerEnvironment(MaxtechStorage.id("mu_energy_export_off"),
                        new StorageTestEnvironment("energy.exportEnabled", "false", false)),
                EXPORT_OFF, STRUCTURE, 400);
        // A network without a controller has one channel while this runs.
        StorageTests.test(event, event.registerEnvironment(MaxtechStorage.id("mu_energy_one_channel"),
                new StorageTestEnvironment("network.channels.adHoc", "1", false)), NO_CHANNEL, STRUCTURE, 400);
    }

    /**
     * Splitting a network keeps its energy exactly, and merging again restores it: a controller (5,000 MU) and an
     * energy cell charged both directly and by the pass are cut apart by breaking the cable between them; the two
     * halves hold together what the whole held, the cell's half in its own pool, and placing the cable again brings it
     * back into one total. Nothing feeds the network while it is measured.
     */
    private static void splitAndMerge(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.cables(helper, 2, 6, CableContent.STORAGE_CABLE_TIER_1.type().get());
        EnergyCellBlockEntity cell = EnergyCellGameTests.cell(helper, NetworkGameTests.at(7),
                EnergyContent.TIER_1.get());
        EnergyEndpoint[] producer = new EnergyEndpoint[1];
        long[] total = new long[1];
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(cell.network() != null, "no network");
            helper.assertTrue(NetworkGameTests.controllerEntity(helper, core).network() == cell.network(), "one net");
        }).thenExecute(() -> {
            NetworkState network = cellNet(helper, cell);
            NetworkGameTests.power(helper, core, 10_000);
            cell.cellStore().receive(20_000);
            producer[0] = feed(network, 700);
        }).thenIdle(10).thenExecute(() -> producer[0].setSupply(0)).thenIdle(2).thenExecute(() -> {
            NetworkState network = cellNet(helper, cell);
            total[0] = network.storedEnergy();
            helper.assertTrue(cell.cellStore().stored() > 20_000, "the pass charged the cell");
            helper.assertValueEqual(total[0],
                    NetworkGameTests.controllerEntity(helper, core).buffer().stored() + cell.cellStore().stored(),
                    "the whole");
            // The cell's side is the smaller one, so the cell moves to a new network state and pool.
            helper.setBlock(NetworkGameTests.at(6), Blocks.AIR);
        }).thenWaitUntil(() -> helper
                .assertTrue(NetworkGameTests.controllerEntity(helper, core).network() != cell.network(), "split"))
                .thenIdle(2).thenExecute(() -> {
                    NetworkState withCore = NetworkGameTests.controllerEntity(helper, core).network();
                    NetworkState withCell = cell.network();
                    if (withCore == null || withCell == null) {
                        throw helper.assertionException(Component.literal("a half has no network"));
                    }
                    helper.assertValueEqual(withCore.storedEnergy() + withCell.storedEnergy(), total[0],
                            "both halves together");
                    helper.assertValueEqual(withCell.energyCells().stored(), cell.cellStore().stored(),
                            "the cell's half counts it");
                    NetworkGameTests.cables(helper, 6, 6, CableContent.STORAGE_CABLE_TIER_1.type().get());
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        NetworkGameTests.controllerEntity(helper, core).network() == cell.network(), "merged again"))
                .thenIdle(2).thenExecute(() -> helper.assertValueEqual(cellNet(helper, cell).storedEnergy(), total[0],
                        "the merged network"))
                .thenSucceed();
    }

    private static NetworkState cellNet(GameTestHelper helper, EnergyCellBlockEntity cell) {
        NetworkState network = cell.network();
        if (network == null) {
            throw helper.assertionException(Component.literal("the cell has no network"));
        }
        return network;
    }

    /**
     * A fuel generator touching a device is a device of that network (MLE Decided) that holds no channel (owner
     * 2026-10-17, like an energy cell): it joins, counts towards no device limit, and its link feeds the network;
     * broken, it leaves.
     */
    private static void generatorTakesNoChannel(GameTestHelper helper) {
        NetworkGameTests.controller(helper, 1);
        TestStorage.TestDevice drive = NetworkGameTests.device(helper, NetworkGameTests.at(2));
        BlockPos generatorPos = NetworkGameTests.at(2).above();
        int[] before = new int[1];
        FuelGeneratorBlockEntity[] generator = new FuelGeneratorBlockEntity[1];
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(drive.network() != null, "no network"))
                .thenExecute(() -> {
                    before[0] = NetworkGameTests.net(helper, drive).limitedDevices();
                    helper.setBlock(generatorPos, MachineContent.block(MachineKind.FUEL_GENERATOR));
                    generator[0] = helper.getBlockEntity(generatorPos, FuelGeneratorBlockEntity.class);
                    MachineGameTests.insert(generator[0], new ItemStack(Items.COAL));
                }).thenWaitUntil(() -> {
                    helper.assertTrue(generator[0].device().deviceLink().network() == drive.network(), "joined");
                    helper.assertFalse(generator[0].device().deviceLink().holdsChannel(), "holds no channel");
                    helper.assertTrue(NetworkGameTests.net(helper, drive).storedEnergy() > 0, "feeds the network");
                }).thenExecute(() -> {
                    NetworkState network = NetworkGameTests.net(helper, drive);
                    helper.assertValueEqual(network.limitedDevices(), before[0], "no more limited devices");
                    helper.assertTrue(generator[0].linkedNetwork() == network, "linked");
                    helper.setBlock(generatorPos, Blocks.AIR);
                    helper.assertTrue(generator[0].device().deviceLink().network() == null, "left when broken");
                }).thenSucceed();
    }

    /**
     * With one channel, a network without a controller whose device holds it still takes a generator, which needs no
     * channel: the generator joins, is never offline, and feeds the network while the drive keeps the channel.
     */
    private static void generatorOnFullNetwork(GameTestHelper helper) {
        TestStorage.TestDevice drive = NetworkGameTests.device(helper, NetworkGameTests.at(2));
        BlockPos generatorPos = NetworkGameTests.at(2).above();
        FuelGeneratorBlockEntity[] generator = new FuelGeneratorBlockEntity[1];
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(drive.network() != null, "no network");
            helper.assertTrue(drive.deviceLink().holdsChannel(), "the drive holds the channel");
        }).thenExecute(() -> {
            helper.setBlock(generatorPos, MachineContent.block(MachineKind.FUEL_GENERATOR));
            generator[0] = helper.getBlockEntity(generatorPos, FuelGeneratorBlockEntity.class);
            MachineGameTests.insert(generator[0], new ItemStack(Items.COAL));
        }).thenWaitUntil(() -> {
            helper.assertTrue(generator[0].linkedNetwork() == drive.network(), "linked");
            helper.assertTrue(NetworkGameTests.net(helper, drive).storedEnergy() > 0, "feeds the network");
        }).thenExecute(() -> {
            helper.assertTrue(generator[0].device().deviceLink().offlineBy() == null, "never offline");
            helper.assertFalse(generator[0].device().deviceLink().holdsChannel(), "holds no channel");
            helper.assertTrue(drive.deviceLink().holdsChannel(), "the drive keeps the channel");
        }).thenSucceed();
    }

    /** A producer of {@code perTick} MU in {@code network}'s energy network, as a generator would be. */
    private static EnergyEndpoint feed(NetworkState network, long perTick) {
        EnergyEndpoint producer = EnergyEndpoint.producer();
        network.energy().add(producer);
        producer.setSupply(perTick);
        return producer;
    }

    private static EnergyNetwork energyNetwork(GameTestHelper helper, NetworkState network) {
        return network.energyNetwork().orElseThrow(() -> helper.assertionException(Component.literal("no energy net")));
    }

    /**
     * Per-operation charging is unchanged on the energy network: an extract that moves nothing costs nothing, an insert
     * pays one operation's 4 MU, and energy the pass brings in between does not change what an operation costs.
     */
    private static void nothingMovedCostsNothing(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        TestStorage.TestDevice drive = NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(drive.network() != null, "no network"))
                .thenExecute(() -> {
                    NetworkGameTests.power(helper, controller, 1000);
                    NetworkState network = NetworkGameTests.net(helper, drive);
                    try (Transaction transaction = Transaction.openRoot()) {
                        helper.assertValueEqual(network.extract(COBBLE, 10, transaction), 0L, "nothing to extract");
                        transaction.commit();
                    }
                    helper.assertValueEqual(network.storedEnergy(), 1000L, "an extract that moved nothing");
                    helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 10), 10L, "inserted");
                    helper.assertValueEqual(network.storedEnergy(), 1000L - 4, "one operation");
                    feed(network, 8);
                }).thenIdle(10).thenExecute(() -> {
                    NetworkState network = NetworkGameTests.net(helper, drive);
                    long before = network.storedEnergy();
                    helper.assertTrue(before > 1000L - 4, "the pass charged the controller: " + before);
                    try (Transaction transaction = Transaction.openRoot()) {
                        helper.assertValueEqual(network.extract(COBBLE, 3, transaction), 3L, "extracted");
                        transaction.commit();
                    }
                    helper.assertValueEqual(network.storedEnergy(), before - 4, "the same 4 MU with flows");
                    try (Transaction transaction = Transaction.openRoot()) {
                        helper.assertValueEqual(network.extract(COBBLE, 3, transaction), 3L, "rolled back");
                    }
                    helper.assertValueEqual(network.storedEnergy(), before - 4, "a rollback refunds");
                }).thenSucceed();
    }

    /**
     * Brownout is linear: two networks whose only energy is a producer of 2 and of 4 MU/t run the same one-item task;
     * over 200 ticks the one with twice the supply moves about twice the items, each about the supply's worth of 4 MU
     * operations.
     */
    private static void brownoutLinear(GameTestHelper helper) {
        BlockPos slowCore = NetworkGameTests.at(1);
        BlockPos fastCore = new BlockPos(1, 1, 6);
        helper.setBlock(slowCore, ControllerContent.CONTROLLER.get());
        helper.setBlock(fastCore, ControllerContent.CONTROLLER.get());
        TestStorage.TestDevice slow = NetworkGameTests.device(helper, NetworkGameTests.at(2));
        TestStorage.TestDevice fast = NetworkGameTests.device(helper, new BlockPos(2, 1, 6));
        int[] moved = new int[2];
        StorageTask slowTask = task(moved, 0);
        StorageTask fastTask = task(moved, 1);
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(slow.network() != null && fast.network() != null, "no networks");
            helper.assertTrue(slow.network() != fast.network(), "one network");
        }).thenExecute(() -> {
            feed(NetworkGameTests.net(helper, slow), 2);
            feed(NetworkGameTests.net(helper, fast), 4);
            slow.schedule(slowTask);
            fast.schedule(fastTask);
        }).thenIdle(200).thenExecute(() -> {
            slow.cancel(slowTask);
            fast.cancel(fastTask);
            // 200 ticks of 2 and 4 MU at 4 MU an operation: about 100 and 200 items.
            helper.assertTrue(moved[0] >= 80 && moved[0] <= 101, moved[0] + " items at 2 MU/t");
            helper.assertTrue(moved[1] >= 170 && moved[1] <= 201, moved[1] + " items at 4 MU/t");
            double ratio = (double) moved[1] / moved[0];
            helper.assertTrue(ratio > 1.7 && ratio < 2.3, "twice the supply moved " + ratio + " times the items");
        }).thenSucceed();
    }

    private static StorageTask task(int[] moved, int index) {
        return (transfer, gameTime) -> {
            try (Transaction transaction = Transaction.openRoot()) {
                long in = transfer.insert(COBBLE, 1, transaction);
                transaction.commit();
                moved[index] += (int) in;
                return in > 0 ? StorageTask.NEXT_TICK : Math.max(1, transfer.retryTicks());
            }
        };
    }

    /**
     * Load shedding sees energy that comes in through the pass: a network armed at full power and drained to 20 %
     * pauses its display; a producer of 500 MU/t then charges the controller through the energy network, and the
     * display resumes once the network is past 35 %, with no FE insert involved.
     */
    private static void sheddingFollowsFlows(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        TestStorage.TestDevice drive = NetworkGameTests.device(helper, NetworkGameTests.at(2));
        BlockPos displayPos = NetworkGameTests.at(2).above();
        helper.setBlock(displayPos, DisplayContent.PANEL.get());
        DisplayPanelBlockEntity display = helper.getBlockEntity(displayPos, DisplayPanelBlockEntity.class);
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(drive.network() != null && display.network() == drive.network(), "not joined");
            helper.assertTrue(NetworkGameTests.controllerEntity(helper, core).network() == drive.network(), "core");
        }).thenExecute(() -> charge(helper, core, 100))
                .thenWaitUntil(() -> helper.assertTrue(NetworkGameTests.net(helper, drive).sheddingArmed(), "armed"))
                .thenExecute(() -> charge(helper, core, 20))
                .thenWaitUntil(() -> helper.assertTrue(display.powerPaused(), "the display pauses at 20 %"))
                .thenExecute(() -> feed(NetworkGameTests.net(helper, drive), 500))
                .thenWaitUntil(() -> helper.assertTrue(!display.powerPaused(), "the display resumes"))
                .thenExecute(() -> {
                    NetworkState network = NetworkGameTests.net(helper, drive);
                    helper.assertTrue(network.storedEnergy() * 100 >= network.energyCapacity() * 35,
                            "resumed at 35 % or more: " + network.storedEnergy());
                    helper.assertValueEqual(network.shedding().level(), 0, "shed level");
                }).thenSucceed();
    }

    private static void charge(GameTestHelper helper, BlockPos core, int percent) {
        ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
        controller.buffer().load(controller.buffer().capacity() * percent / 100);
    }

    /**
     * Another mod's battery next to an energy cell (a plain FE store that does not push) feeds the network through the
     * cell's edge: the controller fills, and what left the battery is exactly what the network holds (1:1), less what
     * the edge still holds.
     */
    private static void feBlockFeeds(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        EnergyCellBlockEntity cell = EnergyCellGameTests.cell(helper, NetworkGameTests.at(2),
                EnergyContent.TIER_1.get());
        BlockPos batteryPos = NetworkGameTests.at(2).above();
        helper.setBlock(batteryPos, P2PTestContent.SINK.get());
        P2PTestContent.Sink battery = helper.getBlockEntity(batteryPos, P2PTestContent.Sink.class);
        battery.energy.set(50_000);
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(cell.network() != null, "no network");
            helper.assertTrue(cell.edges().edge(Direction.UP) != null, "no edge to the battery");
        }).thenWaitUntil(
                () -> helper.assertValueEqual(NetworkGameTests.controllerEntity(helper, core).buffer().stored(),
                        10_000L, "the controller filled from the battery"))
                .thenExecute(() -> {
                    NetworkState network = cell.network();
                    if (network == null) {
                        throw helper.assertionException(Component.literal("no network"));
                    }
                    long left = 50_000L - battery.energy.getAmountAsLong();
                    helper.assertValueEqual(left, network.storedEnergy() + cell.edges().heldMu(),
                            "every FE that left the battery is in the network");
                    helper.assertTrue(network.energyReceived() >= 10_000L, "counted as received");
                }).thenSucceed();
    }

    /**
     * A network with a full controller and cell and a producer of 300 MU/t has surplus. With export on, an empty FE
     * block of another mod next to the cell gets it through the edge; with export off ({@code energy.exportEnabled}) it
     * gets nothing, the cell gives no FE to a neighbour pulling, and a fuel generator pushes none into another mod's
     * block, while it still burns.
     */
    private static void exportSurplus(GameTestHelper helper, boolean on) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        EnergyCellBlockEntity cell = EnergyCellGameTests.cell(helper, NetworkGameTests.at(2),
                EnergyContent.TIER_1.get());
        BlockPos sinkPos = NetworkGameTests.at(2).above();
        helper.setBlock(sinkPos, P2PTestContent.SINK.get());
        P2PTestContent.Sink sink = helper.getBlockEntity(sinkPos, P2PTestContent.Sink.class);
        BlockPos generatorPos = NetworkGameTests.at(8);
        helper.setBlock(generatorPos, MachineContent.block(MachineKind.FUEL_GENERATOR));
        BlockPos generatorSinkPos = NetworkGameTests.at(9);
        helper.setBlock(generatorSinkPos, P2PTestContent.SINK.get());
        P2PTestContent.Sink generatorSink = helper.getBlockEntity(generatorSinkPos, P2PTestContent.Sink.class);
        FuelGeneratorBlockEntity generator = helper.getBlockEntity(generatorPos, FuelGeneratorBlockEntity.class);
        MachineGameTests.insert(generator, new ItemStack(Items.COAL));
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(cell.network() != null, "no network");
            helper.assertTrue(cell.edges().edge(Direction.UP) != null, "no edge to the block");
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, core, 10_000);
            cell.cellStore().receive(cell.cellStore().capacity());
            NetworkState network = cell.network();
            if (network == null) {
                throw helper.assertionException(Component.literal("no network"));
            }
            feed(network, 300);
        }).thenIdle(40).thenExecute(() -> {
            if (on) {
                helper.assertTrue(sink.energy.getAmountAsLong() > 0, "the surplus went out");
                helper.assertTrue(generatorSink.energy.getAmountAsLong() > 0, "the generator pushed FE");
                return;
            }
            helper.assertTrue(generator.energy().stored() > 0, "the generator burns and keeps it");
            helper.assertValueEqual(sink.energy.getAmountAsLong(), 0L, "export off: nothing through the edge");
            helper.assertValueEqual(generatorSink.energy.getAmountAsLong(), 0L, "export off: no push");
            EnergyHandler handler = helper.getLevel().getCapability(Capabilities.Energy.BLOCK,
                    helper.absolutePos(NetworkGameTests.at(2)), Direction.UP);
            if (handler == null) {
                throw helper.assertionException(Component.literal("no FE on the cell"));
            }
            // Room in the cell, so taking in can be seen.
            cell.cellStore().tryConsume(1_000);
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(handler.extract(500, transaction), 0, "export off: no pull");
                helper.assertValueEqual(handler.insert(500, transaction), 500, "energy still comes in");
            }
        }).thenSucceed();
    }

    /**
     * Energy the pass moves into a cell or the controller marks their chunk unsaved every tick it flows, so a save
     * keeps it, and turns the cell's gauge: a full controller sends a producer's 2,000 MU/t into the cell, then an
     * empty one takes it.
     */
    private static void flowsMarkChanged(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        BlockPos cellPos = NetworkGameTests.at(2);
        EnergyCellBlockEntity cell = EnergyCellGameTests.cell(helper, cellPos, EnergyContent.TIER_1.get());
        ServerLevel level = helper.getLevel();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(cell.network() != null, "no network"))
                .thenExecute(() -> {
                    NetworkGameTests.power(helper, core, 10_000);
                    NetworkState network = cell.network();
                    if (network == null) {
                        throw helper.assertionException(Component.literal("no network"));
                    }
                    feed(network, 2_000);
                }).thenWaitUntil(() -> helper.assertTrue(cell.cellStore().stored() > 0, "the cell charges"))
                .thenExecute(() -> level.getChunkAt(helper.absolutePos(cellPos)).tryMarkSaved()).thenIdle(2)
                .thenExecute(() -> {
                    helper.assertTrue(level.getChunkAt(helper.absolutePos(cellPos)).isUnsaved(),
                            "the flowing cell marks its chunk");
                    helper.assertTrue(helper.getBlockState(cellPos).getValue(EnergyCellBlock.CHARGE) > 0,
                            "the gauge follows the flow");
                    NetworkGameTests.controllerEntity(helper, core).buffer().load(0);
                }).thenIdle(2).thenExecute(() -> {
                    level.getChunkAt(helper.absolutePos(core)).tryMarkSaved();
                    level.getChunkAt(helper.absolutePos(cellPos)).tryMarkSaved();
                }).thenIdle(2)
                .thenExecute(() -> helper.assertTrue(level.getChunkAt(helper.absolutePos(core)).isUnsaved(),
                        "the charging controller marks its chunk"))
                .thenSucceed();
    }

    /**
     * A controller alone merges into a larger network (a cable run with a drive), so its own storage network closes
     * after the survivor took its buffer: the buffer stays in the survivor's energy network, which charges it.
     */
    private static void mergeKeepsController(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.cables(helper, 3, 8, CableContent.STORAGE_CABLE_TIER_1.type().get());
        TestStorage.TestDevice drive = NetworkGameTests.device(helper, NetworkGameTests.at(9));
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(drive.network() != null, "no network");
            helper.assertTrue(NetworkGameTests.controllerEntity(helper, core).network() != null, "no controller net");
            helper.assertTrue(NetworkGameTests.controllerEntity(helper, core).network() != drive.network(), "apart");
        }).thenExecute(() -> NetworkGameTests.cables(helper, 2, 2, CableContent.STORAGE_CABLE_TIER_1.type().get()))
                .thenWaitUntil(() -> helper.assertTrue(
                        NetworkGameTests.controllerEntity(helper, core).network() == drive.network(), "merged"))
                .thenIdle(2).thenExecute(() -> {
                    NetworkState network = NetworkGameTests.net(helper, drive);
                    EnergyEndpoint buffer = NetworkGameTests.controllerEntity(helper, core).energyEndpoint();
                    helper.assertTrue(buffer.network().orElse(null) == energyNetwork(helper, network),
                            "the controller's buffer is in the merged network's energy network");
                    feed(network, 50);
                }).thenIdle(10)
                .thenExecute(
                        () -> helper.assertTrue(NetworkGameTests.controllerEntity(helper, core).buffer().stored() > 0,
                                "the merged network charges"))
                .thenSucceed();
    }

    /**
     * Readouts from Storage's real endpoints: the controller shows its buffer endpoint, which is in the storage
     * network's energy network; a fuel generator touching a device feeds it and shows its producer; the probe reads the
     * network on the device and on the generator, which is no storage node.
     */
    private static void readouts(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        TestStorage.TestDevice drive = NetworkGameTests.device(helper, NetworkGameTests.at(2));
        BlockPos generatorPos = NetworkGameTests.at(2).above();
        helper.setBlock(generatorPos, MachineContent.block(MachineKind.FUEL_GENERATOR));
        FuelGeneratorBlockEntity generator = helper.getBlockEntity(generatorPos, FuelGeneratorBlockEntity.class);
        MachineGameTests.insert(generator, new ItemStack(Items.COAL));
        ServerLevel level = helper.getLevel();
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(drive.network() != null, "no network");
            helper.assertTrue(generator.linkedNetwork() == drive.network(), "the generator feeds the network");
            helper.assertTrue(NetworkGameTests.net(helper, drive).storedEnergy() > 0, "the network charges");
        }).thenExecute(() -> {
            NetworkState network = NetworkGameTests.net(helper, drive);
            EnergyNetwork energy = energyNetwork(helper, network);
            ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
            EnergyEndpoint endpoint = EnergyLookups.BLOCK.find(level, helper.absolutePos(core), null);
            helper.assertTrue(endpoint == controller.energyEndpoint(), "the controller shows its buffer");
            helper.assertTrue(endpoint != null && endpoint.network().orElse(null) == energy, "in the network");
            EnergyEndpoint producer = EnergyLookups.BLOCK.find(level, helper.absolutePos(generatorPos), null);
            helper.assertTrue(producer != null && producer.network().orElse(null) == energy, "generator endpoint");
            EnergyReadout readout = energy.readout();
            helper.assertTrue(readout.supply() > 0, "supply " + readout.supply());
            helper.assertTrue(readout.capacity() >= 10_000, "capacity " + readout.capacity());
            ProbeSampler.Sample onDevice = ProbeSampler.sample(level, helper.absolutePos(NetworkGameTests.at(2)));
            helper.assertTrue(onDevice.energy() && onDevice.supply() > 0, "probe on the device: " + onDevice);
            helper.assertTrue(ProbeSampler.probeable(level, helper.absolutePos(generatorPos)), "probe binds");
            ProbeSampler.Sample onGenerator = ProbeSampler.sample(level, helper.absolutePos(generatorPos));
            helper.assertTrue(onGenerator.energy() && onGenerator.supply() == onDevice.supply(),
                    "probe on the generator: " + onGenerator);
        }).thenSucceed();
    }

    /**
     * Saves and items from before MLE item 4 at 3 FE = 2 MU: a cell's and a controller's 1,000 FE become 666 MU with
     * two thirds of an MU kept in their FE view; saving and loading again changes nothing (stamped). A wireless
     * terminal holding 1,000 FE reads 666 MU and stays at what it holds once a charger wrote it; a cell item placed
     * again holds 666 MU; FE into a cell converts at the rate.
     */
    private static void migration(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EnergyCellBlockEntity cell = EnergyCellGameTests.cell(helper, NetworkGameTests.at(3),
                EnergyContent.TIER_1.get());
        BlockPos core = NetworkGameTests.controller(helper, 1);
        ServerPlayer player = NetworkGameTests.player(level, "mu_migration");
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(cell.network() != null, "no network"))
                .thenExecute(() -> {
                    for (BlockEntity entity : new BlockEntity[]{cell,
                            NetworkGameTests.controllerEntity(helper, core)}) {
                        CompoundTag old = entity.saveWithoutMetadata(level.registryAccess());
                        old.putInt(SaveFormat.VERSION_KEY, 7);
                        old.putLong("energy", 1_000L);
                        old.remove(FeView.HELD);
                        load(level, entity, old);
                        helper.assertValueEqual(stored(entity), 666L, "1,000 FE as MU in " + entity);
                        helper.assertValueEqual(held(entity), 2L, "two thirds of an MU kept in " + entity);
                        CompoundTag saved = entity.saveWithoutMetadata(level.registryAccess());
                        helper.assertValueEqual(saved.getIntOr(SaveFormat.VERSION_KEY, 0), 8, "stamped");
                        load(level, entity, saved);
                        helper.assertValueEqual(stored(entity), 666L, "converted once in " + entity);
                        helper.assertValueEqual(held(entity), 2L, "the remainder once in " + entity);
                    }
                    // The held two thirds come in with the next FE: 1 FE more is 1/3 + 2/3 = one whole MU.
                    EnergyHandler handler = helper.getLevel().getCapability(Capabilities.Energy.BLOCK,
                            helper.absolutePos(NetworkGameTests.at(3)), Direction.UP);
                    if (handler == null) {
                        throw helper.assertionException(Component.literal("no FE on the cell"));
                    }
                    try (Transaction transaction = Transaction.openRoot()) {
                        helper.assertValueEqual(handler.insert(1, transaction), 1, "one FE");
                        transaction.commit();
                    }
                    helper.assertValueEqual(cell.cellStore().stored(), 667L, "the remainder is not lost");

                    ItemStack terminal = new ItemStack(SignalContent.WIRELESS_TERMINAL.get());
                    terminal.set(dev.silencebound.maxtech.storage.registry.ModDataComponents.WIRELESS_ENERGY.get(),
                            1_000);
                    helper.assertValueEqual(WirelessTerminalItem.energy(terminal), 666, "an old terminal's FE");
                    player.getInventory().setItem(0, terminal);
                    EnergyHandler battery = ItemAccess.forPlayerSlot(player, 0).getCapability(Capabilities.Energy.ITEM);
                    if (battery == null) {
                        throw helper.assertionException(Component.literal("no item FE"));
                    }
                    try (Transaction transaction = Transaction.openRoot()) {
                        helper.assertValueEqual(battery.insert(30, transaction), 30, "a charger's FE");
                        transaction.commit();
                    }
                    ItemStack charged = player.getInventory().getItem(0);
                    helper.assertValueEqual(WirelessTerminalItem.energy(charged), 686, "666 MU and 30 FE as 20 MU");
                    helper.assertValueEqual(charged.getOrDefault(
                            dev.silencebound.maxtech.storage.registry.ModDataComponents.WIRELESS_ENERGY.get(), 0), 686,
                            "written in MU, so it is never converted again");

                    ItemStack cellItem = new ItemStack(EnergyContent.TIER_1.item().get());
                    cellItem.set(ModDataComponents.STORED_ENERGY.get(), 1_000L);
                    EnergyCellBlockEntity placed = EnergyCellGameTests.cell(helper, NetworkGameTests.at(6),
                            EnergyContent.TIER_1.get());
                    placed.applyComponentsFromItemStack(cellItem);
                    helper.assertValueEqual(placed.cellStore().stored(), 666L, "an old cell item's FE");
                    helper.setBlock(NetworkGameTests.at(6), Blocks.AIR);
                }).thenSucceed();
    }

    private static void load(ServerLevel level, BlockEntity entity, CompoundTag tag) {
        entity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
    }

    private static long stored(BlockEntity entity) {
        if (entity instanceof EnergyCellBlockEntity cell) {
            return cell.cellStore().stored();
        }
        return ((ControllerBlockEntity) entity).buffer().stored();
    }

    private static long held(BlockEntity entity) {
        EnergyHandler view = entity instanceof EnergyCellBlockEntity cell
                ? cell.feHandler()
                : ((ControllerBlockEntity) entity).feHandler();
        return view instanceof FeView fe ? fe.held() : -1;
    }
}
