package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.SecuritySettings;
import dev.silencebound.maxtech.storage.logic.GraphText;
import dev.silencebound.maxtech.storage.logic.LogicEvaluator;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.controller.ControllerTexts;
import dev.silencebound.maxtech.storage.logic.controller.GraphSharing;
import dev.silencebound.maxtech.storage.logic.controller.LogicContent;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerMenu;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.logic.controller.LogicWorker;
import dev.silencebound.maxtech.storage.logic.controller.SharePayloads;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.jspecify.annotations.Nullable;

/**
 * Sharing graphs as text (M7L2): a graph exported from one controller and imported into another is the same graph,
 * added switched off, and runs once switched on; imports and exports need the logic access level; bad texts, a full
 * controller and a second import within a second are refused with a line and change nothing.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class LogicShareGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("logic_share_round_trip", LogicShareGameTests::roundTrip);
        register("logic_share_access", LogicShareGameTests::access);
        register("logic_share_refusals", LogicShareGameTests::refusals);
    }

    private LogicShareGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("logic_share"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 300);
        }
    }

    // ---- set-up

    static ServerPlayer near(GameTestHelper helper, String name) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), name);
        player.setPos(helper.absolutePos(NetworkGameTests.at(3)).getCenter());
        return player;
    }

    static LogicControllerMenu menu(ServerPlayer player, int id, LogicControllerBlockEntity entity) {
        LogicControllerMenu menu = LogicControllerMenu.forController(id, entity);
        player.containerMenu = menu;
        return menu;
    }

    /** A second logic controller on the same network, beside the network controller. */
    static LogicControllerBlockEntity second(GameTestHelper helper, ServerPlayer owner) {
        BlockPos pos = NetworkGameTests.at(0);
        helper.setBlock(pos, LogicContent.CONTROLLER.get());
        LogicControllerBlockEntity entity = LogicControllerGameTests.entity(helper, pos);
        entity.setOwner(OwnerRecord.of(owner));
        return entity;
    }

    static <T> T present(GameTestHelper helper, @Nullable T value, String what) {
        if (value == null) {
            throw helper.assertionException(Component.literal("expected " + what));
        }
        return value;
    }

    static LogicGraph<LogicKey> graph() {
        return LogicControllerGameTests.crossesNotify(COBBLE, 10, "full");
    }

    private static long runs(LogicWorker worker, int index) {
        return worker.evaluator(index).map(LogicEvaluator::runs).orElse(-1L);
    }

    // ---- tests

    /**
     * Export graph 0 of one controller, import the text into another: the same nodes, settings, wires and name,
     * switched off and not run; switched on, it runs.
     */
    private static void roundTrip(GameTestHelper helper) {
        LogicControllerBlockEntity from = LogicControllerGameTests.setUp(helper, "ls_owner");
        ServerPlayer owner = near(helper, "ls_owner");
        from.setOwner(OwnerRecord.of(owner));
        LogicControllerBlockEntity to = second(helper, owner);
        String[] text = new String[1];
        helper.startSequence().thenWaitUntil(() -> {
            LogicControllerGameTests.network(helper, from);
            LogicControllerGameTests.network(helper, to);
        }).thenExecute(() -> {
            from.worker().setGraph(0, "Cobble alarm", graph());
            menu(owner, 3, from);
            SharePayloads.Exported exported = present(helper,
                    GraphSharing.export(owner, 3, 0, SharePayloads.TO_CLIPBOARD), "an export");
            helper.assertValueEqual(exported.message(), ControllerTexts.SHARE_COPIED, "copied");
            helper.assertTrue(exported.text().startsWith(GraphText.PREFIX + GraphText.VERSION + ":"),
                    "the text: " + exported.text());
            text[0] = exported.text();

            menu(owner, 4, to);
            SharePayloads.Imported imported = present(helper, GraphSharing.importGraph(owner, 4, text[0]), "an answer");
            helper.assertTrue(imported.added(), "imported: " + imported.message() + " " + imported.args());
            helper.assertValueEqual(imported.index(), 0, "first row");
            helper.assertValueEqual(imported.name(), "Cobble alarm", "name");
            helper.assertValueEqual(to.worker().size(), 1, "added");
            helper.assertValueEqual(to.worker().graph(0), from.worker().graph(0), "the same graph");
            helper.assertValueEqual(to.worker().name(0), "Cobble alarm", "the same name");
            helper.assertValueEqual(to.worker().status(0), LogicWorker.Status.DISABLED, "switched off");
        }).thenIdle(3).thenExecute(() -> {
            helper.assertValueEqual(runs(to.worker(), 0), 0L, "an imported graph does not run while off");
            to.worker().setEnabled(0, true);
        }).thenWaitUntil(() -> helper.assertTrue(runs(to.worker(), 0) > 0, "runs once switched on"))
                .thenExecute(
                        () -> helper.assertValueEqual(to.worker().status(0), LogicWorker.Status.RUNNING, "running"))
                .thenSucceed();
    }

    /**
     * Import and export need the logic access level: a crafter gets no answer to either and nothing is added; a player
     * without access gets no export either.
     */
    private static void access(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "ls_boss");
        ServerPlayer owner = near(helper, "ls_boss");
        ServerPlayer crafter = near(helper, "ls_crafter");
        ServerPlayer logician = near(helper, "ls_logician");
        ServerPlayer stranger = near(helper, "ls_stranger");
        entity.setOwner(OwnerRecord.of(owner));
        BlockPos core = NetworkGameTests.at(1);
        NetworkGameTests.controllerEntity(helper, core).setOwner(OwnerRecord.of(owner));
        ControllerClusterGameTests.expansion(helper, core.above(), ExpansionContent.SECURITY_MODULE);
        NetworkGameTests.controllerEntity(helper, core)
                .setSecurity(SecuritySettings.DEFAULT
                        .withPlayer(crafter.getUUID(), crafter.getGameProfile().name(), AccessLevel.CRAFT)
                        .withPlayer(logician.getUUID(), logician.getGameProfile().name(), AccessLevel.LOGIC));
        helper.startSequence().thenWaitUntil(() -> {
            LogicControllerGameTests.network(helper, entity);
            helper.assertTrue(NetworkGameTests.controllerEntity(helper, core).secured(), "module active");
        }).thenExecute(() -> {
            entity.worker().setGraph(0, "shared", graph());
            menu(logician, 9, entity);
            SharePayloads.Exported copied = present(helper,
                    GraphSharing.export(logician, 9, 0, SharePayloads.TO_CLIPBOARD), "logic access exports");
            helper.assertValueEqual(copied.message(), ControllerTexts.SHARE_COPIED, "copied");

            menu(crafter, 7, entity);
            helper.assertTrue(GraphSharing.export(crafter, 7, 0, SharePayloads.TO_CLIPBOARD) == null,
                    "craft access exports nothing");
            helper.assertTrue(GraphSharing.importGraph(crafter, 7, copied.text()) == null,
                    "craft access imports nothing");
            helper.assertValueEqual(entity.worker().size(), 1, "nothing added");

            menu(stranger, 8, entity);
            helper.assertTrue(GraphSharing.export(stranger, 8, 0, SharePayloads.TO_CLIPBOARD) == null,
                    "no access, no export");

            helper.assertTrue(GraphSharing.importGraph(logician, 8, copied.text()) == null, "another container id");
            SharePayloads.Imported imported = present(helper, GraphSharing.importGraph(logician, 9, copied.text()),
                    "logic access imports");
            helper.assertTrue(imported.added(), "added: " + imported.message());
            helper.assertValueEqual(entity.worker().size(), 2, "two graphs");
        }).thenSucceed();
    }

    /**
     * A text that is no graph, a second import within {@link GraphSharing#IMPORT_INTERVAL} and an import into a
     * controller with every row used are refused with their lines and add nothing; an export of a row without a graph
     * is refused too.
     */
    private static void refusals(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "ls_refused");
        ServerPlayer owner = near(helper, "ls_refused");
        entity.setOwner(OwnerRecord.of(owner));
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            menu(owner, 2, entity);
            SharePayloads.Imported foreign = present(helper, GraphSharing.importGraph(owner, 2, "hello there"),
                    "an answer");
            helper.assertTrue(!foreign.added(), "not a graph");
            helper.assertValueEqual(foreign.message(), GraphText.BAD_PREFIX, "line");
            SharePayloads.Imported fast = present(helper, GraphSharing.importGraph(owner, 2, "mtlogic1:AAAA"),
                    "an answer");
            helper.assertValueEqual(fast.message(), ControllerTexts.SHARE_TOO_FAST, "a refused import counts too");
            helper.assertValueEqual(entity.worker().size(), 0, "nothing added");

            SharePayloads.Exported missing = present(helper,
                    GraphSharing.export(owner, 2, 0, SharePayloads.TO_CLIPBOARD), "an answer");
            helper.assertValueEqual(missing.message(), ControllerTexts.SHARE_NO_GRAPH, "no graph in row 0");
            helper.assertTrue(missing.text().isEmpty(), "no text");
            for (int i = 0; i < LogicControllerMenu.ROWS; i++) {
                entity.worker().setGraph(i, "g" + i, graph());
            }
        }).thenIdle(GraphSharing.IMPORT_INTERVAL + 1).thenExecute(() -> {
            String text = present(helper, GraphSharing.export(owner, 2, 0, SharePayloads.TO_CLIPBOARD), "an export")
                    .text();
            SharePayloads.Imported full = present(helper, GraphSharing.importGraph(owner, 2, text), "an answer");
            helper.assertValueEqual(full.message(), ControllerTexts.SHARE_NO_ROW, "every row used");
            helper.assertValueEqual(entity.worker().size(), LogicControllerMenu.ROWS, "nothing added");
        }).thenSucceed();
    }
}
