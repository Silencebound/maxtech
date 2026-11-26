package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.SecuritySettings;
import dev.silencebound.maxtech.storage.logic.ForEach;
import dev.silencebound.maxtech.storage.logic.GraphError;
import dev.silencebound.maxtech.storage.logic.LogicEvaluator;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.LogicLang;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.RunTrace;
import dev.silencebound.maxtech.storage.logic.controller.GraphSharing;
import dev.silencebound.maxtech.storage.logic.controller.LogicAction;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.logic.controller.LogicWorker;
import dev.silencebound.maxtech.storage.logic.controller.RemoteNetworks;
import dev.silencebound.maxtech.storage.logic.controller.SharePayloads;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.NetworkInfo;
import dev.silencebound.maxtech.storage.network.NetworkNames;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.SwitchSource;
import dev.silencebound.maxtech.storage.port.PortBlock;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortMode;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import dev.silencebound.maxtech.storage.logic.controller.LogicContent;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Rules across networks (M7L2 row 13): a rule on network A reads and acts on network B by its name ({@code @name},
 * {@code lamps@name}) while its owner has the logic access level there; without it, or once it is taken away, the rule
 * pauses with an error naming the network; a network that is not loaded reads its last value with a why line and acts
 * on nothing; a rule shared as text carries the name, and a missing one pauses it; simulate changes nothing on B; and
 * names are unique, given to new controllers, shown in Jade and freed when a controller is broken.
 *
 * <p>
 * Network A is {@link LogicControllerGameTests#setUp}'s (controller at x 1, a test device at x 2, the logic controller
 * at x 3, all at z 1); network B is a powered controller at x 1, a test device at x 2 and an import port labelled
 * "lamps" at x 3 facing a chest at x 4, all at z 5.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class LogicNetworkGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final int B_Z = 5;

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("logic_net_read_and_act", LogicNetworkGameTests::readAndAct);
        register("logic_net_refused_without_access", LogicNetworkGameTests::refused);
        register("logic_net_access_revoked", LogicNetworkGameTests::revoked);
        register("logic_net_logic_level_acts", LogicNetworkGameTests::logicLevelActs);
        register("logic_net_unloaded_unavailable", LogicNetworkGameTests::unavailable);
        register("logic_net_text_round_trip", LogicNetworkGameTests::roundTrip);
        register("logic_net_simulate", LogicNetworkGameTests::simulate);
        register("logic_net_names", LogicNetworkGameTests::names);
    }

    private LogicNetworkGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("logic_net"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 400);
        }
    }

    // ---- set-up

    private static BlockPos atB(int x) {
        return new BlockPos(x, 1, B_Z);
    }

    /** Network B, powered, named {@code name}, its controller owned by {@code owner}; returns its controller. */
    private static ControllerBlockEntity networkB(GameTestHelper helper, String name, ServerPlayer owner) {
        helper.setBlock(atB(1), dev.silencebound.maxtech.storage.controller.ControllerContent.CONTROLLER.get());
        NetworkGameTests.device(helper, atB(2));
        helper.setBlock(atB(3), PortContent.PORT.get().defaultBlockState().setValue(PortBlock.FACING, Direction.EAST)
                .setValue(PortBlock.MODE, PortMode.IMPORT));
        helper.getBlockEntity(atB(3), PortBlockEntity.class).setLabel(DeviceLabel.of("lamps", null));
        helper.setBlock(atB(4), Blocks.CHEST);
        ControllerBlockEntity core = NetworkGameTests.controllerEntity(helper, atB(1));
        core.setOwner(OwnerRecord.of(owner));
        core.buffer().receive(1_000_000);
        NetworkNames.Renamed renamed = core.rename(name);
        if (renamed != NetworkNames.Renamed.DONE) {
            throw helper.assertionException(Component.literal("network B not renamed: " + renamed));
        }
        return core;
    }

    private static PortBlockEntity lamps(GameTestHelper helper) {
        return helper.getBlockEntity(atB(3), PortBlockEntity.class);
    }

    private static NetworkState network(GameTestHelper helper, ControllerBlockEntity core) {
        NetworkState network = core.network();
        if (network == null || network.status() != dev.silencebound.maxtech.storage.api.StorageNetwork.Status.ONLINE) {
            throw helper.assertionException(Component.literal("network B is not online yet"));
        }
        return network;
    }

    private static long take(NetworkState network, long amount) {
        try (Transaction transaction = Transaction.openRoot()) {
            long moved = network.extract(COBBLE, amount, transaction);
            transaction.commit();
            return moved;
        }
    }

    /** The owner of logic controller {@code entity} as a player, for security rules. */
    private static ServerPlayer owner(GameTestHelper helper, LogicControllerBlockEntity entity, String name) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), name);
        entity.setOwner(OwnerRecord.of(player));
        return player;
    }

    /** Cobblestone on network {@code name} above 10 turns its "lamps" off (count, not, switch); node 4 reads it. */
    private static LogicGraph<LogicKey> lampsOff(String name) {
        return new LogicControllerGameTests.Graph()
                .node(1, NodeTypes.COUNT_CROSSES,
                        s -> s.withItem("item", LogicKey.of(COBBLE)).withText("label", "@" + name)
                                .withNumber("threshold", 10))
                .node(2, NodeTypes.NOT, s -> s)
                .node(3, NodeTypes.DEVICE_SWITCH, s -> s.withText("label", "lamps@" + name))
                .node(4, NodeTypes.ITEM_COUNT,
                        s -> s.withItem("item", LogicKey.of(COBBLE)).withText("label", "@" + name))
                .wire(1, "past", 2, "value").wire(2, "result", 3, "on").build();
    }

    private static LogicEvaluator<LogicKey> evaluator(GameTestHelper helper, LogicControllerBlockEntity entity) {
        return entity.worker().evaluator(0)
                .orElseThrow(() -> helper.assertionException(Component.literal("graph 0 is paused")));
    }

    private static long count(GameTestHelper helper, LogicControllerBlockEntity entity) {
        LogicEvaluator<LogicKey> evaluator = evaluator(helper, entity);
        return evaluator.value(evaluator.graph().slot(4, "count"));
    }

    private static void assertPausedFor(GameTestHelper helper, LogicWorker worker, int graph, String key,
            String network) {
        helper.assertValueEqual(worker.status(graph), LogicWorker.Status.PAUSED, "status");
        boolean found = false;
        for (GraphError error : worker.errors(graph)) {
            found |= error.key().equals(key) && error.arg().equals(network);
        }
        helper.assertTrue(found, "no " + key + " naming " + network + " in " + worker.errors(graph));
    }

    /** The why reason and text the last run gave for node {@code node}; empty strings for none. */
    private static String[] why(GameTestHelper helper, LogicControllerBlockEntity entity, int node) {
        RunTrace trace = evaluator(helper, entity).trace();
        int line = trace.find(node);
        return line < 0 ? new String[]{"", ""} : new String[]{trace.reason(line), trace.text(line)};
    }

    private static String traceText(RunTrace trace) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < trace.size(); i++) {
            out.append(trace.node(i)).append('=').append(trace.reason(i)).append(' ');
        }
        return out.toString();
    }

    // ---- tests

    /**
     * With the logic level on B (its controller has the same owner), cobblestone on B above 10 turns B's port off and
     * back on below it: the count is read through B's own listeners, the switch is recorded as A's, and the run pays
     * the cross-network surcharge on top of the switch's own points. B shows up in the owner's network picker.
     */
    private static void readAndAct(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "ln_read");
        ServerPlayer owner = owner(helper, entity, "ln_read_owner");
        String name = "ln read B";
        ControllerBlockEntity core = networkB(helper, name, owner);
        helper.startSequence().thenWaitUntil(() -> {
            LogicControllerGameTests.network(helper, entity);
            network(helper, core);
        }).thenExecute(() -> {
            helper.assertTrue(
                    RemoteNetworks.reachable(helper.getLevel(), entity.ownerRecord(), entity.network()).contains(name),
                    "B in the picker: "
                            + RemoteNetworks.reachable(helper.getLevel(), entity.ownerRecord(), entity.network()));
            helper.assertValueEqual(NetworkGameTests.insert(core.network(), COBBLE, 50), 50L, "stored on B");
            entity.worker().setGraph(0, "Lamps", lampsOff(name));
        }).thenWaitUntil(() -> helper.assertTrue(lamps(helper).switchedOff(), "B's lamps are still on"))
                .thenExecute(() -> {
                    helper.assertValueEqual(entity.worker().remoteState(name), Optional.of(LogicWorker.RemoteState.OK),
                            "state");
                    helper.assertValueEqual(lamps(helper).switchedOffBy(),
                            Optional.of(new SwitchSource(entity.getBlockPos(), 0, "Lamps")), "turned off by");
                    helper.assertValueEqual(count(helper, entity), 50L, "the count on B");
                    int own = evaluator(helper, entity).lastActionPoints();
                    helper.assertTrue(own > 0, "the last run switched");
                    helper.assertValueEqual(entity.worker().lastActionPoints(0),
                            own + StorageServerConfig.LOGIC_CROSS_NETWORK_POINTS.getAsInt(), "points with surcharge");
                    helper.assertTrue(entity.worker().lastCost(0) > evaluator(helper, entity).lastCost(),
                            "the reads on B pay a surcharge");
                    take(network(helper, core), 45);
                }).thenWaitUntil(() -> helper.assertFalse(lamps(helper).switchedOff(), "B's lamps are still off"))
                .thenExecute(() -> helper.assertValueEqual(count(helper, entity), 5L, "the count on B")).thenSucceed();
    }

    /**
     * B belongs to another player and has no rule for A's owner: the rule pauses with an error naming B on each node
     * that uses it, and B's port stays on.
     */
    private static void refused(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "ln_refused");
        owner(helper, entity, "ln_refused_owner");
        String name = "ln refused B";
        ControllerBlockEntity core = networkB(helper, name, NetworkGameTests.player(helper.getLevel(), "ln_stranger"));
        helper.startSequence().thenWaitUntil(() -> {
            LogicControllerGameTests.network(helper, entity);
            network(helper, core);
        }).thenExecute(() -> {
            NetworkGameTests.insert(core.network(), COBBLE, 50);
            entity.worker().setGraph(0, "Lamps", lampsOff(name));
        }).thenWaitUntil(() -> assertPausedFor(helper, entity.worker(), 0, LogicLang.NET_DENIED, name)).thenIdle(20)
                .thenExecute(() -> {
                    helper.assertFalse(lamps(helper).switchedOff(), "B's lamps were switched");
                    helper.assertValueEqual(entity.worker().errors(0).size(), 3, "an error on nodes 1, 3 and 4");
                    helper.assertTrue(entity.worker().evaluator(0).isEmpty(), "a paused rule has no evaluator");
                }).thenSucceed();
    }

    /**
     * B's security module gives A's owner full access: the rule runs and switches B's port. Lowered to view, the rule
     * pauses naming B at once (the security change tells the rules), and cobblestone falling on B switches nothing.
     * Raised to logic, the rule runs again and switches B's port back on: the logic level there is enough to act.
     */
    private static void revoked(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "ln_revoked");
        ServerPlayer owner = owner(helper, entity, "ln_revoked_owner");
        String name = "ln revoked B";
        ControllerBlockEntity core = networkB(helper, name, NetworkGameTests.player(helper.getLevel(), "ln_rev_other"));
        ControllerClusterGameTests.expansion(helper, atB(1).above(), ExpansionContent.SECURITY_MODULE);
        core.setSecurity(rule(owner, AccessLevel.FULL));
        helper.startSequence().thenWaitUntil(() -> {
            LogicControllerGameTests.network(helper, entity);
            network(helper, core);
            helper.assertTrue(core.secured(), "the security module is not active yet");
        }).thenExecute(() -> {
            NetworkGameTests.insert(core.network(), COBBLE, 50);
            entity.worker().setGraph(0, "Lamps", lampsOff(name));
        }).thenWaitUntil(() -> helper.assertTrue(lamps(helper).switchedOff(), "B's lamps are still on"))
                .thenExecute(() -> core.setSecurity(rule(owner, AccessLevel.VIEW)))
                .thenWaitUntil(() -> assertPausedFor(helper, entity.worker(), 0, LogicLang.NET_DENIED, name))
                .thenExecute(() -> take(network(helper, core), 45)).thenIdle(20).thenExecute(() -> {
                    helper.assertTrue(lamps(helper).switchedOff(), "a paused rule switched B's lamps");
                    core.setSecurity(rule(owner, AccessLevel.LOGIC));
                }).thenWaitUntil(() -> helper.assertFalse(lamps(helper).switchedOff(), "B's lamps are still off"))
                .thenExecute(() -> helper.assertValueEqual(entity.worker().status(0), LogicWorker.Status.RUNNING,
                        "running again"))
                .thenSucceed();
    }

    /**
     * B belongs to another player whose security module gives A's owner only the logic level: the rule reads B and
     * switches B's port, without full access to it (owner decision 2026-10-09).
     */
    private static void logicLevelActs(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "ln_level");
        ServerPlayer owner = owner(helper, entity, "ln_level_owner");
        String name = "ln level B";
        ControllerBlockEntity core = networkB(helper, name, NetworkGameTests.player(helper.getLevel(), "ln_lvl_other"));
        ControllerClusterGameTests.expansion(helper, atB(1).above(), ExpansionContent.SECURITY_MODULE);
        core.setSecurity(rule(owner, AccessLevel.LOGIC));
        helper.startSequence().thenWaitUntil(() -> {
            LogicControllerGameTests.network(helper, entity);
            network(helper, core);
            helper.assertTrue(core.secured(), "the security module is not active yet");
        }).thenExecute(() -> {
            NetworkGameTests.insert(core.network(), COBBLE, 50);
            entity.worker().setGraph(0, "Lamps", lampsOff(name));
        }).thenWaitUntil(() -> helper.assertTrue(lamps(helper).switchedOff(), "B's lamps are still on"))
                .thenExecute(() -> {
                    helper.assertValueEqual(count(helper, entity), 50L, "the count on B");
                    take(network(helper, core), 45);
                }).thenWaitUntil(() -> helper.assertFalse(lamps(helper).switchedOff(), "B's lamps are still off"))
                .thenSucceed();
    }

    private static SecuritySettings rule(ServerPlayer player, AccessLevel level) {
        return SecuritySettings.DEFAULT.withPlayer(player.getUUID(), player.getGameProfile().name(), level);
    }

    /**
     * B's controller's chunk counts as not loaded: the rule keeps running, the count keeps the last value seen with a
     * why line naming B, and a move to B's port does nothing but say why. Loaded again, the count reads B anew.
     */
    private static void unavailable(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "ln_unloaded");
        ServerPlayer owner = owner(helper, entity, "ln_unloaded_owner");
        String name = "ln unloaded B";
        ControllerBlockEntity core = networkB(helper, name, owner);
        BlockPos coreAt = helper.absolutePos(atB(1));
        LogicGraph<LogicKey> graph = new LogicControllerGameTests.Graph().node(1, NodeTypes.REDSTONE_CHANGES, s -> s)
                .node(3, NodeTypes.MOVE_ITEMS,
                        s -> s.withItem("item", LogicKey.of(COBBLE)).withText("to", "lamps@" + name)
                                .withNumber("amount", 1))
                .node(4, NodeTypes.ITEM_COUNT,
                        s -> s.withItem("item", LogicKey.of(COBBLE)).withText("label", "@" + name))
                .node(5, NodeTypes.FOR_EACH,
                        s -> s.withText("source", "list").withItem(ForEach.item(1), LogicKey.of(COBBLE)))
                .node(6, NodeTypes.ITEM_COUNT, s -> s.withText("label", "@" + name))
                .node(7, NodeTypes.COMPARE, s -> s.withText("mode", "below").withNumber("threshold", 1))
                .node(8, NodeTypes.IF, s -> s).node(9, NodeTypes.NOTIFY, s -> s.withText("text", "empty"))
                .wire(1, "changed", 3, "do").wire(1, "changed", 5, "do").wire(5, "item", 6, "item")
                .wire(6, "count", 7, "value").wire(5, "each", 8, "when").wire(7, "result", 8, "condition")
                .wire(8, "then", 9, "do").build();
        helper.startSequence().thenWaitUntil(() -> {
            LogicControllerGameTests.network(helper, entity);
            network(helper, core);
        }).thenExecute(() -> {
            NetworkGameTests.insert(core.network(), COBBLE, 50);
            entity.worker().setGraph(0, "Feed", graph);
        }).thenWaitUntil(() -> helper.assertValueEqual(count(helper, entity), 50L, "the count on B"))
                .thenExecute(() -> {
                    RemoteNetworks.setLoadedCheck((level, at) -> !at.equals(coreAt));
                    NetworkNames.changed();
                })
                .thenWaitUntil(() -> helper.assertValueEqual(entity.worker().remoteState(name),
                        Optional.of(LogicWorker.RemoteState.UNAVAILABLE), "state"))
                .thenExecute(() -> NetworkGameTests.insert(core.network(), COBBLE, 10)).thenWaitUntil(() -> helper
                        .assertValueEqual(why(helper, entity, 4)[0], LogicLang.WHY_NET_UNAVAILABLE, "why on the count"))
                .thenExecute(() -> {
                    helper.assertValueEqual(why(helper, entity, 4)[1], name, "the why line names B");
                    helper.assertValueEqual(count(helper, entity), 50L, "the last value seen");
                    helper.assertValueEqual(entity.worker().status(0), LogicWorker.Status.RUNNING, "not paused");
                    BlockPos top = NetworkGameTests.at(3).above();
                    helper.setBlock(top, Blocks.REDSTONE_BLOCK);
                }).thenWaitUntil(() -> helper.assertValueEqual(why(helper, entity, 3)[0],
                        LogicLang.WHY_NET_UNAVAILABLE_ACT, "why on the move"))
                .thenExecute(() -> {
                    helper.assertValueEqual(why(helper, entity, 3)[1], name, "the why line names B");
                    helper.assertValueEqual(why(helper, entity, 6)[0], LogicLang.WHY_NET_UNAVAILABLE,
                            "why on the For each's count: " + traceText(evaluator(helper, entity).trace()));
                    helper.assertValueEqual(network(helper, core).contents().amount(COBBLE), 60L, "nothing moved");
                    List<LogicAction> actions = entity.worker().actions();
                    helper.assertTrue(actions.stream().anyMatch(action -> action.to().equals("lamps@" + name)),
                            "the move was asked for: " + actions);
                    // A restart while B stays unloaded: the controller saved the last value it saw.
                    CompoundTag saved = entity.saveWithoutMetadata(helper.getLevel().registryAccess());
                    helper.setBlock(NetworkGameTests.at(3), Blocks.AIR);
                    helper.setBlock(NetworkGameTests.at(3), LogicContent.CONTROLLER.get());
                    LogicControllerGameTests.entity(helper, NetworkGameTests.at(3)).loadCustomOnly(TagValueInput
                            .create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(
                        why(helper, LogicControllerGameTests.entity(helper, NetworkGameTests.at(3)), 4)[0],
                        LogicLang.WHY_NET_UNAVAILABLE, "why on the count after the reload"))
                .thenExecute(() -> {
                    helper.assertValueEqual(
                            count(helper, LogicControllerGameTests.entity(helper, NetworkGameTests.at(3))), 50L,
                            "the saved last value");
                    RemoteNetworks.setLoadedCheck(null);
                    NetworkNames.changed();
                })
                .thenWaitUntil(() -> helper.assertValueEqual(
                        count(helper, LogicControllerGameTests.entity(helper, NetworkGameTests.at(3))), 60L,
                        "B read again"))
                .thenExecute(() -> RemoteNetworks.setLoadedCheck(null)).thenSucceed();
    }

    /**
     * A rule naming B travels as text with the name: exported from A and imported beside it, it is the same graph. With
     * B renamed first, the imported rule (switched off, as every import) is paused with an error naming the old name;
     * renamed back, it is only switched off again.
     */
    private static void roundTrip(GameTestHelper helper) {
        LogicControllerBlockEntity from = LogicControllerGameTests.setUp(helper, "ln_text");
        ServerPlayer owner = LogicShareGameTests.near(helper, "ln_text_owner");
        from.setOwner(OwnerRecord.of(owner));
        LogicControllerBlockEntity to = LogicShareGameTests.second(helper, owner);
        String name = "ln text B";
        ControllerBlockEntity core = networkB(helper, name, owner);
        String[] text = new String[1];
        helper.startSequence().thenWaitUntil(() -> {
            LogicControllerGameTests.network(helper, from);
            LogicControllerGameTests.network(helper, to);
            network(helper, core);
        }).thenExecute(() -> {
            from.worker().setGraph(0, "Lamps", lampsOff(name));
            LogicShareGameTests.menu(owner, 3, from);
            SharePayloads.Exported exported = LogicShareGameTests.present(helper,
                    GraphSharing.export(owner, 3, 0, SharePayloads.TO_CLIPBOARD), "an export");
            text[0] = exported.text();
            helper.assertValueEqual(core.rename("ln text B2"), NetworkNames.Renamed.DONE, "renamed");
        }).thenWaitUntil(() -> assertPausedFor(helper, from.worker(), 0, LogicLang.NET_MISSING, name))
                .thenExecute(() -> {
                    LogicShareGameTests.menu(owner, 4, to);
                    SharePayloads.Imported imported = LogicShareGameTests.present(helper,
                            GraphSharing.importGraph(owner, 4, text[0]), "an answer");
                    helper.assertTrue(imported.added(), "imported: " + imported.message() + " " + imported.args());
                    helper.assertValueEqual(to.worker().graph(0), from.worker().graph(0), "the same graph");
                    assertPausedFor(helper, to.worker(), 0, LogicLang.NET_MISSING, name);
                    helper.assertValueEqual(core.rename(name), NetworkNames.Renamed.DONE, "renamed back");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(to.worker().status(0), LogicWorker.Status.DISABLED,
                        "the import is only switched off"))
                .thenWaitUntil(() -> helper.assertValueEqual(from.worker().status(0), LogicWorker.Status.RUNNING,
                        "the first rule runs again"))
                .thenSucceed();
    }

    /** Simulating, the rule reads B and would switch its port, but B's port stays on and the action is marked. */
    private static void simulate(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "ln_sim");
        ServerPlayer owner = owner(helper, entity, "ln_sim_owner");
        String name = "ln sim B";
        ControllerBlockEntity core = networkB(helper, name, owner);
        helper.startSequence().thenWaitUntil(() -> {
            LogicControllerGameTests.network(helper, entity);
            network(helper, core);
        }).thenExecute(() -> {
            NetworkGameTests.insert(core.network(), COBBLE, 50);
            entity.worker().setGraph(0, "Lamps", lampsOff(name));
            entity.worker().setSimulate(0, true);
        }).thenWaitUntil(() -> {
            List<LogicAction> actions = entity.worker().actions();
            helper.assertTrue(!actions.isEmpty() && actions.getLast().simulated()
                    && actions.getLast().label().equals("lamps@" + name), "no simulated switch: " + actions);
        }).thenIdle(10).thenExecute(() -> {
            helper.assertFalse(lamps(helper).switchedOff(), "a simulated switch turned B's lamps off");
            helper.assertValueEqual(count(helper, entity), 50L, "the count on B");
        }).thenSucceed();
    }

    /**
     * A new controller gets a "Network n" name, shown in Jade; a rename to a name another controller holds is refused
     * in any case; a broken controller frees its name.
     */
    private static void names(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "ln_names");
        ControllerBlockEntity core = networkB(helper, "ln names B", owner);
        helper.setBlock(new BlockPos(1, 1, 9),
                dev.silencebound.maxtech.storage.controller.ControllerContent.CONTROLLER.get());
        ControllerBlockEntity other = NetworkGameTests.controllerEntity(helper, new BlockPos(1, 1, 9));
        BlockPos again = new BlockPos(1, 1, 13);
        ItemStack[] dropped = {ItemStack.EMPTY};
        helper.startSequence().thenWaitUntil(() -> network(helper, core)).thenExecute(() -> {
            helper.assertTrue(other.networkName().startsWith("Network "), "a new name: " + other.networkName());
            NetworkNames names = NetworkNames.get(helper.getLevel().getServer());
            helper.assertTrue(names.find(other.networkName()).isPresent(), "registered");
            helper.assertValueEqual(other.rename("LN NAMES b"), NetworkNames.Renamed.TAKEN, "taken in any case");
            helper.assertValueEqual(other.rename("  "), NetworkNames.Renamed.EMPTY, "empty");
            helper.assertValueEqual(other.rename("ln@names"), NetworkNames.Renamed.DONE, "cleaned");
            helper.assertValueEqual(other.networkName(), "lnnames", "the @ is dropped");
            helper.assertValueEqual(
                    other.saveWithoutMetadata(helper.getLevel().registryAccess()).getStringOr("network_name", ""),
                    "lnnames", "saved with the core");
            List<ItemStack> drops = Block.getDrops(helper.getBlockState(new BlockPos(1, 1, 9)), helper.getLevel(),
                    helper.absolutePos(new BlockPos(1, 1, 9)), other);
            helper.assertValueEqual(drops.getFirst().getOrDefault(ModDataComponents.NETWORK_NAME.get(), ""), "lnnames",
                    "kept on the dropped core");
            List<String> keys = NetworkGameTests.keys(helper, atB(2), BlockInfoRequest.Detail.FULL);
            helper.assertTrue(keys.contains(NetworkInfo.NAME_KEY), "Jade names the network: " + keys);
            helper.setBlock(atB(1), Blocks.AIR);
            helper.assertTrue(names.find("ln names B").isEmpty(), "freed when broken");
            helper.assertValueEqual(other.rename("ln names B"), NetworkNames.Renamed.DONE, "free again");
            // Broken and placed again from its item ("lnnames"): the fresh name it loads with is not left behind.
            helper.setBlock(new BlockPos(1, 1, 9), Blocks.AIR);
            dropped[0] = drops.getFirst();
            helper.setBlock(again, dev.silencebound.maxtech.storage.controller.ControllerContent.CONTROLLER.get());
        }).thenWaitUntil(() -> helper.assertTrue(
                NetworkGameTests.controllerEntity(helper, again).networkName().startsWith("Network "),
                "loads with a fresh name")).thenExecute(() -> {
                    NetworkNames names = NetworkNames.get(helper.getLevel().getServer());
                    ControllerBlockEntity placed = NetworkGameTests.controllerEntity(helper, again);
                    String fresh = placed.networkName();
                    placed.applyComponentsFromItemStack(dropped[0]);
                    helper.assertValueEqual(placed.networkName(), "lnnames", "the item's name");
                    helper.assertTrue(names.find(fresh).isEmpty(), "a ghost entry for " + fresh);
                    long held = names.entries().stream()
                            .filter(entry -> entry.pos() == helper.absolutePos(again).asLong()).count();
                    helper.assertValueEqual(held, 1L, "entries of the placed core");
                }).thenSucceed();
    }
}
