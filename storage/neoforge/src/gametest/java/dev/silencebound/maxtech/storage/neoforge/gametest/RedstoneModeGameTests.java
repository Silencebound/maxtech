package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.iface.InterfaceBlockEntity;
import dev.silencebound.maxtech.storage.iface.InterfaceContent;
import dev.silencebound.maxtech.storage.iface.InterfaceMenu;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.port.PortBlock;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortMenu;
import dev.silencebound.maxtech.storage.port.PortMode;
import dev.silencebound.maxtech.storage.redstone.RedstoneMode;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The redstone mode of ports and interfaces (B09 item 9; the IO port's are in {@link IoPortGameTests}): with signal,
 * without signal and pulse on import and export ports, a storage bus that withdraws its view while inactive and shows
 * it again without dupes, an interface that neither stocks nor takes inserts while inactive, and the mode in the menu,
 * the item, a save and the blueprint card. The signal comes from a redstone block above the device. Layout as in
 * {@link PortGameTests}: the controller at x 1 with a test drive behind it (z 2), a tier-1 cable at x 2, the device at
 * x 3 facing east into the chest at x 4.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class RedstoneModeGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final BlockPos CONTROLLER = new BlockPos(1, 1, 1);
    private static final BlockPos DRIVE = new BlockPos(1, 1, 2);
    private static final BlockPos DEVICE = new BlockPos(3, 1, 1);
    private static final BlockPos INVENTORY = new BlockPos(4, 1, 1);
    private static final BlockPos LEVER = DEVICE.above();
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey DIRT = ItemKey.of(Items.DIRT);
    private static final long POWER = 100_000;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);
    private static final List<String> TESTS = new ArrayList<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("redstone_port_import_with_signal", RedstoneModeGameTests::importWithSignal);
        register("redstone_port_export_without_signal", RedstoneModeGameTests::exportWithoutSignal);
        register("redstone_port_pulse", RedstoneModeGameTests::pulse);
        register("redstone_storage_bus_withdraws", RedstoneModeGameTests::storageBusWithdraws);
        register("redstone_interface_with_signal", RedstoneModeGameTests::interfaceWithSignal);
        register("redstone_settings_kept", RedstoneModeGameTests::settingsKept);
    }

    private RedstoneModeGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        TESTS.add(name);
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("redstone_mode"),
                StorageTestEnvironment.plain());
        for (String name : TESTS) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, STRUCTURE, 400, 0, true)));
        }
    }

    /**
     * An import port "with signal" moves nothing and is off without a signal, imports with one, and stops again when
     * the signal goes.
     */
    private static void importWithSignal(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ChestBlockEntity chest = chest(helper, new ItemStack(Items.COBBLESTONE, 64));
        PortBlockEntity port = port(helper, PortMode.IMPORT);
        port.redstone().setMode(RedstoneMode.WITH_SIGNAL);
        long[] stopped = new long[1];
        helper.startSequence().thenIdle(40).thenExecute(() -> {
            helper.assertValueEqual(drive.provider.amount(COBBLE), 0L, "imported without a signal");
            helper.assertTrue(port.deviceLink().redstoneOff(), "the link is not off");
            helper.setBlock(LEVER, Blocks.REDSTONE_BLOCK);
        }).thenWaitUntil(() -> helper.assertTrue(drive.provider.amount(COBBLE) > 0, "nothing imported with a signal"))
                .thenExecute(() -> helper.setBlock(LEVER, Blocks.AIR)).thenIdle(2)
                .thenExecute(() -> stopped[0] = drive.provider.amount(COBBLE)).thenIdle(60).thenExecute(() -> {
                    helper.assertValueEqual(drive.provider.amount(COBBLE), stopped[0], "imported after the signal");
                    helper.assertTrue(stopped[0] < 64, "the whole chest moved before the signal went");
                    helper.assertValueEqual(count(chest) + drive.provider.amount(COBBLE), 64L, "items");
                }).thenSucceed();
    }

    /**
     * An export port set to "without signal" while it has one stays off (the signal came while it ignored redstone);
     * once the signal goes it exports.
     */
    private static void exportWithoutSignal(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        drive.provider.setDirect(COBBLE, 50);
        ChestBlockEntity chest = chest(helper);
        PortBlockEntity port = port(helper, PortMode.EXPORT);
        helper.startSequence().thenIdle(5).thenExecute(() -> {
            // The signal arrives while the started port ignores redstone, which skips reading it: the mode change
            // reads it.
            helper.setBlock(LEVER, Blocks.REDSTONE_BLOCK);
            port.redstone().setMode(RedstoneMode.WITHOUT_SIGNAL);
            port.filter().setItem(0, new ItemStack(Items.COBBLESTONE));
        }).thenIdle(40).thenExecute(() -> {
            helper.assertValueEqual(count(chest), 0L, "exported with a signal");
            helper.setBlock(LEVER, Blocks.AIR);
        }).thenWaitUntil(() -> helper.assertValueEqual(count(chest), 50L, "exported without a signal")).thenSucceed();
    }

    /**
     * Pulse: nothing moves without an edge; each rising edge moves exactly one step of the port's rate (16 at tier 1,
     * below one batch), with a full second between edges so the rate's credit is back; a held signal moves no more.
     */
    private static void pulse(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ChestBlockEntity chest = chest(helper, new ItemStack(Items.COBBLESTONE, 64));
        PortBlockEntity port = port(helper, PortMode.IMPORT);
        port.redstone().setMode(RedstoneMode.PULSE);
        long step = 16;
        helper.startSequence().thenIdle(40).thenExecute(() -> {
            helper.assertValueEqual(drive.provider.amount(COBBLE), 0L, "imported without a pulse");
            helper.assertTrue(!port.deviceLink().redstoneOff(), "a pulse port is off");
            helper.setBlock(LEVER, Blocks.REDSTONE_BLOCK);
        }).thenWaitUntil(() -> helper.assertValueEqual(drive.provider.amount(COBBLE), step, "after one edge"))
                .thenIdle(30).thenExecute(() -> {
                    // The signal is still on: no new edge, no new operation.
                    helper.assertValueEqual(drive.provider.amount(COBBLE), step, "one edge moved more than a step");
                    helper.setBlock(LEVER, Blocks.AIR);
                }).thenIdle(2).thenExecute(() -> helper.setBlock(LEVER, Blocks.REDSTONE_BLOCK))
                .thenWaitUntil(
                        () -> helper.assertValueEqual(drive.provider.amount(COBBLE), 2 * step, "after two edges"))
                .thenIdle(30).thenExecute(() -> {
                    helper.assertValueEqual(drive.provider.amount(COBBLE), 2 * step, "two edges moved more");
                    helper.assertValueEqual(count(chest) + drive.provider.amount(COBBLE), 64L, "items");
                }).thenSucceed();
    }

    /**
     * A storage bus set to "with signal" without one withdraws the chest from the network: its contents leave the
     * index, a take gets nothing and a store goes to the drive. With the signal its contents show again exactly once,
     * and a take comes out of the chest. Pulse counts as ignore in storage mode.
     */
    private static void storageBusWithdraws(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ChestBlockEntity chest = chest(helper, new ItemStack(Items.COBBLESTONE, 64));
        PortBlockEntity port = port(helper, PortMode.STORAGE);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(state(helper).contents().amount(COBBLE), 64L, "indexed"))
                .thenExecute(() -> port.redstone().setMode(RedstoneMode.WITH_SIGNAL))
                .thenWaitUntil(() -> helper.assertValueEqual(state(helper).contents().amount(COBBLE), 0L, "withdrawn"))
                .thenExecute(() -> {
                    NetworkState network = state(helper);
                    helper.assertValueEqual(extract(network, COBBLE, 10), 0L, "taken while withdrawn");
                    helper.assertValueEqual(NetworkGameTests.insert(network, DIRT, 5), 5L, "stored while withdrawn");
                    helper.assertValueEqual(drive.provider.amount(DIRT), 5L, "dirt in the drive");
                    helper.assertValueEqual(count(chest), 64L, "the chest while withdrawn");
                    helper.setBlock(LEVER, Blocks.REDSTONE_BLOCK);
                })
                .thenWaitUntil(
                        () -> helper.assertValueEqual(state(helper).contents().amount(COBBLE), 64L, "shown again"))
                .thenIdle(20).thenExecute(() -> {
                    NetworkState network = state(helper);
                    helper.assertValueEqual(network.contents().amount(COBBLE), 64L, "shown once");
                    helper.assertValueEqual(extract(network, COBBLE, 10), 10L, "taken again");
                    helper.assertValueEqual(count(chest), 54L, "the chest after the take");
                    helper.assertValueEqual(network.contents().amount(COBBLE), 54L, "indexed after the take");
                    helper.setBlock(LEVER, Blocks.AIR);
                    port.redstone().setMode(RedstoneMode.PULSE);
                    helper.assertTrue(!port.deviceLink().redstoneOff(), "pulse in storage mode is off");
                }).thenIdle(20).thenExecute(() -> helper.assertValueEqual(state(helper).contents().amount(COBBLE), 54L,
                        "shown in pulse mode"))
                .thenSucceed();
    }

    /**
     * An interface "with signal" neither stocks nor takes an insert without one; with the signal it stocks its target.
     */
    private static void interfaceWithSignal(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        drive.provider.setDirect(COBBLE, 100);
        helper.setBlock(DEVICE, InterfaceContent.INTERFACE.get());
        InterfaceBlockEntity iface = helper.getBlockEntity(DEVICE, InterfaceBlockEntity.class);
        iface.worker().redstone().setMode(RedstoneMode.WITH_SIGNAL);
        iface.worker().config().setItem(0, new ItemStack(Items.COBBLESTONE, 16));
        helper.startSequence().thenIdle(40).thenExecute(() -> {
            helper.assertValueEqual(iface.worker().itemStock().getAmountAsLong(0), 0L, "stocked without a signal");
            ResourceHandler<ItemResource> items = helper.getLevel().getCapability(Capabilities.Item.BLOCK,
                    helper.absolutePos(DEVICE), Direction.UP);
            if (items == null) {
                throw helper.assertionException(Component.literal("no item handler"));
            }
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(
                        ResourceHandlerUtil.insertStacking(items, ItemResource.of(Items.DIRT), 4, transaction), 0,
                        "inserted without a signal");
            }
            helper.setBlock(LEVER, Blocks.REDSTONE_BLOCK);
        }).thenWaitUntil(() -> helper.assertValueEqual(iface.worker().itemStock().getAmountAsLong(0), 16L,
                "stocked with a signal"))
                .thenExecute(
                        () -> helper.assertValueEqual(drive.provider.amount(COBBLE), 84L, "cobblestone in the drive"))
                .thenSucceed();
    }

    /**
     * The menu steps the mode forward and back (pulse only on the port); the mode goes into the item and back into a
     * placed port, survives a save and load, and the blueprint card copies it between ports and between interfaces.
     */
    private static void settingsKept(GameTestHelper helper) {
        network(helper);
        chest(helper);
        PortBlockEntity port = port(helper, PortMode.IMPORT);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(helper.absoluteVec(DEVICE.getCenter()));
        PortMenu menu = PortMenu.forPort(1, player.getInventory(), port);
        helper.assertTrue(menu.clickMenuButton(player, PortMenu.CYCLE_REDSTONE), "redstone button");
        helper.assertValueEqual(port.redstone().mode(), RedstoneMode.WITH_SIGNAL, "after one click");
        menu.clickMenuButton(player, PortMenu.REDSTONE_BACK);
        menu.clickMenuButton(player, PortMenu.REDSTONE_BACK);
        helper.assertValueEqual(port.redstone().mode(), RedstoneMode.PULSE, "back from ignore");
        helper.assertValueEqual(menu.redstone().mode(), RedstoneMode.PULSE, "synced to the menu");
        // The item.
        DataComponentMap components = port.collectComponents();
        helper.assertValueEqual(components.getOrDefault(ModDataComponents.REDSTONE_MODE.get(), RedstoneMode.IGNORE),
                RedstoneMode.PULSE, "mode in the item");
        ItemStack item = new ItemStack(PortContent.PORT.item().get());
        item.applyComponents(components);
        BlockPos other = new BlockPos(3, 1, 6);
        BlockPos target = helper.absolutePos(other);
        player.setItemInHand(InteractionHand.MAIN_HAND, item);
        helper.assertTrue(
                item.useOn(new UseOnContext(helper.getLevel(), player, InteractionHand.MAIN_HAND, item,
                        new BlockHitResult(target.getCenter(), Direction.UP, target, false))).consumesAction(),
                "placing");
        PortBlockEntity placed = helper.getBlockEntity(other, PortBlockEntity.class);
        helper.assertValueEqual(placed.redstone().mode(), RedstoneMode.PULSE, "placed mode");
        // A save and load.
        port.redstone().setMode(RedstoneMode.WITHOUT_SIGNAL);
        CompoundTag saved = port.saveWithFullMetadata(helper.getLevel().registryAccess());
        BlockPos abs = helper.absolutePos(DEVICE);
        BlockEntity loaded = BlockEntity.loadStatic(abs, helper.getLevel().getBlockState(abs), saved,
                helper.getLevel().registryAccess());
        helper.assertTrue(
                loaded instanceof PortBlockEntity copy && copy.redstone().mode() == RedstoneMode.WITHOUT_SIGNAL,
                "the mode did not survive a save");
        // The blueprint card, port to port.
        placed.copyableSettings().paste().accept(port.copyableSettings().copy().get());
        helper.assertValueEqual(placed.redstone().mode(), RedstoneMode.WITHOUT_SIGNAL, "pasted onto the port");
        // Interfaces: no pulse; the card copies the mode.
        BlockPos firstPos = new BlockPos(6, 1, 6);
        BlockPos secondPos = new BlockPos(8, 1, 6);
        helper.setBlock(firstPos, InterfaceContent.INTERFACE.get());
        helper.setBlock(secondPos, InterfaceContent.INTERFACE.get());
        InterfaceBlockEntity first = helper.getBlockEntity(firstPos, InterfaceBlockEntity.class);
        InterfaceBlockEntity second = helper.getBlockEntity(secondPos, InterfaceBlockEntity.class);
        InterfaceMenu ifaceMenu = InterfaceMenu.forDevice(2, player.getInventory(), first);
        helper.assertTrue(ifaceMenu.clickMenuButton(player, InterfaceMenu.REDSTONE_BACK), "interface button");
        helper.assertValueEqual(first.redstone().mode(), RedstoneMode.WITHOUT_SIGNAL, "interface back from ignore");
        first.redstone().setMode(RedstoneMode.PULSE);
        helper.assertValueEqual(first.redstone().mode(), RedstoneMode.IGNORE, "an interface has no pulse");
        first.redstone().setMode(RedstoneMode.WITH_SIGNAL);
        helper.assertValueEqual(
                first.collectComponents().getOrDefault(ModDataComponents.REDSTONE_MODE.get(), RedstoneMode.IGNORE),
                RedstoneMode.WITH_SIGNAL, "interface mode in the item");
        second.copyableSettings().paste().accept(first.copyableSettings().copy().get());
        helper.assertValueEqual(second.redstone().mode(), RedstoneMode.WITH_SIGNAL, "pasted onto the interface");
        helper.succeed();
    }

    // --- Helpers ---------------------------------------------------------------------------------------------------

    private static NetworkState state(GameTestHelper helper) {
        return NetworkGameTests.state(helper, CONTROLLER);
    }

    private static long extract(NetworkState network, StackKey key, long amount) {
        try (Transaction transaction = Transaction.openRoot()) {
            long moved = network.extract(key, amount, transaction);
            transaction.commit();
            return moved;
        }
    }

    /** A powered controller with a test drive behind it and the cable towards the device. */
    private static TestStorage.TestDevice network(GameTestHelper helper) {
        NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, CONTROLLER, POWER);
        TestStorage.TestDevice drive = NetworkGameTests.device(helper, DRIVE);
        NetworkGameTests.cables(helper, 2, 2, CableContent.STORAGE_CABLE_TIER_1.type().get());
        return drive;
    }

    private static PortBlockEntity port(GameTestHelper helper, PortMode mode) {
        helper.setBlock(DEVICE, PortContent.PORT.get().defaultBlockState().setValue(PortBlock.FACING, Direction.EAST)
                .setValue(PortBlock.MODE, mode));
        return helper.getBlockEntity(DEVICE, PortBlockEntity.class);
    }

    private static ChestBlockEntity chest(GameTestHelper helper, ItemStack... stacks) {
        helper.setBlock(INVENTORY, Blocks.CHEST);
        ChestBlockEntity chest = helper.getBlockEntity(INVENTORY, ChestBlockEntity.class);
        for (int i = 0; i < stacks.length; i++) {
            chest.setItem(i, stacks[i]);
        }
        return chest;
    }

    private static long count(ChestBlockEntity chest) {
        long total = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack stack = chest.getItem(i);
            if (stack.is(Items.COBBLESTONE)) {
                total += stack.getCount();
            }
        }
        return total;
    }
}
