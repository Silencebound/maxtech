package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.logic.LogicLang;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.admin.AdminList;
import dev.silencebound.maxtech.storage.logic.admin.AdminRow;
import dev.silencebound.maxtech.storage.logic.admin.LogicAdmin;
import dev.silencebound.maxtech.storage.logic.admin.LogicAdminCommand;
import dev.silencebound.maxtech.storage.logic.controller.HistoryEntry;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicWorker;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The admin overview of logic rules (M7L2 chunk 23): the list puts the rule that paid the most checks recently first;
 * an operator's {@code /maxtech logic pause} and {@code resume} switch the rule and record the operator in its history
 * (and nothing when nothing changed); a player without operator rights cannot run any of it.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class LogicAdminGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("logic_admin_list_sorted_by_cost", LogicAdminGameTests::sortedByCost);
        register("logic_admin_pause_records_history", LogicAdminGameTests::pauseRecordsHistory);
        register("logic_admin_refuses_non_op", LogicAdminGameTests::refusesNonOp);
    }

    private LogicAdminGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("logic_admin"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 300);
        }
    }

    // ---- set-up

    private static CommandDispatcher<CommandSourceStack> dispatcher() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(Commands.literal("maxtech").then(LogicAdminCommand.build()));
        return dispatcher;
    }

    /** A player with game master rights (a GameTest player has none of its own). */
    private static ServerPlayer op(ServerLevel level, String name) {
        return new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), name),
                ClientInformation.createDefault()) {
            @Override
            public PermissionSet permissions() {
                return LevelBasedPermissionSet.GAMEMASTER;
            }
        };
    }

    private static CommandSourceStack source(GameTestHelper helper, ServerPlayer player) {
        player.setPos(helper.absolutePos(NetworkGameTests.at(3)).getCenter());
        return player.createCommandSourceStack().withSuppressedOutput();
    }

    private static int run(GameTestHelper helper, CommandSourceStack source, String command) {
        try {
            return dispatcher().execute(command, source);
        } catch (CommandSyntaxException e) {
            throw helper.assertionException(Component.literal(command + ": " + e.getMessage()));
        }
    }

    private static String target(GameTestHelper helper, int rule) {
        BlockPos pos = helper.absolutePos(NetworkGameTests.at(3));
        return pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + rule;
    }

    /** This test's rows, in the order the overview sorted the whole server's rules. */
    private static List<AdminRow> mine(GameTestHelper helper, List<AdminRow> rows) {
        BlockPos pos = helper.absolutePos(NetworkGameTests.at(3));
        String dimension = helper.getLevel().dimension().identifier().toString();
        List<AdminRow> out = new ArrayList<>();
        for (AdminRow row : rows) {
            if (row.pos().equals(pos) && row.dimension().equals(dimension)) {
                out.add(row);
            }
        }
        return out;
    }

    private static void move(GameTestHelper helper, LogicControllerBlockEntity entity, boolean in) {
        if (in) {
            NetworkGameTests.insert(LogicControllerGameTests.network(helper, entity), COBBLE, 20);
        } else {
            try (Transaction transaction = Transaction.openRoot()) {
                LogicControllerGameTests.network(helper, entity).extract(COBBLE, 20, transaction);
                transaction.commit();
            }
        }
    }

    // ---- tests

    /**
     * A rule that starts once and a rule woken by every cobblestone crossing: after a few crossings the second has paid
     * more checks per second recently and comes first in the cost order, and the list command counts both.
     */
    private static void sortedByCost(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "la_cost");
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            entity.worker().setGraph(0, "Once",
                    new LogicControllerGameTests.Graph().node(1, NodeTypes.GRAPH_START, s -> s).build());
            entity.worker().setGraph(1, "Busy", LogicControllerGameTests.crossesNotify(COBBLE, 10, "busy"));
        }).thenIdle(2).thenExecute(() -> move(helper, entity, true)).thenIdle(2)
                .thenExecute(() -> move(helper, entity, false)).thenIdle(2)
                .thenExecute(() -> move(helper, entity, true)).thenIdle(2)
                .thenExecute(() -> move(helper, entity, false)).thenIdle(2).thenExecute(() -> {
                    LogicAdmin.Snapshot snapshot = LogicAdmin.gather(helper.getLevel().getServer());
                    List<AdminRow> rows = mine(helper, AdminList.sorted(snapshot.rows(), AdminList.Sort.COST));
                    helper.assertValueEqual(rows.size(), 2, "both rules are listed: " + rows);
                    helper.assertValueEqual(rows.get(0).name(), "Busy", "the busy rule first: " + rows);
                    helper.assertTrue(rows.get(0).checksPerSecond() > rows.get(1).checksPerSecond(),
                            "it paid more checks per second: " + rows);
                    helper.assertTrue(rows.get(0).lastRunAgo() >= 0, "it ran: " + rows);
                    helper.assertValueEqual(rows.get(0).owner(), "la_cost", "the owner's name");
                    ServerPlayer admin = op(helper.getLevel(), "la_admin");
                    helper.assertTrue(run(helper, source(helper, admin), "maxtech logic list cost 1") >= 2,
                            "the list command counts the server's rules");
                }).thenSucceed();
    }

    /**
     * An operator pauses rule 1 with the command: it is switched off and its newest history entry names the operator.
     * Pausing again changes and records nothing; resuming switches it on and records that.
     */
    private static void pauseRecordsHistory(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "la_owner");
        ServerPlayer admin = op(helper.getLevel(), "la_op");
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            LogicWorker worker = entity.worker();
            worker.setGraph(0, "Rule", LogicControllerGameTests.crossesNotify(COBBLE, 10, "x"));
            CommandSourceStack source = source(helper, admin);
            helper.assertValueEqual(run(helper, source, "maxtech logic pause " + target(helper, 1)), 1, "paused");
            helper.assertTrue(!worker.enabled(0), "the rule is off");
            helper.assertValueEqual(worker.status(0), LogicWorker.Status.DISABLED, "and shows as paused");
            HistoryEntry entry = worker.history(0).getLast();
            helper.assertValueEqual(entry.player(), Optional.of(admin.getUUID()), "the operator's UUID");
            helper.assertValueEqual(entry.name(), "la_op", "the operator's name");
            helper.assertValueEqual(entry.changes().getFirst().key(), LogicLang.HISTORY_ADMIN_OFF, "paused by an op");
            int entries = worker.history(0).size();
            helper.assertValueEqual(run(helper, source, "maxtech logic pause " + target(helper, 1)), 0,
                    "pausing again changes nothing");
            helper.assertValueEqual(worker.history(0).size(), entries, "and records nothing");
            helper.assertValueEqual(run(helper, source, "maxtech logic resume " + target(helper, 1)), 1, "resumed");
            helper.assertTrue(worker.enabled(0), "the rule is on again");
            helper.assertValueEqual(worker.history(0).getLast().changes().getFirst().key(), LogicLang.HISTORY_ADMIN_ON,
                    "resumed by an op");
            helper.assertValueEqual(run(helper, source, "maxtech logic pause " + target(helper, 2)), 0,
                    "a rule that does not exist");
        }).thenSucceed();
    }

    /** A player without game master rights cannot list, pause or open the overview; the rule stays on. */
    private static void refusesNonOp(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "la_player");
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "la_player");
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            LogicWorker worker = entity.worker();
            worker.setGraph(0, "Rule", LogicControllerGameTests.crossesNotify(COBBLE, 10, "x"));
            CommandSourceStack source = source(helper, player);
            for (String command : List.of("maxtech logic pause " + target(helper, 1), "maxtech logic list",
                    "maxtech logic screen", "maxtech logic info " + target(helper, 1))) {
                try {
                    dispatcher().execute(command, source);
                    throw helper.assertionException(Component.literal("a player ran " + command));
                } catch (CommandSyntaxException expected) {
                    // Refused: the logic node is for game masters only.
                }
            }
            helper.assertTrue(worker.enabled(0), "the rule stays on");
            helper.assertTrue(worker.history(0).isEmpty(), "and nothing is recorded");
        }).thenSucceed();
    }
}
