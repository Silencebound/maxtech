package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.serialization.JsonOps;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJob;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJobs;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.crafting.job.JobRun;
import dev.silencebound.maxtech.storage.crafting.job.JobState;
import dev.silencebound.maxtech.storage.crafting.provider.CraftingDeliveries;
import dev.silencebound.maxtech.storage.crafting.request.CraftingSession;
import dev.silencebound.maxtech.storage.crafting.request.RequestPayloads;
import dev.silencebound.maxtech.storage.display.DisplayMenu;
import dev.silencebound.maxtech.storage.display.DisplayPanelBlockEntity;
import dev.silencebound.maxtech.storage.display.DisplaySettings;
import dev.silencebound.maxtech.storage.display.DisplaySourceKind;
import dev.silencebound.maxtech.storage.display.ScreenGeometry;
import dev.silencebound.maxtech.storage.display.ScreenRows;
import dev.silencebound.maxtech.storage.display.task.Task;
import dev.silencebound.maxtech.storage.display.task.TaskBoardData;
import dev.silencebound.maxtech.storage.display.task.TaskBoardRef;
import dev.silencebound.maxtech.storage.display.task.TaskBoardSync;
import dev.silencebound.maxtech.storage.display.task.TaskBoards;
import dev.silencebound.maxtech.storage.display.task.TaskEdit;
import dev.silencebound.maxtech.storage.display.task.TaskFeed;
import dev.silencebound.maxtech.storage.display.task.TaskGoal;
import dev.silencebound.maxtech.storage.display.task.TaskTexts;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.terminal.TerminalAction;
import dev.silencebound.maxtech.storage.terminal.TerminalBlockEntity;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Task boards (M7C item 15): a right-click on a row ticks a hand task (deposit access, the row under the crosshair),
 * the editor's edits and refusals, auto goals ticked by events without polling (an item count, a crafting job's output
 * counted once), boards shared by name per team and per network, and a Logic Controller reading and setting a task.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class TaskBoardGameTests {
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey PLANKS = ItemKey.of(Items.OAK_PLANKS);
    private static final StackKey STICK = ItemKey.of(Items.STICK);
    private static final CraftingPattern STICKS = new CraftingPattern(CraftingPattern.Kind.CRAFTING,
            List.of(new PatternInput(new PatternIngredient.Exact(PLANKS), 2, Optional.empty(), false)),
            List.of(new PatternOutput(STICK, 4, 1f)), Optional.empty(), Optional.empty());
    private static final DisplaySettings BOARD = DisplaySettings.DEFAULT.withSource(DisplaySourceKind.TASK_BOARD)
            .withBoard(new TaskBoardRef("Base goals", false));
    private static final String[] NAMES = {"task_board_tick_by_hand", "task_board_editor", "task_board_item_goal",
            "task_board_crafted_goal", "task_board_crafted_other_network", "task_board_shared_and_saved",
            "task_board_logic"};

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        StorageTests.function("task_board_tick_by_hand", StorageTestEnvironment.clearing(TaskBoardGameTests::byHand));
        StorageTests.function("task_board_editor", StorageTestEnvironment.clearing(TaskBoardGameTests::editor));
        StorageTests.function("task_board_item_goal", StorageTestEnvironment.clearing(TaskBoardGameTests::itemGoal));
        StorageTests.function("task_board_crafted_goal",
                StorageTestEnvironment.clearing(TaskBoardGameTests::craftedGoal));
        StorageTests.function("task_board_crafted_other_network",
                StorageTestEnvironment.clearing(TaskBoardGameTests::craftedOtherNetwork));
        StorageTests.function("task_board_shared_and_saved",
                StorageTestEnvironment.clearing(TaskBoardGameTests::sharedAndSaved));
        StorageTests.function("task_board_logic", StorageTestEnvironment.clearing(TaskBoardGameTests::logic));
    }

    private TaskBoardGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("task_board"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 400);
        }
    }

    // ---- set-up

    /** A controller at x 1 placed by {@code owner}, a test device at x 2 and a board panel at x 3 facing north. */
    private static DisplayPanelBlockEntity setUp(GameTestHelper helper, ServerPlayer owner) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        BlockState state = helper.getBlockState(core);
        state.getBlock().setPlacedBy(helper.getLevel(), helper.absolutePos(core), state, owner,
                new ItemStack(ControllerContent.CONTROLLER.item().get()));
        NetworkGameTests.power(helper, core, 100_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        DisplayPanelBlockEntity panel = DisplayGameTests.panel(helper, NetworkGameTests.at(3), Direction.NORTH);
        panel.applyToWall(BOARD);
        panel.setOwner(OwnerRecord.of(owner));
        return panel;
    }

    private static NetworkState network(GameTestHelper helper, DisplayPanelBlockEntity panel) {
        NetworkState network = panel.network();
        if (network == null) {
            throw helper.assertionException(Component.literal("the panel is in no network"));
        }
        return network;
    }

    /** Waits for the panel's network to belong to {@code owner}: the controller's owner reaches it on a rebuild. */
    private static void owned(GameTestHelper helper, DisplayPanelBlockEntity panel, ServerPlayer owner) {
        helper.assertTrue(network(helper, panel).owner().isOwner(owner.getUUID()), "the network is not owned yet");
    }

    private static TaskBoardData data(GameTestHelper helper) {
        return TaskBoardData.get(helper.getLevel().getServer());
    }

    private static String key(GameTestHelper helper, DisplayPanelBlockEntity panel) {
        TaskBoardRef ref = panel.settings().board();
        return TaskBoards.key(network(helper, panel), ref.name(), ref.network())
                .orElseThrow(() -> helper.assertionException(Component.literal("no board key")));
    }

    /** The panel's board key, with the board emptied: boards are server-wide and may hold an earlier run's tasks. */
    private static String freshKey(GameTestHelper helper, DisplayPanelBlockEntity panel) {
        TaskBoardRef ref = panel.settings().board();
        String key = TaskBoards.key(network(helper, panel), ref.name(), ref.network())
                .orElseThrow(() -> helper.assertionException(Component.literal("no board key")));
        for (Task task : List.copyOf(data(helper).tasks(key))) {
            data(helper).remove(key, task.id());
        }
        return key;
    }

    private static Task task(GameTestHelper helper, String key, int index) {
        List<Task> tasks = data(helper).tasks(key);
        helper.assertTrue(index < tasks.size(),
                "task " + index + " is missing from " + key + " (" + data(helper).boardCount() + " boards)");
        return tasks.get(index);
    }

    /** {@code player} standing north of the panel at x 3, looking at the middle of shown row {@code row}. */
    private static void lookAtRow(GameTestHelper helper, ServerPlayer player, DisplayPanelBlockEntity panel, int row) {
        player.setPos(helper.absoluteVec(NetworkGameTests.at(3).getCenter()).add(0, -1, -1.5));
        ScreenGeometry wall = ScreenGeometry.wall(panel.getBlockPos(), Direction.NORTH, 1, 1);
        float step = ScreenRows.step(ScreenRows.label(1, 1));
        float y = ScreenRows.rowTop(1, 1, wall.bezel(), row) - step / 2;
        // Right is west for a screen facing north.
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

    // ---- tests

    /**
     * A hand task is ticked by a right-click on its row (who and when kept) and opened again by the next; a click on an
     * auto task's row changes nothing; a stranger without deposit access is refused, and a click beside the rows is no
     * row. The rows the screen would show say how many are done.
     */
    private static void byHand(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "board_owner");
        ServerPlayer stranger = NetworkGameTests.player(helper.getLevel(), "board_stranger");
        DisplayPanelBlockEntity panel = setUp(helper, owner);
        String[] key = new String[1];
        helper.startSequence().thenWaitUntil(() -> owned(helper, panel, owner)).thenExecute(() -> {
            key[0] = freshKey(helper, panel);
            helper.assertValueEqual(data(helper).add(key[0], "Base goals", "Build the furnaces", TaskGoal.HAND),
                    TaskBoardData.Result.DONE, "added");
            data(helper).add(key[0], "Base goals", "1000 iron",
                    new TaskGoal(TaskGoal.Kind.ITEM_COUNT, "minecraft:iron_ingot", 1000));
            lookAtRow(helper, owner, panel, 0);
            helper.assertTrue(panel.clickRow(owner), "the click on row 0 was taken");
            Task first = task(helper, key[0], 0);
            helper.assertTrue(first.done(), "ticked");
            helper.assertValueEqual(first.how(), Task.BY_HAND, "by hand");
            helper.assertValueEqual(first.by(), "board_owner", "who");
            helper.assertTrue(first.at() > 0, "when");
            lookAtRow(helper, owner, panel, 1);
            helper.assertTrue(panel.clickRow(owner), "the click on the auto row was taken");
            helper.assertTrue(!task(helper, key[0], 1).done(), "an auto task is not ticked by hand");
            lookAtRow(helper, stranger, panel, 0);
            helper.assertTrue(!panel.clickRow(stranger), "the stranger's click is refused");
            helper.assertTrue(task(helper, key[0], 0).done(), "still ticked after the stranger");
            ScreenRows rows = panel.worker().feed() instanceof TaskFeed feed
                    ? feed.rows(network(helper, panel), 0)
                    : ScreenRows.EMPTY;
            helper.assertValueEqual(rows.rows().size(), 2, "two rows");
            helper.assertValueEqual(rows.fill(), 500, "half done");
            helper.assertTrue(rows.rows().getFirst().has(ScreenRows.CHECKED), "the first row is ticked");
        }).thenIdle(1).thenExecute(() -> {
            lookAtRow(helper, owner, panel, 0);
            helper.assertTrue(panel.clickRow(owner), "the second click was taken");
            helper.assertTrue(!task(helper, key[0], 0).done(), "opened again");
            helper.assertValueEqual(task(helper, key[0], 0).by(), "", "nobody once open");
            lookAtRow(helper, owner, panel, 4);
            helper.assertTrue(!panel.clickRow(owner), "a row without a task falls through to the settings");
        }).thenSucceed();
    }

    /**
     * The editor's edits through {@link TaskEdit} with the display menu open: add (a goal naming no item is refused),
     * save (rename and goal), remove, and switching to the network's board; a second edit in the same tick changes
     * nothing; a player who may not configure the screen gets no answer and changes nothing.
     */
    private static void editor(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "editor_owner");
        ServerPlayer stranger = NetworkGameTests.player(helper.getLevel(), "editor_stranger");
        DisplayPanelBlockEntity panel = setUp(helper, owner);
        panel.applyToWall(BOARD.withBoard(new TaskBoardRef("Editor board", false)));
        String[] key = new String[1];
        helper.startSequence().thenWaitUntil(() -> owned(helper, panel, owner)).thenExecute(() -> {
            key[0] = freshKey(helper, panel);
            owner.setPos(helper.absoluteVec(NetworkGameTests.at(3).getCenter()).add(0, -1, -1.5));
            owner.containerMenu = DisplayMenu.forDevice(7, owner.getInventory(), panel);
            helper.assertValueEqual(
                    edit(owner, TaskEdit.ADD, 0, "Iron", new TaskGoal(TaskGoal.Kind.ITEM_COUNT, "no_such_item", 5)),
                    TaskTexts.NO_ITEM, "an unknown item is refused");
            helper.assertValueEqual(data(helper).tasks(key[0]).size(), 0, "nothing added");
        }).thenIdle(1).thenExecute(() -> {
            helper.assertValueEqual(
                    edit(owner, TaskEdit.ADD, 0, "Iron", new TaskGoal(TaskGoal.Kind.ITEM_COUNT, "iron_ingot", 5)),
                    TaskTexts.SAVED, "added");
            TaskBoardSync answer = TaskBoardSync.last();
            helper.assertTrue(answer != null && answer.tasks().size() == 1, "the answer lists the task");
            helper.assertValueEqual(key(helper, panel), key[0], "the key after the add");
            task(helper, key[0], 0);
        }).thenIdle(1).thenExecute(() -> {
            int id = task(helper, key[0], 0).id();
            helper.assertValueEqual(edit(owner, TaskEdit.SAVE, id, "Lots of iron",
                    new TaskGoal(TaskGoal.Kind.LABEL_COUNT, "furnaces", 8)), TaskTexts.SAVED, "saved");
            helper.assertValueEqual(edit(owner, TaskEdit.ADD, 0, "Too soon", TaskGoal.HAND), "",
                    "a second edit in one tick");
            helper.assertValueEqual(task(helper, key[0], 0).name(), "Lots of iron", "renamed");
            helper.assertValueEqual(task(helper, key[0], 0).goal().kind(), TaskGoal.Kind.LABEL_COUNT, "new goal");
            helper.assertValueEqual(data(helper).tasks(key[0]).size(), 1, "the second edit added nothing");
            stranger.setPos(owner.position());
            stranger.containerMenu = DisplayMenu.forDevice(8, stranger.getInventory(), panel);
            helper.assertTrue(
                    TaskEdit.apply(stranger, new TaskEdit(8, TaskEdit.REMOVE, id, "", TaskGoal.HAND, false)) == null,
                    "the stranger gets no answer");
            helper.assertValueEqual(data(helper).tasks(key[0]).size(), 1, "the stranger removed nothing");
        }).thenIdle(1).thenExecute(() -> {
            int id = task(helper, key[0], 0).id();
            helper.assertValueEqual(edit(owner, TaskEdit.REMOVE, id, "", TaskGoal.HAND), TaskTexts.SAVED, "removed");
            helper.assertValueEqual(data(helper).tasks(key[0]).size(), 0, "the board is empty");
        }).thenIdle(1).thenExecute(() -> {
            edit(owner, TaskEdit.BOARD, 0, "Editor board", TaskGoal.HAND, true);
            helper.assertTrue(panel.settings().board().network(), "the network's board");
            helper.assertTrue(!key(helper, panel).equals(key[0]), "another key");
        }).thenSucceed();
    }

    private static String edit(ServerPlayer player, int op, int task, String text, TaskGoal goal) {
        return edit(player, op, task, text, goal, false);
    }

    private static String edit(ServerPlayer player, int op, int task, String text, TaskGoal goal, boolean network) {
        String answer = TaskEdit.apply(player,
                new TaskEdit(player.containerMenu.containerId, op, task, text, goal, network));
        return answer == null ? "<none>" : answer;
    }

    /**
     * An item count goal of 10 cobblestone stays open at 6 and is ticked by the goal once 10 are stored, without anyone
     * near; after that the feed does not look again while nothing changes.
     */
    private static void itemGoal(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "goal_owner");
        DisplayPanelBlockEntity panel = setUp(helper, owner);
        panel.applyToWall(BOARD.withBoard(new TaskBoardRef("Goal board", false)));
        String[] key = new String[1];
        int[] looks = new int[1];
        helper.startSequence().thenWaitUntil(() -> owned(helper, panel, owner)).thenExecute(() -> {
            key[0] = freshKey(helper, panel);
            data(helper).add(key[0], "Base goals", "Cobble",
                    new TaskGoal(TaskGoal.Kind.ITEM_COUNT, "minecraft:cobblestone", 10));
            NetworkGameTests.insert(network(helper, panel), COBBLE, 6);
        }).thenIdle(5).thenExecute(() -> {
            helper.assertTrue(!task(helper, key[0], 0).done(), "6 of 10 is not done");
            NetworkGameTests.insert(network(helper, panel), COBBLE, 4);
        }).thenWaitUntil(() -> helper.assertTrue(task(helper, key[0], 0).done(), "10 of 10 is done"))
                .thenExecute(() -> helper.assertValueEqual(task(helper, key[0], 0).how(), Task.BY_GOAL, "by the goal"))
                // The tick's own change of the board is looked at once more; then all is quiet.
                .thenIdle(5).thenExecute(() -> looks[0] = feed(helper, panel).looks()).thenIdle(40).thenExecute(() -> {
                    helper.assertValueEqual(feed(helper, panel).looks(), looks[0], "no look while nothing changed");
                    NetworkGameTests.insert(network(helper, panel), COBBLE, 1);
                }).thenIdle(5).thenExecute(() -> helper.assertValueEqual(feed(helper, panel).looks(), looks[0],
                        "a done goal listens no more"))
                .thenSucceed();
    }

    private static TaskFeed feed(GameTestHelper helper, DisplayPanelBlockEntity panel) {
        if (panel.worker().feed() instanceof TaskFeed feed) {
            return feed;
        }
        throw helper.assertionException(Component.literal("the panel has no task feed"));
    }

    /**
     * One crafting network along x at {@code dz} blocks south: controller, device, pattern host, terminal, CPU, panel.
     */
    private record Crafter(CraftingTestContent.Host host, BlockPos terminal, DisplayPanelBlockEntity panel) {
    }

    /** A network board named "Crafting" with a crafted goal of {@code goal} sticks shows on the panel. */
    private static Crafter crafter(GameTestHelper helper, int dz) {
        BlockPos core = NetworkGameTests.at(1).south(dz);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2).south(dz));
        BlockPos hostPos = NetworkGameTests.at(3).south(dz);
        helper.setBlock(hostPos, CraftingTestContent.HOST.get());
        CraftingTestContent.Host host = helper.getBlockEntity(hostPos, CraftingTestContent.Host.class);
        host.setPatterns(List.of(STICKS));
        BlockPos terminal = NetworkGameTests.at(4).south(dz);
        helper.setBlock(terminal, TerminalContent.TERMINAL.get());
        helper.setBlock(NetworkGameTests.at(2).south(dz).above(), CpuContent.CORE_1.get());
        DisplayPanelBlockEntity panel = DisplayGameTests.panel(helper, NetworkGameTests.at(5).south(dz),
                Direction.NORTH);
        // Unowned networks have no team boards: the network's own board.
        panel.applyToWall(BOARD.withBoard(new TaskBoardRef("Crafting", true)));
        return new Crafter(host, terminal, panel);
    }

    /** Plans and starts 8 sticks at {@code crafter}'s terminal through {@code player}'s menu {@code containerId}. */
    private static void plan(GameTestHelper helper, ServerPlayer player, Crafter crafter, int containerId,
            List<CustomPacketPayload> sent) {
        NetworkGameTests.insert(network(helper, crafter.panel()), PLANKS, 4);
        TerminalMenu menu = TerminalMenu.server(containerId, player.getInventory(),
                helper.getBlockEntity(crafter.terminal(), TerminalBlockEntity.class), update -> {
                }, sent::add);
        player.containerMenu = menu;
        menu.clickMenuButton(player, new TerminalAction(TerminalAction.Kind.REFRESH, -1).buttonId());
        menu.broadcastChanges();
        helper.assertTrue(session(helper, menu).handle(new RequestPayloads.Request(containerId,
                RequestPayloads.Action.PLAN, 1, Optional.of(STICK), 8, Optional.empty())), "planned");
    }

    private static void start(GameTestHelper helper, ServerPlayer player) {
        if (!(player.containerMenu instanceof TerminalMenu menu)) {
            throw helper.assertionException(Component.literal("no terminal menu"));
        }
        helper.assertTrue(session(helper, menu).handle(new RequestPayloads.Request(menu.containerId,
                RequestPayloads.Action.START, 0, Optional.empty(), 0, Optional.empty())), "started");
    }

    private static boolean planned(List<CustomPacketPayload> sent) {
        for (CustomPacketPayload payload : sent) {
            if (payload instanceof RequestPayloads.Plan) {
                return true;
            }
        }
        return false;
    }

    /**
     * A crafted goal of 8 sticks: a terminal crafts 8 sticks on the board's network, the job ends done and its 8 count
     * once (hearing the same job again adds nothing), which ticks the goal.
     */
    private static void craftedGoal(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "craft_owner");
        Crafter crafter = crafter(helper, 0);
        List<CustomPacketPayload> sent = new ArrayList<>();
        String[] key = new String[1];
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, crafter.panel())).thenExecute(() -> {
            key[0] = freshKey(helper, crafter.panel());
            data(helper).add(key[0], "Crafting", "Sticks", new TaskGoal(TaskGoal.Kind.CRAFTED, "stick", 8));
            plan(helper, owner, crafter, 1, sent);
        }).thenWaitUntil(() -> helper.assertTrue(planned(sent), "the plan")).thenIdle(1).thenExecute(() -> {
            start(helper, owner);
            id[0] = JobQueue.jobsOn(network(helper, crafter.panel())).getFirst().id();
        }).thenWaitUntil(() -> helper.assertValueEqual(crafter.host().pushed, 2, "both crafts sent"))
                .thenExecute(() -> {
                    helper.assertValueEqual(task(helper, key[0], 0).progress(), 0L, "nothing crafted yet");
                    NetworkGameTests.insert(network(helper, crafter.panel()), STICK, 8);
                    CraftingDeliveries.delivered(network(helper, crafter.panel()), STICK, 8);
                }).thenWaitUntil(() -> helper.assertValueEqual(state(helper, id[0]), JobState.DONE, "ended"))
                .thenWaitUntil(() -> helper.assertTrue(task(helper, key[0], 0).done(), "8 crafted ticks the goal"))
                .thenExecute(() -> {
                    helper.assertValueEqual(task(helper, key[0], 0).progress(), 8L, "counted once");
                    helper.assertValueEqual(task(helper, key[0], 0).how(), Task.BY_GOAL, "by the goal");
                    // The same job heard again (its CPU's network, say) adds nothing.
                    data(helper).credit(key[0], id[0], STICK, 8);
                    helper.assertValueEqual(task(helper, key[0], 0).progress(), 8L, "still counted once");
                }).thenSucceed();
    }

    /**
     * Only a job that ends done counts, and only on its own network: network A crafts 8 sticks to the end, then a job
     * on network B ends partial. A's goal counts 8, B's nothing (a partial job's "finished" once credited A's job to
     * B).
     */
    private static void craftedOtherNetwork(GameTestHelper helper) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "craft_two");
        Crafter first = crafter(helper, 0);
        Crafter second = crafter(helper, 4);
        List<CustomPacketPayload> sent = new ArrayList<>();
        String[] keys = new String[2];
        UUID[] id = new UUID[2];
        helper.startSequence().thenWaitUntil(() -> {
            network(helper, first.panel());
            network(helper, second.panel());
        }).thenExecute(() -> {
            helper.assertTrue(network(helper, first.panel()) != network(helper, second.panel()), "two networks");
            keys[0] = freshKey(helper, first.panel());
            keys[1] = freshKey(helper, second.panel());
            helper.assertTrue(!keys[0].equals(keys[1]), "two boards");
            data(helper).add(keys[0], "Crafting", "Sticks", new TaskGoal(TaskGoal.Kind.CRAFTED, "stick", 100));
            data(helper).add(keys[1], "Crafting", "Sticks", new TaskGoal(TaskGoal.Kind.CRAFTED, "stick", 100));
            plan(helper, player, first, 1, sent);
        }).thenWaitUntil(() -> helper.assertTrue(planned(sent), "the first plan")).thenIdle(1).thenExecute(() -> {
            start(helper, player);
            id[0] = JobQueue.jobsOn(network(helper, first.panel())).getFirst().id();
        }).thenWaitUntil(() -> helper.assertValueEqual(first.host().pushed, 2, "both crafts sent")).thenExecute(() -> {
            NetworkGameTests.insert(network(helper, first.panel()), STICK, 8);
            CraftingDeliveries.delivered(network(helper, first.panel()), STICK, 8);
        }).thenWaitUntil(() -> helper.assertValueEqual(state(helper, id[0]), JobState.DONE, "the first ended"))
                .thenExecute(() -> {
                    helper.assertValueEqual(task(helper, keys[0], 0).progress(), 8L, "the first counted");
                    sent.clear();
                    plan(helper, player, second, 2, sent);
                }).thenWaitUntil(() -> helper.assertTrue(planned(sent), "the second plan")).thenIdle(1)
                .thenExecute(() -> {
                    start(helper, player);
                    id[1] = JobQueue.jobsOn(network(helper, second.panel())).getFirst().id();
                }).thenWaitUntil(() -> helper.assertValueEqual(second.host().pushed, 2, "the second's crafts sent"))
                .thenExecute(() -> {
                    CraftingJob job = CraftingJobs.get(helper.getLevel()).get(id[1]);
                    JobRun run = JobQueue.progress(helper.getLevel(), id[1]);
                    if (job == null || run == null) {
                        throw helper.assertionException(Component.literal("the second job does not run"));
                    }
                    JobQueue.ended(helper.getLevel(), job, run, JobState.PARTIAL, null);
                }).thenIdle(2).thenExecute(() -> {
                    helper.assertValueEqual(task(helper, keys[1], 0).progress(), 0L, "a partial job counts nothing");
                    helper.assertValueEqual(task(helper, keys[0], 0).progress(), 8L, "the first still 8");
                }).thenSucceed();
    }

    private static CraftingSession session(GameTestHelper helper, TerminalMenu menu) {
        CraftingSession session = menu.crafting();
        if (session == null) {
            throw helper.assertionException(Component.literal("no crafting session"));
        }
        return session;
    }

    private static JobState state(GameTestHelper helper, UUID id) {
        CraftingJob job = CraftingJobs.get(helper.getLevel()).get(id);
        return job == null ? JobState.DONE : job.state();
    }

    /**
     * Two networks of one owner share a team board by its name (a tick on one shows on the other); the network's board
     * of the same name is a different board, and a network without a controller has none. The boards survive a save and
     * load through their codec.
     */
    private static void sharedAndSaved(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "shared_owner");
        DisplayPanelBlockEntity first = setUp(helper, owner);
        BlockPos otherCore = NetworkGameTests.at(1).south(4);
        helper.setBlock(otherCore, ControllerContent.CONTROLLER.get());
        BlockState state = helper.getBlockState(otherCore);
        state.getBlock().setPlacedBy(helper.getLevel(), helper.absolutePos(otherCore), state, owner,
                new ItemStack(ControllerContent.CONTROLLER.item().get()));
        NetworkGameTests.power(helper, otherCore, 100_000);
        DisplayPanelBlockEntity second = DisplayGameTests.panel(helper, otherCore.east(), Direction.NORTH);
        second.applyToWall(BOARD);
        second.setOwner(OwnerRecord.of(owner));
        String[] ownKey = new String[1];
        helper.startSequence().thenWaitUntil(() -> {
            owned(helper, first, owner);
            owned(helper, second, owner);
        }).thenExecute(() -> {
            helper.assertTrue(network(helper, first) != network(helper, second), "two networks");
            String team = freshKey(helper, first);
            helper.assertValueEqual(key(helper, second), team, "one team board");
            data(helper).add(team, "Base goals", "Shared", TaskGoal.HAND);
            data(helper).setDone(team, task(helper, team, 0).id(), true, Task.BY_HAND, "shared_owner", 1);
            helper.assertTrue(task(helper, key(helper, second), 0).done(), "the tick shows on the other network");
            String own = TaskBoards.key(network(helper, first), "Base goals", true).orElseThrow();
            helper.assertTrue(!own.equals(team), "the network's board is another");
            helper.assertTrue(data(helper).tasks(own).isEmpty(), "and has no tasks");
            var saved = TaskBoardData.CODEC.encodeStart(JsonOps.INSTANCE, data(helper)).getOrThrow();
            TaskBoardData loaded = TaskBoardData.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
            helper.assertValueEqual(loaded.tasks(team), data(helper).tasks(team), "saved and loaded");
            // One team holds at most MAX_BOARDS_PER_OWNER boards, whatever the server holds.
            String prefix = team.substring(0, team.lastIndexOf('/') + 1);
            for (int i = 0; i < TaskBoardData.MAX_BOARDS_PER_OWNER; i++) {
                data(helper).add(prefix + "filler " + i, "filler " + i, "Filler", TaskGoal.HAND);
            }
            helper.assertValueEqual(data(helper).add(prefix + "one more", "one more", "Filler", TaskGoal.HAND),
                    TaskBoardData.Result.TOO_MANY_BOARDS, "the team's boards are capped");
            for (int i = 0; i < TaskBoardData.MAX_BOARDS_PER_OWNER; i++) {
                for (Task task : List.copyOf(data(helper).tasks(prefix + "filler " + i))) {
                    data(helper).remove(prefix + "filler " + i, task.id());
                }
            }
            // The second network's own board goes when its controller is broken.
            ownKey[0] = TaskBoards.key(network(helper, second), "Base goals", true).orElseThrow();
            data(helper).add(ownKey[0], "Base goals", "Own", TaskGoal.HAND);
            helper.destroyBlock(otherCore);
        }).thenWaitUntil(() -> helper.assertTrue(TaskBoards.key(network(helper, second), "Base goals", true).isEmpty(),
                "no network board without a controller")).thenExecute(() -> {
                    helper.assertTrue(data(helper).tasks(ownKey[0]).isEmpty(), "the broken core's board is gone");
                    helper.assertTrue(TaskBoards.key(network(helper, second), "Base goals", false).isPresent(),
                            "the owned panel network still has team boards");
                }).thenSucceed();
    }

    /**
     * A Logic Controller ticks a hand task with "set task" when its redstone changes, and a "task" node reads it as
     * done; a tick opened again by hand reaches the reader too, without polling. A task that does not exist gets a why
     * line.
     */
    private static void logic(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "tb_logic");
        BlockPos controller = NetworkGameTests.at(3);
        DisplayPanelBlockEntity panel = DisplayGameTests.panel(helper, NetworkGameTests.at(4), Direction.NORTH);
        panel.applyToWall(BOARD.withBoard(new TaskBoardRef("Base goals", true)));
        LogicGraph<LogicKey> setter = new LogicControllerGameTests.Graph().node(1, NodeTypes.REDSTONE_CHANGES, s -> s)
                .node(2, NodeTypes.SET_TASK, s -> s.withText("board", "Base goals").withText("task", "furnaces")
                        .withText("board_scope", "network"))
                .wire(1, "changed", 2, "do").build();
        LogicGraph<LogicKey> reader = new LogicControllerGameTests.Graph().node(1, NodeTypes.TASK_STATE,
                s -> s.withText("board", "base goals").withText("task", "Furnaces").withText("board_scope", "network"))
                .build();
        String[] key = new String[1];
        helper.startSequence().thenWaitUntil(() -> {
            LogicControllerGameTests.network(helper, entity);
            network(helper, panel);
        }).thenExecute(() -> {
            key[0] = freshKey(helper, panel);
            data(helper).add(key[0], "Base goals", "Furnaces", TaskGoal.HAND);
            entity.worker().setGraph(0, "Setter", setter);
            entity.worker().setGraph(1, "Reader", reader);
        }).thenWaitUntil(() -> helper.assertTrue(evaluatorRuns(entity, 1) > 0, "the reader ran")).thenExecute(() -> {
            helper.assertValueEqual(value(helper, entity, 1, 1, "done"), 0L, "open at first");
            BlockPos top = controller.above();
            helper.setBlock(top, Blocks.REDSTONE_BLOCK);
        }).thenWaitUntil(() -> helper.assertTrue(task(helper, key[0], 0).done(), "ticked by logic")).thenExecute(() -> {
            helper.assertValueEqual(task(helper, key[0], 0).how(), Task.BY_LOGIC, "by logic");
        }).thenWaitUntil(() -> helper.assertValueEqual(value(helper, entity, 1, 1, "done"), 1L, "the reader sees it"))
                .thenExecute(() -> data(helper).setDone(key[0], task(helper, key[0], 0).id(), false, Task.BY_HAND,
                        "tb_logic", 2))
                .thenWaitUntil(() -> helper.assertValueEqual(value(helper, entity, 1, 1, "done"), 0L,
                        "the reader sees it opened again"))
                .thenSucceed();
    }

    private static int evaluatorRuns(LogicControllerBlockEntity entity, int graph) {
        return entity.worker().evaluator(graph).map(evaluator -> (int) evaluator.runs()).orElse(0);
    }

    private static long value(GameTestHelper helper, LogicControllerBlockEntity entity, int graph, int node,
            String output) {
        var evaluator = entity.worker().evaluator(graph)
                .orElseThrow(() -> helper.assertionException(Component.literal("graph " + graph + " is paused")));
        return evaluator.value(evaluator.graph().slot(node, output));
    }
}
