package dev.silencebound.maxtech.lib.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.conduit.ConduitEntry;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceMode;
import dev.silencebound.maxtech.lib.api.conduit.ConduitHost;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.conduit.Conduits;
import dev.silencebound.maxtech.lib.api.network.Network;
import dev.silencebound.maxtech.lib.api.network.NetworkLogic;
import dev.silencebound.maxtech.lib.api.network.NetworkManager;
import dev.silencebound.maxtech.lib.api.network.NetworkType;
import dev.silencebound.maxtech.lib.api.registry.Registrar;
import dev.silencebound.maxtech.lib.api.registry.Registrars;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * An addon's conduit through the api alone ({@link Conduits}, {@code lib.api.network}), as Farms builds its lab tubes:
 * no Lib internals here on purpose, so a missing api piece fails to compile. The test conduit refuses vertical links
 * through its connection rule and takes a plain wrench itself.
 */
@EventBusSubscriber(modid = ConduitApiGameTests.MOD_ID)
public final class ConduitApiGameTests {
    static final String MOD_ID = "maxtech_lib";
    private static final Identifier STRUCTURE = Identifier.fromNamespaceAndPath(MOD_ID, "empty_7x2x3");
    private static final Identifier NETWORK_ID = Identifier.fromNamespaceAndPath(MOD_ID, "test_api_network");

    private static final AtomicInteger RULE_CALLS = new AtomicInteger();
    private static final AtomicInteger WRENCH_CALLS = new AtomicInteger();

    private static final NetworkType NETWORK = NetworkType.register(NETWORK_ID,
            level -> Conduits.connectivity(level, NETWORK_ID), level -> new NetworkLogic() {
                @Override
                public void tick(Network network) {
                }
            });

    private static final ConduitEntry<ConduitType> CONDUIT = Conduits
            .builder(MOD_ID, "test_api_conduit", item -> new ConduitType(NETWORK_ID, "test", 0x55AA55, 0.25F, item))
            .creativeTab(CreativeModeTabs.REDSTONE_BLOCKS).connectionRule((conduit, side, neighbour) -> {
                RULE_CALLS.incrementAndGet();
                return side.getAxis() != Direction.Axis.Y;
            }).onWrench((level, conduit, player, hit, dismantle) -> {
                WRENCH_CALLS.incrementAndGet();
                return dismantle ? InteractionResult.PASS : InteractionResult.SUCCESS;
            }).register();

    private static final Registrar<Consumer<GameTestHelper>> FUNCTIONS = Registrars.create(MOD_ID,
            Registries.TEST_FUNCTION);
    private static final List<String> TESTS = new ArrayList<>();

    static {
        test("conduit_api_network", ConduitApiGameTests::network);
        test("conduit_api_wrench_hook", ConduitApiGameTests::wrenchHook);
        test("conduit_api_creative_tab", ConduitApiGameTests::creativeTab);
    }

    private ConduitApiGameTests() {
    }

    private static void test(String name, Consumer<GameTestHelper> function) {
        TESTS.add(name);
        FUNCTIONS.register(name, () -> function);
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(Identifier.fromNamespaceAndPath(MOD_ID, "conduit_api"));
        for (String name : TESTS) {
            Identifier id = Identifier.fromNamespaceAndPath(MOD_ID, name);
            event.registerTest(id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                    new TestData<>(environment, STRUCTURE, 100, 0, true)));
        }
    }

    /**
     * A row of three conduits forms one network of the addon's type; a fourth on top of the middle one is refused by
     * the rule, so it stays a network of its own and the middle conduit shows no face towards it.
     */
    private static void network(GameTestHelper helper) {
        BlockPos[] row = {new BlockPos(1, 0, 1), new BlockPos(2, 0, 1), new BlockPos(3, 0, 1)};
        BlockPos top = new BlockPos(2, 1, 1);
        int calls = RULE_CALLS.get();
        for (BlockPos pos : row) {
            helper.assertTrue(Conduits.place(helper.getLevel(), helper.absolutePos(pos), CONDUIT.type().get()),
                    "conduit not placed at " + pos);
        }
        helper.assertTrue(Conduits.place(helper.getLevel(), helper.absolutePos(top), CONDUIT.type().get()),
                "conduit not placed on top");
        helper.assertTrue(RULE_CALLS.get() > calls, "the connection rule was not asked");
        NetworkManager manager = NetworkManager.get(helper.getLevel());
        helper.succeedWhen(() -> {
            Network first = manager.network(NETWORK, helper.absolutePos(row[0]));
            helper.assertTrue(first != null, "the first conduit has no network");
            for (BlockPos pos : row) {
                helper.assertTrue(first == manager.network(NETWORK, helper.absolutePos(pos)),
                        "conduit at " + pos + " is not in the row's network");
            }
            Network above = manager.network(NETWORK, helper.absolutePos(top));
            helper.assertTrue(above != null && above != first, "the refused conduit joined the row's network");
            ConduitHost middle = host(helper, row[1]);
            helper.assertValueEqual(2, Integer.bitCount(middle.connections()), "links of the middle conduit");
            helper.assertValueEqual(0, middle.conduitFaces() & (1 << Direction.UP.get3DDataValue()),
                    "a refused side counted as a conduit face");
            helper.assertTrue(middle.findFacade().isEmpty() && middle.dyeColour().isEmpty(),
                    "a fresh conduit has a facade or a colour");
        });
    }

    /** The hook takes a plain wrench (the face keeps its mode); a dismantle passes through to Lib, which removes it. */
    private static void wrenchHook(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 0, 1);
        BlockPos abs = helper.absolutePos(pos);
        helper.assertTrue(Conduits.place(helper.getLevel(), abs, CONDUIT.type().get()), "conduit not placed");
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(abs), Direction.NORTH, abs, false);
        int calls = WRENCH_CALLS.get();
        ConduitFaceMode before = host(helper, pos).faceMode(Direction.NORTH);
        helper.assertTrue(Conduits.useWrench(helper.getLevel(), abs, player, hit, false).consumesAction(),
                "the wrench did nothing");
        helper.assertValueEqual(calls + 1, WRENCH_CALLS.get(), "wrench hook calls");
        helper.assertValueEqual(before, host(helper, pos).faceMode(Direction.NORTH), "the face changed under the hook");
        Conduits.useWrench(helper.getLevel(), abs, player, hit, true);
        helper.assertValueEqual(calls + 2, WRENCH_CALLS.get(), "wrench hook calls after a dismantle");
        helper.assertTrue(Conduits.host(helper.getLevel(), abs).isEmpty(), "a dismantle left the conduit");
        helper.assertTrue(player.getInventory().contains(new ItemStack(CONDUIT.item().get())),
                "the dismantled conduit is not in the inventory");
        helper.succeed();
    }

    /** The builder's creative tab lists the conduit's item. */
    private static void creativeTab(GameTestHelper helper) {
        CreativeModeTab tab = BuiltInRegistries.CREATIVE_MODE_TAB.getValueOrThrow(CreativeModeTabs.REDSTONE_BLOCKS);
        tab.buildContents(new CreativeModeTab.ItemDisplayParameters(helper.getLevel().enabledFeatures(), false,
                helper.getLevel().registryAccess()));
        boolean listed = false;
        for (ItemStack stack : tab.getDisplayItems()) {
            listed |= stack.is(CONDUIT.item().get());
        }
        helper.assertTrue(listed, "the conduit is not in its creative tab");
        helper.assertTrue(Conduits.blockOf(CONDUIT.type().get()).isPresent(), "the type has no block");
        helper.succeed();
    }

    private static ConduitHost host(GameTestHelper helper, BlockPos pos) {
        return Conduits.host(helper.getLevel(), helper.absolutePos(pos))
                .orElseThrow(() -> helper.assertionException("no conduit at " + pos));
    }
}
