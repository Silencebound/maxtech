package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.cell.CellInventory;
import dev.silencebound.maxtech.storage.creative.CreativeEnergyContent;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.energy.EnergyCellBlockEntity;
import dev.silencebound.maxtech.storage.energy.EnergyContent;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The network statistics display panels read (M6c area D): FE received and spent as committed running totals, and the
 * byte use of the network's cells, summed when asked.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class NetworkStatsGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);
    private static final List<String> TESTS = new ArrayList<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("network_stats_energy_received", NetworkStatsGameTests::energyReceived);
        register("network_stats_energy_spent", NetworkStatsGameTests::energySpent);
        register("network_stats_bytes", NetworkStatsGameTests::bytes);
        register("network_stats_energy_cell_input", NetworkStatsGameTests::energyCellInput);
    }

    private NetworkStatsGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        TESTS.add(name);
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("network_stats"),
                StorageTestEnvironment.plain());
        for (String name : TESTS) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, STRUCTURE, 200, 0, true)));
        }
    }

    /**
     * FE inserted through the controller's input and through another device's input counts once each; a rolled-back
     * insert counts nothing, and the controller's own receive (no FE input) counts nothing either.
     */
    private static void energyReceived(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        TestStorage.TestDevice device = NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.net(helper, device)).thenExecute(() -> {
            NetworkState network = NetworkGameTests.net(helper, device);
            helper.assertValueEqual(insertFe(helper, controller, 500, true), 500, "into the controller");
            helper.assertValueEqual(network.energyReceived(), 500L, "received through the controller");
            helper.assertValueEqual(insertFe(helper, NetworkGameTests.at(2), 300, true), 300, "into the device");
            helper.assertValueEqual(network.energyReceived(), 800L, "received through a device");
            helper.assertValueEqual(insertFe(helper, NetworkGameTests.at(2), 100, false), 100, "rolled back");
            helper.assertValueEqual(network.energyReceived(), 800L, "after a rollback");
            NetworkGameTests.power(helper, controller, 50);
            helper.assertValueEqual(network.energyReceived(), 800L, "after the controller's own receive");
            helper.assertValueEqual(network.storedEnergy(), 850L, "stored");
        }).thenSucceed();
    }

    /**
     * Each committed operation adds its cost (4 FE whatever it moves); a rolled-back one is refunded and not counted.
     * With a creative source the spending still counts while nothing is received.
     */
    private static void energySpent(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        TestStorage.TestDevice device = NetworkGameTests.device(helper, NetworkGameTests.at(2));
        TestStorage.TestDevice creativeDevice = NetworkGameTests.device(helper, NetworkGameTests.at(10));
        helper.setBlock(NetworkGameTests.at(11), CreativeEnergyContent.CREATIVE_ENERGY_SOURCE.get());
        helper.startSequence().thenWaitUntil(() -> {
            NetworkGameTests.net(helper, device);
            NetworkGameTests.net(helper, creativeDevice);
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, controller, 1000);
            NetworkState network = NetworkGameTests.net(helper, device);
            helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 10), 10L, "inserted");
            helper.assertValueEqual(network.energySpent(), 4L, "spent on 10 items");
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(network.extract(COBBLE, 4, transaction), 4L, "simulated extract");
            }
            helper.assertValueEqual(network.energySpent(), 4L, "after a rolled-back extract");
            NetworkState creative = NetworkGameTests.net(helper, creativeDevice);
            helper.assertValueEqual(NetworkGameTests.insert(creative, COBBLE, 20), 20L, "inserted with creative");
            helper.assertValueEqual(creative.energySpent(), 4L, "spent with a creative source");
            helper.assertValueEqual(creative.energyReceived(), 0L, "received with a creative source");
        }).thenSucceed();
    }

    /**
     * Used and total bytes are the sums over the network's cells; a provider that does not count bytes (the test
     * device) adds nothing, and taking a cell out drops its share.
     */
    private static void bytes(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        DriveBlockEntity drive = CellGameTests.drive(helper, NetworkGameTests.at(2));
        NetworkGameTests.device(helper, NetworkGameTests.at(3));
        drive.insertCell(CellGameTests.cell(CellContent.ITEM_TYPE.id(), 1));
        drive.insertCell(CellGameTests.cell(CellContent.ITEM_TYPE.id(), 2));
        helper.startSequence().thenWaitUntil(() -> {
            CellGameTests.mounted(helper, drive, 1);
            helper.assertTrue(drive.network() != null, "no network");
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, controller, 100_000);
            NetworkState network = NetworkGameTests.state(helper, controller);
            CellInventory first = CellGameTests.mounted(helper, drive, 0);
            CellInventory second = CellGameTests.mounted(helper, drive, 1);
            helper.assertValueEqual(network.totalBytes(), first.totalBytes() + second.totalBytes(), "total bytes");
            helper.assertTrue(network.totalBytes() > 0, "no capacity");
            helper.assertValueEqual(network.usedBytes(), 0L, "used bytes when empty");
            // Cells come first by priority, then the test device; 9000 fills the first cell and spills over.
            helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 9000), 9000L, "inserted");
            long used = first.usedBytes() + second.usedBytes();
            helper.assertTrue(used > 0, "cells hold nothing");
            helper.assertValueEqual(network.usedBytes(), used, "used bytes");
            ItemStack out = drive.removeCell();
            CellInventory left = CellGameTests.mounted(helper, drive, 0);
            helper.assertValueEqual(network.usedBytes(), left.usedBytes(), "used bytes with one cell");
            helper.assertValueEqual(network.totalBytes(), left.totalBytes(), "total bytes with one cell");
            drive.insertCell(out);
        }).thenSucceed();
    }

    /**
     * FE a neighbour puts into an energy cell counts as received, once; FE the controller passes on to the cells
     * because its buffer is full counts once at the controller, not again at the cell.
     */
    private static void energyCellInput(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        EnergyCellBlockEntity cell = EnergyCellGameTests.cell(helper, NetworkGameTests.at(2),
                EnergyContent.TIER_1.get());
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(cell.network() != null, "no network"))
                .thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, controller);
                    helper.assertValueEqual(insertFe(helper, NetworkGameTests.at(2), 500, true), 500, "into the cell");
                    helper.assertValueEqual(network.energyReceived(), 500L, "received through the cell");
                    helper.assertValueEqual(insertFe(helper, NetworkGameTests.at(2), 100, false), 100, "rolled back");
                    helper.assertValueEqual(network.energyReceived(), 500L, "after a rollback");
                    NetworkGameTests.power(helper, controller,
                            NetworkGameTests.controllerEntity(helper, controller).buffer().capacity() - 100);
                    long before = cell.cellStore().stored();
                    helper.assertValueEqual(insertFe(helper, controller, 500, true), 500, "into the controller");
                    helper.assertValueEqual(cell.cellStore().stored() - before, 400L, "surplus in the cell");
                    helper.assertValueEqual(network.energyReceived(), 1000L, "received, the surplus once");
                }).thenSucceed();
    }

    private static int insertFe(GameTestHelper helper, BlockPos pos, int amount, boolean commit) {
        EnergyHandler handler = helper.getLevel().getCapability(Capabilities.Energy.BLOCK, helper.absolutePos(pos),
                Direction.UP);
        if (handler == null) {
            throw helper.assertionException(Component.literal("No FE input at " + pos.toShortString()));
        }
        try (Transaction transaction = Transaction.openRoot()) {
            int inserted = handler.insert(amount, transaction);
            if (commit) {
                transaction.commit();
            }
            return inserted;
        }
    }
}
