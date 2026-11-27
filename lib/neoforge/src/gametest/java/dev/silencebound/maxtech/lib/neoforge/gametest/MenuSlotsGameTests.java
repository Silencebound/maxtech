package dev.silencebound.maxtech.lib.neoforge.gametest;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.inventory.FluidTank;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import dev.silencebound.maxtech.lib.api.menu.MenuValues;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.menu.SlotGroup;
import dev.silencebound.maxtech.lib.menu.ToolkitMenu;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.SimpleFluidContent;
import net.neoforged.neoforge.transfer.fluid.ItemAccessFluidHandler;
import org.jspecify.annotations.Nullable;

/**
 * Toolkit menu slots and transfers on a mock player: shift-clicks go only where the menu says, outputs only give, a
 * full or partly full inventory neither dupes nor loses items, and tank clicks move only what both sides took.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class MenuSlotsGameTests {
    private static final String[] NAMES = {"menu_slots_shift_into_groups", "menu_slots_output_take_only",
            "menu_slots_full_inventory", "menu_slots_client_mirror", "menu_tank_bucket_clicks"};
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);
    private static final RegistryHelper.Registrar<DataComponentType<?>> COMPONENTS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.DATA_COMPONENT_TYPE);
    private static final RegistryEntry<DataComponentType<?>, DataComponentType<SimpleFluidContent>> FLASK_CONTENT = COMPONENTS
            .register("test_flask_content", () -> DataComponentType.<SimpleFluidContent>builder()
                    .persistent(SimpleFluidContent.CODEC).networkSynchronized(SimpleFluidContent.STREAM_CODEC).build());
    private static final RegistryHelper.Registrar<Item> ITEMS = Platform.get().registry().registrar(MaxtechLib.MOD_ID,
            Registries.ITEM);
    /** A container of 300 mB that fills and empties in any amount. */
    private static final RegistryEntry<Item, Item> FLASK = ITEMS.register("test_flask",
            id -> new Item(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id)).stacksTo(1)));
    private static final int FLASK_CAPACITY = 300;

    /** 0: coal input. 1: output. 2: display. 3: open input. 4: open input left out of shift-clicks. */
    private static final ItemSlots.Builder SLOTS = ItemSlots.builder(5).input(0, 1, stack -> stack.is(Items.COAL))
            .output(1, 2).display(2, 3);
    private static final MenuValues<Host> VALUES = MenuValues.<Host>builder().build();
    /** Menu index of the player's first main-inventory slot (inventory slot 9); the hotbar follows at 32. */
    private static final int MAIN = 5;
    private static final int HOTBAR = MAIN + 27;

    static {
        FUNCTIONS.register(NAMES[0], () -> MenuSlotsGameTests::shiftIntoGroups);
        FUNCTIONS.register(NAMES[1], () -> MenuSlotsGameTests::outputTakeOnly);
        FUNCTIONS.register(NAMES[2], () -> MenuSlotsGameTests::fullInventory);
        FUNCTIONS.register(NAMES[3], () -> MenuSlotsGameTests::clientMirror);
        FUNCTIONS.register(NAMES[4], () -> MenuSlotsGameTests::tankBucketClicks);
    }

    private MenuSlotsGameTests() {
    }

    @SubscribeEvent
    static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerItem(Capabilities.Fluid.ITEM,
                (stack, access) -> new ItemAccessFluidHandler(access, FLASK_CONTENT.get(), FLASK_CAPACITY),
                FLASK.get());
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechLib.id("menu_slots"));
        for (String name : NAMES) {
            Identifier id = MaxtechLib.id(name);
            event.registerTest(id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                    new TestData<>(environment, LibGameTests.EMPTY_STRUCTURE, 20, 0, true)));
        }
    }

    /** What a machine would hold. */
    private static final class Host {
        final ItemSlots slots = SLOTS.build(slot -> {
        });
        final FluidTank tank = FluidTank.create(4000, () -> {
        });
    }

    private static final class TestMenu extends ToolkitMenu<Host> {
        final ItemSlots inventory;
        final int tankButton;

        /** The server menu over {@code host}, or with a null host the client menu over an empty mirror. */
        TestMenu(Player player, @Nullable Host host) {
            super(null, 1, player, VALUES, host);
            inventory = host != null ? host.slots : SLOTS.build(slot -> {
            });
            SlotGroup coal = addSlots(inventory, 0, 1, 8, 8, 1);
            SlotGroup output = addSlots(inventory, 1, 2, 26, 8, 1);
            addSlots(inventory, 2, 3, 44, 8, 1);
            SlotGroup open = addSlots(inventory, 3, 4, 62, 8, 1);
            addSlots(inventory, 4, 5, 80, 8, 1);
            addPlayerInventory(player.getInventory(), 8, 84);
            // The output is named to show that its role still refuses shift-clicked items.
            shiftClick(output, coal, open);
            tankButton = addTank(host != null ? host.tank : null);
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }

    private static void quickMove(TestMenu menu, Player player, int index) {
        menu.clicked(index, 0, ContainerInput.QUICK_MOVE, player);
    }

    private static int count(Player player, Item item) {
        int total = 0;
        Inventory inventory = player.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * Coal fills the coal input first and then the open input (the named order), never the output; dirt then finds no
     * named group with room and stays with the player rather than going into slot 4, which is not named. A display slot
     * gives nothing to a shift-click.
     */
    private static void shiftIntoGroups(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Host host = new Host();
        TestMenu menu = new TestMenu(player, host);
        host.slots.set(0, new ItemStack(Items.COAL, 60));
        host.slots.set(2, new ItemStack(Items.DIAMOND));
        player.getInventory().setItem(9, new ItemStack(Items.COAL, 64));
        player.getInventory().setItem(10, new ItemStack(Items.DIRT, 10));
        quickMove(menu, player, MAIN);
        helper.assertValueEqual(host.slots.stack(0).getCount(), 64, "coal input filled first");
        helper.assertTrue(ItemStack.matches(host.slots.stack(3), new ItemStack(Items.COAL, 60)),
                "rest of the coal in the open input: " + host.slots.stack(3));
        helper.assertTrue(host.slots.stack(1).isEmpty(), "output filled by a shift-click");
        helper.assertTrue(player.getInventory().getItem(9).isEmpty(), "coal left with the player");
        quickMove(menu, player, MAIN + 1);
        helper.assertTrue(host.slots.stack(4).isEmpty(), "slot 4 filled by a shift-click");
        helper.assertTrue(ItemStack.matches(player.getInventory().getItem(10), new ItemStack(Items.DIRT, 10)),
                "dirt moved: " + player.getInventory().getItem(10));
        quickMove(menu, player, 2);
        helper.assertTrue(host.slots.stack(2).is(Items.DIAMOND), "display slot gave its item");
        helper.assertValueEqual(count(player, Items.DIAMOND), 0, "diamond with the player");
        helper.succeed();
    }

    /**
     * The output refuses a placed stack and a shift-clicked one, but gives its items by click and by shift-click (main
     * inventory before the hotbar).
     */
    private static void outputTakeOnly(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Host host = new Host();
        TestMenu menu = new TestMenu(player, host);
        host.slots.set(1, new ItemStack(Items.IRON_INGOT, 20));
        menu.setCarried(new ItemStack(Items.DIRT, 5));
        menu.clicked(1, 0, ContainerInput.PICKUP, player);
        helper.assertTrue(ItemStack.matches(host.slots.stack(1), new ItemStack(Items.IRON_INGOT, 20)),
                "output after placing dirt: " + host.slots.stack(1));
        helper.assertTrue(ItemStack.matches(menu.getCarried(), new ItemStack(Items.DIRT, 5)), "carried dirt");
        menu.setCarried(ItemStack.EMPTY);
        menu.clicked(1, 0, ContainerInput.PICKUP, player);
        helper.assertTrue(ItemStack.matches(menu.getCarried(), new ItemStack(Items.IRON_INGOT, 20)), "taken by click");
        helper.assertTrue(host.slots.stack(1).isEmpty(), "output after the take");
        menu.setCarried(ItemStack.EMPTY);

        host.slots.set(1, new ItemStack(Items.IRON_INGOT, 20));
        player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 1));
        quickMove(menu, player, 1);
        helper.assertTrue(host.slots.stack(1).isEmpty(), "output after the shift-click");
        helper.assertTrue(ItemStack.matches(player.getInventory().getItem(0), new ItemStack(Items.IRON_INGOT, 21)),
                "matching hotbar stack filled first: " + player.getInventory().getItem(0));

        player.getInventory().setItem(0, ItemStack.EMPTY);
        player.getInventory().setItem(11, new ItemStack(Items.IRON_INGOT, 7));
        host.slots.set(1, new ItemStack(Items.IRON_INGOT, 64));
        host.slots.set(3, new ItemStack(Items.DIRT));
        quickMove(menu, player, MAIN + 2);
        helper.assertValueEqual(host.slots.stack(1).getCount(), 64, "output took a shift-clicked stack");
        helper.assertTrue(ItemStack.matches(player.getInventory().getItem(11), new ItemStack(Items.IRON_INGOT, 7)),
                "iron that fits nowhere moved");
        helper.succeed();
    }

    /**
     * With room for only part of an output stack, exactly that part moves and the rest stays; with no room nothing
     * moves. The total never changes.
     */
    private static void fullInventory(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Host host = new Host();
        TestMenu menu = new TestMenu(player, host);
        Inventory inventory = player.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            inventory.setItem(i, new ItemStack(Items.STONE, 64));
        }
        inventory.setItem(20, new ItemStack(Items.IRON_INGOT, 50));
        host.slots.set(1, new ItemStack(Items.IRON_INGOT, 64));
        quickMove(menu, player, 1);
        helper.assertValueEqual(host.slots.stack(1).getCount(), 50, "output left with partial room");
        helper.assertValueEqual(count(player, Items.IRON_INGOT), 64, "player iron with partial room");
        quickMove(menu, player, 1);
        helper.assertValueEqual(host.slots.stack(1).getCount(), 50, "output left with no room");
        helper.assertValueEqual(count(player, Items.IRON_INGOT), 64, "player iron with no room");

        host.slots.set(3, new ItemStack(Items.STONE, 60));
        quickMove(menu, player, HOTBAR);
        helper.assertValueEqual(host.slots.stack(3).getCount(), 64, "stone into the open input's room");
        helper.assertValueEqual(inventory.getItem(0).getCount(), 60, "stone left in the hotbar slot");
        helper.assertValueEqual(count(player, Items.STONE) + host.slots.stack(3).getCount(), 35 * 64 + 60,
                "stone in all");
        helper.succeed();
    }

    /**
     * A client menu over an empty mirror has the same slots with the same rules, and vanilla's content sync fills it to
     * match the server.
     */
    private static void clientMirror(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Host host = new Host();
        TestMenu server = new TestMenu(player, host);
        TestMenu client = new TestMenu(player, null);
        host.slots.set(0, new ItemStack(Items.COAL, 3));
        host.slots.set(1, new ItemStack(Items.IRON_INGOT, 9));
        host.slots.set(2, new ItemStack(Items.DIAMOND));
        helper.assertValueEqual(client.slots.size(), server.slots.size(), "slot count");
        client.initializeContents(0, server.getItems(), ItemStack.EMPTY);
        ItemStack coal = new ItemStack(Items.COAL);
        for (int i = 0; i < server.slots.size(); i++) {
            helper.assertTrue(ItemStack.matches(client.getSlot(i).getItem(), server.getSlot(i).getItem()),
                    "slot " + i + " not synced");
            helper.assertValueEqual(client.getSlot(i).mayPlace(coal), server.getSlot(i).mayPlace(coal),
                    "mayPlace of slot " + i);
            helper.assertValueEqual(client.getSlot(i).mayPickup(player), server.getSlot(i).mayPickup(player),
                    "mayPickup of slot " + i);
        }
        helper.assertTrue(ItemStack.matches(client.inventory.stack(1), new ItemStack(Items.IRON_INGOT, 9)),
                "mirror slots filled");
        helper.assertFalse(client.clickMenuButton(player, client.tankButton), "client tank button moved fluid");
        helper.succeed();
    }

    private static boolean click(TestMenu menu, Player player, ItemStack carried) {
        menu.setCarried(carried);
        return menu.clickMenuButton(player, menu.tankButton);
    }

    private static ItemStack flask(int amount) {
        ItemStack flask = new ItemStack(FLASK.get());
        if (amount > 0) {
            flask.set(FLASK_CONTENT.get(), SimpleFluidContent.copyOf(new FluidStack(Fluids.WATER, amount)));
        }
        return flask;
    }

    private static int flaskAmount(ItemStack flask) {
        SimpleFluidContent content = flask.getOrDefault(FLASK_CONTENT.get(), SimpleFluidContent.EMPTY);
        return content.getAmount();
    }

    /**
     * Buckets fill from and empty into the tank only by whole buckets that both sides take; another fluid or a full
     * tank refuses; one bucket of a stack fills and the full one goes to the inventory; a 300 mB flask takes and gives
     * partial amounts exactly.
     */
    private static void tankBucketClicks(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Host host = new Host();
        TestMenu menu = new TestMenu(player, host);
        FluidKey water = FluidKey.of(Fluids.WATER);
        host.tank.set(water, 1500);
        helper.assertTrue(click(menu, player, new ItemStack(Items.BUCKET)), "bucket filled");
        helper.assertTrue(menu.getCarried().is(Items.WATER_BUCKET), "carried " + menu.getCarried());
        helper.assertValueEqual(host.tank.amount(), 500L, "tank after filling a bucket");
        helper.assertFalse(click(menu, player, new ItemStack(Items.BUCKET)), "bucket filled from 500 mB");
        helper.assertValueEqual(host.tank.amount(), 500L, "tank after a refused fill");
        helper.assertTrue(menu.getCarried().is(Items.BUCKET), "carried after a refused fill");

        host.tank.set(FluidKey.of(Fluids.LAVA), 1000);
        helper.assertFalse(click(menu, player, new ItemStack(Items.WATER_BUCKET)), "water into lava");
        helper.assertValueEqual(host.tank.amount(), 1000L, "lava tank");
        helper.assertTrue(menu.getCarried().is(Items.WATER_BUCKET), "water bucket kept");
        host.tank.set(water, 3500);
        helper.assertFalse(click(menu, player, new ItemStack(Items.WATER_BUCKET)), "bucket into 500 mB of room");
        helper.assertValueEqual(host.tank.amount(), 3500L, "tank with too little room");
        host.tank.set(water, 2500);
        helper.assertTrue(click(menu, player, new ItemStack(Items.WATER_BUCKET)), "bucket emptied");
        helper.assertValueEqual(host.tank.amount(), 3500L, "tank after emptying a bucket");
        helper.assertTrue(menu.getCarried().is(Items.BUCKET), "empty bucket back on the cursor");

        host.tank.set(water, 2500);
        helper.assertTrue(click(menu, player, new ItemStack(Items.BUCKET, 2)), "one of two buckets filled");
        helper.assertTrue(ItemStack.matches(menu.getCarried(), new ItemStack(Items.BUCKET)), "one bucket left carried");
        helper.assertValueEqual(count(player, Items.WATER_BUCKET), 1, "full bucket in the inventory");
        helper.assertValueEqual(host.tank.amount(), 1500L, "tank after one of two");

        host.tank.set(water, 1000);
        helper.assertTrue(click(menu, player, flask(0)), "flask filled");
        helper.assertValueEqual(flaskAmount(menu.getCarried()), FLASK_CAPACITY, "flask after filling");
        helper.assertValueEqual(host.tank.amount(), 700L, "tank after filling the flask");
        host.tank.set(water, 3900);
        helper.assertTrue(click(menu, player, flask(FLASK_CAPACITY)), "flask emptied into 100 mB of room");
        helper.assertValueEqual(flaskAmount(menu.getCarried()), 200, "flask after partly emptying");
        helper.assertValueEqual(host.tank.amount(), 4000L, "tank filled to capacity");
        helper.assertFalse(click(menu, player, new ItemStack(Items.DIRT)), "dirt moved fluid");
        helper.assertFalse(menu.clickMenuButton(player, menu.tankButton + 1), "a button no tank has");
        helper.succeed();
    }
}
