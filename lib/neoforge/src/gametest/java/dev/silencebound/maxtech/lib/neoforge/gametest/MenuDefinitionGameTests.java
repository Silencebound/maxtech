package dev.silencebound.maxtech.lib.neoforge.gametest;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.inventory.FluidTank;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import dev.silencebound.maxtech.lib.api.menu.EnergyValue;
import dev.silencebound.maxtech.lib.api.menu.Layout;
import dev.silencebound.maxtech.lib.api.menu.LayoutPlan;
import dev.silencebound.maxtech.lib.api.menu.MenuDefinition;
import dev.silencebound.maxtech.lib.api.menu.MenuItems;
import dev.silencebound.maxtech.lib.api.menu.MenuSlots;
import dev.silencebound.maxtech.lib.api.menu.Menus;
import dev.silencebound.maxtech.lib.api.menu.ProgressValue;
import dev.silencebound.maxtech.lib.api.menu.TankValue;
import dev.silencebound.maxtech.lib.api.registry.Registrar;
import dev.silencebound.maxtech.lib.api.registry.Registrars;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.menu.DefinedMenu;
import dev.silencebound.maxtech.lib.menu.ToolkitMenu;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * A menu declared with {@link Menus#define} and registered through the api alone: it opens for a server player through
 * {@link MenuDefinition#open}, puts its slots where the layout says on both sides, and shift-clicks and tank clicks go
 * through the definition.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class MenuDefinitionGameTests {
    private static final String[] NAMES = {"menu_definition_opens", "menu_definition_slot_positions",
            "menu_definition_shift_click", "menu_definition_tank_click", "menu_definition_still_valid"};
    private static final Registrar<Consumer<GameTestHelper>> FUNCTIONS = Registrars.create(MaxtechLib.MOD_ID,
            Registries.TEST_FUNCTION);

    /** 0: coal input. 1-2: outputs. 3: open input, not a shift-click target. */
    private static final ItemSlots.Builder SLOTS = ItemSlots.builder(4).input(0, 1, stack -> stack.is(Items.COAL))
            .output(1, 3);
    private static final AtomicReference<TankValue> TANK = new AtomicReference<>();
    private static final MenuDefinition<Host> DEFINITION = Menus.define(MaxtechLib.MOD_ID, "test_definition",
            Host.class, m -> {
                MenuItems<Host> items = m.items(host -> host.slots, SLOTS);
                MenuSlots coal = m.slots(items, 0, 1);
                MenuSlots output = m.slots(items, 1, 3);
                MenuSlots open = m.slots(items, 3, 4);
                TankValue tank = m.tank(host -> host.tank);
                EnergyValue energy = m.energy(host -> host.energy);
                ProgressValue burn = m.progress(host -> host.burn, host -> 200);
                TANK.set(tank);
                m.shiftClick(coal);
                m.validWhile((host, player) -> host.open);
                m.layout(Layout.column(Layout.row(Layout.tank(tank, 18, 52),
                        Layout.column(Layout.SMALL_GAP, Layout.slots(coal, 1, 1),
                                Layout.progress(burn, Layout.ProgressShape.FLAME)),
                        Layout.slots(output, 1, 2), Layout.slots(open, 1, 1), Layout.energy(energy, 52)),
                        Layout.playerInventory()));
            });

    static {
        DEFINITION.register(Registrars.create(MaxtechLib.MOD_ID, Registries.MENU));
        FUNCTIONS.register(NAMES[0], () -> MenuDefinitionGameTests::opens);
        FUNCTIONS.register(NAMES[1], () -> MenuDefinitionGameTests::slotPositions);
        FUNCTIONS.register(NAMES[2], () -> MenuDefinitionGameTests::shiftClick);
        FUNCTIONS.register(NAMES[3], () -> MenuDefinitionGameTests::tankClick);
        FUNCTIONS.register(NAMES[4], () -> MenuDefinitionGameTests::stillValid);
    }

    /** Menu index of the player's first main-inventory slot: after the definition's four slots. */
    private static final int MAIN = 4;

    private MenuDefinitionGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechLib.id("menu_definition"));
        for (String name : NAMES) {
            Identifier id = MaxtechLib.id(name);
            event.registerTest(id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                    new TestData<>(environment, LibGameTests.EMPTY_STRUCTURE, 20, 0, true)));
        }
    }

    /** What a machine would hold; not a block entity, so the definition says when the menu stays open. */
    private static final class Host {
        final ItemSlots slots = SLOTS.build(slot -> {
        });
        final FluidTank tank = FluidTank.create(4000, () -> {
        });
        final EnergyEndpoint energy = EnergyEndpoint.buffer(1000, 100, 100, 0);
        long burn;
        boolean open = true;
    }

    /** The server menu, built the way {@link MenuDefinition#open} builds it. */
    private static DefinedMenu<Host> serverMenu(Player player, Host host) {
        DefinedMenu<Host> menu = new DefinedMenu<>(DEFINITION, 3, player.getInventory(), host);
        menu.sendValuesTo(payload -> {
        });
        return menu;
    }

    /** Opening through the api puts the definition's menu over the host on the player, titled from its lang key. */
    @SuppressWarnings({"removal", "unchecked"}) // the only way to a ServerPlayer with a connection; DefinedMenu<Host>
    private static void opens(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try {
            Host host = new Host();
            host.slots.set(0, new ItemStack(Items.COAL, 5));
            // Vanilla broadcasts the values while opening, and the mock connection refuses Lib's payloads.
            ToolkitMenu.sendNewMenusValuesTo(payload -> {
            });
            DEFINITION.open(player, host);
            helper.assertTrue(player.containerMenu instanceof DefinedMenu<?>, "open menu " + player.containerMenu);
            DefinedMenu<Host> menu = (DefinedMenu<Host>) player.containerMenu;
            helper.assertValueEqual(menu.getType(), (MenuType<?>) DEFINITION.type().get(), "menu type");
            helper.assertValueEqual(menu.definition(), DEFINITION, "definition");
            helper.assertValueEqual(menu.slots.size(), MAIN + 36, "slots");
            helper.assertTrue(ItemStack.matches(menu.getSlot(0).getItem(), new ItemStack(Items.COAL, 5)),
                    "slot 0 shows the host's coal: " + menu.getSlot(0).getItem());
            helper.assertTrue(menu.stillValid(player), "valid while the host is open");
        } finally {
            ToolkitMenu.sendNewMenusValuesTo(null);
            helper.getLevel().getServer().getPlayerList().remove(player);
        }
        helper.succeed();
    }

    /**
     * Slots sit one pixel inside the boxes the plan gives their groups, the same in the server menu and in the client
     * menu the registered type builds (no host, empty copies of the slots).
     */
    private static void slotPositions(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        DefinedMenu<Host> server = serverMenu(player, new Host());
        DefinedMenu<?> client = (DefinedMenu<?>) DEFINITION.type().get().create(4, player.getInventory());
        helper.assertValueEqual(client.slots.size(), server.slots.size(), "client slots");
        for (int i = 0; i < server.slots.size(); i++) {
            Slot a = server.getSlot(i);
            Slot b = client.getSlot(i);
            helper.assertTrue(a.x == b.x && a.y == b.y, "slot " + i + ": " + a.x + "," + a.y + " / " + b.x + "," + b.y);
        }
        int expected = 0;
        for (LayoutPlan.Box box : DEFINITION.plan().boxes()) {
            if (box.node() instanceof Layout.Slots slots) {
                for (int n = 0; n < slots.group().size(); n++) {
                    // One inventory in groups of slot order, so a slot's menu index is its inventory index.
                    Slot slot = server.getSlot(slots.group().from() + n);
                    int x = box.x() + 1 + (n % slots.columns()) * Layout.SLOT;
                    int y = box.y() + 1 + (n / slots.columns()) * Layout.SLOT;
                    helper.assertTrue(slot.x == x && slot.y == y,
                            "group " + slots.group().index() + " slot " + n + " at " + slot.x + "," + slot.y);
                    expected++;
                }
            } else if (box.node() instanceof Layout.PlayerInventory) {
                Slot first = server.getSlot(MAIN);
                Slot hotbar = server.getSlot(MAIN + 27);
                helper.assertTrue(first.x == box.x() + 1 && first.y == box.y() + 1,
                        "inventory at " + first.x + "," + first.y);
                helper.assertTrue(hotbar.x == box.x() + 1 && hotbar.y == box.y() + 1 + 58,
                        "hotbar at " + hotbar.x + "," + hotbar.y);
            }
        }
        helper.assertValueEqual(expected, MAIN, "placed definition slots");
        helper.succeed();
    }

    /** Shift-clicked coal goes only into the coal input (the one named group); an output gives back to the player. */
    private static void shiftClick(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Host host = new Host();
        DefinedMenu<Host> menu = serverMenu(player, host);
        player.getInventory().setItem(9, new ItemStack(Items.COAL, 70 - 6));
        host.slots.set(0, new ItemStack(Items.COAL, 60));
        menu.clicked(MAIN, 0, ContainerInput.QUICK_MOVE, player);
        helper.assertValueEqual(host.slots.stack(0).getCount(), 64, "coal input");
        helper.assertTrue(host.slots.stack(3).isEmpty(), "open input is no shift-click target");
        helper.assertValueEqual(player.getInventory().getItem(9).getCount(), 60, "coal left with the player");
        host.slots.set(2, new ItemStack(Items.IRON_INGOT, 3));
        menu.clicked(2, 0, ContainerInput.QUICK_MOVE, player);
        helper.assertTrue(host.slots.stack(2).isEmpty(), "output after the shift-click");
        helper.assertTrue(player.getInventory().contains(new ItemStack(Items.IRON_INGOT)), "iron with the player");
        helper.succeed();
    }

    /** The tank's button id from the definition fills a carried bucket from the tank. */
    private static void tankClick(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Host host = new Host();
        DefinedMenu<Host> menu = serverMenu(player, host);
        host.tank.set(FluidKey.of(Fluids.WATER), 1500);
        int button = menu.tankButton(TANK.get());
        helper.assertTrue(button >= 0, "tank button");
        menu.setCarried(new ItemStack(Items.BUCKET));
        helper.assertTrue(menu.clickMenuButton(player, button), "bucket filled");
        helper.assertTrue(menu.getCarried().is(Items.WATER_BUCKET), "carried " + menu.getCarried());
        helper.assertValueEqual(host.tank.amount(), 500L, "tank after filling a bucket");
        helper.assertFalse(menu.clickMenuButton(player, button + 1), "a button no tank has");
        helper.succeed();
    }

    /** The definition's test decides whether the menu stays open. */
    private static void stillValid(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Host host = new Host();
        DefinedMenu<Host> menu = serverMenu(player, host);
        helper.assertTrue(menu.stillValid(player), "open host");
        host.open = false;
        helper.assertFalse(menu.stillValid(player), "closed host");
        helper.succeed();
    }
}
