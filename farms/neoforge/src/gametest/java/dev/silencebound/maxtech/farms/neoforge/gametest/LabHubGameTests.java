package dev.silencebound.maxtech.farms.neoforge.gametest;

import dev.silencebound.maxtech.farms.MaxtechFarms;
import dev.silencebound.maxtech.farms.config.FarmsServerConfig;
import dev.silencebound.maxtech.farms.hub.HubStorage;
import dev.silencebound.maxtech.farms.hub.LabHubBlockEntity;
import dev.silencebound.maxtech.farms.registry.FarmsContent;
import dev.silencebound.maxtech.farms.sampler.SamplerItem;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetworks;
import dev.silencebound.maxtech.lib.api.energy.FeRate;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
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
 * remainder; an idle hub waits without a timer and registers no ticker and FE coming in wakes nothing; a broken or
 * exploded hub drops with everything it holds; a player's hand fills and empties the tank, puts fuel in and moves a
 * sampler in and out; the sampler charges from the store and stops when full.
 */
@EventBusSubscriber(modid = MaxtechFarms.MOD_ID)
public final class LabHubGameTests {
    private static final Identifier STRUCTURE = MaxtechFarms.id("empty_7x2x3");
    private static final BlockPos HUB = new BlockPos(1, 1, 1);
    private static final int COAL_TICKS = 1600;
    private static final Map<String, Consumer<GameTestHelper>> TESTS = Map.ofEntries(
            Map.entry("hub_tank_fills_and_drains", LabHubGameTests::tankFillsAndDrains),
            Map.entry("hub_fe_charges", LabHubGameTests::feCharges),
            Map.entry("hub_fuel_burns", LabHubGameTests::fuelBurns),
            Map.entry("hub_fuel_pauses_when_full", LabHubGameTests::fuelPausesWhenFull),
            Map.entry("hub_fuel_leaves_bucket", LabHubGameTests::fuelLeavesBucket),
            Map.entry("hub_fuel_resumes_when_output_frees", LabHubGameTests::fuelResumesWhenOutputFrees),
            Map.entry("hub_items_in_and_out", LabHubGameTests::itemsInAndOut),
            Map.entry("hub_idle_sleeps", LabHubGameTests::idleSleeps),
            Map.entry("hub_fe_wakes_nothing", LabHubGameTests::feWakesNothing),
            Map.entry("hub_hand_buckets", LabHubGameTests::handBuckets),
            Map.entry("hub_hand_fuel", LabHubGameTests::handFuel),
            Map.entry("hub_drops_with_contents", LabHubGameTests::dropsWithContents),
            Map.entry("hub_breaks_by_hand_with_contents", LabHubGameTests::breaksByHandWithContents),
            Map.entry("hub_creative_break_drops", LabHubGameTests::creativeBreakDrops),
            Map.entry("hub_survives_explosion", LabHubGameTests::survivesExplosion),
            Map.entry("hub_sampler_charges", LabHubGameTests::samplerCharges),
            Map.entry("hub_sampler_stops_when_full", LabHubGameTests::samplerStopsWhenFull),
            Map.entry("hub_overflow_kept", LabHubGameTests::overflowKept),
            Map.entry("hub_fuel_restored_when_remainder_short", LabHubGameTests::fuelRestoredWhenRemainderShort),
            Map.entry("hub_hand_sampler_unknown_slot", LabHubGameTests::handSamplerUnknownSlot),
            Map.entry("hub_unknown_survives_drop", LabHubGameTests::unknownSurvivesDrop));

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
        helper.assertValueEqual(hub.storage().tank().amount(), 0L, "aborted fill");
        try (Transaction transaction = Transaction.openRoot()) {
            tank.insert(water, 3000, transaction);
            transaction.commit();
        }
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertValueEqual(tank.insert(FluidResource.of(Fluids.LAVA), 1000, transaction), 0, "a second fluid");
            helper.assertValueEqual(tank.extract(water, 5000, transaction), 3000, "drained what it held");
            transaction.commit();
        }
        helper.assertValueEqual(hub.storage().tank().amount(), 0L, "after draining");
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
            helper.assertValueEqual(hub.storage().slots().stack(HubStorage.FUEL).getCount(), 1, "coal left");
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
                helper.assertValueEqual(items.extract(ItemResource.of(Items.BUCKET), 1, transaction), 1,
                        "the empty bucket comes out");
                transaction.commit();
            }
        }).thenSucceed();
    }

    /**
     * A lava bucket whose remainder fits nowhere (output full) waits; taking something out of the output wakes the hub,
     * so the burn starts and the empty bucket lands in the freed slot.
     */
    private static void fuelResumesWhenOutputFrees(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper);
        for (int slot = 0; slot < HubStorage.OUTPUT_SLOTS; slot++) {
            hub.storage().slots().set(HubStorage.OUTPUT_FIRST + slot, new ItemStack(Items.DIRT, 64));
        }
        hub.storage().slots().set(HubStorage.FUEL, new ItemStack(Items.LAVA_BUCKET));
        helper.startSequence().thenIdle(5).thenExecute(() -> {
            helper.assertTrue(!hub.burner().isBurning(), "burned without room for the bucket");
            ResourceHandler<ItemResource> items = capability(helper, Capabilities.Item.BLOCK);
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(items.extract(1, ItemResource.of(Items.DIRT), 64, transaction), 64, "dirt out");
                transaction.commit();
            }
        }).thenIdle(3).thenExecute(() -> {
            helper.assertTrue(hub.burner().isBurning(), "the burn did not start after output room opened");
            helper.assertTrue(hub.storage().slots().stack(HubStorage.FUEL).isEmpty(), "lava left the fuel slot");
            helper.assertTrue(hub.storage().slots().stack(HubStorage.OUTPUT_FIRST).is(Items.BUCKET),
                    "the bucket in the freed slot");
        }).thenSucceed();
    }

    /**
     * FE pushed every tick neither clears the burn's timer nor wakes a hub whose fuel slot holds only an empty bucket.
     * The farms task group runs on a wall-clock budget, so on a slow machine the first run or a due step can slip a few
     * ticks; the test waits for the task's state and treats a queued task as legitimate only once its timer is due.
     */
    private static void feWakesNothing(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper);
        EnergyHandler fe = capability(helper, Capabilities.Energy.BLOCK);
        insertItem(helper, new ItemStack(Items.COAL), 1);
        long[] due = {Long.MAX_VALUE};
        GameTestSequence sequence = helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(hub.burner().isBurning(), "the burn started");
            helper.assertTrue(!hub.fuelTask().isQueued() && hub.fuelTask().wakeAt() > 0, "the burn's timer is set");
            due[0] = hub.fuelTask().wakeAt();
        });
        for (int i = 0; i < 30; i++) {
            sequence.thenExecute(() -> {
                push(fe);
                helper.assertTrue(hub.burner().isBurning(), "burning");
                if (hub.fuelTask().isQueued()) {
                    helper.assertTrue(helper.getLevel().getGameTime() >= due[0], "FE woke the task before its step");
                } else {
                    helper.assertTrue(hub.fuelTask().wakeAt() > 0, "FE cleared the burn's timer");
                    due[0] = hub.fuelTask().wakeAt();
                }
            }).thenIdle(1);
        }
        sequence.thenExecute(() -> {
            hub.burner().load(0, 0);
            hub.storage().slots().set(HubStorage.FUEL, new ItemStack(Items.BUCKET));
        }).thenWaitUntil(() -> helper.assertTrue(!hub.fuelTask().isQueued() && hub.fuelTask().wakeAt() == -1,
                "the task settles without a timer"));
        for (int i = 0; i < 25; i++) {
            sequence.thenExecute(() -> {
                push(fe);
                helper.assertTrue(!hub.fuelTask().isQueued() && hub.fuelTask().wakeAt() == -1,
                        "FE woke a hub with only a bucket in its fuel slot");
            }).thenIdle(1);
        }
        sequence.thenSucceed();
    }

    private static void push(EnergyHandler fe) {
        try (Transaction transaction = Transaction.openRoot()) {
            fe.insert(100, transaction);
            transaction.commit();
        }
    }

    private static void itemsInAndOut(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper);
        ResourceHandler<ItemResource> items = capability(helper, Capabilities.Item.BLOCK);
        hub.storage().slots().set(HubStorage.OUTPUT_FIRST + 3, new ItemStack(Items.BONE, 10));
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertValueEqual(items.insert(ItemResource.of(Items.DIRT), 64, transaction), 0, "dirt is no fuel");
            helper.assertValueEqual(items.insert(4, ItemResource.of(Items.BONE), 1, transaction), 0,
                    "output takes nothing from outside");
            helper.assertValueEqual(items.extract(0, ItemResource.of(Items.COAL), 1, transaction), 0,
                    "empty fuel slot");
            helper.assertValueEqual(items.extract(ItemResource.of(Items.BONE), 64, transaction), 10, "bones out");
        }
        helper.assertValueEqual(hub.storage().slots().stack(HubStorage.OUTPUT_FIRST + 3).getCount(), 10,
                "aborted extract");
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
        LabHubBlockEntity hub = filled(helper);
        hub.burner().start(COAL_TICKS, helper.getLevel().getGameTime());
        ItemStack sampler = new ItemStack(FarmsContent.SAMPLER.get());
        SamplerItem.setCharge(sampler, 321);
        hub.storage().slots().set(HubStorage.SAMPLER, sampler);
        helper.getLevel().destroyBlock(helper.absolutePos(HUB), true);
        LabHubBlockEntity placed = placeDrop(helper);
        assertFilled(helper, placed);
        helper.assertValueEqual(placed.burner().burnLeft(), COAL_TICKS, "burn left");
        helper.assertValueEqual(SamplerItem.charge(placed.storage().sampler()), 321L, "sampler");
        helper.succeed();
    }

    /** A survival player breaking the hub with an empty hand gets it with everything it holds. */
    private static void breaksByHandWithContents(GameTestHelper helper) {
        filled(helper);
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        player.gameMode.destroyBlock(helper.absolutePos(HUB));
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.assertBlockNotPresent(FarmsContent.LAB_HUB.get(), HUB);
        assertFilled(helper, placeDrop(helper));
        helper.succeed();
    }

    /** A creative player gets no drops, so a hub that holds something drops itself (playerWillDestroy). */
    private static void creativeBreakDrops(GameTestHelper helper) {
        filled(helper);
        ServerPlayer player = player(helper, GameType.CREATIVE);
        player.gameMode.destroyBlock(helper.absolutePos(HUB));
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.assertBlockNotPresent(FarmsContent.LAB_HUB.get(), HUB);
        assertFilled(helper, placeDrop(helper));
        helper.succeed();
    }

    /**
     * A block explosion (drop decay on by default) never destroys the hub: it drops with its contents. Five rounds,
     * since the loot table's random sequence is seeded from the fixed test world seed: with the survives_explosion
     * condition back, one roll could pass every run.
     */
    private static void survivesExplosion(GameTestHelper helper) {
        Vec3 center = Vec3.atCenterOf(helper.absolutePos(HUB));
        for (int round = 0; round < 5; round++) {
            filled(helper);
            helper.getLevel().explode(null, center.x, center.y, center.z, 3.0F, Level.ExplosionInteraction.BLOCK);
            helper.assertBlockNotPresent(FarmsContent.LAB_HUB.get(), HUB);
            if (round < 4) {
                List<ItemEntity> drops = drops(helper);
                helper.assertValueEqual(drops.size(), 1, "dropped hubs in round " + round);
                helper.assertTrue(drops.get(0).getItem().has(DataComponents.BLOCK_ENTITY_DATA),
                        "contents in round " + round);
                drops.get(0).discard();
            }
        }
        assertFilled(helper, placeDrop(helper));
        helper.succeed();
    }

    /** A bucket fills the tank and drains it back; a lava bucket by hand goes into the tank, not the fuel slot. */
    private static void handBuckets(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper);
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        use(helper, player, new ItemStack(Items.WATER_BUCKET));
        helper.assertValueEqual(hub.storage().tank().amount(), 1000L, "water in");
        helper.assertTrue(player.getMainHandItem().is(Items.BUCKET), "hand after filling: " + player.getMainHandItem());
        use(helper, player, player.getMainHandItem());
        helper.assertValueEqual(hub.storage().tank().amount(), 0L, "water out");
        helper.assertTrue(player.getMainHandItem().is(Items.WATER_BUCKET),
                "hand after draining: " + player.getMainHandItem());
        use(helper, player, new ItemStack(Items.LAVA_BUCKET));
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.assertValueEqual(hub.storage().tank().amount(), 1000L, "lava in the tank");
        helper.assertTrue(hub.storage().tank().resource().equals(Optional.of(FluidKey.of(Fluids.LAVA))), "lava");
        helper.assertTrue(hub.storage().slots().stack(HubStorage.FUEL).isEmpty(), "lava went into the fuel slot");
        helper.succeed();
    }

    /** Fuel by hand: survival hands it over, creative keeps its stack. */
    private static void handFuel(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper);
        ServerPlayer survival = player(helper, GameType.SURVIVAL);
        use(helper, survival, new ItemStack(Items.COAL, 5));
        helper.assertValueEqual(survival.getMainHandItem().getCount(), 0, "survival stack");
        ServerPlayer creative = player(helper, GameType.CREATIVE);
        use(helper, creative, new ItemStack(Items.COAL, 5));
        helper.assertValueEqual(creative.getMainHandItem().getCount(), 5, "creative stack");
        helper.getLevel().getServer().getPlayerList().remove(survival);
        helper.getLevel().getServer().getPlayerList().remove(creative);
        // One coal may have started burning already.
        int held = hub.storage().slots().stack(HubStorage.FUEL).getCount() + (hub.burner().isBurning() ? 1 : 0);
        helper.assertValueEqual(held, 10, "coal in the hub");
        helper.succeed();
    }

    /**
     * A sampler put in by hand charges from the store in steps, taking exactly what it gains; sneaking with an empty
     * hand takes it out again.
     */
    private static void samplerCharges(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper);
        hub.energy().setStored(50_000);
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        ItemStack sampler = new ItemStack(FarmsContent.SAMPLER.get());
        SamplerItem.setCharge(sampler, 0);
        use(helper, player, sampler);
        helper.assertTrue(player.getMainHandItem().isEmpty(), "the sampler left the hand");
        long step = FarmsServerConfig.hubSamplerChargeMuPerTick() * 20;
        helper.startSequence().thenIdle(45).thenExecute(() -> {
            long charge = SamplerItem.charge(hub.storage().sampler());
            helper.assertTrue(charge >= 2 * step && charge <= 3 * step, "charged " + charge + " in 45 ticks");
            helper.assertValueEqual(hub.energy().stored(), 50_000 - charge, "MU taken from the store");
            player.setShiftKeyDown(true);
            use(helper, player, ItemStack.EMPTY);
            player.setShiftKeyDown(false);
            helper.getLevel().getServer().getPlayerList().remove(player);
            helper.assertTrue(player.getMainHandItem().is(FarmsContent.SAMPLER.get()), "taken out");
            helper.assertValueEqual(SamplerItem.charge(player.getMainHandItem()), charge, "charge kept");
            helper.assertTrue(hub.storage().sampler().isEmpty(), "the slot is empty");
        }).thenSucceed();
    }

    /** A full sampler and an empty store leave the task waiting without a timer; energy coming in resumes charging. */
    private static void samplerStopsWhenFull(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper);
        hub.energy().setStored(50_000);
        long capacity = SamplerItem.capacity();
        hub.storage().slots().set(HubStorage.SAMPLER, sampler(capacity - 100));
        helper.startSequence().thenIdle(30).thenExecute(() -> {
            helper.assertValueEqual(SamplerItem.charge(hub.storage().sampler()), capacity, "full");
            helper.assertValueEqual(hub.energy().stored(), 50_000L - 100, "took only what fit");
            helper.assertTrue(!hub.fuelTask().isQueued() && hub.fuelTask().wakeAt() == -1,
                    "a full sampler waits without a timer");
            hub.energy().setStored(0);
            hub.storage().slots().set(HubStorage.SAMPLER, sampler(0));
        }).thenIdle(3).thenExecute(() -> {
            helper.assertTrue(!hub.fuelTask().isQueued() && hub.fuelTask().wakeAt() == -1,
                    "an empty store waits without a timer");
            EnergyHandler fe = capability(helper, Capabilities.Energy.BLOCK);
            try (Transaction transaction = Transaction.openRoot()) {
                fe.insert((int) EnergyNetworks.feRate().toFe(1000), transaction);
                transaction.commit();
            }
        }).thenIdle(25).thenExecute(() -> helper.assertTrue(SamplerItem.charge(hub.storage().sampler()) > 0,
                "energy coming in resumed charging")).thenSucceed();
    }

    /**
     * A save holding a stack for a slot the hub does not have lands in the slots' overflow: the hub is not pristine, so
     * its dropped item carries the stack, and handing it out (listener index -1) leaves the hub pristine again.
     */
    private static void overflowKept(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper);
        var registries = helper.getLevel().registryAccess();
        ItemSlots larger = ItemSlots.builder(HubStorage.SIZE + 1).build(slot -> {
        });
        larger.set(HubStorage.SIZE, new ItemStack(Items.BONE, 9));
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        larger.save(out.child("items"));
        hub.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, registries, out.buildResult()));
        helper.assertValueEqual(hub.storage().slots().overflow().size(), 1, "overflow after loading");
        helper.assertTrue(!hub.isPristine(), "a hub with overflow counts as pristine");
        helper.getLevel().destroyBlock(helper.absolutePos(HUB), true);
        LabHubBlockEntity placed = placeDrop(helper);
        helper.assertValueEqual(placed.storage().slots().overflow().size(), 1, "overflow on the dropped hub");
        ItemStack taken = placed.storage().slots().takeOverflow(0);
        helper.assertTrue(ItemStack.matches(taken, new ItemStack(Items.BONE, 9)), "overflow stack " + taken);
        helper.assertTrue(placed.isPristine(), "pristine after the overflow was handed out");
        helper.succeed();
    }

    /**
     * The output buffer fills up between the simulated and the real remainder insert (the extraction's listener fills
     * the last free slot): the fuel goes back, the output holds no bucket, and the burn does not start.
     */
    private static void fuelRestoredWhenRemainderShort(GameTestHelper helper) {
        HubStorage storage = new HubStorage(helper::getLevel, new HubStorage.Listener() {
            @Override
            public void changed() {
            }

            @Override
            public void tankChanged() {
            }

            @Override
            public void fuelChanged() {
            }

            @Override
            public void outputChanged() {
            }

            @Override
            public void samplerChanged() {
            }
        });
        for (int slot = HubStorage.OUTPUT_FIRST; slot < HubStorage.SAMPLER - 1; slot++) {
            storage.slots().set(slot, new ItemStack(Items.DIRT, 64));
        }
        storage.slots().set(HubStorage.FUEL, new ItemStack(Items.LAVA_BUCKET));
        helper.assertValueEqual(
                storage.takeFuel(helper.getLevel(),
                        () -> storage.slots().set(HubStorage.SAMPLER - 1, new ItemStack(Items.DIRT, 64))),
                0, "burn time of a fuel that lost its room");
        helper.assertTrue(storage.slots().stack(HubStorage.FUEL).is(Items.LAVA_BUCKET), "the fuel was not restored");
        helper.assertValueEqual(storage.slots().stack(HubStorage.FUEL).getCount(), 1, "restored fuel count");
        for (int slot = HubStorage.OUTPUT_FIRST; slot < HubStorage.SAMPLER; slot++) {
            helper.assertTrue(storage.slots().stack(slot).is(Items.DIRT), "output slot " + slot + " holds a remainder");
        }
        helper.succeed();
    }

    /** A hand click with a sampler on a sampler slot that holds an unknown item neither throws nor replaces it. */
    private static void handSamplerUnknownSlot(GameTestHelper helper) {
        LabHubBlockEntity hub = unknownHub(helper);
        helper.assertTrue(hub.storage().slots().unknown(HubStorage.SAMPLER), "sampler slot not unknown");
        ServerPlayer player = player(helper, GameType.SURVIVAL);
        use(helper, player, sampler(5));
        helper.assertValueEqual(player.getMainHandItem().getCount(), 1, "the sampler left the hand");
        helper.assertTrue(hub.storage().slots().unknown(HubStorage.SAMPLER), "the unknown item is gone");
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    /** Unknown items and an unknown tank stay in the hub's save and in its dropped item. */
    private static void unknownSurvivesDrop(GameTestHelper helper) {
        LabHubBlockEntity hub = unknownHub(helper);
        var registries = helper.getLevel().registryAccess();
        CompoundTag before = hub.saveCustomOnly(registries);
        helper.assertTrue(hub.storage().tank().unknown(), "tank not unknown");
        helper.assertTrue(!hub.isPristine(), "a hub holding unknown things counts as pristine");
        helper.getLevel().destroyBlock(helper.absolutePos(HUB), true);
        LabHubBlockEntity placed = placeDrop(helper);
        helper.assertTrue(placed.storage().tank().unknown(), "tank lost its unknown contents");
        helper.assertTrue(placed.storage().slots().unknown(HubStorage.SAMPLER), "sampler slot lost its unknown item");
        helper.assertTrue(placed.storage().slots().unknown(HubStorage.OUTPUT_FIRST), "output lost its unknown item");
        CompoundTag after = placed.saveCustomOnly(registries);
        helper.assertValueEqual(after.getCompoundOrEmpty("items"), before.getCompoundOrEmpty("items"), "items saved");
        helper.assertValueEqual(after.getCompoundOrEmpty("nutrient"), before.getCompoundOrEmpty("nutrient"), "tank");
        helper.succeed();
    }

    /** A hub loaded from a save whose sampler slot, first output slot and tank hold things that no longer load. */
    private static LabHubBlockEntity unknownHub(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper);
        var registries = helper.getLevel().registryAccess();
        hub.storage().tank().set(FluidKey.of(Fluids.WATER), 4000);
        hub.storage().slots().set(HubStorage.SAMPLER, new ItemStack(Items.STICK));
        hub.storage().slots().set(HubStorage.OUTPUT_FIRST, new ItemStack(Items.BONE, 3));
        CompoundTag saved = hub.saveCustomOnly(registries);
        rename(saved, Map.of("minecraft:stick", "gone_mod:stick", "minecraft:bone", "gone_mod:bone", "minecraft:water",
                "gone_mod:goo"));
        hub.storage().slots().set(HubStorage.SAMPLER, ItemStack.EMPTY);
        hub.storage().slots().set(HubStorage.OUTPUT_FIRST, ItemStack.EMPTY);
        hub.storage().tank().clear();
        hub.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, registries, saved));
        return hub;
    }

    private static void rename(CompoundTag tag, Map<String, String> ids) {
        for (String key : List.copyOf(tag.keySet())) {
            Tag value = tag.get(key);
            if (value instanceof CompoundTag child) {
                rename(child, ids);
            } else if (value instanceof ListTag list) {
                for (Tag entry : list) {
                    if (entry instanceof CompoundTag child) {
                        rename(child, ids);
                    }
                }
            } else if (value instanceof StringTag string && ids.containsKey(string.value())) {
                tag.putString(key, ids.get(string.value()));
            }
        }
    }

    private static ItemStack sampler(long charge) {
        ItemStack sampler = new ItemStack(FarmsContent.SAMPLER.get());
        SamplerItem.setCharge(sampler, charge);
        return sampler;
    }

    /** A hub holding nutrient, output, fuel and MU. */
    private static LabHubBlockEntity filled(GameTestHelper helper) {
        LabHubBlockEntity hub = hub(helper);
        hub.storage().tank().set(FluidKey.of(Fluids.WATER), 4000);
        hub.storage().slots().set(HubStorage.OUTPUT_FIRST, new ItemStack(Items.BONE, 7));
        hub.storage().slots().set(HubStorage.FUEL, new ItemStack(Items.COAL, 3));
        hub.energy().setStored(1234);
        return hub;
    }

    private static void assertFilled(GameTestHelper helper, LabHubBlockEntity placed) {
        helper.assertValueEqual(placed.storage().tank().amount(), 4000L, "nutrient");
        helper.assertValueEqual(placed.storage().slots().stack(HubStorage.OUTPUT_FIRST).getCount(), 7, "output");
        helper.assertValueEqual(placed.storage().slots().stack(HubStorage.FUEL).getCount(), 3, "fuel");
        helper.assertValueEqual(placed.energy().stored(), 1234L, "MU");
    }

    /** Takes the one dropped hub near {@link #HUB} and places it at (4, 1, 1); returns the placed hub. */
    private static LabHubBlockEntity placeDrop(GameTestHelper helper) {
        List<ItemEntity> drops = drops(helper);
        helper.assertValueEqual(drops.size(), 1, "dropped hubs");
        ItemStack item = drops.get(0).getItem().copy();
        drops.get(0).discard();
        BlockPos place = new BlockPos(4, 1, 1);
        helper.setBlock(place.below(), Blocks.STONE);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, item);
        BlockPos below = helper.absolutePos(place.below());
        item.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(below), Direction.UP, below, false)));
        return helper.getBlockEntity(place, LabHubBlockEntity.class);
    }

    /** The dropped hubs near {@link #HUB}. */
    private static List<ItemEntity> drops(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(helper.absolutePos(HUB)).inflate(4),
                entity -> entity.getItem().is(FarmsContent.LAB_HUB_ITEM.get()));
    }

    /** A player with a connection, so the game mode's use and break paths run as for a real client. */
    @SuppressWarnings("removal") // GameTestHelper's only way to a ServerPlayer with a connection; no replacement yet
    private static ServerPlayer player(GameTestHelper helper, GameType mode) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(mode);
        return player;
    }

    /** {@code player} uses {@code stack} on the hub through the game mode. */
    private static void use(GameTestHelper helper, ServerPlayer player, ItemStack stack) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockPos pos = helper.absolutePos(HUB);
        player.gameMode.useItemOn(player, helper.getLevel(), player.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
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
