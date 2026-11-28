package dev.silencebound.maxtech.lib.neoforge.gametest;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.inventory.FluidTank;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import dev.silencebound.maxtech.lib.api.menu.Layout;
import dev.silencebound.maxtech.lib.api.menu.MenuDefinition;
import dev.silencebound.maxtech.lib.api.menu.MenuTesting;
import dev.silencebound.maxtech.lib.api.menu.Menus;
import dev.silencebound.maxtech.lib.api.menu.PortValue;
import dev.silencebound.maxtech.lib.api.registry.Registrar;
import dev.silencebound.maxtech.lib.api.registry.Registrars;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import dev.silencebound.maxtech.lib.api.transfer.PortFaceMode;
import dev.silencebound.maxtech.lib.api.transfer.TransferPort;
import dev.silencebound.maxtech.lib.api.transfer.TransferPorts;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.jspecify.annotations.Nullable;

/**
 * Where a port value's facing comes from: a block entity's vanilla {@code FACING} (all six ways, a dispenser here),
 * none for a host that is not a block entity (the screen then names faces absolutely), and the facing getter overload.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class SideConfigFacingGameTests {
    private static final String[] NAMES = {"side_config_facing_six_ways", "side_config_facing_none",
            "side_config_facing_getter", "side_config_hidden_kind"};
    private static final Registrar<Consumer<GameTestHelper>> FUNCTIONS = Registrars.create(MaxtechLib.MOD_ID,
            Registries.TEST_FUNCTION);
    private static final BlockPos POS = BlockPos.ZERO;
    private static @Nullable TransferPort port;

    /** A host that is not a block entity and has no facing. */
    record Plain() {
    }

    /** A host whose facing comes from a getter. */
    record Turned(Optional<Direction> facing) {
    }

    private static final AtomicReference<PortValue> DISPENSER_PORT = new AtomicReference<>();
    private static final AtomicReference<PortValue> PLAIN_PORT = new AtomicReference<>();
    private static final AtomicReference<PortValue> TURNED_PORT = new AtomicReference<>();
    static final MenuDefinition<DispenserBlockEntity> DISPENSER = Menus.define(MaxtechLib.MOD_ID,
            "test_facing_dispenser", DispenserBlockEntity.class, m -> {
                PortValue value = m.port(host -> port(), ResourceKinds.ITEM);
                DISPENSER_PORT.set(value);
                m.layout(Layout.faces(value));
            });
    static final MenuDefinition<Plain> PLAIN = Menus.define(MaxtechLib.MOD_ID, "test_facing_plain", Plain.class, m -> {
        PortValue value = m.port(host -> port(), ResourceKinds.ITEM);
        PLAIN_PORT.set(value);
        m.layout(Layout.faces(value));
    });
    static final MenuDefinition<Turned> TURNED = Menus.define(MaxtechLib.MOD_ID, "test_facing_turned", Turned.class,
            m -> {
                // Fluids are synced but not shown, so clicks on them must be ignored.
                PortValue value = m.port(host -> port(), Turned::facing, ResourceKinds.ITEM, ResourceKinds.FLUID);
                TURNED_PORT.set(value);
                m.layout(Layout.faces(value, ResourceKinds.ITEM));
            });

    static {
        Registrar<net.minecraft.world.inventory.MenuType<?>> menus = Registrars.create(MaxtechLib.MOD_ID,
                Registries.MENU);
        DISPENSER.register(menus);
        PLAIN.register(menus);
        TURNED.register(menus);
        FUNCTIONS.register(NAMES[0], () -> SideConfigFacingGameTests::sixWays);
        FUNCTIONS.register(NAMES[1], () -> SideConfigFacingGameTests::none);
        FUNCTIONS.register(NAMES[2], () -> SideConfigFacingGameTests::getter);
        FUNCTIONS.register(NAMES[3], () -> SideConfigFacingGameTests::hiddenKind);
    }

    private SideConfigFacingGameTests() {
    }

    /** One unstarted port for every host here; only its settings are read. */
    private static TransferPort port() {
        TransferPort built = port;
        if (built == null) {
            built = TransferPorts.builder().items(ItemSlots.builder(1).build(slot -> {
            }), Set.of(PortFaceMode.PUSH)).fluids(FluidTank.create(1000, () -> {
            }), Set.of(PortFaceMode.PULL)).build();
            port = built;
        }
        return built;
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechLib.id("side_config_facing"));
        for (String name : NAMES) {
            Identifier id = MaxtechLib.id(name);
            event.registerTest(id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                    new TestData<>(environment, LibGameTests.EMPTY_STRUCTURE, 20, 0, true)));
        }
    }

    @SuppressWarnings("removal") // the only way to a ServerPlayer with a connection
    private static <H> Optional<Direction> facingSeen(GameTestHelper helper, MenuDefinition<H> definition, H host,
            PortValue value) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try (MenuTesting.Session<H> session = MenuTesting.open(definition, player, host)) {
            session.tick();
            return value.facing(session.clientValues());
        } finally {
            helper.getLevel().getServer().getPlayerList().remove(player);
        }
    }

    /** A kind the port allows but no faces node shows takes no clicks; a shown kind does. */
    @SuppressWarnings("removal") // the only way to a ServerPlayer with a connection
    private static void hiddenKind(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        PortValue value = TURNED_PORT.get();
        try (MenuTesting.Session<Turned> session = MenuTesting.open(TURNED, player, new Turned(Optional.empty()))) {
            helper.assertFalse(session.pressFace(value, ResourceKinds.FLUID, Direction.UP, PortFaceMode.PULL),
                    "click on a hidden kind");
            helper.assertValueEqual(port().settings().mode(ResourceKinds.FLUID, Direction.UP), PortFaceMode.OFF,
                    "hidden kind's face");
            helper.assertTrue(session.pressFace(value, ResourceKinds.ITEM, Direction.UP, PortFaceMode.PUSH),
                    "click on a shown kind");
            helper.assertValueEqual(port().settings().mode(ResourceKinds.ITEM, Direction.UP), PortFaceMode.PUSH,
                    "shown kind's face");
            port().setSettings(port().settings().withMode(ResourceKinds.ITEM, Direction.UP, PortFaceMode.OFF));
        } finally {
            helper.getLevel().getServer().getPlayerList().remove(player);
        }
        helper.succeed();
    }

    /** A dispenser facing each of the six ways reaches the client with that facing. */
    private static void sixWays(GameTestHelper helper) {
        for (Direction facing : Direction.values()) {
            helper.setBlock(POS, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, facing));
            if (!(helper.getLevel().getBlockEntity(helper.absolutePos(POS)) instanceof DispenserBlockEntity host)) {
                throw helper.assertionException("No dispenser block entity");
            }
            helper.assertValueEqual(facingSeen(helper, DISPENSER, host, DISPENSER_PORT.get()), Optional.of(facing),
                    "facing of a dispenser facing " + facing);
        }
        helper.succeed();
    }

    /** A host that is not a block entity has no facing, so the screen names faces absolutely. */
    private static void none(GameTestHelper helper) {
        helper.assertValueEqual(facingSeen(helper, PLAIN, new Plain(), PLAIN_PORT.get()), Optional.empty(),
                "facing of a plain host");
        helper.succeed();
    }

    /** The getter overload sends the host's own facing, or none. */
    private static void getter(GameTestHelper helper) {
        helper.assertValueEqual(facingSeen(helper, TURNED, new Turned(Optional.of(Direction.DOWN)), TURNED_PORT.get()),
                Optional.of(Direction.DOWN), "facing from the getter");
        helper.assertValueEqual(facingSeen(helper, TURNED, new Turned(Optional.empty()), TURNED_PORT.get()),
                Optional.empty(), "no facing from the getter");
        helper.succeed();
    }
}
