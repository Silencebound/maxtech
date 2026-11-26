package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.signal.AntennaLinking;
import dev.silencebound.maxtech.storage.signal.SignalContent;
import dev.silencebound.maxtech.storage.subnet.SubnetBlock;
import dev.silencebound.maxtech.storage.subnet.SubnetBlockEntity;
import dev.silencebound.maxtech.storage.subnet.SubnetContent;
import dev.silencebound.maxtech.storage.subnet.SubnetMenu;
import dev.silencebound.maxtech.storage.subnet.SubnetMode;
import java.util.Locale;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
// PLATFORM-LEAK: tests move items in NeoForge transactions like the network does.
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The subnet controller (M6d): for each mode, what the parent and the subnet see of each other, take from each other
 * and store into each other, with nothing counted twice; the filter; and the settings surviving the item and the
 * screen's buttons.
 *
 * <p>
 * Layout on one row: a controller at x = 1, cables 2 to 4 with a parent device beside cable 2, the subnet controller at
 * x = 5 facing west, subnet cables 6 to 7 and a subnet device at x = 8.
 */
// Networks are compared by identity.
@SuppressWarnings("ReferenceEquality")
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class SubnetGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final String SETTINGS = "subnet_settings_saved";
    private static final String PARENT_FE = "subnet_draws_parent_fe";
    private static final String TOO_LITTLE = "subnet_parent_too_little_fe";
    private static final String LOOP = "subnet_loop_counts_fe_once";
    private static final String ANTENNA = "subnet_antenna_link_stays_inside";

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        for (SubnetMode mode : SubnetMode.values()) {
            FUNCTIONS.register(name(mode), () -> StorageTestEnvironment.clearing(helper -> modeTest(helper, mode)));
        }
        FUNCTIONS.register(SETTINGS, () -> StorageTestEnvironment.clearing(SubnetGameTests::settingsSaved));
        FUNCTIONS.register(PARENT_FE, () -> StorageTestEnvironment.clearing(SubnetGameTests::drawsParentFe));
        FUNCTIONS.register(TOO_LITTLE, () -> StorageTestEnvironment.clearing(SubnetGameTests::parentTooLittle));
        FUNCTIONS.register(LOOP, () -> StorageTestEnvironment.clearing(SubnetGameTests::loop));
        FUNCTIONS.register(ANTENNA, () -> StorageTestEnvironment.clearing(SubnetGameTests::antennaInside));
    }

    private SubnetGameTests() {
    }

    private static String name(SubnetMode mode) {
        return "subnet_" + mode.name().toLowerCase(Locale.ROOT);
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> plain = event.registerEnvironment(MaxtechStorage.id("subnet"),
                StorageTestEnvironment.plain());
        for (SubnetMode mode : SubnetMode.values()) {
            test(event, name(mode), plain);
        }
        test(event, SETTINGS, plain);
        test(event, PARENT_FE, plain);
        test(event, TOO_LITTLE, plain);
        test(event, LOOP, plain);
        test(event, ANTENNA, plain);
    }

    private static void test(RegisterGameTestsEvent event, String name, Holder<TestEnvironmentDefinition<?>> env) {
        event.registerTest(MaxtechStorage.id(name),
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                        new TestData<>(env, STRUCTURE, 200, 0, true)));
    }

    /** Places the subnet controller at {@code pos} facing {@code front}. */
    static SubnetBlockEntity subnet(GameTestHelper helper, BlockPos pos, Direction front) {
        helper.setBlock(pos, SubnetContent.SUBNET.get().defaultBlockState().setValue(SubnetBlock.FACING, front));
        return helper.getBlockEntity(pos, SubnetBlockEntity.class);
    }

    /**
     * The parent holds 7 iron in its own device, the subnet 10 cobblestone. After the mode is set: the parent sees the
     * cobblestone only when the mode shares up and can then take it; the subnet sees the iron only when it is a client
     * or merged; an iron insert into the subnet goes to the parent's device only when merged, and to the subnet's own
     * device otherwise. Each side's own totals never include what it shows of the other twice. With a filter naming
     * iron, merged parents no longer see the cobblestone.
     */
    private static void modeTest(GameTestHelper helper, SubnetMode mode) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.cables(helper, 2, 4, CableContent.STORAGE_CABLE_TIER_1.type().get());
        TestStorage.TestDevice parentDevice = NetworkGameTests.device(helper, new BlockPos(2, 1, 2));
        SubnetBlockEntity subnet = subnet(helper, NetworkGameTests.at(5), Direction.WEST);
        NetworkGameTests.cables(helper, 6, 7, CableContent.STORAGE_CABLE_TIER_1.type().get());
        TestStorage.TestDevice subnetDevice = NetworkGameTests.device(helper, NetworkGameTests.at(8));
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState parent = NetworkGameTests.state(helper, controller);
            NetworkState child = NetworkGameTests.net(helper, subnetDevice);
            helper.assertTrue(parent != child, "the subnet is its own network");
            helper.assertTrue(subnet.parent() == parent, "uplink not in the parent");
            helper.assertTrue(subnet.network() == child, "subnet controller not in the subnet");
            helper.assertTrue(parentDevice.network() == parent, "parent device");
            helper.assertFalse(child.isAdHoc(), "the subnet controller is the subnet's controller");
            helper.assertValueEqual(child.routeRoot(), helper.absolutePos(NetworkGameTests.at(5)).asLong(),
                    "the subnet's route root");
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, controller, 10_000);
            subnet.buffer().receive(10_000);
            parentDevice.provider.setDirect(IRON, 7);
            subnetDevice.provider.setDirect(COBBLE, 10);
            subnet.setMode(mode);
        }).thenIdle(2).thenExecute(() -> {
            NetworkState parent = NetworkGameTests.state(helper, controller);
            NetworkState child = NetworkGameTests.net(helper, subnetDevice);
            helper.assertValueEqual(parent.contents().amount(COBBLE), mode.up() ? 10L : 0L, "parent sees cobble");
            helper.assertValueEqual(child.contents().amount(IRON), mode.down() ? 7L : 0L, "subnet sees iron");
            helper.assertValueEqual(child.contents().amount(COBBLE), 10L, "subnet's own cobble, once");
            helper.assertValueEqual(parent.contents().amount(IRON), 7L, "parent's own iron, once");
            helper.assertValueEqual(extract(parent, COBBLE, 3), mode.up() ? 3L : 0L, "parent takes cobble");
            helper.assertValueEqual(subnetDevice.provider.amount(COBBLE), mode.up() ? 7L : 10L, "cobble left");
            helper.assertValueEqual(child.contents().amount(COBBLE), mode.up() ? 7L : 10L, "subnet total after");
            helper.assertValueEqual(NetworkGameTests.insert(child, IRON, 2), 2L, "subnet stores iron");
            helper.assertValueEqual(parentDevice.provider.amount(IRON), mode.stores() ? 9L : 7L,
                    "iron stored through the view only when merged");
            helper.assertValueEqual(subnetDevice.provider.amount(IRON), mode.stores() ? 0L : 2L,
                    "iron stored in the subnet's own device otherwise");
            helper.assertValueEqual(parent.contents().amount(IRON), mode.up() ? 9L : (mode.stores() ? 9L : 7L),
                    "parent's iron total");
        }).thenExecute(() -> subnet.filter().setItem(0, new ItemStack(Items.IRON_INGOT))).thenIdle(1)
                .thenExecute(() -> {
                    NetworkState parent = NetworkGameTests.state(helper, controller);
                    helper.assertValueEqual(parent.contents().amount(COBBLE), 0L, "filtered cobble");
                    NetworkState child = NetworkGameTests.net(helper, subnetDevice);
                    helper.assertValueEqual(child.contents().amount(IRON),
                            (mode.down() ? (mode.stores() ? 9L : 7L) : 0L) + (mode.stores() ? 0L : 2L),
                            "iron passes the filter");
                }).thenSucceed();
    }

    /**
     * Mode, priority and filter survive the item form, and the screen's buttons change them for the owner and nobody
     * else.
     */
    private static void settingsSaved(GameTestHelper helper) {
        BlockPos pos = NetworkGameTests.at(5);
        SubnetBlockEntity subnet = subnet(helper, pos, Direction.WEST);
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "subnet_owner");
        ServerPlayer stranger = NetworkGameTests.player(helper.getLevel(), "subnet_stranger");
        subnet.setOwner(OwnerRecord.of(owner));
        SubnetMenu menu = SubnetMenu.forSubnet(1, owner.getInventory(), subnet);
        helper.assertTrue(menu.clickMenuButton(owner, SubnetMenu.CYCLE_MODE), "owner cycles the mode");
        helper.assertValueEqual(subnet.mode(), SubnetMode.SHARES_UP, "mode after one click");
        helper.assertTrue(menu.applyPriority(owner, Integer.MAX_VALUE), "owner raises the priority");
        helper.assertValueEqual(subnet.priority(), Integer.MAX_VALUE, "priority after setting MAX_VALUE");
        SubnetMenu foreign = SubnetMenu.forSubnet(2, stranger.getInventory(), subnet);
        helper.assertFalse(foreign.clickMenuButton(stranger, SubnetMenu.CYCLE_MODE), "stranger refused");
        helper.assertValueEqual(subnet.mode(), SubnetMode.SHARES_UP, "mode after the stranger");
        subnet.filter().setItem(3, new ItemStack(Items.IRON_INGOT));
        subnet.buffer().receive(1234);
        ItemStack item = subnet.toItemStack(helper.getLevel().registryAccess());
        CompoundTag tag = subnet.saveWithoutMetadata(helper.getLevel().registryAccess());
        SubnetBlockEntity loaded = new SubnetBlockEntity(helper.absolutePos(pos), helper.getBlockState(pos));
        loaded.loadCustomOnly(
                TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), tag));
        helper.assertValueEqual(loaded.mode(), SubnetMode.SHARES_UP, "mode after loading");
        helper.assertValueEqual(loaded.priority(), Integer.MAX_VALUE, "priority after loading");
        helper.assertTrue(loaded.filter().allows(IRON) && !loaded.filter().allows(COBBLE), "filter after loading");
        helper.assertValueEqual(loaded.buffer().stored(), 1234L, "FE after loading");
        helper.assertTrue(item.get(DataComponents.BLOCK_ENTITY_DATA) != null, "settings in the item");
        helper.succeed();
    }

    /**
     * An isolated subnet without FE of its own: with an unpowered parent it stores nothing; with a powered parent it
     * stores into its own device and the parent pays, while the subnet's buffer stays empty.
     */
    private static void drawsParentFe(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.cables(helper, 2, 4, CableContent.STORAGE_CABLE_TIER_1.type().get());
        SubnetBlockEntity subnet = subnet(helper, NetworkGameTests.at(5), Direction.WEST);
        NetworkGameTests.cables(helper, 6, 7, CableContent.STORAGE_CABLE_TIER_1.type().get());
        TestStorage.TestDevice subnetDevice = NetworkGameTests.device(helper, NetworkGameTests.at(8));
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState child = NetworkGameTests.net(helper, subnetDevice);
            helper.assertTrue(subnet.parent() == NetworkGameTests.state(helper, controller), "uplink in the parent");
            helper.assertTrue(subnet.network() == child, "subnet formed");
        }).thenExecute(() -> {
            subnet.setMode(SubnetMode.ISOLATED);
            NetworkState child = NetworkGameTests.net(helper, subnetDevice);
            helper.assertValueEqual(NetworkGameTests.insert(child, COBBLE, 4), 0L, "stored with no FE anywhere");
            NetworkGameTests.power(helper, controller, 1000);
            long before = NetworkGameTests.controllerEntity(helper, controller).buffer().stored();
            helper.assertTrue(child.isPowered(), "the parent powers the subnet");
            helper.assertValueEqual(NetworkGameTests.insert(child, COBBLE, 4), 4L, "stored on the parent's FE");
            helper.assertValueEqual(subnetDevice.provider.amount(COBBLE), 4L, "in the subnet's own device");
            helper.assertValueEqual(subnet.buffer().stored(), 0L, "subnet buffer");
            helper.assertTrue(NetworkGameTests.controllerEntity(helper, controller).buffer().stored() < before,
                    "the parent paid");
        }).thenSucceed();
    }

    /**
     * The subnet holds 1 FE and its parent 1 FE, less than the cheapest operation (3 FE): an insert moves nothing and
     * neither buffer loses FE.
     */
    private static void parentTooLittle(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.cables(helper, 2, 4, CableContent.STORAGE_CABLE_TIER_1.type().get());
        SubnetBlockEntity subnet = subnet(helper, NetworkGameTests.at(5), Direction.WEST);
        NetworkGameTests.cables(helper, 6, 7, CableContent.STORAGE_CABLE_TIER_1.type().get());
        TestStorage.TestDevice subnetDevice = NetworkGameTests.device(helper, NetworkGameTests.at(8));
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(subnet.parent() == NetworkGameTests.state(helper, controller), "uplink in the parent");
            helper.assertTrue(subnet.network() == NetworkGameTests.net(helper, subnetDevice), "subnet formed");
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, controller, 1);
            subnet.buffer().receive(1);
            NetworkState child = NetworkGameTests.net(helper, subnetDevice);
            helper.assertValueEqual(child.storedEnergy(), 2L, "own and parent FE together");
            helper.assertValueEqual(NetworkGameTests.insert(child, COBBLE, 4), 0L, "stored with too little FE");
            helper.assertValueEqual(subnetDevice.provider.amount(COBBLE), 0L, "nothing in the device");
            helper.assertValueEqual(subnet.buffer().stored(), 1L, "subnet buffer kept");
            helper.assertValueEqual(NetworkGameTests.controllerEntity(helper, controller).buffer().stored(), 1L,
                    "parent buffer kept");
        }).thenSucceed();
    }

    /**
     * Two subnet controllers that are each other's parent: network X (controller A at x = 2, its front on Y's cable at
     * x = 1) and network Y (controller B at x = 4, its front on X's cable at x = 3, its cables running round to x = 1).
     * A holds 100 FE: both networks report 100, not more, and an insert in Y is paid from A exactly once.
     */
    private static void loop(GameTestHelper helper) {
        BlockPos y1 = new BlockPos(1, 1, 1);
        SubnetBlockEntity a = subnet(helper, new BlockPos(2, 1, 1), Direction.WEST);
        NetworkGameTests.cables(helper, 3, 3, CableContent.STORAGE_CABLE_TIER_1.type().get());
        SubnetBlockEntity b = subnet(helper, new BlockPos(4, 1, 1), Direction.WEST);
        BlockPos[] ring = {new BlockPos(5, 1, 1), new BlockPos(5, 1, 2), new BlockPos(5, 1, 3), new BlockPos(4, 1, 3),
                new BlockPos(3, 1, 3), new BlockPos(2, 1, 3), new BlockPos(1, 1, 3), new BlockPos(1, 1, 2), y1};
        for (BlockPos pos : ring) {
            ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(pos),
                    CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
        }
        TestStorage.TestDevice device = NetworkGameTests.device(helper, new BlockPos(6, 1, 1));
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState x = a.network();
            NetworkState y = b.network();
            helper.assertTrue(x != null && y != null && x != y, "two networks");
            helper.assertTrue(a.parent() == y && b.parent() == x, "each the other's parent");
            helper.assertTrue(device.network() == y, "device in Y");
        }).thenExecute(() -> {
            a.buffer().receive(100);
            NetworkState x = NetworkGameTests.state(helper, new BlockPos(3, 1, 1));
            NetworkState y = NetworkGameTests.net(helper, device);
            helper.assertValueEqual(x.storedEnergy(), 100L, "X counts A once");
            helper.assertValueEqual(y.storedEnergy(), 100L, "Y counts A once");
            helper.assertValueEqual(NetworkGameTests.insert(y, COBBLE, 4), 4L, "Y stores on A's FE");
            helper.assertValueEqual(a.buffer().stored(), 96L, "A paid once (4 FE)");
            helper.assertValueEqual(y.storedEnergy(), 96L, "Y after the insert");
        }).thenSucceed();
    }

    /**
     * An antenna inside a subnet paired with a far antenna: the far device joins the subnet, not the parent, and the
     * subnet stays a network of its own.
     */
    private static void antennaInside(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.cables(helper, 2, 4, CableContent.STORAGE_CABLE_TIER_1.type().get());
        SubnetBlockEntity subnet = subnet(helper, NetworkGameTests.at(5), Direction.WEST);
        NetworkGameTests.cables(helper, 6, 6, CableContent.STORAGE_CABLE_TIER_1.type().get());
        BlockPos near = NetworkGameTests.at(7);
        BlockPos far = NetworkGameTests.at(30);
        helper.setBlock(near, SignalContent.ANTENNA_TIER_1.get());
        helper.setBlock(far, SignalContent.ANTENNA_TIER_1.get());
        TestStorage.TestDevice device = NetworkGameTests.device(helper, NetworkGameTests.at(31));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(
                        subnet.network() != null && subnet.parent() == NetworkGameTests.state(helper, controller),
                        "subnet formed"))
                .thenExecute(() -> {
                    String refused = AntennaLinking.pair(helper.getLevel(), helper.absolutePos(near),
                            helper.absolutePos(far));
                    helper.assertTrue(refused == null, "pairing refused: " + refused);
                })
                .thenWaitUntil(
                        () -> helper.assertTrue(device.network() == subnet.network(), "far device in the subnet"))
                .thenExecute(() -> {
                    helper.assertFalse(device.network() == NetworkGameTests.state(helper, controller),
                            "far device in the parent");
                    helper.assertFalse(subnet.parent() == subnet.network(), "subnet merged into the parent");
                }).thenSucceed();
    }

    private static long extract(NetworkState network, StackKey key, long amount) {
        try (Transaction transaction = Transaction.openRoot()) {
            long moved = network.extract(key, amount, transaction);
            transaction.commit();
            return moved;
        }
    }
}
