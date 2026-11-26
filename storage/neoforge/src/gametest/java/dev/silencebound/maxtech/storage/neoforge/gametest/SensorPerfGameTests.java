package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.sensor.SensorBlock;
import dev.silencebound.maxtech.storage.sensor.SensorBlockEntity;
import dev.silencebound.maxtech.storage.sensor.SensorContent;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * 80 idle sensors on one network, each on a filled chest with polling off (PRD 8.1): once each has read its chest, they
 * cost nothing per tick. No ticker, and not one read in 200 ticks while nothing changes; one chest change costs exactly
 * one read of the sensor on it.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class SensorPerfGameTests {
    private static final String NAME = "perf_sensor_idle";
    private static final int COLUMNS = 40;
    private static final int ROWS = 2;

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        StorageTests.function(NAME, StorageTestEnvironment.clearing(SensorPerfGameTests::idle));
    }

    private SensorPerfGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        StorageTests.test(event,
                event.registerEnvironment(MaxtechStorage.id(NAME), StorageTestEnvironment.roomy("", "", false)), NAME,
                MaxtechStorage.id("empty_42x4x27"), 400);
    }

    private static int reads(List<SensorBlockEntity> sensors) {
        int reads = 0;
        for (SensorBlockEntity sensor : sensors) {
            reads += sensor.sensor().reads();
        }
        return reads;
    }

    /**
     * A controller at x 1; two rows of sensors (y 1 and 2) along x 2 to 41, linked to each other, each facing south at
     * a chest.
     */
    private static void idle(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 1_000_000);
        SensorGameTests.ownNetwork(helper, controller);
        List<SensorBlockEntity> sensors = new ArrayList<>(COLUMNS * ROWS);
        List<ChestBlockEntity> chests = new ArrayList<>(COLUMNS * ROWS);
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                BlockPos pos = new BlockPos(2 + column, 1 + row, 1);
                helper.setBlock(pos,
                        SensorContent.SENSOR.get().defaultBlockState().setValue(SensorBlock.FACING, Direction.SOUTH));
                sensors.add(helper.getBlockEntity(pos, SensorBlockEntity.class));
                helper.setBlock(pos.south(), Blocks.CHEST);
                ChestBlockEntity chest = helper.getBlockEntity(pos.south(), ChestBlockEntity.class);
                chest.setItem(0, new ItemStack(Items.COBBLESTONE, 1 + column));
                chest.setChanged();
                chests.add(chest);
            }
        }
        int[] reads = new int[1];
        helper.startSequence().thenWaitUntil(() -> {
            for (SensorBlockEntity sensor : sensors) {
                helper.assertTrue(sensor.sensor().value("items.total") > 0, "a sensor has not read its chest");
            }
        }).thenIdle(20).thenExecute(() -> {
            for (SensorBlockEntity sensor : sensors) {
                helper.assertTrue(
                        sensor.getBlockState().getTicker(helper.getLevel(), SensorContent.BLOCK_ENTITY.get()) == null,
                        "a sensor ticks");
            }
            reads[0] = reads(sensors);
        }).thenIdle(200).thenExecute(() -> {
            helper.assertValueEqual(reads(sensors), reads[0], "reads of idle sensors in 200 ticks");
            ChestBlockEntity chest = chests.get(7);
            chest.setItem(1, new ItemStack(Items.DIRT, 2));
            chest.setChanged();
        }).thenIdle(5)
                .thenExecute(
                        () -> helper.assertValueEqual(reads(sensors), reads[0] + 1, "reads after one chest changed"))
                .thenSucceed();
    }
}
