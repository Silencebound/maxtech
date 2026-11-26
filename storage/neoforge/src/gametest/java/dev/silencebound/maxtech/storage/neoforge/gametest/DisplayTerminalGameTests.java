package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.display.DisplayContent;
import dev.silencebound.maxtech.storage.display.DisplayPanelBlockEntity;
import dev.silencebound.maxtech.storage.display.DisplayPart;
import dev.silencebound.maxtech.storage.display.DisplaySettings;
import dev.silencebound.maxtech.storage.display.DisplaySourceKind;
import dev.silencebound.maxtech.storage.display.DisplayTerminal;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Displays as small terminals (M7C item 13): a right-click with the shown item stores it (a quick second one stores the
 * rest of the inventory's), a left-click takes one and a sneaking one a stack. Exact amounts, a full network or
 * inventory moving nothing, access and reach, the cooldown, other screens and the cable part.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class DisplayTerminalGameTests {
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey DIRT = ItemKey.of(Items.DIRT);
    private static final DisplaySettings SHOWS_COBBLE = DisplaySettings.DEFAULT.withSource(DisplaySourceKind.ITEM_COUNT)
            .withItem(COBBLE);
    /** Ticks between two clicks of a test, past the cooldown. */
    private static final int PAUSE = DisplayTerminal.COOLDOWN_TICKS + 1;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);
    private static final List<String> NAMES = new ArrayList<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        test("display_click_store_take_exact", DisplayTerminalGameTests::storeTakeExact);
        test("display_click_full", DisplayTerminalGameTests::full);
        test("display_click_access_refused", DisplayTerminalGameTests::accessRefused);
        test("display_click_cooldown", DisplayTerminalGameTests::cooldown);
        test("display_click_other_screens", DisplayTerminalGameTests::otherScreens);
        test("display_click_part", DisplayTerminalGameTests::part);
        test("display_click_components", DisplayTerminalGameTests::components);
        test("display_click_double_click_expires", DisplayTerminalGameTests::doubleClickExpires);
        test("display_click_creative_store", DisplayTerminalGameTests::creativeStore);
        test("display_click_logout_forgets", DisplayTerminalGameTests::logoutForgets);
    }

    private DisplayTerminalGameTests() {
    }

    private static void test(String name, Consumer<GameTestHelper> function) {
        NAMES.add(name);
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(function));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("display_terminal"), StorageTestEnvironment.plain());
        for (String name : NAMES) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, StorageTests.SMALL, 200, 0, true)));
        }
    }

    /**
     * Twenty in hand stored, then a quick empty-handed click stores the 45 in the inventory; a click takes one, a
     * sneaking click a stack of 64, a click on an empty network nothing. The inventory and the network hold 65 between
     * them after every step.
     */
    private static void storeTakeExact(GameTestHelper helper) {
        Setup setup = setup(helper, SHOWS_COBBLE);
        setup.then(helper, () -> {
            ServerPlayer owner = setup.owner;
            owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COBBLESTONE, 20));
            owner.getInventory().setItem(9, new ItemStack(Items.COBBLESTONE, 30));
            owner.getInventory().setItem(20, new ItemStack(Items.COBBLESTONE, 15));
            helper.assertTrue(use(helper, owner).consumesAction(), "the store click was not taken");
            helper.assertValueEqual(stored(setup), 20L, "stored from the hand");
            helper.assertTrue(owner.getMainHandItem().isEmpty(), "the hand still holds cobblestone");
            assertTotal(helper, setup, "after the store");
        }).thenExecuteAfter(2, () -> {
            helper.assertTrue(useEmpty(helper, setup.owner).consumesAction(), "the double-click");
            helper.assertValueEqual(stored(setup), 65L, "stored after the double-click");
            helper.assertValueEqual(carried(setup.owner), 0, "cobblestone left in the inventory");
        }).thenExecuteAfter(PAUSE, () -> {
            attack(helper, setup.owner, false);
            helper.assertValueEqual(carried(setup.owner), 1, "taken by a click");
            assertTotal(helper, setup, "after a click");
        }).thenExecuteAfter(PAUSE, () -> {
            attack(helper, setup.owner, true);
            helper.assertValueEqual(carried(setup.owner), 65, "taken by a sneaking click");
            helper.assertValueEqual(stored(setup), 0L, "left in the network");
        }).thenExecuteAfter(PAUSE, () -> {
            attack(helper, setup.owner, true);
            helper.assertValueEqual(carried(setup.owner), 65, "taken from an empty network");
            assertTotal(helper, setup, "at the end");
        }).thenSucceed();
    }

    /**
     * A full network takes nothing of the held stack, which stays whole; a full inventory takes nothing from the
     * network, which keeps its count.
     */
    private static void full(GameTestHelper helper) {
        Setup setup = setup(helper, SHOWS_COBBLE);
        setup.then(helper, () -> {
            setup.device.provider.setDirect(DIRT, TestStorage.DEVICE_CAPACITY);
            setup.owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COBBLESTONE, 10));
            helper.assertTrue(use(helper, setup.owner) instanceof InteractionResult.TryEmptyHandInteraction,
                    "a refused store did not fall through to the settings");
            helper.assertValueEqual(setup.owner.getMainHandItem().getCount(), 10, "held after storing into a full net");
            helper.assertValueEqual(stored(setup), 0L, "stored into a full network");
            helper.assertTrue(!DisplayTerminal.storeAll(setup.owner, setup.panel, SHOWS_COBBLE,
                    helper.absolutePos(NetworkGameTests.at(3))), "a refused store armed the double-click");
        }).thenExecuteAfter(PAUSE, () -> {
            setup.device.provider.setDirect(DIRT, 0);
            setup.device.provider.setDirect(COBBLE, 5);
            Inventory inventory = setup.owner.getInventory();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                inventory.setItem(i, new ItemStack(Items.STONE, 64));
            }
            attack(helper, setup.owner, true);
            helper.assertValueEqual(stored(setup), 5L, "left after taking into a full inventory");
            helper.assertValueEqual(carried(setup.owner), 0, "taken into a full inventory");
        }).thenSucceed();
    }

    /** A stranger can neither store nor take, and the owner cannot from out of reach. */
    private static void accessRefused(GameTestHelper helper) {
        Setup setup = setup(helper, SHOWS_COBBLE);
        setup.then(helper, () -> {
            setup.device.provider.setDirect(COBBLE, 50);
            ServerPlayer stranger = near(helper, NetworkGameTests.player(helper.getLevel(), "display_stranger"));
            stranger.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COBBLESTONE, 10));
            helper.assertTrue(use(helper, stranger) instanceof InteractionResult.TryEmptyHandInteraction,
                    "the stranger's refused store did not fall through");
            attack(helper, stranger, true);
            helper.assertValueEqual(stranger.getMainHandItem().getCount(), 10, "the stranger's stack");
            helper.assertValueEqual(carried(stranger), 10, "the stranger's cobblestone");
            helper.assertValueEqual(stored(setup), 50L, "stored after the stranger");

            ServerPlayer far = setup.owner;
            far.setPos(helper.absoluteVec(NetworkGameTests.at(3).getCenter()).add(0, 0, -20));
            far.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COBBLESTONE, 10));
            use(helper, far);
            attack(helper, far, true);
            helper.assertValueEqual(carried(far), 10, "the far owner's cobblestone");
            helper.assertValueEqual(stored(setup), 50L, "stored after the far owner");
        }).thenSucceed();
    }

    /** Two takes in one tick take one item; two stores in one tick store once. */
    private static void cooldown(GameTestHelper helper) {
        Setup setup = setup(helper, SHOWS_COBBLE);
        setup.then(helper, () -> {
            setup.device.provider.setDirect(COBBLE, 50);
            attack(helper, setup.owner, false);
            attack(helper, setup.owner, false);
            helper.assertValueEqual(carried(setup.owner), 1, "taken by two clicks in one tick");
        }).thenExecuteAfter(PAUSE, () -> {
            setup.owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COBBLESTONE, 5));
            use(helper, setup.owner);
            setup.owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COBBLESTONE, 7));
            use(helper, setup.owner);
            helper.assertValueEqual(stored(setup), 54L, "stored by two clicks in one tick");
            helper.assertValueEqual(setup.owner.getMainHandItem().getCount(), 7, "the second stack");
        }).thenSucceed();
    }

    /**
     * A screen showing power, or another item than the one held, keeps its usual clicks: the item use falls through and
     * a left-click takes nothing.
     */
    private static void otherScreens(GameTestHelper helper) {
        Setup setup = setup(helper, DisplaySettings.DEFAULT.withItem(COBBLE));
        setup.then(helper, () -> {
            setup.device.provider.setDirect(COBBLE, 50);
            setup.owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COBBLESTONE, 10));
            // Every source but the item count, each with cobblestone still set as its item.
            for (DisplaySourceKind source : DisplaySourceKind.values()) {
                if (source == DisplaySourceKind.ITEM_COUNT) {
                    continue;
                }
                setup.panel.applyToWall(SHOWS_COBBLE.withSource(source));
                helper.assertTrue(use(helper, setup.owner) instanceof InteractionResult.TryEmptyHandInteraction,
                        "a " + source.getSerializedName() + " screen took the item use");
                attack(helper, setup.owner, false);
                helper.assertValueEqual(carried(setup.owner), 10,
                        "cobblestone after a " + source.getSerializedName() + " screen");
            }
            setup.panel.applyToWall(SHOWS_COBBLE.withItem(DIRT));
            helper.assertTrue(use(helper, setup.owner) instanceof InteractionResult.TryEmptyHandInteraction,
                    "a dirt screen took cobblestone");
            helper.assertValueEqual(setup.owner.getMainHandItem().getCount(), 10, "the held stack");
            helper.assertValueEqual(stored(setup), 50L, "stored");
        }).thenSucceed();
    }

    /** The display part on a cable stores and takes the same way, through the cable block. */
    private static void part(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        ServerPlayer owner = owned(helper, controller);
        NetworkGameTests.cables(helper, 2, 2, CableContent.STORAGE_CABLE_TIER_1.type().get());
        TestStorage.TestDevice device = NetworkGameTests.device(helper, NetworkGameTests.at(3));
        ItemStack item = new ItemStack(DisplayContent.PANEL_PART_ITEM.get());
        item.set(ModDataComponents.DISPLAY_SETTINGS.get(), SHOWS_COBBLE);
        helper.assertTrue(PartGameTests
                .usePart(helper, helper.makeMockPlayer(GameType.SURVIVAL), 2, Direction.NORTH, item).consumesAction(),
                "the part was not placed");
        BlockPos abs = helper.absolutePos(NetworkGameTests.at(2));
        // North of the cable, looking south at the part on its north face.
        owner.setPos(abs.getCenter().x, abs.getCenter().y - owner.getEyeHeight(), abs.getZ() - 1.5);
        owner.setYRot(0);
        owner.setXRot(0);
        helper.startSequence().thenWaitUntil(() -> {
            if (!(PartGameTests.part(helper, PartGameTests.parts(helper, 2), Direction.NORTH) instanceof DisplayPart p)
                    || p.deviceLink().network() == null) {
                throw helper.assertionException(Component.literal("the part is in no network"));
            }
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, controller, 10_000);
            owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COBBLESTONE, 12));
            BlockState cable = helper.getLevel().getBlockState(abs);
            // The part's plate on the block's north face.
            Vec3 face = new Vec3(abs.getCenter().x, abs.getCenter().y, abs.getZ());
            helper.assertTrue(
                    cable.useItemOn(owner.getMainHandItem(), helper.getLevel(), owner, InteractionHand.MAIN_HAND,
                            new BlockHitResult(face, Direction.NORTH, abs, false)).consumesAction(),
                    "the part's store click");
            helper.assertValueEqual(device.provider.amount(COBBLE), 12L, "stored through the part");
            helper.assertTrue(owner.getMainHandItem().isEmpty(), "the hand after storing");
        }).thenExecuteAfter(PAUSE, () -> {
            helper.getLevel().getBlockState(abs).attack(helper.getLevel(), abs, owner);
            helper.assertValueEqual(carried(owner), 1, "taken through the part");
            helper.assertValueEqual(device.provider.amount(COBBLE), 11L, "left after taking through the part");
        }).thenSucceed();
    }

    /**
     * A screen showing a renamed cobblestone takes only that variant: plain cobblestone falls through, the renamed
     * stack is stored as itself and comes back with its name.
     */
    private static void components(GameTestHelper helper) {
        ItemStack named = new ItemStack(Items.COBBLESTONE, 5);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("quarry"));
        ItemKey namedKey = ItemKey.of(named);
        Setup setup = setup(helper, SHOWS_COBBLE.withItem(namedKey));
        setup.then(helper, () -> {
            setup.owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COBBLESTONE, 5));
            helper.assertTrue(use(helper, setup.owner) instanceof InteractionResult.TryEmptyHandInteraction,
                    "plain cobblestone was taken by a renamed screen");
            setup.owner.setItemInHand(InteractionHand.MAIN_HAND, named.copy());
            helper.assertTrue(use(helper, setup.owner).consumesAction(), "the renamed store");
            helper.assertValueEqual(setup.device.provider.amount(namedKey), 5L, "renamed stored");
            helper.assertValueEqual(stored(setup), 0L, "plain stored");
        }).thenExecuteAfter(PAUSE, () -> {
            attack(helper, setup.owner, false);
            helper.assertValueEqual(setup.device.provider.amount(namedKey), 4L, "renamed left");
            int found = 0;
            for (ItemStack stack : setup.owner.getInventory().getNonEquipmentItems()) {
                if (stack.is(Items.COBBLESTONE) && ItemKey.of(stack).equals(namedKey)) {
                    found += stack.getCount();
                }
            }
            helper.assertValueEqual(found, 1, "renamed cobblestone taken");
        }).thenSucceed();
    }

    /** An empty-handed click later than the double-click window stores nothing more. */
    private static void doubleClickExpires(GameTestHelper helper) {
        Setup setup = setup(helper, SHOWS_COBBLE);
        BlockPos abs = helper.absolutePos(NetworkGameTests.at(3));
        setup.then(helper, () -> {
            setup.owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COBBLESTONE, 4));
            setup.owner.getInventory().setItem(9, new ItemStack(Items.COBBLESTONE, 30));
            use(helper, setup.owner);
            helper.assertValueEqual(stored(setup), 4L, "stored from the hand");
        }).thenExecuteAfter(DisplayTerminal.DOUBLE_CLICK_TICKS + 2, () -> {
            helper.assertTrue(!DisplayTerminal.storeAll(setup.owner, setup.panel, SHOWS_COBBLE, abs),
                    "a late click stored all");
            helper.assertValueEqual(stored(setup), 4L, "stored after the late click");
            helper.assertValueEqual(carried(setup.owner), 30, "left in the inventory");
        }).thenSucceed();
    }

    /** A creative player's store moves the held stack like anyone's: no copy stays in the hand. */
    private static void creativeStore(GameTestHelper helper) {
        Setup setup = setup(helper, SHOWS_COBBLE);
        setup.then(helper, () -> {
            setup.owner.getAbilities().instabuild = true;
            setup.owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COBBLESTONE, 10));
            helper.assertTrue(use(helper, setup.owner).consumesAction(), "the creative store");
            helper.assertValueEqual(stored(setup), 10L, "stored by a creative player");
            helper.assertValueEqual(carried(setup.owner), 0, "cobblestone left with a creative player");
        }).thenSucceed();
    }

    /** Logging out forgets the player's clicks, so the memory does not grow with every player ever seen. */
    private static void logoutForgets(GameTestHelper helper) {
        Setup setup = setup(helper, SHOWS_COBBLE);
        setup.then(helper, () -> {
            setup.owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COBBLESTONE, 3));
            use(helper, setup.owner);
            helper.assertTrue(DisplayTerminal.remembers(setup.owner.getUUID()), "the click was not remembered");
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(setup.owner));
            helper.assertTrue(!DisplayTerminal.remembers(setup.owner.getUUID()), "remembered after logging out");
        }).thenSucceed();
    }

    private static InteractionResult use(GameTestHelper helper, ServerPlayer player) {
        BlockPos abs = helper.absolutePos(NetworkGameTests.at(3));
        return helper.getLevel().getBlockState(abs).useItemOn(player.getMainHandItem(), helper.getLevel(), player,
                InteractionHand.MAIN_HAND, new BlockHitResult(abs.getCenter(), Direction.NORTH, abs, false));
    }

    private static InteractionResult useEmpty(GameTestHelper helper, ServerPlayer player) {
        BlockPos abs = helper.absolutePos(NetworkGameTests.at(3));
        return helper.getLevel().getBlockState(abs).useWithoutItem(helper.getLevel(), player,
                new BlockHitResult(abs.getCenter(), Direction.NORTH, abs, false));
    }

    private static void attack(GameTestHelper helper, ServerPlayer player, boolean sneaking) {
        BlockPos abs = helper.absolutePos(NetworkGameTests.at(3));
        player.setShiftKeyDown(sneaking);
        helper.getLevel().getBlockState(abs).attack(helper.getLevel(), abs, player);
        player.setShiftKeyDown(false);
    }

    private static long stored(Setup setup) {
        return setup.device.provider.amount(COBBLE);
    }

    /** Cobblestone anywhere in the player's inventory. */
    private static int carried(ServerPlayer player) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.is(Items.COBBLESTONE)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static void assertTotal(GameTestHelper helper, Setup setup, String when) {
        helper.assertValueEqual(carried(setup.owner) + stored(setup), 65L, "cobblestone " + when);
    }

    /** {@code player} standing just north of the panel, within reach. */
    private static ServerPlayer near(GameTestHelper helper, ServerPlayer player) {
        player.setPos(helper.absoluteVec(NetworkGameTests.at(3).getCenter()).add(0, -1, -1.5));
        return player;
    }

    /** A survival player who placed the controller at {@code controller}, so owns its network. */
    private static ServerPlayer owned(GameTestHelper helper, BlockPos controller) {
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "display_owner");
        BlockState state = helper.getBlockState(controller);
        state.getBlock().setPlacedBy(helper.getLevel(), helper.absolutePos(controller), state, owner,
                new ItemStack(ControllerContent.CONTROLLER.item().get()));
        return owner;
    }

    /**
     * A controller at x 1 placed by the owner, a test storage device at x 2 and, at x 3, a panel facing north with
     * {@code settings}; the owner stands next to the panel.
     */
    private static Setup setup(GameTestHelper helper, DisplaySettings settings) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        ServerPlayer owner = near(helper, owned(helper, controller));
        TestStorage.TestDevice device = NetworkGameTests.device(helper, NetworkGameTests.at(2));
        DisplayPanelBlockEntity panel = DisplayGameTests.panel(helper, NetworkGameTests.at(3), Direction.NORTH);
        panel.applyToWall(settings);
        return new Setup(controller, device, panel, owner);
    }

    private record Setup(BlockPos controller, TestStorage.TestDevice device, DisplayPanelBlockEntity panel,
            ServerPlayer owner) {
        /** Waits for the panel's network with its owner, powers it, then runs {@code start}. */
        GameTestSequence then(GameTestHelper helper, Runnable start) {
            return helper.startSequence().thenWaitUntil(() -> {
                NetworkState network = panel.network();
                helper.assertTrue(network != null && network.owner().isOwner(owner.getUUID()), "no owned network");
            }).thenExecute(() -> {
                NetworkGameTests.power(helper, controller, 10_000);
                start.run();
            });
        }
    }
}
