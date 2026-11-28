package dev.silencebound.maxtech.lib.neoforge.gametest;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.menu.MenuTesting;
import dev.silencebound.maxtech.lib.api.registry.Registrar;
import dev.silencebound.maxtech.lib.api.registry.Registrars;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.neoforge.gametest.ToolkitDemo.Handles;
import dev.silencebound.maxtech.lib.neoforge.gametest.ToolkitDemo.MachineEntity;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The toolkit through its public api, on the demo machine ({@link ToolkitDemo}): the definition opens for a server
 * player, its values reach the menu, shift-clicks respect the filtered group, bucket clicks fill and drain the tank,
 * and the output only gives. Everything goes through {@link MenuTesting}, which an addon can use the same way: a mock
 * player has no connection to read the values from.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class ToolkitApiGameTests {
    private static final String[] NAMES = {"toolkit_api_opens", "toolkit_api_values_sync", "toolkit_api_shift_click",
            "toolkit_api_bucket_clicks", "toolkit_api_output_take_only", "toolkit_api_machine_runs"};
    private static final Registrar<Consumer<GameTestHelper>> FUNCTIONS = Registrars.create(MaxtechLib.MOD_ID,
            Registries.TEST_FUNCTION);

    /** Menu index of the player's first main-inventory slot: after the machine's eleven slots. */
    private static final int MAIN = ToolkitDemo.SIZE;
    private static final BlockPos POS = BlockPos.ZERO;

    static {
        FUNCTIONS.register(NAMES[0], () -> ToolkitApiGameTests::opens);
        FUNCTIONS.register(NAMES[1], () -> ToolkitApiGameTests::valuesSync);
        FUNCTIONS.register(NAMES[2], () -> ToolkitApiGameTests::shiftClick);
        FUNCTIONS.register(NAMES[3], () -> ToolkitApiGameTests::bucketClicks);
        FUNCTIONS.register(NAMES[4], () -> ToolkitApiGameTests::outputTakeOnly);
        FUNCTIONS.register(NAMES[5], () -> ToolkitApiGameTests::machineRuns);
    }

    private ToolkitApiGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechLib.id("toolkit_api"));
        for (String name : NAMES) {
            Identifier id = MaxtechLib.id(name);
            event.registerTest(id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                    new TestData<>(environment, LibGameTests.EMPTY_STRUCTURE, 20, 0, true)));
        }
    }

    /** What a test does with the open menu; the machine's task cannot run before it finishes (same tick). */
    private interface Body {
        void run(GameTestHelper helper, ServerPlayer player, MachineEntity machine,
                MenuTesting.Session<MachineEntity> session);
    }

    /** Places the machine, opens its definition for a mock server player and runs {@code body}. */
    @SuppressWarnings("removal") // the only way to a ServerPlayer with a connection
    private static void withOpenMenu(GameTestHelper helper, Body body) {
        helper.setBlock(POS, ToolkitDemo.BLOCK.get().defaultBlockState());
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(POS)) instanceof MachineEntity machine)) {
            throw helper.assertionException("No machine block entity");
        }
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try (MenuTesting.Session<MachineEntity> session = MenuTesting.open(ToolkitDemo.DEFINITION, player, machine)) {
            body.run(helper, player, machine, session);
        } finally {
            helper.getLevel().getServer().getPlayerList().remove(player);
        }
        helper.succeed();
    }

    /** The definition has no layout problems, and opens as the registered menu type over the machine's slots. */
    private static void opens(GameTestHelper helper) {
        helper.assertValueEqual(ToolkitDemo.DEFINITION.problems(), List.of(), "layout problems");
        withOpenMenu(helper, (h, player, machine, session) -> {
            AbstractContainerMenu menu = session.menu();
            h.assertValueEqual(menu.getType(), ToolkitDemo.DEFINITION.type().get(), "menu type");
            h.assertValueEqual(menu.slots.size(), MAIN + 36, "slots");
            machine.slots.set(ToolkitDemo.INPUT_SLOT, new ItemStack(Items.RAW_IRON, 3));
            h.assertTrue(ItemStack.matches(menu.getSlot(0).getItem(), new ItemStack(Items.RAW_IRON, 3)),
                    "input slot shows the machine's ore: " + menu.getSlot(0).getItem());
            h.assertFalse(menu.stillValid(player), "valid far from the machine");
            player.setPos(h.absoluteVec(Vec3.atCenterOf(POS)));
            h.assertTrue(menu.stillValid(player), "valid beside the machine");
        });
    }

    /** What the machine holds reaches the menu's value view: bars, status, the live number. */
    private static void valuesSync(GameTestHelper helper) {
        withOpenMenu(helper, (h, player, machine, session) -> {
            Handles handles = ToolkitDemo.handles();
            machine.progress = 40;
            machine.burn = 150;
            machine.cycles = 7;
            machine.tank.set(FluidKey.of(Fluids.WATER), 2500);
            machine.energy.setStored(6000);
            machine.slots.set(ToolkitDemo.INPUT_SLOT, new ItemStack(Items.RAW_COPPER));
            session.tick();
            var view = session.clientValues();
            h.assertValueEqual(session.serverValues().amount(handles.tank), 2500L, "tank on the server");
            h.assertValueEqual(view.current(handles.arrow), 40L, "arrow");
            h.assertValueEqual(view.total(handles.arrow), (long) ToolkitDemo.ARROW_TICKS, "arrow total");
            h.assertValueEqual(view.current(handles.flame), 150L, "flame");
            h.assertValueEqual(view.get(handles.cycles), 7, "cycles");
            h.assertValueEqual(view.amount(handles.tank), 2500L, "tank");
            h.assertValueEqual(view.stored(handles.energy), 6000L, "energy");
            h.assertValueEqual(view.capacity(handles.energy), ToolkitDemo.ENERGY_CAPACITY, "energy capacity");
            h.assertValueEqual(view.get(handles.status), ToolkitDemo.Status.WORKING, "status");
            machine.burn = 0;
            session.tick();
            h.assertValueEqual(session.clientValues().get(handles.status), ToolkitDemo.Status.NO_FUEL,
                    "status without a flame");
        });
    }

    /** Shift-clicked ore and fuel go to their groups only; anything else, and the output, is left alone. */
    private static void shiftClick(GameTestHelper helper) {
        withOpenMenu(helper, (h, player, machine, session) -> {
            player.getInventory().setItem(9, new ItemStack(Items.RAW_IRON, 10));
            player.getInventory().setItem(10, new ItemStack(Items.COAL, 5));
            player.getInventory().setItem(11, new ItemStack(Items.DIRT, 5));
            session.shiftClickPlayerSlot(0);
            session.shiftClickPlayerSlot(1);
            session.shiftClickPlayerSlot(2);
            h.assertValueEqual(machine.slots.stack(ToolkitDemo.INPUT_SLOT).getCount(), 10, "ore in the input");
            h.assertTrue(machine.slots.stack(ToolkitDemo.INPUT_SLOT).is(Items.RAW_IRON), "input holds iron");
            h.assertValueEqual(machine.slots.stack(ToolkitDemo.FUEL_SLOT).getCount(), 5, "coal in the fuel slot");
            h.assertTrue(player.getInventory().contains(new ItemStack(Items.DIRT)), "dirt stays with the player");
            for (int slot = 0; slot < ToolkitDemo.SIZE; slot++) {
                h.assertFalse(machine.slots.stack(slot).is(Items.DIRT), "dirt in machine slot " + slot);
            }
            for (int slot = ToolkitDemo.FIRST_OUTPUT; slot < ToolkitDemo.SIZE; slot++) {
                h.assertTrue(machine.slots.stack(slot).isEmpty(), "nothing shift-clicked into output " + slot);
            }
        });
    }

    /** The tank's button gives a carried bucket its water, and a carried water bucket empties into the tank. */
    private static void bucketClicks(GameTestHelper helper) {
        withOpenMenu(helper, (h, player, machine, session) -> {
            AbstractContainerMenu menu = session.menu();
            var tank = ToolkitDemo.handles().tank;
            machine.tank.set(FluidKey.of(Fluids.WATER), 1500);
            menu.setCarried(new ItemStack(Items.BUCKET));
            h.assertTrue(session.pressTankButton(tank), "bucket filled");
            h.assertTrue(menu.getCarried().is(Items.WATER_BUCKET), "carried " + menu.getCarried());
            h.assertValueEqual(machine.tank.amount(), 500L, "tank after filling a bucket");
            h.assertTrue(session.pressTankButton(tank), "bucket emptied");
            h.assertTrue(menu.getCarried().is(Items.BUCKET), "carried " + menu.getCarried());
            h.assertValueEqual(machine.tank.amount(), 1500L, "tank after draining a bucket");
            menu.setCarried(new ItemStack(Items.LAVA_BUCKET));
            h.assertFalse(session.pressTankButton(tank), "a lava bucket into a water tank");
            h.assertValueEqual(machine.tank.amount(), 1500L, "tank kept its water");
        });
    }

    /** Players take from the output but cannot put into it, by click or by drag. */
    private static void outputTakeOnly(GameTestHelper helper) {
        withOpenMenu(helper, (h, player, machine, session) -> {
            AbstractContainerMenu menu = session.menu();
            int output = ToolkitDemo.FIRST_OUTPUT;
            machine.slots.set(output, new ItemStack(Items.IRON_INGOT, 4));
            Slot slot = menu.getSlot(output);
            h.assertFalse(slot.mayPlace(new ItemStack(Items.IRON_INGOT)), "output takes items");
            h.assertTrue(slot.mayPickup(player), "output cannot be taken from");
            menu.setCarried(new ItemStack(Items.RAW_IRON, 3));
            menu.clicked(output, 0, ContainerInput.PICKUP, player);
            h.assertValueEqual(machine.slots.stack(output).getCount(), 4, "output after a click with a stack");
            menu.setCarried(ItemStack.EMPTY);
            menu.clicked(output, 0, ContainerInput.QUICK_MOVE, player);
            h.assertTrue(machine.slots.stack(output).isEmpty(), "output after the shift-click");
            h.assertTrue(player.getInventory().contains(new ItemStack(Items.IRON_INGOT)), "ingots with the player");
        });
    }

    /** The demo loop: a burning flame turns ore into ingots once per arrow, and the bars sweep. */
    private static void machineRuns(GameTestHelper helper) {
        withOpenMenu(helper, (h, player, machine, session) -> {
            machine.slots.set(ToolkitDemo.INPUT_SLOT, new ItemStack(Items.RAW_IRON, 2));
            machine.slots.set(ToolkitDemo.FUEL_SLOT, new ItemStack(Items.COAL, 1));
            for (int i = 0; i < ToolkitDemo.ARROW_TICKS / ToolkitDemo.STEP; i++) {
                machine.step();
            }
            h.assertValueEqual(machine.cycles, 1, "one arrow");
            h.assertValueEqual(machine.slots.stack(ToolkitDemo.INPUT_SLOT).getCount(), 1, "ore used");
            h.assertTrue(machine.slots.stack(ToolkitDemo.FUEL_SLOT).isEmpty(), "fuel burnt");
            h.assertTrue(machine.slots.stack(ToolkitDemo.FIRST_OUTPUT).is(Items.IRON_INGOT), "ingot made");
            h.assertTrue(machine.tank.amount() > 0 && machine.energy.stored() > 0, "bars moved");
            // Past the flame's burn the machine needs fuel again.
            for (int i = 0; i < ToolkitDemo.BURN_TICKS / ToolkitDemo.STEP; i++) {
                machine.step();
            }
            h.assertValueEqual(machine.status(), ToolkitDemo.Status.IDLE, "status once the ore is used");
            machine.slots.set(ToolkitDemo.INPUT_SLOT, new ItemStack(Items.RAW_GOLD));
            h.assertValueEqual(machine.status(), ToolkitDemo.Status.NO_FUEL, "status after the fuel is gone");
        });
    }
}
