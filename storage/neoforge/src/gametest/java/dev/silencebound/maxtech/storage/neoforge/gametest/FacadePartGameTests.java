package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortPart;
import dev.silencebound.maxtech.storage.sensor.SensorContent;
import dev.silencebound.maxtech.storage.sensor.SensorPart;
import dev.silencebound.maxtech.storage.sensor.SensorWorker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Parts under a facade (B09 item 19): a facade hides a cable's parts from clicks and info, but they still hear their
 * neighbours. A port part asleep on an empty chest wakes when the chest fills, and a sensor part with polling off reads
 * the chest again when it changes, both with a stone facade over the cable. Layouts run along x at y = 1, z = 1:
 * controller, cables.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class FacadePartGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_16x12x16");
    private static final String PORT = "part_facade_port_wakes";
    private static final String SENSOR = "part_facade_sensor_reads";
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        StorageTests.function(PORT, StorageTestEnvironment.clearing(FacadePartGameTests::portWakes));
        StorageTests.function(SENSOR, StorageTestEnvironment.clearing(FacadePartGameTests::sensorReads));
    }

    private FacadePartGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        var environment = event.registerEnvironment(MaxtechStorage.id("facade_part"), StorageTestEnvironment.plain());
        StorageTests.test(event, environment, PORT, STRUCTURE, 400);
        StorageTests.test(event, environment, SENSOR, STRUCTURE, 400);
    }

    /** Puts a stone facade over the cable at {@code x}. */
    private static void facade(GameTestHelper helper, int x) {
        helper.assertTrue(helper.getBlockEntity(NetworkGameTests.at(x), ConduitBlockEntity.class)
                .setFacade(Blocks.STONE.defaultBlockState()), "no facade set");
    }

    private static <T extends CablePart> T place(GameTestHelper helper, int x, Direction face,
            dev.silencebound.maxtech.storage.part.PartType type, Class<T> kind) {
        helper.assertTrue(PartGameTests.parts(helper, x).place(face, type, null, new ItemStack(type.item())),
                "part not placed");
        CablePart part = PartGameTests.part(helper, PartGameTests.parts(helper, x), face);
        if (kind.isInstance(part)) {
            return kind.cast(part);
        }
        throw helper.assertionException(Component.literal("Wrong part: " + part));
    }

    /**
     * A powered controller, a cable with an import port part on its north face over an empty chest, a test drive; the
     * cable gets a facade. After the port has gone to sleep the chest gets 10 cobblestone, and they reach the drive.
     */
    private static void portWakes(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 10_000);
        NetworkGameTests.cables(helper, 2, 2, CableContent.STORAGE_CABLE_TIER_1.type().get());
        TestStorage.TestDevice drive = NetworkGameTests.device(helper, NetworkGameTests.at(3));
        BlockPos chestPos = NetworkGameTests.at(2).north();
        helper.setBlock(chestPos, Blocks.CHEST);
        ChestBlockEntity chest = helper.getBlockEntity(chestPos, ChestBlockEntity.class);
        place(helper, 2, Direction.NORTH, PortContent.PORT_PART, PortPart.class);
        facade(helper, 2);
        helper.startSequence().thenIdle(250).thenExecute(() -> {
            helper.assertValueEqual(drive.provider.amount(COBBLE), 0L, "moved from an empty chest");
            chest.setItem(0, new ItemStack(Items.COBBLESTONE, 10));
            chest.setChanged();
        }).thenIdle(10).thenExecute(() -> helper.assertValueEqual(drive.provider.amount(COBBLE), 10L,
                "the port under the facade did not wake on the chest's change")).thenSucceed();
    }

    /**
     * A powered controller, cables at 2 and 3, a sensor part on cable 3's east face reading a chest at 4 (polling off);
     * cable 3 gets a facade. Dirt put into the chest is read without polling.
     */
    private static void sensorReads(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 1_000_000);
        NetworkGameTests.controllerEntity(helper, controller)
                .setOwner(OwnerRecord.of(NetworkGameTests.player(helper.getLevel(), "facade_sensor_network")));
        NetworkGameTests.cables(helper, 2, 3, CableContent.STORAGE_CABLE_TIER_1.type().get());
        BlockPos chestPos = NetworkGameTests.at(4);
        helper.setBlock(chestPos, Blocks.CHEST);
        ChestBlockEntity chest = helper.getBlockEntity(chestPos, ChestBlockEntity.class);
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 1));
        chest.setChanged();
        SensorWorker sensor = place(helper, 3, Direction.EAST, SensorContent.SENSOR_PART, SensorPart.class).sensor();
        helper.startSequence()
                .thenWaitUntil(
                        () -> helper.assertValueEqual(sensor.value("item.minecraft:cobblestone"), 1L, "first read"))
                .thenExecute(() -> facade(helper, 3)).thenIdle(20).thenExecute(() -> {
                    chest.setItem(5, new ItemStack(Items.DIRT, 4));
                    chest.setChanged();
                }).thenWaitUntil(() -> helper.assertValueEqual(sensor.value("item.minecraft:dirt"), 4L,
                        "the sensor under the facade did not read the chest's change"))
                .thenSucceed();
    }
}
