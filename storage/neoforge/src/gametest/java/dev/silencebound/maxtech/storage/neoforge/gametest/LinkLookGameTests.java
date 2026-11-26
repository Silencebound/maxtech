package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.cable.StorageCableBlock;
import dev.silencebound.maxtech.lib.network.Network;
import dev.silencebound.maxtech.lib.network.NetworkManager;
import dev.silencebound.maxtech.storage.network.LinkLook;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageNetworkLogic;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.PartLook;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The accent line on a device's front shows its network state (MTX, owner 2026-10-17; {@link LinkLook}): online with a
 * channel on a powered network with a controller, no channel past the channel limit, offline unpowered or without a
 * controller; a freed channel turns no channel into online. Checked on a terminal block (its {@link LinkLook#PROPERTY})
 * and a terminal part (its synced look in the cable's render key).
 */
// Controllers are compared by identity.
@SuppressWarnings("ReferenceEquality")
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class LinkLookGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final String NO_CHANNEL = "channels_link_no_channel";
    private static final String OFFLINE = "channels_link_offline";
    private static final String QUIET = "channels_link_quiet";
    private static final String STANDBY = "channels_link_standby";
    private static final String PAUSE = "channels_link_pause";
    private static final String RELOAD = "channels_link_reload";
    /** The terminal block beside the cable at x 5, facing north, so it links south to the cable. */
    private static final BlockPos TERMINAL = new BlockPos(5, 1, 0);
    /** The cable that carries the terminal part on its south face. */
    private static final BlockPos PART_CABLE = NetworkGameTests.at(6);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        StorageTests.function(NO_CHANNEL, StorageTestEnvironment.clearing(LinkLookGameTests::noChannel));
        StorageTests.function(OFFLINE, StorageTestEnvironment.clearing(LinkLookGameTests::offline));
        StorageTests.function(QUIET, StorageTestEnvironment.clearing(LinkLookGameTests::quiet));
        StorageTests.function(STANDBY, StorageTestEnvironment.clearing(LinkLookGameTests::standby));
        StorageTests.function(PAUSE, StorageTestEnvironment.clearing(LinkLookGameTests::pause));
        StorageTests.function(RELOAD, StorageTestEnvironment.clearing(LinkLookGameTests::reload));
    }

    private LinkLookGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechStorage.id("link_looks"),
                StorageTestEnvironment.plain());
        StorageTests.test(event, plain, NO_CHANNEL, STRUCTURE, 300);
        StorageTests.test(event, plain, OFFLINE, STRUCTURE, 300);
        for (String name : List.of(QUIET, STANDBY, PAUSE, RELOAD)) {
            StorageTests.test(event, plain, name, STRUCTURE, 300);
        }
    }

    /**
     * Eight devices fill a tier-1 cable (8 channels) on a powered network and show online. A terminal block and a
     * terminal part added later find no channel and show it; two devices broken free two channels, and both show
     * online.
     */
    private static void noChannel(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 1_000_000);
        NetworkGameTests.cables(helper, 2, 6, CableContent.STORAGE_CABLE_TIER_1.type().get());
        List<BlockPos> spots = new ArrayList<>();
        for (int x = 3; x <= 4; x++) {
            spots.add(new BlockPos(x, 1, 0));
            spots.add(new BlockPos(x, 1, 2));
            spots.add(new BlockPos(x, 2, 1));
            spots.add(new BlockPos(x, 0, 1));
        }
        List<TestStorage.TestDevice> devices = new ArrayList<>();
        for (BlockPos pos : spots) {
            devices.add(NetworkGameTests.device(helper, pos));
        }
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertValueEqual(8, NetworkGameTests.state(helper, controller).channelsHeld(), "channels held");
            for (TestStorage.TestDevice device : devices) {
                helper.assertValueEqual(LinkLook.ONLINE, device.deviceLink().linkLook(), "a device with a channel");
            }
        }).thenExecute(() -> {
            helper.setBlock(TERMINAL, TerminalContent.TERMINAL.get());
            helper.assertTrue(
                    parts(helper).place(Direction.SOUTH, TerminalContent.TERMINAL_PART, null, ItemStack.EMPTY),
                    "part not placed");
        }).thenWaitUntil(() -> assertLooks(helper, LinkLook.NO_CHANNEL)).thenExecute(() -> {
            helper.destroyBlock(spots.get(0));
            helper.destroyBlock(spots.get(1));
        }).thenWaitUntil(() -> assertLooks(helper, LinkLook.ONLINE)).thenSucceed();
    }

    /**
     * A terminal block and part on a controller's network: offline while it has no energy, online once powered, offline
     * again when the energy runs out, online when it comes back, and offline once the controller is gone.
     */
    private static void offline(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.cables(helper, 2, 6, CableContent.STORAGE_CABLE_TIER_1.type().get());
        helper.setBlock(TERMINAL, TerminalContent.TERMINAL.get());
        helper.assertTrue(parts(helper).place(Direction.SOUTH, TerminalContent.TERMINAL_PART, null, ItemStack.EMPTY),
                "part not placed");
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(NetworkGameTests.state(helper, controller).channelsHeld() == 2, "channels held");
            assertLooks(helper, LinkLook.OFFLINE);
        }).thenExecute(() -> NetworkGameTests.power(helper, controller, 1_000_000))
                .thenWaitUntil(() -> assertLooks(helper, LinkLook.ONLINE))
                .thenExecute(() -> NetworkGameTests.controllerEntity(helper, controller).buffer().load(0))
                .thenWaitUntil(() -> assertLooks(helper, LinkLook.OFFLINE))
                .thenExecute(() -> NetworkGameTests.power(helper, controller, 1_000_000))
                .thenWaitUntil(() -> assertLooks(helper, LinkLook.ONLINE))
                .thenExecute(() -> helper.destroyBlock(controller))
                .thenWaitUntil(() -> assertLooks(helper, LinkLook.OFFLINE)).thenSucceed();
    }

    /**
     * A powered network with a terminal block and part online: forty quiet ticks show nothing again and push nothing,
     * and five ticks without energy (shorter than the dwell) push nothing either.
     */
    private static void quiet(GameTestHelper helper) {
        BlockPos controller = poweredWithTerminal(helper);
        long[] before = new long[2];
        helper.startSequence().thenWaitUntil(() -> assertLooks(helper, LinkLook.ONLINE)).thenIdle(5).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            before[0] = network.linkShowings();
            before[1] = network.linkPushes();
        }).thenIdle(40).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertValueEqual(before[0], network.linkShowings(), "showings on quiet ticks");
            helper.assertValueEqual(before[1], network.linkPushes(), "pushes on quiet ticks");
            NetworkGameTests.controllerEntity(helper, controller).buffer().load(0);
        }).thenIdle(5).thenExecute(() -> NetworkGameTests.power(helper, controller, 1_000_000))
                .thenExecuteFor(30, () -> assertLooks(helper, LinkLook.ONLINE))
                .thenExecute(() -> helper.assertValueEqual(before[1],
                        NetworkGameTests.state(helper, controller).linkPushes(), "pushes after a short loss of energy"))
                .thenSucceed();
    }

    /**
     * Two powered controllers at the ends of the cable, the later one on standby: the terminal block and part stay
     * online on every tick while the first controller is broken and the standby takes charge.
     */
    private static void standby(GameTestHelper helper) {
        BlockPos first = poweredWithTerminal(helper);
        BlockPos second = NetworkGameTests.controller(helper, 7);
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(NetworkGameTests.controllerEntity(helper, second).isStandby(), "second not on standby");
            NetworkGameTests.power(helper, second, 1_000_000);
            assertLooks(helper, LinkLook.ONLINE);
        }).thenExecute(() -> helper.destroyBlock(first)).thenExecuteFor(40, () -> assertLooks(helper, LinkLook.ONLINE))
                .thenExecute(
                        () -> helper
                                .assertTrue(
                                        NetworkGameTests.state(helper, second).controllerDevice() == NetworkGameTests
                                                .controllerEntity(helper, second),
                                        "the second controller not in charge"))
                .thenSucceed();
    }

    /**
     * The network is paused for unloaded chunks (the graph's pause event; it then stops ticking): a pause of five ticks
     * pushes nothing; a longer one shows offline once it outlasts the dwell, not before; resuming shows online.
     */
    private static void pause(GameTestHelper helper) {
        BlockPos controller = poweredWithTerminal(helper);
        long[] pushes = new long[1];
        helper.startSequence().thenWaitUntil(() -> assertLooks(helper, LinkLook.ONLINE)).thenExecute(() -> {
            pushes[0] = NetworkGameTests.state(helper, controller).linkPushes();
            setPaused(helper, true);
        }).thenIdle(5).thenExecute(() -> setPaused(helper, false)).thenIdle(30).thenExecute(() -> {
            assertLooks(helper, LinkLook.ONLINE);
            helper.assertValueEqual(pushes[0], NetworkGameTests.state(helper, controller).linkPushes(),
                    "pushes after a short pause");
            setPaused(helper, true);
        }).thenIdle(10).thenExecute(() -> assertLooks(helper, LinkLook.ONLINE))
                .thenWaitUntil(() -> assertLooks(helper, LinkLook.OFFLINE)).thenExecute(() -> setPaused(helper, false))
                .thenWaitUntil(() -> assertLooks(helper, LinkLook.ONLINE)).thenSucceed();
    }

    /**
     * A terminal block saved online loads again while its network is paused, so no network shows it: it shows offline,
     * not the stale online look, until the network resumes and shows it online.
     */
    private static void reload(GameTestHelper helper) {
        poweredWithTerminal(helper);
        helper.startSequence().thenWaitUntil(() -> assertLooks(helper, LinkLook.ONLINE)).thenExecute(() -> {
            setPaused(helper, true);
            BlockPos abs = helper.absolutePos(TERMINAL);
            BlockEntity terminal = helper.getBlockEntity(TERMINAL, BlockEntity.class);
            CompoundTag tag = terminal.saveWithFullMetadata(helper.getLevel().registryAccess());
            terminal.onChunkUnloaded();
            helper.getLevel().getChunkAt(abs).removeBlockEntity(abs);
            BlockEntity loaded = BlockEntity.loadStatic(abs, helper.getLevel().getBlockState(abs), tag,
                    helper.getLevel().registryAccess());
            if (loaded == null) {
                throw helper.assertionException(Component.literal("the terminal did not load"));
            }
            helper.getLevel().getChunkAt(abs).addAndRegisterBlockEntity(loaded);
        }).thenIdle(3)
                .thenExecute(() -> helper.assertValueEqual(LinkLook.OFFLINE,
                        helper.getBlockState(TERMINAL).getValue(LinkLook.PROPERTY), "terminal block after loading"))
                .thenExecute(() -> setPaused(helper, false))
                .thenWaitUntil(() -> helper.assertValueEqual(LinkLook.ONLINE,
                        helper.getBlockState(TERMINAL).getValue(LinkLook.PROPERTY), "terminal block after resuming"))
                .thenSucceed();
    }

    /** A powered controller at x 1, tier-1 cables at x 2 to 6, the terminal block and part. Returns the controller. */
    private static BlockPos poweredWithTerminal(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 1_000_000);
        NetworkGameTests.cables(helper, 2, 6, CableContent.STORAGE_CABLE_TIER_1.type().get());
        helper.setBlock(TERMINAL, TerminalContent.TERMINAL.get());
        helper.assertTrue(parts(helper).place(Direction.SOUTH, TerminalContent.TERMINAL_PART, null, ItemStack.EMPTY),
                "part not placed");
        return controller;
    }

    /**
     * Sends the cable network the graph's pause or resume event, as unloading or loading a chunk does in pause mode.
     */
    private static void setPaused(GameTestHelper helper, boolean paused) {
        StorageNetworkLogic logic = StorageNetworks.logic(helper.getLevel(), StorageNetworks.ID);
        Network network = NetworkManager.get(helper.getLevel()).network(StorageNetworks.TYPE,
                helper.absolutePos(PART_CABLE));
        if (logic == null || network == null) {
            throw helper.assertionException(Component.literal("no storage network"));
        }
        logic.onPausedChanged(network, paused);
    }

    /** The terminal block's state and the terminal part's look, as the server has it and as clients get it. */
    private static void assertLooks(GameTestHelper helper, LinkLook look) {
        helper.assertValueEqual(look, helper.getBlockState(TERMINAL).getValue(LinkLook.PROPERTY), "terminal block");
        CableParts parts = parts(helper);
        CablePart part = parts.part(Direction.SOUTH);
        if (part == null) {
            throw helper.assertionException(Component.literal("no terminal part"));
        }
        helper.assertValueEqual(look, part.deviceLink().linkLook(), "terminal part");
        if (!(parts.renderKey() instanceof PartLook render) || render.faces().size() != 1) {
            throw helper.assertionException(Component.literal("no part look"));
        }
        helper.assertValueEqual(look, render.faces().get(0).link(), "terminal part's render key");
    }

    private static CableParts parts(GameTestHelper helper) {
        CableParts parts = StorageCableBlock.parts(helper.getLevel(), helper.absolutePos(PART_CABLE));
        if (parts == null) {
            throw helper.assertionException(Component.literal("no cable parts"));
        }
        return parts;
    }
}
