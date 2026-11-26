package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.logic.LogicEvaluator;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.RunTrace;
import dev.silencebound.maxtech.storage.logic.controller.LogicContent;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.logic.controller.TeamBudget;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.sensor.SensorBlock;
import dev.silencebound.maxtech.storage.sensor.SensorBlockEntity;
import dev.silencebound.maxtech.storage.sensor.SensorContent;
import dev.silencebound.maxtech.storage.sensor.SensorMenu;
import dev.silencebound.maxtech.storage.sensor.SensorPoll;
import dev.silencebound.maxtech.storage.sensor.SensorSettings;
import dev.silencebound.maxtech.storage.sensor.SensorTexts;
import dev.silencebound.maxtech.storage.sensor.SensorUnit;
import dev.silencebound.maxtech.storage.sensor.SensorValue;
import dev.silencebound.maxtech.storage.sensor.SensorWorker;
import dev.silencebound.maxtech.storage.sensor.reader.PackReaders;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

/**
 * Pack readers and the new automatic values (M7L2): slots on a chest, tanks on a two-tank block, the furnace's lit
 * state with Storage's built-in furnace reader, the test readers on {@link SensorTestContent}'s tank (numbers, scale,
 * yes/no, text, list positions, a missing path shown as "value not found" on the screen, in the info lines and in the
 * logic why line), the saved-data read's cost and minimum interval, and {@code /reload} replacing a reader through a
 * world datapack (in its own environment, so no other batch sees the reload).
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class SensorReaderGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_16x12x16");
    private static final List<String> NAMES = new ArrayList<>();
    private static final String RELOAD = "sensor_reader_reload_replaces";
    private static final String TANK_READER = "maxtech_storage:test/sensor_tank";
    private static final String PACK = "maxtech_reader_test";

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        SensorTestContent.init();
        register("sensor_reader_chest_slots", SensorReaderGameTests::chestSlots);
        register("sensor_reader_tanks", SensorReaderGameTests::tanks);
        register("sensor_reader_furnace", SensorReaderGameTests::furnace);
        register("sensor_reader_values", SensorReaderGameTests::values);
        register("sensor_reader_interval_and_cost", SensorReaderGameTests::intervalAndCost);
        register("sensor_reader_logic_why", SensorReaderGameTests::logicWhy);
        StorageTests.function(RELOAD, StorageTestEnvironment.clearing(SensorReaderGameTests::reload));
    }

    private SensorReaderGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("sensor_readers"), StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 400);
        }
        Holder<TestEnvironmentDefinition<?>> reload = event
                .registerEnvironment(MaxtechStorage.id("sensor_reader_reload"), StorageTestEnvironment.plain());
        // Reloads take wall-clock time while test ticks run unthrottled, so this one gets many ticks.
        StorageTests.test(event, reload, RELOAD, STRUCTURE, 6000);
    }

    /** A powered, owned controller at x 1 and a sensor block at x 2 facing east, at the target at x 3. */
    private static SensorBlockEntity sensor(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 1_000_000);
        SensorGameTests.ownNetwork(helper, controller);
        helper.setBlock(NetworkGameTests.at(2),
                SensorContent.SENSOR.get().defaultBlockState().setValue(SensorBlock.FACING, Direction.EAST));
        return helper.getBlockEntity(NetworkGameTests.at(2), SensorBlockEntity.class);
    }

    /** The test tank at x 3. */
    private static SensorTestContent.Tank tank(GameTestHelper helper) {
        helper.setBlock(NetworkGameTests.at(3), SensorTestContent.TANK.get());
        return helper.getBlockEntity(NetworkGameTests.at(3), SensorTestContent.Tank.class);
    }

    private static void assertValue(GameTestHelper helper, SensorWorker sensor, String id, long expected) {
        helper.assertValueEqual(sensor.value(id), expected, id);
    }

    private static SensorValue listed(GameTestHelper helper, List<SensorValue> values, String id) {
        for (SensorValue value : values) {
            if (value.id().equals(id)) {
                return value;
            }
        }
        throw helper.assertionException(Component.literal(id + " is not listed"));
    }

    private static List<String> ids(List<SensorValue> values) {
        List<String> ids = new ArrayList<>();
        for (SensorValue value : values) {
            ids.add(value.id());
        }
        return ids;
    }

    /** A chest's slots: used and free, listed after the totals. */
    private static void chestSlots(GameTestHelper helper) {
        SensorWorker sensor = sensor(helper).sensor();
        BlockPos pos = NetworkGameTests.at(3);
        helper.setBlock(pos, Blocks.CHEST);
        ChestBlockEntity chest = helper.getBlockEntity(pos, ChestBlockEntity.class);
        chest.setItem(0, new ItemStack(Items.DIRT, 5));
        chest.setItem(4, new ItemStack(Items.COBBLESTONE, 64));
        chest.setItem(9, new ItemStack(Items.COBBLESTONE, 1));
        chest.setChanged();
        helper.startSequence().thenWaitUntil(() -> assertValue(helper, sensor, "items.total", 70)).thenExecute(() -> {
            assertValue(helper, sensor, "items.slots_used", 3);
            assertValue(helper, sensor, "items.slots_free", 24);
            List<String> ids = ids(sensor.values());
            int total = ids.indexOf("items.total");
            helper.assertValueEqual(ids.subList(total, total + 4),
                    List.of("items.total", "items.capacity", "items.slots_used", "items.slots_free"), "slot rows");
            helper.assertFalse(ids.contains("tank.1.amount"), "a chest has no tanks");
            helper.assertFalse(ids.contains("data.level"), "a chest has no reader");
        }).thenSucceed();
    }

    /** A block with two tanks lists each tank's amount and capacity next to the totals. */
    private static void tanks(GameTestHelper helper) {
        SensorWorker sensor = sensor(helper).sensor();
        SensorTestContent.Tank tank = tank(helper);
        tank.tanks.set(0, FluidResource.of(Fluids.WATER), 1_000);
        tank.tanks.set(1, FluidResource.of(Fluids.LAVA), 500);
        tank.setChanged();
        helper.startSequence().thenWaitUntil(() -> assertValue(helper, sensor, "fluids.total", 1_500))
                .thenExecute(() -> {
                    assertValue(helper, sensor, "tank.1.amount", 1_000);
                    assertValue(helper, sensor, "tank.2.amount", 500);
                    assertValue(helper, sensor, "tank.2.capacity", 8_000);
                    assertValue(helper, sensor, "tank.3.amount", 0);
                    List<String> ids = ids(sensor.values());
                    helper.assertTrue(ids.contains("tank.2.capacity"), "tank rows: " + ids);
                    helper.assertFalse(ids.contains("tank.3.amount"), "a third tank");
                }).thenSucceed();
    }

    /**
     * A burning furnace: its {@code lit} state, and Storage's built-in furnace reader reading its saved data (cook and
     * burn times), polled once a second since cooking tells no neighbour.
     */
    private static void furnace(GameTestHelper helper) {
        SensorWorker sensor = sensor(helper).sensor();
        BlockPos pos = NetworkGameTests.at(3);
        helper.setBlock(pos, Blocks.FURNACE);
        FurnaceBlockEntity furnace = helper.getBlockEntity(pos, FurnaceBlockEntity.class);
        furnace.setItem(0, new ItemStack(Items.RAW_IRON, 4));
        furnace.setItem(1, new ItemStack(Items.COAL, 2));
        furnace.setChanged();
        sensor.setSettings(SensorSettings.DEFAULT.withPoll(SensorPoll.ONE_SECOND));
        helper.startSequence().thenWaitUntil(() -> assertValue(helper, sensor, "state.lit", 1))
                .thenWaitUntil(() -> assertValue(helper, sensor, "data.cook_total", 200)).thenExecute(() -> {
                    assertValue(helper, sensor, "data.burn_total", 1_600);
                    helper.assertTrue(sensor.value("data.burn_left") > 0, "burn time left");
                    SensorValue cook = listed(helper, sensor.values(), "data.cook_total");
                    helper.assertValueEqual(cook.group(), "maxtech_storage:minecraft/furnace", "reader");
                    helper.assertValueEqual(cook.text(), "ticks", "unit");
                    helper.assertValueEqual(SensorTexts.formatValue(cook).getString(), "200 ticks", "shown");
                }).thenSucceed();
    }

    /**
     * The test readers on the test tank: number, scaled number, yes/no, text and a list position, grouped by reader
     * after the automatic values; a missing path reads 0 and shows "value not found" on the screen and in the info
     * lines; a reader whose condition fails and a broken file give nothing, and the broken file stops no other.
     */
    private static void values(GameTestHelper helper) {
        SensorBlockEntity entity = sensor(helper);
        SensorWorker sensor = entity.sensor();
        SensorTestContent.Tank tank = tank(helper);
        tank.fill = 7;
        tank.stored = 12_345;
        tank.active = true;
        tank.mode = "turbo";
        tank.setChanged();
        helper.startSequence().thenWaitUntil(() -> assertValue(helper, sensor, "data.level", 7)).thenExecute(() -> {
            assertValue(helper, sensor, "data.stored", 12);
            assertValue(helper, sensor, "data.active", 1);
            assertValue(helper, sensor, "data.second_int", 1);
            assertValue(helper, sensor, "data.missing", 0);
            helper.assertTrue(sensor.found("data.level"), "level found");
            helper.assertFalse(sensor.found("data.missing"), "missing found");
            helper.assertFalse(sensor.found("data.never_named"), "a name no reader gives");
            helper.assertTrue(sensor.found("fluids.total"), "automatic values are found");
            helper.assertFalse(entity.logicValueFound("data.missing"), "logic sees it missing");

            ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "reader_screen");
            SensorMenu menu = SensorMenu.server(3, player, entity);
            menu.broadcastChanges();
            List<SensorValue> values = menu.values();
            List<String> ids = ids(values);
            int first = ids.indexOf("data.level");
            helper.assertTrue(first > ids.indexOf("fluids.capacity"), "reader values after the automatic ones");
            helper.assertValueEqual(listed(helper, values, "data.level").group(), TANK_READER, "group");
            helper.assertValueEqual(listed(helper, values, "fluids.total").group(), "", "automatic group");
            SensorValue mode = listed(helper, values, "data.mode");
            helper.assertValueEqual(mode.unit(), SensorUnit.TEXT, "text unit");
            helper.assertValueEqual(SensorTexts.formatValue(mode).getString(), "turbo", "text shown");
            helper.assertValueEqual(SensorTexts.formatValue(listed(helper, values, "data.stored")).getString(),
                    "12 kFE", "unit shown");
            Component active = SensorTexts.formatValue(listed(helper, values, "data.active"));
            helper.assertTrue(
                    active.getContents() instanceof TranslatableContents yes && yes.getKey().equals(SensorTexts.YES),
                    "yes shown");
            SensorValue missing = listed(helper, values, "data.missing");
            helper.assertFalse(missing.found(), "missing found on the screen");
            Component shown = SensorTexts.formatValue(missing);
            helper.assertTrue(shown.getContents() instanceof TranslatableContents translatable
                    && translatable.getKey().equals(SensorTexts.NOT_FOUND), "value not found shown");
            helper.assertTrue(NetworkGameTests.keys(helper, NetworkGameTests.at(2), BlockInfoRequest.Detail.FULL)
                    .contains(SensorTexts.MISSING), "info line for a missing value");
            helper.assertFalse(ids.contains("data.absent"), "a reader whose mod is absent");
            helper.assertFalse(ids.contains("data.broken"), "a broken reader");
            List<String> loaded = new ArrayList<>();
            for (PackReaders.Entry reader : PackReaders.all()) {
                loaded.add(reader.id().toString());
            }
            helper.assertTrue(loaded.contains(TANK_READER), "the test reader loaded: " + loaded);
            helper.assertFalse(loaded.contains("maxtech_storage:test/broken"), "the broken reader loaded");
            helper.assertFalse(loaded.contains("maxtech_storage:test/absent_mod"), "the absent mod's reader loaded");
        }).thenSucceed();
    }

    /**
     * A read that also reads saved data costs {@code sensor.savedDataCost} more checks; a change within
     * {@code sensor.savedDataMinIntervalTicks} reads the other values at once (plain cost) and the reader values only
     * when the interval is over.
     */
    private static void intervalAndCost(GameTestHelper helper) {
        SensorBlockEntity entity = sensor(helper);
        SensorWorker sensor = entity.sensor();
        entity.setOwner(OwnerRecord.of(NetworkGameTests.player(helper.getLevel(), "reader_budget")));
        SensorTestContent.Tank tank = tank(helper);
        int interval = StorageServerConfig.SENSOR_SAVED_DATA_INTERVAL.getAsInt();
        int dataCost = StorageServerConfig.SENSOR_SAVED_DATA_COST.getAsInt();
        long[] used = new long[1];
        int[] counts = new int[3];
        long[] dataReadAt = new long[1];
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(sensor.dataReads() > 0, "no saved-data read"))
                .thenIdle(interval + 5)
                // Early in a second, so the reads below land in the same second of the team budget.
                .thenWaitUntil(() -> helper.assertValueEqual(helper.getLevel().getGameTime() % 20, 2L, "second start"))
                .thenExecute(() -> {
                    used[0] = TeamBudget.used(TeamBudget.team(helper.getLevel(), entity.ownerRecord()),
                            helper.getLevel().getGameTime());
                    counts[0] = sensor.dataReads();
                    counts[1] = sensor.reads();
                    tank.fill = 1;
                    tank.setChanged();
                }).thenWaitUntil(() -> helper.assertValueEqual(sensor.dataReads(), counts[0] + 1, "data read"))
                .thenExecute(() -> {
                    long now = helper.getLevel().getGameTime();
                    dataReadAt[0] = now;
                    assertValue(helper, sensor, "data.level", 1);
                    long spent = TeamBudget.used(TeamBudget.team(helper.getLevel(), entity.ownerRecord()), now)
                            - used[0];
                    helper.assertValueEqual(spent, (long) SensorWorker.READ_COST + dataCost, "cost of a data read");
                    used[0] += spent;
                    counts[1] = sensor.reads();
                    counts[2] = tank.saves;
                    tank.fill = 2;
                    tank.setChanged();
                }).thenWaitUntil(() -> helper.assertValueEqual(sensor.reads(), counts[1] + 1, "plain read"))
                .thenExecute(() -> {
                    long now = helper.getLevel().getGameTime();
                    helper.assertValueEqual(sensor.dataReads(), counts[0] + 1, "a data read within the interval");
                    helper.assertValueEqual(tank.saves, counts[2], "saved within the interval");
                    assertValue(helper, sensor, "data.level", 1);
                    long spent = TeamBudget.used(TeamBudget.team(helper.getLevel(), entity.ownerRecord()), now)
                            - used[0];
                    helper.assertValueEqual(spent, (long) SensorWorker.READ_COST, "cost of a plain read");
                }).thenWaitUntil(() -> assertValue(helper, sensor, "data.level", 2)).thenExecute(() -> {
                    long waited = helper.getLevel().getGameTime() - dataReadAt[0];
                    helper.assertTrue(waited >= interval, "caught up after " + waited + " ticks");
                    helper.assertValueEqual(sensor.dataReads(), counts[0] + 2, "one catch-up read");
                }).thenSucceed();
    }

    /** A graph reading a reader value whose path is missing says so in its why line; a found value says nothing. */
    private static void logicWhy(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        SensorGameTests.ownNetwork(helper, core);
        helper.setBlock(NetworkGameTests.at(2),
                SensorContent.SENSOR.get().defaultBlockState().setValue(SensorBlock.FACING, Direction.EAST));
        SensorBlockEntity sensor = helper.getBlockEntity(NetworkGameTests.at(2), SensorBlockEntity.class);
        sensor.setLabel(DeviceLabel.of("tank", null));
        SensorTestContent.Tank tank = tank(helper);
        tank.fill = 3;
        tank.setChanged();
        helper.setBlock(NetworkGameTests.at(0), LogicContent.CONTROLLER.get());
        LogicControllerBlockEntity entity = LogicControllerGameTests.entity(helper, NetworkGameTests.at(0));
        entity.setOwner(OwnerRecord.of(NetworkGameTests.player(helper.getLevel(), "reader_logic")));
        LogicGraph<LogicKey> graph = new LogicControllerGameTests.Graph()
                .node(1, NodeTypes.SENSOR_CROSSES,
                        s -> s.withText("label", "tank").withText("value", "data.missing").withNumber("threshold", 1))
                .node(2, NodeTypes.SENSOR_CROSSES,
                        s -> s.withText("label", "tank").withText("value", "data.level").withNumber("threshold", 1))
                .node(3, NodeTypes.NOTIFY, s -> s.withText("text", "missing")).wire(1, "crossed", 3, "do")
                .node(4, NodeTypes.NOTIFY, s -> s.withText("text", "level")).wire(2, "crossed", 4, "do").build();
        helper.startSequence().thenWaitUntil(() -> {
            LogicControllerGameTests.network(helper, entity);
            assertValue(helper, sensor.sensor(), "data.level", 3);
        }).thenExecute(() -> entity.worker().setGraph(0, "Tank", graph)).thenWaitUntil(() -> {
            LogicEvaluator<LogicKey> evaluator = entity.worker().evaluator(0)
                    .orElseThrow(() -> helper.assertionException(Component.literal("graph 0 is paused")));
            helper.assertTrue(evaluator.runs() > 0, "not run");
            RunTrace trace = evaluator.trace();
            int missing = trace.find(1);
            helper.assertTrue(missing >= 0 && trace.reason(missing).equals(SensorTexts.WHY_NOT_FOUND),
                    "why line of the missing value");
            int found = trace.find(2);
            helper.assertTrue(found < 0 || !trace.reason(found).equals(SensorTexts.WHY_NOT_FOUND),
                    "why line of a found value");
        }).thenSucceed();
    }

    /**
     * {@code /reload} with a world datapack that has the same file id as a test reader replaces it: the value named
     * {@code marker} reads another path. Removing the pack and reloading again brings the first one back.
     */
    private static void reload(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Path pack = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(PACK);
        delete(pack);
        SensorWorker sensor = sensor(helper).sensor();
        SensorTestContent.Tank tank = tank(helper);
        tank.fill = 5;
        tank.stored = 77;
        tank.setChanged();
        PackRepository packs = server.getPackRepository();
        String id = "file/" + PACK;
        List<String> before = new ArrayList<>(packs.getSelectedIds());
        before.remove(id);
        List<CompletableFuture<Void>> reloads = new ArrayList<>();
        // A run that failed half-way may have left the pack on: switch it off first.
        helper.startSequence().thenExecute(() -> {
            if (packs.getSelectedIds().contains(id)) {
                packs.reload();
                reloads.add(server.reloadResources(before));
            } else {
                reloads.add(CompletableFuture.completedFuture(null));
            }
        }).thenWaitUntil(() -> helper.assertTrue(reloads.get(0).isDone(), "clean-up reload running"))
                .thenExecute(sensor::refresh).thenWaitUntil(() -> assertValue(helper, sensor, "data.marker", 5))
                .thenExecute(() -> {
                    write(pack.resolve("pack.mcmeta"),
                            "{\"pack\": {\"description\": \"reader test\", \"min_format\": "
                                    + SharedConstants.DATA_PACK_FORMAT_MAJOR + ", \"max_format\": "
                                    + SharedConstants.DATA_PACK_FORMAT_MAJOR + "}}");
                    write(pack.resolve("data/maxtech_storage/maxtech_storage/readers/test/reload.json"),
                            "{\"blocks\": [\"maxtech_storage:test_sensor_tank\"], "
                                    + "\"values\": {\"marker\": \"Energy.stored\"}}");
                    packs.reload();
                    List<String> with = new ArrayList<>(before);
                    with.add(id);
                    reloads.add(server.reloadResources(with));
                }).thenWaitUntil(() -> helper.assertTrue(reloads.get(1).isDone(), "reload running")).thenExecute(() -> {
                    helper.assertTrue(packs.getSelectedIds().contains(id), "the test pack is not on");
                    sensor.refresh();
                }).thenWaitUntil(() -> assertValue(helper, sensor, "data.marker", 77)).thenExecute(() -> {
                    helper.assertValueEqual(listed(helper, sensor.values(), "data.marker").group(),
                            "maxtech_storage:test/reload", "same file id");
                    delete(pack);
                    packs.reload();
                    reloads.add(server.reloadResources(before));
                }).thenWaitUntil(() -> helper.assertTrue(reloads.get(2).isDone(), "reload running"))
                .thenExecute(sensor::refresh).thenWaitUntil(() -> assertValue(helper, sensor, "data.marker", 5))
                .thenSucceed();
    }

    private static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void delete(Path folder) {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
