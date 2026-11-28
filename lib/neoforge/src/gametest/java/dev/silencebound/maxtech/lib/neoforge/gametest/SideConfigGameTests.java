package dev.silencebound.maxtech.lib.neoforge.gametest;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.menu.MenuTesting;
import dev.silencebound.maxtech.lib.api.menu.PortValue;
import dev.silencebound.maxtech.lib.api.registry.Registrar;
import dev.silencebound.maxtech.lib.api.registry.Registrars;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import dev.silencebound.maxtech.lib.api.transfer.PortFaceMode;
import dev.silencebound.maxtech.lib.api.transfer.PortFaces;
import dev.silencebound.maxtech.lib.menu.DefinedMenu;
import dev.silencebound.maxtech.lib.menu.ToolkitMenu;
import dev.silencebound.maxtech.lib.neoforge.gametest.ToolkitDemo.MachineEntity;
import java.util.EnumSet;
import java.util.Optional;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The side config of the toolkit demo machine ({@link ToolkitDemo}): a face click sets the port's mode on the server
 * and the mirror follows; modes the port does not allow for a kind, ids of no port, kind or face, and clicks on the
 * hostless client menu change nothing; the host's facing and block reach the client.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class SideConfigGameTests {
    private static final String[] NAMES = {"side_config_cycles", "side_config_invalid", "side_config_facing"};
    private static final Registrar<Consumer<GameTestHelper>> FUNCTIONS = Registrars.create(MaxtechLib.MOD_ID,
            Registries.TEST_FUNCTION);
    private static final BlockPos POS = BlockPos.ZERO;

    static {
        FUNCTIONS.register(NAMES[0], () -> SideConfigGameTests::cycles);
        FUNCTIONS.register(NAMES[1], () -> SideConfigGameTests::invalid);
        FUNCTIONS.register(NAMES[2], () -> SideConfigGameTests::facing);
    }

    private SideConfigGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechLib.id("side_config"));
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
    private static void withOpenMenu(GameTestHelper helper, BlockState state, Body body) {
        helper.setBlock(POS, state);
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

    private static BlockState machine() {
        return ToolkitDemo.BLOCK.get().defaultBlockState();
    }

    /**
     * Asking for push, then pull, sets the port's face on the server and the mirror after a tick; asking for the mode a
     * face has sends nothing new; the allowed modes of both kinds reach the client.
     */
    private static void cycles(GameTestHelper helper) {
        withOpenMenu(helper, machine(), (h, player, machine, session) -> {
            PortValue port = ToolkitDemo.handles().port;
            session.tick();
            h.assertValueEqual(port.mode(session.clientValues(), ResourceKinds.ITEM, Direction.WEST), PortFaceMode.OFF,
                    "mirror before a click");
            h.assertValueEqual(port.allowed(session.clientValues(), ResourceKinds.ITEM),
                    EnumSet.allOf(PortFaceMode.class), "item modes on the client");
            h.assertValueEqual(port.allowed(session.clientValues(), ResourceKinds.FLUID),
                    EnumSet.of(PortFaceMode.OFF, PortFaceMode.PULL), "fluid modes on the client");
            for (PortFaceMode mode : new PortFaceMode[]{PortFaceMode.PUSH, PortFaceMode.PULL, PortFaceMode.OFF}) {
                h.assertTrue(session.pressFace(port, ResourceKinds.ITEM, Direction.WEST, mode), "click to " + mode);
                h.assertValueEqual(machine.port.settings().mode(ResourceKinds.ITEM, Direction.WEST), mode, "host");
                session.tick();
                h.assertValueEqual(port.mode(session.clientValues(), ResourceKinds.ITEM, Direction.WEST), mode,
                        "mirror after " + mode);
            }
            h.assertValueEqual(port.mode(session.clientValues(), ResourceKinds.ITEM, Direction.EAST), PortFaceMode.OFF,
                    "other faces stay off");
            h.assertTrue(session.pressFace(port, ResourceKinds.FLUID, Direction.UP, PortFaceMode.PULL), "fluid pull");
            session.tick();
            h.assertValueEqual(port.mode(session.clientValues(), ResourceKinds.FLUID, Direction.UP), PortFaceMode.PULL,
                    "fluid mirror");
            h.assertValueEqual(port.mode(session.clientValues(), ResourceKinds.ITEM, Direction.UP), PortFaceMode.OFF,
                    "kinds are set apart");
            int sent = session.payloadsSent();
            h.assertTrue(session.pressFace(port, ResourceKinds.FLUID, Direction.UP, PortFaceMode.PULL), "same mode");
            session.tick();
            h.assertValueEqual(session.payloadsSent(), sent, "payloads after a click that changed nothing");
        });
    }

    /**
     * A mode the port does not allow for the kind, ids past the declared kinds, ports or faces, and clicks on the
     * client menu change nothing and report no click; a kind the value does not show is refused by the helper.
     */
    private static void invalid(GameTestHelper helper) {
        withOpenMenu(helper, machine(), (h, player, machine, session) -> {
            PortValue port = ToolkitDemo.handles().port;
            DefinedMenu<?> menu = (DefinedMenu<?>) session.menu();
            h.assertFalse(session.pressFace(port, ResourceKinds.FLUID, Direction.NORTH, PortFaceMode.PUSH),
                    "push on a pull-only kind");
            h.assertValueEqual(machine.port.settings().mode(ResourceKinds.FLUID, Direction.NORTH), PortFaceMode.OFF,
                    "fluid face after a refused click");
            int base = ToolkitMenu.PORT_BUTTONS;
            int perKind = 6 * 3;
            int[] invalid = {base + 2 * perKind + 1, base + PortValue.MAX_KINDS * perKind + 1,
                    base + PortValue.MAX_KINDS * perKind * 7, Integer.MAX_VALUE};
            for (int id : invalid) {
                h.assertFalse(menu.clickMenuButton(player, id), "click on id " + id);
            }
            h.assertValueEqual(machine.port.settings().faces(ResourceKinds.ITEM), PortFaces.NONE,
                    "item faces after invalid ids");
            h.assertValueEqual(machine.port.settings().faces(ResourceKinds.FLUID), PortFaces.NONE,
                    "fluid faces after invalid ids");
            int push = menu.faceButton(port, ResourceKinds.ITEM, Direction.NORTH, PortFaceMode.PUSH);
            h.assertValueEqual(push, base + Direction.NORTH.get3DDataValue() * 3 + PortFaceMode.PUSH.id(),
                    "button id of an item push north");
            DefinedMenu<MachineEntity> client = new DefinedMenu<>(ToolkitDemo.DEFINITION, menu.containerId,
                    player.getInventory(), null);
            h.assertFalse(client.clickMenuButton(player, push), "click on the client menu");
            h.assertValueEqual(machine.port.settings().mode(ResourceKinds.ITEM, Direction.NORTH), PortFaceMode.OFF,
                    "item face after a client click");
            h.assertTrue(menu.clickMenuButton(player, push), "click on the server menu");
            h.assertValueEqual(machine.port.settings().mode(ResourceKinds.ITEM, Direction.NORTH), PortFaceMode.PUSH,
                    "item face after a valid id");
            try {
                session.pressFace(port, ResourceKinds.ENERGY, Direction.NORTH, PortFaceMode.PUSH);
                throw h.assertionException("A kind the value does not show was accepted");
            } catch (IllegalArgumentException expected) {
                // the helper refuses it
            }
        });
    }

    /** The block's facing and state reach the client mirror, so the screen names faces relative to the front. */
    private static void facing(GameTestHelper helper) {
        BlockState state = machine().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        withOpenMenu(helper, state, (h, player, machine, session) -> {
            PortValue port = ToolkitDemo.handles().port;
            session.tick();
            h.assertValueEqual(port.facing(session.clientValues()), Optional.of(Direction.EAST), "facing");
            h.assertValueEqual(port.blockState(session.clientValues()), Optional.of(state), "block state");
        });
    }
}
