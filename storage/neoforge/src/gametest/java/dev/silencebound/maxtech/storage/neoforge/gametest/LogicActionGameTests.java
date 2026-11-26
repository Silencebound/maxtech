package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.bulk.BulkDriveBlockEntity;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.condition.ConditionContent;
import dev.silencebound.maxtech.storage.condition.ConditionSettings;
import dev.silencebound.maxtech.storage.condition.ConditionSwitchBlockEntity;
import dev.silencebound.maxtech.storage.condition.ConditionType;
import dev.silencebound.maxtech.storage.crafting.stock.BackgroundCrafting;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.iface.InterfaceBlockEntity;
import dev.silencebound.maxtech.storage.iface.InterfaceContent;
import dev.silencebound.maxtech.storage.iface.InterfaceWorker;
import dev.silencebound.maxtech.storage.logic.LogicEvaluator;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.LogicLang;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.RunTrace;
import dev.silencebound.maxtech.storage.logic.controller.ControllerTexts;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.logic.controller.LogicNoticePayload;
import dev.silencebound.maxtech.storage.logic.controller.NoticeDelivery;
import dev.silencebound.maxtech.storage.logic.controller.LogicEvents;
import dev.silencebound.maxtech.storage.logic.controller.LogicWorker;
import dev.silencebound.maxtech.storage.logic.controller.TeamBudget;
import dev.silencebound.maxtech.storage.logic.GraphError;
import dev.silencebound.maxtech.storage.logic.NodeType;
import dev.silencebound.maxtech.storage.logic.ScriptNodeSpec;
import dev.silencebound.maxtech.storage.logic.ScriptNodes;
import dev.silencebound.maxtech.storage.logic.controller.TeamSource;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.NetworkInfo;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import dev.silencebound.maxtech.storage.network.SwitchSource;
import dev.silencebound.maxtech.storage.port.PortBlock;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortMode;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import dev.silencebound.maxtech.storage.storagechest.StorageChestBlockEntity;
import dev.silencebound.maxtech.storage.storagechest.StorageChestContent;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.SecuritySettings;
import dev.silencebound.maxtech.storage.crafting.request.CraftingSession;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.terminal.NetworkLogPayloads;
import dev.silencebound.maxtech.storage.terminal.TerminalBlockEntity;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Logic actions and the extra triggers (M7L area E), each end to end from a graph on a Logic Controller: on/off pauses
 * a port and resumes it, survives saving and the dropped item, and storage refuses it; priority; redstone through a
 * condition switch in logic mode; craft requests queued in the background and deduplicated, refusals logged; item moves
 * through a labelled port with exact counts, the port's rate as the cap, partial and full targets, never a dupe;
 * notices in the network's log, as team toasts and chat lines, and in the terminal's log tab; and the triggers redstone
 * into a labelled device, labelled devices added or removed, a cell, bulk drive or storage chest turning full and link
 * overload, each firing once on its event and not otherwise.
 *
 * <p>
 * Set-up as {@link LogicControllerGameTests#setUp}: a powered controller at x 1, a test drive at x 2, the logic
 * controller at x 3. Most graphs start from "redstone into the controller changes", toggled with a redstone block on
 * top of it ({@link #pulse}).
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class LogicActionGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final BlockPos PORT = NetworkGameTests.at(4);
    private static final BlockPos CHEST = NetworkGameTests.at(5);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("logic_action_switch_pauses_port", LogicActionGameTests::switchPausesPort);
        register("logic_action_switch_saved_and_dropped", LogicActionGameTests::switchSaved);
        register("logic_action_storage_refuses_off", LogicActionGameTests::storageRefusesOff);
        register("logic_action_priority", LogicActionGameTests::priority);
        register("logic_action_redstone_through_switch", LogicActionGameTests::redstone);
        register("logic_action_craft_background_dedup", LogicActionGameTests::craft);
        register("logic_action_move_items_exact", LogicActionGameTests::moveExact);
        register("logic_action_move_items_partial_and_full", LogicActionGameTests::movePartialAndFull);
        register("logic_action_move_interface", LogicActionGameTests::moveInterface);
        register("logic_action_move_interface_refused", LogicActionGameTests::moveInterfaceRefused);
        register("logic_action_interface_off", LogicActionGameTests::interfaceOff);
        register("logic_action_notify_log", LogicActionGameTests::notifyLog);
        register("logic_action_notify_team_toast", helper -> notifyTeam(helper, "toast"));
        register("logic_action_notify_team_chat", helper -> notifyTeam(helper, "chat"));
        register("logic_action_team_source_shares_notices_and_budget", LogicActionGameTests::teamSource);
        register("logic_action_team_source_cached", LogicActionGameTests::teamSourceCached);
        register("logic_script_node_runs_then_pauses", LogicActionGameTests::scriptNode);
        register("logic_action_terminal_log", LogicActionGameTests::terminalLog);
        register("logic_trigger_bulk_drive_full", LogicActionGameTests::bulkDriveFull);
        register("logic_trigger_storage_chest_full", LogicActionGameTests::storageChestFull);
        register("logic_trigger_redstone_into_label", LogicActionGameTests::redstoneIntoLabel);
        register("logic_trigger_label_devices", LogicActionGameTests::labelDevices);
        register("logic_trigger_cell_full", LogicActionGameTests::cellFull);
        register("logic_trigger_link_overloaded", LogicActionGameTests::linkOverloaded);
    }

    private LogicActionGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("logic_action"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 400);
        }
    }

    // ---- helpers

    private static LogicControllerBlockEntity setUp(GameTestHelper helper, String owner) {
        return LogicControllerGameTests.setUp(helper, owner);
    }

    private static NetworkState network(GameTestHelper helper, LogicControllerBlockEntity entity) {
        return LogicControllerGameTests.network(helper, entity);
    }

    /** Toggles a redstone block on top of the logic controller: "redstone changes" fires once. */
    private static void pulse(GameTestHelper helper) {
        BlockPos top = NetworkGameTests.at(3).above();
        helper.setBlock(top, helper.getBlockState(top).is(Blocks.REDSTONE_BLOCK) ? Blocks.AIR : Blocks.REDSTONE_BLOCK);
    }

    /** "Redstone into the controller changes" does {@code action} (node 2), configured by {@code settings}. */
    private static LogicGraph<LogicKey> onPulse(dev.silencebound.maxtech.storage.logic.NodeType action,
            java.util.function.UnaryOperator<dev.silencebound.maxtech.storage.logic.NodeSettings<LogicKey>> settings) {
        return new LogicControllerGameTests.Graph().node(1, NodeTypes.REDSTONE_CHANGES, s -> s)
                .node(2, action, settings).wire(1, "changed", 2, "do").build();
    }

    private static LogicEvaluator<LogicKey> evaluator(GameTestHelper helper, LogicControllerBlockEntity entity) {
        if (entity.worker().size() == 0) {
            // An earlier step failed before the graph was set: fail the test, don't crash the server.
            throw helper.assertionException(Component.literal("no graph yet"));
        }
        return entity.worker().evaluator(0)
                .orElseThrow(() -> helper.assertionException(Component.literal("graph 0 is paused")));
    }

    private static void ran(GameTestHelper helper, LogicControllerBlockEntity entity) {
        helper.assertTrue(evaluator(helper, entity).runs() > 0, "graph 0 has not run yet");
    }

    /** The why reason the last run gave for node {@code node}; empty for none. */
    private static String why(GameTestHelper helper, LogicControllerBlockEntity entity, int node) {
        RunTrace trace = evaluator(helper, entity).trace();
        int i = trace.find(node);
        return i < 0 ? "" : trace.reason(i);
    }

    /** A port at x 4 facing a chest at x 5, labelled {@code label}. */
    private static PortBlockEntity port(GameTestHelper helper, PortMode mode, String label) {
        helper.setBlock(PORT, PortContent.PORT.get().defaultBlockState().setValue(PortBlock.FACING, Direction.EAST)
                .setValue(PortBlock.MODE, mode));
        PortBlockEntity port = helper.getBlockEntity(PORT, PortBlockEntity.class);
        port.setLabel(DeviceLabel.of(label, null));
        return port;
    }

    private static ChestBlockEntity chest(GameTestHelper helper) {
        helper.setBlock(CHEST, Blocks.CHEST);
        return helper.getBlockEntity(CHEST, ChestBlockEntity.class);
    }

    private static int count(ChestBlockEntity chest, Item item) {
        int total = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack stack = chest.getItem(i);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static long stored(GameTestHelper helper, LogicControllerBlockEntity entity) {
        return network(helper, entity).contents().amount(COBBLE);
    }

    // ---- actions

    /**
     * Redstone into the controller turns the import port "intake" off: cobblestone put in the chest stays there, the
     * port says "Turned off by logic" and {@code device_state} reads off. Without redstone it is on again and imports
     * everything.
     */
    private static void switchPausesPort(GameTestHelper helper) {
        LogicControllerBlockEntity entity = setUp(helper, "la_switch");
        PortBlockEntity port = port(helper, PortMode.IMPORT, "intake");
        ChestBlockEntity chest = chest(helper);
        // On while there is no redstone: compare above 0, not, switch; device_state mirrors it in node 5.
        LogicGraph<LogicKey> graph = new LogicControllerGameTests.Graph().node(1, NodeTypes.REDSTONE_CHANGES, s -> s)
                .node(2, NodeTypes.COMPARE, s -> s).node(3, NodeTypes.NOT, s -> s)
                .node(4, NodeTypes.DEVICE_SWITCH, s -> s.withText("label", "intake"))
                .node(5, NodeTypes.DEVICE_STATE, s -> s.withText("label", "intake")).wire(1, "strength", 2, "value")
                .wire(2, "result", 3, "value").wire(3, "result", 4, "on").build();
        helper.startSequence().thenWaitUntil(() -> network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Intake", graph))
                .thenWaitUntil(() -> ran(helper, entity)).thenExecute(() -> pulse(helper))
                .thenWaitUntil(() -> helper.assertTrue(port.switchedOff(), "the port is still on")).thenExecute(() -> {
                    helper.assertValueEqual(port.switchedOffBy(),
                            Optional.of(new SwitchSource(entity.getBlockPos(), 0, "Intake")), "turned off by");
                    List<String> keys = NetworkGameTests.keys(helper, PORT, BlockInfoRequest.Detail.FULL);
                    helper.assertTrue(keys.contains(NetworkInfo.SWITCHED_OFF_KEY), "no Jade line");
                    helper.assertTrue(keys.contains(NetworkInfo.SWITCHED_OFF_BY_KEY), "no Jade line for the graph");
                    chest.setItem(0, new ItemStack(Items.COBBLESTONE, 20));
                    chest.setChanged();
                })
                .thenWaitUntil(() -> helper.assertValueEqual(
                        evaluator(helper, entity).value(evaluator(helper, entity).graph().slot(5, "on")), 0L,
                        "device_state while off"))
                .thenIdle(40).thenExecute(() -> {
                    helper.assertValueEqual(count(chest, Items.COBBLESTONE), 20, "the paused port took items");
                    helper.assertValueEqual(stored(helper, entity), 0L, "stored while paused");
                    pulse(helper);
                }).thenWaitUntil(() -> {
                    helper.assertTrue(!port.switchedOff(), "the port is still off");
                    helper.assertTrue(port.switchedOffBy().isEmpty(), "the source stayed after it was turned on");
                    helper.assertValueEqual(stored(helper, entity), 20L, "imported after it was turned on");
                    helper.assertValueEqual(count(chest, Items.COBBLESTONE), 0, "left in the chest");
                }).thenSucceed();
    }

    /** A port turned off stays off through a save and load and through its dropped item. */
    private static void switchSaved(GameTestHelper helper) {
        setUp(helper, "la_saved");
        PortBlockEntity port = port(helper, PortMode.IMPORT, "intake");
        SwitchSource source = new SwitchSource(new BlockPos(1, 2, 3), 2, "Night");
        port.setSwitchedOff(true, source);
        CompoundTag saved = port.saveWithoutMetadata(helper.getLevel().registryAccess());
        List<ItemStack> drops = Block.getDrops(helper.getBlockState(PORT), helper.getLevel(), helper.absolutePos(PORT),
                port);
        helper.assertTrue(drops.getFirst().getOrDefault(ModDataComponents.SWITCHED_OFF.get(), false),
                "off in the dropped item");
        helper.setBlock(PORT, Blocks.AIR);
        PortBlockEntity loaded = port(helper, PortMode.IMPORT, "intake");
        loaded.loadCustomOnly(
                TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
        helper.assertTrue(loaded.switchedOff(), "off after a load");
        helper.assertValueEqual(loaded.switchedOffBy(), Optional.of(source), "source after a load");
        BlockPos other = NetworkGameTests.at(7);
        helper.setBlock(other, PortContent.PORT.get().defaultBlockState().setValue(PortBlock.FACING, Direction.EAST));
        PortBlockEntity placed = helper.getBlockEntity(other, PortBlockEntity.class);
        placed.applyComponentsFromItemStack(drops.getFirst());
        helper.assertTrue(placed.switchedOff(), "off when placed from the item");
        loaded.setSwitchedOff(false);
        helper.assertTrue(!loaded.saveWithoutMetadata(helper.getLevel().registryAccess()).contains("switched_off"),
                "on is not saved");
        helper.succeed();
    }

    /** Turning off the test drive (storage) leaves it on and the why line says storage can't be turned off. */
    private static void storageRefusesOff(GameTestHelper helper) {
        LogicControllerBlockEntity entity = setUp(helper, "la_storage");
        TestStorage.TestDevice drive = helper.getBlockEntity(NetworkGameTests.at(2), TestStorage.TestDevice.class);
        drive.setLabel(DeviceLabel.of("bank", null));
        LogicGraph<LogicKey> graph = new LogicControllerGameTests.Graph()
                .node(1, NodeTypes.CONSTANT, s -> s.withText("kind", "yes_no").withFlag("yes", false))
                .node(2, NodeTypes.DEVICE_SWITCH, s -> s.withText("label", "bank")).wire(1, "value", 2, "on").build();
        helper.startSequence().thenWaitUntil(() -> network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Bank", graph)).thenWaitUntil(() -> ran(helper, entity))
                .thenExecute(() -> {
                    helper.assertTrue(!drive.switchedOff(), "storage was turned off");
                    helper.assertValueEqual(why(helper, entity, 2), LogicLang.CANT_SWITCH, "why");
                }).thenSucceed();
    }

    /** A pulse sets the labelled drive's priority to 42 through the shared priority setting; 5000 is clamped. */
    private static void priority(GameTestHelper helper) {
        LogicControllerBlockEntity entity = setUp(helper, "la_priority");
        DriveBlockEntity drive = CellGameTests.drive(helper, PORT);
        drive.setLabel(DeviceLabel.of("bank", null));
        helper.startSequence().thenWaitUntil(() -> network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Priority",
                        onPulse(NodeTypes.SET_PRIORITY, s -> s.withText("label", "bank").withNumber("priority", 42))))
                .thenWaitUntil(() -> ran(helper, entity)).thenExecute(() -> {
                    helper.assertValueEqual(drive.priority(), 0, "priority before the pulse");
                    pulse(helper);
                }).thenWaitUntil(() -> helper.assertValueEqual(drive.priority(), 42, "priority"))
                .thenExecute(() -> entity.worker().setGraph(0, "Priority",
                        onPulse(NodeTypes.SET_PRIORITY, s -> s.withText("label", "bank").withNumber("priority", 5000))))
                .thenWaitUntil(() -> ran(helper, entity)).thenExecute(() -> pulse(helper))
                .thenWaitUntil(() -> helper.assertValueEqual(drive.priority(), 5000, "priority beyond the old range"))
                .thenSucceed();
    }

    /**
     * A constant 7 drives "redstone out" to the label "lamp": the condition switch in logic mode gives 7, the one in
     * item-count mode with the same label gives nothing, and the lamp on the first lights.
     */
    private static void redstone(GameTestHelper helper) {
        LogicControllerBlockEntity entity = setUp(helper, "la_redstone");
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "la_redstone_owner");
        // Logic changes only devices its owner may configure: the switches' owner owns the controller too.
        entity.setOwner(OwnerRecord.of(owner));
        ResearchTeam team = Research.team(owner, true);
        Research.addPoints(helper.getLevel().getServer(), team, 1000);
        Research.spendPoints(helper.getLevel().getServer(), team, MaxtechStorage.id("condition_switch"));
        ConditionSwitchBlockEntity logic = switchAt(helper, PORT, owner, ConditionType.LOGIC);
        ConditionSwitchBlockEntity counting = switchAt(helper, NetworkGameTests.at(5), owner, ConditionType.ITEM_COUNT);
        helper.setBlock(PORT.above(), Blocks.REDSTONE_LAMP);
        LogicGraph<LogicKey> graph = new LogicControllerGameTests.Graph()
                .node(1, NodeTypes.CONSTANT, s -> s.withNumber("value", 7))
                .node(2, NodeTypes.REDSTONE_OUT, s -> s.withText("label", "lamp")).wire(1, "value", 2, "strength")
                .build();
        helper.startSequence().thenWaitUntil(() -> network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Lamp", graph))
                .thenWaitUntil(() -> helper.assertValueEqual(logic.worker().strength(), 7, "logic switch strength"))
                .thenExecute(() -> {
                    BlockPos lamp = helper.absolutePos(PORT.above());
                    helper.assertValueEqual(helper.getLevel().getBlockState(helper.absolutePos(PORT)).getSignal(
                            helper.getLevel(), helper.absolutePos(PORT), Direction.DOWN), 7, "signal the block gives");
                    helper.assertTrue(helper.getLevel().getBlockState(lamp)
                            .getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT), "lamp");
                    helper.assertValueEqual(counting.worker().logicStrength(), 0, "switch not in logic mode");
                    helper.assertValueEqual(counting.worker().strength(), 0, "its output");
                }).thenSucceed();
    }

    private static ConditionSwitchBlockEntity switchAt(GameTestHelper helper, BlockPos pos, ServerPlayer owner,
            ConditionType type) {
        helper.setBlock(pos, ConditionContent.SWITCH.get());
        ConditionSwitchBlockEntity entity = helper.getBlockEntity(pos, ConditionSwitchBlockEntity.class);
        entity.setOwner(OwnerRecord.of(owner));
        entity.worker().setSettings(ConditionSettings.DEFAULT.withType(type));
        entity.setLabel(DeviceLabel.of("lamp", null));
        return entity;
    }

    /**
     * A pulse requests 5 cobblestone as a background craft of the controller's owner; pulses while it runs ask for
     * nothing more (the why line says one runs); once it ends the next pulse asks again. Refused, the request goes to
     * the why line and the network's log.
     */
    private static void craft(GameTestHelper helper) {
        LogicControllerBlockEntity entity = setUp(helper, "la_craft");
        StockKeepingGameTests.FakeQueue queue = StockKeepingGameTests.QUEUE;
        helper.startSequence().thenWaitUntil(() -> network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Craft",
                        onPulse(NodeTypes.REQUEST_CRAFT,
                                s -> s.withItem("item", LogicKey.of(COBBLE)).withNumber("amount", 5))))
                .thenWaitUntil(() -> ran(helper, entity)).thenExecute(() -> pulse(helper))
                .thenWaitUntil(() -> helper.assertValueEqual(queue.calls(network(helper, entity)).size(), 1, "calls"))
                .thenExecute(() -> {
                    BackgroundCrafting.Request request = queue.calls(network(helper, entity)).getFirst().request();
                    helper.assertValueEqual(request.key(), COBBLE, "key");
                    helper.assertValueEqual(request.amount(), 5L, "amount");
                    helper.assertValueEqual(request.owner(), entity.ownerRecord(), "requested as the owner");
                    pulse(helper);
                }).thenIdle(5).thenExecute(() -> {
                    helper.assertValueEqual(queue.calls(network(helper, entity)).size(), 1, "calls while it runs");
                    helper.assertValueEqual(why(helper, entity, 2), LogicLang.WHY_CRAFT_RUNNING, "why");
                    queue.calls(network(helper, entity)).getFirst().done().accept(BackgroundCrafting.Outcome.DONE);
                    pulse(helper);
                }).thenWaitUntil(() -> helper.assertValueEqual(queue.calls(network(helper, entity)).size(), 2,
                        "calls after it ended"))
                .thenExecute(() -> {
                    queue.calls(network(helper, entity)).get(1).done().accept(BackgroundCrafting.Outcome.FAILED);
                    queue.refuse(network(helper, entity), true);
                    pulse(helper);
                }).thenIdle(5).thenExecute(() -> {
                    queue.refuse(network(helper, entity), false);
                    helper.assertValueEqual(why(helper, entity, 2), LogicLang.WHY_NOT_CRAFTABLE, "why when refused");
                    List<String> keys = new ArrayList<>();
                    for (NetworkNotices.Notice notice : network(helper, entity).notices().lines()) {
                        keys.add(notice.key());
                    }
                    helper.assertValueEqual(keys, List.of(LogicLang.LOG_CRAFT_FAILED, LogicLang.LOG_CRAFT_REFUSED),
                            "log");
                }).thenSucceed();
    }

    /**
     * Through the export port "box" (empty filter, so it moves nothing itself): a pulse moves 10 cobblestone from the
     * network into the chest, the next asks for 64 and moves at most the port's credit (16 a second), and one from
     * "box" moves 5 back. Items in the chest and the network always add up to 100.
     */
    private static void moveExact(GameTestHelper helper) {
        LogicControllerBlockEntity entity = setUp(helper, "la_move");
        port(helper, PortMode.EXPORT, "box");
        ChestBlockEntity chest = chest(helper);
        long[] before = new long[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper, entity), COBBLE, 100);
            entity.worker().setGraph(0, "Move", onPulse(NodeTypes.MOVE_ITEMS,
                    s -> s.withItem("item", LogicKey.of(COBBLE)).withText("to", "box").withNumber("amount", 10)));
        }).thenWaitUntil(() -> ran(helper, entity)).thenIdle(20).thenExecute(() -> pulse(helper))
                .thenWaitUntil(() -> helper.assertValueEqual(count(chest, Items.COBBLESTONE), 10, "in the chest"))
                .thenIdle(5).thenExecute(() -> {
                    helper.assertValueEqual(stored(helper, entity), 90L, "stored");
                    helper.assertValueEqual(entity.worker().lastMoved(), 10L, "moved");
                    entity.worker().setGraph(0, "Move", onPulse(NodeTypes.MOVE_ITEMS, s -> s
                            .withItem("item", LogicKey.of(COBBLE)).withText("to", "box").withNumber("amount", 64)));
                }).thenIdle(25).thenExecute(() -> pulse(helper)).thenIdle(5).thenExecute(() -> {
                    long moved = entity.worker().lastMoved();
                    helper.assertTrue(moved > 0 && moved <= 16, moved + " moved at 16 a second");
                    helper.assertValueEqual(count(chest, Items.COBBLESTONE) + stored(helper, entity), 100L, "total");
                    helper.assertValueEqual(why(helper, entity, 2), LogicLang.WHY_MOVED, "why for a short move");
                    before[0] = count(chest, Items.COBBLESTONE);
                    entity.worker().setGraph(0, "Back", onPulse(NodeTypes.MOVE_ITEMS, s -> s
                            .withItem("item", LogicKey.of(COBBLE)).withText("from", "box").withNumber("amount", 5)));
                }).thenIdle(25).thenExecute(() -> pulse(helper)).thenIdle(5).thenExecute(() -> {
                    helper.assertValueEqual((long) count(chest, Items.COBBLESTONE), before[0] - 5, "taken back");
                    helper.assertValueEqual(count(chest, Items.COBBLESTONE) + stored(helper, entity), 100L, "total");
                }).thenSucceed();
    }

    /**
     * A chest full of dirt but one slot of 60 cobblestone takes 4 of 10 (the why line says so), then, full, none; the
     * network keeps the rest and nothing is lost or made.
     */
    private static void movePartialAndFull(GameTestHelper helper) {
        LogicControllerBlockEntity entity = setUp(helper, "la_partial");
        port(helper, PortMode.EXPORT, "box");
        ChestBlockEntity chest = chest(helper);
        for (int slot = 1; slot < chest.getContainerSize(); slot++) {
            chest.setItem(slot, new ItemStack(Items.DIRT, 64));
        }
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 60));
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper, entity), COBBLE, 100);
            entity.worker().setGraph(0, "Move", onPulse(NodeTypes.MOVE_ITEMS,
                    s -> s.withItem("item", LogicKey.of(COBBLE)).withText("to", "box").withNumber("amount", 10)));
        }).thenWaitUntil(() -> ran(helper, entity)).thenIdle(20).thenExecute(() -> pulse(helper))
                .thenWaitUntil(() -> helper.assertValueEqual(count(chest, Items.COBBLESTONE), 64, "filled up"))
                .thenIdle(5).thenExecute(() -> {
                    helper.assertValueEqual(stored(helper, entity), 96L, "stored after a partial move");
                    helper.assertValueEqual(entity.worker().lastMoved(), 4L, "moved");
                    helper.assertValueEqual(why(helper, entity, 2), LogicLang.WHY_MOVED, "why");
                }).thenIdle(20).thenExecute(() -> pulse(helper)).thenIdle(10).thenExecute(() -> {
                    helper.assertValueEqual(entity.worker().lastMoved(), 0L, "moved into a full chest");
                    helper.assertValueEqual(stored(helper, entity), 96L, "stored");
                    helper.assertValueEqual(count(chest, Items.COBBLESTONE), 64, "in the chest");
                }).thenSucceed();
    }

    /**
     * Moves through an interface's stock (slot 0 set to 16 cobblestone): into it takes 16 of 20 asked (the target is
     * the room), then nothing while full; dirt, which no slot names, does not go in; out of it returns 5. A graph's
     * move from the label "stock" takes 3 more. Cobblestone in the stock and the network always adds up to 100.
     */
    private static void moveInterface(GameTestHelper helper) {
        LogicControllerBlockEntity entity = setUp(helper, "la_iface");
        helper.setBlock(PORT, InterfaceContent.INTERFACE.get());
        InterfaceBlockEntity iface = helper.getBlockEntity(PORT, InterfaceBlockEntity.class);
        iface.setLabel(DeviceLabel.of("stock", null));
        InterfaceWorker worker = iface.worker();
        StackKey dirt = ItemKey.of(Items.DIRT);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(iface.network() == network(helper, entity),
                "the interface is not in the network")).thenExecute(() -> {
                    NetworkState network = network(helper, entity);
                    NetworkGameTests.insert(network, COBBLE, 100);
                    NetworkGameTests.insert(network, dirt, 10);
                    worker.config().setItem(0, new ItemStack(Items.COBBLESTONE, 16));
                    long time = helper.getLevel().getGameTime();
                    // In the same tick, before the stock task fills the slot itself.
                    helper.assertValueEqual(iface.logicMove(COBBLE, 20, true, time), 16L, "moved in, up to the target");
                    helper.assertValueEqual(worker.itemStock().getAmountAsLong(0), 16L, "stock");
                    helper.assertValueEqual(stored(helper, entity), 84L, "stored");
                    helper.assertValueEqual(iface.logicMove(COBBLE, 20, true, time), 0L, "moved into a full slot");
                    helper.assertValueEqual(iface.logicMove(dirt, 5, true, time), 0L, "moved dirt in");
                    helper.assertValueEqual(network.contents().amount(dirt), 10L, "dirt stored");
                    helper.assertValueEqual(iface.logicMove(COBBLE, 5, false, time), 5L, "moved out");
                    helper.assertValueEqual(worker.itemStock().getAmountAsLong(0), 11L, "stock after moving out");
                    helper.assertValueEqual(stored(helper, entity), 89L, "stored after moving out");
                    helper.assertValueEqual(iface.logicMove(dirt, 5, false, time), 0L, "moved dirt out");
                    entity.worker().setGraph(0, "Out", onPulse(NodeTypes.MOVE_ITEMS, s -> s
                            .withItem("item", LogicKey.of(COBBLE)).withText("from", "stock").withNumber("amount", 3)));
                }).thenWaitUntil(() -> ran(helper, entity)).thenIdle(5).thenExecute(() -> pulse(helper))
                .thenWaitUntil(() -> helper.assertValueEqual(entity.worker().lastMoved(), 3L, "the graph's move"))
                .thenIdle(5).thenExecute(() -> {
                    helper.assertValueEqual(worker.itemStock().getAmountAsLong(0) + stored(helper, entity), 100L,
                            "cobblestone in the stock and the network");
                    // The stock task keeps its target: what logic took out is topped up again.
                    helper.assertValueEqual(worker.itemStock().getAmountAsLong(0), 16L, "refilled");
                }).thenSucceed();
    }

    /**
     * Without power every move is refused, both ways, and nothing is lost or made: the stock keeps its 10 and the drive
     * its 100. With power the same move out moves exactly 5.
     */
    private static void moveInterfaceRefused(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        TestStorage.TestDevice drive = NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.setBlock(NetworkGameTests.at(3), InterfaceContent.INTERFACE.get());
        InterfaceBlockEntity iface = helper.getBlockEntity(NetworkGameTests.at(3), InterfaceBlockEntity.class);
        InterfaceWorker worker = iface.worker();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(iface.network() != null, "no network"))
                .thenExecute(() -> {
                    drive.provider.setDirect(COBBLE, 100);
                    worker.config().setItem(0, new ItemStack(Items.COBBLESTONE, 16));
                    try (Transaction transaction = Transaction.openRoot()) {
                        worker.itemStock().insert(0, ItemResource.of(Items.COBBLESTONE), 10, transaction);
                        transaction.commit();
                    }
                    long time = helper.getLevel().getGameTime();
                    helper.assertValueEqual(iface.logicMove(COBBLE, 5, false, time), 0L, "moved out unpowered");
                    helper.assertValueEqual(iface.logicMove(COBBLE, 5, true, time), 0L, "moved in unpowered");
                    helper.assertValueEqual(worker.itemStock().getAmountAsLong(0), 10L, "stock");
                    helper.assertValueEqual(drive.provider.amount(COBBLE), 100L, "drive");
                    NetworkGameTests.power(helper, core, 1_000_000);
                }).thenIdle(2).thenExecute(() -> {
                    long before = worker.itemStock().getAmountAsLong(0) + drive.provider.amount(COBBLE);
                    long stock = worker.itemStock().getAmountAsLong(0);
                    long moved = iface.logicMove(COBBLE, 5, false, helper.getLevel().getGameTime());
                    helper.assertTrue(stock >= 5, "stock before the move: " + stock);
                    helper.assertValueEqual(moved, 5L, "moved out with power");
                    helper.assertValueEqual(worker.itemStock().getAmountAsLong(0) + drive.provider.amount(COBBLE),
                            before, "nothing lost or made");
                }).thenSucceed();
    }

    /**
     * An interface turned off by logic refuses a neighbour's insert and extract and logic's moves; turned on, the same
     * insert goes into the network.
     */
    private static void interfaceOff(GameTestHelper helper) {
        LogicControllerBlockEntity entity = setUp(helper, "la_iface_off");
        helper.setBlock(PORT, InterfaceContent.INTERFACE.get());
        InterfaceBlockEntity iface = helper.getBlockEntity(PORT, InterfaceBlockEntity.class);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(iface.network() == network(helper, entity),
                "the interface is not in the network")).thenExecute(() -> {
                    NetworkGameTests.insert(network(helper, entity), COBBLE, 50);
                    iface.setSwitchedOff(true);
                    ResourceHandler<ItemResource> items = iface.worker().itemHandler();
                    try (Transaction transaction = Transaction.openRoot()) {
                        helper.assertValueEqual(items.insert(InterfaceWorker.ITEM_SLOTS,
                                ItemResource.of(Items.COBBLESTONE), 10, transaction), 0, "inserted while off");
                        helper.assertValueEqual(items.extract(0, ItemResource.of(Items.COBBLESTONE), 10, transaction),
                                0, "extracted while off");
                        transaction.commit();
                    }
                    helper.assertValueEqual(iface.logicMove(COBBLE, 5, false, helper.getLevel().getGameTime()), 0L,
                            "logic move while off");
                    helper.assertValueEqual(stored(helper, entity), 50L, "stored while off");
                    iface.setSwitchedOff(false);
                    try (Transaction transaction = Transaction.openRoot()) {
                        helper.assertValueEqual(items.insert(InterfaceWorker.ITEM_SLOTS,
                                ItemResource.of(Items.COBBLESTONE), 10, transaction), 10, "inserted when on");
                        transaction.commit();
                    }
                    helper.assertValueEqual(stored(helper, entity), 60L, "stored when on");
                }).thenSucceed();
    }

    /** A notice with the log channel lands in the network's log once per pulse; one without it does not. */
    private static void notifyLog(GameTestHelper helper) {
        LogicControllerBlockEntity entity = setUp(helper, "la_notify");
        LogicGraph<LogicKey> graph = new LogicControllerGameTests.Graph().node(1, NodeTypes.REDSTONE_CHANGES, s -> s)
                .node(2, NodeTypes.NOTIFY, s -> s.withText("text", "logged").withFlag("chat", true))
                .node(3, NodeTypes.NOTIFY, s -> s.withText("text", "toast only").withFlag("log", false))
                .wire(1, "changed", 2, "do").wire(1, "changed", 3, "do").build();
        helper.startSequence().thenWaitUntil(() -> network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Notify", graph))
                .thenWaitUntil(() -> ran(helper, entity)).thenExecute(() -> pulse(helper))
                .thenWaitUntil(() -> helper.assertValueEqual(network(helper, entity).notices().posted(), 1L, "posted"))
                .thenIdle(5).thenExecute(() -> {
                    List<NetworkNotices.Notice> lines = network(helper, entity).notices().lines();
                    helper.assertValueEqual(lines.size(), 1, "lines");
                    helper.assertValueEqual(lines.getFirst().text(), "logged", "text");
                    helper.assertValueEqual(lines.getFirst().line().getString(), "logged", "shown");
                }).thenSucceed();
    }

    /** What the notice sender would have sent, per player: {@code toast:<text>} or {@code chat:<lang key>:<text>}. */
    private static final Map<UUID, List<String>> RECEIVED = new ConcurrentHashMap<>();
    private static final NoticeDelivery.Sender RECORDER = new NoticeDelivery.Sender() {
        @Override
        public void toast(ServerPlayer player, LogicNoticePayload payload) {
            received(player).add("toast:" + payload.text());
        }

        @Override
        public void chat(ServerPlayer player, Component line) {
            String entry = line.getContents() instanceof TranslatableContents contents && contents.getArgs().length == 1
                    ? contents.getKey() + ":" + contents.getArgs()[0]
                    : line.getString();
            received(player).add("chat:" + entry);
        }
    };

    private static List<String> received(ServerPlayer player) {
        return RECEIVED.computeIfAbsent(player.getUUID(), uuid -> new CopyOnWriteArrayList<>());
    }

    /**
     * A notice on the team toast or team chat channel reaches the online controller owner once per pulse and not an
     * online player outside the owner's team; the other channel sends nothing. Mock players are on the server's player
     * list but cannot take Storage's payloads, so the test swaps the sender (registered for the whole run; it records
     * only, and copies of these tests run side by side).
     */
    @SuppressWarnings("removal") // makeMockServerPlayerInLevel is the way to put a player on the player list
    private static void notifyTeam(GameTestHelper helper, String channel) {
        NoticeDelivery.setSender(RECORDER);
        ServerPlayer member = helper.makeMockServerPlayerInLevel();
        ServerPlayer outsider = helper.makeMockServerPlayerInLevel();
        LogicControllerBlockEntity entity = setUp(helper, "la_team_" + channel);
        entity.setOwner(OwnerRecord.of(member));
        boolean toast = channel.equals("toast");
        String text = "door open";
        String expected = toast ? "toast:" + text : "chat:" + ControllerTexts.CHAT + ":" + text;
        LogicGraph<LogicKey> graph = onPulse(NodeTypes.NOTIFY,
                s -> s.withText("text", text).withFlag("log", false).withFlag("toast", toast).withFlag("chat", !toast));
        helper.startSequence().thenWaitUntil(() -> network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Team", graph)).thenWaitUntil(() -> ran(helper, entity))
                .thenExecute(() -> pulse(helper))
                .thenWaitUntil(() -> helper.assertValueEqual(received(member), List.of(expected), "the owner got"))
                .thenIdle(10).thenExecute(() -> {
                    helper.assertValueEqual(received(member), List.of(expected), "the owner got, once");
                    helper.assertValueEqual(received(outsider), List.of(), "the outsider got");
                    helper.assertValueEqual(network(helper, entity).notices().posted(), 0L, "log lines");
                    helper.getLevel().getServer().getPlayerList().remove(member);
                    helper.getLevel().getServer().getPlayerList().remove(outsider);
                }).thenSucceed();
    }

    /** A script node type as KubeJS would register it: passes a trigger on, and fails on its second one. */
    private static final class Relay {
        static final NodeType TYPE = ScriptNodes.register(ScriptNodeSpec.of("gametest:relay").name("Relay").memory(1)
                .cost(3).input("in", "event").output("out", "event").evaluate(ctx -> {
                    if (!ctx.fired("in")) {
                        return;
                    }
                    ctx.setMemory(0, ctx.memory(0) + 1);
                    if (ctx.memory(0) >= 2) {
                        throw new IllegalStateException("second trigger");
                    }
                    ctx.fire("out");
                }));
    }

    /** What {@link LogicEvents} told, per controller position: {@code notice:<rule>:<text>} and {@code ran:<rule>}. */
    private static final Map<BlockPos, List<String>> HEARD = new ConcurrentHashMap<>();
    private static final LogicEvents.Listener HEARING = new LogicEvents.Listener() {
        @Override
        public boolean wantsRuns() {
            return true;
        }

        @Override
        public void ruleRan(net.minecraft.server.level.ServerLevel level, BlockPos pos, String rule, int index,
                int cost, int actions, boolean simulated, long gameTime) {
            HEARD.computeIfAbsent(pos, p -> new CopyOnWriteArrayList<>()).add("ran:" + rule);
        }

        @Override
        public void notice(net.minecraft.server.level.ServerLevel level, BlockPos pos, String rule, String text,
                int channels, UUID team) {
            HEARD.computeIfAbsent(pos, p -> new CopyOnWriteArrayList<>()).add("notice:" + rule + ":" + text);
        }
    };

    /**
     * A script node type (registered as KubeJS's {@code registerNodeTypes} would, with a Java callback) runs in a
     * controller's graph: its first trigger passes on to a notice, which the logic events hear with the rule's name, as
     * they hear every run. Its second trigger throws: the graph pauses with the script's message on the node and
     * nothing of that run is carried out.
     */
    private static void scriptNode(GameTestHelper helper) {
        LogicEvents.setListener(HEARING);
        NodeType relay = Relay.TYPE;
        LogicControllerBlockEntity entity = setUp(helper, "la_script");
        BlockPos pos = entity.getBlockPos();
        HEARD.remove(pos);
        LogicGraph<LogicKey> graph = new LogicControllerGameTests.Graph().node(1, NodeTypes.REDSTONE_CHANGES, s -> s)
                .node(2, relay, s -> s)
                .node(3, NodeTypes.NOTIFY, s -> s.withText("text", "relayed").withFlag("toast", false))
                .wire(1, "changed", 2, "in").wire(2, "out", 3, "do").build();
        helper.startSequence().thenWaitUntil(() -> network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Relay rule", graph))
                .thenWaitUntil(() -> ran(helper, entity)).thenExecute(() -> pulse(helper))
                .thenWaitUntil(() -> helper.assertValueEqual(entity.worker().actions().size(), 1, "the notice"))
                .thenExecute(() -> {
                    List<String> heard = HEARD.getOrDefault(pos, List.of());
                    helper.assertTrue(heard.contains("notice:Relay rule:relayed"), "heard the notice: " + heard);
                    helper.assertTrue(heard.contains("ran:Relay rule"), "heard the run: " + heard);
                    helper.assertValueEqual(entity.worker().status(0), LogicWorker.Status.RUNNING, "status");
                    pulse(helper);
                }).thenWaitUntil(() -> helper.assertValueEqual(entity.worker().status(0), LogicWorker.Status.PAUSED,
                        "status after the script failed"))
                .thenIdle(3).thenExecute(() -> {
                    helper.assertValueEqual(entity.worker().actions().size(), 1, "no second notice");
                    List<GraphError> errors = entity.worker().errors(0);
                    helper.assertValueEqual(errors.size(), 1, "errors");
                    helper.assertValueEqual(errors.getFirst().node(), 2, "on the script node");
                    helper.assertValueEqual(errors.getFirst().key(), LogicLang.SCRIPT_FAILED, "key");
                    helper.assertValueEqual(errors.getFirst().arg(), "second trigger", "the script's message");
                    HEARD.remove(pos);
                    // The batch teardown removes it too, should the test fail before this.
                    LogicEvents.setListener(null);
                }).thenSucceed();
    }

    /** The fake team mod: player UUID to team id, for {@link #teamSource}; players not in it are in no team. */
    static final Map<UUID, UUID> FAKE_TEAMS = new ConcurrentHashMap<>();
    /** How often the fake was asked, per player. */
    private static final Map<UUID, Integer> FAKE_LOOKUPS = new ConcurrentHashMap<>();
    private static final TeamSource FAKE_SOURCE = (server, player) -> {
        FAKE_LOOKUPS.merge(player, 1, Integer::sum);
        return FAKE_TEAMS.get(player);
    };

    private static boolean fakeTeamsInstalled;

    /**
     * Installs the fake team source once for the whole run: installing clears the team cache, which would disturb
     * {@link #teamSourceCached} running beside another team test.
     */
    static void installFakeTeams() {
        if (!fakeTeamsInstalled) {
            fakeTeamsInstalled = true;
            TeamBudget.setTeamSource(FAKE_SOURCE);
        }
    }

    /**
     * The team source's answer is cached per owner: repeated lookups (every graph run) ask it once, a team change seen
     * through {@link TeamBudget#forgetTeams} (FTB's team events) asks again and applies the new team, and an answer
     * older than {@link TeamBudget#TEAM_CACHE_TICKS} is asked again.
     */
    private static void teamSourceCached(GameTestHelper helper) {
        installFakeTeams();
        OwnerRecord owner = OwnerRecord.of(NetworkGameTests.player(helper.getLevel(), "la_team_cached"));
        UUID id = owner.owner();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        FAKE_TEAMS.put(id, first);
        FAKE_LOOKUPS.remove(id);
        for (int i = 0; i < 5; i++) {
            helper.assertValueEqual(TeamBudget.team(helper.getLevel(), owner), first, "the team");
        }
        helper.assertValueEqual(FAKE_LOOKUPS.getOrDefault(id, 0), 1, "lookups for five runs");
        FAKE_TEAMS.put(id, second);
        helper.assertValueEqual(TeamBudget.team(helper.getLevel(), owner), first, "the cached team");
        TeamBudget.forgetTeams();
        helper.assertValueEqual(TeamBudget.team(helper.getLevel(), owner), second, "the team after a change");
        helper.assertValueEqual(FAKE_LOOKUPS.getOrDefault(id, 0), 2, "lookups after a change");
        FAKE_TEAMS.remove(id);
        helper.startSequence().thenIdle(TeamBudget.TEAM_CACHE_TICKS).thenExecute(() -> {
            helper.assertValueEqual(TeamBudget.team(helper.getLevel(), owner), id, "the team after leaving");
            helper.assertValueEqual(FAKE_LOOKUPS.getOrDefault(id, 0), 3, "lookups once expired");
            FAKE_LOOKUPS.remove(id);
        }).thenSucceed();
    }

    /**
     * A team mod's team (FTB Teams through {@code compat.ftbteams}; here a fake {@link TeamSource}) decides the logic
     * team: the owner and a teammate in its team share it and both get a team toast, a player in no team keeps their
     * own (the research-team fallback) and gets nothing, and the run is charged to the team. The fake is installed for
     * the whole run and answers only for this test's players, so tests running beside it see no team mod.
     */
    @SuppressWarnings("removal") // makeMockServerPlayerInLevel is the way to put a player on the player list
    private static void teamSource(GameTestHelper helper) {
        NoticeDelivery.setSender(RECORDER);
        installFakeTeams();
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        ServerPlayer teammate = helper.makeMockServerPlayerInLevel();
        ServerPlayer outsider = helper.makeMockServerPlayerInLevel();
        UUID team = UUID.randomUUID();
        FAKE_TEAMS.put(owner.getUUID(), team);
        FAKE_TEAMS.put(teammate.getUUID(), team);
        LogicControllerBlockEntity entity = setUp(helper, "la_team_source");
        entity.setOwner(OwnerRecord.of(owner));
        helper.assertValueEqual(TeamBudget.team(helper.getLevel(), entity), team, "the controller's team");
        helper.assertValueEqual(TeamBudget.team(helper.getLevel(), OwnerRecord.of(teammate)), team, "teammate's team");
        helper.assertValueEqual(TeamBudget.team(helper.getLevel(), OwnerRecord.of(outsider)), outsider.getUUID(),
                "the outsider's team");
        String text = "pump on";
        String expected = "toast:" + text;
        LogicGraph<LogicKey> graph = onPulse(NodeTypes.NOTIFY,
                s -> s.withText("text", text).withFlag("log", false).withFlag("toast", true).withFlag("chat", false));
        long[] before = new long[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Team", graph)).thenWaitUntil(() -> ran(helper, entity))
                .thenExecute(() -> {
                    before[0] = TeamBudget.total(team);
                    pulse(helper);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(received(teammate), List.of(expected), "the teammate got"))
                .thenIdle(5).thenExecute(() -> {
                    helper.assertValueEqual(received(owner), List.of(expected), "the owner got");
                    helper.assertValueEqual(received(outsider), List.of(), "the outsider got");
                    helper.assertTrue(TeamBudget.total(team) > before[0], "the run was not charged to the team");
                    FAKE_TEAMS.remove(owner.getUUID());
                    FAKE_TEAMS.remove(teammate.getUUID());
                    helper.getLevel().getServer().getPlayerList().remove(owner);
                    helper.getLevel().getServer().getPlayerList().remove(teammate);
                    helper.getLevel().getServer().getPlayerList().remove(outsider);
                }).thenSucceed();
    }

    /**
     * The terminal's network log tab: the network's owner gets the log's lines through an open terminal; a player the
     * security module gives no access gets nothing.
     */
    private static void terminalLog(GameTestHelper helper) {
        LogicControllerBlockEntity entity = setUp(helper, "la_terminal_log");
        BlockPos core = NetworkGameTests.at(1);
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "la_log_owner");
        ServerPlayer stranger = NetworkGameTests.player(helper.getLevel(), "la_log_stranger");
        NetworkGameTests.controllerEntity(helper, core).setOwner(OwnerRecord.of(owner));
        ControllerClusterGameTests.expansion(helper, core.above(), ExpansionContent.SECURITY_MODULE);
        NetworkGameTests.controllerEntity(helper, core).setSecurity(SecuritySettings.DEFAULT
                .withPlayer(stranger.getUUID(), stranger.getGameProfile().name(), AccessLevel.NONE));
        helper.setBlock(PORT, TerminalContent.TERMINAL.get());
        List<CustomPacketPayload> toOwner = new ArrayList<>();
        List<CustomPacketPayload> toStranger = new ArrayList<>();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(
                        helper.getBlockEntity(PORT, TerminalBlockEntity.class).network() == network(helper, entity),
                        "the terminal is not in the network"))
                .thenExecute(() -> {
                    network(helper, entity).notices().post(new NetworkNotices.Notice(1, "", "pump stopped", ""));
                    helper.assertTrue(NetworkLogPayloads.answer(terminalSession(helper, owner, toOwner, 51)),
                            "the owner's answer");
                    helper.assertValueEqual(toOwner.size(), 1, "payloads to the owner");
                    NetworkLogPayloads.Log log = (NetworkLogPayloads.Log) toOwner.getFirst();
                    helper.assertValueEqual(log.containerId(), 51, "container id");
                    helper.assertValueEqual(log.lines().size(), 1, "lines");
                    helper.assertValueEqual(log.lines().getFirst().line().getString(), "pump stopped", "line");
                    helper.assertFalse(NetworkLogPayloads.answer(terminalSession(helper, stranger, toStranger, 52)),
                            "the stranger's answer");
                    helper.assertValueEqual(toStranger.size(), 0, "payloads to the stranger");
                }).thenSucceed();
    }

    private static CraftingSession terminalSession(GameTestHelper helper, ServerPlayer player,
            List<CustomPacketPayload> sent, int containerId) {
        TerminalMenu menu = TerminalMenu.server(containerId, player.getInventory(),
                helper.getBlockEntity(PORT, TerminalBlockEntity.class), update -> {
                }, sent::add);
        player.containerMenu = menu;
        return Objects.requireNonNull(menu.crafting());
    }

    // ---- triggers

    /** A counter (node 2) counting events of node 1. */
    private static LogicGraph<LogicKey> counting(dev.silencebound.maxtech.storage.logic.NodeType trigger, String output,
            java.util.function.UnaryOperator<dev.silencebound.maxtech.storage.logic.NodeSettings<LogicKey>> settings) {
        return new LogicControllerGameTests.Graph().node(1, trigger, settings).node(2, NodeTypes.COUNTER, s -> s)
                .wire(1, output, 2, "up").build();
    }

    private static long counted(GameTestHelper helper, LogicControllerBlockEntity entity) {
        LogicEvaluator<LogicKey> evaluator = evaluator(helper, entity);
        return evaluator.value(evaluator.graph().slot(2, "count"));
    }

    /**
     * Redstone placed next to the test drive labelled "door" fires once, without other triggers; a block placed
     * elsewhere does not.
     */
    private static void redstoneIntoLabel(GameTestHelper helper) {
        LogicControllerBlockEntity entity = setUp(helper, "lt_redstone");
        TestStorage.TestDevice drive = helper.getBlockEntity(NetworkGameTests.at(2), TestStorage.TestDevice.class);
        drive.setLabel(DeviceLabel.of("door", null));
        long[] runs = new long[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Door",
                        counting(NodeTypes.REDSTONE_CHANGES, "changed", s -> s.withText("label", "door"))))
                .thenWaitUntil(() -> ran(helper, entity)).thenIdle(5).thenExecute(() -> {
                    runs[0] = entity.worker().runs();
                    helper.setBlock(NetworkGameTests.at(8), Blocks.REDSTONE_BLOCK);
                }).thenIdle(5).thenExecute(() -> {
                    helper.assertValueEqual(entity.worker().runs(), runs[0], "runs for a block elsewhere");
                    helper.setBlock(NetworkGameTests.at(2).north(), Blocks.REDSTONE_BLOCK);
                }).thenWaitUntil(() -> helper.assertValueEqual(counted(helper, entity), 1L, "fired")).thenIdle(10)
                .thenExecute(() -> helper.assertValueEqual(counted(helper, entity), 1L, "fired once")).thenSucceed();
    }

    /**
     * A port placed with the label "pumps" fires "added" once; relabelling the test drive "pumps" fires it again;
     * breaking the port fires "removed" once.
     */
    private static void labelDevices(GameTestHelper helper) {
        LogicControllerBlockEntity entity = setUp(helper, "lt_devices");
        LogicGraph<LogicKey> graph = new LogicControllerGameTests.Graph()
                .node(1, NodeTypes.LABEL_DEVICES, s -> s.withText("label", "pumps")).node(2, NodeTypes.COUNTER, s -> s)
                .node(3, NodeTypes.COUNTER, s -> s).wire(1, "added", 2, "up").wire(1, "removed", 3, "up").build();
        helper.startSequence().thenWaitUntil(() -> network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Pumps", graph)).thenWaitUntil(() -> ran(helper, entity))
                .thenIdle(5).thenExecute(() -> {
                    helper.assertValueEqual(counted(helper, entity), 0L, "nothing at the start");
                    port(helper, PortMode.IMPORT, "pumps");
                }).thenWaitUntil(() -> helper.assertValueEqual(counted(helper, entity), 1L, "added"))
                .thenExecute(() -> helper.getBlockEntity(NetworkGameTests.at(2), TestStorage.TestDevice.class)
                        .setLabel(DeviceLabel.of("Pumps", null)))
                .thenWaitUntil(() -> helper.assertValueEqual(counted(helper, entity), 2L, "relabelled"))
                .thenExecute(() -> helper.setBlock(PORT, Blocks.AIR)).thenWaitUntil(() -> {
                    LogicEvaluator<LogicKey> evaluator = evaluator(helper, entity);
                    helper.assertValueEqual(evaluator.value(evaluator.graph().slot(3, "count")), 1L, "removed");
                }).thenIdle(10).thenExecute(() -> {
                    LogicEvaluator<LogicKey> evaluator = evaluator(helper, entity);
                    helper.assertValueEqual(evaluator.value(evaluator.graph().slot(3, "count")), 1L, "removed once");
                    helper.assertValueEqual(counted(helper, entity), 2L, "added twice in all");
                }).thenSucceed();
    }

    /** Filling a drive's only cell fires "a cell filled" once; more inserts, refused, fire nothing. */
    private static void cellFull(GameTestHelper helper) {
        LogicControllerBlockEntity entity = setUp(helper, "lt_full");
        DriveBlockEntity drive = CellGameTests.drive(helper, PORT);
        drive.insertCell(CellGameTests.cell(CellContent.ITEM_TYPE.id(), 1));
        drive.setLabel(DeviceLabel.of("bank", null));
        helper.startSequence().thenWaitUntil(() -> {
            network(helper, entity);
            CellGameTests.mounted(helper, drive, 0);
        }).thenExecute(() -> entity.worker().setGraph(0, "Full",
                counting(NodeTypes.STORAGE_FULL, "filled", s -> s.withText("label", "bank"))))
                .thenWaitUntil(() -> ran(helper, entity)).thenIdle(5).thenExecute(() -> {
                    helper.assertValueEqual(counted(helper, entity), 0L, "nothing full yet");
                    NetworkGameTests.insert(network(helper, entity), COBBLE, 100);
                }).thenIdle(5).thenExecute(() -> {
                    helper.assertValueEqual(counted(helper, entity), 0L, "a cell with room");
                    // Far more than one cell holds: the test drive at x 2 is not labelled, the cell fills.
                    for (int i = 0; i < 64 && drive.fullCells() == 0; i++) {
                        try (Transaction transaction = Transaction.openRoot()) {
                            CellGameTests.mounted(helper, drive, 0).insert(COBBLE, 1_000_000, transaction);
                            transaction.commit();
                        }
                    }
                    helper.assertValueEqual(drive.fullCells(), 1, "full cells");
                }).thenWaitUntil(() -> helper.assertValueEqual(counted(helper, entity), 1L, "fired")).thenIdle(10)
                .thenExecute(() -> helper.assertValueEqual(counted(helper, entity), 1L, "fired once")).thenSucceed();
    }

    /**
     * A bulk drive labelled "bank" filled to one below its cap fires nothing; the last item fires "a cell filled" once.
     * Taking one out and putting it back fires it once more.
     */
    private static void bulkDriveFull(GameTestHelper helper) {
        LogicControllerBlockEntity entity = setUp(helper, "lt_bulk_full");
        BulkDriveBlockEntity bulk = BulkDriveGameTests.drive(helper, PORT);
        bulk.setLabel(DeviceLabel.of("bank", null));
        long max = StorageServerConfig.BULK_DRIVE_MAX_COUNT.getAsLong();
        helper.startSequence()
                .thenWaitUntil(
                        () -> helper.assertTrue(bulk.network() != null && bulk.network() == network(helper, entity),
                                "the bulk drive is not in the network"))
                .thenExecute(() -> entity.worker().setGraph(0, "Bulk full",
                        counting(NodeTypes.STORAGE_FULL, "filled", s -> s.withText("label", "bank"))))
                .thenWaitUntil(() -> ran(helper, entity)).thenIdle(5).thenExecute(() -> {
                    helper.assertValueEqual(counted(helper, entity), 0L, "an empty drive");
                    helper.assertValueEqual(bulkMove(bulk, max - 1, true), max - 1, "filled to one below the cap");
                }).thenIdle(5).thenExecute(() -> {
                    helper.assertValueEqual(counted(helper, entity), 0L, "a drive with room for one");
                    bulkMove(bulk, 1, true);
                    helper.assertTrue(bulk.isFull(), "the drive is not full");
                }).thenWaitUntil(() -> helper.assertValueEqual(counted(helper, entity), 1L, "fired")).thenIdle(10)
                .thenExecute(() -> {
                    helper.assertValueEqual(counted(helper, entity), 1L, "fired once");
                    bulkMove(bulk, 1, false);
                }).thenIdle(5).thenExecute(() -> {
                    helper.assertValueEqual(counted(helper, entity), 1L, "room again fires nothing");
                    bulkMove(bulk, 1, true);
                }).thenWaitUntil(() -> helper.assertValueEqual(counted(helper, entity), 2L, "full again")).thenIdle(10)
                .thenExecute(() -> helper.assertValueEqual(counted(helper, entity), 2L, "full again, once"))
                .thenSucceed();
    }

    private static long bulkMove(BulkDriveBlockEntity bulk, long amount, boolean in) {
        try (Transaction transaction = Transaction.openRoot()) {
            long moved = in
                    ? bulk.store().insert(COBBLE, amount, transaction)
                    : bulk.store().extract(COBBLE, amount, transaction);
            transaction.commit();
            return moved;
        }
    }

    /**
     * A storage chest faced by the port labelled "bank" (switched off, so it moves nothing) fires "a cell filled" once
     * when its last free slot is taken, not before; emptying a slot and filling it again fires once more.
     */
    private static void storageChestFull(GameTestHelper helper) {
        LogicControllerBlockEntity entity = setUp(helper, "lt_chest_full");
        PortBlockEntity port = port(helper, PortMode.IMPORT, "bank");
        port.setSwitchedOff(true);
        helper.setBlock(CHEST, StorageChestContent.STORAGE_CHEST.get());
        StorageChestBlockEntity chest = helper.getBlockEntity(CHEST, StorageChestBlockEntity.class);
        int stacks = StorageChestBlockEntity.SLOTS - 1;
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(port.network() == network(helper, entity),
                        "the port is not in the network"))
                .thenExecute(() -> entity.worker().setGraph(0, "Chest full",
                        counting(NodeTypes.STORAGE_FULL, "filled", s -> s.withText("label", "bank"))))
                .thenWaitUntil(() -> ran(helper, entity)).thenIdle(5).thenExecute(() -> {
                    helper.assertValueEqual(counted(helper, entity), 0L, "an empty chest");
                    helper.assertValueEqual(chestMove(chest, COBBLE, stacks * 64L, true), stacks * 64L,
                            "all slots but one");
                }).thenIdle(5).thenExecute(() -> {
                    helper.assertValueEqual(counted(helper, entity), 0L, "a chest with a free slot");
                    chestMove(chest, ItemKey.of(Items.DIRT), 1, true);
                    helper.assertTrue(chest.isFull(), "the chest is not full");
                }).thenWaitUntil(() -> helper.assertValueEqual(counted(helper, entity), 1L, "fired")).thenIdle(10)
                .thenExecute(() -> {
                    helper.assertValueEqual(counted(helper, entity), 1L, "fired once");
                    chestMove(chest, ItemKey.of(Items.DIRT), 1, false);
                }).thenIdle(5).thenExecute(() -> {
                    helper.assertValueEqual(counted(helper, entity), 1L, "a free slot again fires nothing");
                    chestMove(chest, ItemKey.of(Items.DIRT), 1, true);
                }).thenWaitUntil(() -> helper.assertValueEqual(counted(helper, entity), 2L, "full again")).thenIdle(10)
                .thenExecute(() -> helper.assertValueEqual(counted(helper, entity), 2L, "full again, once"))
                .thenSucceed();
    }

    private static long chestMove(StorageChestBlockEntity chest, StackKey key, long amount, boolean in) {
        try (Transaction transaction = Transaction.openRoot()) {
            long moved = in ? chest.insert(key, amount, transaction) : chest.extract(key, amount, transaction);
            transaction.commit();
            return moved;
        }
    }

    /**
     * Three more devices on the tier-1 cable of the test drive labelled "busy" fill its route past 50 % of its channels
     * (M7N): "overloaded" fires once, and the graph sees the load fall back once they are gone, without new events.
     */
    private static void linkOverloaded(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.cables(helper, 2, 2,
                dev.silencebound.maxtech.storage.cable.CableContent.STORAGE_CABLE_TIER_1.type().get());
        TestStorage.TestDevice busy = NetworkGameTests.device(helper, NetworkGameTests.at(3));
        busy.setLabel(DeviceLabel.of("busy", null));
        helper.setBlock(NetworkGameTests.at(4),
                dev.silencebound.maxtech.storage.logic.controller.LogicContent.CONTROLLER.get());
        NetworkGameTests.power(helper, controller, 1_000_000);
        LogicControllerBlockEntity entity = LogicControllerGameTests.entity(helper, NetworkGameTests.at(4));
        entity.setOwner(OwnerRecord.of(NetworkGameTests.player(helper.getLevel(), "lt_load")));
        long[] taskRuns = new long[1];
        LogicGraph<LogicKey> graph = counting(NodeTypes.LINK_OVERLOADED, "overloaded",
                s -> s.withText("label", "busy").withNumber("threshold", 50).withNumber("switch_back", 30));
        List<BlockPos> more = List.of(new BlockPos(2, 1, 0), new BlockPos(2, 1, 2), new BlockPos(2, 2, 1));
        helper.startSequence().thenWaitUntil(() -> network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Load", graph)).thenWaitUntil(() -> ran(helper, entity))
                .thenIdle(5).thenExecute(() -> {
                    // The drive and the logic controller behind it use 2 of the cable's 8 channels (25 %).
                    helper.assertValueEqual(counted(helper, entity), 0L, "idle link");
                    for (BlockPos pos : more) {
                        NetworkGameTests.device(helper, pos);
                    }
                }).thenWaitUntil(() -> helper.assertValueEqual(counted(helper, entity), 1L, "fired"))
                .thenExecute(() -> {
                    for (BlockPos pos : more) {
                        helper.destroyBlock(pos);
                    }
                }).thenWaitUntil(() -> {
                    LogicEvaluator<LogicKey> evaluator = evaluator(helper, entity);
                    helper.assertValueEqual(evaluator.value(evaluator.graph().slot(1, "over")), 0L, "drained");
                }).thenExecute(() -> {
                    helper.assertValueEqual(counted(helper, entity), 1L, "fired once");
                    // The look that saw it drop may wake the task once more; after that it sleeps.
                }).thenIdle(5).thenExecute(() -> taskRuns[0] = entity.worker().taskRuns())
                .thenIdle(4 * StorageServerConfig.CONDITION_MIN_INTERVAL.getAsInt())
                // Below the limit the load is not looked at again: the controller's task sleeps.
                .thenExecute(() -> helper.assertValueEqual(entity.worker().taskRuns(), taskRuns[0],
                        "task runs once the load drained"))
                .thenSucceed();
    }
}
