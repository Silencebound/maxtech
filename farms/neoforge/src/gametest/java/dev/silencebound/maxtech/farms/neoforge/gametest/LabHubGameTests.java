package dev.silencebound.maxtech.farms.neoforge.gametest;

import dev.silencebound.maxtech.farms.MaxtechFarms;
import dev.silencebound.maxtech.farms.config.FarmsServerConfig;
import dev.silencebound.maxtech.farms.hub.HubStorage;
import dev.silencebound.maxtech.farms.hub.LabHubBlockEntity;
import dev.silencebound.maxtech.farms.registry.FarmsContent;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetworks;
import dev.silencebound.maxtech.lib.api.energy.FeRate;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * The lab hub through other mods' eyes: its tank, FE input, fuel slot and output move only what fits and undo on an
 * aborted transaction; fuel burns into MU at its furnace burn time, pauses when the store is full and leaves its
 * remainder; an idle hub waits without a timer and registers no ticker; a broken hub drops with everything it holds.
 */
@EventBusSubscriber(modid = MaxtechFarms.MOD_ID)
public final class LabHubGameTests {
    private static final Identifier STRUCTURE = MaxtechFarms.id("empty_7x2x3");
    private static final BlockPos HUB = new BlockPos(1, 1, 1);
    private static final int COAL_TICKS = 1600;
    private static final Map<String, Consumer<GameTestHelper>> TESTS = Map.of("hub_tank_fills_and_drains",
            LabHubGameTests::tankFillsAndDrains, "hub_fe_charges", LabHubGameTests::feCharges, "hub_fuel_burns",
            LabHubGameTests::fuelBurns, "hub_fuel_pauses_when_full", LabHubGameTests::fuelPausesWhenFull,
            "hub_fuel_leaves_bucket", LabHubGameTests::fuelLeavesBucket, "hub_items_in_and_out",
            LabHubGameTests::itemsInAndOut, "hub_idle_sleeps", LabHubGameTests::idleSleeps, "hub_drops_with_contents",
            LabHubGameTests::dropsWithContents);

    private LabHubGameTests() {
    }

    /** The test functions, registered on the mod bus; the GameTest source set only exists in dev runs. */
    @SubscribeEvent
    static void registerFunctions(RegisterEvent event) {
        for (Map.Entry<String, Consumer<GameTestHelper>> test : TESTS.entrySet()) {
            event.register(Registries.TEST_FUNCTION, MaxtechFarms.id(test.getKey()), test::getValue);
        }
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechFarms.id("lab_hub"));
        for (String name : TESTS.keySet()) {
            event.registerTest(MaxtechFarms.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechFarms.id(name)),
                            new TestData<>(environment, STRUCTURE, 200, 0, true)));
        }
    }

    private static void tankFillsAndDrains(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper);
        ResourceHandler<FluidResource> tank = capability(helper, Capabilities.Fluid.BLOCK);
        FluidResource water = FluidResource.of(Fluids.WATER);
        int capacity = FarmsServerConfig.hubTankCapacity();
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertValueEqual(tank.insert(water, capacity + 4000, transaction), capacity,
                    "filled up to capacity");
        }
        helper.assertValueEqual(hub.storage().tank().getAmountAsLong(0), 0L, "aborted fill");
        try (Transaction transaction = Transaction.openRoot()) {
            tank.insert(water, 3000, transaction);
            transaction.commit();
        }
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertValueEqual(tank.insert(FluidResource.of(Fluids.LAVA), 1000, transaction), 0, "a second fluid");
            helper.assertValueEqual(tank.extract(water, 5000, transaction), 3000, "drained what it held");
            transaction.commit();
        }
        helper.assertValueEqual(hub.storage().tank().getAmountAsLong(0), 0L, "after draining");
        helper.succeed();
    }

    private static void feCharges(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper);
        EnergyHandler fe = capability(helper, Capabilities.Energy.BLOCK);
        FeRate rate = EnergyNetworks.feRate();
        int offered = (int) rate.toFe(1000) + rate.fe();
        int taken;
        try (Transaction transaction = Transaction.openRoot()) {
            taken = fe.insert(offered, transaction);
        }
        helper.assertValueEqual(hub.energy().stored(), 0L, "aborted charge");
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertValueEqual(fe.insert(offered, transaction), taken, "same charge again");
            transaction.commit();
        }
        long mu = hub.energy().stored();
        helper.assertTrue(mu > 0 && rate.unitsOfMu(mu) <= rate.unitsOfFe(taken),
                "no energy created: " + mu + " MU for " + taken + " FE at " + rate);
        hub.energy().setStored(hub.energy().capacity());
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertValueEqual(fe.insert(offered, transaction), 0, "a full hub");
            helper.assertValueEqual(fe.extract(offered, transaction), 0, "FE never leaves");
        }
        helper.succeed();
    }

    private static void fuelBurns(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper);
        long rate = FarmsServerConfig.hubFuelMuPerTick();
        insertItem(helper, new ItemStack(Items.COAL, 2), 2);
        helper.startSequence().thenIdle(45).thenExecute(() -> {
            helper.assertValueEqual(hub.storage().fuel().getAmountAsInt(0), 1, "coal left");
            long stored = hub.energy().stored();
            helper.assertTrue(stored > 0, "nothing burned");
            helper.assertValueEqual(stored, (COAL_TICKS - hub.burner().burnLeft()) * rate,
                    "MU match the burn time used");
            helper.assertValueEqual(hub.burner().burnTotal(), COAL_TICKS, "coal burn time");
        }).thenSucceed();
    }

    private static void fuelPausesWhenFull(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper);
        long rate = FarmsServerConfig.hubFuelMuPerTick();
        long capacity = hub.energy().capacity();
        hub.energy().setStored(capacity - 5 * rate);
        insertItem(helper, new ItemStack(Items.COAL), 1);
        helper.startSequence().thenIdle(60).thenExecute(() -> {
            helper.assertValueEqual(hub.energy().stored(), capacity, "full");
            helper.assertValueEqual(hub.burner().burnLeft(), COAL_TICKS - 5, "burned only what fit");
            helper.assertTrue(!hub.fuelTask().isQueued() && hub.fuelTask().wakeAt() == -1,
                    "a full hub waits without a timer");
            helper.assertValueEqual(hub.useEnergy(10 * rate), 10 * rate, "used");
        }).thenIdle(30).thenExecute(() -> {
            helper.assertValueEqual(hub.burner().burnLeft(), COAL_TICKS - 15, "resumed after energy was used");
            helper.assertValueEqual(hub.energy().stored(), capacity, "full again");
        }).thenSucceed();
    }

    private static void fuelLeavesBucket(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper);
        insertItem(helper, new ItemStack(Items.LAVA_BUCKET), 1);
        helper.startSequence().thenIdle(5).thenExecute(() -> {
            helper.assertTrue(hub.burner().isBurning(), "lava burns");
            ResourceHandler<ItemResource> items = capability(helper, Capabilities.Item.BLOCK);
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(items.extract(0, ItemResource.of(Items.BUCKET), 1, transaction), 1,
                        "the empty bucket comes out");
                transaction.commit();
            }
        }).thenSucceed();
    }

    private static void itemsInAndOut(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper);
        ResourceHandler<ItemResource> items = capability(helper, Capabilities.Item.BLOCK);
        hub.storage().output().set(3, ItemResource.of(Items.BONE), 10);
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertValueEqual(items.insert(ItemResource.of(Items.DIRT), 64, transaction), 0, "dirt is no fuel");
            helper.assertValueEqual(items.insert(4, ItemResource.of(Items.BONE), 1, transaction), 0,
                    "output takes nothing from outside");
            helper.assertValueEqual(items.extract(0, ItemResource.of(Items.COAL), 1, transaction), 0,
                    "empty fuel slot");
            helper.assertValueEqual(items.extract(ItemResource.of(Items.BONE), 64, transaction), 10, "bones out");
        }
        helper.assertValueEqual(hub.storage().output().getAmountAsInt(3), 10, "aborted extract");
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertValueEqual(items.insert(ItemResource.of(Items.COAL), 100, transaction), 64,
                    "fuel up to a stack");
            helper.assertValueEqual(items.extract(0, ItemResource.of(Items.COAL), 1, transaction), 0,
                    "fuel stays to burn");
        }
        helper.assertValueEqual(items.size(), 1 + HubStorage.OUTPUT_SLOTS, "slots");
        helper.succeed();
    }

    private static void idleSleeps(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper);
        helper.assertTrue(
                helper.getBlockState(HUB).getTicker(helper.getLevel(), FarmsContent.LAB_HUB_ENTITY.get()) == null,
                "the hub registers a ticker");
        helper.startSequence().thenIdle(3).thenExecute(() -> {
            helper.assertTrue(!hub.fuelTask().isQueued() && hub.fuelTask().wakeAt() == -1,
                    "an idle hub waits without a timer");
            EnergyHandler fe = capability(helper, Capabilities.Energy.BLOCK);
            try (Transaction transaction = Transaction.openRoot()) {
                fe.insert(100, transaction);
                transaction.commit();
            }
            helper.assertTrue(!hub.fuelTask().isQueued(), "FE woke the idle fuel task");
        }).thenSucceed();
    }

    private static void dropsWithContents(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper);
        hub.storage().tank().set(0, FluidResource.of(Fluids.WATER), 4000);
        hub.storage().output().set(0, ItemResource.of(Items.BONE), 7);
        hub.storage().fuel().set(0, ItemResource.of(Items.COAL), 3);
        hub.energy().setStored(1234);
        helper.getLevel().destroyBlock(helper.absolutePos(HUB), true);
        BlockPos at = helper.absolutePos(HUB);
        List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(2));
        helper.assertValueEqual(drops.size(), 1, "drops");
        ItemStack item = drops.get(0).getItem().copy();
        drops.get(0).discard();
        helper.assertTrue(item.is(FarmsContent.LAB_HUB_ITEM.get()), "dropped " + item);
        BlockPos place = new BlockPos(4, 1, 1);
        helper.setBlock(place.below(), Blocks.STONE);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, item);
        BlockPos below = helper.absolutePos(place.below());
        item.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(below), Direction.UP, below, false)));
        LabHubBlockEntity placed = helper.getBlockEntity(place, LabHubBlockEntity.class);
        helper.assertValueEqual(placed.storage().tank().getAmountAsLong(0), 4000L, "nutrient");
        helper.assertValueEqual(placed.storage().output().getAmountAsInt(0), 7, "output");
        helper.assertValueEqual(placed.storage().fuel().getAmountAsInt(0), 3, "fuel");
        helper.assertValueEqual(placed.energy().stored(), 1234L, "MU");
        helper.succeed();
    }

    private static LabHubBlockEntity hub(GameTestHelper helper) {
        helper.setBlock(HUB, FarmsContent.LAB_HUB.get());
        return helper.getBlockEntity(HUB, LabHubBlockEntity.class);
    }

    private static <T> T capability(GameTestHelper helper, BlockCapability<T, @Nullable Direction> capability) {
        T handler = helper.getLevel().getCapability(capability, helper.absolutePos(HUB), Direction.UP);
        if (handler == null) {
            throw helper.assertionException(Component.literal("no " + capability.name()));
        }
        return handler;
    }

    private static void insertItem(GameTestHelper helper, ItemStack stack, int expected) {
        ResourceHandler<ItemResource> items = capability(helper, Capabilities.Item.BLOCK);
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertValueEqual(items.insert(ItemResource.of(stack), stack.getCount(), transaction), expected,
                    "fuel in");
            transaction.commit();
        }
    }
}
