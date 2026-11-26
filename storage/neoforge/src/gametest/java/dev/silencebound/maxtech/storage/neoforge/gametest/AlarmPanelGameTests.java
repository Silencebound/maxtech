package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlock;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlockEntity;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.controller.ControllerMenu;
import dev.silencebound.maxtech.storage.controller.alarm.Alarm;
import dev.silencebound.maxtech.storage.controller.alarm.AlarmCenter;
import dev.silencebound.maxtech.storage.controller.alarm.AlarmKind;
import dev.silencebound.maxtech.storage.controller.alarm.AlarmTabPayload;
import dev.silencebound.maxtech.storage.controller.alarm.AlarmTexts;
import dev.silencebound.maxtech.storage.display.DisplayPanelBlockEntity;
import dev.silencebound.maxtech.storage.display.DisplaySettings;
import dev.silencebound.maxtech.storage.display.DisplaySourceKind;
import dev.silencebound.maxtech.storage.display.ScreenGeometry;
import dev.silencebound.maxtech.storage.display.ScreenRows;
import dev.silencebound.maxtech.storage.display.alarm.AlarmFeed;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.controller.LogicContent;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The alarm panel (M7C item 16): low power raised, acknowledged on the controller's Alarms tab (who and when, deposit
 * access), cleared and raised again unacknowledged; the alarms kept through a save; a display showing them, a click on
 * a fresh row acknowledging it; and a Logic Controller's rule paused by an error raising an alarm that clears once the
 * rule is fixed.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class AlarmPanelGameTests {
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey RAW_IRON = ItemKey.of(Items.RAW_IRON);
    private static final StackKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final DisplaySettings PANEL = DisplaySettings.DEFAULT.withSource(DisplaySourceKind.ALARMS);
    private static final String[] NAMES = {"alarm_panel_raise_ack_reraise", "alarm_panel_saved",
            "alarm_panel_display_click", "alarm_panel_rule_error", "alarm_panel_storage_full", "alarm_panel_prune",
            "alarm_panel_cap", "alarm_panel_stuck_craft"};

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        StorageTests.function("alarm_panel_raise_ack_reraise",
                StorageTestEnvironment.clearing(AlarmPanelGameTests::raiseAckReraise));
        StorageTests.function("alarm_panel_saved", StorageTestEnvironment.clearing(AlarmPanelGameTests::saved));
        StorageTests.function("alarm_panel_display_click",
                StorageTestEnvironment.clearing(AlarmPanelGameTests::displayClick));
        StorageTests.function("alarm_panel_storage_full",
                StorageTestEnvironment.clearing(AlarmPanelGameTests::storageFull));
        StorageTests.function("alarm_panel_prune", StorageTestEnvironment.clearing(AlarmPanelGameTests::prune));
        StorageTests.function("alarm_panel_cap", StorageTestEnvironment.clearing(AlarmPanelGameTests::cap));
        StorageTests.function("alarm_panel_stuck_craft",
                StorageTestEnvironment.clearing(AlarmPanelGameTests::stuckCraft));
        StorageTests.function("alarm_panel_rule_error",
                StorageTestEnvironment.clearing(AlarmPanelGameTests::ruleError));
    }

    private AlarmPanelGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("alarm_panel"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 400);
        }
    }

    // ---- set-up

    /** A full controller at x 1 placed by {@code owner} and a test device at x 2. */
    private static ControllerBlockEntity setUp(GameTestHelper helper, ServerPlayer owner) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        BlockState state = helper.getBlockState(core);
        state.getBlock().setPlacedBy(helper.getLevel(), helper.absolutePos(core), state, owner,
                new ItemStack(ControllerContent.CONTROLLER.item().get()));
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        return NetworkGameTests.controllerEntity(helper, core);
    }

    private static NetworkState network(GameTestHelper helper, ControllerBlockEntity core) {
        NetworkState network = core.network();
        if (network == null) {
            throw helper.assertionException(Component.literal("the controller is in no network"));
        }
        return network;
    }

    private static void owned(GameTestHelper helper, ControllerBlockEntity core, ServerPlayer owner) {
        helper.assertTrue(network(helper, core).owner().isOwner(owner.getUUID()), "the network is not owned yet");
    }

    private static Optional<Alarm> alarm(ControllerBlockEntity core, AlarmKind kind) {
        for (Alarm alarm : core.alarms().alarms()) {
            if (alarm.kind() == kind.id()) {
                return Optional.of(alarm);
            }
        }
        return Optional.empty();
    }

    private static Alarm present(GameTestHelper helper, ControllerBlockEntity core, AlarmKind kind) {
        return alarm(core, kind)
                .orElseThrow(() -> helper.assertionException(Component.literal("no " + kind + " alarm")));
    }

    private static int alarmNotices(NetworkState network) {
        int count = 0;
        for (NetworkNotices.Notice notice : network.notices().lines()) {
            if (notice.key().equals(AlarmTexts.NOTICE)) {
                count++;
            }
        }
        return count;
    }

    // ---- tests

    /**
     * Draining the controller raises low power once (a log line), with nothing polling while nothing changes; the owner
     * acknowledges it on the Alarms tab (a stranger cannot); power back clears it, keeping who saw it; draining again
     * raises it anew, unacknowledged, but within {@code alarms.reannounceTicks} without a second log line.
     */
    private static void raiseAckReraise(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "alarm_owner");
        ServerPlayer stranger = NetworkGameTests.player(helper.getLevel(), "alarm_stranger");
        ControllerBlockEntity core = setUp(helper, owner);
        List<AlarmTabPayload> sent = new ArrayList<>();
        ControllerMenu[] menu = new ControllerMenu[1];
        int[] looks = new int[1];
        helper.startSequence().thenWaitUntil(() -> owned(helper, core, owner)).thenIdle(5).thenExecute(() -> {
            helper.assertTrue(alarm(core, AlarmKind.LOW_POWER).filter(Alarm::active).isEmpty(),
                    "a full controller has no low power alarm");
            core.buffer().load(0);
        }).thenWaitUntil(() -> helper.assertTrue(present(helper, core, AlarmKind.LOW_POWER).fresh(), "raised"))
                .thenExecute(() -> {
                    helper.assertValueEqual(alarmNotices(network(helper, core)), 1, "one log line");
                    menu[0] = ControllerMenu.server(3, core, owner, payload -> {
                    }, sent::add);
                    menu[0].broadcastChanges();
                    helper.assertTrue(!sent.isEmpty() && sent.getLast().alarms().getFirst().fresh(),
                            "the tab got the fresh alarm first");
                    helper.assertTrue(!menu[0].clickMenuButton(stranger, ControllerMenu.ALARM_BUTTON),
                            "the stranger may not acknowledge");
                    helper.assertTrue(menu[0].clickMenuButton(owner, ControllerMenu.ALARM_BUTTON), "acknowledged");
                    Alarm acked = present(helper, core, AlarmKind.LOW_POWER);
                    helper.assertValueEqual(acked.ackBy(), "alarm_owner", "who");
                    helper.assertTrue(acked.ackAt() > 0 && acked.active(), "when, and still active");
                    helper.assertTrue(!menu[0].clickMenuButton(owner, ControllerMenu.ALARM_BUTTON),
                            "a second ack changes nothing");
                    looks[0] = core.alarms().evaluations();
                }).thenIdle(40).thenExecute(() -> {
                    helper.assertValueEqual(core.alarms().evaluations(), looks[0], "nothing polls while idle");
                    core.buffer().receive(1_000_000);
                })
                .thenWaitUntil(() -> helper.assertTrue(!present(helper, core, AlarmKind.LOW_POWER).active(), "cleared"))
                .thenExecute(() -> {
                    Alarm cleared = present(helper, core, AlarmKind.LOW_POWER);
                    helper.assertValueEqual(cleared.ackBy(), "alarm_owner", "who saw it stays");
                    helper.assertTrue(cleared.cleared() > 0, "when it cleared");
                    core.buffer().load(0);
                })
                .thenWaitUntil(
                        () -> helper.assertTrue(present(helper, core, AlarmKind.LOW_POWER).fresh(), "raised again"))
                .thenExecute(() -> {
                    helper.assertValueEqual(alarmNotices(network(helper, core)), 1, "not announced again so soon");
                    helper.assertValueEqual(core.alarms().alarms().size(), 1, "still one alarm");
                    menu[0].broadcastChanges();
                    helper.assertTrue(sent.getLast().alarms().getFirst().fresh(), "the tab shows it fresh again");
                }).thenSucceed();
    }

    /** An acknowledged and a cleared alarm come back from the controller's save as they were. */
    private static void saved(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "alarm_saver");
        ControllerBlockEntity core = setUp(helper, owner);
        helper.startSequence().thenWaitUntil(() -> owned(helper, core, owner)).thenExecute(() -> core.buffer().load(0))
                .thenWaitUntil(() -> helper.assertTrue(alarm(core, AlarmKind.LOW_POWER).isPresent(), "raised"))
                .thenExecute(() -> {
                    long now = helper.getLevel().getGameTime();
                    helper.assertTrue(core.alarms().acknowledge("low_power", "alarm_saver", now), "acknowledged");
                    CompoundTag saved = core.saveWithoutMetadata(helper.getLevel().registryAccess());
                    ControllerBlockEntity copy = new ControllerBlockEntity(core.getBlockPos(), core.getBlockState());
                    copy.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING,
                            helper.getLevel().registryAccess(), saved));
                    helper.assertValueEqual(copy.alarms().alarms(), core.alarms().alarms(), "after a reload");
                    helper.assertValueEqual(copy.alarms().alarms().getFirst().ackBy(), "alarm_saver", "who");
                }).thenSucceed();
    }

    /**
     * A display showing the alarm panel lists the fresh alarm first in red; a right-click on its row acknowledges it
     * (the owner's name), and the row turns amber with who saw it.
     */
    private static void displayClick(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "alarm_viewer");
        ControllerBlockEntity core = setUp(helper, owner);
        DisplayPanelBlockEntity panel = DisplayGameTests.panel(helper, NetworkGameTests.at(3), Direction.NORTH);
        panel.applyToWall(PANEL);
        panel.setOwner(OwnerRecord.of(owner));
        helper.startSequence().thenWaitUntil(() -> owned(helper, core, owner)).thenExecute(() -> core.buffer().load(0))
                .thenWaitUntil(() -> helper.assertTrue(alarm(core, AlarmKind.LOW_POWER).isPresent(), "raised"))
                .thenExecute(() -> {
                    if (!(panel.worker().feed() instanceof AlarmFeed feed)) {
                        throw helper.assertionException(Component.literal("the panel has no alarm feed"));
                    }
                    helper.assertTrue(feed.center() == core.alarms(), "the feed shows the core's");
                    ScreenRows rows = feed.rows(network(helper, core), 0);
                    helper.assertValueEqual(rows.rows().getFirst().tone(), ScreenRows.BAD, "fresh is red");
                    lookAtRow(helper, owner, panel, 0);
                    helper.assertTrue(panel.clickRow(owner), "the click was taken");
                    Alarm alarm = present(helper, core, AlarmKind.LOW_POWER);
                    helper.assertValueEqual(alarm.ackBy(), "alarm_viewer", "acknowledged from the display");
                    ScreenRows after = feed.rows(network(helper, core), 0);
                    helper.assertValueEqual(after.rows().getFirst().tone(), ScreenRows.WARN, "seen is amber");
                }).thenSucceed();
    }

    /**
     * Saving a rule with an error on a Logic Controller of the network raises a rule error alarm naming how many rules
     * are paused; saving a working version clears it.
     */
    private static void ruleError(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "alarm_rules");
        ControllerBlockEntity core = setUp(helper, owner);
        helper.setBlock(NetworkGameTests.at(3), LogicContent.CONTROLLER.get());
        LogicControllerBlockEntity logic = LogicControllerGameTests.entity(helper, NetworkGameTests.at(3));
        logic.setOwner(OwnerRecord.of(owner));
        LogicGraph<LogicKey> broken = new LogicControllerGameTests.Graph().node(4, NodeTypes.COMPARE, s -> s)
                .node(5, NodeTypes.NOTIFY, s -> s.withText("text", "broken")).build();
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, logic)).thenExecute(() -> {
            helper.assertTrue(logic.worker().setGraph(0, "Broken", broken).orElseThrow().paused(), "paused");
            helper.assertValueEqual(logic.worker().pausedRules(), 1, "one paused rule");
        }).thenWaitUntil(() -> helper.assertTrue(present(helper, core, AlarmKind.RULE_ERROR).fresh(), "raised"))
                .thenExecute(() -> logic.worker().setGraph(0, "Fixed",
                        LogicControllerGameTests.crossesNotify(ItemKey.of(Items.COBBLESTONE), 10, "fine")))
                .thenWaitUntil(() -> helper.assertTrue(!present(helper, core, AlarmKind.RULE_ERROR).active(),
                        "cleared once fixed"))
                .thenExecute(() -> helper.assertTrue(AlarmCenter.of(network(helper, core)) == core.alarms(),
                        "the network's panel is the core's"))
                .thenSucceed();
    }

    /**
     * Storage at 95% raises storage nearly full; at 87% (inside the 5% clear margin under 90%) it still holds; at 80%
     * it clears. Content changes look at most once per {@code alarms.recheckTicks} (100), so each step waits past it.
     */
    private static void storageFull(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "alarm_filler");
        ControllerBlockEntity core = setUp(helper, owner);
        TestStorage.TestDevice device = helper.getBlockEntity(NetworkGameTests.at(2), TestStorage.TestDevice.class);
        device.provider.countBytes();
        int[] looks = new int[1];
        helper.startSequence().thenWaitUntil(() -> owned(helper, core, owner))
                .thenExecute(() -> fill(helper, core, device, 95_000))
                .thenWaitUntil(
                        () -> helper.assertTrue(present(helper, core, AlarmKind.STORAGE_FULL).fresh(), "raised at 95%"))
                .thenIdle(110).thenExecute(() -> {
                    looks[0] = core.alarms().evaluations();
                    fill(helper, core, device, 87_000);
                }).thenWaitUntil(() -> helper.assertTrue(core.alarms().evaluations() > looks[0], "looked at 87%"))
                .thenExecute(() -> helper.assertTrue(present(helper, core, AlarmKind.STORAGE_FULL).active(),
                        "still held inside the clear margin"))
                .thenIdle(110).thenExecute(() -> fill(helper, core, device, 80_000)).thenWaitUntil(() -> helper
                        .assertTrue(!present(helper, core, AlarmKind.STORAGE_FULL).active(), "cleared at 80%"))
                .thenSucceed();
    }

    /**
     * Sets the device to hold {@code amount} cobblestone: all but one behind the network's back (inserting costs FE per
     * item), the last one inserted so the network reports a content change.
     */
    private static void fill(GameTestHelper helper, ControllerBlockEntity core, TestStorage.TestDevice device,
            long amount) {
        device.provider.setDirect(COBBLE, amount - 1);
        core.buffer().receive(1_000_000);
        helper.assertValueEqual(NetworkGameTests.insert(network(helper, core), COBBLE, 1), 1L, "the last one");
    }

    /** A cleared alarm kept past {@code alarms.clearedKeepTicks} is dropped on the next look; a fresher one stays. */
    private static void prune(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "alarm_pruner");
        ControllerBlockEntity core = setUp(helper, owner);
        helper.startSequence().thenWaitUntil(() -> owned(helper, core, owner)).thenIdle(5).thenExecute(() -> {
            long now = helper.getLevel().getGameTime();
            Component text = Component.literal("old");
            core.alarms().load(List.of(Alarm.raise("test/old", AlarmKind.OFFLINE, text, 1).clear(now - 12_001),
                    Alarm.raise("test/new", AlarmKind.OFFLINE, text, 1).clear(now)));
            core.alarms().recheck();
        }).thenWaitUntil(() -> helper.assertValueEqual(core.alarms().alarms().size(), 1, "the old one dropped"))
                .thenExecute(() -> helper.assertValueEqual(core.alarms().alarms().getFirst().key(), "test/new",
                        "the fresher one stays"))
                .thenSucceed();
    }

    /**
     * With 64 alarms that all hold, a new one is not kept; with one of them cleared, the cleared one makes room.
     */
    private static void cap(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "alarm_capper");
        ControllerBlockEntity core = setUp(helper, owner);
        int[] looks = new int[1];
        helper.startSequence().thenWaitUntil(() -> owned(helper, core, owner)).thenIdle(5).thenExecute(() -> {
            core.alarms().load(filler(helper, AlarmCenter.MAX_ALARMS, false));
            looks[0] = core.alarms().evaluations();
            core.buffer().load(0);
        }).thenWaitUntil(() -> helper.assertTrue(core.alarms().evaluations() > looks[0], "looked")).thenExecute(() -> {
            helper.assertTrue(alarm(core, AlarmKind.LOW_POWER).filter(a -> a.key().equals("low_power")).isEmpty(),
                    "no room for low power");
            helper.assertValueEqual(core.alarms().alarms().size(), AlarmCenter.MAX_ALARMS, "still 64");
            core.buffer().receive(1_000_000);
        }).thenIdle(20).thenExecute(() -> {
            core.alarms().load(filler(helper, AlarmCenter.MAX_ALARMS, true));
            core.buffer().load(0);
        }).thenWaitUntil(
                () -> helper.assertTrue(core.alarms().alarms().stream().anyMatch(a -> a.key().equals("low_power")),
                        "low power took the cleared one's place"))
                .thenExecute(() -> {
                    helper.assertValueEqual(core.alarms().alarms().size(), AlarmCenter.MAX_ALARMS, "still 64");
                    helper.assertTrue(core.alarms().alarms().stream().noneMatch(a -> a.key().equals("test/0")),
                            "the cleared one went");
                }).thenSucceed();
    }

    /** {@code count} alarms that hold, the first one cleared when {@code firstCleared}. */
    private static List<Alarm> filler(GameTestHelper helper, int count, boolean firstCleared) {
        long now = helper.getLevel().getGameTime();
        List<Alarm> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Alarm alarm = Alarm.raise("test/" + i, AlarmKind.OFFLINE, Component.literal("filler"), now);
            out.add(i == 0 && firstCleared ? alarm.clear(now) : alarm);
        }
        return out;
    }

    /**
     * A job whose provider faces a full chest raises a craft stuck alarm naming the machine full; with room and the job
     * resumed, the batch goes in and the alarm clears.
     */
    private static void stuckCraft(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "alarm_crafter");
        ControllerBlockEntity core = setUp(helper, owner);
        helper.setBlock(NetworkGameTests.at(4), Blocks.CHEST);
        ChestBlockEntity chest = helper.getBlockEntity(NetworkGameTests.at(4), ChestBlockEntity.class);
        for (int i = 0; i < chest.getContainerSize(); i++) {
            chest.setItem(i, new ItemStack(Items.STONE, 64));
        }
        helper.setBlock(NetworkGameTests.at(3), ProviderContent.PROVIDER.get().defaultBlockState()
                .setValue(PatternProviderBlock.FACING, Direction.EAST));
        PatternProviderBlockEntity provider = helper.getBlockEntity(NetworkGameTests.at(3),
                PatternProviderBlockEntity.class);
        provider.logic().slots().setItem(0,
                PatternItem.encode(new CraftingPattern(CraftingPattern.Kind.PROCESSING,
                        List.of(new PatternInput(new PatternIngredient.Exact(RAW_IRON), 1, Optional.empty(), false)),
                        List.of(new PatternOutput(IRON, 1, 1f)), Optional.empty(), Optional.empty())));
        helper.setBlock(NetworkGameTests.at(2).above(), CpuContent.CORE_1.get());
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> owned(helper, core, owner)).thenWaitUntil(
                () -> helper.assertTrue(network(helper, core).patterns().providerCount() > 0, "the provider joined"))
                .thenExecute(() -> {
                    NetworkState network = network(helper, core);
                    NetworkGameTests.insert(network, RAW_IRON, 1);
                    CraftingPlanner.Result plan = CraftingPlanner.planNow(network, IRON, 1);
                    id[0] = JobQueue.submit(network, network.nodes().iterator().nextLong(), plan,
                            JobQueue.Request.player(UUID.randomUUID())).job().orElseThrow().id();
                }).thenWaitUntil(() -> helper.assertTrue(present(helper, core, AlarmKind.CRAFT_STUCK).fresh(),
                        "craft stuck raised"))
                .thenExecute(() -> {
                    helper.assertTrue(present(helper, core, AlarmKind.CRAFT_STUCK).key().endsWith("/MACHINE_FULL"),
                            "the machine is full");
                    chest.setItem(0, ItemStack.EMPTY);
                    helper.assertTrue(JobQueue.resume(helper.getLevel(), id[0]), "resumed");
                })
                .thenWaitUntil(() -> helper.assertTrue(!present(helper, core, AlarmKind.CRAFT_STUCK).active(),
                        "cleared once the batch went in"))
                .thenExecute(() -> JobQueue.cancel(helper.getLevel(), id[0])).thenSucceed();
    }

    /** {@code player} standing north of the panel at x 3, looking at the middle of shown row {@code row}. */
    private static void lookAtRow(GameTestHelper helper, ServerPlayer player, DisplayPanelBlockEntity panel, int row) {
        player.setPos(helper.absoluteVec(NetworkGameTests.at(3).getCenter()).add(0, -1, -1.5));
        ScreenGeometry wall = ScreenGeometry.wall(panel.getBlockPos(), Direction.NORTH, 1, 1);
        float step = ScreenRows.step(ScreenRows.label(1, 1));
        float y = ScreenRows.rowTop(1, 1, wall.bezel(), row) - step / 2;
        Vec3 target = wall.corner().add(-0.5, y, 0);
        Vec3 eye = player.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        player.setXRot(Mth.wrapDegrees((float) -(Mth.atan2(dy, flat) * 180.0 / Math.PI)));
        player.setYRot(Mth.wrapDegrees((float) (Mth.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F));
        player.xRotO = player.getXRot();
        player.yRotO = player.getYRot();
    }
}
