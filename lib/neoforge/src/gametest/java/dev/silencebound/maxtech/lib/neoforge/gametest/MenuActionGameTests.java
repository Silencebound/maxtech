package dev.silencebound.maxtech.lib.neoforge.gametest;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.machine.RedstoneMode;
import dev.silencebound.maxtech.lib.api.menu.CycleAction;
import dev.silencebound.maxtech.lib.api.menu.MenuTesting;
import dev.silencebound.maxtech.lib.api.registry.Registrar;
import dev.silencebound.maxtech.lib.api.registry.Registrars;
import dev.silencebound.maxtech.lib.menu.DefinedMenu;
import dev.silencebound.maxtech.lib.menu.ToolkitMenu;
import dev.silencebound.maxtech.lib.neoforge.gametest.ToolkitDemo.MachineEntity;
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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Menu server actions on the toolkit demo machine's redstone button ({@link ToolkitDemo}): a click sets the host's
 * value on the server and the value reaches the client mirror; button ids of no action, ordinals past the enum and
 * clicks on the hostless client menu change nothing.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class MenuActionGameTests {
    private static final String[] NAMES = {"menu_action_cycles", "menu_action_invalid_ids"};
    private static final Registrar<Consumer<GameTestHelper>> FUNCTIONS = Registrars.create(MaxtechLib.MOD_ID,
            Registries.TEST_FUNCTION);
    private static final BlockPos POS = BlockPos.ZERO;

    static {
        FUNCTIONS.register(NAMES[0], () -> MenuActionGameTests::cycles);
        FUNCTIONS.register(NAMES[1], () -> MenuActionGameTests::invalidIds);
    }

    private MenuActionGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechLib.id("menu_action"));
        for (String name : NAMES) {
            Identifier id = MaxtechLib.id(name);
            event.registerTest(id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                    new TestData<>(environment, LibGameTests.EMPTY_STRUCTURE, 20, 0, true)));
        }
    }

    private interface Body {
        void run(GameTestHelper helper, ServerPlayer player, MachineEntity machine,
                MenuTesting.Session<MachineEntity> session);
    }

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

    /**
     * Clicking to the next mode (as the screen does from the mirror) sets it on the host, the mirror follows after a
     * tick, and stepping on wraps round to the first mode; a step back from the first goes to the last.
     */
    private static void cycles(GameTestHelper helper) {
        withOpenMenu(helper, (h, player, machine, session) -> {
            CycleAction<RedstoneMode> action = ToolkitDemo.handles().redstone;
            var mode = ToolkitDemo.handles().mode;
            session.tick();
            h.assertValueEqual(session.clientValues().get(mode), RedstoneMode.ALWAYS, "mirror before a click");
            for (RedstoneMode expected : new RedstoneMode[]{RedstoneMode.HIGH, RedstoneMode.LOW, RedstoneMode.PULSE,
                    RedstoneMode.NEVER, RedstoneMode.ALWAYS}) {
                RedstoneMode shown = session.clientValues().get(mode);
                h.assertTrue(session.pressCycleButton(action, action.next(shown)), "click to " + expected);
                h.assertValueEqual(machine.redstone.mode(), expected, "host mode");
                session.tick();
                h.assertValueEqual(session.clientValues().get(mode), expected, "mirror");
            }
            h.assertTrue(session.pressCycleButton(action, action.previous(RedstoneMode.ALWAYS)), "click back");
            session.tick();
            h.assertValueEqual(machine.redstone.mode(), RedstoneMode.NEVER, "host mode after a step back");
            h.assertValueEqual(session.clientValues().get(mode), RedstoneMode.NEVER, "mirror after a step back");
            int sent = session.payloadsSent();
            h.assertTrue(session.pressCycleButton(action, RedstoneMode.NEVER), "click to the current mode");
            session.tick();
            h.assertValueEqual(session.payloadsSent(), sent, "payloads after a click that changed nothing");
        });
    }

    /** Ids past the enum's constants, of no action or below every button change nothing and report no click. */
    private static void invalidIds(GameTestHelper helper) {
        withOpenMenu(helper, (h, player, machine, session) -> {
            CycleAction<RedstoneMode> action = ToolkitDemo.handles().redstone;
            DefinedMenu<?> menu = (DefinedMenu<?>) session.menu();
            machine.redstone.setMode(RedstoneMode.LOW);
            int high = menu.cycleButton(action, RedstoneMode.HIGH);
            h.assertValueEqual(high, ToolkitMenu.ACTION_BUTTONS + action.index() * CycleAction.MAX_CHOICES + 1,
                    "button id of high");
            int[] invalid = {ToolkitMenu.ACTION_BUTTONS + RedstoneMode.values().length,
                    ToolkitMenu.ACTION_BUTTONS + CycleAction.MAX_CHOICES - 1,
                    ToolkitMenu.ACTION_BUTTONS + CycleAction.MAX_CHOICES, Integer.MAX_VALUE, -1,
                    ToolkitMenu.ACTION_BUTTONS - 1};
            for (int id : invalid) {
                h.assertFalse(menu.clickMenuButton(player, id), "click on id " + id);
                h.assertValueEqual(machine.redstone.mode(), RedstoneMode.LOW, "mode after id " + id);
            }
            DefinedMenu<MachineEntity> client = new DefinedMenu<>(ToolkitDemo.DEFINITION, menu.containerId,
                    player.getInventory(), null);
            h.assertFalse(client.clickMenuButton(player, high), "click on the client menu");
            h.assertTrue(menu.clickMenuButton(player, high), "click on the server menu");
            h.assertValueEqual(machine.redstone.mode(), RedstoneMode.HIGH, "mode after a valid id");
        });
    }
}
