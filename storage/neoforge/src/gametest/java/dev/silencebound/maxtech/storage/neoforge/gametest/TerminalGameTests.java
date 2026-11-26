package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.lib.sync.ClientSyncedList;
import dev.silencebound.maxtech.lib.sync.ServerSyncedList;
import dev.silencebound.maxtech.lib.sync.SyncedListUpdate;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.material.MaterialContent;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.terminal.TerminalAction;
import dev.silencebound.maxtech.storage.terminal.TerminalBlock;
import dev.silencebound.maxtech.storage.terminal.TerminalBlockEntity;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The item terminal (M6a area 3): a menu's synced list gets a full dump on request and merged, rate-limited deltas
 * after it, and a new dump when the terminal lands in another network; clicks take, store, fill and empty with nothing
 * made or lost; a player the owner has not let in moves nothing; a dump of component-heavy keys stays under the batch
 * byte cap; and the terminal is craftable from area 5's parts. Menus run with a recording sink instead of a connection.
 * Layouts run along x at y = 1, z = 1: controller, drive, drive; the terminal hangs off the last drive at z = 2, facing
 * away from it.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
// Networks are compared by identity: a split hands the terminal a different one.
@SuppressWarnings("ReferenceEquality")
public final class TerminalGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_16x12x16");
    private static final String[] NAMES = {"terminal_sync_dump_and_deltas", "terminal_withdraw_and_deposit",
            "terminal_access_refused", "terminal_dump_batched_by_bytes", "terminal_recipe", "terminal_with_drive",
            "terminal_links_except_front"};
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey STONE = ItemKey.of(Items.STONE);
    private static final StackKey EMERALD = ItemKey.of(Items.EMERALD);
    private static final FluidKey WATER = FluidKey.of(Fluids.WATER);
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register(NAMES[0], TerminalGameTests::syncDumpAndDeltas);
        register(NAMES[1], TerminalGameTests::withdrawAndDeposit);
        register(NAMES[2], TerminalGameTests::accessRefused);
        register(NAMES[3], TerminalGameTests::dumpBatchedByBytes);
        register(NAMES[4], TerminalGameTests::recipe);
        register(NAMES[5], TerminalGameTests::withDrive);
        register(NAMES[6], TerminalGameTests::linksExceptFront);
    }

    private TerminalGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("terminal"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, STRUCTURE, 400, 0, true)));
        }
    }

    /** A terminal facing east, so its back is the west side where the neighbouring device sits. */
    private static BlockState behind() {
        return TerminalContent.TERMINAL.get().defaultBlockState().setValue(TerminalBlock.FACING, Direction.EAST);
    }

    /** A controller with FE, two test drives and a terminal on the second one's back; returns the terminal. */
    private static TerminalBlockEntity network(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 10_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        NetworkGameTests.device(helper, NetworkGameTests.at(3));
        // Hangs off the second drive's south side, which is its back.
        BlockPos terminal = NetworkGameTests.at(3).south();
        helper.setBlock(terminal,
                TerminalContent.TERMINAL.get().defaultBlockState().setValue(TerminalBlock.FACING, Direction.SOUTH));
        return helper.getBlockEntity(terminal, TerminalBlockEntity.class);
    }

    private static TestStorage.TestDevice drive(GameTestHelper helper, int x) {
        return helper.getBlockEntity(NetworkGameTests.at(x), TestStorage.TestDevice.class);
    }

    private static NetworkState net(GameTestHelper helper, TerminalBlockEntity terminal) {
        NetworkState network = terminal.network();
        if (network == null) {
            throw helper.assertionException(Component.literal("Terminal has no network"));
        }
        return network;
    }

    /** A player standing by the terminal, so the menu's reach check holds. */
    private static ServerPlayer player(GameTestHelper helper, String name) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), name);
        player.setPos(helper.absoluteVec(NetworkGameTests.at(3).above().getCenter()));
        return player;
    }

    static boolean click(TerminalMenu menu, ServerPlayer player, TerminalAction.Kind kind, int id) {
        return menu.clickMenuButton(player, new TerminalAction(kind, id).buttonId());
    }

    /** Applies every recorded batch to a client list through the payload codec, as a client would get them. */
    static ClientSyncedList<StackKey> replay(GameTestHelper helper, List<SyncedListUpdate<StackKey>> sent) {
        ClientSyncedList<StackKey> client = new ClientSyncedList<>();
        for (SyncedListUpdate<StackKey> update : sent) {
            client.apply(decode(helper, update));
        }
        return client;
    }

    private static SyncedListUpdate<StackKey> decode(GameTestHelper helper, SyncedListUpdate<StackKey> update) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess());
        TerminalContent.LIST.codec().encode(buf, update);
        return TerminalContent.LIST.codec().decode(buf);
    }

    /**
     * Nothing is sent before the client asks; the dump then holds the stored keys. Changes are merged per key: three
     * changes of one key and a new key in one tick arrive as one batch with two counts, and a key changing every tick
     * for 20 ticks is sent only at the configured rate, ending at its last count. After a split and a merge, the client
     * holds exactly the terminal's network; a move into another network sends a fresh dump.
     */
    private static void syncDumpAndDeltas(GameTestHelper helper) {
        TerminalBlockEntity terminal = network(helper);
        ServerPlayer player = player(helper, "terminal_viewer");
        List<SyncedListUpdate<StackKey>> sent = new ArrayList<>();
        int[] ticks = new int[1];
        int[] batchesBefore = new int[1];
        NetworkState[] before = new NetworkState[1];
        helper.startSequence().thenWaitUntil(() -> net(helper, terminal)).thenExecute(() -> {
            drive(helper, 2).provider.setDirect(COBBLE, 40);
            TerminalMenu menu = TerminalMenu.server(1, player.getInventory(), terminal, sent::add);
            player.containerMenu = menu;
            menu.broadcastChanges();
            helper.assertValueEqual(sent.size(), 0, "batches before the client asked");
            helper.assertTrue(click(menu, player, TerminalAction.Kind.REFRESH, -1), "refresh accepted");
            menu.broadcastChanges();
            helper.assertValueEqual(sent.size(), 1, "the dump");
            helper.assertTrue(sent.get(0).reset(), "a dump resets the client");
            helper.assertFalse(click(menu, player, TerminalAction.Kind.REFRESH, -1), "a second refresh at once");
            drive(helper, 2).provider.setDirect(COBBLE, 50);
            drive(helper, 2).provider.setDirect(COBBLE, 45);
            NetworkGameTests.insert(net(helper, terminal), STONE, 7);
            helper.onEachTick(() -> {
                if (ticks[0] < 20) {
                    ticks[0]++;
                    drive(helper, 2).provider.setDirect(COBBLE, 45L + ticks[0]);
                }
                menu.broadcastChanges();
            });
        }).thenIdle(25).thenExecute(() -> {
            SyncedListUpdate<StackKey> delta = decode(helper, sent.get(1));
            helper.assertValueEqual(delta.countCount(), 2, "counts in the first delta, one per key");
            helper.assertValueEqual(delta.definitionCount(), 1, "only stone is new to the client");
            // Rates that divide 20 evenly (the presets use 4) send exactly that often in 20 ticks of changes.
            int perSecond = ServerConfig.LIST_UPDATES_PER_SECOND.getAsInt();
            helper.assertValueEqual(sent.size() - 1, perSecond, "deltas sent in 20 ticks of changes");
            ClientSyncedList<StackKey> client = replay(helper, sent);
            NetworkState network = net(helper, terminal);
            helper.assertValueEqual(client.count(network.contents().keyId(COBBLE)), 65L, "the last cobblestone count");
            helper.assertValueEqual(client.count(network.contents().keyId(STONE)), 7L, "stone");
        }).thenExecute(() -> NetworkGameTests.device(helper, NetworkGameTests.at(4)).provider
                .setDirect(ItemKey.of(Items.DIAMOND), 3))
                .thenWaitUntil(
                        () -> helper.assertTrue(net(helper, terminal).deviceCount() == 5, "the new drive joined"))
                .thenIdle(6).thenExecute(() -> {
                    before[0] = net(helper, terminal);
                    batchesBefore[0] = sent.size();
                    helper.destroyBlock(NetworkGameTests.at(2));
                }).thenWaitUntil(() -> helper.assertTrue(net(helper, terminal).deviceCount() == 3, "split off"))
                .thenIdle(6).thenExecute(() -> {
                    // The graph decides which part keeps the old network; a terminal that lands in a new one must
                    // send a new dump, one that stays gets the drive's keys removed as deltas.
                    boolean dumped = false;
                    for (int i = batchesBefore[0]; i < sent.size(); i++) {
                        dumped |= sent.get(i).reset();
                    }
                    helper.assertValueEqual(dumped, net(helper, terminal) != before[0], "a dump exactly on a move");
                    ClientSyncedList<StackKey> client = replay(helper, sent);
                    NetworkState network = net(helper, terminal);
                    helper.assertValueEqual(client.size(), 1, "keys on the client after the split");
                    int diamond = network.contents().keyId(ItemKey.of(Items.DIAMOND));
                    helper.assertValueEqual(client.count(diamond), 3L, "the diamonds the terminal's part holds");
                    helper.assertValueEqual(terminal.openMenus(), 1, "the menu still watches");
                    for (int x = 6; x <= 9; x++) {
                        NetworkGameTests.device(helper, NetworkGameTests.at(x));
                    }
                    drive(helper, 6).provider.setDirect(EMERALD, 2);
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        NetworkGameTests.net(helper, drive(helper, 6)).deviceCount() == 4, "the other network"))
                .thenExecute(() -> {
                    before[0] = net(helper, terminal);
                    batchesBefore[0] = sent.size();
                    // Bridges the terminal's three devices to the four: the smaller network is absorbed.
                    NetworkGameTests.device(helper, NetworkGameTests.at(5));
                }).thenWaitUntil(() -> helper.assertTrue(net(helper, terminal).deviceCount() == 8, "merged"))
                .thenIdle(6).thenExecute(() -> {
                    helper.assertTrue(net(helper, terminal) != before[0], "the terminal moved to the larger network");
                    boolean dumped = false;
                    for (int i = batchesBefore[0]; i < sent.size(); i++) {
                        dumped |= sent.get(i).reset();
                    }
                    helper.assertTrue(dumped, "the move sent a new dump");
                    ClientSyncedList<StackKey> client = replay(helper, sent);
                    NetworkState network = net(helper, terminal);
                    helper.assertValueEqual(client.size(), 2, "keys on the client after the merge");
                    helper.assertValueEqual(client.count(network.contents().keyId(EMERALD)), 2L, "the emeralds");
                }).thenSucceed();
    }

    /**
     * Taking a stack, half and one onto the cursor, a stack into the inventory as far as it has room, storing all or
     * one, shift-clicking an inventory stack in, and filling and emptying a bucket: after each, what the network and
     * the player hold adds up to what was there.
     */
    private static void withdrawAndDeposit(GameTestHelper helper) {
        TerminalBlockEntity terminal = network(helper);
        ServerPlayer player = player(helper, "terminal_user");
        helper.startSequence().thenWaitUntil(() -> net(helper, terminal)).thenExecute(() -> {
            NetworkState network = net(helper, terminal);
            NetworkGameTests.insert(network, COBBLE, 100);
            NetworkGameTests.insert(network, WATER, 3000);
            TerminalMenu menu = TerminalMenu.server(1, player.getInventory(), terminal, update -> {
            });
            player.containerMenu = menu;
            int cobble = network.contents().keyId(COBBLE);
            int water = network.contents().keyId(WATER);
            helper.assertTrue(click(menu, player, TerminalAction.Kind.TAKE_STACK, cobble), "take a stack");
            expect(helper, menu, network, 64, 36, "a stack");
            helper.assertFalse(click(menu, player, TerminalAction.Kind.TAKE_ONE, cobble), "take onto a full cursor");
            helper.assertTrue(click(menu, player, TerminalAction.Kind.DEPOSIT_ONE, -1), "store one");
            expect(helper, menu, network, 63, 37, "one stored");
            helper.assertTrue(click(menu, player, TerminalAction.Kind.DEPOSIT_ALL, -1), "store all");
            expect(helper, menu, network, 0, 100, "all stored");
            helper.assertTrue(click(menu, player, TerminalAction.Kind.TAKE_HALF, cobble), "take half");
            expect(helper, menu, network, 32, 68, "half a stack");
            menu.clickMenuButton(player, new TerminalAction(TerminalAction.Kind.DEPOSIT_ALL, -1).buttonId());
            helper.assertTrue(click(menu, player, TerminalAction.Kind.TAKE_ONE, cobble), "take one");
            expect(helper, menu, network, 1, 99, "one");
            menu.clickMenuButton(player, new TerminalAction(TerminalAction.Kind.DEPOSIT_ALL, -1).buttonId());

            // Into the inventory: all 36 slots full of dirt but one holding 60 cobblestone leaves room for 4.
            for (int i = 0; i < 36; i++) {
                player.getInventory().setItem(i,
                        new ItemStack(i == 5 ? Items.COBBLESTONE : Items.DIRT, i == 5 ? 60 : 64));
            }
            helper.assertTrue(click(menu, player, TerminalAction.Kind.TAKE_TO_INVENTORY, cobble), "to inventory");
            helper.assertValueEqual(player.getInventory().getItem(5).getCount(), 64, "the cobblestone slot");
            helper.assertValueEqual(network.contents().amount(COBBLE), 96L, "four left the network");
            helper.assertFalse(click(menu, player, TerminalAction.Kind.TAKE_TO_INVENTORY, cobble), "no room left");

            // Shift-clicking the hotbar's first slot stores its stack.
            player.getInventory().setItem(0, new ItemStack(Items.STONE, 10));
            menu.quickMoveStack(player, slotOf(menu, 0));
            helper.assertTrue(player.getInventory().getItem(0).isEmpty(), "the shift-clicked slot emptied");
            helper.assertValueEqual(network.contents().amount(STONE), 10L, "stone stored");
            player.getInventory().clearContent();

            // A bucket fills from the water entry and empties back.
            menu.setCarried(new ItemStack(Items.BUCKET));
            helper.assertTrue(click(menu, player, TerminalAction.Kind.FILL_CONTAINER, water), "fill");
            helper.assertTrue(menu.getCarried().is(Items.WATER_BUCKET), "a water bucket, was " + menu.getCarried());
            helper.assertValueEqual(network.contents().amount(WATER), 2000L, "a bucket left");
            helper.assertTrue(click(menu, player, TerminalAction.Kind.DEPOSIT_ONE, -1), "empty");
            helper.assertTrue(menu.getCarried().is(Items.BUCKET), "the empty bucket back");
            helper.assertValueEqual(network.contents().amount(WATER), 3000L, "the water back");
            helper.assertValueEqual(network.contents().amount(ItemKey.of(Items.BUCKET)), 0L, "the bucket not stored");
        }).thenSucceed();
    }

    private static void expect(GameTestHelper helper, TerminalMenu menu, NetworkState network, int carried, long stored,
            String step) {
        ItemStack cursor = menu.getCarried();
        helper.assertValueEqual(cursor.isEmpty() ? 0 : cursor.getCount(), carried, step + ": on the cursor");
        helper.assertTrue(cursor.isEmpty() || cursor.is(Items.COBBLESTONE), step + ": cursor holds " + cursor);
        helper.assertValueEqual(network.contents().amount(COBBLE), stored, step + ": in the network");
    }

    /** The menu slot over the player's inventory slot {@code index}. */
    private static int slotOf(TerminalMenu menu, int index) {
        for (Slot slot : menu.slots) {
            if (slot.getContainerSlot() == index) {
                return slot.index;
            }
        }
        throw new IllegalStateException("No slot for inventory slot " + index);
    }

    /**
     * On a network owned by someone else, a stranger's menu is not valid and moves nothing: no take, no store, no
     * shift-click. The owner's clicks work.
     */
    private static void accessRefused(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = player(helper, "terminal_owner");
        ServerPlayer stranger = player(helper, "terminal_stranger");
        TerminalBlockEntity terminal = network(helper);
        BlockPos controller = NetworkGameTests.at(1);
        BlockState state = helper.getBlockState(controller);
        state.getBlock().setPlacedBy(level, helper.absolutePos(controller), state, owner,
                new ItemStack(ControllerContent.CONTROLLER.item().get()));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(net(helper, terminal).owner().isOwner(owner.getUUID()), "owner"))
                .thenExecute(() -> {
                    NetworkState network = net(helper, terminal);
                    NetworkGameTests.insert(network, COBBLE, 100);
                    int cobble = network.contents().keyId(COBBLE);
                    TerminalMenu menu = TerminalMenu.server(1, stranger.getInventory(), terminal, update -> {
                    });
                    helper.assertFalse(terminal.canAccess(stranger), "stranger");
                    helper.assertFalse(menu.stillValid(stranger), "the stranger's menu closes");
                    helper.assertFalse(click(menu, stranger, TerminalAction.Kind.TAKE_STACK, cobble), "take");
                    helper.assertTrue(menu.getCarried().isEmpty(), "nothing taken");
                    menu.setCarried(new ItemStack(Items.STONE, 5));
                    helper.assertFalse(click(menu, stranger, TerminalAction.Kind.DEPOSIT_ALL, -1), "store");
                    helper.assertValueEqual(menu.getCarried().getCount(), 5, "nothing stored");
                    stranger.getInventory().setItem(0, new ItemStack(Items.STONE, 10));
                    menu.quickMoveStack(stranger, slotOf(menu, 0));
                    helper.assertValueEqual(stranger.getInventory().getItem(0).getCount(), 10, "shift-click refused");
                    // The comfort actions (M6e area D) check access the same way.
                    stranger.getInventory().setItem(9, new ItemStack(Items.COBBLESTONE, 10));
                    stranger.getInventory().setItem(1, new ItemStack(Items.COBBLESTONE, 1));
                    helper.assertFalse(click(menu, stranger, TerminalAction.Kind.DEPOSIT_STORED, -1), "deposit key");
                    helper.assertFalse(click(menu, stranger, TerminalAction.Kind.RESTOCK_HOTBAR, -1), "restock key");
                    menu.receiveRecipe(stranger, List.of(List.of(new ItemStack(Items.COBBLESTONE))), true);
                    helper.assertValueEqual(stranger.getInventory().getItem(9).getCount(), 10, "nothing moved");
                    helper.assertValueEqual(stranger.getInventory().getItem(1).getCount(), 1, "no restock");
                    helper.assertValueEqual(network.contents().amount(COBBLE), 100L, "no recipe pulled");
                    stranger.getInventory().setItem(9, ItemStack.EMPTY);
                    stranger.getInventory().setItem(1, ItemStack.EMPTY);
                    helper.assertValueEqual(network.contents().amount(COBBLE), 100L, "cobblestone untouched");
                    helper.assertValueEqual(network.contents().amount(STONE), 0L, "no stone stored");
                    TerminalMenu own = TerminalMenu.server(2, owner.getInventory(), terminal, update -> {
                    });
                    helper.assertTrue(own.stillValid(owner), "the owner's menu");
                    helper.assertValueEqual(terminal.openMenus(), 0, "menus the client never opened watch nothing");
                    click(own, owner, TerminalAction.Kind.REFRESH, -1);
                    helper.assertValueEqual(terminal.openMenus(), 1, "the owner's refresh starts watching");
                    helper.assertTrue(click(own, owner, TerminalAction.Kind.TAKE_STACK, cobble), "the owner takes");
                    // removed() puts the cursor back through the player's connection, which a test player lacks.
                    own.setCarried(ItemStack.EMPTY);
                    menu.setCarried(ItemStack.EMPTY);
                    own.removed(owner);
                    menu.removed(stranger);
                    helper.assertValueEqual(terminal.openMenus(), 0, "closed menus stop watching");
                }).thenSucceed();
    }

    /**
     * A dump of 300 keys with about 25 KiB of lore each (some 7 MiB) is split by encoded size: every batch encodes to
     * at most {@link SyncedListUpdate#MAX_BYTES}, and the client ends up with every key, lore intact.
     */
    private static void dumpBatchedByBytes(GameTestHelper helper) {
        TerminalBlockEntity terminal = network(helper);
        ServerPlayer player = player(helper, "terminal_heavy");
        List<SyncedListUpdate<StackKey>> sent = new ArrayList<>();
        int keys = 300;
        helper.startSequence().thenWaitUntil(() -> net(helper, terminal)).thenExecute(() -> {
            String filler = "x".repeat(400);
            for (int i = 0; i < keys; i++) {
                List<Component> lines = new ArrayList<>();
                for (int line = 0; line < 64; line++) {
                    lines.add(Component.literal(i + " " + line + " " + filler));
                }
                ItemStack paper = new ItemStack(Items.PAPER);
                paper.set(DataComponents.LORE, new ItemLore(lines));
                drive(helper, 2).provider.setDirect(ItemKey.of(paper), 1);
            }
            TerminalMenu menu = TerminalMenu.server(1, player.getInventory(), terminal, sent::add);
            click(menu, player, TerminalAction.Kind.REFRESH, -1);
            menu.broadcastChanges();
            helper.assertTrue(sent.size() > 1, "the dump was split, " + sent.size() + " batch(es)");
            for (SyncedListUpdate<StackKey> update : sent) {
                RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                        helper.getLevel().registryAccess());
                TerminalContent.LIST.codec().encode(buf, update);
                helper.assertTrue(buf.writerIndex() <= SyncedListUpdate.MAX_BYTES,
                        "a batch of " + update.countCount() + " keys is " + buf.writerIndex() + " bytes");
            }
            ClientSyncedList<StackKey> client = replay(helper, sent);
            helper.assertValueEqual(client.size(), keys, "keys on the client");
            StackKey any = client.entry(client.ids().iterator().nextInt());
            helper.assertTrue(any instanceof ItemKey item && item.toStack(1).get(DataComponents.LORE) != null,
                    "lore arrived");
        }).thenSucceed();
    }

    /**
     * With a real drive and item cell: a shift-clicked stack lands in the cell and reaches the open GUI as a delta;
     * taking it back empties the cell.
     */
    private static void withDrive(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        DriveBlockEntity drive = CellGameTests.drive(helper, NetworkGameTests.at(2));
        drive.insertCell(CellGameTests.cell(CellContent.ITEM_TYPE.id(), 1));
        helper.setBlock(NetworkGameTests.at(3), behind());
        TerminalBlockEntity terminal = helper.getBlockEntity(NetworkGameTests.at(3), TerminalBlockEntity.class);
        ServerPlayer player = player(helper, "terminal_drive_user");
        List<SyncedListUpdate<StackKey>> sent = new ArrayList<>();
        TerminalMenu[] menu = new TerminalMenu[1];
        helper.startSequence().thenWaitUntil(() -> {
            CellGameTests.mounted(helper, drive, 0);
            net(helper, terminal);
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, controller, 10_000);
            menu[0] = TerminalMenu.server(1, player.getInventory(), terminal, sent::add);
            click(menu[0], player, TerminalAction.Kind.REFRESH, -1);
            menu[0].broadcastChanges();
            player.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 64));
            menu[0].quickMoveStack(player, slotOf(menu[0], 0));
            helper.assertTrue(player.getInventory().getItem(0).isEmpty(), "the stack went in");
            helper.assertValueEqual(CellGameTests.mounted(helper, drive, 0).amount(COBBLE), 64L, "in the cell");
        }).thenIdle(6).thenExecute(() -> {
            menu[0].broadcastChanges();
            ClientSyncedList<StackKey> client = replay(helper, sent);
            int id = net(helper, terminal).contents().keyId(COBBLE);
            helper.assertValueEqual(client.count(id), 64L, "the GUI saw the cell's cobblestone");
            helper.assertTrue(click(menu[0], player, TerminalAction.Kind.TAKE_STACK, id), "take it back");
            helper.assertValueEqual(menu[0].getCarried().getCount(), 64, "on the cursor");
            helper.assertValueEqual(CellGameTests.mounted(helper, drive, 0).amount(COBBLE), 0L, "the cell is empty");
        }).thenSucceed();
    }

    /**
     * The terminal block is a full cube that links on every side but its screen: a cable at its side joins it to the
     * controller's network, a device in front of the screen stays apart.
     */
    private static void linksExceptFront(GameTestHelper helper) {
        NetworkGameTests.controller(helper, 1);
        NetworkGameTests.cables(helper, 2, 2, CableContent.STORAGE_CABLE_TIER_1.type().get());
        // Facing north: the cable at x 2 touches its west side, the device at z 0 its screen.
        helper.setBlock(NetworkGameTests.at(3),
                TerminalContent.TERMINAL.get().defaultBlockState().setValue(TerminalBlock.FACING, Direction.NORTH));
        TerminalBlockEntity terminal = helper.getBlockEntity(NetworkGameTests.at(3), TerminalBlockEntity.class);
        TestStorage.TestDevice front = NetworkGameTests.device(helper, NetworkGameTests.at(3).north());
        BlockPos abs = helper.absolutePos(NetworkGameTests.at(3));
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockState state = TerminalContent.TERMINAL.get().defaultBlockState().setValue(TerminalBlock.FACING,
                    facing);
            helper.assertTrue(state.getShape(helper.getLevel(), abs) == Shapes.block(),
                    "not a full block facing " + facing);
        }
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(
                        terminal.network() == NetworkGameTests.state(helper, NetworkGameTests.at(1)),
                        "not linked through its side"))
                .thenIdle(10)
                .thenExecute(
                        () -> helper.assertTrue(front.network() != terminal.network(), "linked through its screen"))
                .thenSucceed();
    }

    /** The terminal crafts from area 5's parts, as the vanilla Crafter looks recipes up. */
    private static void recipe(GameTestHelper helper) {
        ItemStack plate = new ItemStack(MaterialContent.CASING_PLATE.get());
        ItemStack glass = new ItemStack(MaterialContent.DISPLAY_GLASS.get());
        ItemStack wire = new ItemStack(MaterialContent.INSULATED_COPPER_WIRE.get());
        ItemStack io = new ItemStack(MaterialContent.BASIC_IO_PROCESSOR.get());
        ItemStack logic = new ItemStack(MaterialContent.BASIC_LOGIC_PROCESSOR.get());
        crafts(helper, List.of(plate, glass, plate, wire, io, wire, plate, logic, plate),
                TerminalContent.TERMINAL.item().get());
        helper.succeed();
    }

    static void crafts(GameTestHelper helper, List<ItemStack> grid, Item output) {
        List<ItemStack> copies = new ArrayList<>();
        for (ItemStack stack : grid) {
            copies.add(stack.copy());
        }
        CraftingInput input = CraftingInput.of(3, 3, copies);
        Optional<RecipeHolder<CraftingRecipe>> recipe = CrafterBlock.getPotentialResults(helper.getLevel(), input);
        helper.assertTrue(recipe.isPresent(), "a crafting recipe makes " + output);
        helper.assertTrue(recipe.orElseThrow().value().assemble(input).is(output), "crafts " + output);
    }
}
