package dev.silencebound.maxtech.lib.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.api.conduit.ConduitContents;
import dev.silencebound.maxtech.lib.api.conduit.ConduitEntry;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceMode;
import dev.silencebound.maxtech.lib.api.conduit.ConduitHost;
import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import dev.silencebound.maxtech.lib.api.conduit.ConduitView;
import dev.silencebound.maxtech.lib.api.conduit.Conduits;
import dev.silencebound.maxtech.lib.api.network.Network;
import dev.silencebound.maxtech.lib.api.network.NetworkLogic;
import dev.silencebound.maxtech.lib.api.network.NetworkManager;
import dev.silencebound.maxtech.lib.api.network.NetworkType;
import dev.silencebound.maxtech.lib.api.registry.Registrar;
import dev.silencebound.maxtech.lib.api.registry.Registrars;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.EmptyFluid;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * An addon's conduit through the api alone ({@link Conduits}, {@code lib.api.network}), as Farms builds its lab tubes:
 * no Lib internals here on purpose, so a missing api piece fails to compile. The test conduit refuses links on its top
 * face through its connection rule (one-sided, so the neighbour's rule must be asked too) and takes a plain wrench
 * itself.
 */
@EventBusSubscriber(modid = ConduitApiGameTests.MOD_ID)
public final class ConduitApiGameTests {
    static final String MOD_ID = "maxtech_lib";
    private static final Identifier STRUCTURE = Identifier.fromNamespaceAndPath(MOD_ID, "empty_7x2x3");
    // The saved key is part of Lib's save format, not the api; pinned here so a rename shows up.
    private static final String RENDER_VALUE = "render_value";
    private static final String CONTENTS = "contents";
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
            .look(ConduitLook.DEFAULT).creativeTab(CreativeModeTabs.REDSTONE_BLOCKS)
            .connectionRule((conduit, side, neighbour) -> {
                RULE_CALLS.incrementAndGet();
                return side != Direction.UP;
            }).onWrench((level, conduit, player, hit, dismantle) -> {
                WRENCH_CALLS.incrementAndGet();
                return dismantle ? InteractionResult.PASS : InteractionResult.SUCCESS;
            }).register();

    /** Ships a loot table of its own ({@code data/maxtech_lib/loot_table/blocks/test_api_conduit_loot.json}). */
    private static final ConduitEntry<ConduitType> LOOT_CONDUIT = Conduits
            .builder(MOD_ID, "test_api_conduit_loot", item -> new ConduitType(NETWORK_ID, "loot", 0x55AA55, item))
            .register();

    /**
     * Lib's shell look on an original clear glass sprite ({@code textures/block/glass_tube.png}), borders in the type's
     * colour; the dev client's check of the shell look.
     */
    static final ConduitEntry<ConduitType> GLASS_TUBE = Conduits
            .builder(MOD_ID, "test_glass_tube", item -> new ConduitType(NETWORK_ID, "glass", 0x6E8A94, 0.375F, item))
            .look(ConduitLook.shell(Identifier.fromNamespaceAndPath(MOD_ID, "block/glass_tube")))
            .creativeTab(CreativeModeTabs.REDSTONE_BLOCKS).register();

    /** Drawn through a model hook that overrides nothing ({@link HookedTubeDemoClient}, client only). */
    static final ConduitEntry<ConduitType> HOOKED_TUBE = Conduits
            .builder(MOD_ID, "test_hooked_tube", item -> new ConduitType(NETWORK_ID, "hooked", 0xAA7744, 0.25F, item))
            .look(ConduitLook.DEFAULT).customModel(false).register();

    private static final Identifier ATTACH_NETWORK_ID = Identifier.fromNamespaceAndPath(MOD_ID, "test_attach_network");
    // onNodeInvalidated calls by packed position; server thread only.
    private static final Map<Long, Integer> INVALIDATIONS = new HashMap<>();

    private static final NetworkType ATTACH_NETWORK = NetworkType.register(ATTACH_NETWORK_ID,
            level -> Conduits.connectivity(level, ATTACH_NETWORK_ID), level -> new NetworkLogic() {
                @Override
                public void onNodeInvalidated(Network network, long pos) {
                    INVALIDATIONS.merge(pos, 1, Integer::sum);
                }

                @Override
                public void tick(Network network) {
                }
            });

    /** Notices the block entities beside it. */
    private static final ConduitEntry<ConduitType> ATTACHING = Conduits
            .builder(MOD_ID, "test_attaching_conduit",
                    item -> new ConduitType(ATTACH_NETWORK_ID, "attaching", 0x5555AA, 0.25F, item))
            .attachesToBlocks().register();

    /** The same network without the opt-in. */
    private static final ConduitEntry<ConduitType> NOT_ATTACHING = Conduits.builder(MOD_ID, "test_plain_attach_conduit",
            item -> new ConduitType(ATTACH_NETWORK_ID, "plain", 0x5555AA, 0.25F, item)).register();

    private static final Registrar<Consumer<GameTestHelper>> FUNCTIONS = Registrars.create(MOD_ID,
            Registries.TEST_FUNCTION);
    private static final List<String> TESTS = new ArrayList<>();
    private static final Map<String, Integer> MAX_TICKS = new HashMap<>();

    static {
        test("conduit_api_network", ConduitApiGameTests::network);
        test("conduit_api_wrench_hook", ConduitApiGameTests::wrenchHook);
        test("conduit_api_creative_tab", ConduitApiGameTests::creativeTab);
        test("conduit_api_drops_itself", ConduitApiGameTests::dropsItself);
        test("conduit_api_loot_table_wins", ConduitApiGameTests::lootTableWins);
        test("conduit_api_render_value_syncs", ConduitApiGameTests::renderValueSyncs);
        test("conduit_api_render_value_saved", ConduitApiGameTests::renderValueSaved);
        test("conduit_api_contents_syncs", ConduitApiGameTests::contentsSyncs);
        test("conduit_api_contents_saved", ConduitApiGameTests::contentsSaved);
        test("conduit_api_custom_model_places", ConduitApiGameTests::customModelPlaces);
        test("conduit_api_shell_places", ConduitApiGameTests::shellPlaces);
        test("conduit_api_shell_contents_places", ConduitApiGameTests::shellContentsPlaces);
        test("conduit_api_attach_place_remove", ConduitApiGameTests::attachPlaceRemove);
        test("conduit_api_attach_unflagged", ConduitApiGameTests::attachUnflagged);
        test("conduit_api_attach_chunk_load", ConduitApiGameTests::attachChunkLoad);
        test("conduit_api_attach_link_and_block", ConduitApiGameTests::attachLinkAndBlock);
        test("conduit_api_attach_ignores_conduits", ConduitApiGameTests::attachIgnoresConduits);
        // These two read what the conduit holds, which only Lib's block entity shows.
        test("conduit_attach_chunk_unload", ConduitApiGameTests::attachChunkUnload);
        test("conduit_attach_chunks_together", ConduitApiGameTests::attachChunksTogether);
        // Force chunks far from the test grid and wait for them to load and unload.
        MAX_TICKS.put("conduit_api_attach_chunk_load", 600);
        MAX_TICKS.put("conduit_attach_chunk_unload", 600);
        MAX_TICKS.put("conduit_attach_chunks_together", 1200);
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
                    new TestData<>(environment, STRUCTURE, MAX_TICKS.getOrDefault(name, 100), 0, true)));
        }
    }

    /**
     * A row of three conduits forms one network of the addon's type; a fourth on top of the middle one is refused by
     * the middle one's rule, so it stays a network of its own: neither side links or shows a face, although the top
     * conduit's own rule allows its bottom face.
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
            ConduitHost upper = host(helper, top);
            helper.assertValueEqual(0, upper.connections() & (1 << Direction.DOWN.get3DDataValue()),
                    "the top conduit linked down although the conduit below refuses");
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

    /** A conduit whose mod ships no loot table drops its own item when mined. */
    private static void dropsItself(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 0, 1);
        helper.assertTrue(Conduits.place(helper.getLevel(), helper.absolutePos(pos), CONDUIT.type().get()),
                "conduit not placed");
        helper.getLevel().destroyBlock(helper.absolutePos(pos), true);
        helper.assertItemEntityPresent(CONDUIT.item().get(), pos, 2.0);
        helper.succeed();
    }

    /** A loot table the mod ships decides the drops: a stick here, not the conduit. */
    private static void lootTableWins(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 0, 1);
        helper.assertTrue(Conduits.place(helper.getLevel(), helper.absolutePos(pos), LOOT_CONDUIT.type().get()),
                "conduit not placed");
        helper.getLevel().destroyBlock(helper.absolutePos(pos), true);
        helper.assertItemEntityPresent(Items.STICK, pos, 2.0);
        helper.assertItemEntityNotPresent(LOOT_CONDUIT.item().get(), pos, 2.0);
        helper.succeed();
    }

    /**
     * A conduit marked {@code customModel} is an ordinary conduit on the server: it registers, places, joins its
     * network's links and keeps a render value. Its model hook is client-only and is not tested here.
     */
    private static void customModelPlaces(GameTestHelper helper) {
        placesAndLinks(helper, HOOKED_TUBE);
    }

    /**
     * A conduit in Lib's shell look is an ordinary conduit on the server too: it registers, places, links and keeps a
     * render value. Its model is client-only and is not tested here.
     */
    private static void shellPlaces(GameTestHelper helper) {
        placesAndLinks(helper, GLASS_TUBE);
    }

    /**
     * A shell tube holds the contents its addon sets, lava flowing in from the west with a second lane next to a tube
     * of water, and both still link; Lib draws them on the client only.
     */
    private static void shellContentsPlaces(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 0, 1);
        BlockPos next = new BlockPos(2, 0, 1);
        ConduitType type = GLASS_TUBE.type().get();
        helper.assertTrue(Conduits.place(helper.getLevel(), helper.absolutePos(pos), type), "tube not placed");
        helper.assertTrue(Conduits.place(helper.getLevel(), helper.absolutePos(next), type), "second tube not placed");
        ConduitContents lava = new ConduitContents(Optional.of(Fluids.LAVA), 2, Optional.of(Direction.WEST), true);
        ConduitContents water = new ConduitContents(Optional.of(Fluids.WATER), ConduitContents.FULL, Optional.empty(),
                false);
        host(helper, pos).setContents(lava);
        host(helper, next).setContents(water);
        helper.assertValueEqual(lava, host(helper, pos).contents(), "contents of the lava tube");
        helper.assertValueEqual(water, host(helper, next).contents(), "contents of the water tube");
        helper.succeedWhen(() -> {
            helper.assertTrue(ConduitConnections.isConnected(host(helper, pos).connections(), Direction.EAST),
                    "the tubes did not link");
            helper.assertValueEqual(lava, host(helper, pos).contents(), "contents after linking");
        });
    }

    private static void placesAndLinks(GameTestHelper helper, ConduitEntry<ConduitType> entry) {
        BlockPos pos = new BlockPos(1, 0, 1);
        BlockPos next = new BlockPos(2, 0, 1);
        ConduitType type = entry.type().get();
        helper.assertTrue(Conduits.blockOf(type).filter(block -> block.equals(entry.block().get())).isPresent(),
                "the tube type has no block");
        helper.assertTrue(Conduits.place(helper.getLevel(), helper.absolutePos(pos), type), "tube not placed");
        helper.assertTrue(Conduits.place(helper.getLevel(), helper.absolutePos(next), type), "second tube not placed");
        helper.assertBlockPresent(entry.block().get(), pos);
        ConduitHost host = host(helper, pos);
        host.setRenderValue(0x3F76E4);
        helper.assertValueEqual(0x3F76E4, host.renderValue(), "render value of a tube");
        helper.succeedWhen(
                () -> helper.assertTrue(ConduitConnections.isConnected(host(helper, pos).connections(), Direction.EAST),
                        "the tubes did not link"));
    }

    /**
     * A new render value is saved and goes to clients in the update tag; setting the same value again marks nothing for
     * saving (the block update is sent on the same path). A value of 0 leaves the key out of the update tag.
     */
    private static void renderValueSyncs(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 0, 1);
        BlockPos abs = helper.absolutePos(pos);
        helper.assertTrue(Conduits.place(helper.getLevel(), abs, CONDUIT.type().get()), "conduit not placed");
        ConduitHost host = host(helper, pos);
        helper.assertValueEqual(0, host.renderValue(), "render value of a fresh conduit");
        helper.assertFalse(updateTag(helper, abs).contains(RENDER_VALUE), "a fresh conduit synced a render value");
        LevelChunk chunk = helper.getLevel().getChunkAt(abs);
        chunk.tryMarkSaved();
        host.setRenderValue(0x3366CC);
        helper.assertValueEqual(0x3366CC, host.renderValue(), "render value after setting it");
        helper.assertTrue(chunk.isUnsaved(), "a new render value was not marked for saving");
        helper.assertValueEqual(0x3366CC, updateTag(helper, abs).getIntOr(RENDER_VALUE, 0),
                "render value in the update tag");
        chunk.tryMarkSaved();
        host.setRenderValue(0x3366CC);
        helper.assertFalse(chunk.isUnsaved(), "setting the same render value marked the conduit for saving");
        host.setRenderValue(0);
        helper.assertTrue(chunk.isUnsaved(), "clearing the render value was not marked for saving");
        helper.assertFalse(updateTag(helper, abs).contains(RENDER_VALUE), "render value 0 went into the update tag");
        helper.succeed();
    }

    /**
     * The render value survives a save and load of the block entity; data without it loads as 0, also into a conduit
     * that held a value.
     */
    private static void renderValueSaved(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 0, 1);
        BlockPos abs = helper.absolutePos(pos);
        helper.assertTrue(Conduits.place(helper.getLevel(), abs, CONDUIT.type().get()), "conduit not placed");
        host(helper, pos).setRenderValue(7);
        CompoundTag saved = blockEntity(helper, abs).saveWithFullMetadata(helper.getLevel().registryAccess());
        helper.assertValueEqual(7, saved.getIntOr(RENDER_VALUE, 0), "saved render value");
        helper.assertValueEqual(7, loaded(helper, abs, saved).renderValue(), "render value after loading");
        CompoundTag without = saved.copy();
        without.remove(RENDER_VALUE);
        helper.assertValueEqual(0, loaded(helper, abs, without).renderValue(), "render value of data without one");
        // The client's reset path: the conduit holding 7 loads an update tag without the key.
        BlockEntity existing = blockEntity(helper, abs);
        existing.loadWithComponents(
                TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), without));
        helper.assertValueEqual(0, host(helper, pos).renderValue(), "render value kept after loading data without one");
        helper.succeed();
    }

    /**
     * New contents are saved and go to clients in the update tag, the fluid by name; setting the same contents again
     * marks nothing for saving. Empty contents leave the key out, and a conduit holding contents that loads an update
     * tag without the key (the client's reset path) shows none.
     */
    private static void contentsSyncs(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 0, 1);
        BlockPos abs = helper.absolutePos(pos);
        helper.assertTrue(Conduits.place(helper.getLevel(), abs, CONDUIT.type().get()), "conduit not placed");
        ConduitHost host = host(helper, pos);
        helper.assertValueEqual(ConduitContents.EMPTY, host.contents(), "contents of a fresh conduit");
        helper.assertFalse(updateTag(helper, abs).contains(CONTENTS), "a fresh conduit synced contents");
        ConduitContents water = new ConduitContents(Optional.of(Fluids.WATER), 3, Optional.of(Direction.WEST), true);
        LevelChunk chunk = helper.getLevel().getChunkAt(abs);
        chunk.tryMarkSaved();
        host.setContents(water);
        helper.assertValueEqual(water, host.contents(), "contents after setting them");
        helper.assertTrue(chunk.isUnsaved(), "new contents were not marked for saving");
        CompoundTag update = updateTag(helper, abs);
        helper.assertValueEqual("minecraft:water", update.getCompoundOrEmpty(CONTENTS).getStringOr("fluid", ""),
                "fluid name in the update tag");
        chunk.tryMarkSaved();
        host.setContents(new ConduitContents(Optional.of(Fluids.WATER), 3, Optional.of(Direction.WEST), true));
        helper.assertFalse(chunk.isUnsaved(), "setting equal contents marked the conduit for saving");
        // The client's reset path: the conduit holding water loads an update tag without the key.
        CompoundTag without = update.copy();
        without.remove(CONTENTS);
        blockEntity(helper, abs).loadWithComponents(
                TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), without));
        helper.assertValueEqual(ConduitContents.EMPTY, host(helper, pos).contents(),
                "contents kept after loading an update tag without them");
        host.setContents(water);
        chunk.tryMarkSaved();
        host.setContents(ConduitContents.EMPTY);
        helper.assertTrue(chunk.isUnsaved(), "clearing the contents was not marked for saving");
        helper.assertFalse(updateTag(helper, abs).contains(CONTENTS), "empty contents went into the update tag");
        helper.assertTrue(rejects(() -> new ConduitContents(Optional.of(Fluids.EMPTY), 0, Optional.empty(), false)),
                "the empty fluid was taken as a fluid");
        helper.assertTrue(rejects(() -> new ConduitContents(Optional.of(Fluids.LAVA), 5, Optional.empty(), false)),
                "fill 5 was accepted");
        helper.assertTrue(
                rejects(() -> new ConduitContents(Optional.of(unregisteredFluid()), 0, Optional.empty(), false)),
                "an unregistered fluid was accepted");
        helper.assertValueEqual(new ConduitContents(Optional.of(Fluids.WATER), 2, Optional.empty(), false),
                new ConduitContents(Optional.of(Fluids.FLOWING_WATER), 2, Optional.empty(), false),
                "flowing water was not normalised to water");
        helper.assertValueEqual(Fluids.LAVA,
                new ConduitContents(Optional.of(Fluids.FLOWING_LAVA), 0, Optional.empty(), false).fluid().get(),
                "flowing lava was not stored as its source");
        helper.succeed();
    }

    /**
     * Contents survive a save and load of the block entity with the fluid by registry name; data without them, or
     * naming a fluid that is no longer registered, loads as empty.
     */
    private static void contentsSaved(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 0, 1);
        BlockPos abs = helper.absolutePos(pos);
        helper.assertTrue(Conduits.place(helper.getLevel(), abs, CONDUIT.type().get()), "conduit not placed");
        ConduitContents lava = new ConduitContents(Optional.of(Fluids.LAVA), ConduitContents.FULL,
                Optional.of(Direction.UP), false);
        host(helper, pos).setContents(lava);
        CompoundTag saved = blockEntity(helper, abs).saveWithFullMetadata(helper.getLevel().registryAccess());
        CompoundTag contents = saved.getCompoundOrEmpty(CONTENTS);
        helper.assertValueEqual("minecraft:lava", contents.getStringOr("fluid", ""), "saved fluid name");
        helper.assertValueEqual(lava, loaded(helper, abs, saved).contents(), "contents after loading");
        CompoundTag without = saved.copy();
        without.remove(CONTENTS);
        helper.assertValueEqual(ConduitContents.EMPTY, loaded(helper, abs, without).contents(),
                "contents of data without them");
        CompoundTag unknown = saved.copy();
        unknown.getCompoundOrEmpty(CONTENTS).putString("fluid", "removed_mod:goo");
        helper.assertValueEqual(ConduitContents.EMPTY, loaded(helper, abs, unknown).contents(),
                "contents naming an unknown fluid");
        helper.succeed();
    }

    private static int invalidations(BlockPos abs) {
        return INVALIDATIONS.getOrDefault(abs.asLong(), 0);
    }

    /** Forgets the counts of an earlier run at the same position. */
    private static void resetInvalidations(BlockPos abs) {
        INVALIDATIONS.remove(abs.asLong());
    }

    /** What the conduit at {@code abs} last saw on {@code side}; Lib's block entity, not the api. */
    private static Optional<BlockEntity> attachment(GameTestHelper helper, BlockPos abs, Direction side) {
        if (!(helper.getLevel().getBlockEntity(abs) instanceof ConduitBlockEntity conduit)) {
            throw helper.assertionException("no conduit at " + abs);
        }
        return conduit.attachment(side);
    }

    /**
     * A conduit that attaches to blocks reports a block entity placed beside it, replaced by another and removed again
     * once each through {@code onNodeInvalidated}; a block without a block entity, and the placement of the conduit
     * itself, report nothing.
     */
    private static void attachPlaceRemove(GameTestHelper helper) {
        BlockPos pos = new BlockPos(2, 0, 1);
        BlockPos side = pos.east();
        BlockPos abs = helper.absolutePos(pos);
        resetInvalidations(abs);
        helper.assertTrue(Conduits.place(helper.getLevel(), abs, ATTACHING.type().get()), "conduit not placed");
        NetworkManager manager = NetworkManager.get(helper.getLevel());
        int[] before = new int[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(manager.network(ATTACH_NETWORK, abs) != null, "no network"))
                .thenExecute(() -> {
                    helper.assertValueEqual(0, invalidations(abs), "invalidations from the placement");
                    helper.setBlock(pos.west(), Blocks.STONE);
                }).thenIdle(2).thenExecute(() -> {
                    helper.assertValueEqual(0, invalidations(abs), "invalidations from a block without block entity");
                    before[0] = invalidations(abs);
                    helper.setBlock(side, Blocks.CHEST);
                }).thenIdle(2).thenExecute(() -> {
                    helper.assertValueEqual(before[0] + 1, invalidations(abs), "invalidations after placing a chest");
                    helper.setBlock(side, Blocks.BARREL);
                }).thenIdle(2).thenExecute(() -> {
                    helper.assertValueEqual(before[0] + 2, invalidations(abs), "invalidations after the replacement");
                    helper.setBlock(side, Blocks.AIR);
                }).thenIdle(2).thenExecute(() -> {
                    helper.assertValueEqual(before[0] + 3, invalidations(abs), "invalidations after the removal");
                }).thenSucceed();
    }

    /** A conduit without the opt-in ignores block entities appearing and going beside it, as before. */
    private static void attachUnflagged(GameTestHelper helper) {
        BlockPos pos = new BlockPos(2, 0, 1);
        BlockPos side = pos.east();
        BlockPos abs = helper.absolutePos(pos);
        resetInvalidations(abs);
        helper.assertTrue(Conduits.place(helper.getLevel(), abs, NOT_ATTACHING.type().get()), "conduit not placed");
        NetworkManager manager = NetworkManager.get(helper.getLevel());
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(manager.network(ATTACH_NETWORK, abs) != null, "no network"))
                .thenExecute(() -> helper.setBlock(side, Blocks.CHEST)).thenIdle(2)
                .thenExecute(() -> helper.setBlock(side, Blocks.AIR)).thenIdle(2)
                .thenExecute(() -> helper.assertValueEqual(0, invalidations(abs), "invalidations of a plain conduit"))
                .thenSucceed();
    }

    /**
     * A chest across a chunk border whose chunk reaches full status only after the conduit is in place, so no neighbour
     * update tells the conduit: it still reports the chest once. Far from the test grid, with real chunk tickets: one
     * forced chunk keeps the conduit's chunk two chunks away at full status, while the chest's chunk next to it, three
     * away, is not; forcing the chest's chunk brings it. Both blocks are set without neighbour or shape updates, which
     * would read, and so load, the other chunk.
     */
    private static void attachChunkLoad(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int anchorX = 6000;
        int chunkZ = 6000;
        int conduitChunkX = anchorX + 2;
        int chestChunkX = anchorX + 3;
        int y = helper.absolutePos(BlockPos.ZERO).getY();
        BlockPos conduit = new BlockPos(conduitChunkX * 16 + 15, y, chunkZ * 16 + 8);
        BlockPos chest = conduit.east();
        int quiet = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        Block block = Conduits.blockOf(ATTACHING.type().get())
                .orElseThrow(() -> helper.assertionException("the attaching type has no block"));
        NetworkManager manager = NetworkManager.get(level);
        int[] before = new int[1];
        helper.startSequence().thenExecute(() -> {
            level.setChunkForced(chestChunkX, chunkZ, true);
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), quiet);
            level.setChunkForced(chestChunkX, chunkZ, false);
            level.setChunkForced(anchorX, chunkZ, true);
        }).thenWaitUntil(() -> {
            helper.assertTrue(level.getChunkSource().getChunkNow(conduitChunkX, chunkZ) != null,
                    "the conduit's chunk is not at full status yet");
            helper.assertTrue(level.getChunkSource().getChunkNow(chestChunkX, chunkZ) == null,
                    "the chest's chunk is still at full status");
        }).thenExecute(() -> level.setBlock(conduit, block.defaultBlockState(), quiet))
                .thenWaitUntil(() -> helper.assertTrue(manager.network(ATTACH_NETWORK, conduit) != null, "no network"))
                .thenIdle(2).thenExecute(() -> {
                    helper.assertTrue(level.getChunkSource().getChunkNow(chestChunkX, chunkZ) == null,
                            "the chest's chunk came back early");
                    before[0] = invalidations(conduit);
                    level.setChunkForced(chestChunkX, chunkZ, true);
                }).thenWaitUntil(() -> helper.assertValueEqual(before[0] + 1, invalidations(conduit),
                        "invalidations after the chest's chunk loaded"))
                .thenIdle(3).thenExecute(() -> {
                    helper.assertValueEqual(before[0] + 1, invalidations(conduit), "invalidations a few ticks later");
                    level.setBlock(conduit, Blocks.AIR.defaultBlockState(), quiet);
                    level.setBlock(chest, Blocks.AIR.defaultBlockState(), quiet);
                    level.setChunkForced(chestChunkX, chunkZ, false);
                    level.setChunkForced(anchorX, chunkZ, false);
                }).thenSucceed();
    }

    /**
     * A new link and a new block entity seen in the same update give one invalidation, not one each. Both are set
     * without neighbour updates, so the conduit sees them together when it refreshes its connections.
     */
    private static void attachLinkAndBlock(GameTestHelper helper) {
        BlockPos pos = new BlockPos(2, 0, 1);
        BlockPos abs = helper.absolutePos(pos);
        resetInvalidations(abs);
        ServerLevel level = helper.getLevel();
        helper.assertTrue(Conduits.place(level, abs, ATTACHING.type().get()), "conduit not placed");
        Block plain = Conduits.blockOf(NOT_ATTACHING.type().get())
                .orElseThrow(() -> helper.assertionException("the plain type has no block"));
        int quiet = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        NetworkManager manager = NetworkManager.get(level);
        int[] before = new int[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(manager.network(ATTACH_NETWORK, abs) != null, "no network"))
                .thenExecute(() -> {
                    level.setBlock(abs.east(), Blocks.CHEST.defaultBlockState(), quiet);
                    level.setBlock(abs.west(), plain.defaultBlockState(), quiet);
                }).thenIdle(2).thenExecute(() -> {
                    before[0] = invalidations(abs);
                    host(helper, pos).refreshConnections();
                }).thenIdle(2).thenExecute(() -> {
                    helper.assertTrue((host(helper, pos).connections() & ConduitConnections.bit(Direction.WEST)) != 0,
                            "the new link is missing");
                    helper.assertValueEqual(before[0] + 1, invalidations(abs),
                            "invalidations after a link and a block entity at once");
                }).thenSucceed();
    }

    /** A conduit of another network beside an attaching one is no attachment: no invalidation, nothing held. */
    private static void attachIgnoresConduits(GameTestHelper helper) {
        BlockPos pos = new BlockPos(2, 0, 1);
        BlockPos abs = helper.absolutePos(pos);
        resetInvalidations(abs);
        helper.assertTrue(Conduits.place(helper.getLevel(), abs, ATTACHING.type().get()), "conduit not placed");
        NetworkManager manager = NetworkManager.get(helper.getLevel());
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(manager.network(ATTACH_NETWORK, abs) != null, "no network"))
                .thenExecute(
                        () -> helper.assertTrue(Conduits.place(helper.getLevel(), abs.east(), CONDUIT.type().get()),
                                "other conduit not placed"))
                .thenIdle(2).thenExecute(() -> {
                    helper.assertTrue((host(helper, pos).connections() & ConduitConnections.bit(Direction.EAST)) == 0,
                            "conduits of two networks linked");
                    helper.assertValueEqual(0, invalidations(abs), "invalidations from a neighbouring conduit");
                }).thenSucceed();
    }

    /**
     * A chest whose chunk leaves full status is dropped once: one invalidation, and the conduit no longer holds the
     * chest's block entity; nothing more while the chunk stays away, and one more when it comes back. Real tickets far
     * from the test grid, as in {@link #attachChunkLoad}: un-forcing the chest's chunk drops it to the level the anchor
     * gives it, below full status.
     */
    private static void attachChunkUnload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int anchorX = 6100;
        int chunkZ = 6100;
        int conduitChunkX = anchorX + 2;
        int chestChunkX = anchorX + 3;
        int y = helper.absolutePos(BlockPos.ZERO).getY();
        BlockPos conduit = new BlockPos(conduitChunkX * 16 + 15, y, chunkZ * 16 + 8);
        BlockPos chest = conduit.east();
        resetInvalidations(conduit);
        int quiet = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        Block block = Conduits.blockOf(ATTACHING.type().get())
                .orElseThrow(() -> helper.assertionException("the attaching type has no block"));
        NetworkManager manager = NetworkManager.get(level);
        int[] before = new int[1];
        helper.startSequence().thenExecute(() -> {
            level.setChunkForced(anchorX, chunkZ, true);
            level.setChunkForced(chestChunkX, chunkZ, true);
        }).thenWaitUntil(() -> {
            helper.assertTrue(level.getChunkSource().getChunkNow(conduitChunkX, chunkZ) != null,
                    "the conduit's chunk is not at full status yet");
            helper.assertTrue(level.getChunkSource().getChunkNow(chestChunkX, chunkZ) != null,
                    "the chest's chunk is not at full status yet");
        }).thenExecute(() -> {
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), quiet);
            level.setBlock(conduit, block.defaultBlockState(), quiet);
        }).thenWaitUntil(() -> helper.assertTrue(manager.network(ATTACH_NETWORK, conduit) != null, "no network"))
                .thenIdle(2).thenExecute(() -> {
                    helper.assertTrue(attachment(helper, conduit, Direction.EAST).isPresent(), "the chest is not held");
                    before[0] = invalidations(conduit);
                    level.setChunkForced(chestChunkX, chunkZ, false);
                }).thenWaitUntil(() -> helper.assertValueEqual(before[0] + 1, invalidations(conduit),
                        "invalidations after the chest's chunk left"))
                .thenIdle(3).thenExecute(() -> {
                    helper.assertValueEqual(before[0] + 1, invalidations(conduit), "invalidations a few ticks later");
                    helper.assertTrue(level.getChunkSource().getChunkNow(chestChunkX, chunkZ) == null,
                            "the chest's chunk is still at full status");
                    helper.assertTrue(attachment(helper, conduit, Direction.EAST).isEmpty(),
                            "the chest in the chunk that left is still held");
                    level.setChunkForced(chestChunkX, chunkZ, true);
                }).thenWaitUntil(() -> helper.assertValueEqual(before[0] + 2, invalidations(conduit),
                        "invalidations after the chest's chunk came back"))
                .thenExecute(() -> {
                    helper.assertTrue(attachment(helper, conduit, Direction.EAST).isPresent(),
                            "the chest is not held again");
                    level.setBlock(conduit, Blocks.AIR.defaultBlockState(), quiet);
                    level.setBlock(chest, Blocks.AIR.defaultBlockState(), quiet);
                    level.setChunkForced(chestChunkX, chunkZ, false);
                    level.setChunkForced(anchorX, chunkZ, false);
                }).thenSucceed();
    }

    /**
     * A saved conduit and the chest across its chunk border, both unloaded, are loaded again by forcing both chunks in
     * the same tick: the conduit holds the chest afterwards, whichever chunk's load comes first. Nothing else holds
     * them, so un-forcing both unloads them for real (their block entities are removed).
     */
    private static void attachChunksTogether(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int conduitChunkX = 6200;
        int chestChunkX = conduitChunkX + 1;
        int chunkZ = 6200;
        int y = helper.absolutePos(BlockPos.ZERO).getY();
        BlockPos conduit = new BlockPos(conduitChunkX * 16 + 15, y, chunkZ * 16 + 8);
        BlockPos chest = conduit.east();
        int quiet = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        Block block = Conduits.blockOf(ATTACHING.type().get())
                .orElseThrow(() -> helper.assertionException("the attaching type has no block"));
        BlockEntity[] first = new BlockEntity[2];
        helper.startSequence().thenExecute(() -> {
            level.setChunkForced(conduitChunkX, chunkZ, true);
            level.setChunkForced(chestChunkX, chunkZ, true);
        }).thenWaitUntil(() -> {
            helper.assertTrue(level.getChunkSource().getChunkNow(conduitChunkX, chunkZ) != null,
                    "the conduit's chunk is not at full status yet");
            helper.assertTrue(level.getChunkSource().getChunkNow(chestChunkX, chunkZ) != null,
                    "the chest's chunk is not at full status yet");
        }).thenExecute(() -> {
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), quiet);
            level.setBlock(conduit, block.defaultBlockState(), quiet);
        }).thenIdle(2).thenExecute(() -> {
            helper.assertTrue(attachment(helper, conduit, Direction.EAST).isPresent(), "the chest is not held");
            first[0] = blockEntity(helper, conduit);
            first[1] = blockEntity(helper, chest);
            level.setChunkForced(conduitChunkX, chunkZ, false);
            level.setChunkForced(chestChunkX, chunkZ, false);
        }).thenWaitUntil(() -> helper.assertTrue(first[0].isRemoved() && first[1].isRemoved(),
                "the chunks have not unloaded yet")).thenExecute(() -> {
                    level.setChunkForced(conduitChunkX, chunkZ, true);
                    level.setChunkForced(chestChunkX, chunkZ, true);
                }).thenWaitUntil(() -> {
                    helper.assertTrue(level.getChunkSource().getChunkNow(conduitChunkX, chunkZ) != null,
                            "the conduit's chunk is not back");
                    helper.assertTrue(level.getChunkSource().getChunkNow(chestChunkX, chunkZ) != null,
                            "the chest's chunk is not back");
                }).thenIdle(3).thenExecute(() -> {
                    helper.assertTrue(attachment(helper, conduit, Direction.EAST).isPresent(),
                            "the reloaded conduit does not hold the reloaded chest");
                    level.setBlock(conduit, Blocks.AIR.defaultBlockState(), quiet);
                    level.setBlock(chest, Blocks.AIR.defaultBlockState(), quiet);
                    level.setChunkForced(conduitChunkX, chunkZ, false);
                    level.setChunkForced(chestChunkX, chunkZ, false);
                }).thenSucceed();
    }

    /** A fluid instance outside the registry: created without its constructor, which would need an open registry. */
    private static Fluid unregisteredFluid() {
        try {
            return (Fluid) sun.reflect.ReflectionFactory.getReflectionFactory()
                    .newConstructorForSerialization(EmptyFluid.class, Object.class.getDeclaredConstructor())
                    .newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean rejects(Runnable construct) {
        try {
            construct.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static CompoundTag updateTag(GameTestHelper helper, BlockPos abs) {
        return blockEntity(helper, abs).getUpdateTag(helper.getLevel().registryAccess());
    }

    private static BlockEntity blockEntity(GameTestHelper helper, BlockPos abs) {
        BlockEntity entity = helper.getLevel().getBlockEntity(abs);
        if (entity == null) {
            throw helper.assertionException("no block entity at " + abs);
        }
        return entity;
    }

    /** Loads {@code tag} into a new block entity, as a chunk load does, outside the level. */
    private static ConduitView loaded(GameTestHelper helper, BlockPos abs, CompoundTag tag) {
        BlockEntity loaded = BlockEntity.loadStatic(abs, helper.getLevel().getBlockState(abs), tag,
                helper.getLevel().registryAccess());
        if (!(loaded instanceof ConduitView view)) {
            throw helper.assertionException("the conduit did not load");
        }
        return view;
    }

    private static ConduitHost host(GameTestHelper helper, BlockPos pos) {
        return Conduits.host(helper.getLevel(), helper.absolutePos(pos))
                .orElseThrow(() -> helper.assertionException("no conduit at " + pos));
    }
}
