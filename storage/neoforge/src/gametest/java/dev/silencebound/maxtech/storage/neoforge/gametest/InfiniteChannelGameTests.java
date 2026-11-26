package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.info.InfoEntry;
import dev.silencebound.maxtech.lib.neoforge.compat.jade.BlockInfoLines;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.cable.SmartLoad;
import dev.silencebound.maxtech.storage.cable.StorageCableBlock;
import dev.silencebound.maxtech.storage.network.ChannelMode;
import dev.silencebound.maxtech.storage.network.Channels;
import dev.silencebound.maxtech.storage.network.Glance;
import dev.silencebound.maxtech.storage.network.LinkLook;
import dev.silencebound.maxtech.storage.network.NetworkInfo;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Infinite channels (B09 item 12, {@code network.channels.mode = infinite}): a tier-1 cable carries more devices than
 * its 8 channels and all of them are online; the cable shows its load but never full; device fronts and Jade's status
 * never say "no channel"; and switching the mode on a running server counts each network again once, not per tick.
 *
 * <p>
 * Every layout is a powered controller at x 1, tier-1 cables at x 2 to 7 and test devices beside the cables at x 3 to
 * 7, so the tier-1 cable at x 2 (the root link's segment) is the only limit.
 */
// Networks are compared by identity.
@SuppressWarnings("ReferenceEquality")
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class InfiniteChannelGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final String FULL_TIER_1 = "channels_infinite_full_tier1_online";
    private static final String LOOKS = "channels_infinite_link_look";
    private static final String SWITCH = "channels_infinite_mode_switch";
    private static final String REFUSED = "channels_infinite_refused_grey";
    /** Devices beside the cables: four more than a tier-1 cable carries. */
    private static final int DEVICES = 12;
    /** The terminal block beside the cable at x 5, facing north, so it links south to the cable. */
    private static final BlockPos TERMINAL = new BlockPos(5, 1, 0);
    /** The cable that carries the terminal part on its south face. */
    private static final BlockPos PART_CABLE = NetworkGameTests.at(6);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        StorageTests.function(FULL_TIER_1, StorageTestEnvironment.clearing(InfiniteChannelGameTests::fullTier1));
        StorageTests.function(LOOKS, StorageTestEnvironment.clearing(InfiniteChannelGameTests::looks));
        StorageTests.function(SWITCH, StorageTestEnvironment.clearing(InfiniteChannelGameTests::modeSwitch));
        StorageTests.function(REFUSED, StorageTestEnvironment.clearing(InfiniteChannelGameTests::refusedGrey));
    }

    private InfiniteChannelGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> infinite = event.registerEnvironment(
                MaxtechStorage.id("channels_infinite"),
                new StorageTestEnvironment(StorageTestEnvironment.CHANNEL_MODE, "infinite", false));
        StorageTests.test(event, infinite, FULL_TIER_1, STRUCTURE, 300);
        StorageTests.test(event, infinite, LOOKS, STRUCTURE, 300);
        // Its own batch: the test switches the mode itself, and the teardown puts back the value from before.
        StorageTests.test(event,
                event.registerEnvironment(MaxtechStorage.id("channels_infinite_switch"),
                        new StorageTestEnvironment(StorageTestEnvironment.CHANNEL_MODE, "infinite", false)),
                SWITCH, STRUCTURE, 400);
        // A team may hold one device: the device cap, not channels, turns the second away.
        StorageTests.test(event,
                event.registerEnvironment(MaxtechStorage.id(REFUSED), new StorageTestEnvironment(
                        StorageTestEnvironment.CHANNEL_MODE + ";caps.devicesPerTeam", "infinite;1", false)),
                REFUSED, STRUCTURE, 400);
    }

    /**
     * Without channel limits a team's device cap still applies: an owned network that may hold one device refuses the
     * second, and that device shows offline (grey) on its front and in its status, never no channel (red).
     */
    private static void refusedGrey(GameTestHelper helper) {
        BlockPos controller = layout(helper);
        BlockPos first = new BlockPos(3, 1, 0);
        BlockPos second = new BlockPos(3, 1, 2);
        TestStorage.TestDevice[] devices = new TestStorage.TestDevice[2];
        helper.startSequence().thenIdle(2).thenExecute(() -> {
            own(helper, controller);
            devices[0] = NetworkGameTests.device(helper, first);
            own(helper, first);
        }).thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertTrue(network.capTeam() != null, "the network is capped");
            helper.assertTrue(devices[0].network() == network, "the first device joined");
        }).thenExecute(() -> {
            devices[1] = NetworkGameTests.device(helper, second);
            own(helper, second);
        }).thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertTrue(network.channelsInfinite(), "the network counted with limits");
            helper.assertTrue(devices[1].deviceLink().refusedBy() == network, "the second device is not refused");
            helper.assertValueEqual(devices[1].deviceLink().linkLook(), LinkLook.OFFLINE, "the refused device's front");
        }).thenExecute(
                () -> helper.assertValueEqual(Glance.of(devices[1]), Glance.OFFLINE, "the refused device's status"))
                .thenSucceed();
    }

    private static void own(GameTestHelper helper, BlockPos pos) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "channels_infinite_owner");
        if (helper.getBlockEntity(pos, BlockEntity.class) instanceof StorageDeviceBlockEntity device) {
            device.setOwner(OwnerRecord.of(owner));
        }
    }

    /**
     * Twelve devices behind a tier-1 cable (8 channels) are all online and hold a channel each; the cable carries 12,
     * shows a load just under saturated, and its Jade bar reads "12/∞" in the accent or amber band with no pips.
     */
    private static void fullTier1(GameTestHelper helper) {
        BlockPos controller = layout(helper);
        List<TestStorage.TestDevice> devices = devices(helper, DEVICES);
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertTrue(network.channelsInfinite(), "the network counted with limits");
            helper.assertValueEqual(network.channelsHeld(), DEVICES, "channels held");
            helper.assertTrue(network.offlineDevices().isEmpty(), "devices offline for channels");
            for (TestStorage.TestDevice device : devices) {
                helper.assertTrue(device.network() == network, "a device is not in the network");
                helper.assertValueEqual(device.deviceLink().linkLook(), LinkLook.ONLINE, "a device's front");
            }
        }).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            long root = helper.absolutePos(NetworkGameTests.at(2)).asLong();
            helper.assertValueEqual(network.segmentChannels(root), DEVICES, "channels on the tier-1 cable");
            helper.assertValueEqual(network.segmentLoad(root), Channels.INFINITE_LOAD_MAX, "the cable's load");
            helper.assertTrue(SmartLoad.bucket(network.segmentLoad(root)) != SmartLoad.SATURATED,
                    "a smart cable would show saturated");
            List<InfoEntry> entries = BlockInfoLines.collect(helper.getLevel(),
                    helper.absolutePos(NetworkGameTests.at(2)), null, null, true);
            InfoEntry bar = null;
            for (InfoEntry entry : entries) {
                helper.assertFalse(entry.kind() == InfoEntry.Kind.PIPS, "pips without channel limits");
                if (entry.kind() == InfoEntry.Kind.PROGRESS && bar == null) {
                    bar = entry;
                }
            }
            if (bar == null) {
                throw helper.assertionException(Component.literal("no channel bar on the cable"));
            }
            helper.assertValueEqual(bar.max(), 100L, "the bar's scale");
            helper.assertTrue(bar.value() < bar.max(), "the bar is full");
            helper.assertTrue(bar.rgb() != BlockInfoSink.Tone.BAD.rgb(), "the bar is red");
            if (!(bar.text().getContents() instanceof TranslatableContents label)
                    || !label.getKey().equals(NetworkInfo.CHANNELS_KEY) || label.getArgs().length != 2
                    || !(label.getArgs()[1] instanceof Component limit)
                    || !(limit.getContents() instanceof TranslatableContents unlimited)
                    || !unlimited.getKey().equals(NetworkInfo.UNLIMITED_KEY)) {
                throw helper.assertionException(Component.literal("the bar's label is not used/unlimited"));
            }
        }).thenSucceed();
    }

    /**
     * Eight devices on the tier-1 cable, then a terminal block and a terminal part: without channel limits both get a
     * channel, their fronts show online and Jade's status says online, never no channel.
     */
    private static void looks(GameTestHelper helper) {
        BlockPos controller = layout(helper);
        devices(helper, 8);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(NetworkGameTests.state(helper, controller).channelsHeld(),
                        8, "eight devices fill the cable's channels"))
                .thenExecute(() -> {
                    helper.setBlock(TERMINAL, TerminalContent.TERMINAL.get());
                    helper.assertTrue(
                            parts(helper).place(Direction.SOUTH, TerminalContent.TERMINAL_PART, null, ItemStack.EMPTY),
                            "part not placed");
                }).thenWaitUntil(() -> {
                    helper.assertValueEqual(NetworkGameTests.state(helper, controller).channelsHeld(), 10,
                            "the terminals hold channels too");
                    helper.assertValueEqual(helper.getBlockState(TERMINAL).getValue(LinkLook.PROPERTY), LinkLook.ONLINE,
                            "terminal block");
                    CablePart part = parts(helper).part(Direction.SOUTH);
                    if (part == null) {
                        throw helper.assertionException(Component.literal("no terminal part"));
                    }
                    helper.assertValueEqual(part.deviceLink().linkLook(), LinkLook.ONLINE, "terminal part");
                }).thenExecute(() -> {
                    String status = statusKey(helper, TERMINAL);
                    helper.assertValueEqual(status, Glance.ONLINE.key(), "the terminal's Jade status");
                }).thenSucceed();
    }

    /**
     * Twelve devices online without limits; switching to limited counts the network again once: the four newest go
     * offline with no channel, and the count stays put for a second after. Switching back counts once more and brings
     * them online.
     */
    private static void modeSwitch(GameTestHelper helper) {
        BlockPos controller = layout(helper);
        List<TestStorage.TestDevice> devices = devices(helper, DEVICES);
        int[] counts = new int[1];
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertValueEqual(network.channelsHeld(), DEVICES, "channels held without limits");
        }).thenIdle(5).thenExecute(() -> {
            counts[0] = NetworkGameTests.state(helper, controller).channelCounts();
            StorageTestEnvironment.switchChannelMode(ChannelMode.LIMITED);
        }).thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertFalse(network.channelsInfinite(), "still counted without limits");
            helper.assertValueEqual(network.channelsHeld(), 8, "channels held with limits");
            helper.assertValueEqual(network.offlineDevices().size(), DEVICES - 8, "devices offline with limits");
        }).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertValueEqual(network.channelCounts(), counts[0] + 1, "full counts after the switch");
            long newestOnline = Long.MIN_VALUE;
            long oldestOffline = Long.MAX_VALUE;
            for (TestStorage.TestDevice device : devices) {
                long seq = device.deviceLink().joinSeq();
                if (device.network() == network) {
                    newestOnline = Math.max(newestOnline, seq);
                } else {
                    oldestOffline = Math.min(oldestOffline, seq);
                    helper.assertValueEqual(device.deviceLink().linkLook(), LinkLook.NO_CHANNEL,
                            "an offline device's front with limits");
                }
            }
            helper.assertTrue(newestOnline < oldestOffline, "an older device went offline");
            counts[0] = network.channelCounts();
        }).thenIdle(20).thenExecute(() -> {
            helper.assertValueEqual(NetworkGameTests.state(helper, controller).channelCounts(), counts[0],
                    "full counts while nothing changed");
            StorageTestEnvironment.switchChannelMode(ChannelMode.INFINITE);
        }).thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertTrue(network.channelsInfinite(), "counted with limits after switching back");
            helper.assertValueEqual(network.channelsHeld(), DEVICES, "channels held after switching back");
            for (TestStorage.TestDevice device : devices) {
                helper.assertValueEqual(device.deviceLink().linkLook(), LinkLook.ONLINE, "a device's front");
            }
        }).thenExecute(() -> helper.assertValueEqual(NetworkGameTests.state(helper, controller).channelCounts(),
                counts[0] + 1, "full counts after switching back")).thenSucceed();
    }

    /** The powered controller at x 1 and tier-1 cables at x 2 to 7. Returns the controller. */
    private static BlockPos layout(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 1_000_000);
        NetworkGameTests.cables(helper, 2, 7, CableContent.STORAGE_CABLE_TIER_1.type().get());
        return controller;
    }

    /**
     * {@code count} test devices beside the cables at x 3, 4, 5 and 7, placed at once; never at the terminal's spot or
     * beside the cable that carries the terminal part.
     */
    private static List<TestStorage.TestDevice> devices(GameTestHelper helper, int count) {
        List<TestStorage.TestDevice> devices = new ArrayList<>();
        for (int x : new int[]{3, 4, 5, 7}) {
            for (BlockPos pos : List.of(new BlockPos(x, 1, 0), new BlockPos(x, 1, 2), new BlockPos(x, 2, 1),
                    new BlockPos(x, 0, 1))) {
                if (devices.size() < count && !pos.equals(TERMINAL)) {
                    devices.add(NetworkGameTests.device(helper, pos));
                }
            }
        }
        return devices;
    }

    /** The lang key of the status entry Jade shows first at {@code pos}. */
    private static String statusKey(GameTestHelper helper, BlockPos pos) {
        List<InfoEntry> entries = BlockInfoLines.collect(helper.getLevel(), helper.absolutePos(pos), null, null, false);
        for (InfoEntry entry : entries) {
            if (entry.kind() == InfoEntry.Kind.STATUS
                    && entry.text().getContents() instanceof TranslatableContents translatable) {
                return translatable.getKey();
            }
        }
        throw helper.assertionException(Component.literal("no status at " + pos.toShortString()));
    }

    private static CableParts parts(GameTestHelper helper) {
        CableParts parts = StorageCableBlock.parts(helper.getLevel(), helper.absolutePos(PART_CABLE));
        if (parts == null) {
            throw helper.assertionException(Component.literal("no cable parts"));
        }
        return parts;
    }
}
