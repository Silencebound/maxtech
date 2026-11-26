package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.SecuritySettings;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.logic.GraphError;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.LogicLang;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.controller.EditorPayloads;
import dev.silencebound.maxtech.storage.logic.controller.EditorTexts;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerMenu;
import dev.silencebound.maxtech.storage.logic.controller.LogicEditing;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.logic.controller.LogicWorker;
import dev.silencebound.maxtech.storage.logic.editor.EditorNote;
import dev.silencebound.maxtech.storage.logic.editor.LiveLine;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.jspecify.annotations.Nullable;

/**
 * The node editor's server side (M7L area F): a save is decoded and checked on the server and refused when it is not
 * from the graph's editor, too soon or unreadable; a graph with errors is saved but paused; the editor needs the logic
 * access level; one player edits a graph at a time and the lock frees when the editor leaves; live values go out only
 * while an editor is open.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class LogicEditorGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("logic_editor_save_checks_the_graph", LogicEditorGameTests::saveChecks);
        register("logic_editor_errors_save_paused", LogicEditorGameTests::errorsSavePaused);
        register("logic_editor_needs_logic_access", LogicEditorGameTests::access);
        register("logic_editor_one_editor_at_a_time", LogicEditorGameTests::oneEditor);
        register("logic_editor_live_only_while_open", LogicEditorGameTests::liveOnlyWhileOpen);
        register("logic_editor_live_watched_count", LogicEditorGameTests::liveWatchedCount);
        register("logic_editor_index_past_count", LogicEditorGameTests::indexPastCount);
        register("logic_editor_over_limit_paused", LogicEditorGameTests::overLimit);
    }

    private LogicEditorGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("logic_editor"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 300);
        }
    }

    // ---- set-up

    /** A player standing at the logic controller, in reach. */
    private static ServerPlayer near(GameTestHelper helper, String name) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), name);
        player.setPos(helper.absolutePos(NetworkGameTests.at(3)).getCenter());
        return player;
    }

    /** Opens the controller's menu for {@code player} as container {@code id}. */
    private static LogicControllerMenu menu(ServerPlayer player, int id, LogicControllerBlockEntity entity) {
        LogicControllerMenu menu = LogicControllerMenu.forController(id, entity);
        player.containerMenu = menu;
        return menu;
    }

    private static EditorPayloads.Save save(GameTestHelper helper, int id, int index, String name,
            LogicGraph<LogicKey> graph) {
        return new EditorPayloads.Save(id, index, name, LogicEditing.encode(helper.getLevel().registryAccess(), graph));
    }

    private static <T> T present(GameTestHelper helper, @Nullable T value, String what) {
        if (value == null) {
            throw helper.assertionException(Component.literal("expected " + what));
        }
        return value;
    }

    /** A graph with an error: a notice without a trigger. */
    private static LogicGraph<LogicKey> broken() {
        return new LogicControllerGameTests.Graph().node(1, NodeTypes.NOTIFY, s -> s.withText("text", "lonely"))
                .build();
    }

    /** A network secured so {@code crafter} has craft access and {@code logician} logic access. */
    private static void secure(GameTestHelper helper, ServerPlayer owner, ServerPlayer crafter, ServerPlayer logician) {
        BlockPos core = NetworkGameTests.at(1);
        NetworkGameTests.controllerEntity(helper, core).setOwner(OwnerRecord.of(owner));
        ControllerClusterGameTests.expansion(helper, core.above(), ExpansionContent.SECURITY_MODULE);
        NetworkGameTests.controllerEntity(helper, core)
                .setSecurity(SecuritySettings.DEFAULT
                        .withPlayer(crafter.getUUID(), crafter.getGameProfile().name(), AccessLevel.CRAFT)
                        .withPlayer(logician.getUUID(), logician.getGameProfile().name(), AccessLevel.LOGIC));
    }

    // ---- tests

    /**
     * The editor opens graph 0 of an empty controller as a new graph; a graph that does not decode, a save too soon
     * after the last and a save to another graph than the one opened are refused and change nothing; a good save is
     * applied at once with a cleaned name.
     */
    private static void saveChecks(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "le_saver");
        ServerPlayer owner = near(helper, "le_saver");
        entity.setOwner(OwnerRecord.of(owner));
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            menu(owner, 5, entity);
            helper.assertTrue(LogicEditing.open(owner, 4, 0) == null, "another container id");
            EditorPayloads.Graph opened = present(helper, LogicEditing.open(owner, 5, 0), "the new graph");
            helper.assertTrue(!opened.readOnly(), "the opener edits");
            helper.assertTrue(opened.notes().isEmpty(), "nothing to note on an empty graph");
            helper.assertTrue(LogicEditing.open(owner, 5, 0) == null, "a second open in the same tick");

            CompoundTag future = new CompoundTag();
            future.putInt("version", 99);
            EditorPayloads.Saved unreadable = present(helper,
                    LogicEditing.save(owner, new EditorPayloads.Save(5, 0, "future", future)), "an answer");
            helper.assertTrue(!unreadable.saved(), "an unreadable graph is refused");
            helper.assertValueEqual(unreadable.refusal(), EditorTexts.REFUSED_UNREADABLE, "refusal");
            helper.assertValueEqual(entity.worker().size(), 0, "nothing saved");
            EditorPayloads.Saved afterRefused = present(helper,
                    LogicEditing.save(owner, save(helper, 5, 0, "x", crossesNotify())), "an answer");
            helper.assertValueEqual(afterRefused.refusal(), EditorTexts.REFUSED_TOO_FAST,
                    "a refused save counts against the limit");
        }).thenIdle(LogicEditing.SAVE_INTERVAL + 1).thenExecute(() -> {
            EditorPayloads.Saved saved = present(helper,
                    LogicEditing.save(owner, save(helper, 5, 0, "Cobble§c full\n", crossesNotify())), "an answer");
            helper.assertTrue(saved.saved(), "saved");
            helper.assertTrue(saved.notes().isEmpty(), "no notes: " + saved.notes());
            helper.assertValueEqual(entity.worker().size(), 1, "graph added");
            helper.assertValueEqual(entity.worker().name(0), "Cobblec full", "name cleaned");
            helper.assertValueEqual(entity.worker().status(0), LogicWorker.Status.RUNNING, "running");

            EditorPayloads.Saved again = present(helper,
                    LogicEditing.save(owner, save(helper, 5, 0, "again", broken())), "an answer");
            helper.assertTrue(!again.saved(), "a second save in the same tick");
            helper.assertValueEqual(again.refusal(), EditorTexts.REFUSED_TOO_FAST, "refusal");

            // Closing the screen and opening it again (a new menu) does not reset the limit.
            menu(owner, 12, entity);
            present(helper, LogicEditing.open(owner, 12, 0), "reopened");
            EditorPayloads.Saved reopened = present(helper,
                    LogicEditing.save(owner, save(helper, 12, 0, "again", broken())), "an answer");
            helper.assertValueEqual(reopened.refusal(), EditorTexts.REFUSED_TOO_FAST, "limit after reopening");
            helper.assertValueEqual(entity.worker().status(0), LogicWorker.Status.RUNNING, "unchanged");
        }).thenIdle(LogicEditing.SAVE_INTERVAL + 1).thenExecute(() -> {
            menu(owner, 5, entity);
            present(helper, LogicEditing.open(owner, 5, 0), "the first menu again");
            EditorPayloads.Saved other = present(helper,
                    LogicEditing.save(owner, save(helper, 5, 1, "other", broken())), "an answer");
            helper.assertTrue(!other.saved(), "a save to a graph the editor did not open");
            helper.assertValueEqual(other.refusal(), EditorTexts.REFUSED_NOT_EDITOR, "refusal");
            helper.assertValueEqual(entity.worker().size(), 1, "still one graph");
            helper.assertValueEqual(entity.worker().status(0), LogicWorker.Status.RUNNING, "unchanged");
        }).thenSucceed();
    }

    /**
     * Graph indices past the next free one, or past the screen's rows, open nothing; a session whose graph slot went
     * away (removed by a command) saves nothing.
     */
    private static void indexPastCount(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "le_indexer");
        ServerPlayer owner = near(helper, "le_indexer");
        entity.setOwner(OwnerRecord.of(owner));
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            entity.worker().setGraph(0, "First", crossesNotify());
            menu(owner, 13, entity);
            helper.assertTrue(LogicEditing.open(owner, 13, 2) == null, "past the next free graph");
        }).thenIdle(LogicEditing.OPEN_INTERVAL).thenExecute(() -> {
            helper.assertTrue(LogicEditing.open(owner, 13, -1) == null, "a negative index");
        }).thenIdle(LogicEditing.OPEN_INTERVAL).thenExecute(() -> {
            present(helper, LogicEditing.open(owner, 13, 1), "the next free graph");
            entity.worker().removeGraph(0);
            EditorPayloads.Saved saved = present(helper,
                    LogicEditing.save(owner, save(helper, 13, 1, "Gone", crossesNotify())), "an answer");
            helper.assertTrue(!saved.saved(), "graph 1 of a controller with none");
            helper.assertValueEqual(saved.refusal(), EditorTexts.REFUSED_NO_SLOT, "refusal");
            helper.assertValueEqual(entity.worker().size(), 0, "nothing saved");
            for (int i = 0; i < LogicControllerMenu.ROWS; i++) {
                entity.worker().setGraph(i, "Row " + i, crossesNotify());
            }
        }).thenIdle(LogicEditing.OPEN_INTERVAL).thenExecute(() -> {
            helper.assertTrue(LogicEditing.open(owner, 13, LogicControllerMenu.ROWS) == null,
                    "no new graph past the screen's rows");
        }).thenSucceed();
    }

    /** A graph with more nodes than {@code logic.maxNodes} is saved but paused, with the limit as a graph-wide note. */
    private static void overLimit(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "le_hoarder");
        ServerPlayer owner = near(helper, "le_hoarder");
        entity.setOwner(OwnerRecord.of(owner));
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            LogicControllerGameTests.Graph big = new LogicControllerGameTests.Graph();
            int max = StorageServerConfig.LOGIC_MAX_NODES.getAsInt();
            for (int id = 1; id <= max + 1; id++) {
                big.node(id, NodeTypes.CONSTANT, s -> s);
            }
            menu(owner, 14, entity);
            present(helper, LogicEditing.open(owner, 14, 0), "the editor");
            EditorPayloads.Saved saved = present(helper,
                    LogicEditing.save(owner, save(helper, 14, 0, "Big", big.build())), "an answer");
            helper.assertTrue(saved.saved(), "saved");
            EditorNote note = saved.notes().isEmpty() ? null : saved.notes().getFirst();
            helper.assertTrue(
                    note != null && note.error() && note.node() == GraphError.GRAPH
                            && note.key().equals(LogicLang.TOO_MANY_NODES) && note.arg().equals(Integer.toString(max)),
                    "the size limit on the graph: " + saved.notes());
            helper.assertValueEqual(entity.worker().status(0), LogicWorker.Status.PAUSED, "paused");
            helper.assertValueEqual(entity.worker().graph(0).nodes().size(), max + 1, "kept as built");
        }).thenSucceed();
    }

    /** A save with errors is saved as built but paused, with the error on its node; the previous version stops. */
    private static void errorsSavePaused(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "le_pauser");
        ServerPlayer owner = near(helper, "le_pauser");
        entity.setOwner(OwnerRecord.of(owner));
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            menu(owner, 6, entity);
            present(helper, LogicEditing.open(owner, 6, 0), "the editor");
            present(helper, LogicEditing.save(owner, save(helper, 6, 0, "Good", crossesNotify())), "saved");
            helper.assertTrue(entity.worker().evaluator(0).isPresent(), "the good graph runs");
        }).thenIdle(LogicEditing.SAVE_INTERVAL + 1).thenExecute(() -> {
            EditorPayloads.Saved saved = present(helper,
                    LogicEditing.save(owner, save(helper, 6, 0, "Broken", broken())), "an answer");
            helper.assertTrue(saved.saved(), "a graph with errors is still saved");
            EditorNote note = saved.notes().isEmpty() ? null : saved.notes().getFirst();
            helper.assertTrue(note != null && note.error() && note.node() == 1 && note.port().equals("do"),
                    "the error sits on the notice's trigger input: " + saved.notes());
            helper.assertValueEqual(entity.worker().status(0), LogicWorker.Status.PAUSED, "paused");
            helper.assertTrue(entity.worker().evaluator(0).isEmpty(), "the previous version stopped");
            helper.assertValueEqual(entity.worker().graph(0), broken(), "saved as built");
            helper.assertValueEqual(entity.worker().name(0), "Broken", "renamed");
        }).thenSucceed();
    }

    /** A player with craft access cannot open the editor or save; one with logic access can. */
    private static void access(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "le_owner");
        ServerPlayer owner = near(helper, "le_owner");
        ServerPlayer crafter = near(helper, "le_crafter");
        ServerPlayer logician = near(helper, "le_logician");
        entity.setOwner(OwnerRecord.of(owner));
        secure(helper, owner, crafter, logician);
        helper.startSequence().thenWaitUntil(() -> {
            LogicControllerGameTests.network(helper, entity);
            helper.assertTrue(NetworkGameTests.controllerEntity(helper, NetworkGameTests.at(1)).secured(),
                    "module active");
        }).thenExecute(() -> {
            menu(crafter, 7, entity);
            helper.assertTrue(LogicEditing.open(crafter, 7, 0) == null, "craft access opens no editor");
            helper.assertTrue(LogicEditing.save(crafter, save(helper, 7, 0, "x", crossesNotify())) == null,
                    "craft access saves nothing");
            helper.assertValueEqual(entity.worker().size(), 0, "nothing saved");
            menu(logician, 8, entity);
            present(helper, LogicEditing.open(logician, 8, 0), "logic access opens the editor");
            helper.assertTrue(
                    present(helper, LogicEditing.save(logician, save(helper, 8, 0, "x", crossesNotify())), "an answer")
                            .saved(),
                    "logic access saves");
        }).thenSucceed();
    }

    /**
     * The first player to open a graph edits it; a second sees it read-only with the editor's name and cannot save.
     * When the editor closes the editor or the controller screen, the graph is free for the next.
     */
    private static void oneEditor(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "le_first");
        ServerPlayer owner = near(helper, "le_first");
        ServerPlayer crafter = near(helper, "le_nobody");
        ServerPlayer logician = near(helper, "le_second");
        entity.setOwner(OwnerRecord.of(owner));
        secure(helper, owner, crafter, logician);
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            menu(owner, 9, entity);
            LogicControllerMenu second = menu(logician, 10, entity);
            helper.assertTrue(!present(helper, LogicEditing.open(owner, 9, 0), "first").readOnly(), "the first edits");
            EditorPayloads.Graph seen = present(helper, LogicEditing.open(logician, 10, 0), "second");
            helper.assertTrue(seen.readOnly(), "the second only looks");
            helper.assertValueEqual(seen.editor(), "le_first", "and sees who edits");
            EditorPayloads.Saved refused = present(helper,
                    LogicEditing.save(logician, save(helper, 10, 0, "mine", crossesNotify())), "an answer");
            helper.assertTrue(!refused.saved(), "a viewer cannot save");
            helper.assertValueEqual(entity.worker().size(), 0, "nothing saved");
            helper.assertTrue(
                    present(helper, LogicEditing.save(owner, save(helper, 9, 0, "ours", crossesNotify())), "an answer")
                            .saved(),
                    "the editor saves");
            helper.assertTrue(second.editorUpdate(helper.getLevel().getGameTime()) instanceof EditorPayloads.Graph,
                    "the viewer gets the saved graph");
        }).thenIdle(LogicEditing.OPEN_INTERVAL).thenExecute(() -> {
            LogicEditing.close(owner, 9);
            helper.assertTrue(!present(helper, LogicEditing.open(logician, 10, 0), "reopened").readOnly(),
                    "free once the editor closed");
            helper.assertTrue(present(helper, LogicEditing.open(owner, 9, 0), "back").readOnly(),
                    "now the first only looks");
        }).thenIdle(LogicEditing.OPEN_INTERVAL).thenExecute(() -> {
            logician.containerMenu = logician.inventoryMenu;
            helper.assertTrue(!present(helper, LogicEditing.open(owner, 9, 0), "after").readOnly(),
                    "free once the editor's screen closed");
        }).thenSucceed();
    }

    /**
     * Without an open editor the menu sends nothing; with one it sends the graph's live values, again only when they
     * changed; after the editor closes, nothing.
     */
    private static void liveOnlyWhileOpen(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "le_watcher");
        ServerPlayer owner = near(helper, "le_watcher");
        entity.setOwner(OwnerRecord.of(owner));
        LogicControllerMenu[] menu = new LogicControllerMenu[1];
        long[] runs = new long[1];
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            entity.worker().setGraph(0, "Watched", crossesNotify());
            menu[0] = menu(owner, 11, entity);
        }).thenWaitUntil(() -> helper.assertTrue(entity.worker().runs() > 0, "the graph ran once")).thenExecute(() -> {
            long time = helper.getLevel().getGameTime();
            helper.assertTrue(menu[0].editorUpdate(time) == null, "no editor, no live values");
            present(helper, LogicEditing.open(owner, 11, 0), "the editor");
            CustomPacketPayload first = menu[0].editorUpdate(time);
            helper.assertTrue(first instanceof EditorPayloads.Live, "live values once the editor opens");
            helper.assertTrue(menu[0].editorUpdate(time + LogicEditing.LIVE_INTERVAL) == null,
                    "nothing changed, nothing sent");
            runs[0] = entity.worker().runs();
            NetworkGameTests.insert(LogicControllerGameTests.network(helper, entity), COBBLE, 20);
        }).thenWaitUntil(() -> helper.assertTrue(entity.worker().runs() > runs[0], "the crossing ran"))
                .thenExecute(() -> {
                    CustomPacketPayload update = menu[0].editorUpdate(helper.getLevel().getGameTime() + 100);
                    if (!(update instanceof EditorPayloads.Live live)) {
                        throw helper.assertionException(Component.literal("expected live values, got " + update));
                    }
                    helper.assertTrue(live.values().contains(new EditorPayloads.LiveValue(1, "count", 20)),
                            "the count on the wire: " + live.values());
                    helper.assertTrue(live.values().contains(new EditorPayloads.LiveValue(1, "crossed", live.runs())),
                            "the crossing fired in the last run: " + live.values());
                    LogicEditing.close(owner, 11);
                    helper.assertTrue(menu[0].session() == null, "the session ended");
                    helper.assertTrue(menu[0].editorUpdate(helper.getLevel().getGameTime() + 200) == null,
                            "nothing after the editor closed");
                }).thenSucceed();
    }

    /**
     * A crossing node's live line: the count it watches goes out with the live values and follows the network's count,
     * also while the count crosses no limit and so runs nothing (the node's own count output stays behind then).
     */
    private static void liveWatchedCount(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "le_counter");
        ServerPlayer owner = near(helper, "le_counter");
        entity.setOwner(OwnerRecord.of(owner));
        LogicControllerMenu[] menu = new LogicControllerMenu[1];
        long[] runs = new long[1];
        long[] time = new long[1];
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            entity.worker().setGraph(0, "Lamp over 100 cobble",
                    LogicControllerGameTests.crossesNotify(COBBLE, 100, "lamp"));
            menu[0] = menu(owner, 12, entity);
        }).thenWaitUntil(() -> helper.assertTrue(entity.worker().runs() > 0, "the graph ran once")).thenExecute(() -> {
            present(helper, LogicEditing.open(owner, 12, 0), "the editor");
            time[0] = helper.getLevel().getGameTime();
            helper.assertTrue(menu[0].editorUpdate(time[0]) instanceof EditorPayloads.Live, "first live values");
            runs[0] = entity.worker().runs();
            NetworkGameTests.insert(LogicControllerGameTests.network(helper, entity), COBBLE, 30);
        }).thenWaitUntil(() -> helper.assertTrue(entity.worker().watched(0, 0) == 30, "the count reached the graph"))
                .thenExecute(() -> {
                    time[0] += LogicEditing.LIVE_INTERVAL;
                    watched(helper, menu[0].editorUpdate(time[0]), 30);
                    helper.assertTrue(entity.worker().runs() == runs[0], "30 crosses no limit: no run");
                    time[0] += LogicEditing.LIVE_INTERVAL;
                    helper.assertTrue(menu[0].editorUpdate(time[0]) == null, "nothing changed, nothing sent");
                    NetworkGameTests.insert(LogicControllerGameTests.network(helper, entity), COBBLE, 1210);
                })
                .thenWaitUntil(() -> helper.assertTrue(entity.worker().watched(0, 0) == 1240, "the new count arrived"))
                .thenExecute(() -> {
                    time[0] += LogicEditing.LIVE_INTERVAL;
                    watched(helper, menu[0].editorUpdate(time[0]), 1240);
                    LogicEditing.close(owner, 12);
                }).thenSucceed();
    }

    private static void watched(GameTestHelper helper, @Nullable CustomPacketPayload update, long count) {
        if (!(update instanceof EditorPayloads.Live live)) {
            throw helper.assertionException(Component.literal("expected live values, got " + update));
        }
        helper.assertTrue(live.values().contains(new EditorPayloads.LiveValue(1, LiveLine.WATCHED, count)),
                "the watched count " + count + " in the live values: " + live.values());
    }

    private static LogicGraph<LogicKey> crossesNotify() {
        return LogicControllerGameTests.crossesNotify(COBBLE, 10, "full");
    }
}
