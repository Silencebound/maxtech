package dev.silencebound.maxtech.lib.neoforge.gametest;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.menu.EnergyValue;
import dev.silencebound.maxtech.lib.api.menu.EnumValue;
import dev.silencebound.maxtech.lib.api.menu.IntValue;
import dev.silencebound.maxtech.lib.api.menu.LongValue;
import dev.silencebound.maxtech.lib.api.menu.MenuValueView;
import dev.silencebound.maxtech.lib.api.menu.MenuValues;
import dev.silencebound.maxtech.lib.api.menu.ProgressValue;
import dev.silencebound.maxtech.lib.api.menu.TankValue;
import dev.silencebound.maxtech.lib.api.inventory.FluidTank;
import dev.silencebound.maxtech.lib.api.inventory.ResourceTank;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.KindKey;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import dev.silencebound.maxtech.lib.menu.MenuValuesPayload;
import dev.silencebound.maxtech.lib.menu.ToolkitMenu;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.jspecify.annotations.Nullable;

/**
 * Toolkit menu values on a running server: the client mirror follows the server through the real payload codec (fluid
 * components included), and an idle menu sends nothing. Payloads are collected instead of sent, since GameTest mock
 * players cannot open NeoForge menus; the client menu is built directly and fed what the server sent.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class MenuValuesGameTests {
    private static final String[] NAMES = {"menu_values_mirror_follows_server", "menu_values_idle_sends_nothing",
            "menu_values_equal_fluid_sends_nothing", "menu_values_resync_sends_all", "menu_values_frozen_level_syncs",
            "menu_values_kind_tanks_sync"};
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);
    private static final int TICKS = 30;

    private static final MenuValues.Builder<Host> BUILDER = MenuValues.builder();
    private static final IntValue COUNT = BUILDER.intValue(h -> h.count);
    private static final LongValue BIG = BUILDER.longValue(h -> h.big);
    private static final EnumValue<Direction> FACING = BUILDER.enumValue(Direction.class, h -> h.facing);
    private static final EnergyValue ENERGY = BUILDER.energy(h -> h.stored, h -> h.capacity);
    private static final ProgressValue PROGRESS = BUILDER.progress(h -> h.done, h -> h.total);
    private static final TankValue TANK = BUILDER.tank(h -> h.fluid, h -> h.amount, h -> h.tankCapacity);
    private static final MenuValues<Host> VALUES = BUILDER.build();

    /** A tank whose getter builds a new but equal fluid key with components on every read. */
    private static final MenuValues.Builder<Host> FRESH_BUILDER = MenuValues.builder();
    private static final TankValue FRESH_TANK = FRESH_BUILDER.tank(
            h -> Optional.of(FluidKey.of(Fluids.LAVA,
                    DataComponentPatch.builder().set(DataComponents.CUSTOM_NAME, Component.literal("Hot")).build())),
            h -> h.amount, h -> h.tankCapacity);
    private static final MenuValues<Host> FRESH_VALUES = FRESH_BUILDER.build();

    /** Tanks of the GameTest kind {@code maxtech_lib:test_glow}, given as a key and as a Lib tank, and a fluid tank. */
    private static final MenuValues.Builder<KindHost> KIND_BUILDER = MenuValues.builder();
    private static final TankValue GLOW = KIND_BUILDER.kindTank(h -> h.glow, h -> h.glowAmount, h -> 1000);
    private static final TankValue GLOW_TANK = KIND_BUILDER.tank(ConduitApiGameTests.TEST_KIND, h -> h.glowTank);
    private static final TankValue FLUID_TANK = KIND_BUILDER.tank(ResourceKinds.FLUID, h -> h.fluidTank);
    private static final MenuValues<KindHost> KIND_VALUES = KIND_BUILDER.build();

    static {
        FUNCTIONS.register(NAMES[0], () -> MenuValuesGameTests::mirrorFollowsServer);
        FUNCTIONS.register(NAMES[1], () -> MenuValuesGameTests::idleSendsNothing);
        FUNCTIONS.register(NAMES[2], () -> MenuValuesGameTests::equalFluidSendsNothing);
        FUNCTIONS.register(NAMES[3], () -> MenuValuesGameTests::resyncSendsAll);
        FUNCTIONS.register(NAMES[4], () -> MenuValuesGameTests::frozenLevelSyncs);
        FUNCTIONS.register(NAMES[5], () -> MenuValuesGameTests::kindTanksSync);
    }

    private MenuValuesGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechLib.id("menu_values"));
        for (String name : NAMES) {
            Identifier id = MaxtechLib.id(name);
            event.registerTest(id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                    new TestData<>(environment, LibGameTests.EMPTY_STRUCTURE, TICKS + 20, 0, true)));
        }
    }

    /** What a block entity would hold. */
    private static final class Host {
        int count;
        long big;
        Direction facing = Direction.DOWN;
        long stored;
        long capacity = 10_000_000_000L;
        long done;
        long total = 200;
        Optional<FluidKey> fluid = Optional.empty();
        long amount;
        long tankCapacity = 8000;
    }

    /** A block entity with tanks of more than one kind. */
    private static final class KindHost {
        Optional<KindKey<?>> glow = Optional.empty();
        long glowAmount;
        final ResourceTank<Integer> glowTank = ResourceTank.create(ConduitApiGameTests.TEST_KIND, 2000, () -> {
        });
        FluidTank fluidTank = FluidTank.create(8000, () -> {
        });
    }

    private static final class KindMenu extends ToolkitMenu<KindHost> {
        KindMenu(int containerId, Player player, @Nullable KindHost host) {
            super(null, containerId, player, KIND_VALUES, host);
        }

        @Override
        public ItemStack quickMoveStack(Player player, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }

    private static final class TestMenu extends ToolkitMenu<Host> {
        TestMenu(int containerId, Player player, MenuValues<Host> values, Host host) {
            super(null, containerId, player, values, host);
        }

        TestMenu(int containerId, Player player, MenuValues<Host> values) {
            super(null, containerId, player, values, null);
        }

        @Override
        public ItemStack quickMoveStack(Player player, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }

    /** Sends {@code payload} through the registered codec, as the connection would. */
    private static MenuValuesPayload overTheWire(GameTestHelper helper, MenuValuesPayload payload) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess());
        MenuValuesPayload.CODEC.encode(buf, payload);
        MenuValuesPayload decoded = MenuValuesPayload.CODEC.decode(buf);
        helper.assertValueEqual(buf.readableBytes(), 0, "whole payload read");
        return decoded;
    }

    /**
     * Every tick the host changes some values (a long past {@link Integer#MAX_VALUE}, a fluid with a custom name) and
     * the menu broadcasts twice; each payload goes through the codec into a client menu, which ends equal to the
     * server. A payload for another container id leaves the client alone.
     */
    private static void mirrorFollowsServer(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Host host = new Host();
        TestMenu server = new TestMenu(5, player, VALUES, host);
        TestMenu client = new TestMenu(5, player, VALUES);
        List<MenuValuesPayload> sent = new ArrayList<>();
        server.sendValuesTo(sent::add);
        FluidKey hot = FluidKey.of(Fluids.LAVA,
                DataComponentPatch.builder().set(DataComponents.CUSTOM_NAME, Component.literal("Hot")).build());
        int[] ticks = new int[1];
        helper.onEachTick(() -> {
            if (ticks[0] >= TICKS) {
                return;
            }
            ticks[0]++;
            int t = ticks[0];
            host.count = -t;
            host.big = Integer.MAX_VALUE + 1_000_000L * t;
            host.facing = t % 2 == 0 ? Direction.NORTH : Direction.EAST;
            if (t % 3 == 0) {
                host.stored = 5_000_000_000L + t;
                host.done = t;
            }
            host.fluid = t > TICKS / 2 ? Optional.of(hot) : Optional.of(FluidKey.of(Fluids.WATER));
            host.amount = 100L * t;
            server.broadcastChanges();
            server.broadcastChanges();
            int before = sent.size();
            for (MenuValuesPayload payload : sent) {
                helper.assertTrue(MenuValuesPayload.apply(overTheWire(helper, payload), client,
                        helper.getLevel().registryAccess()), "payload applied");
            }
            sent.clear();
            helper.assertTrue(before <= 1, "at most one payload a tick, got " + before);
        });
        helper.runAfterDelay(TICKS + 5, () -> {
            helper.assertValueEqual(ticks[0], TICKS, "ticks with changes");
            assertSame(helper, server.values(), client.values());
            helper.assertValueEqual(client.values().fluid(TANK), Optional.of(hot), "fluid with its custom name");
            helper.assertValueEqual(client.values().get(BIG), Integer.MAX_VALUE + 1_000_000L * TICKS, "long");

            TestMenu other = new TestMenu(6, player, VALUES);
            host.count = 99;
            server.broadcastChanges();
            helper.assertValueEqual(sent.size(), 1, "one payload for the change");
            helper.assertFalse(MenuValuesPayload.apply(sent.getFirst(), other, helper.getLevel().registryAccess()),
                    "payload for container 5 refused by container 6");
            helper.assertValueEqual(other.values().get(COUNT), 0, "container 6 unchanged");
            helper.succeed();
        });
    }

    private static void assertSame(GameTestHelper helper, MenuValueView server, MenuValueView client) {
        helper.assertValueEqual(client.get(COUNT), server.get(COUNT), "int");
        helper.assertValueEqual(client.get(BIG), server.get(BIG), "long");
        helper.assertValueEqual(client.get(FACING), server.get(FACING), "enum");
        helper.assertValueEqual(client.stored(ENERGY), server.stored(ENERGY), "energy stored");
        helper.assertValueEqual(client.capacity(ENERGY), server.capacity(ENERGY), "energy capacity");
        helper.assertValueEqual(client.current(PROGRESS), server.current(PROGRESS), "progress");
        helper.assertValueEqual(client.total(PROGRESS), server.total(PROGRESS), "progress total");
        helper.assertValueEqual(client.fluid(TANK), server.fluid(TANK), "tank fluid");
        helper.assertValueEqual(client.amount(TANK), server.amount(TANK), "tank amount");
        helper.assertValueEqual(client.capacity(TANK), server.capacity(TANK), "tank capacity");
    }

    /**
     * The first broadcast sends everything; idle ticks send nothing; a change sends one payload, and a second change in
     * the same tick waits for the next tick instead of sending twice.
     */
    private static void idleSendsNothing(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Host host = new Host();
        TestMenu server = new TestMenu(3, player, VALUES, host);
        List<MenuValuesPayload> sent = new ArrayList<>();
        server.sendValuesTo(sent::add);
        int[] ticks = new int[1];
        helper.onEachTick(() -> {
            ticks[0]++;
            int t = ticks[0];
            if (t == 1) {
                server.broadcastChanges();
                helper.assertValueEqual(sent.size(), 1, "first tick sends all values, though all are defaults");
            } else if (t <= 21) {
                server.broadcastChanges();
                helper.assertValueEqual(sent.size(), 1, "idle tick " + t);
            } else if (t == 22) {
                host.amount = 500;
                server.broadcastChanges();
                host.amount = 600;
                server.broadcastChanges();
                server.broadcastChanges();
                helper.assertValueEqual(sent.size(), 2, "one payload in the tick of two changes");
            } else if (t == 23) {
                server.broadcastChanges();
                helper.assertValueEqual(sent.size(), 3, "the later change next tick");
                TestMenu client = new TestMenu(3, player, VALUES);
                for (MenuValuesPayload payload : sent) {
                    MenuValuesPayload.apply(overTheWire(helper, payload), client, helper.getLevel().registryAccess());
                }
                helper.assertValueEqual(client.values().amount(TANK), 600L, "latest amount");
                helper.assertValueEqual(client.values().total(PROGRESS), 200L, "first payload's progress total");
            } else if (t <= 33) {
                server.broadcastChanges();
                helper.assertValueEqual(sent.size(), 3, "idle again");
            } else if (t == 34) {
                helper.succeed();
            }
        });
    }

    /** Only the tank getter runs, and it returns a new but equal fluid key each tick: one payload, then none. */
    private static void equalFluidSendsNothing(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        TestMenu server = new TestMenu(4, player, FRESH_VALUES, new Host());
        List<MenuValuesPayload> sent = new ArrayList<>();
        server.sendValuesTo(sent::add);
        int[] ticks = new int[1];
        helper.onEachTick(() -> {
            ticks[0]++;
            if (ticks[0] <= 20) {
                server.broadcastChanges();
                helper.assertValueEqual(sent.size(), 1, "payloads by tick " + ticks[0]);
            } else if (ticks[0] == 21) {
                TestMenu client = new TestMenu(4, player, FRESH_VALUES);
                MenuValuesPayload.apply(overTheWire(helper, sent.getFirst()), client,
                        helper.getLevel().registryAccess());
                helper.assertValueEqual(client.values().fluid(FRESH_TANK), server.values().fluid(FRESH_TANK),
                        "fluid with components");
                helper.succeed();
            }
        });
    }

    /** After idle ticks, a resync ({@code sendAllDataToRemote}) sends every value again in the next payload. */
    private static void resyncSendsAll(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Host host = new Host();
        host.count = 7;
        host.stored = 1234;
        host.fluid = Optional.of(FluidKey.of(Fluids.WATER));
        host.amount = 1000;
        TestMenu server = new TestMenu(8, player, VALUES, host);
        List<MenuValuesPayload> sent = new ArrayList<>();
        server.sendValuesTo(sent::add);
        int[] ticks = new int[1];
        helper.onEachTick(() -> {
            ticks[0]++;
            int t = ticks[0];
            if (t <= 5) {
                server.broadcastChanges();
                helper.assertValueEqual(sent.size(), 1, "first payload, then idle");
            } else if (t == 6) {
                sent.clear();
                server.sendAllDataToRemote();
                server.broadcastChanges();
                helper.assertValueEqual(sent.size(), 1, "resync payload");
                TestMenu client = new TestMenu(8, player, VALUES);
                MenuValuesPayload.apply(overTheWire(helper, sent.getFirst()), client,
                        helper.getLevel().registryAccess());
                assertSame(helper, server.values(), client.values());
                helper.assertValueEqual(client.values().get(COUNT), 7, "unchanged int resent");
                server.broadcastChanges();
            } else if (t == 7) {
                server.broadcastChanges();
                helper.assertValueEqual(sent.size(), 1, "idle after the resync");
                helper.succeed();
            }
        });
    }

    /**
     * Under {@code /tick freeze} the level's time stands while the server still ticks players, which broadcast their
     * menus. Several server ticks within one level tick each send their change.
     */
    private static void frozenLevelSyncs(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        Host host = new Host();
        TestMenu server = new TestMenu(9, player, VALUES, host);
        TestMenu client = new TestMenu(9, player, VALUES);
        List<MenuValuesPayload> sent = new ArrayList<>();
        server.sendValuesTo(sent::add);
        long[] serverTicks = new long[1];
        server.countTicksWith(() -> serverTicks[0]);
        for (int t = 1; t <= 5; t++) {
            serverTicks[0]++;
            host.count = t;
            server.broadcastChanges();
            helper.assertValueEqual(sent.size(), t, "payloads after server tick " + t);
        }
        for (MenuValuesPayload payload : sent) {
            MenuValuesPayload.apply(overTheWire(helper, payload), client, helper.getLevel().registryAccess());
        }
        helper.assertValueEqual(client.values().get(COUNT), 5, "last count");
        helper.succeed();
    }

    /**
     * Tanks of the GameTest kind sync through the real payload codec, whether declared by key or over a Lib tank; a
     * fluid tank whose saved fluid no longer loads arrives as unknown with its saved amount, not as empty, and once its
     * contents are discarded and refilled it reads as a fluid again. Idle ticks send nothing.
     */
    private static void kindTanksSync(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        KindHost host = new KindHost();
        host.glow = Optional.of(new KindKey<>(ConduitApiGameTests.TEST_KIND, 37));
        host.glowAmount = 600;
        host.glowTank.set(40, 1500);
        FluidTank saved = FluidTank.create(8000, () -> {
        });
        saved.set(FluidKey.of(Fluids.WATER), 2500);
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        saved.save(out);
        CompoundTag missing = out.buildResult();
        CompoundTag resource = missing.getCompoundOrEmpty("resource");
        resource.putString("id", "gone_mod:goo");
        missing.put("resource", resource);
        host.fluidTank.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, missing));
        helper.assertTrue(host.fluidTank.unknown(), "saved fluid still loads");

        KindMenu server = new KindMenu(11, player, host);
        KindMenu client = new KindMenu(11, player, null);
        List<MenuValuesPayload> sent = new ArrayList<>();
        server.sendValuesTo(sent::add);
        Runnable deliver = () -> {
            for (MenuValuesPayload payload : sent) {
                helper.assertTrue(MenuValuesPayload.apply(overTheWire(helper, payload), client, registries),
                        "payload applied");
            }
            sent.clear();
        };
        server.broadcastChanges();
        deliver.run();
        MenuValueView view = client.values();
        helper.assertValueEqual(view.resource(GLOW), Optional.of(new KindKey<>(ConduitApiGameTests.TEST_KIND, 37)),
                "glow by key");
        helper.assertValueEqual(view.amount(GLOW), 600L, "glow amount");
        helper.assertValueEqual(view.resource(GLOW_TANK), Optional.of(new KindKey<>(ConduitApiGameTests.TEST_KIND, 40)),
                "glow tank");
        helper.assertValueEqual(view.amount(GLOW_TANK), 1500L, "glow tank amount");
        helper.assertValueEqual(view.capacity(GLOW_TANK), 2000L, "glow tank capacity");
        boolean refused = false;
        try {
            view.fluid(GLOW_TANK);
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        helper.assertTrue(refused, "fluid read of a glow tank not refused");
        KindKey<?> unknown = view.resource(FLUID_TANK).orElseThrow();
        helper.assertTrue(unknown.isUnknown(), "unloaded fluid not unknown: " + unknown);
        helper.assertValueEqual(unknown.kindId(), ResourceKinds.FLUID.id(), "unknown contents' kind");
        helper.assertValueEqual(view.amount(FLUID_TANK), 2500L, "unknown contents' saved amount");
        helper.assertValueEqual(view.fluid(FLUID_TANK), Optional.empty(), "unknown contents as a fluid");

        int[] ticks = new int[1];
        helper.onEachTick(() -> {
            ticks[0]++;
            if (ticks[0] <= 10) {
                server.broadcastChanges();
                helper.assertValueEqual(sent.size(), 0, "idle tick " + ticks[0] + " sent");
            } else if (ticks[0] == 11) {
                host.fluidTank.discardUnknown();
                host.fluidTank.set(FluidKey.of(Fluids.LAVA), 700);
                host.glowTank.set(41, 1500);
                server.broadcastChanges();
                helper.assertValueEqual(sent.size(), 1, "one payload for the changes");
                deliver.run();
                helper.assertValueEqual(view.fluid(FLUID_TANK), Optional.of(FluidKey.of(Fluids.LAVA)), "refilled");
                helper.assertValueEqual(view.resource(FLUID_TANK),
                        Optional.of(new KindKey<>(ResourceKinds.FLUID, FluidKey.of(Fluids.LAVA))), "refilled key");
                helper.assertValueEqual(view.amount(FLUID_TANK), 700L, "refilled amount");
                helper.assertValueEqual(view.resource(GLOW_TANK),
                        Optional.of(new KindKey<>(ConduitApiGameTests.TEST_KIND, 41)), "glow tank changed");
                helper.succeed();
            }
        });
    }
}
