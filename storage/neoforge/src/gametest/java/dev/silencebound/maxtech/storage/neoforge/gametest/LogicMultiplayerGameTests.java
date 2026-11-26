package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.SecuritySettings;
import dev.silencebound.maxtech.storage.logic.GraphChange;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.LogicLang;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.controller.EditorPayloads;
import dev.silencebound.maxtech.storage.logic.controller.GraphSlot;
import dev.silencebound.maxtech.storage.logic.controller.HistoryEntry;
import dev.silencebound.maxtech.storage.logic.controller.LogicContent;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerMenu;
import dev.silencebound.maxtech.storage.logic.controller.LogicEditing;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.logic.controller.LogicWorker;
import dev.silencebound.maxtech.storage.logic.controller.RuleHistory;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.jspecify.annotations.Nullable;

/**
 * Logic for several players (M7L2 chunk 9): a second player sees a graph someone edits read-only, hears when the editor
 * leaves and can then take it over ("Edit now"); every save, revert, switch and simulate toggle goes into the rule's
 * change history with the right player, the history keeps only {@code logic.historyEntries} entries, survives a save
 * and load and reaches the open screen only when it changed.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class LogicMultiplayerGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final String CAPPED = "logic_multi_history_capped_and_saved";

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("logic_multi_read_only_then_edit_now", LogicMultiplayerGameTests::readOnlyThenEditNow);
        register("logic_multi_history_records_who", LogicMultiplayerGameTests::historyRecordsWho);
        StorageTests.function(CAPPED, StorageTestEnvironment.clearing(LogicMultiplayerGameTests::historyCapped));
    }

    private LogicMultiplayerGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("logic_multi"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 300);
        }
        StorageTests.test(event, event.registerEnvironment(MaxtechStorage.id(CAPPED),
                new StorageTestEnvironment("logic.historyEntries", "3", false)), CAPPED, STRUCTURE, 200);
    }

    // ---- set-up

    private static ServerPlayer near(GameTestHelper helper, String name) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), name);
        player.setPos(helper.absolutePos(NetworkGameTests.at(3)).getCenter());
        return player;
    }

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

    /** A network secured so {@code logician} has logic access. */
    private static void secure(GameTestHelper helper, ServerPlayer owner, ServerPlayer logician) {
        BlockPos core = NetworkGameTests.at(1);
        NetworkGameTests.controllerEntity(helper, core).setOwner(OwnerRecord.of(owner));
        ControllerClusterGameTests.expansion(helper, core.above(), ExpansionContent.SECURITY_MODULE);
        NetworkGameTests.controllerEntity(helper, core).setSecurity(SecuritySettings.DEFAULT
                .withPlayer(logician.getUUID(), logician.getGameProfile().name(), AccessLevel.LOGIC));
    }

    /** The live flags the menu sends its open editor now (a far later time, past the live interval). */
    private static int flags(GameTestHelper helper, LogicControllerMenu menu, long later) {
        CustomPacketPayload update = menu.editorUpdate(helper.getLevel().getGameTime() + later);
        if (!(update instanceof EditorPayloads.Live live)) {
            throw helper.assertionException(Component.literal("expected live values, got " + update));
        }
        return live.flags();
    }

    private static HistoryEntry newest(LogicWorker worker, int index) {
        return worker.history(index).getLast();
    }

    private static void assertEntry(GameTestHelper helper, HistoryEntry entry, ServerPlayer player, String key,
            String what) {
        helper.assertValueEqual(entry.player(), Optional.of(player.getUUID()), what + ": the player's UUID");
        helper.assertValueEqual(entry.name(), player.getGameProfile().name(), what + ": the player's name");
        helper.assertValueEqual(entry.changes().getFirst().key(), key, what + ": " + entry.changes());
        helper.assertTrue(entry.realTime() > 0, what + ": the server's clock");
    }

    // ---- tests

    /**
     * While the owner edits, the logician gets the graph read-only with live values and cannot save; the lock is not
     * free. Once the owner closes, the logician's live update says so, and opening again ("Edit now") makes them the
     * editor, who can save.
     */
    private static void readOnlyThenEditNow(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lm_first");
        ServerPlayer owner = near(helper, "lm_first");
        ServerPlayer logician = near(helper, "lm_second");
        entity.setOwner(OwnerRecord.of(owner));
        secure(helper, owner, logician);
        LogicControllerMenu[] second = new LogicControllerMenu[1];
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            entity.worker().setGraph(0, "Shared", LogicControllerGameTests.crossesNotify(COBBLE, 10, "a"));
            menu(owner, 20, entity);
            second[0] = menu(logician, 21, entity);
            helper.assertTrue(!present(helper, LogicEditing.open(owner, 20, 0), "first").readOnly(), "owner edits");
            EditorPayloads.Graph seen = present(helper, LogicEditing.open(logician, 21, 0), "second");
            helper.assertTrue(seen.readOnly(), "the second only looks");
            helper.assertValueEqual(seen.editor(), "lm_first", "and sees who edits");
            helper.assertTrue((flags(helper, second[0], 0) & EditorPayloads.LIVE_LOCK_FREE) == 0,
                    "not free while the owner edits");
            helper.assertTrue(!present(helper,
                    LogicEditing.save(logician,
                            save(helper, 21, 0, "mine", LogicControllerGameTests.crossesNotify(COBBLE, 5, "b"))),
                    "an answer").saved(), "a viewer cannot save");
            LogicEditing.close(owner, 20);
            helper.assertTrue((flags(helper, second[0], 100) & EditorPayloads.LIVE_LOCK_FREE) != 0,
                    "the viewer hears the editor left");
        }).thenIdle(LogicEditing.OPEN_INTERVAL + LogicEditing.SAVE_INTERVAL).thenExecute(() -> {
            helper.assertTrue(!present(helper, LogicEditing.open(logician, 21, 0), "edit now").readOnly(),
                    "edit now makes the viewer the editor");
            helper.assertTrue(present(helper,
                    LogicEditing.save(logician,
                            save(helper, 21, 0, "Shared", LogicControllerGameTests.crossesNotify(COBBLE, 5, "b"))),
                    "an answer").saved(), "the new editor saves");
            assertEntry(helper, newest(entity.worker(), 0), logician, LogicLang.HISTORY_SETTING, "the save");
        }).thenSucceed();
    }

    /**
     * A new rule saved from the editor, a changed setting, a rename, switching off by another player, simulate and a
     * revert each add an entry naming the player who did it and what changed; the open screen gets the history once.
     */
    private static void historyRecordsWho(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lm_owner");
        ServerPlayer owner = near(helper, "lm_owner");
        ServerPlayer logician = near(helper, "lm_logician");
        entity.setOwner(OwnerRecord.of(owner));
        secure(helper, owner, logician);
        LogicControllerMenu[] menus = new LogicControllerMenu[2];
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            menus[0] = menu(owner, 30, entity);
            present(helper, LogicEditing.open(owner, 30, 0), "the editor");
            helper.assertTrue(present(helper,
                    LogicEditing.save(owner,
                            save(helper, 30, 0, "Rule", LogicControllerGameTests.crossesNotify(COBBLE, 100, "x"))),
                    "an answer").saved(), "created");
            LogicWorker worker = entity.worker();
            assertEntry(helper, newest(worker, 0), owner, LogicLang.HISTORY_CREATED, "created");
            helper.assertValueEqual(newest(worker, 0).changes().getFirst().args(), List.of(GraphChange.Arg.text("2")),
                    "created with 2 nodes");
        }).thenIdle(LogicEditing.SAVE_INTERVAL).thenExecute(() -> {
            helper.assertTrue(present(helper,
                    LogicEditing.save(owner,
                            save(helper, 30, 0, "Renamed", LogicControllerGameTests.crossesNotify(COBBLE, 200, "x"))),
                    "an answer").saved(), "saved");
            LogicWorker worker = entity.worker();
            HistoryEntry saved = newest(worker, 0);
            assertEntry(helper, saved, owner, LogicLang.HISTORY_RENAMED, "renamed");
            GraphChange setting = saved.changes().get(1);
            helper.assertValueEqual(setting.key(), LogicLang.HISTORY_SETTING, "then the setting");
            helper.assertValueEqual(setting.args(),
                    List.of(GraphChange.Arg.key(NodeTypes.COUNT_CROSSES.nameKey()),
                            GraphChange.Arg.key(NodeTypes.COUNT_CROSSES.setting("threshold").orElseThrow().nameKey()),
                            GraphChange.Arg.text("100"), GraphChange.Arg.text("200")),
                    "Count crosses limit 100 -> 200");
            LogicEditing.close(owner, 30);

            menus[1] = menu(logician, 31, entity);
            helper.assertTrue(menus[1].clickMenuButton(logician, 0), "switch off");
            assertEntry(helper, newest(worker, 0), logician, LogicLang.HISTORY_SWITCHED_OFF, "paused");
            helper.assertTrue(menus[1].clickMenuButton(logician, LogicControllerMenu.BUTTON_SIMULATE), "simulate");
            assertEntry(helper, newest(worker, 0), logician, LogicLang.HISTORY_SIMULATE_ON, "simulate");
            helper.assertTrue(menus[0].clickMenuButton(owner, LogicControllerMenu.BUTTON_REVERT), "revert");
            assertEntry(helper, newest(worker, 0), owner, LogicLang.HISTORY_REVERTED, "reverted");
            helper.assertValueEqual(worker.history(0).size(), 5, "five entries");

            long time = helper.getLevel().getGameTime() + 1000;
            List<RuleHistory.Update> first = menus[1].historyUpdate(time);
            helper.assertTrue(first.size() == LogicControllerMenu.ROWS && first.getFirst().entries().size() == 5,
                    "the screen gets every row once: " + first.size());
            helper.assertTrue(menus[1].historyUpdate(time + 100).isEmpty(), "nothing changed, nothing sent");
            helper.assertTrue(menus[1].clickMenuButton(logician, 0), "switch on");
            List<RuleHistory.Update> next = menus[1].historyUpdate(time + 200);
            helper.assertTrue(next.size() == 1 && next.getFirst().index() == 0, "only the changed row: " + next);
        }).thenSucceed();
    }

    /** With {@code logic.historyEntries} at 3 the rule keeps its newest three entries, through a save and load. */
    private static void historyCapped(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lm_capped");
        ServerPlayer owner = near(helper, "lm_capped");
        entity.setOwner(OwnerRecord.of(owner));
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            LogicWorker worker = entity.worker();
            worker.setGraph(0, "Rule", LogicControllerGameTests.crossesNotify(COBBLE, 10, "x"));
            LogicControllerMenu menu = menu(owner, 40, entity);
            for (int i = 0; i < 5; i++) {
                menu.clickMenuButton(owner, 0);
            }
            helper.assertValueEqual(worker.history(0).size(), 3, "capped at three");
            // Five switches: off, on, off, on, off; the newest three are off, on, off.
            helper.assertValueEqual(worker.history(0).getLast().changes().getFirst().key(),
                    LogicLang.HISTORY_SWITCHED_OFF, "the newest is kept");
            helper.assertValueEqual(worker.history(0).getFirst().changes().getFirst().key(),
                    LogicLang.HISTORY_SWITCHED_OFF, "the oldest kept is the third");
            CompoundTag saved = entity.saveWithoutMetadata(helper.getLevel().registryAccess());
            BlockPos other = NetworkGameTests.at(8);
            helper.setBlock(other, LogicContent.CONTROLLER.get());
            LogicControllerBlockEntity loaded = LogicControllerGameTests.entity(helper, other);
            loaded.loadCustomOnly(
                    TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
            helper.assertValueEqual(loaded.worker().history(0), worker.history(0), "the history survives a load");
            // A slot saved under a higher limit is cut to the configured one when it loads.
            List<HistoryEntry> five = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                five.add(new HistoryEntry(i, 0, Optional.empty(), "p" + i, List.of()));
            }
            GraphSlot slot = worker.slots().getFirst();
            loaded.worker().load(List.of(new GraphSlot(slot.name(), slot.enabled(), slot.graph(), slot.memory(),
                    slot.simulate(), slot.previous(), slot.pins(), five)));
            helper.assertValueEqual(loaded.worker().history(0), five.subList(2, 5), "trimmed on load");
        }).thenSucceed();
    }
}
