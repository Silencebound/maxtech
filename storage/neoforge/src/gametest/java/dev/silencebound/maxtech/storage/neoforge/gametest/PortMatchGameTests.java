package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.inventory.RemoteSlot;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.cell.KeyMatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.port.MatchMode;
import dev.silencebound.maxtech.storage.port.PortBlock;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortEntrySettings;
import dev.silencebound.maxtech.storage.port.PortFilter;
import dev.silencebound.maxtech.storage.port.PortMenu;
import dev.silencebound.maxtech.storage.port.PortMode;
import dev.silencebound.maxtech.storage.port.PortOrder;
import dev.silencebound.maxtech.storage.port.PortPayloads;
import dev.silencebound.maxtech.storage.tool.blueprint.DeviceSettings;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Port filter entries beyond exact (B09 item 18): fuzzy, tag and mod entries on import, export and storage bus ports,
 * the round-robin export order, and the regulator (B09 item 22): an export entry keeps an amount in the inventory and
 * tops it up as it is drained. Persistence of the entries, the order and the keep amounts through saves, the item and
 * the blueprint card, and the menu's controls. Layout as in {@link PortGameTests}: the controller at x 1 with a test
 * drive behind it (z 2), a cable at x 2, the port at x 3 facing east into the chest at x 4.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class PortMatchGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final BlockPos CONTROLLER = new BlockPos(1, 1, 1);
    private static final BlockPos DRIVE = new BlockPos(1, 1, 2);
    private static final BlockPos PORT = new BlockPos(3, 1, 1);
    private static final BlockPos INVENTORY = new BlockPos(4, 1, 1);
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey DIRT = ItemKey.of(Items.DIRT);
    private static final StackKey OAK = ItemKey.of(Items.OAK_LOG);
    private static final StackKey BIRCH = ItemKey.of(Items.BIRCH_LOG);
    private static final StackKey SPRUCE = ItemKey.of(Items.SPRUCE_LOG);
    private static final StackKey COAL = ItemKey.of(Items.COAL);
    private static final Identifier LOGS = Identifier.withDefaultNamespace("logs");
    private static final long POWER = 100_000;
    /** A test with {@code ports.filter.exportKeysPerOperation} at 2, in its own environment. */
    private static final String CAP = "port_match_cap_skips_full_kinds";
    /** More new kinds than the storage index's log of appeared kinds holds (1,024). */
    private static final int OVERFLOW_KINDS = 1100;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);
    private static final List<String> TESTS = new ArrayList<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("port_match_fuzzy_import", PortMatchGameTests::fuzzyImport);
        register("port_match_fuzzy_export", PortMatchGameTests::fuzzyExport);
        register("port_match_tag_import", PortMatchGameTests::tagImport);
        register("port_match_tag_export", PortMatchGameTests::tagExport);
        register("port_match_mod_import", PortMatchGameTests::modImport);
        register("port_match_mod_export", PortMatchGameTests::modExport);
        register("port_match_storage_bus", PortMatchGameTests::storageBus);
        register("port_match_round_robin", PortMatchGameTests::roundRobin);
        register("port_match_regulator_keeps", PortMatchGameTests::regulatorKeeps);
        register("port_match_regulator_tag", PortMatchGameTests::regulatorTag);
        register("port_match_persistence", PortMatchGameTests::persistence);
        register("port_match_menu", PortMatchGameTests::menu);
        register("port_match_appeared_overflow", PortMatchGameTests::appearedOverflow);
        register("port_match_kind_returns", PortMatchGameTests::kindReturns);
        register("port_match_refused_import", PortMatchGameTests::refusedImport);
        FUNCTIONS.register(CAP, () -> StorageTestEnvironment.clearing(PortMatchGameTests::capSkipsFullKinds));
    }

    private PortMatchGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        TESTS.add(name);
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("port_match"),
                StorageTestEnvironment.plain());
        for (String name : TESTS) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, STRUCTURE, 800, 0, true)));
        }
        Holder<TestEnvironmentDefinition<?>> capped = event.registerEnvironment(MaxtechStorage.id(CAP),
                new StorageTestEnvironment("ports.filter.exportKeysPerOperation", "2", false));
        event.registerTest(MaxtechStorage.id(CAP),
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(CAP)),
                        new TestData<>(capped, STRUCTURE, 400, 0, true)));
    }

    /** An iron pickaxe with some damage: a key of its own, which only a fuzzy entry for the pickaxe matches. */
    private static ItemStack damagedPickaxe() {
        ItemStack stack = new ItemStack(Items.IRON_PICKAXE);
        stack.setDamageValue(37);
        return stack;
    }

    /**
     * A fuzzy entry for a new iron pickaxe imports the damaged one; the diamond pickaxe stays. The same entry in exact
     * mode would take nothing.
     */
    private static void fuzzyImport(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ChestBlockEntity chest = chest(helper, damagedPickaxe(), new ItemStack(Items.DIAMOND_PICKAXE));
        PortBlockEntity port = port(helper, PortMode.IMPORT);
        port.filter().setItem(0, new ItemStack(Items.IRON_PICKAXE));
        port.filter().setMatch(0, MatchMode.FUZZY, null);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(drive.provider.amount(ItemKey.of(damagedPickaxe())), 1L,
                        "damaged pickaxe in the drive"))
                .thenIdle(20)
                .thenExecute(() -> helper.assertValueEqual(count(chest, Items.DIAMOND_PICKAXE), 1, "diamond pickaxe"))
                .thenSucceed();
    }

    /** A fuzzy export entry for a new iron pickaxe exports the damaged one the network holds. */
    private static void fuzzyExport(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        drive.provider.setDirect(ItemKey.of(damagedPickaxe()), 1);
        drive.provider.setDirect(ItemKey.of(Items.DIAMOND_PICKAXE), 1);
        ChestBlockEntity chest = chest(helper);
        PortBlockEntity port = port(helper, PortMode.EXPORT);
        port.filter().setItem(0, new ItemStack(Items.IRON_PICKAXE));
        port.filter().setMatch(0, MatchMode.FUZZY, null);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(count(chest, Items.IRON_PICKAXE), 1, "pickaxe exported"))
                .thenIdle(20).thenExecute(() -> {
                    helper.assertTrue(ItemStack.isSameItemSameComponents(chest.getItem(0), damagedPickaxe()),
                            "the damaged pickaxe itself");
                    helper.assertValueEqual(drive.provider.amount(ItemKey.of(Items.DIAMOND_PICKAXE)), 1L,
                            "diamond pickaxe left in the drive");
                }).thenSucceed();
    }

    /** A tag entry (#minecraft:logs, picked on an oak log) imports oak and birch logs and leaves the cobblestone. */
    private static void tagImport(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ChestBlockEntity chest = chest(helper, new ItemStack(Items.BIRCH_LOG, 20), new ItemStack(Items.COBBLESTONE, 20),
                new ItemStack(Items.OAK_LOG, 5));
        PortBlockEntity port = port(helper, PortMode.IMPORT);
        port.filter().setItem(0, new ItemStack(Items.OAK_LOG));
        port.filter().setMatch(0, MatchMode.TAG, LOGS);
        helper.assertValueEqual(port.filter().mode(0), MatchMode.TAG, "mode after setting the tag");
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertValueEqual(drive.provider.amount(BIRCH), 20L, "birch in the drive");
            helper.assertValueEqual(drive.provider.amount(OAK), 5L, "oak in the drive");
        }).thenIdle(20).thenExecute(() -> {
            helper.assertValueEqual(count(chest, Items.COBBLESTONE), 20, "cobblestone in the chest");
            helper.assertValueEqual(drive.provider.amount(COBBLE), 0L, "cobblestone in the drive");
        }).thenSucceed();
    }

    /**
     * An import skips the stacks its filter refuses without mistaking an equal-looking stack for them: an exact entry
     * for named cobblestone takes the named stack between plain ones and leaves the plain ones.
     */
    private static void refusedImport(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ItemStack named = new ItemStack(Items.COBBLESTONE, 5);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("kept apart"));
        ChestBlockEntity chest = chest(helper, new ItemStack(Items.COBBLESTONE, 64),
                new ItemStack(Items.COBBLESTONE, 64), named.copy(), new ItemStack(Items.COBBLESTONE, 64));
        PortBlockEntity port = port(helper, PortMode.IMPORT);
        port.filter().setItem(0, named.copyWithCount(1));
        helper.startSequence().thenWaitUntil(() -> helper.assertValueEqual(drive.provider.amount(ItemKey.of(named)), 5L,
                "named cobblestone in the drive")).thenIdle(40).thenExecute(() -> {
                    helper.assertValueEqual(count(chest, Items.COBBLESTONE), 192, "plain cobblestone in the chest");
                    helper.assertValueEqual(drive.provider.amount(COBBLE), 0L, "plain cobblestone in the drive");
                }).thenSucceed();
    }

    /**
     * A tag export entry moves every log the network holds and nothing else, including a log kind that only arrives in
     * the network after the port started (found through the network's log of new keys).
     */
    private static void tagExport(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        drive.provider.setDirect(OAK, 10);
        drive.provider.setDirect(BIRCH, 12);
        drive.provider.setDirect(COBBLE, 30);
        ChestBlockEntity chest = chest(helper);
        PortBlockEntity port = port(helper, PortMode.EXPORT);
        port.filter().setItem(4, new ItemStack(Items.OAK_LOG));
        port.filter().setMatch(4, MatchMode.TAG, LOGS);
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertValueEqual(count(chest, Items.OAK_LOG), 10, "oak in the chest");
            helper.assertValueEqual(count(chest, Items.BIRCH_LOG), 12, "birch in the chest");
        }).thenExecute(() -> NetworkGameTests.insert(NetworkGameTests.state(helper, CONTROLLER), SPRUCE, 7))
                .thenWaitUntil(() -> helper.assertValueEqual(count(chest, Items.SPRUCE_LOG), 7, "spruce in the chest"))
                .thenIdle(20).thenExecute(() -> {
                    helper.assertValueEqual(count(chest, Items.COBBLESTONE), 0, "cobblestone in the chest");
                    helper.assertValueEqual(drive.provider.amount(COBBLE), 30L, "cobblestone in the drive");
                }).thenSucceed();
    }

    /** A mod entry picked on a speed card imports Storage's items and leaves Minecraft's. */
    private static void modImport(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        Item card = PortContent.CRAFTING_CARD.get();
        ChestBlockEntity chest = chest(helper, new ItemStack(card, 3), new ItemStack(Items.COBBLESTONE, 20));
        PortBlockEntity port = port(helper, PortMode.IMPORT);
        port.filter().setItem(0, new ItemStack(PortContent.SPEED_CARD.get()));
        port.filter().setMatch(0, MatchMode.MOD, null);
        helper.startSequence().thenWaitUntil(() -> helper.assertValueEqual(drive.provider.amount(ItemKey.of(card)), 3L,
                "crafting cards in the drive")).thenIdle(20).thenExecute(() -> {
                    helper.assertValueEqual(count(chest, Items.COBBLESTONE), 20, "cobblestone in the chest");
                    helper.assertValueEqual(drive.provider.amount(COBBLE), 0L, "cobblestone in the drive");
                }).thenSucceed();
    }

    /** A mod entry for Minecraft exports Minecraft's items and leaves Storage's. */
    private static void modExport(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        StackKey card = ItemKey.of(PortContent.SPEED_CARD.get());
        drive.provider.setDirect(card, 4);
        drive.provider.setDirect(DIRT, 9);
        drive.provider.setDirect(COBBLE, 6);
        ChestBlockEntity chest = chest(helper);
        PortBlockEntity port = port(helper, PortMode.EXPORT);
        port.filter().setItem(0, new ItemStack(Items.STONE));
        port.filter().setMatch(0, MatchMode.MOD, null);
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertValueEqual(count(chest, Items.DIRT), 9, "dirt in the chest");
            helper.assertValueEqual(count(chest, Items.COBBLESTONE), 6, "cobblestone in the chest");
        }).thenIdle(20)
                .thenExecute(
                        () -> helper.assertValueEqual(drive.provider.amount(card), 4L, "speed cards left in the drive"))
                .thenSucceed();
    }

    /**
     * A storage bus with a fuzzy pickaxe entry and a tag entry for logs shows the damaged pickaxe and the logs, not the
     * cobblestone; a stored log goes into the chest, stored cobblestone into the drive.
     */
    private static void storageBus(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ChestBlockEntity chest = chest(helper, damagedPickaxe(), new ItemStack(Items.BIRCH_LOG, 8),
                new ItemStack(Items.COBBLESTONE, 16));
        PortBlockEntity port = port(helper, PortMode.STORAGE);
        port.setPriority(10);
        port.filter().setItem(0, new ItemStack(Items.IRON_PICKAXE));
        port.filter().setMatch(0, MatchMode.FUZZY, null);
        port.filter().setItem(1, new ItemStack(Items.OAK_LOG));
        port.filter().setMatch(1, MatchMode.TAG, LOGS);
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, CONTROLLER);
            helper.assertValueEqual(network.contents().amount(BIRCH), 8L, "indexed birch");
            helper.assertValueEqual(network.contents().amount(ItemKey.of(damagedPickaxe())), 1L, "indexed pickaxe");
        }).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, CONTROLLER);
            helper.assertValueEqual(network.contents().amount(COBBLE), 0L, "cobblestone shown");
            helper.assertValueEqual(NetworkGameTests.insert(network, OAK, 4), 4L, "oak stored");
            helper.assertValueEqual(count(chest, Items.OAK_LOG), 4, "oak in the chest");
            helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 5), 5L, "cobblestone stored");
            helper.assertValueEqual(drive.provider.amount(COBBLE), 5L, "cobblestone in the drive");
            helper.assertValueEqual(count(chest, Items.COBBLESTONE), 16, "cobblestone in the chest");
        }).thenSucceed();
    }

    /**
     * Round robin with cobblestone and dirt in the filter: each operation (16 items at tier 1) starts after the key the
     * last one moved, so the two kinds take turns and never differ by more than one operation. Filter order would move
     * only cobblestone until it ran out.
     */
    private static void roundRobin(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        drive.provider.setDirect(COBBLE, 200);
        drive.provider.setDirect(DIRT, 200);
        ChestBlockEntity chest = chest(helper);
        PortBlockEntity port = port(helper, PortMode.EXPORT);
        port.filter().setItem(0, new ItemStack(Items.COBBLESTONE));
        port.filter().setItem(1, new ItemStack(Items.DIRT));
        port.worker().setOrder(PortOrder.ROUND_ROBIN);
        helper.onEachTick(() -> {
            int cobble = count(chest, Items.COBBLESTONE);
            int dirt = count(chest, Items.DIRT);
            helper.assertTrue(Math.abs(cobble - dirt) <= 16, "cobblestone " + cobble + " against dirt " + dirt);
        });
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(count(chest, Items.COBBLESTONE) >= 64, "cobblestone in the chest");
            helper.assertTrue(count(chest, Items.DIRT) >= 64, "dirt in the chest");
        }).thenSucceed();
    }

    /**
     * A coal entry keeping 10: the port tops the chest up to exactly 10 and stops; taking 4 out makes it put exactly 4
     * back. The chest never holds more than 10.
     */
    private static void regulatorKeeps(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        drive.provider.setDirect(COAL, 100);
        ChestBlockEntity chest = chest(helper);
        PortBlockEntity port = port(helper, PortMode.EXPORT);
        port.filter().setItem(0, new ItemStack(Items.COAL));
        port.filter().setKeep(0, 10);
        helper.onEachTick(() -> helper.assertTrue(count(chest, Items.COAL) <= 10,
                "coal in the chest: " + count(chest, Items.COAL)));
        helper.startSequence().thenWaitUntil(() -> helper.assertValueEqual(count(chest, Items.COAL), 10, "coal kept"))
                .thenIdle(40).thenExecute(() -> {
                    helper.assertValueEqual(count(chest, Items.COAL), 10, "coal after waiting");
                    helper.assertValueEqual(drive.provider.amount(COAL), 90L, "coal left in the drive");
                    take(chest, Items.COAL, 4);
                }).thenWaitUntil(() -> helper.assertValueEqual(count(chest, Items.COAL), 10, "coal topped up"))
                .thenIdle(40).thenExecute(() -> {
                    helper.assertValueEqual(count(chest, Items.COAL), 10, "coal after topping up");
                    helper.assertValueEqual(drive.provider.amount(COAL), 86L, "coal left in the drive");
                }).thenSucceed();
    }

    /** A tag entry keeping 16 logs counts every log in the chest: oak and birch together stop at 16. */
    private static void regulatorTag(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        drive.provider.setDirect(OAK, 50);
        drive.provider.setDirect(BIRCH, 50);
        ChestBlockEntity chest = chest(helper, new ItemStack(Items.BIRCH_LOG, 6));
        PortBlockEntity port = port(helper, PortMode.EXPORT);
        port.filter().setItem(0, new ItemStack(Items.OAK_LOG));
        port.filter().setMatch(0, MatchMode.TAG, LOGS);
        port.filter().setKeep(0, 16);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(
                        count(chest, Items.OAK_LOG) + count(chest, Items.BIRCH_LOG), 16, "logs in the chest"))
                .thenIdle(40)
                .thenExecute(() -> helper.assertValueEqual(drive.provider.amount(OAK) + drive.provider.amount(BIRCH),
                        90L, "logs left in the drive"))
                .thenSucceed();
    }

    /**
     * More than 1,024 kinds appear in the network between two runs of a tag export port, the matching spruce log first,
     * so the index's log of appeared kinds starts over and loses it: the port, behind the log's start, walks the
     * network's kinds again and still exports the spruce.
     */
    private static void appearedOverflow(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        drive.provider.setDirect(OAK, 10);
        ChestBlockEntity chest = chest(helper);
        PortBlockEntity port = port(helper, PortMode.EXPORT);
        port.filter().setItem(0, new ItemStack(Items.OAK_LOG));
        port.filter().setMatch(0, MatchMode.TAG, LOGS);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(count(chest, Items.OAK_LOG), 10, "oak in the chest"))
                .thenExecute(() -> {
                    // In one tick, so the port cannot run in between.
                    drive.provider.setDirect(SPRUCE, 5);
                    int added = 0;
                    for (Item item : BuiltInRegistries.ITEM) {
                        if (added == OVERFLOW_KINDS) {
                            break;
                        }
                        if (!item.equals(Items.AIR) && !KeyMatch.inTag(item, ItemTags.LOGS)) {
                            drive.provider.setDirect(ItemKey.of(item), 1);
                            added++;
                        }
                    }
                    helper.assertValueEqual(added, OVERFLOW_KINDS, "kinds added");
                    NetworkState network = NetworkGameTests.state(helper, CONTROLLER);
                    helper.assertTrue(network.appearedStart() > 0, "the log did not start over");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(count(chest, Items.SPRUCE_LOG), 5, "spruce in the chest"))
                .thenSucceed();
    }

    /**
     * A kind a tag export port emptied out of the network (total 0, its id released after the index's release interval)
     * is exported again when it comes back.
     */
    private static void kindReturns(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        drive.provider.setDirect(OAK, 10);
        ChestBlockEntity chest = chest(helper);
        PortBlockEntity port = port(helper, PortMode.EXPORT);
        port.filter().setItem(0, new ItemStack(Items.BIRCH_LOG));
        port.filter().setMatch(0, MatchMode.TAG, LOGS);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(count(chest, Items.OAK_LOG), 10, "oak in the chest"))
                .thenExecute(() -> helper.assertValueEqual(
                        NetworkGameTests.state(helper, CONTROLLER).contents().amount(OAK), 0L, "oak in the network"))
                // Past the index's release interval (200 ticks), so the oak's id is released.
                .thenIdle(250).thenExecute(() -> drive.provider.setDirect(OAK, 7))
                .thenWaitUntil(() -> helper.assertValueEqual(count(chest, Items.OAK_LOG), 17, "oak in the chest again"))
                .thenSucceed();
    }

    /**
     * With at most 2 kinds tried per operation, a chest with no empty slot and room only for dirt, and stone, granite
     * and diorite ahead of dirt in the filter: kinds that cannot fit do not use up the cap, so the dirt is exported.
     */
    private static void capSkipsFullKinds(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        Item[] ahead = {Items.STONE, Items.GRANITE, Items.DIORITE};
        for (Item item : ahead) {
            drive.provider.setDirect(ItemKey.of(item), 64);
        }
        drive.provider.setDirect(DIRT, 64);
        ChestBlockEntity chest = chest(helper);
        for (int i = 0; i < chest.getContainerSize() - 1; i++) {
            chest.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        }
        chest.setItem(chest.getContainerSize() - 1, new ItemStack(Items.DIRT, 10));
        PortBlockEntity port = port(helper, PortMode.EXPORT);
        for (int i = 0; i < ahead.length; i++) {
            port.filter().setItem(i, new ItemStack(ahead[i]));
        }
        port.filter().setItem(ahead.length, new ItemStack(Items.DIRT));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(count(chest, Items.DIRT), 64, "dirt in the chest"))
                .thenExecute(() -> helper.assertValueEqual(drive.provider.amount(DIRT), 10L, "dirt in the drive"))
                .thenSucceed();
    }

    /**
     * Entry modes, tags, keep amounts and the order survive saving, the dropped item and the blueprint card; a port
     * saved before they existed loads its filter as exact, and a new item in a slot starts exact again.
     */
    private static void persistence(GameTestHelper helper) {
        PortBlockEntity port = port(helper, PortMode.EXPORT);
        port.filter().setItem(0, new ItemStack(Items.OAK_LOG));
        port.filter().setMatch(0, MatchMode.TAG, LOGS);
        port.filter().setItem(1, new ItemStack(Items.IRON_PICKAXE));
        port.filter().setMatch(1, MatchMode.FUZZY, null);
        port.filter().setKeep(1, 3);
        port.filter().setItem(2, new ItemStack(Items.STONE));
        port.filter().setMatch(2, MatchMode.MOD, null);
        port.filter().setKeep(2, 100_000);
        port.worker().setOrder(PortOrder.ROUND_ROBIN);

        CompoundTag saved = port.saveCustomOnly(helper.getLevel().registryAccess());
        PortBlockEntity loaded = new PortBlockEntity(port.getBlockPos(), port.getBlockState());
        loaded.loadCustomOnly(
                TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
        same(helper, loaded, "loaded");

        // The item a broken port drops, placed again.
        ItemStack item = new ItemStack(PortContent.PORT.item().get());
        item.applyComponents(port.collectComponents());
        Player placer = helper.makeMockPlayer(GameType.SURVIVAL);
        placer.setItemInHand(InteractionHand.MAIN_HAND, item);
        BlockPos target = helper.absolutePos(PORT.south(4));
        helper.assertTrue(
                item.useOn(new UseOnContext(helper.getLevel(), placer, InteractionHand.MAIN_HAND, item,
                        new BlockHitResult(target.getCenter(), Direction.UP, target, false))).consumesAction(),
                "placing the item");
        same(helper, helper.getBlockEntity(PORT.south(4), PortBlockEntity.class), "placed");

        // The blueprint card onto a fresh port.
        helper.setBlock(PORT.south(2), PortContent.PORT.get().defaultBlockState());
        PortBlockEntity pasted = helper.getBlockEntity(PORT.south(2), PortBlockEntity.class);
        DeviceSettings copied = port.copyableSettings();
        pasted.copyableSettings().paste().accept(copied.copy().get());
        same(helper, pasted, "pasted");

        // A save from before the entry settings: the filter alone.
        CompoundTag old = saved.copy();
        old.remove("filter_entries");
        old.remove("order");
        PortBlockEntity before = new PortBlockEntity(port.getBlockPos(), port.getBlockState());
        before.loadCustomOnly(
                TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), old));
        for (int i = 0; i < 3; i++) {
            helper.assertValueEqual(before.filter().mode(i), MatchMode.EXACT, "old save slot " + i);
            helper.assertValueEqual(before.filter().keep(i), 0, "old save keep " + i);
        }
        helper.assertValueEqual(before.worker().order(), PortOrder.FILTER, "old save order");

        port.filter().setItem(0, new ItemStack(Items.BIRCH_LOG));
        helper.assertValueEqual(port.filter().mode(0), MatchMode.EXACT, "a new item starts exact");
        helper.succeed();
    }

    private static void same(GameTestHelper helper, PortBlockEntity port, String what) {
        PortFilter filter = port.filter();
        helper.assertValueEqual(filter.mode(0), MatchMode.TAG, what + " slot 0 mode");
        helper.assertValueEqual(filter.tag(0), Optional.of(LOGS), what + " slot 0 tag");
        helper.assertValueEqual(filter.mode(1), MatchMode.FUZZY, what + " slot 1 mode");
        helper.assertValueEqual(filter.keep(1), 3, what + " slot 1 keep");
        helper.assertValueEqual(filter.mode(2), MatchMode.MOD, what + " slot 2 mode");
        helper.assertValueEqual(filter.keep(2), 100_000, what + " slot 2 keep");
        helper.assertValueEqual(port.worker().order(), PortOrder.ROUND_ROBIN, what + " order");
        helper.assertValueEqual(filter.settings().size(), 3, what + " settings");
        helper.assertTrue(filter.settings().contains(new PortEntrySettings(1, MatchMode.FUZZY, Optional.empty(), 3)),
                what + " slot 1 settings");
    }

    /**
     * A right click with an empty hand steps a slot's match mode (exact, fuzzy, each tag, mod) without clearing it, the
     * buttons step it and the order, a keep payload sets the amount, and the data slots carry all of it.
     */
    @SuppressWarnings("removal") // makeMockServerPlayerInLevel is the way to put a player on the player list
    private static void menu(GameTestHelper helper) {
        PortBlockEntity port = port(helper, PortMode.EXPORT);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos near = helper.absolutePos(PORT.west());
        player.snapTo(near.getX() + 0.5, near.getY(), near.getZ() + 0.5);
        PortMenu menu = PortMenu.forPort(1, player.getInventory(), port);
        player.containerMenu = menu;
        port.filter().setItem(0, new ItemStack(Items.OAK_LOG));
        menu.clicked(0, 1, ContainerInput.PICKUP, player);
        helper.assertTrue(port.filter().getItem(0).is(Items.OAK_LOG), "right click kept the item");
        helper.assertValueEqual(port.filter().mode(0), MatchMode.FUZZY, "after one right click");
        menu.clicked(0, 1, ContainerInput.PICKUP, player);
        helper.assertValueEqual(port.filter().mode(0), MatchMode.TAG, "after two right clicks");
        int tags = PortFilter.tagOptions(new ItemStack(Items.OAK_LOG)).size();
        helper.assertTrue(tags > 0, "oak logs have tags");
        helper.assertValueEqual(port.filter().tag(0),
                Optional.of(PortFilter.tagOptions(new ItemStack(Items.OAK_LOG)).get(0)), "the first tag");
        helper.assertTrue(menu.clickMenuButton(player, PortMenu.MATCH_BACK), "back button refused");
        helper.assertValueEqual(port.filter().mode(0), MatchMode.FUZZY, "after the back button");
        helper.assertTrue(menu.clickMenuButton(player, PortMenu.MATCH_BACK), "back button refused");
        helper.assertTrue(menu.clickMenuButton(player, PortMenu.MATCH_BACK), "back button refused");
        helper.assertValueEqual(port.filter().mode(0), MatchMode.MOD, "back from exact wraps to mod");
        helper.assertTrue(PortPayloads.keep(player, menu.containerId, 0, 64), "keep payload refused");
        helper.assertValueEqual(port.filter().keep(0), 64, "keep amount");
        helper.assertTrue(!PortPayloads.keep(player, menu.containerId + 1, 0, 5), "keep for another menu");
        helper.assertTrue(menu.clickMenuButton(player, PortMenu.CYCLE_ORDER), "order button refused");
        helper.assertValueEqual(port.worker().order(), PortOrder.ROUND_ROBIN, "order after the button");
        helper.assertTrue(PortPayloads.ghost(player, menu.containerId, 3, new ItemStack(Items.DIRT, 5)),
                "ghost payload refused");
        helper.assertValueEqual(port.filter().getItem(3).getCount(), 1, "a ghost holds one");

        int[][] sent = new int[1][];
        menu.setSynchronizer(new ContainerSynchronizer() {
            @Override
            public void sendInitialData(AbstractContainerMenu container, List<ItemStack> slotItems, ItemStack carried,
                    int[] dataSlots) {
                sent[0] = dataSlots.clone();
            }

            @Override
            public void sendSlotChange(AbstractContainerMenu container, int slotIndex, ItemStack itemStack) {
            }

            @Override
            public void sendCarriedChange(AbstractContainerMenu container, ItemStack itemStack) {
            }

            @Override
            public void sendDataChange(AbstractContainerMenu container, int id, int value) {
            }

            @Override
            public RemoteSlot createSlot() {
                return RemoteSlot.PLACEHOLDER;
            }
        });
        int[] values = sent[0];
        helper.assertTrue(values != null, "no initial data");
        RegistryFriendlyByteBuf open = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess());
        open.writeBlockPos(port.getBlockPos());
        open.writeVarInt(0);
        open.writeVarInt(1);
        PortMenu client = PortMenu.fromNetwork(2, player.getInventory(), open);
        for (int id = 0; values != null && id < values.length; id++) {
            client.setData(id, values[id]);
        }
        helper.assertValueEqual(client.match(0), MatchMode.MOD, "synced mode");
        helper.assertValueEqual(client.keep(0), 64, "synced keep");
        helper.assertValueEqual(client.order(), PortOrder.ROUND_ROBIN, "synced order");
        helper.succeed();
    }

    /** A powered controller with a test drive behind it and the cable towards the port. */
    private static TestStorage.TestDevice network(GameTestHelper helper) {
        NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, CONTROLLER, POWER);
        TestStorage.TestDevice drive = NetworkGameTests.device(helper, DRIVE);
        NetworkGameTests.cables(helper, 2, 2, CableContent.STORAGE_CABLE_TIER_1.type().get());
        return drive;
    }

    private static PortBlockEntity port(GameTestHelper helper, PortMode mode) {
        helper.setBlock(PORT, PortContent.PORT.get().defaultBlockState().setValue(PortBlock.FACING, Direction.EAST)
                .setValue(PortBlock.MODE, mode));
        return helper.getBlockEntity(PORT, PortBlockEntity.class);
    }

    private static ChestBlockEntity chest(GameTestHelper helper, ItemStack... stacks) {
        helper.setBlock(INVENTORY, Blocks.CHEST);
        ChestBlockEntity chest = helper.getBlockEntity(INVENTORY, ChestBlockEntity.class);
        for (int i = 0; i < stacks.length; i++) {
            chest.setItem(i, stacks[i]);
        }
        return chest;
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

    /** Takes {@code amount} of {@code item} out of the chest, as a hopper below it would. */
    private static void take(ChestBlockEntity chest, Item item, int amount) {
        int left = amount;
        for (int i = 0; i < chest.getContainerSize() && left > 0; i++) {
            ItemStack stack = chest.getItem(i);
            if (stack.is(item)) {
                int taken = Math.min(left, stack.getCount());
                stack.shrink(taken);
                chest.setItem(i, stack);
                left -= taken;
            }
        }
        chest.setChanged();
    }
}
