package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.display.DisplayContent;
import dev.silencebound.maxtech.storage.display.DisplayPanelBlockEntity;
import dev.silencebound.maxtech.storage.display.DisplayPart;
import dev.silencebound.maxtech.storage.energy.EnergyCellBlockEntity;
import dev.silencebound.maxtech.storage.energy.EnergyContent;
import dev.silencebound.maxtech.storage.machine.FuelGeneratorBlockEntity;
import dev.silencebound.maxtech.storage.machine.MachineContent;
import dev.silencebound.maxtech.storage.machine.MachineKind;
import dev.silencebound.maxtech.storage.network.DeviceLink;
import dev.silencebound.maxtech.storage.network.LinkLook;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.condition.ConditionContent;
import dev.silencebound.maxtech.storage.condition.ConditionSettings;
import dev.silencebound.maxtech.storage.condition.ConditionSwitchBlockEntity;
import dev.silencebound.maxtech.storage.condition.ConditionType;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.ServiceDeviceBlockEntity;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuCoreBlockEntity;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.network.NetworkService;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.research.ResearchContent;
import dev.silencebound.maxtech.storage.research.ResearchTerminalBlockEntity;
import dev.silencebound.maxtech.storage.sensor.SensorBlock;
import dev.silencebound.maxtech.storage.sensor.SensorBlockEntity;
import dev.silencebound.maxtech.storage.sensor.SensorContent;
import dev.silencebound.maxtech.storage.sensor.SensorPart;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Energy, display, sensing and support blocks use no channel (owner 2026-10-17, like AE2's energy cells). On a network
 * whose only channel (a root link of 1) is held, each still joins and works: an energy cell, a fuel generator, a
 * display panel and part (energy stored and supplied); a network anchor, a security module, research terminal block and
 * part, sensor block and part, condition switch block and part and a crafting CPU (anchoring, security, sensor
 * readings, the switch's output, the CPU in the job queue). Their fronts show online, and a device placed afterwards
 * still gets the channel while the next one finds none.
 */
// Networks are compared by identity.
@SuppressWarnings("ReferenceEquality")
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class ChannelFreeGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final String FULL_NETWORK = "channel_free_on_full_network";
    private static final String SUPPORT = "channel_free_support_on_full_network";
    private static final String LEAVES = "channel_free_leaves_full_network";
    private static final String DEVICE_LIMIT = "channel_free_past_device_limit";
    /** The cable whose faces carry the support test's parts. */
    private static final int PART_CABLE = 10;
    private static final BlockPos CELL = new BlockPos(4, 1, 0);
    private static final BlockPos GENERATOR = CELL.above();
    private static final BlockPos PANEL = new BlockPos(5, 1, 0);
    private static final BlockPos FIRST = new BlockPos(3, 1, 0);
    private static final BlockPos SECOND = new BlockPos(3, 1, 2);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        StorageTests.function(FULL_NETWORK, StorageTestEnvironment.clearing(ChannelFreeGameTests::fullNetwork));
        StorageTests.function(SUPPORT, StorageTestEnvironment.clearing(ChannelFreeGameTests::support));
        StorageTests.function(LEAVES, StorageTestEnvironment.clearing(ChannelFreeGameTests::leaves));
        StorageTests.function(DEVICE_LIMIT, StorageTestEnvironment.clearing(ChannelFreeGameTests::deviceLimit));
    }

    private ChannelFreeGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        // One root link of one channel: the network's only channel.
        Holder<TestEnvironmentDefinition<?>> oneChannel = event.registerEnvironment(MaxtechStorage.id(FULL_NETWORK),
                new StorageTestEnvironment("network.channels.rootLink", "1", false));
        StorageTests.test(event, oneChannel, FULL_NETWORK, STRUCTURE, 600);
        StorageTests.test(event, oneChannel, SUPPORT, STRUCTURE, 600);
        StorageTests.test(event, oneChannel, LEAVES, STRUCTURE, 400);
        // A team may hold one device: the device limit, not channels, is what fills.
        StorageTests.test(event, event.registerEnvironment(MaxtechStorage.id(DEVICE_LIMIT),
                new StorageTestEnvironment("caps.devicesPerTeam", "1", false)), DEVICE_LIMIT, STRUCTURE, 400);
    }

    private static void fullNetwork(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.cables(helper, 2, 6, CableContent.STORAGE_CABLE_TIER_1.type().get());
        EnergyCellBlockEntity cell = EnergyCellGameTests.cell(helper, CELL, EnergyContent.TIER_1.get());
        helper.setBlock(GENERATOR, MachineContent.block(MachineKind.FUEL_GENERATOR));
        FuelGeneratorBlockEntity generator = helper.getBlockEntity(GENERATOR, FuelGeneratorBlockEntity.class);
        DisplayPanelBlockEntity panel = DisplayGameTests.panel(helper, PANEL, Direction.NORTH);
        helper.assertTrue(
                PartGameTests.parts(helper, 6).place(Direction.NORTH, DisplayContent.PANEL_PART, null, ItemStack.EMPTY),
                "display part not placed");
        TestStorage.TestDevice[] devices = new TestStorage.TestDevice[2];
        long[] before = new long[1];
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertTrue(cell.network() == network, "the cell joined");
            helper.assertTrue(generator.linkedNetwork() == network, "the generator joined");
            helper.assertTrue(panel.network() == network, "the panel joined");
            helper.assertTrue(part(helper).deviceLink().network() == network, "the part joined");
        }).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertValueEqual(network.channelsHeld(), 0, "channels held by energy blocks and displays");
            NetworkGameTests.power(helper, controller,
                    NetworkGameTests.controllerEntity(helper, controller).buffer().capacity());
            before[0] = cell.cellStore().stored();
            MachineGameTests.insert(generator, new ItemStack(Items.COAL));
            devices[0] = NetworkGameTests.device(helper, FIRST);
        }).thenWaitUntil(() -> helper.assertTrue(devices[0].deviceLink().holdsChannel(), "the device's channel"))
                .thenExecute(() -> devices[1] = NetworkGameTests.device(helper, SECOND))
                .thenWaitUntil(() -> helper.assertTrue(devices[1].deviceLink().offlineBy() != null,
                        "the next device finds the channel taken"))
                .thenWaitUntil(() -> {
                    helper.assertTrue(cell.cellStore().stored() > before[0], "the generator charges the cell");
                    helper.assertValueEqual(LinkLook.ONLINE, part(helper).deviceLink().linkLook(), "the part's front");
                }).thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, controller);
                    helper.assertValueEqual(network.channelsHeld(), 1, "channels held");
                    for (DeviceLink link : new DeviceLink[]{cell.deviceLink(), generator.device().deviceLink(),
                            panel.deviceLink(), part(helper).deviceLink()}) {
                        helper.assertFalse(link.holdsChannel(), "holds no channel");
                        helper.assertTrue(link.offlineBy() == null && link.refusedBy() == null, "never turned away");
                        helper.assertTrue(link.network() == network, "still a member");
                    }
                }).thenSucceed();
    }

    private static void support(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 1_000_000);
        SensorGameTests.ownNetwork(helper, controller);
        NetworkGameTests.cables(helper, 2, PART_CABLE, CableContent.STORAGE_CABLE_TIER_1.type().get());
        BlockPos anchor = new BlockPos(4, 1, 0);
        BlockPos security = new BlockPos(5, 1, 0);
        BlockPos research = new BlockPos(6, 1, 0);
        BlockPos sensor = new BlockPos(7, 1, 0);
        BlockPos condition = new BlockPos(8, 1, 0);
        BlockPos lamp = condition.above();
        BlockPos cpu = new BlockPos(9, 1, 0);
        helper.setBlock(anchor, ExpansionContent.NETWORK_ANCHOR.get());
        helper.setBlock(security, ExpansionContent.SECURITY_MODULE.get());
        helper.setBlock(research, ResearchContent.TERMINAL.get());
        // Facing up at a chest of five dirt.
        helper.setBlock(sensor.above(), Blocks.CHEST);
        helper.getBlockEntity(sensor.above(), ChestBlockEntity.class).setItem(0, new ItemStack(Items.DIRT, 5));
        helper.setBlock(sensor,
                SensorContent.SENSOR.get().defaultBlockState().setValue(SensorBlock.FACING, Direction.UP));
        helper.setBlock(condition, ConditionContent.SWITCH.get());
        helper.setBlock(lamp, Blocks.REDSTONE_LAMP);
        helper.getBlockEntity(condition, ConditionSwitchBlockEntity.class).worker()
                .setSettings(ConditionSettings.DEFAULT.withType(ConditionType.POWER).withAbove(true).withThreshold(50));
        helper.setBlock(cpu, CpuContent.CORE_1.get());
        helper.setBlock(cpu.above(), CpuContent.STORAGE.get());
        CableParts parts = PartGameTests.parts(helper, PART_CABLE);
        helper.assertTrue(parts.place(Direction.NORTH, ResearchContent.TERMINAL_PART, null, ItemStack.EMPTY),
                "research part not placed");
        // Facing south at a chest of three dirt.
        BlockPos partChest = NetworkGameTests.at(PART_CABLE).south();
        helper.setBlock(partChest, Blocks.CHEST);
        helper.getBlockEntity(partChest, ChestBlockEntity.class).setItem(0, new ItemStack(Items.DIRT, 3));
        helper.assertTrue(parts.place(Direction.SOUTH, SensorContent.SENSOR_PART, null,
                new ItemStack(SensorContent.SENSOR_PART_ITEM.get())), "sensor part not placed");
        helper.assertTrue(parts.place(Direction.UP, ConditionContent.SWITCH_PART, null, ItemStack.EMPTY),
                "switch part not placed");
        DeviceLink[] links = {helper.getBlockEntity(anchor, ServiceDeviceBlockEntity.class).deviceLink(),
                helper.getBlockEntity(security, ServiceDeviceBlockEntity.class).deviceLink(),
                helper.getBlockEntity(research, ResearchTerminalBlockEntity.class).deviceLink(),
                helper.getBlockEntity(sensor, SensorBlockEntity.class).deviceLink(),
                helper.getBlockEntity(condition, ConditionSwitchBlockEntity.class).deviceLink(),
                helper.getBlockEntity(cpu, CpuCoreBlockEntity.class).deviceLink(),
                partOn(helper, Direction.NORTH).deviceLink(), partOn(helper, Direction.SOUTH).deviceLink(),
                partOn(helper, Direction.UP).deviceLink()};
        TestStorage.TestDevice[] devices = new TestStorage.TestDevice[2];
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            for (int i = 0; i < links.length; i++) {
                helper.assertTrue(links[i].network() == network, "member " + i);
            }
        }).thenExecute(() -> {
            helper.assertValueEqual(NetworkGameTests.state(helper, controller).channelsHeld(), 0,
                    "channels held by support blocks");
            devices[0] = NetworkGameTests.device(helper, FIRST);
        }).thenWaitUntil(() -> helper.assertTrue(devices[0].deviceLink().holdsChannel(), "the device's channel"))
                .thenExecute(() -> devices[1] = NetworkGameTests.device(helper, SECOND))
                .thenWaitUntil(() -> helper.assertTrue(devices[1].deviceLink().offlineBy() != null,
                        "the next device finds the channel taken"))
                .thenWaitUntil(() -> {
                    NetworkState network = NetworkGameTests.state(helper, controller);
                    helper.assertTrue(NetworkGameTests.controllerEntity(helper, controller).anchored(), "anchored");
                    helper.assertValueEqual(network.serviceCount(NetworkService.SECURITY), 1, "security module");
                    helper.assertValueEqual(
                            helper.getBlockEntity(sensor, SensorBlockEntity.class).sensor().value("items.total"), 5L,
                            "the sensor block reads");
                    if (!(partOn(helper, Direction.SOUTH) instanceof SensorPart sensorPart)) {
                        throw helper.assertionException(Component.literal("Not a sensor part"));
                    }
                    helper.assertValueEqual(sensorPart.sensor().value("items.total"), 3L, "the sensor part reads");
                    helper.assertValueEqual(helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT), true,
                            "the switch's output");
                    helper.assertValueEqual(JobQueue.cpus(network), 1, "the CPU in the job queue");
                    for (int i = 0; i < links.length; i++) {
                        helper.assertValueEqual(LinkLook.ONLINE, links[i].linkLook(), "front " + i);
                    }
                }).thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, controller);
                    helper.assertValueEqual(network.channelsHeld(), 1, "channels held");
                    for (int i = 0; i < links.length; i++) {
                        helper.assertFalse(links[i].holdsChannel(), "holds no channel " + i);
                        helper.assertTrue(links[i].offlineBy() == null && links[i].refusedBy() == null,
                                "never turned away " + i);
                    }
                }).thenSucceed();
    }

    /**
     * A channel-free device leaving a network whose only channel is held: the energy cell goes, its store leaves the
     * pool, and the channel count, the limited devices and the offline device stay as they were; once the device
     * holding the channel breaks, the offline one gets it.
     */
    private static void leaves(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.cables(helper, 2, 6, CableContent.STORAGE_CABLE_TIER_1.type().get());
        EnergyCellBlockEntity cell = EnergyCellGameTests.cell(helper, CELL, EnergyContent.TIER_1.get());
        TestStorage.TestDevice[] devices = new TestStorage.TestDevice[2];
        int[] limited = new int[1];
        helper.startSequence().thenWaitUntil(() -> helper
                .assertTrue(cell.network() == NetworkGameTests.state(helper, controller), "the cell joined"))
                .thenExecute(() -> {
                    devices[0] = NetworkGameTests.device(helper, FIRST);
                }).thenWaitUntil(() -> helper.assertTrue(devices[0].deviceLink().holdsChannel(), "the channel"))
                .thenExecute(() -> devices[1] = NetworkGameTests.device(helper, SECOND))
                .thenWaitUntil(() -> helper.assertTrue(devices[1].deviceLink().offlineBy() != null, "offline"))
                .thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, controller);
                    limited[0] = network.limitedDevices();
                    helper.assertTrue(network.energyCells().capacity() > 0, "the cell in the pool");
                    helper.destroyBlock(CELL);
                }).thenIdle(10).thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, controller);
                    helper.assertTrue(cell.deviceLink().network() == null, "the cell left");
                    helper.assertValueEqual(network.energyCells().capacity(), 0L, "the pool without the cell");
                    helper.assertValueEqual(network.channelsHeld(), 1, "channels held after the cell left");
                    helper.assertValueEqual(network.limitedDevices(), limited[0], "limited devices");
                    helper.assertTrue(devices[0].deviceLink().holdsChannel(), "the device keeps its channel");
                    helper.assertTrue(devices[1].deviceLink().offlineBy() == network, "still offline");
                    helper.destroyBlock(FIRST);
                }).thenWaitUntil(() -> helper.assertTrue(devices[1].deviceLink().holdsChannel(),
                        "the offline device gets the freed channel"))
                .thenSucceed();
    }

    /**
     * With a team device cap of 1, an owned network holds one test device and turns the other away; an energy cell and
     * a sensor still join past the full limit and are never refused. Once the member device breaks, the refused one
     * gets its room.
     */
    private static void deviceLimit(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 1_000_000);
        NetworkGameTests.cables(helper, 2, 6, CableContent.STORAGE_CABLE_TIER_1.type().get());
        TestStorage.TestDevice[] devices = new TestStorage.TestDevice[2];
        EnergyCellBlockEntity[] cell = new EnergyCellBlockEntity[1];
        BlockPos sensor = new BlockPos(5, 1, 0);
        helper.startSequence().thenIdle(2).thenExecute(() -> {
            own(helper, controller);
            devices[0] = NetworkGameTests.device(helper, FIRST);
            own(helper, FIRST);
        }).thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertTrue(network.capTeam() != null, "the network is capped");
            helper.assertTrue(devices[0].network() == network, "the first device joined");
        }).thenExecute(() -> {
            devices[1] = NetworkGameTests.device(helper, SECOND);
            own(helper, SECOND);
            cell[0] = EnergyCellGameTests.cell(helper, CELL, EnergyContent.TIER_1.get());
            own(helper, CELL);
            helper.setBlock(sensor,
                    SensorContent.SENSOR.get().defaultBlockState().setValue(SensorBlock.FACING, Direction.NORTH));
            own(helper, sensor);
        }).thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertTrue(devices[1].deviceLink().refusedBy() == network, "the second device is refused");
            helper.assertTrue(cell[0].network() == network, "the cell joined past the limit");
            helper.assertTrue(helper.getBlockEntity(sensor, SensorBlockEntity.class).network() == network,
                    "the sensor joined past the limit");
        }).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertValueEqual(network.limitedDevices(), 1, "limited devices");
            helper.assertTrue(cell[0].deviceLink().refusedBy() == null, "the cell is never refused");
            helper.destroyBlock(FIRST);
        }).thenWaitUntil(() -> helper.assertTrue(devices[1].network() == NetworkGameTests.state(helper, controller),
                "the refused device got room")).thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, controller);
                    helper.assertValueEqual(network.limitedDevices(), 1, "limited devices after");
                    helper.assertTrue(cell[0].network() == network, "the cell stays");
                }).thenSucceed();
    }

    private static void own(GameTestHelper helper, BlockPos pos) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "channel_free_owner");
        if (helper.getBlockEntity(pos, BlockEntity.class) instanceof StorageDeviceBlockEntity device) {
            device.setOwner(OwnerRecord.of(owner));
        }
    }

    private static CablePart partOn(GameTestHelper helper, Direction face) {
        return PartGameTests.part(helper, PartGameTests.parts(helper, PART_CABLE), face);
    }

    private static DisplayPart part(GameTestHelper helper) {
        if (PartGameTests.part(helper, PartGameTests.parts(helper, 6), Direction.NORTH) instanceof DisplayPart part) {
            return part;
        }
        throw helper.assertionException(Component.literal("Not a display part"));
    }
}
