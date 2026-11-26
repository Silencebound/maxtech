package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.DynamicOps;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.cell.CellFilter;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.crafting.job.CraftLimits;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.iface.InterfaceContent;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicContent;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.NetworkCommand;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.snapshot.NetworkSnapshot;
import dev.silencebound.maxtech.storage.network.snapshot.NetworkSnapshots;
import dev.silencebound.maxtech.storage.network.snapshot.SnapshotDevices;
import dev.silencebound.maxtech.storage.port.PortBlock;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortMode;
import dev.silencebound.maxtech.storage.port.PortPart;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
// PLATFORM-LEAK: tests put items into the network through NeoForge transfer transactions.
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Network snapshots (M7C item 27): a snapshot puts back the settings of ports (block and part), drives, the logic
 * controller and the controller and leaves stored items alone; devices removed or replaced by another kind are skipped
 * and named; the saved data round-trips and its caps refuse; only game masters reach the command. A controller at x 1,
 * a cable along x 2 to 5, a port part on the cable's north face at x 2, and south of the cable a port at x 3, a drive
 * at x 4 and a logic controller at x 5.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class NetworkSnapshotGameTests {
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final String[] NAMES = {"network_snapshot_restore", "network_snapshot_skipped",
            "network_snapshot_saved", "network_snapshot_permission"};

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register(NAMES[0], NetworkSnapshotGameTests::restore);
        register(NAMES[1], NetworkSnapshotGameTests::skipped);
        register(NAMES[2], NetworkSnapshotGameTests::saved);
        register(NAMES[3], NetworkSnapshotGameTests::permission);
    }

    private NetworkSnapshotGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("network_snapshot"), StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 200);
        }
    }

    // ---- set-up

    private record Layout(ControllerBlockEntity core, PortPart part, PortBlockEntity port, DriveBlockEntity drive,
            LogicControllerBlockEntity logic) {
    }

    private static Layout layout(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 10_000_000);
        NetworkGameTests.cables(helper, 2, 5, CableContent.STORAGE_CABLE_TIER_1.type().get());
        helper.assertTrue(PartGameTests.usePart(helper, helper.makeMockPlayer(GameType.SURVIVAL), 2, Direction.NORTH,
                new ItemStack(PortContent.PORT_PART_ITEM.get())).consumesAction(), "port part placed");
        PortPart part = (PortPart) PartGameTests.part(helper, PartGameTests.parts(helper, 2), Direction.NORTH);
        BlockPos portPos = NetworkGameTests.at(3).south();
        helper.setBlock(portPos, PortContent.PORT.get().defaultBlockState().setValue(PortBlock.FACING, Direction.SOUTH)
                .setValue(PortBlock.MODE, PortMode.IMPORT));
        DriveBlockEntity drive = CellGameTests.drive(helper, NetworkGameTests.at(4).south());
        drive.insertCell(CellPartsGameTests.cell(CellContent.SMALL_STORAGE_CELL.get(),
                List.of(CellContent.MEMORY_MODULE_1.get()), List.of(), CellFilter.EMPTY));
        helper.setBlock(NetworkGameTests.at(5).south(), LogicContent.CONTROLLER.get());
        return new Layout(NetworkGameTests.controllerEntity(helper, core), part,
                helper.getBlockEntity(portPos, PortBlockEntity.class), drive,
                LogicControllerGameTests.entity(helper, NetworkGameTests.at(5).south()));
    }

    /** The network every device of {@code layout} is in; fails (to be retried) until they all joined it. */
    private static NetworkState joined(GameTestHelper helper, Layout layout) {
        NetworkState network = layout.core().network();
        if (network == null) {
            throw helper.assertionException(Component.literal("the controller is in no network"));
        }
        helper.assertTrue(
                layout.part().deviceLink().network() == network && layout.port().network() == network
                        && layout.drive().network() == network && layout.logic().network() == network,
                "devices joined");
        CellGameTests.mounted(helper, layout.drive(), 0);
        return network;
    }

    /** Settings an owner would set up. */
    private static void configure(Layout layout) {
        layout.port().setMode(PortMode.EXPORT);
        layout.port().filter().setItem(0, new ItemStack(Items.COBBLESTONE));
        layout.port().setLabel(DeviceLabel.of("smelt", DyeColor.ORANGE));
        layout.part().setMode(PortMode.EXPORT);
        layout.part().setLabel(DeviceLabel.of("feed", null));
        layout.drive().setPriority(7);
        layout.drive().setLabel(DeviceLabel.of("bank", null));
        layout.logic().worker().setGraph(0, "Full", LogicControllerGameTests.crossesNotify(COBBLE, 10, "full"));
        layout.core().setCraftLimits(new CraftLimits(64, 1));
    }

    /** What a griefer would change. */
    private static void grief(Layout layout) {
        layout.port().setMode(PortMode.IMPORT);
        layout.port().filter().setItem(0, ItemStack.EMPTY);
        layout.port().setLabel(DeviceLabel.NONE);
        layout.part().setMode(PortMode.IMPORT);
        layout.part().setLabel(DeviceLabel.of("griefed", DyeColor.RED));
        layout.drive().setPriority(0);
        layout.drive().setLabel(DeviceLabel.NONE);
        layout.logic().worker().removeGraph(0);
        layout.core().setCraftLimits(CraftLimits.DEFAULT);
    }

    private static ServerPlayer op(ServerLevel level, String name) {
        return new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), name),
                ClientInformation.createDefault()) {
            @Override
            public PermissionSet permissions() {
                return LevelBasedPermissionSet.GAMEMASTER;
            }
        };
    }

    private static CommandDispatcher<CommandSourceStack> dispatcher() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(Commands.literal("maxtech").then(NetworkCommand.build()));
        return dispatcher;
    }

    private static int run(GameTestHelper helper, String command) {
        CommandSourceStack source = op(helper.getLevel(), "snapshot_admin").createCommandSourceStack()
                .withSuppressedOutput();
        try {
            return dispatcher().execute(command, source);
        } catch (CommandSyntaxException e) {
            throw helper.assertionException(Component.literal(command + ": " + e.getMessage()));
        }
    }

    private static String coords(GameTestHelper helper, BlockPos relative) {
        BlockPos pos = helper.absolutePos(relative);
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /** Removes the snapshots a test made, so tests in other batches start from none. */
    private static void forget(GameTestHelper helper, String... names) {
        NetworkSnapshots snapshots = NetworkSnapshots.get(helper.getLevel().getServer());
        for (String name : names) {
            snapshots.remove(name);
        }
    }

    private static void insert(NetworkState network, long amount) {
        try (Transaction transaction = Transaction.openRoot()) {
            network.insert(COBBLE, amount, transaction);
            transaction.commit();
        }
    }

    // ---- tests

    /**
     * Saved through the command, griefed, restored through the command: every setting is back, and cobblestone stored
     * after the save is still stored (a snapshot holds no items).
     */
    private static void restore(GameTestHelper helper) {
        Layout layout = layout(helper);
        String name = "snap_restore_" + helper.getLevel().getGameTime();
        int[] saved = new int[1];
        helper.startSequence().thenWaitUntil(() -> joined(helper, layout)).thenExecute(() -> {
            configure(layout);
            saved[0] = run(helper,
                    "maxtech network snapshot save " + coords(helper, NetworkGameTests.at(2)) + " " + name);
            helper.assertTrue(saved[0] >= 5, "the controller, the port part, the port, the drive and the logic "
                    + "controller were saved, got " + saved[0]);
            helper.assertValueEqual(
                    run(helper, "maxtech network snapshot list " + coords(helper, NetworkGameTests.at(4))), 1,
                    "listed for the network");
            NetworkState network = joined(helper, layout);
            insert(network, 100);
            grief(layout);
            int restored = run(helper, "maxtech network snapshot restore " + name);
            helper.assertValueEqual(restored, saved[0], "every saved device restored");
            helper.assertValueEqual(layout.port().mode(), PortMode.EXPORT, "port mode back");
            helper.assertTrue(layout.port().filter().getItem(0).is(Items.COBBLESTONE), "port filter back");
            helper.assertValueEqual(layout.port().label(), DeviceLabel.of("smelt", DyeColor.ORANGE), "port label back");
            helper.assertValueEqual(layout.part().mode(), PortMode.EXPORT, "part mode back");
            helper.assertValueEqual(layout.part().label(), DeviceLabel.of("feed", null), "part label back");
            helper.assertValueEqual(layout.drive().priority(), 7, "drive priority back");
            helper.assertValueEqual(layout.drive().label(), DeviceLabel.of("bank", null), "drive label back");
            helper.assertValueEqual(layout.logic().worker().size(), 1, "logic rule back");
            helper.assertValueEqual(layout.logic().worker().name(0), "Full", "logic rule name back");
            helper.assertValueEqual(layout.core().craftLimits(), new CraftLimits(64, 1), "craft limits back");
            helper.assertValueEqual(network.nativeAmount(COBBLE), 100L, "stored items untouched");
            helper.assertValueEqual(run(helper, "maxtech network snapshot delete " + name), 1, "deleted");
        }).thenExecute(() -> forget(helper, name)).thenSucceed();
    }

    /**
     * The port removed and the drive replaced by an interface after the save: both are skipped with their reasons, the
     * other devices are restored.
     */
    private static void skipped(GameTestHelper helper) {
        Layout layout = layout(helper);
        String name = "snap_skipped_" + helper.getLevel().getGameTime();
        helper.startSequence().thenWaitUntil(() -> joined(helper, layout)).thenExecute(() -> {
            configure(layout);
            int saved = run(helper,
                    "maxtech network snapshot save " + coords(helper, NetworkGameTests.at(2)) + " " + name);
            grief(layout);
            helper.setBlock(NetworkGameTests.at(3).south(), Blocks.AIR);
            helper.setBlock(NetworkGameTests.at(4).south(), InterfaceContent.INTERFACE.get());
            NetworkSnapshot snapshot = NetworkSnapshots.get(helper.getLevel().getServer()).get(name).orElseThrow();
            SnapshotDevices.Result result = SnapshotDevices.restore(helper.getLevel(), snapshot);
            helper.assertValueEqual(result.restored(), saved - 2, "all but two restored");
            helper.assertValueEqual(result.skipped().size(), 2, "two skipped");
            for (SnapshotDevices.Skipped skip : result.skipped()) {
                if (skip.pos().equals(helper.absolutePos(NetworkGameTests.at(3).south()))) {
                    helper.assertValueEqual(skip.why(), SnapshotDevices.Skip.GONE, "the removed port");
                } else {
                    helper.assertValueEqual(skip.pos(), helper.absolutePos(NetworkGameTests.at(4).south()),
                            "the replaced drive");
                    helper.assertValueEqual(skip.why(), SnapshotDevices.Skip.CHANGED, "the replaced drive");
                }
            }
            helper.assertValueEqual(layout.part().mode(), PortMode.EXPORT, "the part restored");
            helper.assertValueEqual(layout.logic().worker().size(), 1, "the rule restored");
        }).thenExecute(() -> forget(helper, name)).thenSucceed();
    }

    /**
     * A captured snapshot survives the saved data's codec; a taken name, a full network and a full server refuse; names
     * outside a-z, 0-9, _ and - are refused.
     */
    private static void saved(GameTestHelper helper) {
        Layout layout = layout(helper);
        helper.startSequence().thenWaitUntil(() -> joined(helper, layout)).thenExecute(() -> {
            configure(layout);
            NetworkState network = joined(helper, layout);
            List<NetworkSnapshot.Device> devices = SnapshotDevices.capture(network);
            BlockPos anchor = layout.core().getBlockPos();
            NetworkSnapshot first = new NetworkSnapshot("a", Level.OVERWORLD, anchor, 5, "test", devices);
            NetworkSnapshots data = new NetworkSnapshots();
            helper.assertValueEqual(data.add(first, 1, 2), NetworkSnapshots.Added.ADDED, "added");
            DynamicOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
            Tag tag = NetworkSnapshots.CODEC.encodeStart(ops, data).getOrThrow();
            NetworkSnapshots back = NetworkSnapshots.CODEC.parse(ops, tag).getOrThrow();
            helper.assertTrue(back.get("a").isPresent(), "read back");
            helper.assertValueEqual(NetworkSnapshots.CODEC.encodeStart(ops, back).getOrThrow(), tag, "round trip");
            helper.assertValueEqual(back.get("a").orElseThrow().devices().size(), devices.size(), "all devices");
            helper.assertTrue(devices.size() >= 5, "devices captured");

            NetworkSnapshot sameName = new NetworkSnapshot("a", Level.OVERWORLD, anchor.above(), 6, "test", devices);
            helper.assertValueEqual(data.add(sameName, 1, 2), NetworkSnapshots.Added.NAME_TAKEN, "name taken");
            NetworkSnapshot sameNetwork = new NetworkSnapshot("b", Level.OVERWORLD, anchor, 6, "test", devices);
            helper.assertValueEqual(data.add(sameNetwork, 1, 2), NetworkSnapshots.Added.NETWORK_FULL, "network full");
            NetworkSnapshot other = new NetworkSnapshot("c", Level.OVERWORLD, anchor.above(), 6, "test", devices);
            helper.assertValueEqual(data.add(other, 1, 1), NetworkSnapshots.Added.SERVER_FULL, "server full");
            helper.assertValueEqual(data.add(other, 1, 2), NetworkSnapshots.Added.ADDED, "another network fits");

            helper.assertTrue(NetworkSnapshots.validName("before-raid_2"), "plain name");
            helper.assertTrue(!NetworkSnapshots.validName(""), "empty");
            helper.assertTrue(!NetworkSnapshots.validName("Upper"), "upper case");
            helper.assertTrue(!NetworkSnapshots.validName("a.b"), "dot");
            helper.assertTrue(!NetworkSnapshots.validName("x".repeat(NetworkSnapshots.MAX_NAME + 1)), "too long");
        }).thenSucceed();
    }

    /** A player without game master rights does not reach {@code network snapshot}; a game master does. */
    private static void permission(GameTestHelper helper) {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack player = NetworkGameTests.player(helper.getLevel(), "snapshot_player")
                .createCommandSourceStack().withSuppressedOutput();
        boolean refused = false;
        try {
            dispatcher.execute("maxtech network snapshot list", player);
        } catch (CommandSyntaxException e) {
            refused = true;
        }
        helper.assertTrue(refused, "a plain player is refused");
        helper.assertTrue(run(helper, "maxtech network snapshot list") >= 0, "a game master runs it");
        helper.succeed();
    }
}
