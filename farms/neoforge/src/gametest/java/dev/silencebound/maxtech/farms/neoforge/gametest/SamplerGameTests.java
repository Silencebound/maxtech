package dev.silencebound.maxtech.farms.neoforge.gametest;

import dev.silencebound.maxtech.farms.MaxtechFarms;
import dev.silencebound.maxtech.farms.registry.FarmsContent;
import dev.silencebound.maxtech.farms.sampler.SamplerItem;
import dev.silencebound.maxtech.farms.specimen.Specimen;
import dev.silencebound.maxtech.farms.specimen.SpecimenItem;
import dev.silencebound.maxtech.farms.specimen.SpecimenKind;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetworks;
import dev.silencebound.maxtech.farms.neoforge.SamplerEnergyHandler;
import dev.silencebound.maxtech.lib.api.energy.FeRate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
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
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The sampler on a zombie next to a mock player: a finished sample gives a fresh specimen and costs MU, refusals and
 * cancels cost nothing and let the entity's own interaction happen, fresh samples of one mob stack, a full inventory
 * drops the specimen, and other mods' FE chargers fill it without creating energy at an uneven rate.
 */
@EventBusSubscriber(modid = MaxtechFarms.MOD_ID)
public final class SamplerGameTests {
    private static final Identifier STRUCTURE = MaxtechFarms.id("empty_7x2x3");
    private static final Map<String, Consumer<GameTestHelper>> TESTS = Map.of("sampler_takes_specimen",
            SamplerGameTests::takesSpecimen, "sampler_refuses_boss_and_player", SamplerGameTests::refusesBossAndPlayer,
            "sampler_refuses_without_charge", SamplerGameTests::refusesWithoutCharge, "sampler_cancel_costs_nothing",
            SamplerGameTests::cancelCostsNothing, "sampler_fresh_specimens_stack",
            SamplerGameTests::freshSpecimensStack, "sampler_charges_from_fe", SamplerGameTests::chargesFromFe,
            "sampler_charges_at_uneven_rate", SamplerGameTests::chargesAtUnevenRate, "sampler_full_inventory_drops",
            SamplerGameTests::fullInventoryDrops);

    private SamplerGameTests() {
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
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechFarms.id("sampler"));
        for (String name : TESTS.keySet()) {
            event.registerTest(MaxtechFarms.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechFarms.id(name)),
                            new TestData<>(environment, STRUCTURE, 20, 0, true)));
        }
    }

    private static void takesSpecimen(GameTestHelper helper) {
        Player player = player(helper);
        Zombie zombie = zombie(helper);
        float health = zombie.getHealth();
        helper.assertValueEqual(sample(helper, player, zombie), true, "sampled");
        ItemStack specimen = player.getInventory().getItem(1);
        helper.assertValueEqual(Objects.<Object>requireNonNullElse(SpecimenItem.data(specimen), "none"),
                Specimen.fresh(SpecimenKind.MOB, EntityType.getKey(EntityType.ZOMBIE)), "specimen");
        helper.assertValueEqual(specimen.getCount(), 1, "specimens");
        helper.assertValueEqual(SamplerItem.charge(sampler(player)),
                SamplerItem.capacity() - SamplerItem.costPerSample(), "charge after one sample");
        helper.assertTrue(zombie.isAlive() && zombie.getHealth() == health, "the mob is unharmed");
        helper.succeed();
    }

    private static void refusesBossAndPlayer(GameTestHelper helper) {
        Player player = player(helper);
        Entity wither = EntityType.WITHER.create(helper.getLevel(), EntitySpawnReason.COMMAND);
        if (wither == null) {
            throw helper.assertionException(Component.literal("no wither"));
        }
        wither.setPos(player.position().add(1, 0, 0));
        assertRefused(helper, player, wither, SamplerItem.Refusal.BOSS);
        Player other = player(helper);
        assertRefused(helper, player, other, SamplerItem.Refusal.PLAYER);
        Entity minecart = EntityType.MINECART.create(helper.getLevel(), EntitySpawnReason.COMMAND);
        if (minecart == null) {
            throw helper.assertionException(Component.literal("no minecart"));
        }
        minecart.setPos(player.position().add(1, 0, 0));
        assertRefused(helper, player, minecart, SamplerItem.Refusal.NOT_A_MOB);
        helper.succeed();
    }

    private static void refusesWithoutCharge(GameTestHelper helper) {
        Player player = player(helper);
        Zombie zombie = zombie(helper);
        SamplerItem.setCharge(sampler(player), 0);
        assertRefused(helper, player, zombie, SamplerItem.Refusal.NO_CHARGE);
        SamplerItem.setCharge(sampler(player), SamplerItem.costPerSample() - 1);
        assertRefused(helper, player, zombie, SamplerItem.Refusal.NO_CHARGE);
        helper.succeed();
    }

    private static void cancelCostsNothing(GameTestHelper helper) {
        Player player = player(helper);
        Zombie zombie = zombie(helper);
        start(helper, player, zombie);
        player.releaseUsingItem();
        helper.assertFalse(player.isUsingItem(), "released");
        helper.assertFalse(SamplerItem.complete(player, sampler(player)), "no sample after release");

        start(helper, player, zombie);
        zombie.discard();
        helper.assertFalse(SamplerItem.complete(player, sampler(player)), "no sample of a removed mob");
        player.stopUsingItem();

        Zombie far = zombie(helper);
        start(helper, player, far);
        far.setPos(player.position().add(20, 0, 0));
        helper.assertFalse(SamplerItem.complete(player, sampler(player)), "no sample out of reach");

        helper.assertTrue(player.getInventory().getItem(1).isEmpty(), "no specimen");
        helper.assertValueEqual(SamplerItem.charge(sampler(player)), SamplerItem.capacity(), "charge");
        helper.succeed();
    }

    private static void freshSpecimensStack(GameTestHelper helper) {
        Player player = player(helper);
        Zombie zombie = zombie(helper);
        helper.assertValueEqual(sample(helper, player, zombie), true, "first sample");
        helper.assertValueEqual(sample(helper, player, zombie), true, "second sample");
        helper.assertValueEqual(player.getInventory().getItem(1).getCount(), 2, "stacked specimens");
        helper.assertTrue(player.getInventory().getItem(2).isEmpty(), "no second stack");
        helper.succeed();
    }

    private static void chargesFromFe(GameTestHelper helper) {
        Player player = player(helper);
        SamplerItem.setCharge(sampler(player), 0);
        EnergyHandler battery = ItemAccess.forPlayerSlot(player, 0).getCapability(Capabilities.Energy.ITEM);
        if (battery == null) {
            throw helper.assertionException(Component.literal("no item energy capability on the sampler"));
        }
        long capacityFe = EnergyNetworks.feRate().toFe(SamplerItem.capacity());
        helper.assertValueEqual(battery.getCapacityAsLong(), capacityFe, "capacity in FE");
        int inserted;
        try (Transaction transaction = Transaction.openRoot()) {
            inserted = battery.insert(Integer.MAX_VALUE, transaction);
            transaction.commit();
        }
        helper.assertValueEqual((long) inserted, capacityFe, "FE taken");
        helper.assertValueEqual(SamplerItem.charge(sampler(player)), SamplerItem.capacity(), "full after charging");
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertValueEqual(battery.insert(1000, transaction), 0, "a full sampler takes nothing");
            helper.assertValueEqual(battery.extract(1000, transaction), 0, "chargers draw nothing");
        }
        helper.succeed();
    }

    /**
     * Lib's configured FE rate is set by Lib alone, so the handler gets 3 FE : 2 MU passed in: 10 FE make 6 MU for 9
     * FE, a single FE makes no whole MU and is refused, and the FE shown is the charge's worth rounded down.
     */
    private static void chargesAtUnevenRate(GameTestHelper helper) {
        Player player = player(helper);
        SamplerItem.setCharge(sampler(player), 0);
        FeRate rate = new FeRate(3, 2);
        SamplerEnergyHandler battery = new SamplerEnergyHandler(ItemAccess.forPlayerSlot(player, 0), () -> rate);
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertValueEqual(battery.insert(10, transaction), 9, "FE taken for 6 MU");
            transaction.commit();
        }
        helper.assertValueEqual(SamplerItem.charge(sampler(player)), 6L, "MU after 10 FE");
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertValueEqual(battery.insert(1, transaction), 0, "one FE makes no whole MU");
            helper.assertValueEqual(battery.insert(2, transaction), 2, "two FE make one MU");
            transaction.commit();
        }
        helper.assertValueEqual(SamplerItem.charge(sampler(player)), 7L, "MU after 2 more FE");
        helper.assertValueEqual(battery.getAmountAsLong(), 10L, "7 MU shown as FE, rounded down");
        helper.succeed();
    }

    private static void fullInventoryDrops(GameTestHelper helper) {
        Player player = player(helper);
        Zombie zombie = zombie(helper);
        for (int slot = 1; slot < player.getInventory().getContainerSize(); slot++) {
            player.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
        }
        helper.assertValueEqual(sample(helper, player, zombie), true, "sampled");
        Specimen fresh = Specimen.fresh(SpecimenKind.MOB, EntityType.getKey(EntityType.ZOMBIE));
        List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                player.getBoundingBox().inflate(2), drop -> fresh.equals(SpecimenItem.data(drop.getItem())));
        helper.assertValueEqual(drops.size(), 1, "dropped specimens");
        helper.succeed();
    }

    /** A survival mock player at (1, 1, 1) holding a full sampler in slot 0. */
    private static Player player(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(helper.absoluteVec(new Vec3(1.5, 1, 1.5)));
        player.getInventory().setItem(0, new ItemStack(FarmsContent.SAMPLER.get()));
        player.getInventory().setSelectedSlot(0);
        return player;
    }

    private static Zombie zombie(GameTestHelper helper) {
        return helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new Vec3(3.5, 1, 1.5));
    }

    private static ItemStack sampler(Player player) {
        return player.getInventory().getItem(0);
    }

    private static void start(GameTestHelper helper, Player player, Entity target) {
        InteractionResult result = SamplerItem.startSampling(player, InteractionHand.MAIN_HAND, target);
        helper.assertTrue(result.consumesAction(), "sampling starts");
        helper.assertTrue(player.isUsingItem(), "using the sampler");
    }

    /** Starts sampling and finishes it the way the game does after {@link SamplerItem#USE_TICKS}. */
    private static boolean sample(GameTestHelper helper, Player player, Entity target) {
        start(helper, player, target);
        long before = SamplerItem.charge(sampler(player));
        player.getUseItem().finishUsingItem(helper.getLevel(), player);
        player.stopUsingItem();
        return SamplerItem.charge(sampler(player)) == before - SamplerItem.costPerSample();
    }

    private static void assertRefused(GameTestHelper helper, Player player, Entity target,
            SamplerItem.Refusal expected) {
        long before = SamplerItem.charge(sampler(player));
        helper.assertTrue(SamplerItem.refusal(sampler(player), target) == expected, "refusal " + expected);
        InteractionResult result = SamplerItem.startSampling(player, InteractionHand.MAIN_HAND, target);
        helper.assertTrue(result instanceof InteractionResult.Pass, "a refusal passes to the entity's own interaction");
        helper.assertFalse(player.isUsingItem(), "not using the sampler");
        helper.assertValueEqual(SamplerItem.charge(sampler(player)), before, "charge unchanged");
    }
}
