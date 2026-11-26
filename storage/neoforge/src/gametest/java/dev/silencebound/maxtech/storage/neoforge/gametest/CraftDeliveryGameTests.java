package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.SecuritySettings;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.job.CraftDeliveries;
import dev.silencebound.maxtech.storage.crafting.job.CraftDelivery;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJob;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJobs;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.crafting.job.JobState;
import dev.silencebound.maxtech.storage.crafting.provider.CraftingDeliveries;
import dev.silencebound.maxtech.storage.crafting.request.CraftDestinations;
import dev.silencebound.maxtech.storage.crafting.request.CraftingSession;
import dev.silencebound.maxtech.storage.crafting.request.RequestPayloads;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.port.PortBlock;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortMode;
import dev.silencebound.maxtech.storage.terminal.TerminalAction;
import dev.silencebound.maxtech.storage.terminal.TerminalBlockEntity;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Delivering finished crafts (M7C item 5): a craft requested "to me" lands in the requester's inventory as far as it
 * has room, the rest stays in storage; out of range it all stays in storage; "to a label" moves it through the labelled
 * port into the chest it faces; a crafter without full access or a choice the server never offered is refused. Layout
 * along x at y = 1, z = 1: powered controller, test storage device (a CPU core on top), pattern host with the sticks
 * pattern, terminal; a labelled export port hangs south of the storage device, facing a chest.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class CraftDeliveryGameTests {
    private static final String[] NAMES = {"craft_deliver_to_player", "craft_deliver_out_of_range",
            "craft_deliver_to_label", "craft_deliver_refused", "craft_deliver_full_inventory",
            "craft_deliver_cancelled", "craft_deliver_label_access_lost"};

    /** The "to me" cases. */
    private enum Case {
        NEAR, FAR, FULL, CANCELLED
    }
    private static final StackKey PLANKS = ItemKey.of(Items.OAK_PLANKS);
    private static final StackKey STICK = ItemKey.of(Items.STICK);
    private static final CraftingPattern STICKS = new CraftingPattern(CraftingPattern.Kind.CRAFTING,
            List.of(new PatternInput(new PatternIngredient.Exact(PLANKS), 2, Optional.empty(), false)),
            List.of(new PatternOutput(STICK, 4, 1f)), Optional.empty(), Optional.empty());
    private static final BlockPos TERMINAL = NetworkGameTests.at(4);
    private static final BlockPos PORT = NetworkGameTests.at(2).south();
    private static final BlockPos CHEST = PORT.south();
    /** The players the delivery finds as if online; tests of one batch run side by side. */
    private static final Map<UUID, ServerPlayer> ONLINE = new ConcurrentHashMap<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        CraftingTestContent.init();
        register(NAMES[0], helper -> toPlayer(helper, Case.NEAR));
        register(NAMES[1], helper -> toPlayer(helper, Case.FAR));
        register(NAMES[2], helper -> toLabel(helper, false));
        register(NAMES[3], CraftDeliveryGameTests::refused);
        register(NAMES[4], helper -> toPlayer(helper, Case.FULL));
        register(NAMES[5], helper -> toPlayer(helper, Case.CANCELLED));
        register(NAMES[6], helper -> toLabel(helper, true));
    }

    private CraftDeliveryGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("craft_delivery"), StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 400);
        }
    }

    private static CraftingTestContent.Host setUp(GameTestHelper helper, boolean cpu) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        BlockPos hostPos = NetworkGameTests.at(3);
        helper.setBlock(hostPos, CraftingTestContent.HOST.get());
        CraftingTestContent.Host host = helper.getBlockEntity(hostPos, CraftingTestContent.Host.class);
        host.setPatterns(List.of(STICKS));
        helper.setBlock(TERMINAL, TerminalContent.TERMINAL.get());
        if (cpu) {
            helper.setBlock(NetworkGameTests.at(2).above(), CpuContent.CORE_1.get());
        }
        return host;
    }

    private static NetworkState network(GameTestHelper helper) {
        NetworkState network = helper.getBlockEntity(TERMINAL, TerminalBlockEntity.class).network();
        if (network == null) {
            throw helper.assertionException(Component.literal("the terminal is in no network"));
        }
        return network;
    }

    private static TerminalMenu open(GameTestHelper helper, ServerPlayer player, List<CustomPacketPayload> sent,
            int containerId) {
        TerminalMenu menu = TerminalMenu.server(containerId, player.getInventory(),
                helper.getBlockEntity(TERMINAL, TerminalBlockEntity.class), update -> {
                }, sent::add);
        player.containerMenu = menu;
        menu.clickMenuButton(player, new TerminalAction(TerminalAction.Kind.REFRESH, -1).buttonId());
        menu.broadcastChanges();
        return menu;
    }

    private static CraftingSession session(GameTestHelper helper, TerminalMenu menu) {
        CraftingSession session = menu.crafting();
        if (session == null) {
            throw helper.assertionException(Component.literal("no crafting session"));
        }
        return session;
    }

    private static RequestPayloads.Request plan(TerminalMenu menu, long amount) {
        return new RequestPayloads.Request(menu.containerId, RequestPayloads.Action.PLAN, 1, Optional.of(STICK), amount,
                Optional.empty());
    }

    private static RequestPayloads.Request start(TerminalMenu menu, int deliverTo) {
        return new RequestPayloads.Request(menu.containerId, RequestPayloads.Action.START, deliverTo, Optional.empty(),
                0, Optional.empty());
    }

    private static <T> List<T> of(List<CustomPacketPayload> sent, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (CustomPacketPayload payload : sent) {
            if (type.isInstance(payload)) {
                found.add(type.cast(payload));
            }
        }
        return found;
    }

    private static String lastNotice(List<CustomPacketPayload> sent) {
        List<RequestPayloads.Notice> notices = of(sent, RequestPayloads.Notice.class);
        return notices.isEmpty() ? "" : notices.getLast().key();
    }

    private static JobState state(GameTestHelper helper, UUID id) {
        CraftingJob job = CraftingJobs.get(helper.getLevel()).get(id);
        return job == null ? JobState.DONE : job.state();
    }

    /** The job's sticks come back as a provider would offer them: ten, of which the job expects eight. */
    private static void offerSticks(GameTestHelper helper) {
        NetworkState network = network(helper);
        NetworkGameTests.insert(network, STICK, 10);
        CraftingDeliveries.delivered(network, STICK, 10);
    }

    /**
     * A player without a connection whom the delivery finds as if online ({@link CraftDelivery#setPlayerLookup}); a
     * listed mock player could not take the job-finished toast.
     */
    private static ServerPlayer online(GameTestHelper helper, String name) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), name);
        UUID id = player.getUUID();
        ONLINE.put(id, player);
        CraftDelivery.setPlayerLookup(ONLINE::get);
        return player;
    }

    private static int count(Inventory inventory, StackKey key) {
        int count = 0;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (ItemKey.of(stack).equals(key)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * Eight sticks "to me" for a player whose inventory has room for five: five arrive, three stay in storage with the
     * two spare; out of range or with a full inventory nothing arrives and all ten stay. A job cancelled after its
     * crafts went out delivers nothing: the ten sticks that come back afterwards are stored once, not counted for it.
     * The delivery is gone once the job ended.
     */
    private static void toPlayer(GameTestHelper helper, Case which) {
        boolean near = which == Case.NEAR;
        CraftingTestContent.Host host = setUp(helper, true);
        ServerPlayer player = online(helper, "deliver_me");
        BlockPos at = which == Case.FAR ? TERMINAL.above().east(100) : TERMINAL.above();
        player.setPos(helper.absoluteVec(at.getCenter()));
        Inventory inventory = player.getInventory();
        for (int slot = 1; slot < Inventory.INVENTORY_SIZE; slot++) {
            inventory.setItem(slot, new ItemStack(Items.DIRT, 64));
        }
        inventory.setItem(0, which == Case.FULL ? new ItemStack(Items.DIRT, 64) : new ItemStack(Items.STICK, 59));
        List<CustomPacketPayload> sent = new ArrayList<>();
        TerminalMenu[] menu = new TerminalMenu[1];
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> network(helper)).thenExecute(() -> {
            NetworkGameTests.insert(network(helper), PLANKS, 4);
            menu[0] = open(helper, player, sent, 1);
            helper.assertTrue(session(helper, menu[0]).handle(plan(menu[0], 8)), "planned");
        }).thenWaitUntil(() -> helper.assertValueEqual(of(sent, RequestPayloads.Plan.class).size(), 1, "the plan"))
                .thenIdle(1).thenExecute(() -> {
                    helper.assertValueEqual(of(sent, CraftDestinations.Destinations.class).size(), 1,
                            "destinations come with the plan");
                    helper.assertTrue(session(helper, menu[0]).handle(start(menu[0], CraftDestinations.PLAYER)),
                            "started to me");
                    id[0] = JobQueue.jobsOn(network(helper)).getFirst().id();
                    helper.assertTrue(CraftDeliveries.get(helper.getLevel()).get(id[0]) != null, "delivery kept");
                }).thenWaitUntil(() -> helper.assertValueEqual(host.pushed, 2, "both crafts sent")).thenExecute(() -> {
                    if (which == Case.CANCELLED) {
                        helper.assertTrue(JobQueue.cancel(helper.getLevel(), id[0]), "cancelled");
                    }
                    offerSticks(helper);
                }).thenWaitUntil(() -> helper.assertValueEqual(state(helper, id[0]), JobState.DONE, "ended"))
                .thenExecute(() -> {
                    int expected = switch (which) {
                        case NEAR -> 64;
                        case FULL -> 0;
                        default -> 59;
                    };
                    helper.assertValueEqual(count(inventory, STICK), expected, "sticks in the inventory");
                    helper.assertValueEqual(network(helper).contents().amount(STICK), near ? 5L : 10L,
                            "sticks left in storage");
                    helper.assertTrue(CraftDeliveries.get(helper.getLevel()).get(id[0]) == null, "delivery dropped");
                    ONLINE.remove(player.getUUID());
                }).thenSucceed();
    }

    /**
     * Eight sticks "to" the label of an export port facing a chest: they end in the chest, two spare stay. When the
     * requester lost full access while the job ran (the network got an owner), nothing goes out and all ten stay.
     */
    private static void toLabel(GameTestHelper helper, boolean accessLost) {
        CraftingTestContent.Host host = setUp(helper, true);
        helper.setBlock(CHEST, Blocks.CHEST);
        helper.setBlock(PORT, PortContent.PORT.get().defaultBlockState().setValue(PortBlock.FACING, Direction.SOUTH)
                .setValue(PortBlock.MODE, PortMode.EXPORT));
        PortBlockEntity port = helper.getBlockEntity(PORT, PortBlockEntity.class);
        port.setLabel(DeviceLabel.of("Out", null));
        ServerPlayer player = online(helper, "deliver_label");
        player.setPos(helper.absoluteVec(TERMINAL.above().getCenter()));
        List<CustomPacketPayload> sent = new ArrayList<>();
        TerminalMenu[] menu = new TerminalMenu[1];
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> {
            network(helper);
            helper.assertTrue(port.network() != null, "the port joined");
        }).thenIdle(30).thenExecute(() -> {
            NetworkGameTests.insert(network(helper), PLANKS, 4);
            menu[0] = open(helper, player, sent, 1);
            helper.assertTrue(session(helper, menu[0]).handle(plan(menu[0], 8)), "planned");
        }).thenWaitUntil(() -> helper.assertValueEqual(of(sent, RequestPayloads.Plan.class).size(), 1, "the plan"))
                .thenIdle(1).thenExecute(() -> {
                    List<DeviceLabel> labels = of(sent, CraftDestinations.Destinations.class).getLast().labels();
                    helper.assertValueEqual(labels.size(), 1, "one port label offered");
                    helper.assertValueEqual(labels.getFirst().text(), "Out", "the port's label");
                    helper.assertTrue(session(helper, menu[0]).handle(start(menu[0], CraftDestinations.LABEL)),
                            "started to the label");
                    id[0] = JobQueue.jobsOn(network(helper)).getFirst().id();
                    if (accessLost) {
                        NetworkGameTests.controllerEntity(helper, NetworkGameTests.at(1)).setOwner(
                                OwnerRecord.of(NetworkGameTests.player(helper.getLevel(), "deliver_new_owner")));
                    }
                }).thenWaitUntil(() -> helper.assertValueEqual(host.pushed, 2, "both crafts sent"))
                .thenExecute(() -> offerSticks(helper))
                .thenWaitUntil(() -> helper.assertValueEqual(state(helper, id[0]), JobState.DONE, "done"))
                .thenExecute(() -> {
                    ChestBlockEntity chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
                    int inChest = 0;
                    for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                        inChest += chest.getItem(slot).is(Items.STICK) ? chest.getItem(slot).getCount() : 0;
                    }
                    helper.assertValueEqual(inChest, accessLost ? 0 : 8, "sticks in the chest");
                    helper.assertValueEqual(network(helper).contents().amount(STICK), accessLost ? 10L : 2L,
                            "sticks stored");
                    ONLINE.remove(player.getUUID());
                }).thenSucceed();
    }

    /**
     * A crafter (craft access, not full) may start a craft to storage but not to their inventory or a label, and a
     * choice beyond the offered list is refused without a notice; nothing is queued by a refused start.
     */
    private static void refused(GameTestHelper helper) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "deliver_owner");
        ServerPlayer crafter = NetworkGameTests.player(helper.getLevel(), "deliver_crafter");
        setUp(helper, false);
        BlockPos core = NetworkGameTests.at(1);
        NetworkGameTests.controllerEntity(helper, core).setOwner(OwnerRecord.of(owner));
        ControllerClusterGameTests.expansion(helper, core.above(), ExpansionContent.SECURITY_MODULE);
        NetworkGameTests.controllerEntity(helper, core).setSecurity(SecuritySettings.DEFAULT
                .withPlayer(crafter.getUUID(), crafter.getGameProfile().name(), AccessLevel.CRAFT));
        crafter.setPos(helper.absoluteVec(TERMINAL.above().getCenter()));
        List<CustomPacketPayload> sent = new ArrayList<>();
        TerminalMenu[] menu = new TerminalMenu[1];
        helper.startSequence().thenWaitUntil(() -> {
            network(helper);
            helper.assertTrue(NetworkGameTests.controllerEntity(helper, core).secured(), "module active");
        }).thenExecute(() -> {
            NetworkGameTests.insert(network(helper), PLANKS, 4);
            menu[0] = open(helper, crafter, sent, 1);
            helper.assertTrue(session(helper, menu[0]).handle(plan(menu[0], 4)), "crafter plans");
        }).thenWaitUntil(() -> helper.assertValueEqual(of(sent, RequestPayloads.Plan.class).size(), 1, "the plan"))
                .thenIdle(1).thenExecute(() -> {
                    helper.assertFalse(session(helper, menu[0]).handle(start(menu[0], CraftDestinations.PLAYER)),
                            "to me without full access");
                    helper.assertValueEqual(lastNotice(sent), CraftDestinations.DENIED_KEY, "the notice");
                    helper.assertTrue(JobQueue.jobsOn(network(helper)).isEmpty(), "nothing queued");
                }).thenIdle(1).thenExecute(() -> {
                    helper.assertFalse(session(helper, menu[0]).handle(start(menu[0], CraftDestinations.LABEL)),
                            "a label the server never offered");
                    helper.assertTrue(JobQueue.jobsOn(network(helper)).isEmpty(), "still nothing queued");
                }).thenIdle(1).thenExecute(() -> {
                    helper.assertTrue(session(helper, menu[0]).handle(start(menu[0], CraftDestinations.STORAGE)),
                            "to storage is fine");
                    CraftingJob job = JobQueue.jobsOn(network(helper)).getFirst();
                    helper.assertTrue(CraftDeliveries.get(helper.getLevel()).get(job.id()) == null,
                            "no delivery for storage");
                    JobQueue.cancel(helper.getLevel(), job.id());
                }).thenSucceed();
    }
}
