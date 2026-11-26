package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetwork;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetworks;
import dev.silencebound.maxtech.lib.api.energy.EnergyPriority;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.analyzer.AnalyzerReport;
import dev.silencebound.maxtech.storage.network.AnalyzerItem;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.tool.probe.ProbeSampler;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.logic.controller.TeamBudget;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.cable.StorageCableBlock;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.energy.EnergyContent;
import dev.silencebound.maxtech.storage.label.LabelPayloads;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.sensor.SensorBlock;
import dev.silencebound.maxtech.storage.sensor.SensorBlockEntity;
import dev.silencebound.maxtech.storage.sensor.SensorContent;
import dev.silencebound.maxtech.storage.sensor.SensorMenu;
import dev.silencebound.maxtech.storage.sensor.SensorPart;
import dev.silencebound.maxtech.storage.sensor.SensorPoll;
import dev.silencebound.maxtech.storage.sensor.SensorSettings;
import dev.silencebound.maxtech.storage.sensor.SensorValue;
import dev.silencebound.maxtech.storage.sensor.SensorWorker;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The sensor (M7L area D): each reader against vanilla blocks (chest, furnace, cauldron, lever and repeater, an energy
 * cell), in both forms; reads on change without polling, and only watched changes reach listeners; polling costs the
 * team budget and waits when it is spent; an unloaded target is not read until its chunk loads; the settings travel in
 * the item and through the screen.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class SensorGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_16x12x16");
    private static final List<String> NAMES = new ArrayList<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        SensorTestContent.init();
        register("sensor_reads_chest", SensorGameTests::chest);
        register("sensor_reads_furnace", SensorGameTests::furnace);
        register("sensor_reads_cauldron", SensorGameTests::cauldron);
        register("sensor_part_reads_lever_and_repeater", SensorGameTests::leverAndRepeater);
        register("sensor_part_reads_energy", SensorGameTests::energy);
        register("sensor_reads_mu_network", SensorGameTests::muNetwork);
        register("sensor_wakes_on_change_without_polling", SensorGameTests::wakes);
        register("sensor_poll_counts_against_budget", SensorGameTests::pollBudget);
        register("sensor_unloaded_target_not_read", SensorGameTests::unloaded);
        register("sensor_settings_in_item_and_screen", SensorGameTests::settings);
        register("sensor_label_tab_both_forms", SensorGameTests::labels);
    }

    private SensorGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("sensor"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 400);
        }
    }

    /** A powered controller at x 1 and a sensor block at x 2 facing east, at the target at x 3. */
    private static SensorBlockEntity block(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 1_000_000);
        ownNetwork(helper, controller);
        helper.setBlock(NetworkGameTests.at(2),
                SensorContent.SENSOR.get().defaultBlockState().setValue(SensorBlock.FACING, Direction.EAST));
        return helper.getBlockEntity(NetworkGameTests.at(2), SensorBlockEntity.class);
    }

    /** A powered controller at x 1, cables at x 2 and 3, and a sensor part on the cable at x 3 facing east (x 4). */
    private static SensorPart part(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 1_000_000);
        ownNetwork(helper, controller);
        NetworkGameTests.cables(helper, 2, 3, CableContent.STORAGE_CABLE_TIER_1.type().get());
        CableParts parts = StorageCableBlock.parts(helper.getLevel(), helper.absolutePos(NetworkGameTests.at(3)));
        if (parts == null) {
            throw helper.assertionException(Component.literal("no cable parts"));
        }
        helper.assertTrue(parts.place(Direction.EAST, SensorContent.SENSOR_PART, null,
                new ItemStack(SensorContent.SENSOR_PART_ITEM.get())), "part not placed");
        CablePart placed = parts.part(Direction.EAST);
        if (!(placed instanceof SensorPart sensor)) {
            throw helper.assertionException(Component.literal("no sensor part"));
        }
        return sensor;
    }

    /**
     * Gives the network an owner of its own: unowned sensors charge their network owner's logic budget, and an unowned
     * network would share one budget with every other such test.
     */
    static void ownNetwork(GameTestHelper helper, BlockPos controller) {
        NetworkGameTests.controllerEntity(helper, controller)
                .setOwner(OwnerRecord.of(NetworkGameTests.player(helper.getLevel(), "sensor_network")));
    }

    private static void assertValue(GameTestHelper helper, SensorWorker sensor, String id, long expected) {
        helper.assertValueEqual(sensor.value(id), expected, id);
    }

    private static ChestBlockEntity chestWith(GameTestHelper helper, BlockPos pos, ItemStack... stacks) {
        helper.setBlock(pos, Blocks.CHEST);
        ChestBlockEntity chest = helper.getBlockEntity(pos, ChestBlockEntity.class);
        for (int i = 0; i < stacks.length; i++) {
            chest.setItem(i, stacks[i]);
        }
        chest.setChanged();
        return chest;
    }

    /**
     * The block form on a chest: totals, capacity, each item, the comparator output and the state, listed in order with
     * signals first and items by id.
     */
    private static void chest(GameTestHelper helper) {
        SensorBlockEntity entity = block(helper);
        SensorWorker sensor = entity.sensor();
        chestWith(helper, NetworkGameTests.at(3), new ItemStack(Items.DIRT, 5), new ItemStack(Items.COBBLESTONE, 10),
                new ItemStack(Items.COBBLESTONE, 3));
        helper.startSequence().thenWaitUntil(() -> assertValue(helper, sensor, "items.total", 18)).thenExecute(() -> {
            assertValue(helper, sensor, "item.minecraft:cobblestone", 13);
            assertValue(helper, sensor, "item.minecraft:dirt", 5);
            assertValue(helper, sensor, "item.minecraft:stone", 0);
            helper.assertTrue(sensor.value("items.capacity") >= 27 * 64L, "chest capacity");
            helper.assertTrue(sensor.value("comparator") > 0, "comparator output of a filled chest");
            assertValue(helper, sensor, "redstone", 0);
            assertValue(helper, sensor, "fluids.total", 0);
            List<String> ids = new ArrayList<>();
            for (SensorValue value : sensor.values()) {
                ids.add(value.id());
            }
            helper.assertValueEqual(ids.subList(0, 2), List.of("redstone", "comparator"), "signals first");
            int total = ids.indexOf("items.total");
            helper.assertTrue(total > ids.indexOf("state.facing"), "items after the state");
            helper.assertValueEqual(ids.subList(total, total + 6), List.of("items.total", "items.capacity",
                    "items.slots_used", "items.slots_free", "item.minecraft:cobblestone", "item.minecraft:dirt"),
                    "items by id");
            helper.assertFalse(ids.contains("energy.stored"), "a chest has no energy");
        }).thenSucceed();
    }

    /** The block form on a furnace's side: its fuel slot (the side's slot) and its state. */
    private static void furnace(GameTestHelper helper) {
        SensorWorker sensor = block(helper).sensor();
        BlockPos pos = NetworkGameTests.at(3);
        helper.setBlock(pos,
                Blocks.FURNACE.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        FurnaceBlockEntity furnace = helper.getBlockEntity(pos, FurnaceBlockEntity.class);
        furnace.setItem(1, new ItemStack(Items.COAL, 8));
        furnace.setChanged();
        helper.startSequence().thenWaitUntil(() -> assertValue(helper, sensor, "item.minecraft:coal", 8))
                .thenExecute(() -> {
                    assertValue(helper, sensor, "state.lit", 0);
                    assertValue(helper, sensor, "state.facing",
                            BlockStateProperties.HORIZONTAL_FACING.getPossibleValues().indexOf(Direction.SOUTH));
                    String text = "";
                    for (SensorValue value : sensor.values()) {
                        if (value.id().equals("state.facing")) {
                            text = value.text();
                        }
                    }
                    helper.assertValueEqual(text, "south", "facing in words");
                }).thenSucceed();
    }

    /** The block form on a cauldron: fluid through NeoForge's cauldron handler, its level and its comparator output. */
    private static void cauldron(GameTestHelper helper) {
        SensorWorker sensor = block(helper).sensor();
        BlockPos pos = NetworkGameTests.at(3);
        helper.setBlock(pos, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
        helper.startSequence().thenWaitUntil(() -> assertValue(helper, sensor, "fluids.total", 1000))
                .thenExecute(() -> {
                    assertValue(helper, sensor, "fluid.minecraft:water", 1000);
                    assertValue(helper, sensor, "state.level", 3);
                    assertValue(helper, sensor, "comparator", 3);
                    helper.setBlock(pos,
                            Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 1));
                }).thenWaitUntil(() -> assertValue(helper, sensor, "state.level", 1)).thenExecute(() -> {
                    assertValue(helper, sensor, "comparator", 1);
                    helper.assertTrue(sensor.value("fluids.total") < 1000, "less water");
                }).thenSucceed();
    }

    /** The part form on a lever (state and signal) and then on a repeater (its delay). */
    private static void leverAndRepeater(GameTestHelper helper) {
        SensorWorker sensor = part(helper).sensor();
        BlockPos pos = NetworkGameTests.at(4);
        helper.setBlock(pos.below(), Blocks.STONE);
        BlockState lever = Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR);
        helper.setBlock(pos, lever);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(sensor.hasRead(), "not read")).thenExecute(() -> {
            assertValue(helper, sensor, "state.powered", 0);
            assertValue(helper, sensor, "redstone", 0);
            helper.setBlock(pos, lever.setValue(LeverBlock.POWERED, true));
        }).thenWaitUntil(() -> assertValue(helper, sensor, "state.powered", 1)).thenExecute(() -> {
            assertValue(helper, sensor, "redstone", 15);
            helper.setBlock(pos, Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.DELAY, 3));
        }).thenWaitUntil(() -> assertValue(helper, sensor, "state.delay", 3))
                .thenExecute(() -> assertValue(helper, sensor, "state.powered", 0)).thenSucceed();
    }

    /** The part form on an energy cell: stored FE and capacity through the cell's FE handler. */
    private static void energy(GameTestHelper helper) {
        SensorPart part = part(helper);
        SensorWorker sensor = part.sensor();
        BlockPos pos = NetworkGameTests.at(4);
        helper.setBlock(pos, EnergyContent.TIER_1.get());
        long[] inserted = new long[1];
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(sensor.value("energy.capacity") > 0, "capacity"))
                .thenExecute(() -> {
                    EnergyHandler handler = helper.getLevel().getCapability(Capabilities.Energy.BLOCK,
                            helper.absolutePos(pos), Direction.WEST);
                    if (handler == null) {
                        throw helper.assertionException(Component.literal("no FE"));
                    }
                    try (Transaction transaction = Transaction.openRoot()) {
                        inserted[0] = handler.insert(500, transaction);
                        transaction.commit();
                    }
                    helper.assertTrue(inserted[0] > 0, "nothing inserted");
                    sensor.refresh();
                }).thenWaitUntil(() -> assertValue(helper, sensor, "energy.stored", inserted[0])).thenSucceed();
    }

    /**
     * MLE item 3: a sensor part facing a MaxTech energy machine reads its network's readout. 100 MU/t of supply, the
     * machine asking 160 and a leftovers-only battery giving 40 of its 5,000 MU: 140 delivered, 87 % (rounded down),
     * empty in 125 ticks or less, never full. Reading never wakes the sleeping network. Storage's own network hosts an
     * energy network since MLE item 4 (its controller's buffer is in it), so the probe on a cable and the analyzer show
     * that one's readout.
     */
    private static void muNetwork(GameTestHelper helper) {
        SensorPart part = part(helper);
        SensorWorker sensor = part.sensor();
        BlockPos pos = NetworkGameTests.at(4);
        helper.setBlock(pos, SensorTestContent.MU_MACHINE.get());
        EnergyNetwork network = EnergyNetworks.of(helper.getLevel()).createNetwork();
        EnergyEndpoint generator = EnergyEndpoint.producer();
        generator.setSupply(100);
        network.add(generator);
        EnergyEndpoint battery = EnergyEndpoint.buffer(10_000, 40, 40, EnergyPriority.LEFTOVERS);
        battery.setStored(5_000);
        network.add(battery);
        EnergyEndpoint machine = helper.getBlockEntity(pos, SensorTestContent.Machine.class).endpoint;
        machine.setDemand(160);
        network.add(machine);
        long[] passes = new long[1];
        helper.startSequence().thenIdle(2).thenExecute(() -> {
            passes[0] = network.passCount();
            sensor.refresh();
        }).thenWaitUntil(() -> assertValue(helper, sensor, "mu.received", 140)).thenExecute(() -> {
            assertValue(helper, sensor, "mu.supply", 100);
            assertValue(helper, sensor, "mu.demand", 160);
            assertValue(helper, sensor, "mu.supplied", 87);
            assertValue(helper, sensor, "mu.capacity", 10_000);
            assertValue(helper, sensor, "mu.ticks_to_full", -1);
            long empty = sensor.value("mu.ticks_to_empty");
            helper.assertTrue(empty > 0 && empty <= 125, "empty in " + empty);
            long stored = sensor.value("mu.stored");
            helper.assertTrue(stored > 0 && stored < 5_000, "stored " + stored);
            List<String> ids = new ArrayList<>();
            for (SensorValue value : sensor.values()) {
                ids.add(value.id());
            }
            helper.assertTrue(ids.contains("mu.supplied") && ids.contains("mu.pass_nanos"), "listed: " + ids);
            helper.assertValueEqual(passes[0], network.passCount(), "passes while the sensor read");

            BlockPos cable = helper.absolutePos(NetworkGameTests.at(3));
            helper.assertTrue(ProbeSampler.sample(helper.getLevel(), cable).energy(),
                    "a storage network shows its energy network's readout");
            NetworkState storage = AnalyzerItem.networkAt(helper.getLevel(), cable);
            if (storage == null) {
                throw helper.assertionException(Component.literal("no storage network"));
            }
            helper.assertTrue(storage.energyNetwork().isPresent(), "the storage network hosts an energy network");
            helper.assertTrue(!AnalyzerReport.energyLines(storage).isEmpty(), "analyzer energy lines");
            network.dispose();
        }).thenSucceed();
    }

    /**
     * With polling off, an idle sensor reads nothing; a chest change wakes one read. A listener on cobblestone hears of
     * cobblestone only, not of dirt.
     */
    private static void wakes(GameTestHelper helper) {
        SensorWorker sensor = block(helper).sensor();
        BlockPos pos = NetworkGameTests.at(3);
        ChestBlockEntity chest = chestWith(helper, pos, new ItemStack(Items.COBBLESTONE, 1));
        int[] reads = new int[1];
        List<Long> heard = new ArrayList<>();
        helper.startSequence().thenWaitUntil(() -> assertValue(helper, sensor, "item.minecraft:cobblestone", 1))
                .thenExecute(() -> {
                    sensor.watch("item.minecraft:cobblestone", (worker, id, value) -> heard.add(value));
                    reads[0] = sensor.reads();
                }).thenIdle(60).thenExecute(() -> {
                    helper.assertValueEqual(sensor.reads(), reads[0], "reads while nothing changed");
                    chest.setItem(5, new ItemStack(Items.DIRT, 4));
                    chest.setChanged();
                }).thenWaitUntil(() -> assertValue(helper, sensor, "item.minecraft:dirt", 4)).thenExecute(() -> {
                    helper.assertValueEqual(sensor.reads(), reads[0] + 1, "one read for one change");
                    helper.assertValueEqual(heard, List.of(), "heard of an unwatched change");
                    chest.setItem(6, new ItemStack(Items.COBBLESTONE, 2));
                    chest.setChanged();
                }).thenWaitUntil(() -> helper.assertValueEqual(heard, List.of(3L), "heard of cobblestone"))
                .thenSucceed();
    }

    /**
     * Polling every second reads about once a second and charges each read to the owner's team; with the team's budget
     * spent, the sensor waits for the next second.
     */
    private static void pollBudget(GameTestHelper helper) {
        SensorBlockEntity entity = block(helper);
        SensorWorker sensor = entity.sensor();
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "sensor_owner");
        entity.setOwner(OwnerRecord.of(owner));
        chestWith(helper, NetworkGameTests.at(3), new ItemStack(Items.COBBLESTONE, 1));
        int[] reads = new int[1];
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(sensor.hasRead(), "not read"))
                .thenExecute(() -> sensor.setSettings(SensorSettings.DEFAULT.withPoll(SensorPoll.ONE_SECOND)))
                .thenIdle(5).thenExecute(() -> reads[0] = sensor.reads()).thenIdle(100).thenExecute(() -> {
                    int polled = sensor.reads() - reads[0];
                    helper.assertTrue(polled >= 4 && polled <= 6, "reads in 100 ticks of polling: " + polled);
                })
                // Early in a second, spend the rest of the team's budget: the next read must wait for the next one.
                .thenWaitUntil(() -> helper.assertValueEqual(helper.getLevel().getGameTime() % 20, 2L, "second start"))
                .thenExecute(() -> {
                    long now = helper.getLevel().getGameTime();
                    long used = TeamBudget.used(TeamBudget.team(helper.getLevel(), entity.ownerRecord()), now);
                    helper.assertTrue(used <= StorageServerConfig.LOGIC_TEAM_CHECKS.getAsInt(), "over budget");
                    int rest = (int) (StorageServerConfig.LOGIC_TEAM_CHECKS.getAsInt() - used);
                    helper.assertValueEqual(
                            TeamBudget.charge(TeamBudget.team(helper.getLevel(), entity.ownerRecord()), rest, now), 0,
                            "charge");
                    helper.assertTrue(
                            TeamBudget.charge(TeamBudget.team(helper.getLevel(), entity.ownerRecord()), 1, now) > 0,
                            "a spent budget took more");
                    reads[0] = sensor.reads();
                    sensor.refresh();
                }).thenIdle(10).thenExecute(() -> {
                    helper.assertValueEqual(sensor.reads(), reads[0], "reads with the budget spent");
                }).thenWaitUntil(
                        () -> helper.assertTrue(sensor.reads() > reads[0], "no read in the next second"))
                .thenExecute(
                        () -> helper
                                .assertTrue(
                                        TeamBudget.used(TeamBudget.team(helper.getLevel(), entity.ownerRecord()),
                                                helper.getLevel().getGameTime()) >= SensorWorker.READ_COST,
                                        "read not charged"))
                .thenSucceed();
    }

    /**
     * A target whose chunk is not loaded is not read, however its neighbours are told; when its chunk loads (its
     * capabilities are invalidated), the sensor reads it once.
     */
    private static void unloaded(GameTestHelper helper) {
        SensorWorker sensor = block(helper).sensor();
        BlockPos pos = NetworkGameTests.at(3);
        BlockPos target = helper.absolutePos(pos);
        ChestBlockEntity chest = chestWith(helper, pos, new ItemStack(Items.COBBLESTONE, 1));
        int[] reads = new int[1];
        helper.startSequence().thenWaitUntil(() -> assertValue(helper, sensor, "items.total", 1)).thenExecute(() -> {
            SensorWorker.setLoadedCheck((level, at) -> !at.equals(target));
            reads[0] = sensor.reads();
            chest.setItem(3, new ItemStack(Items.COBBLESTONE, 7));
            chest.setChanged();
        }).thenWaitUntil(() -> helper.assertFalse(sensor.targetLoaded(), "still loaded")).thenIdle(20)
                .thenExecute(() -> {
                    helper.assertValueEqual(sensor.reads(), reads[0], "reads of an unloaded target");
                    assertValue(helper, sensor, "items.total", 1);
                    SensorWorker.setLoadedCheck(null);
                    // What NeoForge does when the target's chunk loads.
                    helper.getLevel().invalidateCapabilities(target);
                }).thenWaitUntil(() -> assertValue(helper, sensor, "items.total", 8)).thenExecute(() -> {
                    helper.assertTrue(sensor.targetLoaded(), "loaded again");
                    helper.assertValueEqual(sensor.reads(), reads[0] + 1, "one read on load");
                }).thenSucceed();
    }

    /**
     * The shared label tab works on the sensor's screen in both forms: the label packet labels the open sensor, and the
     * label goes into the dropped block and the part's item next to the settings.
     */
    private static void labels(GameTestHelper helper) {
        SensorBlockEntity block = block(helper);
        BlockPos partCable = NetworkGameTests.at(6);
        NetworkGameTests.cables(helper, 6, 6, CableContent.STORAGE_CABLE_TIER_1.type().get());
        CableParts parts = StorageCableBlock.parts(helper.getLevel(), helper.absolutePos(partCable));
        if (parts == null) {
            throw helper.assertionException(Component.literal("no cable parts"));
        }
        helper.assertTrue(parts.place(Direction.EAST, SensorContent.SENSOR_PART, null,
                new ItemStack(SensorContent.SENSOR_PART_ITEM.get())), "part not placed");
        if (!(parts.part(Direction.EAST) instanceof SensorPart part)) {
            throw helper.assertionException(Component.literal("no sensor part"));
        }
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "sensor_labels");
        owner.setPos(Vec3.atCenterOf(helper.absolutePos(NetworkGameTests.at(4).above())));
        block.setOwner(OwnerRecord.of(owner));
        part.setOwner(OwnerRecord.of(owner));
        DeviceLabel furnaces = DeviceLabel.of("Furnaces", DyeColor.ORANGE);
        DeviceLabel tank = DeviceLabel.of("Tank", null);

        SensorMenu blockMenu = SensorMenu.server(7, owner, block);
        helper.assertTrue(blockMenu.labelDevice() == block, "the block's menu labels the block");
        owner.containerMenu = blockMenu;
        helper.assertTrue(LabelPayloads.apply(owner, 7, furnaces), "block label refused");
        helper.assertValueEqual(block.label(), furnaces, "block label");
        block.sensor().setSettings(SensorSettings.DEFAULT.withPoll(SensorPoll.FIVE_SECONDS));
        ItemStack drop = new ItemStack(SensorContent.SENSOR.item().get());
        drop.applyComponents(block.collectComponents());
        helper.assertValueEqual(drop.getOrDefault(ModDataComponents.DEVICE_LABEL.get(), DeviceLabel.NONE), furnaces,
                "label in the dropped block");
        helper.assertValueEqual(drop.getOrDefault(SensorContent.SETTINGS.get(), SensorSettings.DEFAULT).poll(),
                SensorPoll.FIVE_SECONDS, "settings in the dropped block");

        owner.containerMenu = SensorMenu.server(8, owner, part);
        helper.assertTrue(LabelPayloads.apply(owner, 8, tank), "part label refused");
        helper.assertValueEqual(part.label(), tank, "part label");
        helper.assertValueEqual(part.toItem().getOrDefault(ModDataComponents.DEVICE_LABEL.get(), DeviceLabel.NONE),
                tank, "label in the part's item");
        owner.containerMenu = owner.inventoryMenu;
        helper.succeed();
    }

    /** The screen steps the polling and lists the values; the block drops its settings and the part keeps them. */
    private static void settings(GameTestHelper helper) {
        SensorBlockEntity entity = block(helper);
        chestWith(helper, NetworkGameTests.at(3), new ItemStack(Items.COBBLESTONE, 2));
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "sensor_screen");
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(entity.sensor().hasRead(), "not read"))
                .thenExecute(() -> {
                    SensorMenu menu = SensorMenu.server(1, player, entity);
                    menu.broadcastChanges();
                    helper.assertTrue(menu.values().size() >= 5, "values on the screen: " + menu.values().size());
                    helper.assertTrue(menu.clickMenuButton(player, SensorMenu.CYCLE_POLL), "button refused");
                    helper.assertValueEqual(entity.sensor().settings().poll(), SensorPoll.ONE_SECOND, "poll");
                    helper.assertValueEqual(menu.poll(), SensorPoll.ONE_SECOND, "synced poll");
                    ItemStack drop = new ItemStack(SensorContent.SENSOR.item().get());
                    drop.applyComponents(entity.collectComponents());
                    helper.assertValueEqual(drop.getOrDefault(SensorContent.SETTINGS.get(), SensorSettings.DEFAULT),
                            new SensorSettings(SensorPoll.ONE_SECOND), "settings in the item");
                    ItemStack fresh = new ItemStack(SensorContent.SENSOR.item().get());
                    helper.assertTrue(fresh.get(SensorContent.SETTINGS.get()) == null, "a new sensor has settings");
                }).thenSucceed();
    }
}
