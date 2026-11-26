package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.controller.ControllerStatus;
import dev.silencebound.maxtech.storage.energy.EnergyCellBlock;
import dev.silencebound.maxtech.storage.energy.EnergyCellBlockEntity;
import dev.silencebound.maxtech.storage.energy.EnergyContent;
import dev.silencebound.maxtech.storage.machine.FuelGeneratorBlockEntity;
import dev.silencebound.maxtech.storage.machine.MachineContent;
import dev.silencebound.maxtech.storage.machine.MachineKind;
import dev.silencebound.maxtech.storage.network.NetworkState;
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
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Energy cells (M6c area C): they fill with a generator's surplus once the controller is full and drain once it is
 * empty, keep a network powered on their own, keep their FE in their item, take and give FE to neighbours within their
 * transfer limit, and need research for tiers 2 and 3.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class EnergyCellGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_16x12x16");
    private static final List<String> NAMES = new ArrayList<>();
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("energy_cell_fills_and_drains", EnergyCellGameTests::fillsAndDrains);
        register("energy_cell_powers_network_alone", EnergyCellGameTests::powersAlone);
        register("energy_cell_item_keeps_fe", EnergyCellGameTests::itemKeepsFe);
        register("energy_cell_external_fe", EnergyCellGameTests::externalFe);
        register("energy_cell_research_gate", EnergyCellGameTests::researchGate);
    }

    private EnergyCellGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("energy_cell"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 1200);
        }
    }

    static EnergyCellBlockEntity cell(GameTestHelper helper, BlockPos pos, Block block) {
        helper.setBlock(pos, block);
        return helper.getBlockEntity(pos, EnergyCellBlockEntity.class);
    }

    private static int charge(GameTestHelper helper, BlockPos pos) {
        return helper.getBlockState(pos).getValue(EnergyCellBlock.CHARGE);
    }

    /**
     * A fuel generator on a device of a network whose controller is full: its FE goes into the cell, not lost, and the
     * cell's gauge shows it. Then, without the generator, operations empty the controller first and take the rest from
     * the cell, FE for FE.
     */
    private static void fillsAndDrains(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        TestStorage.TestDevice device = NetworkGameTests.device(helper, NetworkGameTests.at(2));
        EnergyCellBlockEntity cell = cell(helper, NetworkGameTests.at(3), EnergyContent.TIER_1.get());
        BlockPos generatorPos = NetworkGameTests.at(2).above();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(cell.network() != null, "no network"))
                .thenExecute(() -> {
                    NetworkGameTests.power(helper, controller, 1_000_000);
                    helper.setBlock(generatorPos, MachineContent.block(MachineKind.FUEL_GENERATOR));
                    MachineGameTests.insert(helper.getBlockEntity(generatorPos, FuelGeneratorBlockEntity.class),
                            new ItemStack(Items.COAL));
                }).thenWaitUntil(() -> helper.assertTrue(cell.cellStore().stored() >= 4000, "cell not filling"))
                .thenExecute(() -> {
                    helper.setBlock(generatorPos, Blocks.AIR);
                    NetworkState network = NetworkGameTests.net(helper, device);
                    long controllerFe = NetworkGameTests.controllerEntity(helper, controller).buffer().stored();
                    helper.assertValueEqual(controllerFe,
                            NetworkGameTests.controllerEntity(helper, controller).buffer().capacity(),
                            "controller stays full");
                    helper.assertValueEqual(network.storedEnergy(), controllerFe + cell.cellStore().stored(),
                            "network energy");
                    helper.assertValueEqual(network.energyCells().stored(), cell.cellStore().stored(), "pool total");
                    helper.assertValueEqual(charge(helper, NetworkGameTests.at(3)), 1, "gauge");
                }).thenIdle(2).thenExecute(() -> {
                    NetworkState network = NetworkGameTests.net(helper, device);
                    long controllerFe = NetworkGameTests.controllerEntity(helper, controller).buffer().stored();
                    long cellFe = cell.cellStore().stored();
                    // An operation costs 4 FE: 3,000 of them cost more than the controller holds.
                    for (int i = 0; i < 3000; i++) {
                        helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 1), 1L, "insert " + i);
                    }
                    helper.assertValueEqual(NetworkGameTests.controllerEntity(helper, controller).buffer().stored(), 0L,
                            "controller emptied first");
                    helper.assertValueEqual(cell.cellStore().stored(), cellFe - (3000L * 4 - controllerFe),
                            "the rest from the cell");
                    helper.assertValueEqual(network.storedEnergy(), cell.cellStore().stored(), "network energy");
                    helper.assertTrue(network.isPowered(), "powered by the cell");
                }).thenSucceed();
    }

    /**
     * A network whose only FE is in a cell is powered and works, with an empty controller (which shows online) and
     * without one.
     */
    private static void powersAlone(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        TestStorage.TestDevice device = NetworkGameTests.device(helper, NetworkGameTests.at(2));
        EnergyCellBlockEntity cell = cell(helper, NetworkGameTests.at(3), EnergyContent.TIER_1.get());
        TestStorage.TestDevice adHoc = NetworkGameTests.device(helper, NetworkGameTests.at(6));
        EnergyCellBlockEntity adHocCell = cell(helper, NetworkGameTests.at(7), EnergyContent.TIER_1.get());
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(cell.network() != null && adHocCell.network() != null, "no network");
            helper.assertTrue(cell.network() == device.network(), "cell and device apart");
        }).thenExecute(() -> {
            helper.assertFalse(NetworkGameTests.net(helper, device).isPowered(), "powered without FE");
            cell.cellStore().receive(5000);
            adHocCell.cellStore().receive(5000);
        }).thenWaitUntil(() -> helper.assertValueEqual(NetworkGameTests.status(helper, controller),
                ControllerStatus.ONLINE, "controller look")).thenExecute(() -> {
                    for (TestStorage.TestDevice each : List.of(device, adHoc)) {
                        NetworkState network = NetworkGameTests.net(helper, each);
                        helper.assertTrue(network.isPowered(), "not powered");
                        helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 10), 10L, "insert");
                        helper.assertValueEqual(network.storedEnergy(), 5000L - 4, "paid by the cell");
                    }
                    helper.assertTrue(NetworkGameTests.net(helper, adHoc).isAdHoc(), "ad-hoc network");
                }).thenSucceed();
    }

    /**
     * The dropped cell carries its FE (and shows it), registers no ticker, and a cell placed from that item has it
     * again, in its network's total too.
     */
    private static void itemKeepsFe(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos first = NetworkGameTests.at(3);
        EnergyCellBlockEntity cell = cell(helper, first, EnergyContent.TIER_2.get());
        cell.cellStore().receive(345_678);
        helper.assertTrue(helper.getBlockState(first).getTicker(level, BlockEntityType.FURNACE) == null, "a ticker");
        helper.assertValueEqual(charge(helper, first), 2, "gauge at a third");
        List<ItemStack> drops = Block.getDrops(helper.getBlockState(first), level, helper.absolutePos(first), cell);
        helper.assertValueEqual(drops.size(), 1, "drops");
        ItemStack drop = drops.get(0);
        helper.assertTrue(drop.is(EnergyContent.TIER_2.item().get()), "dropped " + drop);
        helper.assertValueEqual(drop.getOrDefault(ModDataComponents.STORED_ENERGY.get(), -1L), 345_678L,
                "FE in the item");
        helper.assertTrue(drop.getItem().isBarVisible(drop), "no charge bar");
        List<Component> lines = new ArrayList<>();
        drop.getItem().appendHoverText(drop, Item.TooltipContext.EMPTY, TooltipDisplay.DEFAULT, lines::add,
                TooltipFlag.NORMAL);
        helper.assertTrue(lines.stream().anyMatch(line -> line.getContents() instanceof TranslatableContents t
                && t.getKey().equals(EnergyContent.STORED_KEY)), "no stored line in " + lines);
        ItemStack empty = new ItemStack(EnergyContent.TIER_2.item().get());
        helper.assertFalse(empty.getItem().isBarVisible(empty), "a bar on an empty cell");
        helper.setBlock(first, Blocks.AIR);
        TestStorage.TestDevice device = NetworkGameTests.device(helper, NetworkGameTests.at(6));
        BlockPos second = NetworkGameTests.at(7);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(device.network() != null, "no network"))
                .thenExecute(() -> {
                    EnergyCellBlockEntity placed = cell(helper, second, EnergyContent.TIER_2.get());
                    placed.applyComponentsFromItemStack(drop);
                    helper.assertValueEqual(placed.cellStore().stored(), 345_678L, "FE back");
                    helper.assertValueEqual(charge(helper, second), 2, "gauge after placing");
                }).thenWaitUntil(() -> helper.assertValueEqual(NetworkGameTests.net(helper, device).storedEnergy(),
                        345_678L, "network total"))
                .thenSucceed();
    }

    /**
     * Every side takes and gives FE, at most the tier's transfer limit per transfer; what a neighbour moves shows in
     * the network's total at once.
     */
    private static void externalFe(GameTestHelper helper) {
        TestStorage.TestDevice device = NetworkGameTests.device(helper, NetworkGameTests.at(2));
        EnergyCellBlockEntity cell = cell(helper, NetworkGameTests.at(3), EnergyContent.TIER_1.get());
        int limit = cell.tier().maxTransfer();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(device.network() != null, "no network"))
                .thenExecute(() -> {
                    NetworkState network = NetworkGameTests.net(helper, device);
                    for (Direction side : Direction.values()) {
                        EnergyHandler handler = helper.getLevel().getCapability(Capabilities.Energy.BLOCK,
                                helper.absolutePos(NetworkGameTests.at(3)), side);
                        if (handler == null) {
                            throw helper.assertionException(Component.literal("no FE on " + side));
                        }
                        try (Transaction transaction = Transaction.openRoot()) {
                            helper.assertValueEqual(handler.insert(limit * 3, transaction), limit, "in on " + side);
                            transaction.commit();
                        }
                    }
                    helper.assertValueEqual(cell.cellStore().stored(), limit * 6L, "stored");
                    helper.assertValueEqual(network.storedEnergy(), limit * 6L, "network total");
                    EnergyHandler handler = helper.getLevel().getCapability(Capabilities.Energy.BLOCK,
                            helper.absolutePos(NetworkGameTests.at(3)), Direction.UP);
                    if (handler == null) {
                        throw helper.assertionException(Component.literal("no FE"));
                    }
                    try (Transaction transaction = Transaction.openRoot()) {
                        helper.assertValueEqual(handler.extract(limit * 3, transaction), limit, "out");
                        transaction.commit();
                    }
                    helper.assertValueEqual(network.storedEnergy(), limit * 5L, "network total after");
                }).thenSucceed();
    }

    /**
     * A player whose team lacks Energy cells II cannot place a tier-2 cell; tier 1 needs nothing, and once the team
     * completes the node the tier-2 cell goes down.
     */
    private static void researchGate(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = NetworkGameTests.player(level, "energy_cells");
        ResearchTeam team = Research.team(player, true);
        BlockPos tier2 = NetworkGameTests.at(3);
        BlockPos tier1 = NetworkGameTests.at(5);
        helper.assertFalse(place(helper, player, EnergyContent.TIER_2.item().get(), tier2), "placed without research");
        helper.assertBlockNotPresent(EnergyContent.TIER_2.get(), tier2);
        helper.assertTrue(place(helper, player, EnergyContent.TIER_1.item().get(), tier1), "tier 1 refused");
        helper.assertBlockPresent(EnergyContent.TIER_1.get(), tier1);
        Research.addPoints(level.getServer(), team, 200);
        Research.spendPoints(level.getServer(), team, MaxtechStorage.id("energy_cells_2"));
        helper.assertTrue(place(helper, player, EnergyContent.TIER_2.item().get(), tier2), "refused after research");
        helper.assertBlockPresent(EnergyContent.TIER_2.get(), tier2);
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
