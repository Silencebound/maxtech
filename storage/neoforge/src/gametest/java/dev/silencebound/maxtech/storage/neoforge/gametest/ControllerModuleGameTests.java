package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ClusterStats;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.controller.card.CardContent;
import dev.silencebound.maxtech.storage.controller.card.CardKind;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.ModuleKind;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The controller modules of M6c2 area G that shape energy, as upgrade cards since M7R item 2: efficiency core cards cut
 * the FE per operation, a surge regulator card holds automation back on low energy while players go on, and the
 * advanced modules need research.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
// Networks are compared by identity.
@SuppressWarnings("ReferenceEquality")
public final class ControllerModuleGameTests {
    private static final String EFFICIENCY = "controller_efficiency_core_saves_fe";
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final List<String> NAMES = new ArrayList<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        StorageTests.function(EFFICIENCY,
                StorageTestEnvironment.clearing(ControllerModuleGameTests::efficiencyCoreSavesFe));
        register("controller_surge_regulator_holds_automation", ControllerModuleGameTests::surgeHoldsAutomation);
        register("controller_efficiency_core_per_tick", ControllerModuleGameTests::efficiencyPerTick);
        register("controller_modules_need_research", ControllerModuleGameTests::modulesNeedResearch);
        register("controller_deleted_modules_drop_on_load", ControllerModuleGameTests::deletedModulesDropOnLoad);
    }

    private ControllerModuleGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("controller_modules"), StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 600);
        }
        // A cost per item, which the presets leave at 0, so the discount shows in more than one FE.
        StorageTests.test(event, event.registerEnvironment(MaxtechStorage.id(EFFICIENCY),
                new StorageTestEnvironment("network.energy.perUnit", "1", false)), EFFICIENCY, STRUCTURE, 600);
    }

    /**
     * With 1 FE per item on top of 4 per operation, an operation of 64 items costs 68 FE; one efficiency core makes it
     * 62 (rounded up from 61.2), for player and device operations alike, and a second operation in the same tick 61, as
     * the discount applies to the tick's sum (123 for 136); four cores reach the cap of -40 %: 41, and a fifth card is
     * refused.
     */
    private static void efficiencyCoreSavesFe(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        TestStorage.TestDevice device = NetworkGameTests.device(helper, core.north());
        long[] costs = new long[4];
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, core);
            helper.assertTrue(device.network() == network, "network formed");
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, core, 10_000);
            costs[0] = playerInsertCost(helper, core);
            ControllerCardGameTests.install(helper, core, CardKind.EFFICIENCY_CORE, 1);
        }).thenWaitUntil(() -> helper.assertValueEqual(NetworkGameTests.state(helper, core).energyPercent(), 90,
                "cost with one core"))
                // A card works at once; the next tick keeps the first cost out of the tick's sum.
                .thenIdle(1).thenExecute(() -> {
                    costs[1] = playerInsertCost(helper, core);
                    NetworkState network = NetworkGameTests.state(helper, core);
                    StorageTransfer transfer = device.deviceLink().transfer();
                    if (transfer == null) {
                        throw helper.assertionException(Component.literal("device in no network"));
                    }
                    long before = network.storedEnergy();
                    try (Transaction transaction = Transaction.openRoot()) {
                        helper.assertValueEqual(transfer.insert(COBBLE, 64, transaction), 64L, "device op");
                        transaction.commit();
                    }
                    costs[2] = before - network.storedEnergy();
                    ControllerCardGameTests.install(helper, core, CardKind.EFFICIENCY_CORE, 3);
                    ItemStack fifth = new ItemStack(CardContent.item(CardKind.EFFICIENCY_CORE));
                    helper.assertFalse(NetworkGameTests.controllerEntity(helper, core).cards()
                            .insert(fifth, ClusterStats.Cards.fromConfig()).isEmpty(), "a fifth core refused");
                }).thenWaitUntil(() -> helper.assertValueEqual(NetworkGameTests.state(helper, core).energyPercent(), 60,
                        "cost with four cores"))
                .thenIdle(1).thenExecute(() -> {
                    costs[3] = playerInsertCost(helper, core);
                    helper.assertValueEqual(costs[0], 68L, "64 items without a core");
                    helper.assertValueEqual(costs[1], 62L, "player operation with one core");
                    helper.assertValueEqual(costs[2], 61L, "device operation with one core, same tick");
                    helper.assertValueEqual(costs[3], 41L, "capped at -40 %");
                }).thenSucceed();
    }

    /**
     * At the presets' 4 FE per operation one efficiency core used to save nothing (3.6 rounded up). The discount now
     * applies to the tick's sum: ten device operations in one tick cost 36 FE, not 40; nine more in the next tick cost
     * 33 (32.4 rounded up once).
     */
    private static void efficiencyPerTick(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        TestStorage.TestDevice device = NetworkGameTests.device(helper, core.north());
        long[] costs = new long[4];
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, core);
            helper.assertTrue(device.network() == network, "network formed");
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, core, 10_000);
            ControllerCardGameTests.install(helper, core, CardKind.EFFICIENCY_CORE, 1);
        }).thenWaitUntil(() -> helper.assertValueEqual(NetworkGameTests.state(helper, core).energyPercent(), 90,
                "cost with one core")).thenExecute(() -> costs[0] = deviceInserts(helper, core, device, 10)).thenIdle(1)
                .thenExecute(() -> costs[1] = deviceInserts(helper, core, device, 9)).thenIdle(1)
                .thenExecute(() -> rolledBackInTick(helper, core, device, costs)).thenExecute(() -> {
                    helper.assertValueEqual(costs[0], 36L, "ten operations");
                    helper.assertValueEqual(costs[1], 33L, "nine operations in the next tick");
                    helper.assertValueEqual(costs[2], 4L, "the first operation of a tick");
                    // 8 for the two committed operations (7.2 rounded up): the rolled-back one left no credit or debt.
                    helper.assertValueEqual(costs[3], 4L, "the operation after a rolled-back one");
                }).thenSucceed();
    }

    /**
     * In one tick: a committed operation, one rolled back after paying, another committed; {@code costs[2]} and
     * {@code costs[3]} get the two committed operations' FE.
     */
    private static void rolledBackInTick(GameTestHelper helper, BlockPos core, TestStorage.TestDevice device,
            long[] costs) {
        costs[2] = deviceInserts(helper, core, device, 1);
        NetworkState network = NetworkGameTests.state(helper, core);
        StorageTransfer transfer = device.deviceLink().transfer();
        if (transfer == null) {
            throw helper.assertionException(Component.literal("device in no network"));
        }
        long before = network.storedEnergy();
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertValueEqual(transfer.insert(COBBLE, 1, transaction), 1L, "rolled-back op");
        }
        helper.assertValueEqual(network.storedEnergy(), before, "FE after the rollback");
        costs[3] = deviceInserts(helper, core, device, 1);
    }

    /** FE {@code count} committed device inserts of one cobblestone each cost, all in this tick. */
    private static long deviceInserts(GameTestHelper helper, BlockPos core, TestStorage.TestDevice device, int count) {
        NetworkState network = NetworkGameTests.state(helper, core);
        StorageTransfer transfer = device.deviceLink().transfer();
        if (transfer == null) {
            throw helper.assertionException(Component.literal("device in no network"));
        }
        long before = network.storedEnergy();
        for (int i = 0; i < count; i++) {
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(transfer.insert(COBBLE, 1, transaction), 1L, "device op");
                transaction.commit();
            }
        }
        return before - network.storedEnergy();
    }

    /** FE a committed player insert of 64 cobblestone costs the network at {@code core}. */
    private static long playerInsertCost(GameTestHelper helper, BlockPos core) {
        NetworkState network = NetworkGameTests.state(helper, core);
        long before = network.storedEnergy();
        helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 64), 64L, "player insert");
        return before - network.storedEnergy();
    }

    /**
     * With a surge regulator and 5 % FE, a device's automation moves nothing and sleeps: its task does not run again
     * while the FE stays low, yet a player's insert and a player's task still go through. FE back above 20 % wakes the
     * task at once, and it moves its item.
     */
    private static void surgeHoldsAutomation(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        TestStorage.TestDevice device = NetworkGameTests.device(helper, core.north());
        int[] runs = {0};
        long[] moved = {0};
        int[] retry = {-1};
        int[] playerMoved = {-1};
        StorageTask automation = (transfer, gameTime) -> {
            runs[0]++;
            try (Transaction transaction = Transaction.openRoot()) {
                moved[0] += transfer.insert(COBBLE, 1, transaction);
                transaction.commit();
            }
            retry[0] = transfer.retryTicks();
            return moved[0] > 0 ? StorageTask.DONE : retry[0];
        };
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, core);
            helper.assertTrue(device.network() == network, "network formed");
            helper.assertValueEqual(NetworkGameTests.controllerEntity(helper, core).cards().usable(), 4, "card slots");
        }).thenExecute(() -> ControllerCardGameTests.install(helper, core, CardKind.SURGE_REGULATOR, 1))
                .thenExecute(() -> {
                    helper.assertValueEqual(NetworkGameTests.state(helper, core).surgeReservePercent(), 10,
                            "surge level");
                }).thenExecute(() -> {
                    NetworkGameTests.power(helper, core, 500);
                    device.schedule(automation);
                }).thenWaitUntil(() -> helper.assertValueEqual(runs[0], 1, "automation ran")).thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, core);
                    helper.assertValueEqual(moved[0], 0L, "automation held back at 5 %");
                    helper.assertTrue(retry[0] >= 100, "sleeps long: " + retry[0]);
                    helper.assertValueEqual(network.throttledDevices(), 1, "held-back devices");
                    helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 10), 10L,
                            "a player's insert goes on");
                    device.schedule(new StorageTask() {
                        @Override
                        public boolean playerPriority() {
                            return true;
                        }

                        @Override
                        public int run(StorageTransfer transfer, long gameTime) {
                            try (Transaction transaction = Transaction.openRoot()) {
                                playerMoved[0] = (int) transfer.insert(COBBLE, 1, transaction);
                                transaction.commit();
                            }
                            return DONE;
                        }
                    });
                }).thenWaitUntil(() -> helper.assertValueEqual(playerMoved[0], 1, "a player's task goes on"))
                .thenIdle(60).thenExecute(() -> {
                    helper.assertValueEqual(runs[0], 1, "no polling while the FE stays low");
                    // 500 - 12 - 3 = 485 FE; 1,600 more is above 20 % of 10,000.
                    NetworkGameTests.power(helper, core, 1_600);
                }).thenWaitUntil(() -> helper.assertValueEqual(moved[0], 1L, "automation resumed")).thenExecute(() -> {
                    helper.assertValueEqual(runs[0], 2, "woken once by the energy");
                    helper.assertValueEqual(NetworkGameTests.state(helper, core).throttledDevices(), 0,
                            "no held-back devices");
                }).thenSucceed();
    }

    /**
     * Modules need research to place: an antenna mount is refused until the owner's team completes Antennas, while a
     * batch amplifier needs none. (The alarm module this test placed is built into the controller since M7R item 3.)
     */
    private static void modulesNeedResearch(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = NetworkGameTests.player(level, "module_research");
        ResearchTeam team = Research.team(player, true);
        BlockPos router = new BlockPos(3, 1, 3);
        BlockPos array = new BlockPos(6, 1, 3);
        helper.assertFalse(place(helper, player, ExpansionContent.ANTENNA_MOUNT.item().get(), router),
                "placed without research");
        helper.assertBlockNotPresent(ExpansionContent.ANTENNA_MOUNT.get(), router);
        helper.assertTrue(place(helper, player, ExpansionContent.BATCH_AMPLIFIER.item().get(), array),
                "batch amplifier refused");
        Research.addPoints(level.getServer(), team, 750);
        helper.assertValueEqual(Research.spendPoints(level.getServer(), team, MaxtechStorage.id("port_tier_2")),
                Research.Result.DONE, "port tier 2");
        helper.assertValueEqual(Research.spendPoints(level.getServer(), team, MaxtechStorage.id("antennas")),
                Research.Result.DONE, "antennas");
        helper.assertTrue(place(helper, player, ExpansionContent.ANTENNA_MOUNT.item().get(), router),
                "refused after research");
        helper.assertBlockPresent(ExpansionContent.ANTENNA_MOUNT.get(), router);
        helper.succeed();
    }

    /**
     * A core saved with a priority router and a burst buffer (kind indices 4 and 5, deleted after M7N) and a status
     * crown and light strip (12 and 13, removed on 2026-10-18) beside a batch amplifier loads with the amplifier alone:
     * the deleted members drop, the rest of the cluster stays.
     */
    private static void deletedModulesDropOnLoad(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
        BlockPos absolute = helper.absolutePos(core);
        long bank = absolute.east().asLong();
        CompoundTag saved = controller.saveWithoutMetadata(helper.getLevel().registryAccess());
        saved.putLongArray("members", new long[]{absolute.above().asLong(), bank, absolute.below().asLong(),
                absolute.west().asLong(), absolute.north().asLong()});
        saved.putIntArray("kinds", new int[]{4, ModuleKind.BATCH_AMPLIFIER.index(), 5, 12, 13});
        ControllerBlockEntity copy = new ControllerBlockEntity(controller.getBlockPos(), controller.getBlockState());
        copy.loadWithComponents(
                TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
        helper.assertValueEqual(List.copyOf(copy.members()), List.of(bank), "members after the load");
        helper.assertValueEqual(copy.expansions(ModuleKind.BATCH_AMPLIFIER), 1, "batch amplifiers");
        helper.succeed();
    }

    private static boolean place(GameTestHelper helper, ServerPlayer player, Item item, BlockPos pos) {
        // Clicking the top of a solid block places above it; an air block would be replaced itself.
        helper.setBlock(pos.below(), Blocks.STONE);
        BlockPos below = helper.absolutePos(pos.below());
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(below).add(0, 0.5, 0), Direction.UP, below, false);
        ItemStack stack = new ItemStack(item);
        return item instanceof BlockItem block
                && block.place(new BlockPlaceContext(helper.getLevel(), player, InteractionHand.MAIN_HAND, stack, hit))
                        .consumesAction();
    }
}
