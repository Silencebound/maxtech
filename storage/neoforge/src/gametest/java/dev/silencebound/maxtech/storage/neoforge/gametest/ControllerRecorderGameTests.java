package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.controller.module.AlarmSettings;
import dev.silencebound.maxtech.storage.controller.module.ExpansionBlockEntity;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.LedgerBook;
import dev.silencebound.maxtech.storage.controller.module.TelemetryRecorder;
import dev.silencebound.maxtech.storage.controller.recorder.ControllerRecorders;
import dev.silencebound.maxtech.storage.controller.recorder.LedgerRecorder;
import dev.silencebound.maxtech.storage.controller.recorder.RecordsView;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The recorders built into the controller (M7R item 3): its ledger and alarm rules live on the primary and move with
 * it, once, through a handover, a merge and a split; the rows and settings of old ledger and alarm module blocks move
 * into the controller once; each view needs its research for the owner's team; and the config switches turn the
 * recorders off.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
// Networks and recorders are compared by identity.
@SuppressWarnings("ReferenceEquality")
public final class ControllerRecorderGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey DIRT = ItemKey.of(Items.DIRT);
    private static final AlarmSettings STORAGE_40 = new AlarmSettings(AlarmSettings.Type.STORAGE_FULL, 40);
    private static final AlarmSettings STORAGE_70 = new AlarmSettings(AlarmSettings.Type.STORAGE_FULL, 70);
    private static final String MIGRATE = "controller_recorders_migrate_once";
    private static final String HAND_OVER = "controller_recorders_hand_over";
    private static final String MERGE = "controller_recorders_merge";
    private static final String SPLIT = "controller_recorders_split";
    private static final String RESEARCH = "controller_recorders_need_research";
    private static final String OFF = "controller_recorders_config_off";
    private static final String FULL = "controller_recorders_migrate_at_rule_cap";

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register(MIGRATE, ControllerRecorderGameTests::migrateOnce);
        register(HAND_OVER, ControllerRecorderGameTests::handOver);
        register(MERGE, ControllerRecorderGameTests::merge);
        register(SPLIT, ControllerRecorderGameTests::split);
        register(RESEARCH, ControllerRecorderGameTests::needResearch);
        register(OFF, ControllerRecorderGameTests::configOff);
        register(FULL, ControllerRecorderGameTests::migrateAtRuleCap);
    }

    private ControllerRecorderGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event
                .registerEnvironment(MaxtechStorage.id("controller_recorders"), StorageTestEnvironment.plain());
        for (String name : List.of(MIGRATE, HAND_OVER, MERGE, SPLIT, RESEARCH, FULL)) {
            StorageTests.test(event, plain, name, STRUCTURE, 400);
        }
        StorageTests.test(event,
                event.registerEnvironment(MaxtechStorage.id(OFF), new StorageTestEnvironment(
                        "controller.recorders.telemetry;controller.recorders.ledger;controller.recorders.alarms",
                        "false;false;false", false)),
                OFF, STRUCTURE, 400);
    }

    /** Completes {@code nodes}, in order, for {@code player}'s team with skip tokens. */
    static void research(GameTestHelper helper, ServerPlayer player, String... nodes) {
        MinecraftServer server = helper.getLevel().getServer();
        ResearchTeam team = Research.team(player, true);
        for (String node : nodes) {
            Research.addToken(server, team);
            helper.assertValueEqual(Research.useToken(server, team, MaxtechStorage.id(node)), Research.Result.DONE,
                    node);
        }
    }

    private static long today(GameTestHelper helper) {
        return Math.floorDiv(helper.getLevel().getGameTime(), LedgerBook.TICKS_PER_DAY);
    }

    /** What went in ({@code [0]}) and out of {@code key} today for automation in the ledger of {@code controller}. */
    private static long[] row(GameTestHelper helper, ControllerBlockEntity controller, UUID player, StackKey key) {
        return controller.recorders().ledger().book().dayTotals(player, key, today(helper));
    }

    private static long[] row(GameTestHelper helper, ControllerBlockEntity controller, StackKey key) {
        return row(helper, controller, LedgerBook.AUTOMATION, key);
    }

    /** Records a move of {@code amount} of {@code key} by automation in {@code controller}'s ledger. */
    private static void move(GameTestHelper helper, ControllerBlockEntity controller, StackKey key, long amount) {
        LedgerRecorder ledger = controller.recorders().ledger();
        ledger.moved(key, amount, null, helper.getLevel().getGameTime());
    }

    /** Loads {@code tag} into the block entity at {@code pos}, as a chunk load does. */
    private static void load(GameTestHelper helper, BlockPos pos, CompoundTag tag) {
        helper.getBlockEntity(pos, ExpansionBlockEntity.class).loadCustomOnly(
                TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), tag));
    }

    private static Tag encode(GameTestHelper helper, LedgerBook<StackKey> book) {
        return LedgerBook.codec(StackKey.CODEC)
                .encodeStart(helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE), book)
                .getOrThrow();
    }

    /**
     * Old modules as an M7R item 2 world left them, beside a controller: a ledger module with 50 cobblestone in and 5
     * dirt out today, an alarm module set to storage at 40 % and one left at its defaults. The controller's fill moves
     * the rows into its ledger and makes a rule of each alarm; a second fill and a save of the modules move nothing
     * again, and an old alarm's drop carries no settings any more.
     */
    private static void migrateOnce(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 2, 5);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        BlockPos ledger = core.east();
        BlockPos alarm = core.west();
        BlockPos plain = core.north();
        UUID alice = UUID.randomUUID();
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.state(helper, core)).thenExecute(() -> {
            ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
            long corePos = controller.getBlockPos().asLong();
            LedgerBook<StackKey> book = new LedgerBook<>();
            book.record(LedgerBook.AUTOMATION, COBBLE, 50, today(helper), 7, 4096);
            book.record(alice, DIRT, -5, today(helper), 7, 4096);
            ControllerClusterGameTests.expansion(helper, ledger, ExpansionContent.LEDGER_MODULE);
            CompoundTag ledgerTag = new CompoundTag();
            ledgerTag.put("ledger", encode(helper, book));
            load(helper, ledger, ledgerTag);
            ControllerClusterGameTests.expansion(helper, alarm, ExpansionContent.ALARM_MODULE);
            CompoundTag alarmTag = new CompoundTag();
            alarmTag.put("alarm", AlarmSettings.CODEC.encodeStart(NbtOps.INSTANCE, STORAGE_40).getOrThrow());
            load(helper, alarm, alarmTag);
            ControllerClusterGameTests.expansion(helper, plain, ExpansionContent.ALARM_MODULE);
            CompoundTag plainTag = new CompoundTag();
            plainTag.put("alarm", AlarmSettings.CODEC.encodeStart(NbtOps.INSTANCE, AlarmSettings.DEFAULT).getOrThrow());
            load(helper, plain, plainTag);
            for (BlockPos pos : List.of(ledger, alarm, plain)) {
                // As an old core left them: pointing at it.
                ControllerClusterGameTests.expansionEntity(helper, pos).join(corePos);
            }
            controller.detectNow();
            check(helper, controller, alice);
            for (BlockPos pos : List.of(ledger, alarm, plain)) {
                ExpansionBlockEntity entity = ControllerClusterGameTests.expansionEntity(helper, pos);
                helper.assertTrue(!entity.holdsRecords(), "given up at " + pos.toShortString());
                helper.assertValueEqual(entity.core(), ExpansionBlockEntity.NO_CORE, "inert " + pos.toShortString());
                CompoundTag saved = entity.saveWithoutMetadata(helper.getLevel().registryAccess());
                helper.assertTrue(!saved.contains("ledger") && !saved.contains("alarm"),
                        "nothing saved at " + pos.toShortString());
            }
            List<ItemStack> drops = Block.getDrops(helper.getBlockState(alarm), helper.getLevel(),
                    helper.absolutePos(alarm), ControllerClusterGameTests.expansionEntity(helper, alarm));
            helper.assertTrue(drops.getFirst().get(ModDataComponents.ALARM.get()) == null, "a plain drop");
            controller.detectNow();
            check(helper, controller, alice);
        }).thenSucceed();
    }

    /**
     * A controller already at the rule cap beside an old alarm module: the fill adds no rule and the module keeps its
     * settings (saved and in its drop); once a rule is removed, the next fill moves them into a rule and the module
     * gives them up.
     */
    private static void migrateAtRuleCap(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 2, 5);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        BlockPos alarm = core.west();
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.state(helper, core)).thenExecute(() -> {
            ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
            while (controller.recorders().addRule(AlarmSettings.DEFAULT)) {
                helper.assertTrue(controller.recorders().rules().size() <= ControllerRecorders.MOST_RULES, "capped");
            }
            int cap = controller.recorders().rules().size();
            ControllerClusterGameTests.expansion(helper, alarm, ExpansionContent.ALARM_MODULE);
            CompoundTag alarmTag = new CompoundTag();
            alarmTag.put("alarm", AlarmSettings.CODEC.encodeStart(NbtOps.INSTANCE, STORAGE_40).getOrThrow());
            load(helper, alarm, alarmTag);
            controller.detectNow();
            ExpansionBlockEntity entity = ControllerClusterGameTests.expansionEntity(helper, alarm);
            helper.assertValueEqual(controller.recorders().rules().size(), cap, "no room, no rule");
            helper.assertTrue(entity.holdsRecords(), "the module keeps its settings");
            helper.assertTrue(entity.saveWithoutMetadata(helper.getLevel().registryAccess()).contains("alarm"),
                    "and saves them");
            List<ItemStack> drops = Block.getDrops(helper.getBlockState(alarm), helper.getLevel(),
                    helper.absolutePos(alarm), entity);
            helper.assertTrue(STORAGE_40.equals(drops.getFirst().get(ModDataComponents.ALARM.get())),
                    "its drop carries them");
            helper.assertTrue(controller.recorders().edit(0, ControllerRecorders.OP_REMOVE), "room made");
            controller.detectNow();
            helper.assertTrue(controller.recorders().ruleSettings().contains(STORAGE_40), "moved once there is room");
            helper.assertTrue(!entity.holdsRecords(), "given up");
            controller.detectNow();
            helper.assertValueEqual(controller.recorders().rules().size(), cap, "moved once");
        }).thenSucceed();
    }

    private static void check(GameTestHelper helper, ControllerBlockEntity controller, UUID alice) {
        helper.assertValueEqual(row(helper, controller, COBBLE)[0], 50L, "cobblestone in, once");
        helper.assertValueEqual(row(helper, controller, alice, DIRT)[1], 5L, "alice's dirt out, once");
        List<AlarmSettings> rules = controller.recorders().ruleSettings();
        helper.assertValueEqual(rules.size(), 2, "a rule per old alarm, once");
        helper.assertTrue(rules.contains(STORAGE_40) && rules.contains(AlarmSettings.DEFAULT), "their settings");
    }

    /**
     * A two-block controller with ledger rows, a rule and a running history: breaking the primary hands all of it to
     * the other block, once (the broken block's item carries no rules), and the history goes on in the same buffers.
     */
    private static void handOver(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        BlockPos other = core.east();
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        TelemetryRecorder[] history = new TelemetryRecorder[1];
        helper.startSequence().thenIdle(2).thenExecute(() -> helper.setBlock(other, ControllerContent.CONTROLLER.get()))
                .thenWaitUntil(() -> {
                    ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
                    helper.assertValueEqual(controller.blockCount(), 2, "two blocks");
                    TelemetryRecorder recording = controller.telemetry();
                    helper.assertTrue(recording != null && recording.size() >= 1, "history running");
                }).thenExecute(() -> {
                    ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
                    move(helper, controller, COBBLE, 64);
                    helper.assertTrue(controller.recorders().addRule(STORAGE_70), "rule");
                    history[0] = java.util.Objects.requireNonNull(controller.telemetry());
                    List<ItemStack> drops = Block.getDrops(helper.getBlockState(core), helper.getLevel(),
                            helper.absolutePos(core), controller);
                    helper.assertTrue(drops.getFirst().get(ModDataComponents.ALARM_RULES.get()) == null,
                            "a handing-over block's item carries no rules");
                    helper.destroyBlock(core);
                    ControllerBlockEntity successor = helper.getBlockEntity(other, ControllerBlockEntity.class);
                    helper.assertTrue(successor.isPrimary(), "the other block holds the state");
                    helper.assertValueEqual(row(helper, successor, COBBLE)[0], 64L, "ledger rows, once");
                    helper.assertValueEqual(successor.recorders().ruleSettings(), List.of(STORAGE_70), "the rule");
                    helper.assertTrue(successor.telemetry() == history[0], "the same history goes on");
                }).thenWaitUntil(() -> {
                    ControllerBlockEntity successor = helper.getBlockEntity(other, ControllerBlockEntity.class);
                    NetworkState network = NetworkGameTests.state(helper, other);
                    helper.assertTrue(successor.recorders().ledger().bound() == network, "the ledger listens again");
                    helper.assertValueEqual(successor.recorders().rules().size(), 1, "the rule moved");
                    helper.assertTrue(successor.recorders().rules().getFirst().evaluations() > 0, "the rule runs");
                }).thenSucceed();
    }

    /**
     * Two controllers with their own rows and rules joined by a third block: the older one keeps its state and takes
     * the other's rows (summed per key) and rules; the merged one keeps nothing.
     */
    private static void merge(GameTestHelper helper) {
        BlockPos first = new BlockPos(5, 1, 5);
        BlockPos second = new BlockPos(5, 1, 7);
        helper.setBlock(first, ControllerContent.CONTROLLER.get());
        helper.startSequence().thenIdle(2)
                .thenExecute(() -> helper.setBlock(second, ControllerContent.CONTROLLER.get()))
                .thenWaitUntil(() -> NetworkGameTests.state(helper, second)).thenExecute(() -> {
                    ControllerBlockEntity a = NetworkGameTests.controllerEntity(helper, first);
                    ControllerBlockEntity b = NetworkGameTests.controllerEntity(helper, second);
                    move(helper, a, COBBLE, 10);
                    move(helper, b, COBBLE, 5);
                    move(helper, b, DIRT, 3);
                    helper.assertTrue(a.recorders().addRule(AlarmSettings.DEFAULT), "rule of the first");
                    helper.assertTrue(b.recorders().addRule(STORAGE_70), "rule of the second");
                    helper.setBlock(first.south(), ControllerContent.CONTROLLER.get());
                })
                .thenWaitUntil(() -> helper
                        .assertValueEqual(NetworkGameTests.controllerEntity(helper, first).blockCount(), 3, "merged"))
                .thenExecute(() -> {
                    ControllerBlockEntity a = NetworkGameTests.controllerEntity(helper, first);
                    ControllerBlockEntity b = helper.getBlockEntity(second, ControllerBlockEntity.class);
                    helper.assertTrue(!b.isPrimary(), "the younger one joined");
                    helper.assertValueEqual(row(helper, a, COBBLE)[0], 15L, "cobblestone summed");
                    helper.assertValueEqual(row(helper, a, DIRT)[0], 3L, "the other's dirt");
                    helper.assertValueEqual(a.recorders().ruleSettings(), List.of(AlarmSettings.DEFAULT, STORAGE_70),
                            "both rules");
                    helper.assertValueEqual(b.recorders().ledger().saved().rows(), 0, "no rows left behind");
                    helper.assertValueEqual(b.recorders().rules().size(), 0, "no rules left behind");
                }).thenSucceed();
    }

    /**
     * A line of three blocks with rows and a rule: breaking the middle one leaves the primary with its state and makes
     * the cut-off block a controller of its own that starts with none.
     */
    private static void split(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        BlockPos end = core.east(2);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        helper.startSequence().thenIdle(2).thenExecute(() -> {
            helper.setBlock(core.east(), ControllerContent.CONTROLLER.get());
            helper.setBlock(end, ControllerContent.CONTROLLER.get());
        }).thenWaitUntil(() -> helper.assertValueEqual(NetworkGameTests.controllerEntity(helper, core).blockCount(), 3,
                "three blocks")).thenExecute(() -> {
                    ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
                    move(helper, controller, COBBLE, 20);
                    helper.assertTrue(controller.recorders().addRule(STORAGE_70), "rule");
                    helper.destroyBlock(core.east());
                })
                .thenWaitUntil(() -> helper
                        .assertTrue(helper.getBlockEntity(end, ControllerBlockEntity.class).isPrimary(), "cut off"))
                .thenExecute(() -> {
                    ControllerBlockEntity kept = NetworkGameTests.controllerEntity(helper, core);
                    ControllerBlockEntity alone = helper.getBlockEntity(end, ControllerBlockEntity.class);
                    helper.assertValueEqual(row(helper, kept, COBBLE)[0], 20L, "the primary keeps its rows");
                    helper.assertValueEqual(kept.recorders().ruleSettings(), List.of(STORAGE_70), "and its rule");
                    helper.assertValueEqual(alone.recorders().ledger().saved().rows(), 0, "the cut-off block: no rows");
                    helper.assertValueEqual(alone.recorders().rules().size(), 0, "and no rules");
                }).thenSucceed();
    }

    /**
     * An owned controller's views are locked until the owner's team has their research: a locked page says so and
     * carries nothing; Network Telemetry opens the graphs, Network Ledger the ledger and Network Tuning the rules. The
     * controller records all along.
     */
    private static void needResearch(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "recorder_research");
        BlockPos core = new BlockPos(5, 1, 5);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        helper.startSequence().thenWaitUntil(() -> {
            TelemetryRecorder recording = NetworkGameTests.controllerEntity(helper, core).telemetry();
            helper.assertTrue(recording != null && recording.size() >= 1, "history running");
        }).thenExecute(() -> {
            ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
            controller.setOwner(OwnerRecord.of(owner));
            move(helper, controller, COBBLE, 8);
            helper.assertTrue(controller.recorders().addRule(STORAGE_70), "rule");
            for (int page = 0; page < RecordsView.PAGES; page++) {
                RecordsView view = RecordsView.build(controller, owner, page);
                helper.assertTrue(view.locked(page), "page " + page + " locked");
            }
            helper.assertTrue(
                    RecordsView.build(controller, owner, RecordsView.TELEMETRY).telemetry().operations().isEmpty(),
                    "no graphs while locked");
            helper.assertTrue(RecordsView.build(controller, owner, RecordsView.LEDGER).ledger().views().isEmpty(),
                    "no ledger while locked");
            helper.assertTrue(RecordsView.build(controller, owner, RecordsView.ALARMS).alarms().rules().isEmpty(),
                    "no rules while locked");
            research(helper, owner, "network_telemetry");
            RecordsView graphs = RecordsView.build(controller, owner, RecordsView.TELEMETRY);
            helper.assertTrue(!graphs.locked(RecordsView.TELEMETRY), "graphs open");
            helper.assertTrue(!graphs.telemetry().operations().isEmpty(), "with samples");
            helper.assertTrue(RecordsView.build(controller, owner, RecordsView.LEDGER).locked(RecordsView.LEDGER),
                    "the ledger has a node of its own");
            research(helper, owner, "network_ledger");
            RecordsView ledger = RecordsView.build(controller, owner, RecordsView.LEDGER);
            helper.assertValueEqual(ledger.ledger().views().getFirst().rows().getFirst().in(), 8L,
                    "recorded all along");
            helper.assertTrue(RecordsView.build(controller, owner, RecordsView.ALARMS).locked(RecordsView.ALARMS),
                    "the rules have a node of their own");
            research(helper, owner, "port_tier_2", "network_tuning");
            helper.assertValueEqual(RecordsView.build(controller, owner, RecordsView.ALARMS).alarms().rules().size(), 1,
                    "rules open");
        }).thenSucceed();
    }

    /**
     * With the three switches off a controller keeps no history, hears no moves (the network keeps no move journal for
     * it) and its rules give no signal, though a rule's condition holds; every page says the recorder is off.
     */
    private static void configOff(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        TestStorage.TestDevice device = NetworkGameTests.device(helper, core.north());
        ServerPlayer viewer = NetworkGameTests.player(helper.getLevel(), "recorder_off");
        helper.startSequence().thenWaitUntil(
                () -> helper.assertTrue(device.network() == NetworkGameTests.state(helper, core), "network formed"))
                .thenExecute(() -> {
                    NetworkGameTests.power(helper, core, 5_000);
                    ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
                    // At or below 100 % FE: always met.
                    helper.assertTrue(
                            controller.recorders().addRule(new AlarmSettings(AlarmSettings.Type.LOW_POWER, 100)),
                            "rule");
                    helper.assertValueEqual(NetworkGameTests.insert(NetworkGameTests.state(helper, core), COBBLE, 64),
                            64L, "insert");
                }).thenIdle(20).thenExecute(() -> {
                    ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
                    ControllerRecorders recorders = controller.recorders();
                    helper.assertTrue(controller.telemetry() == null, "no history");
                    helper.assertTrue(NetworkGameTests.state(helper, core).history() == null, "none for panels");
                    helper.assertTrue(recorders.ledger().bound() == null, "the ledger hears nothing");
                    helper.assertValueEqual(recorders.ledger().saved().rows(), 0, "no rows");
                    helper.assertValueEqual(recorders.rules().size(), 1, "the rule");
                    helper.assertTrue(recorders.rules().getFirst().evaluations() > 0, "the rule looked");
                    helper.assertTrue(!recorders.output() && controller.signal() == 0, "but gives no signal");
                    for (int page = 0; page < RecordsView.PAGES; page++) {
                        helper.assertTrue(RecordsView.build(controller, viewer, page).off(page),
                                "page " + page + " off");
                    }
                }).thenSucceed();
    }
}
