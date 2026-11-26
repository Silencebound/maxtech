package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.energy.EnergyCellBlockEntity;
import dev.silencebound.maxtech.storage.energy.EnergyContent;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.p2p.P2PBlock;
import dev.silencebound.maxtech.storage.p2p.P2PBlockEntity;
import dev.silencebound.maxtech.storage.p2p.P2PContent;
import dev.silencebound.maxtech.storage.p2p.P2PKind;
import dev.silencebound.maxtech.storage.p2p.P2PLinkerItem;
import dev.silencebound.maxtech.storage.p2p.P2PPart;
import dev.silencebound.maxtech.storage.p2p.P2PRole;
import dev.silencebound.maxtech.storage.p2p.P2PSettings;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * P2P links (M6c area B): items and fluid through the network as operations between the ends, FE with its loss,
 * redstone mirrored on change, block and part forms, the linking tool, an end unloading and pairing again, a rollback
 * moving nothing, a loop through the link, and the research gate. Layout: the controller at x 1 and a tier-1 cable from
 * x 2 to x 8, all at y 1, z 1; link blocks stand at z 2 beside the cable facing south, their targets at z 3.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
// Networks are compared by identity.
@SuppressWarnings("ReferenceEquality")
public final class P2PGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final BlockPos CONTROLLER = new BlockPos(1, 1, 1);
    private static final long POWER = 10_000;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);
    private static final List<String> TESTS = new ArrayList<>();
    /** MLE item 4: P2P energy in MU at 3 FE = 2 MU, and with export off. */
    private static final String MU = "p2p_energy_in_mu";
    private static final String EXPORT_OFF = "p2p_energy_export_off";

    static {
        TestStorage.init();
        P2PTestContent.init();
        StorageTestEnvironment.init();
        register("p2p_items_move_between_ends", P2PGameTests::itemsMove);
        register("p2p_fluid_moves", P2PGameTests::fluidMoves);
        register("p2p_energy_loses_percent", P2PGameTests::energyLoses);
        register("p2p_redstone_mirrors", P2PGameTests::redstoneMirrors);
        register("p2p_unload_and_pair_again", P2PGameTests::unloadAndPairAgain);
        register("p2p_parts_carry_items", P2PGameTests::partsCarryItems);
        register("p2p_linker_pairs_ends", P2PGameTests::linkerPairs);
        register("p2p_linker_sneak_real_path", P2PGameTests::linkerSneakRealPath);
        register("p2p_loop_moves_nothing", P2PGameTests::loopMovesNothing);
        register("p2p_research_gate", P2PGameTests::researchGate);
        FUNCTIONS.register(MU, () -> StorageTestEnvironment.clearing(helper -> energyInMu(helper, true)));
        FUNCTIONS.register(EXPORT_OFF, () -> StorageTestEnvironment.clearing(helper -> energyInMu(helper, false)));
    }

    private P2PGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        TESTS.add(name);
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("p2p"),
                StorageTestEnvironment.plain());
        for (String name : TESTS) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, STRUCTURE, 400, 0, true)));
        }
        test(event, MU, new StorageTestEnvironment("energy.feRateFe;energy.feRateMu", "3;2", false));
        test(event, EXPORT_OFF, new StorageTestEnvironment("energy.exportEnabled", "false", false));
    }

    private static void test(RegisterGameTestsEvent event, String name, StorageTestEnvironment definition) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id(name),
                definition);
        event.registerTest(MaxtechStorage.id(name),
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                        new TestData<>(environment, STRUCTURE, 400, 0, true)));
    }

    /**
     * P2P energy in MU (MLE item 4). At 3 FE = 2 MU, two inserts of 150 FE are 100 MU each, one per output; with the 5
     * % loss 95 MU reach each: an energy cell behind one takes them as MU, another mod's FE block behind the other gets
     * 142 FE (95 MU at the rate, rounded down). With export off ({@code energy.exportEnabled}) the other mod's block
     * gets nothing and the cell still gets its MU.
     */
    private static void energyInMu(GameTestHelper helper, boolean export) {
        network(helper, POWER);
        P2PBlockEntity input = end(helper, P2PKind.ENERGY, 3, new P2PSettings(P2PRole.INPUT, 9));
        P2PBlockEntity toCell = end(helper, P2PKind.ENERGY, 5, new P2PSettings(P2PRole.OUTPUT, 9));
        P2PBlockEntity toSink = end(helper, P2PKind.ENERGY, 7, new P2PSettings(P2PRole.OUTPUT, 9));
        EnergyCellBlockEntity cell = EnergyCellGameTests.cell(helper, target(5), EnergyContent.TIER_1.get());
        helper.setBlock(target(7), P2PTestContent.SINK.get());
        P2PTestContent.Sink sink = helper.getBlockEntity(target(7), P2PTestContent.Sink.class);
        helper.startSequence().thenWaitUntil(() -> inNetwork(helper, input, toCell, toSink)).thenExecute(() -> {
            EnergyHandler energy = helper.getLevel().getCapability(Capabilities.Energy.BLOCK, helper.absolutePos(at(3)),
                    Direction.SOUTH);
            if (energy == null) {
                throw helper.assertionException(Component.literal("No FE handler on the input's front"));
            }
            for (int i = 0; i < 2; i++) {
                try (Transaction transaction = Transaction.openRoot()) {
                    helper.assertValueEqual(energy.insert(150, transaction), 150, "FE taken by the input");
                    transaction.commit();
                }
            }
            long cellMu = cell.cellStore().stored();
            long sinkFe = sink.energy.getAmountAsLong();
            if (export) {
                // Each insert of 150 FE is 100 MU and reaches one output, 95 MU after the loss: 95 MU in the cell,
                // and 95 MU at 3:2 is 142 FE (rounded down; the input pays the fraction) in the other mod's block.
                helper.assertValueEqual(cellMu, 95L, "MU in the cell");
                helper.assertValueEqual(sinkFe, 142L, "FE in the other mod's block");
            } else {
                helper.assertValueEqual(sinkFe, 0L, "export off: nothing to another mod's block");
                helper.assertTrue(cellMu > 0, "the cell still gets MU");
            }
        }).thenSucceed();
    }

    /**
     * An item input with two outputs into chests: single items alternate between the outputs, ten go to one, each
     * output reached is one operation (4 FE whatever it moves), a rolled-back insert moves and costs nothing, and the
     * input gives nothing out.
     */
    private static void itemsMove(GameTestHelper helper) {
        network(helper, POWER);
        P2PBlockEntity input = end(helper, P2PKind.ITEM, 3, new P2PSettings(P2PRole.INPUT, 7));
        P2PBlockEntity first = end(helper, P2PKind.ITEM, 6, new P2PSettings(P2PRole.OUTPUT, 7));
        P2PBlockEntity second = end(helper, P2PKind.ITEM, 8, new P2PSettings(P2PRole.OUTPUT, 7));
        ChestBlockEntity firstChest = chest(helper, 6);
        ChestBlockEntity secondChest = chest(helper, 8);
        helper.startSequence().thenWaitUntil(() -> inNetwork(helper, input, first, second)).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, CONTROLLER);
            helper.assertTrue(capability(helper, at(3), Direction.WEST) == null, "an item handler on a side");
            ResourceHandler<ItemResource> items = items(helper, at(3));
            helper.assertValueEqual(insert(items, 1, true) + insert(items, 1, true), 2, "single items");
            helper.assertValueEqual(count(firstChest) + "/" + count(secondChest), "1/1", "split over the outputs");
            helper.assertValueEqual(insert(items, 10, true), 10, "ten items");
            helper.assertValueEqual(count(firstChest) + count(secondChest), 12, "items in the chests");
            helper.assertValueEqual(network.storedEnergy(), POWER - 3 * 4, "FE for three operations");
            // Operations walk no route (M7N): the three ends hold one channel each, before and after.
            helper.assertValueEqual(channels(helper, network, new BlockPos(2, 1, 1)), 3, "channels towards the root");
            helper.assertValueEqual(insert(items, 5, false), 5, "simulated");
            helper.assertValueEqual(count(firstChest) + count(secondChest), 12, "items after a rollback");
            helper.assertValueEqual(network.storedEnergy(), POWER - 3 * 4, "FE after a rollback");
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(items.extract(0, ItemResource.of(Items.COBBLESTONE), 1, transaction), 0,
                        "taken from the input");
            }
        }).thenSucceed();
    }

    /** A fluid input fills the empty cauldron an output faces with one bucket of water. */
    private static void fluidMoves(GameTestHelper helper) {
        network(helper, POWER);
        P2PBlockEntity input = end(helper, P2PKind.FLUID, 3, new P2PSettings(P2PRole.INPUT, 8));
        P2PBlockEntity output = end(helper, P2PKind.FLUID, 6, new P2PSettings(P2PRole.OUTPUT, 8));
        helper.setBlock(target(6), Blocks.CAULDRON);
        helper.startSequence().thenWaitUntil(() -> inNetwork(helper, input, output)).thenExecute(() -> {
            ResourceHandler<FluidResource> fluids = helper.getLevel().getCapability(Capabilities.Fluid.BLOCK,
                    helper.absolutePos(at(3)), Direction.SOUTH);
            helper.assertTrue(fluids != null, "no fluid handler on the input's front");
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(
                        ResourceHandlerUtil.insertStacking(fluids,
                                FluidResource.of(net.minecraft.world.level.material.Fluids.WATER), 1000, transaction),
                        1000, "water sent");
                transaction.commit();
            }
            BlockState cauldron = helper.getBlockState(target(6));
            helper.assertTrue(
                    cauldron.is(Blocks.WATER_CAULDRON)
                            && cauldron.getValue(LayeredCauldronBlock.LEVEL) == LayeredCauldronBlock.MAX_FILL_LEVEL,
                    "the cauldron is not full of water");
        }).thenSucceed();
    }

    /**
     * 1,000 FE into an energy input reach the output's store as 950 (5 % loss); the network's own FE input on the
     * block's other sides is not what the front offers, and the network's buffer is untouched.
     */
    private static void energyLoses(GameTestHelper helper) {
        network(helper, POWER);
        P2PBlockEntity input = end(helper, P2PKind.ENERGY, 3, new P2PSettings(P2PRole.INPUT, 9));
        P2PBlockEntity output = end(helper, P2PKind.ENERGY, 6, new P2PSettings(P2PRole.OUTPUT, 9));
        helper.setBlock(target(6), P2PTestContent.SINK.get());
        P2PTestContent.Sink sink = helper.getBlockEntity(target(6), P2PTestContent.Sink.class);
        helper.startSequence().thenWaitUntil(() -> inNetwork(helper, input, output)).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, CONTROLLER);
            long before = network.storedEnergy();
            EnergyHandler energy = helper.getLevel().getCapability(Capabilities.Energy.BLOCK, helper.absolutePos(at(3)),
                    Direction.SOUTH);
            if (energy == null) {
                throw helper.assertionException(Component.literal("No FE handler on the input's front"));
            }
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(energy.insert(1000, transaction), 1000, "FE taken by the input");
                transaction.commit();
            }
            helper.assertValueEqual(sink.energy.getAmountAsInt(), 950, "FE delivered");
            helper.assertValueEqual(network.storedEnergy(), before, "network FE");
        }).thenSucceed();
    }

    /**
     * A redstone block in front of the input lights the lamp in front of the block output and powers the part output's
     * face; taking it away turns both off. No ticker: everything follows the neighbour updates.
     */
    private static void redstoneMirrors(GameTestHelper helper) {
        network(helper, POWER);
        P2PBlockEntity input = end(helper, P2PKind.REDSTONE, 3, new P2PSettings(P2PRole.INPUT, 10));
        P2PBlockEntity output = end(helper, P2PKind.REDSTONE, 6, new P2PSettings(P2PRole.OUTPUT, 10));
        helper.setBlock(target(6), Blocks.REDSTONE_LAMP);
        CableParts parts = PartGameTests.parts(helper, 8);
        helper.assertTrue(parts.place(Direction.SOUTH, P2PContent.part(P2PKind.REDSTONE), null,
                linked(P2PContent.partItem(P2PKind.REDSTONE).get(), P2PRole.OUTPUT, 10)), "part not placed");
        P2PPart part = (P2PPart) PartGameTests.part(helper, parts, Direction.SOUTH);
        helper.setBlock(new BlockPos(8, 1, 2), Blocks.REDSTONE_LAMP);
        helper.startSequence().thenWaitUntil(() -> inNetwork(helper, input, output))
                .thenExecute(() -> helper.setBlock(target(3), Blocks.REDSTONE_BLOCK)).thenWaitUntil(() -> {
                    helper.assertValueEqual(output.endpoint().outputSignal(), 15, "block output signal");
                    helper.assertValueEqual(part.endpoint().outputSignal(), 15, "part output signal");
                    helper.assertTrue(helper.getBlockState(target(6)).getValue(RedstoneLampBlock.LIT), "lamp off");
                    helper.assertTrue(helper.getBlockState(new BlockPos(8, 1, 2)).getValue(RedstoneLampBlock.LIT),
                            "the part's lamp is off");
                }).thenExecute(() -> helper.setBlock(target(3), Blocks.AIR)).thenWaitUntil(() -> {
                    helper.assertValueEqual(output.endpoint().outputSignal(), 0, "block output signal");
                    helper.assertFalse(helper.getBlockState(target(6)).getValue(RedstoneLampBlock.LIT), "lamp on");
                    helper.assertFalse(helper.getBlockState(new BlockPos(8, 1, 2)).getValue(RedstoneLampBlock.LIT),
                            "the part's lamp is on");
                }).thenSucceed();
    }

    /**
     * The input of a lit redstone link unloads (its block entity removed as a chunk unload does): the output goes dark.
     * Loaded again, it pairs again by itself. The output broken and placed again from its dropped item is paired again
     * too.
     */
    private static void unloadAndPairAgain(GameTestHelper helper) {
        network(helper, POWER);
        P2PBlockEntity input = end(helper, P2PKind.REDSTONE, 3, new P2PSettings(P2PRole.INPUT, 11));
        P2PBlockEntity output = end(helper, P2PKind.REDSTONE, 6, new P2PSettings(P2PRole.OUTPUT, 11));
        helper.setBlock(target(3), Blocks.REDSTONE_BLOCK);
        helper.startSequence().thenWaitUntil(() -> inNetwork(helper, input, output))
                .thenWaitUntil(() -> helper.assertValueEqual(output.endpoint().outputSignal(), 15, "lit"))
                .thenExecute(() -> {
                    input.setRemoved();
                    helper.assertValueEqual(output.endpoint().outputSignal(), 0, "signal with the input unloaded");
                    input.clearRemoved();
                    input.onLoad();
                }).thenWaitUntil(() -> helper.assertValueEqual(output.endpoint().outputSignal(), 15, "paired again"))
                .thenExecute(() -> {
                    helper.getLevel().destroyBlock(helper.absolutePos(at(6)), true);
                    List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                            new AABB(helper.absolutePos(at(6))).inflate(1.5));
                    helper.assertValueEqual(drops.size(), 1, "drops");
                    ItemStack item = drops.getFirst().getItem();
                    drops.getFirst().discard();
                    helper.assertValueEqual(item.getOrDefault(ModDataComponents.P2P_LINK.get(), P2PSettings.NONE),
                            new P2PSettings(P2PRole.OUTPUT, 11), "the link in the item");
                    helper.setBlock(at(6), P2PContent.block(P2PKind.REDSTONE).get().defaultBlockState()
                            .setValue(P2PBlock.FACING, Direction.SOUTH));
                    helper.getBlockEntity(at(6), P2PBlockEntity.class).applyComponentsFromItemStack(item);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(
                        helper.getBlockEntity(at(6), P2PBlockEntity.class).endpoint().outputSignal(), 15,
                        "the placed output"))
                .thenSucceed();
    }

    /**
     * Item link parts: an input part on one cable face takes items through the cable on that face only and an output
     * part puts them into the chest beyond its face; the part's item keeps the link.
     */
    private static void partsCarryItems(GameTestHelper helper) {
        network(helper, POWER);
        CableParts inParts = PartGameTests.parts(helper, 3);
        CableParts outParts = PartGameTests.parts(helper, 6);
        helper.assertTrue(inParts.place(Direction.SOUTH, P2PContent.part(P2PKind.ITEM), null,
                linked(P2PContent.partItem(P2PKind.ITEM).get(), P2PRole.INPUT, 12)), "input not placed");
        helper.assertTrue(outParts.place(Direction.SOUTH, P2PContent.part(P2PKind.ITEM), null,
                linked(P2PContent.partItem(P2PKind.ITEM).get(), P2PRole.OUTPUT, 12)), "output not placed");
        P2PPart in = (P2PPart) PartGameTests.part(helper, inParts, Direction.SOUTH);
        P2PPart out = (P2PPart) PartGameTests.part(helper, outParts, Direction.SOUTH);
        helper.setBlock(new BlockPos(6, 1, 2), Blocks.CHEST);
        ChestBlockEntity chest = helper.getBlockEntity(new BlockPos(6, 1, 2), ChestBlockEntity.class);
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, CONTROLLER);
            helper.assertTrue(in.deviceLink().network() == network && out.deviceLink().network() == network,
                    "parts not in the network");
        }).thenExecute(() -> {
            helper.assertTrue(capability(helper, NetworkGameTests.at(3), Direction.NORTH) == null,
                    "a handler on another face");
            ResourceHandler<ItemResource> items = items(helper, NetworkGameTests.at(3));
            helper.assertValueEqual(insert(items, 20, true), 20, "inserted");
            helper.assertValueEqual(count(chest), 20, "in the chest");
            ItemStack item = in.toItem();
            helper.assertValueEqual(item.getOrDefault(ModDataComponents.P2P_LINK.get(), P2PSettings.NONE),
                    new P2PSettings(P2PRole.INPUT, 12), "the link in the part's item");
        }).thenSucceed();
    }

    /**
     * The linking tool: sneaking on an item end makes it an input and keeps the frequency, using it on another item end
     * makes that an output of it, and an end of another kind is refused.
     */
    private static void linkerPairs(GameTestHelper helper) {
        network(helper, POWER);
        P2PBlockEntity input = end(helper, P2PKind.ITEM, 3, P2PSettings.NONE);
        P2PBlockEntity output = end(helper, P2PKind.ITEM, 6, P2PSettings.NONE);
        P2PBlockEntity fluid = end(helper, P2PKind.FLUID, 8, P2PSettings.NONE);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        ItemStack tool = new ItemStack(P2PContent.LINKER.get());
        player.setShiftKeyDown(true);
        P2PLinkerItem.use(input, player, tool);
        P2PLinkerItem.Held held = tool.get(ModDataComponents.P2P_LINKER.get());
        helper.assertTrue(held != null && held.kind() == P2PKind.ITEM, "the tool holds nothing");
        helper.assertValueEqual(input.endpoint().role(), P2PRole.INPUT, "first end's role");
        player.setShiftKeyDown(false);
        P2PLinkerItem.use(output, player, tool);
        helper.assertValueEqual(output.endpoint().settings(),
                new P2PSettings(P2PRole.OUTPUT, input.endpoint().frequency()), "second end");
        P2PLinkerItem.use(fluid, player, tool);
        helper.assertValueEqual(fluid.endpoint().role(), P2PRole.NONE, "an end of another kind");
        helper.succeed();
    }

    /** A sneaking player holding the tool reaches the block through the game mode, as in a real client. */
    @SuppressWarnings("removal") // GameTestHelper's only way to a ServerPlayer with a connection; no replacement yet
    private static void linkerSneakRealPath(GameTestHelper helper) {
        network(helper, POWER);
        P2PBlockEntity input = end(helper, P2PKind.ITEM, 3, P2PSettings.NONE);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ItemStack tool = new ItemStack(P2PContent.LINKER.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, tool);
        player.setShiftKeyDown(true);
        BlockPos pos = input.getBlockPos();
        player.gameMode.useItemOn(player, helper.getLevel(), tool, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
        helper.assertValueEqual(input.endpoint().role(), P2PRole.INPUT, "sneak-use through the game mode");
        helper.succeed();
    }

    /**
     * An output facing its own input: an insert reaches the input again through the output and gets nothing there, so
     * nothing moves and nothing recurses.
     */
    private static void loopMovesNothing(GameTestHelper helper) {
        network(helper, POWER);
        P2PBlockEntity input = end(helper, P2PKind.ITEM, 3, new P2PSettings(P2PRole.INPUT, 13));
        for (int z = 2; z <= 3; z++) {
            ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(new BlockPos(4, 1, z)),
                    CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
        }
        helper.setBlock(target(3),
                P2PContent.block(P2PKind.ITEM).get().defaultBlockState().setValue(P2PBlock.FACING, Direction.NORTH));
        P2PBlockEntity output = helper.getBlockEntity(target(3), P2PBlockEntity.class);
        output.endpoint().link(new P2PSettings(P2PRole.OUTPUT, 13));
        helper.startSequence().thenWaitUntil(() -> inNetwork(helper, input, output)).thenExecute(() -> {
            long before = NetworkGameTests.state(helper, CONTROLLER).storedEnergy();
            helper.assertValueEqual(insert(items(helper, at(3)), 5, true), 0, "moved through a loop");
            helper.assertValueEqual(NetworkGameTests.state(helper, CONTROLLER).storedEnergy(), before, "FE");
        }).thenSucceed();
    }

    /**
     * Owned redstone ends whose team lacks P2P Links stay dark with their input powered; paying for Port Tier 2,
     * Antennas and P2P Links lights the output without any other event.
     */
    private static void researchGate(GameTestHelper helper) {
        network(helper, POWER);
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "p2p_research");
        ResearchTeam team = Research.team(owner, true);
        P2PBlockEntity input = end(helper, P2PKind.REDSTONE, 3, new P2PSettings(P2PRole.INPUT, 14));
        P2PBlockEntity output = end(helper, P2PKind.REDSTONE, 6, new P2PSettings(P2PRole.OUTPUT, 14));
        for (int x : new int[]{3, 6}) {
            BlockState state = helper.getBlockState(at(x));
            state.getBlock().setPlacedBy(helper.getLevel(), helper.absolutePos(at(x)), state, owner, ItemStack.EMPTY);
        }
        helper.setBlock(target(3), Blocks.REDSTONE_BLOCK);
        MinecraftServer server = helper.getLevel().getServer();
        helper.startSequence().thenWaitUntil(() -> inNetwork(helper, input, output)).thenIdle(10).thenExecute(() -> {
            helper.assertValueEqual(input.endpoint().inputSignal(), 15, "the input reads the redstone block");
            helper.assertValueEqual(output.endpoint().outputSignal(), 0, "signal without research");
            Research.addPoints(server, team, 5000);
            for (String node : new String[]{"port_tier_2", "antennas", "p2p_links"}) {
                helper.assertValueEqual(Research.spendPoints(server, team, MaxtechStorage.id(node)),
                        Research.Result.DONE, node);
            }
            helper.assertValueEqual(output.endpoint().outputSignal(), 15, "signal after research");
        }).thenSucceed();
    }

    private static void network(GameTestHelper helper, long power) {
        NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, CONTROLLER, power);
        NetworkGameTests.cables(helper, 2, 8, CableContent.STORAGE_CABLE_TIER_1.type().get());
    }

    private static BlockPos at(int x) {
        return new BlockPos(x, 1, 2);
    }

    private static BlockPos target(int x) {
        return new BlockPos(x, 1, 3);
    }

    /** A link block of {@code kind} beside the cable at {@code x}, facing south, with {@code settings}. */
    private static P2PBlockEntity end(GameTestHelper helper, P2PKind kind, int x, P2PSettings settings) {
        helper.setBlock(at(x),
                P2PContent.block(kind).get().defaultBlockState().setValue(P2PBlock.FACING, Direction.SOUTH));
        P2PBlockEntity entity = helper.getBlockEntity(at(x), P2PBlockEntity.class);
        if (settings.isLinked()) {
            entity.endpoint().link(settings);
        }
        return entity;
    }

    private static ItemStack linked(net.minecraft.world.item.Item item, P2PRole role, long frequency) {
        ItemStack stack = new ItemStack(item);
        stack.set(ModDataComponents.P2P_LINK.get(), new P2PSettings(role, frequency));
        return stack;
    }

    private static ChestBlockEntity chest(GameTestHelper helper, int x) {
        helper.setBlock(target(x), Blocks.CHEST);
        return helper.getBlockEntity(target(x), ChestBlockEntity.class);
    }

    private static void inNetwork(GameTestHelper helper, P2PBlockEntity... ends) {
        NetworkState network = NetworkGameTests.state(helper, CONTROLLER);
        for (P2PBlockEntity end : ends) {
            helper.assertTrue(end.network() == network, "an end is not in the network");
        }
        helper.assertTrue(network.routeRoot() == helper.absolutePos(CONTROLLER).asLong(), "no routes yet");
    }

    private static @Nullable ResourceHandler<ItemResource> capability(GameTestHelper helper, BlockPos pos,
            Direction side) {
        return helper.getLevel().getCapability(Capabilities.Item.BLOCK, helper.absolutePos(pos), side);
    }

    /** The item handler on the south face at {@code pos} (an input's front, or a cable's south part). */
    private static ResourceHandler<ItemResource> items(GameTestHelper helper, BlockPos pos) {
        ResourceHandler<ItemResource> handler = capability(helper, pos, Direction.SOUTH);
        if (handler == null) {
            throw helper.assertionException(Component.literal("No item handler at " + pos));
        }
        return handler;
    }

    private static int insert(ResourceHandler<ItemResource> handler, int amount, boolean commit) {
        try (Transaction transaction = Transaction.openRoot()) {
            int moved = ResourceHandlerUtil.insertStacking(handler, ItemResource.of(Items.COBBLESTONE), amount,
                    transaction);
            if (commit) {
                transaction.commit();
            }
            return moved;
        }
    }

    private static int count(ChestBlockEntity chest) {
        int total = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            total += chest.getItem(i).getCount();
        }
        return total;
    }

    private static int channels(GameTestHelper helper, NetworkState network, BlockPos pos) {
        return network.segmentChannels(helper.absolutePos(pos).asLong());
    }
}
