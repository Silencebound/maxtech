package dev.silencebound.maxtech.farms.neoforge.gametest;

import dev.silencebound.maxtech.farms.MaxtechFarms;
import dev.silencebound.maxtech.farms.hub.HubMenu;
import dev.silencebound.maxtech.farms.hub.HubStatus;
import dev.silencebound.maxtech.farms.hub.HubStorage;
import dev.silencebound.maxtech.farms.hub.LabHubBlock;
import dev.silencebound.maxtech.farms.hub.LabHubBlockEntity;
import dev.silencebound.maxtech.farms.hub.LabHubBlockEntity.LabLink;
import dev.silencebound.maxtech.farms.lab.LabTubes;
import dev.silencebound.maxtech.farms.registry.FarmsContent;
import dev.silencebound.maxtech.farms.sampler.SamplerItem;
import dev.silencebound.maxtech.lib.api.conduit.Conduits;
import dev.silencebound.maxtech.lib.api.menu.MenuTesting;
import dev.silencebound.maxtech.lib.api.menu.MenuValueView;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * The lab hub's screen through Lib's {@link MenuTesting}: it opens over the hub's slots, the tank, energy, flame,
 * sampler charge and status reach a client menu, the status line takes the first state that applies, shift-clicks go to
 * the fuel and sampler slots but never the output, the output only gives, a carried bucket fills from and empties into
 * the tank, and an empty hand opens the screen while sneaking with a sampler inside takes it out instead.
 */
@EventBusSubscriber(modid = MaxtechFarms.MOD_ID)
public final class LabHubMenuGameTests {
    private static final Identifier STRUCTURE = MaxtechFarms.id("empty_7x2x3");
    private static final BlockPos HUB = new BlockPos(1, 1, 1);
    /** The menu's slots: the hub's, then the player's main inventory and hotbar. */
    private static final int MENU_SLOTS = HubStorage.SIZE + 36;
    private static final Map<String, Consumer<GameTestHelper>> TESTS = Map.ofEntries(
            Map.entry("hub_menu_opens", LabHubMenuGameTests::opens),
            Map.entry("hub_menu_values_sync", LabHubMenuGameTests::valuesSync),
            Map.entry("hub_menu_status_precedence", LabHubMenuGameTests::statusPrecedence),
            Map.entry("hub_menu_shift_click", LabHubMenuGameTests::shiftClick),
            Map.entry("hub_menu_output_take_only", LabHubMenuGameTests::outputTakeOnly),
            Map.entry("hub_menu_bucket_click", LabHubMenuGameTests::bucketClick),
            Map.entry("hub_hand_opens_menu", LabHubMenuGameTests::handOpensMenu));

    private LabHubMenuGameTests() {
    }

    /** The test functions, registered on the mod bus; the GameTest source set only exists in dev runs. */
    @SubscribeEvent
    static void registerFunctions(RegisterEvent event) {
        for (Map.Entry<String, Consumer<GameTestHelper>> test : TESTS.entrySet()) {
            event.register(Registries.TEST_FUNCTION, MaxtechFarms.id(test.getKey()), test::getValue);
        }
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechFarms.id("lab_hub_menu"));
        for (String name : TESTS.keySet()) {
            event.registerTest(MaxtechFarms.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechFarms.id(name)),
                            new TestData<>(environment, STRUCTURE, 200, 0, true)));
        }
    }

    /** What a test does with the open menu, all within one tick, so the hub's task cannot run in between. */
    private interface Body {
        void run(ServerPlayer player, MenuTesting.Session<LabHubBlockEntity> session);
    }

    /** Opens the hub's screen over {@code hub} for a mock server player and runs {@code body}. */
    @SuppressWarnings("removal") // GameTestHelper's only way to a ServerPlayer with a connection; no replacement yet
    private static void withMenu(GameTestHelper helper, LabHubBlockEntity hub, Body body) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try (MenuTesting.Session<LabHubBlockEntity> session = MenuTesting.open(HubMenu.MENU, player, hub)) {
            body.run(player, session);
        } finally {
            helper.getLevel().getServer().getPlayerList().remove(player);
        }
    }

    /** The layout passes Lib's checks, and the menu shows the hub's own slots in the hub's order. */
    private static void opens(GameTestHelper helper) {
        helper.assertValueEqual(HubMenu.MENU.problems(), List.of(), "layout problems");
        LabHubBlockEntity hub = hub(helper, HUB);
        withMenu(helper, hub, (player, session) -> {
            AbstractContainerMenu menu = session.menu();
            helper.assertValueEqual(menu.slots.size(), MENU_SLOTS, "slots");
            hub.storage().slots().set(HubStorage.FUEL, new ItemStack(Items.COAL, 3));
            hub.storage().slots().set(HubStorage.OUTPUT_FIRST + 26, new ItemStack(Items.BONE, 2));
            hub.storage().slots().set(HubStorage.SAMPLER, new ItemStack(FarmsContent.SAMPLER.get()));
            helper.assertTrue(ItemStack.matches(menu.getSlot(HubStorage.FUEL).getItem(), new ItemStack(Items.COAL, 3)),
                    "fuel slot: " + menu.getSlot(HubStorage.FUEL).getItem());
            helper.assertTrue(menu.getSlot(HubStorage.OUTPUT_FIRST + 26).getItem().is(Items.BONE), "last output");
            helper.assertTrue(menu.getSlot(HubStorage.SAMPLER).getItem().is(FarmsContent.SAMPLER.get()), "sampler");
        });
        helper.succeed();
    }

    /** Tank (with the hub's own capacity), MU, the flame and the sampler's charge reach a client menu. */
    private static void valuesSync(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper, HUB);
        withMenu(helper, hub, (player, session) -> {
            hub.storage().tank().set(FluidKey.of(Fluids.WATER), 3000);
            hub.energy().setStored(5000);
            hub.burner().load(400, 1600);
            ItemStack sampler = new ItemStack(FarmsContent.SAMPLER.get());
            SamplerItem.setCharge(sampler, SamplerItem.capacity() / 4);
            hub.storage().slots().set(HubStorage.SAMPLER, sampler);
            session.tick();
            MenuValueView view = session.clientValues();
            helper.assertValueEqual(view.amount(HubMenu.TANK), 3000L, "tank");
            helper.assertValueEqual(view.capacity(HubMenu.TANK), hub.storage().tank().capacity(), "tank capacity");
            helper.assertTrue(view.fluid(HubMenu.TANK).map(key -> key.fluid().isSame(Fluids.WATER)).orElse(false),
                    "water in the tank");
            helper.assertValueEqual(view.stored(HubMenu.ENERGY), 5000L, "MU");
            helper.assertValueEqual(view.capacity(HubMenu.ENERGY), hub.energy().capacity(), "MU capacity");
            helper.assertValueEqual(view.current(HubMenu.BURN), 400L, "burn left");
            helper.assertValueEqual(view.total(HubMenu.BURN), 1600L, "burn total");
            helper.assertValueEqual(view.get(HubMenu.SAMPLER_PERCENT), 25, "sampler charge");
            SamplerItem.setCharge(sampler, SamplerItem.capacity());
            hub.storage().slots().set(HubStorage.SAMPLER, sampler);
            session.tick();
            helper.assertValueEqual(session.clientValues().get(HubMenu.SAMPLER_PERCENT), 100, "full sampler");
            hub.storage().slots().set(HubStorage.SAMPLER, ItemStack.EMPTY);
            session.tick();
            helper.assertValueEqual(session.clientValues().get(HubMenu.SAMPLER_PERCENT), 0, "no sampler");
        });
        helper.succeed();
    }

    /**
     * Each state in turn, each time with every later one also true: no tubes beats an empty tank and no power, an empty
     * tank beats no power, a burning flame counts as power, and a refused hub shows that above all.
     */
    private static void statusPrecedence(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper, HUB);
        helper.assertValueEqual(status(helper, hub), HubStatus.NOT_CONNECTED, "no tubes, empty, no power");
        hub.storage().tank().set(FluidKey.of(Fluids.WATER), 1000);
        hub.energy().setStored(100);
        helper.assertValueEqual(status(helper, hub), HubStatus.NOT_CONNECTED, "no tubes, otherwise running");
        hub.storage().tank().set(FluidKey.of(Fluids.WATER), 0);
        hub.energy().setStored(0);
        tube(helper, new BlockPos(2, 1, 1));
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(hub.labLink() == LabLink.ACTIVE, "hub runs"))
                .thenExecute(() -> {
                    helper.assertValueEqual(status(helper, hub), HubStatus.TANK_EMPTY, "empty and no power");
                    hub.energy().setStored(100);
                    helper.assertValueEqual(status(helper, hub), HubStatus.TANK_EMPTY, "empty with power");
                    hub.storage().tank().set(FluidKey.of(Fluids.WATER), 1000);
                    hub.energy().setStored(0);
                    helper.assertValueEqual(status(helper, hub), HubStatus.NO_POWER, "nutrient, no power");
                    hub.burner().load(100, 1600);
                    helper.assertValueEqual(status(helper, hub), HubStatus.RUNNING, "a flame and no MU");
                    hub.burner().load(0, 0);
                    hub.energy().setStored(100);
                    helper.assertValueEqual(status(helper, hub), HubStatus.RUNNING, "MU and no flame");
                    hub(helper, new BlockPos(3, 1, 1));
                }).thenWaitUntil(() -> helper.assertTrue(refused(helper).labLink() == LabLink.REFUSED, "refused"))
                .thenExecute(() -> {
                    LabHubBlockEntity second = refused(helper);
                    helper.assertValueEqual(status(helper, second), HubStatus.REFUSED, "refused, empty, no power");
                    second.storage().tank().set(FluidKey.of(Fluids.WATER), 1000);
                    second.energy().setStored(100);
                    helper.assertValueEqual(status(helper, second), HubStatus.REFUSED, "refused, otherwise running");
                    helper.assertValueEqual(status(helper, hub), HubStatus.RUNNING, "the first hub still runs");
                }).thenSucceed();
    }

    private static LabHubBlockEntity refused(GameTestHelper helper) {
        return helper.getBlockEntity(new BlockPos(3, 1, 1), LabHubBlockEntity.class);
    }

    /** The status a client menu shows after one tick with the hub as it is now. */
    private static HubStatus status(GameTestHelper helper, LabHubBlockEntity hub) {
        HubStatus[] shown = new HubStatus[1];
        withMenu(helper, hub, (player, session) -> {
            session.tick();
            shown[0] = session.clientValues().get(HubMenu.STATUS);
        });
        return shown[0];
    }

    /** Coal goes to the fuel slot, a sampler to its slot; a bone stays with the player, never in the output. */
    private static void shiftClick(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper, HUB);
        withMenu(helper, hub, (player, session) -> {
            player.getInventory().setItem(9, new ItemStack(Items.COAL, 5));
            player.getInventory().setItem(10, new ItemStack(FarmsContent.SAMPLER.get()));
            player.getInventory().setItem(11, new ItemStack(Items.BONE, 4));
            player.getInventory().setItem(12, new ItemStack(Items.BUCKET));
            for (int slot = 0; slot < 4; slot++) {
                session.shiftClickPlayerSlot(slot);
            }
            helper.assertTrue(
                    ItemStack.matches(hub.storage().slots().stack(HubStorage.FUEL), new ItemStack(Items.COAL, 5)),
                    "coal in the fuel slot");
            helper.assertTrue(hub.storage().sampler().is(FarmsContent.SAMPLER.get()), "sampler in its slot");
            for (int slot = HubStorage.OUTPUT_FIRST; slot < HubStorage.SAMPLER; slot++) {
                helper.assertTrue(hub.storage().slots().stack(slot).isEmpty(), "shift-clicked into output " + slot);
            }
            helper.assertTrue(player.getInventory().contains(new ItemStack(Items.BONE)), "bone with the player");
            helper.assertTrue(player.getInventory().contains(new ItemStack(Items.BUCKET)), "bucket with the player");
            session.shiftClick(HubMenu.SAMPLER, 0);
            helper.assertTrue(hub.storage().sampler().isEmpty(), "sampler shift-clicked out");
            helper.assertTrue(player.getInventory().contains(new ItemStack(FarmsContent.SAMPLER.get())),
                    "sampler back with the player");
        });
        helper.succeed();
    }

    /** The output gives to a click and a shift-click but takes nothing put on it. */
    private static void outputTakeOnly(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper, HUB);
        withMenu(helper, hub, (player, session) -> {
            AbstractContainerMenu menu = session.menu();
            int output = HubStorage.OUTPUT_FIRST + 4;
            hub.storage().slots().set(output, new ItemStack(Items.BONE, 4));
            Slot slot = menu.getSlot(output);
            helper.assertFalse(slot.mayPlace(new ItemStack(Items.BONE)), "output takes items");
            menu.setCarried(new ItemStack(Items.COAL, 3));
            menu.clicked(output, 0, ContainerInput.PICKUP, player);
            helper.assertValueEqual(hub.storage().slots().stack(output).getCount(), 4, "output after a click");
            helper.assertTrue(ItemStack.matches(menu.getCarried(), new ItemStack(Items.COAL, 3)), "carried coal");
            menu.setCarried(new ItemStack(Items.COAL, 3));
            menu.clicked(HubStorage.OUTPUT_FIRST, 0, ContainerInput.PICKUP, player);
            helper.assertTrue(hub.storage().slots().stack(HubStorage.OUTPUT_FIRST).isEmpty(), "coal into output");
            menu.setCarried(ItemStack.EMPTY);
            session.shiftClick(HubMenu.OUTPUT, 4);
            helper.assertTrue(hub.storage().slots().stack(output).isEmpty(), "output after the shift-click");
            helper.assertTrue(player.getInventory().contains(new ItemStack(Items.BONE)), "bones with the player");
        });
        helper.succeed();
    }

    /** A carried bucket on the gauge takes a bucket of nutrient; the filled bucket puts it back. */
    private static void bucketClick(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper, HUB);
        withMenu(helper, hub, (player, session) -> {
            AbstractContainerMenu menu = session.menu();
            hub.storage().tank().set(FluidKey.of(Fluids.WATER), 1500);
            menu.setCarried(new ItemStack(Items.BUCKET));
            helper.assertTrue(session.pressTankButton(HubMenu.TANK), "bucket filled");
            helper.assertTrue(menu.getCarried().is(Items.WATER_BUCKET), "carried " + menu.getCarried());
            helper.assertValueEqual(hub.storage().tank().amount(), 500L, "tank after filling a bucket");
            helper.assertTrue(session.pressTankButton(HubMenu.TANK), "bucket emptied");
            helper.assertTrue(menu.getCarried().is(Items.BUCKET), "carried " + menu.getCarried());
            helper.assertValueEqual(hub.storage().tank().amount(), 1500L, "tank after emptying a bucket");
        });
        helper.succeed();
    }

    /**
     * An empty hand opens the hub's screen for that player and that hub, also with a sampler inside; sneaking with an
     * empty hand takes a sampler out and opens nothing, and with no sampler inside opens the screen too. Lib sends
     * nothing to a mock player, so the block's opening is recorded instead of run.
     */
    @SuppressWarnings("removal") // GameTestHelper's only way to a ServerPlayer with a connection; no replacement yet
    private static void handOpensMenu(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper, HUB);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        List<LabHubBlockEntity> opened = new ArrayList<>();
        LabHubBlock.openScreenWith((who, which) -> {
            helper.assertTrue(who.equals(player), "opened for another player");
            opened.add(which);
        });
        try {
            use(helper, player);
            helper.assertValueEqual(opened, List.of(hub), "screens opened by an empty hand");
            hub.storage().slots().set(HubStorage.SAMPLER, new ItemStack(FarmsContent.SAMPLER.get()));
            use(helper, player);
            helper.assertValueEqual(opened.size(), 2, "screens opened with a sampler inside");
            helper.assertTrue(player.getMainHandItem().isEmpty(), "no sneak, no sampler taken");
            player.setShiftKeyDown(true);
            use(helper, player);
            helper.assertValueEqual(opened.size(), 2, "screens opened while taking the sampler");
            helper.assertTrue(player.getMainHandItem().is(FarmsContent.SAMPLER.get()), "sampler in the hand");
            helper.assertTrue(hub.storage().sampler().isEmpty(), "sampler slot empty");
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            use(helper, player);
            helper.assertValueEqual(opened.size(), 3, "screens opened sneaking without a sampler");
        } finally {
            LabHubBlock.openScreenWith(null);
            helper.getLevel().getServer().getPlayerList().remove(player);
        }
        helper.succeed();
    }

    /** {@code player} uses the hub with an empty hand through the game mode. */
    private static void use(GameTestHelper helper, ServerPlayer player) {
        BlockPos pos = helper.absolutePos(HUB);
        player.gameMode.useItemOn(player, helper.getLevel(), player.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    private static LabHubBlockEntity hub(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, FarmsContent.LAB_HUB.get());
        return helper.getBlockEntity(pos, LabHubBlockEntity.class);
    }

    private static void tube(GameTestHelper helper, BlockPos pos) {
        helper.assertTrue(Conduits.place(helper.getLevel(), helper.absolutePos(pos), LabTubes.TUBE.type().get()),
                "placed a tube");
    }
}
