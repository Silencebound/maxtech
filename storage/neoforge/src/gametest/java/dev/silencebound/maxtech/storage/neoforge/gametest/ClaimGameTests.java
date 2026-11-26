package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlock;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlockEntity;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderMenu;
import dev.silencebound.maxtech.storage.network.FacingAccess;
import dev.silencebound.maxtech.storage.network.NetworkInfo;
import dev.silencebound.maxtech.storage.p2p.P2PBlock;
import dev.silencebound.maxtech.storage.p2p.P2PBlockEntity;
import dev.silencebound.maxtech.storage.p2p.P2PContent;
import dev.silencebound.maxtech.storage.p2p.P2PKind;
import dev.silencebound.maxtech.storage.p2p.P2PRole;
import dev.silencebound.maxtech.storage.p2p.P2PSettings;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import dev.silencebound.maxtech.storage.sensor.SensorPoll;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.CropBlock;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.port.PortBlock;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortMenu;
import dev.silencebound.maxtech.storage.port.PortMode;
import dev.silencebound.maxtech.storage.sensor.SensorBlock;
import dev.silencebound.maxtech.storage.sensor.SensorBlockEntity;
import dev.silencebound.maxtech.storage.sensor.SensorContent;
import dev.silencebound.maxtech.storage.sensor.SensorMenu;
import dev.silencebound.maxtech.storage.sensor.SensorWorker;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Land claims on the blocks devices face ({@code network.FacingAccess}): a listener cancels the right-click event of
 * fake players at chosen positions, as a claim mod would. Owned ports, sensors and pattern providers facing such a
 * block leave it alone and say "Blocked by a land claim" in their screen and the info lines, and work again once the
 * claim is lifted and their answer ran out ({@code claims.cacheSeconds} 1 here); an unowned port is not asked by
 * default; a kept answer asks nothing however often idle ports run.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class ClaimGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_16x12x16");
    private static final Identifier WIDE = MaxtechStorage.id("empty_42x4x27");
    private static final String CACHED = "claim_kept_answer_asks_nothing";
    private static final List<String> NAMES = new ArrayList<>();
    /** Tests that need the default 30-second answer, so expiry cannot hide what they look at. */
    private static final List<String> KEPT = new ArrayList<>();
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey STONE = ItemKey.of(Items.STONE);
    private static final BlockPos PORT = NetworkGameTests.at(3);
    private static final BlockPos TARGET = NetworkGameTests.at(4);
    private static final int IDLE_PORTS = 20;
    /** Absolute positions whose right-click a claim refuses to fake players. */
    private static final Set<BlockPos> CLAIMED = new HashSet<>();
    /**
     * Absolute positions where a right-click-harvest style mod would act on any right-click: each event posted there
     * counts, so a test sees whether a check reached it.
     */
    private static final Map<BlockPos, Integer> CLICKED = new HashMap<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("claim_port_blocked_then_lifted", ClaimGameTests::port);
        register("claim_unowned_port_not_asked", ClaimGameTests::unownedPort);
        register("claim_port_logic_move_blocked", ClaimGameTests::logicMove);
        register("claim_sensor_reads_nothing", ClaimGameTests::sensor);
        register("claim_provider_accepts_nothing", ClaimGameTests::provider);
        register("claim_p2p_output_blocked_then_lifted", ClaimGameTests::p2pOutput);
        register("claim_sensor_plain_block_not_clicked", ClaimGameTests::plainBlock);
        kept(CACHED, ClaimGameTests::keptAnswer);
        kept("claim_replaced_target_asks_again", ClaimGameTests::replacedTarget);
        kept("claim_unloaded_target_not_asked", ClaimGameTests::unloadedTarget);
    }

    private ClaimGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    private static void kept(String name, Consumer<GameTestHelper> test) {
        KEPT.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("claim"),
                new StorageTestEnvironment("claims.cacheSeconds", "1", false));
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 400);
        }
        Holder<TestEnvironmentDefinition<?>> kept = event.registerEnvironment(MaxtechStorage.id("claim_kept"),
                StorageTestEnvironment.plain());
        for (String name : KEPT) {
            StorageTests.test(event, kept, name, WIDE, 400);
        }
    }

    @SubscribeEvent
    static void rightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof FakePlayer)) {
            return;
        }
        CLICKED.computeIfPresent(event.getPos(), (pos, count) -> count + 1);
        if (CLAIMED.contains(event.getPos())) {
            event.setCanceled(true);
        }
    }

    private static void claim(GameTestHelper helper, BlockPos pos) {
        CLAIMED.add(helper.absolutePos(pos).immutable());
    }

    private static void lift(GameTestHelper helper, BlockPos pos) {
        CLAIMED.remove(helper.absolutePos(pos));
    }

    private static OwnerRecord owner(GameTestHelper helper, String name) {
        return OwnerRecord.of(NetworkGameTests.player(helper.getLevel(), name));
    }

    /** A powered controller at x 1 and a test drive at x 2, which the device at x 3 touches. */
    private static void network(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
    }

    private static ChestBlockEntity chest(GameTestHelper helper, ItemStack... stacks) {
        helper.setBlock(TARGET, Blocks.CHEST);
        ChestBlockEntity chest = helper.getBlockEntity(TARGET, ChestBlockEntity.class);
        for (int i = 0; i < stacks.length; i++) {
            chest.setItem(i, stacks[i]);
        }
        chest.setChanged();
        return chest;
    }

    private static PortBlockEntity importPort(GameTestHelper helper) {
        helper.setBlock(PORT, PortContent.PORT.get().defaultBlockState().setValue(PortBlock.FACING, Direction.EAST)
                .setValue(PortBlock.MODE, PortMode.IMPORT));
        return helper.getBlockEntity(PORT, PortBlockEntity.class);
    }

    private static long stored(GameTestHelper helper, StackKey key) {
        NetworkState network = NetworkGameTests.network(helper, PORT);
        return network == null ? 0 : network.contents().amount(key);
    }

    private static void assertInfo(GameTestHelper helper, BlockPos pos, boolean blocked) {
        boolean shown = NetworkGameTests.keys(helper, pos, BlockInfoRequest.Detail.FULL)
                .contains(NetworkInfo.CLAIM_BLOCKED_KEY);
        helper.assertValueEqual(shown, blocked, "the claim line in the info lines");
    }

    /**
     * An owned import port facing a claimed chest moves nothing and says so in its screen and info lines; once the
     * claim is lifted it moves everything after its answer runs out.
     */
    private static void port(GameTestHelper helper) {
        network(helper);
        ChestBlockEntity chest = chest(helper, new ItemStack(Items.COBBLESTONE, 40));
        claim(helper, TARGET);
        PortBlockEntity port = importPort(helper);
        port.setOwner(owner(helper, "claim_port_owner"));
        ServerPlayer viewer = NetworkGameTests.player(helper.getLevel(), "claim_port_viewer");
        helper.startSequence().thenIdle(40).thenExecute(() -> {
            helper.assertValueEqual(stored(helper, COBBLE), 0L, "cobblestone taken from a claimed chest");
            helper.assertValueEqual(chest.getItem(0).getCount(), 40, "cobblestone left in the chest");
            helper.assertTrue(port.claimBlocked(), "the port is blocked");
            helper.assertTrue(PortMenu.forPort(0, viewer.getInventory(), port).claimBlocked(),
                    "the screen shows the claim");
            assertInfo(helper, PORT, true);
            lift(helper, TARGET);
        }).thenWaitUntil(() -> helper.assertValueEqual(stored(helper, COBBLE), 40L, "cobblestone after the lift"))
                .thenExecute(() -> {
                    helper.assertFalse(port.claimBlocked(), "the port is no longer blocked");
                    assertInfo(helper, PORT, false);
                }).thenSucceed();
    }

    /**
     * A logic move (M7L area E) through an owned export port facing a claimed chest moves nothing and the network keeps
     * its cobblestone; once the claim is lifted and the answer ran out, the same move goes through.
     */
    private static void logicMove(GameTestHelper helper) {
        network(helper);
        ChestBlockEntity chest = chest(helper);
        claim(helper, TARGET);
        helper.setBlock(PORT, PortContent.PORT.get().defaultBlockState().setValue(PortBlock.FACING, Direction.EAST)
                .setValue(PortBlock.MODE, PortMode.EXPORT));
        PortBlockEntity port = helper.getBlockEntity(PORT, PortBlockEntity.class);
        port.setOwner(owner(helper, "claim_logic_owner"));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(NetworkGameTests.network(helper, PORT) != null, "no network"))
                .thenExecute(() -> {
                    NetworkGameTests.insert(NetworkGameTests.network(helper, PORT), COBBLE, 50);
                    long moved = port.logicMove(COBBLE, 10, true, helper.getLevel().getGameTime());
                    helper.assertValueEqual(moved, 0L, "moved into a claimed chest");
                    helper.assertTrue(chest.getItem(0).isEmpty(), "the chest");
                    helper.assertValueEqual(stored(helper, COBBLE), 50L, "stored");
                    lift(helper, TARGET);
                }).thenIdle(30).thenExecute(() -> {
                    long moved = port.logicMove(COBBLE, 10, true, helper.getLevel().getGameTime());
                    helper.assertValueEqual(moved, 10L, "moved after the lift");
                    helper.assertValueEqual(chest.getItem(0).getCount(), 10, "the chest");
                    helper.assertValueEqual(stored(helper, COBBLE), 40L, "stored");
                }).thenSucceed();
    }

    /** With {@code claims.checkUnowned} off (the default), a port nobody owns works as before. */
    private static void unownedPort(GameTestHelper helper) {
        network(helper);
        chest(helper, new ItemStack(Items.COBBLESTONE, 10));
        claim(helper, TARGET);
        PortBlockEntity port = importPort(helper);
        helper.startSequence().thenWaitUntil(() -> helper.assertValueEqual(stored(helper, COBBLE), 10L, "moved"))
                .thenExecute(() -> {
                    helper.assertValueEqual(port.worker().access().checks(), 0, "claims asked for an unowned port");
                    lift(helper, TARGET);
                }).thenSucceed();
    }

    /**
     * An owned sensor facing a claimed chest reads nothing (values 0, an empty list) and says so; after the lift it
     * reads the chest.
     */
    private static void sensor(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 1_000_000);
        BlockPos at = NetworkGameTests.at(2);
        BlockPos target = NetworkGameTests.at(3);
        helper.setBlock(target, Blocks.CHEST);
        ChestBlockEntity chest = helper.getBlockEntity(target, ChestBlockEntity.class);
        chest.setItem(0, new ItemStack(Items.DIRT, 18));
        chest.setChanged();
        claim(helper, target);
        helper.setBlock(at,
                SensorContent.SENSOR.get().defaultBlockState().setValue(SensorBlock.FACING, Direction.EAST));
        SensorBlockEntity entity = helper.getBlockEntity(at, SensorBlockEntity.class);
        entity.setOwner(owner(helper, "claim_sensor_owner"));
        SensorWorker sensor = entity.sensor();
        ServerPlayer viewer = NetworkGameTests.player(helper.getLevel(), "claim_sensor_viewer");
        helper.startSequence().thenIdle(30).thenExecute(() -> {
            helper.assertTrue(sensor.claimBlocked(), "the sensor is blocked");
            helper.assertValueEqual(sensor.value("items.total"), 0L, "items read through a claim");
            helper.assertTrue(sensor.values().isEmpty(), "values listed through a claim");
            helper.assertTrue(SensorMenu.server(0, viewer, entity).claimBlocked(), "the screen shows the claim");
            assertInfo(helper, at, true);
            lift(helper, target);
        }).thenWaitUntil(() -> helper.assertValueEqual(sensor.value("items.total"), 18L, "items after the lift"))
                .thenExecute(() -> {
                    helper.assertFalse(sensor.claimBlocked(), "the sensor is no longer blocked");
                    assertInfo(helper, at, false);
                }).thenSucceed();
    }

    /** An owned pattern provider facing a claimed chest accepts no batch; after the lift it does. */
    private static void provider(GameTestHelper helper) {
        network(helper);
        chest(helper);
        claim(helper, TARGET);
        CraftingPattern pattern = new CraftingPattern(CraftingPattern.Kind.PROCESSING,
                List.of(new PatternInput(new PatternIngredient.Exact(COBBLE), 1, Optional.empty(), false)),
                List.of(new PatternOutput(STONE, 1, 1f)), Optional.empty(), Optional.empty());
        helper.setBlock(PORT, ProviderContent.PROVIDER.get().defaultBlockState().setValue(PatternProviderBlock.FACING,
                Direction.EAST));
        PatternProviderBlockEntity provider = helper.getBlockEntity(PORT, PatternProviderBlockEntity.class);
        provider.setOwner(owner(helper, "claim_provider_owner"));
        provider.logic().slots().setItem(0, PatternItem.encode(pattern));
        ServerPlayer viewer = NetworkGameTests.player(helper.getLevel(), "claim_provider_viewer");
        helper.startSequence().thenIdle(5).thenExecute(() -> {
            helper.assertFalse(provider.logic().canAccept(pattern), "a batch accepted for a claimed chest");
            helper.assertTrue(provider.claimBlocked(), "the provider is blocked");
            helper.assertTrue(ProviderMenu.forDevice(0, viewer.getInventory(), provider).claimBlocked(),
                    "the screen shows the claim");
            assertInfo(helper, PORT, true);
            lift(helper, TARGET);
        }).thenIdle(25).thenExecute(() -> {
            helper.assertTrue(provider.logic().canAccept(pattern), "a batch accepted after the lift");
            helper.assertFalse(provider.claimBlocked(), "the provider is no longer blocked");
        }).thenSucceed();
    }

    /**
     * Twenty idle owned ports with the default 30-second answer: after the first check each, the runs of their backoff
     * ask the claim mods nothing more (each one check in 13 seconds).
     */
    private static void keptAnswer(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 1_000_000);
        OwnerRecord owner = owner(helper, "claim_idle_owner");
        List<PortBlockEntity> ports = new ArrayList<>(IDLE_PORTS);
        for (int column = 0; column < IDLE_PORTS; column++) {
            BlockPos floor = new BlockPos(2 + column, 1, 1);
            // Tier 4: a channel for every port (M7N).
            ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(floor),
                    CableContent.STORAGE_CABLE_TIER_4.type().get(), null);
            helper.setBlock(floor.above(),
                    PortContent.PORT.get().defaultBlockState().setValue(PortBlock.FACING, Direction.UP));
            helper.setBlock(floor.above(2), Blocks.BARREL);
            PortBlockEntity port = helper.getBlockEntity(floor.above(), PortBlockEntity.class);
            port.setOwner(owner);
            ports.add(port);
        }
        long[] runs = new long[1];
        helper.startSequence().thenIdle(60).thenExecute(() -> {
            for (PortBlockEntity port : ports) {
                helper.assertValueEqual(port.worker().access().checks(), 1, "checks of a port after its first runs");
            }
            runs[0] = NetworkGameTests.state(helper, controller).scheduler().runs();
        }).thenIdle(200).thenExecute(() -> {
            helper.assertTrue(NetworkGameTests.state(helper, controller).scheduler().runs() > runs[0],
                    "the idle ports ran");
            for (PortBlockEntity port : ports) {
                helper.assertValueEqual(port.worker().access().checks(), 1, "checks of a port with a kept answer");
                helper.assertFalse(port.claimBlocked(), "an unclaimed barrel blocked");
            }
        }).thenSucceed();
    }

    /**
     * An owned P2P item output facing a claimed chest hands nothing on from its input; after the lift and the answer
     * running out, everything reaches the chest.
     */
    private static void p2pOutput(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 1_000_000);
        NetworkGameTests.cables(helper, 2, 8, CableContent.STORAGE_CABLE_TIER_1.type().get());
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "claim_p2p_owner");
        ResearchTeam team = Research.team(player, true);
        MinecraftServer server = helper.getLevel().getServer();
        Research.addPoints(server, team, 5000);
        for (String node : new String[]{"port_tier_2", "antennas", "p2p_links"}) {
            helper.assertValueEqual(Research.spendPoints(server, team, MaxtechStorage.id(node)), Research.Result.DONE,
                    node);
        }
        P2PBlockEntity input = p2pEnd(helper, 3, P2PRole.INPUT, OwnerRecord.of(player));
        P2PBlockEntity output = p2pEnd(helper, 6, P2PRole.OUTPUT, OwnerRecord.of(player));
        BlockPos chestPos = new BlockPos(6, 1, 3);
        helper.setBlock(chestPos, Blocks.CHEST);
        ChestBlockEntity chest = helper.getBlockEntity(chestPos, ChestBlockEntity.class);
        claim(helper, chestPos);
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertTrue(input.network() == network && output.network() == network, "ends in the network");
        }).thenExecute(() -> {
            helper.assertValueEqual(p2pInsert(helper, 10), 0, "items handed into a claimed chest");
            helper.assertTrue(chest.isEmpty(), "the claimed chest stays empty");
            helper.assertTrue(output.claimBlocked(), "the output is blocked");
            assertInfo(helper, new BlockPos(6, 1, 2), true);
            lift(helper, chestPos);
        }).thenIdle(25).thenExecute(() -> {
            helper.assertValueEqual(p2pInsert(helper, 10), 10, "items handed on after the lift");
            helper.assertValueEqual(chest.getItem(0).getCount(), 10, "items in the chest");
            helper.assertFalse(output.claimBlocked(), "the output is no longer blocked");
        }).thenSucceed();
    }

    private static P2PBlockEntity p2pEnd(GameTestHelper helper, int x, P2PRole role, OwnerRecord owner) {
        BlockPos pos = new BlockPos(x, 1, 2);
        helper.setBlock(pos,
                P2PContent.block(P2PKind.ITEM).get().defaultBlockState().setValue(P2PBlock.FACING, Direction.SOUTH));
        P2PBlockEntity end = helper.getBlockEntity(pos, P2PBlockEntity.class);
        end.setOwner(owner);
        end.endpoint().link(new P2PSettings(role, 41));
        return end;
    }

    /** Inserts cobblestone into the input at x 3 through its front, committed; returns what went in. */
    private static int p2pInsert(GameTestHelper helper, int amount) {
        ResourceHandler<ItemResource> handler = helper.getLevel().getCapability(Capabilities.Item.BLOCK,
                helper.absolutePos(new BlockPos(3, 1, 2)), Direction.SOUTH);
        if (handler == null) {
            throw helper.assertionException(Component.literal("no item handler on the input"));
        }
        try (Transaction transaction = Transaction.openRoot()) {
            int moved = ResourceHandlerUtil.insertStacking(handler, ItemResource.of(Items.COBBLESTONE), amount,
                    transaction);
            transaction.commit();
            return moved;
        }
    }

    /**
     * A sensor polling a ripe wheat crop every second never posts a right-click there (a harvest mod would pick it),
     * and reads its age even inside a claim; a sensor facing a chest at a watched position does post, so the counter
     * works.
     */
    private static void plainBlock(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 1_000_000);
        OwnerRecord owner = owner(helper, "claim_crop_owner");
        BlockPos crop = new BlockPos(3, 1, 1);
        BlockPos chest = new BlockPos(3, 1, 3);
        helper.setBlock(new BlockPos(3, 0, 1), Blocks.FARMLAND);
        helper.setBlock(crop, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        helper.setBlock(chest, Blocks.CHEST);
        SensorWorker onCrop = pollingSensor(helper, new BlockPos(2, 1, 1), owner);
        SensorWorker onChest = pollingSensor(helper, new BlockPos(2, 1, 3), owner);
        BlockPos cropAt = helper.absolutePos(crop).immutable();
        BlockPos chestAt = helper.absolutePos(chest).immutable();
        CLICKED.put(cropAt, 0);
        CLICKED.put(chestAt, 0);
        claim(helper, crop);
        helper.startSequence().thenIdle(70).thenExecute(() -> {
            int cropClicks = CLICKED.getOrDefault(cropAt, -1);
            int chestClicks = CLICKED.getOrDefault(chestAt, -1);
            CLICKED.remove(cropAt);
            CLICKED.remove(chestAt);
            lift(helper, crop);
            helper.assertValueEqual(cropClicks, 0, "right-clicks posted at the crop");
            helper.assertTrue(chestClicks > 0, "no right-click posted at the chest");
            helper.assertValueEqual(onCrop.value("state.age"), 7L, "the crop's age");
            helper.assertFalse(onCrop.claimBlocked(), "a crop blocked by its claim");
            helper.assertTrue(onChest.hasRead(), "the chest read");
        }).thenSucceed();
    }

    private static SensorWorker pollingSensor(GameTestHelper helper, BlockPos pos, OwnerRecord owner) {
        helper.setBlock(pos,
                SensorContent.SENSOR.get().defaultBlockState().setValue(SensorBlock.FACING, Direction.EAST));
        SensorBlockEntity entity = helper.getBlockEntity(pos, SensorBlockEntity.class);
        entity.setOwner(owner);
        SensorWorker sensor = entity.sensor();
        sensor.setSettings(sensor.settings().withPoll(SensorPoll.ONE_SECOND));
        return sensor;
    }

    /**
     * A kept answer survives a change notice while the block stays the same; a chest replaced by a barrel in a claim is
     * asked about again at the next use, well within the 30-second answer.
     */
    private static void replacedTarget(GameTestHelper helper) {
        BlockPos device = helper.absolutePos(new BlockPos(2, 1, 1));
        BlockPos target = new BlockPos(3, 1, 1);
        helper.setBlock(target, Blocks.CHEST);
        OwnerRecord owner = owner(helper, "claim_replace_owner");
        FacingAccess access = new FacingAccess();
        ServerLevel level = helper.getLevel();
        helper.assertTrue(access.allowed(level, device, Direction.EAST, owner), "an unclaimed chest refused");
        helper.assertValueEqual(access.checks(), 1, "checks after the first use");
        claim(helper, target);
        access.targetChanged();
        helper.assertTrue(access.allowed(level, device, Direction.EAST, owner), "the kept answer for the same chest");
        helper.assertValueEqual(access.checks(), 1, "checks after a notice for the same block");
        helper.setBlock(target, Blocks.BARREL);
        access.targetChanged();
        helper.assertFalse(access.allowed(level, device, Direction.EAST, owner), "a claimed barrel allowed");
        helper.assertValueEqual(access.checks(), 2, "checks after the block was replaced");
        helper.assertTrue(access.blocked(), "blocked after the replacement");
        lift(helper, target);
        helper.succeed();
    }

    /** A target in a chunk that is not loaded is not asked about, and asking does not load the chunk. */
    private static void unloadedTarget(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos device = helper.absolutePos(new BlockPos(2, 1, 1)).offset(200_000, 0, 0);
        BlockPos target = device.east();
        int chunkX = SectionPos.blockToSectionCoord(target.getX());
        int chunkZ = SectionPos.blockToSectionCoord(target.getZ());
        helper.assertTrue(level.getChunkSource().getChunkNow(chunkX, chunkZ) == null, "the far chunk is loaded");
        CLAIMED.add(target);
        FacingAccess access = new FacingAccess();
        boolean allowed = access.allowed(level, device, Direction.EAST, owner(helper, "claim_far_owner"));
        CLAIMED.remove(target);
        helper.assertTrue(allowed, "an unloaded target refused");
        helper.assertValueEqual(access.checks(), 0, "events posted for an unloaded target");
        helper.assertFalse(access.blocked(), "blocked without an answer");
        helper.assertTrue(level.getChunkSource().getChunkNow(chunkX, chunkZ) == null, "the check loaded the chunk");
        helper.succeed();
    }
}
