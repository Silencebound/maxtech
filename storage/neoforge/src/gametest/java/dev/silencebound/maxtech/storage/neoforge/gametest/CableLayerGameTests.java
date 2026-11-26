package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.cable.CableLook;
import dev.silencebound.maxtech.storage.cable.StorageCableConduitType;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.ModuleKind;
import dev.silencebound.maxtech.storage.network.NetworkService;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import dev.silencebound.maxtech.storage.tool.layer.CableLayerItem;
import dev.silencebound.maxtech.storage.tool.layer.LayerContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * M6f cable layer: lays a run along its path from the bound network's cables, in the picked kind and colour, paying FE
 * per cable; passes over cables already there, stops at a block in the way, at an empty charge and at the network's
 * last cable, taking out only what it laid; refuses runs that are too long, strangers and unbound layers; charges
 * through the item energy capability.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class CableLayerGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();
    private static final BlockPos CORE = new BlockPos(5, 1, 2);
    /** The runs go from here along X, then Z. */
    private static final BlockPos FROM = new BlockPos(10, 1, 8);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("cable_layer_lays_run", CableLayerGameTests::laysRun);
        register("cable_layer_stops", CableLayerGameTests::stops);
        register("cable_layer_refusals", CableLayerGameTests::refusals);
    }

    private CableLayerGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("cable_layer"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 200);
        }
    }

    /**
     * Charged through the item energy capability, picked to red tier-2 glass by sneak-using a cable, the layer lays the
     * seven positions from (10,1,8) to (14,1,10): X first, then Z, every one a red tier-2 glass cable joined to the
     * next. Seven cables leave the network and seven times the per-cable cost leaves the charge.
     */
    private static void laysRun(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ServerPlayer player = researched(helper, "layer_owner");
        StorageCableConduitType glass = CableContent.cable(CableLook.GLASS, 2).type().get();
        Item glassItem = glass.item().asItem();
        helper.startSequence().thenWaitUntil(() -> ready(helper, drive)).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, CORE);
            store(network, glassItem, 10);
            player.getInventory().setItem(0, bound(helper, new ItemStack(LayerContent.CABLE_LAYER.get())));
            player.getInventory().setSelectedSlot(0);
            EnergyHandler battery = ItemAccess.forPlayerSlot(player, 0).getCapability(Capabilities.Energy.ITEM);
            if (battery == null) {
                throw helper.assertionException(Component.literal("no item energy capability on the layer"));
            }
            int charged;
            try (Transaction transaction = Transaction.openRoot()) {
                charged = battery.insert(CableLayerItem.maxInput(), transaction);
                transaction.commit();
            }
            ItemStack layer = player.getInventory().getItem(0);
            helper.assertValueEqual(CableLayerItem.energy(layer), charged, "charged");
            helper.assertTrue(charged >= 7 * CableLayerItem.energyPerCable(), "charge pays for the run");

            CableLayerItem.pick(layer, glass, DyeColor.RED);
            helper.assertValueEqual(CableLayerItem.cable(layer).asItem(), glassItem, "picked kind");
            CableLayerItem.Result result = CableLayerItem.lay(player, layer, InteractionHand.MAIN_HAND,
                    helper.absolutePos(FROM), helper.absolutePos(new BlockPos(14, 1, 10)));
            helper.assertValueEqual(result.outcome(), CableLayerItem.Outcome.DONE, "outcome");
            helper.assertValueEqual(result.laid(), 7, "laid");
            for (int x = 10; x <= 14; x++) {
                assertCable(helper, new BlockPos(x, 1, 8), glass, DyeColor.RED);
            }
            assertCable(helper, new BlockPos(14, 1, 9), glass, DyeColor.RED);
            assertCable(helper, new BlockPos(14, 1, 10), glass, DyeColor.RED);
            helper.assertBlockPresent(Blocks.AIR, new BlockPos(10, 1, 9));
            ConduitBlockEntity corner = helper.getBlockEntity(new BlockPos(14, 1, 8), ConduitBlockEntity.class);
            helper.assertTrue(Integer.bitCount(corner.conduit().connections()) == 2, "corner joins both legs");
            helper.assertValueEqual(network.contents().amount(ItemKey.of(glassItem)), 3L, "cables left");
            helper.assertValueEqual(CableLayerItem.energy(layer), charged - 7 * CableLayerItem.energyPerCable(),
                    "charge spent");
        }).thenSucceed();
    }

    /**
     * Along (10..17,1,8): a cable already at x=11 is passed over and a stone block at x=15 stops the run, so x=10 and
     * x=12 to 14 get cables and four leave the network. With charge for two cables the next run lays two and stops on
     * power; with one cable in the network the last run lays one and stops on cable. Nothing more leaves the network
     * than was laid.
     */
    private static void stops(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ServerPlayer player = researched(helper, "layer_stops");
        StorageCableConduitType plain = CableContent.STORAGE_CABLE_TIER_1.type().get();
        Item plainItem = plain.item().asItem();
        ItemKey key = ItemKey.of(plainItem);
        helper.startSequence().thenWaitUntil(() -> ready(helper, drive)).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, CORE);
            store(network, plainItem, 10);
            ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(new BlockPos(11, 1, 8)), plain, null);
            helper.setBlock(new BlockPos(15, 1, 8), Blocks.STONE);
            int cost = CableLayerItem.energyPerCable();
            helper.assertTrue(cost > 0, "laying costs FE");
            ItemStack layer = charged(helper, 100 * cost);

            CableLayerItem.Result blocked = CableLayerItem.lay(player, layer, InteractionHand.MAIN_HAND,
                    helper.absolutePos(FROM), helper.absolutePos(new BlockPos(17, 1, 8)));
            helper.assertValueEqual(blocked.outcome(), CableLayerItem.Outcome.BLOCKED, "blocked outcome");
            helper.assertValueEqual(blocked.laid(), 4, "laid before the stone");
            helper.assertTrue(Objects.equals(blocked.stop(), helper.absolutePos(new BlockPos(15, 1, 8))), "stopped at");
            for (int x : new int[]{10, 12, 13, 14}) {
                assertCable(helper, new BlockPos(x, 1, 8), plain, null);
            }
            helper.assertBlockPresent(Blocks.STONE, new BlockPos(15, 1, 8));
            helper.assertBlockPresent(Blocks.AIR, new BlockPos(16, 1, 8));
            helper.assertValueEqual(network.contents().amount(key), 6L, "taken for the blocked run");
            helper.assertValueEqual(CableLayerItem.energy(layer), 96 * cost, "paid for four");

            layer.set(LayerContent.ENERGY.get(), 2 * cost + cost / 2);
            CableLayerItem.Result power = CableLayerItem.lay(player, layer, InteractionHand.MAIN_HAND,
                    helper.absolutePos(new BlockPos(10, 1, 12)), helper.absolutePos(new BlockPos(14, 1, 12)));
            helper.assertValueEqual(power.outcome(), CableLayerItem.Outcome.NO_POWER, "power outcome");
            helper.assertValueEqual(power.laid(), 2, "laid on the charge");
            helper.assertTrue(Objects.equals(power.stop(), helper.absolutePos(new BlockPos(12, 1, 12))),
                    "stopped on power at");
            helper.assertBlockPresent(Blocks.AIR, new BlockPos(12, 1, 12));
            helper.assertValueEqual(network.contents().amount(key), 4L, "taken on the charge");
            helper.assertValueEqual(CableLayerItem.energy(layer), cost / 2, "charge left");

            try (Transaction transaction = Transaction.openRoot()) {
                network.extract(key, 3, transaction);
                transaction.commit();
            }
            layer.set(LayerContent.ENERGY.get(), 100 * cost);
            CableLayerItem.Result empty = CableLayerItem.lay(player, layer, InteractionHand.MAIN_HAND,
                    helper.absolutePos(new BlockPos(10, 1, 14)), helper.absolutePos(new BlockPos(14, 1, 14)));
            helper.assertValueEqual(empty.outcome(), CableLayerItem.Outcome.NO_CABLE, "cable outcome");
            helper.assertValueEqual(empty.laid(), 1, "laid the last cable");
            helper.assertValueEqual(network.contents().amount(key), 0L, "network emptied");
            helper.assertValueEqual(CableLayerItem.energy(layer), 99 * cost, "paid for one");
        }).thenSucceed();
    }

    /**
     * A run one longer than {@code tools.layer.maxLength}, an unbound layer, a stranger without access on an owned
     * network and the owner out of wireless range lay nothing and take nothing.
     */
    private static void refusals(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ServerPlayer owner = researched(helper, "layer_refusal_owner");
        ServerPlayer stranger = researched(helper, "layer_stranger");
        NetworkGameTests.controllerEntity(helper, CORE).setOwner(OwnerRecord.of(owner));
        Item plainItem = CableContent.STORAGE_CABLE_TIER_1.item().get();
        helper.startSequence().thenWaitUntil(() -> ready(helper, drive)).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, CORE);
            store(network, plainItem, 10);
            ItemStack layer = charged(helper, 1_000_000);
            BlockPos from = helper.absolutePos(FROM);
            BlockPos to = helper.absolutePos(FROM.east(4));
            int max = CableLayerItem.maxLength();
            helper.assertValueEqual(
                    CableLayerItem.lay(owner, layer, InteractionHand.MAIN_HAND, from, from.east(max)).outcome(),
                    CableLayerItem.Outcome.TOO_LONG, "too long");
            helper.assertValueEqual(CableLayerItem
                    .lay(owner, new ItemStack(LayerContent.CABLE_LAYER.get()), InteractionHand.MAIN_HAND, from, to)
                    .outcome(), CableLayerItem.Outcome.NO_NETWORK, "unbound");
            helper.assertValueEqual(CableLayerItem.lay(stranger, layer, InteractionHand.MAIN_HAND, from, to).outcome(),
                    CableLayerItem.Outcome.NO_NETWORK, "stranger");
            owner.setPos(Vec3.atCenterOf(helper.absolutePos(CORE)).add(80, 0, 0));
            helper.assertValueEqual(CableLayerItem.lay(owner, layer, InteractionHand.MAIN_HAND, from, to).outcome(),
                    CableLayerItem.Outcome.NO_NETWORK, "out of range");
            helper.assertBlockPresent(Blocks.AIR, FROM);
            helper.assertValueEqual(network.contents().amount(ItemKey.of(plainItem)), 10L, "nothing taken");
        }).thenSucceed();
    }

    private static void assertCable(GameTestHelper helper, BlockPos pos, StorageCableConduitType type,
            @Nullable DyeColor colour) {
        ConduitBlockEntity conduit = helper.getBlockEntity(pos, ConduitBlockEntity.class);
        helper.assertValueEqual(conduit.type(), type, "cable at " + pos);
        helper.assertTrue(conduit.conduit().colour() == colour, "colour at " + pos);
    }

    private static void store(NetworkState network, Item item, int count) {
        try (Transaction transaction = Transaction.openRoot()) {
            network.insert(ItemKey.of(item), count, transaction);
            transaction.commit();
        }
    }

    private static ItemStack charged(GameTestHelper helper, int fe) {
        ItemStack layer = bound(helper, new ItemStack(LayerContent.CABLE_LAYER.get()));
        layer.set(LayerContent.ENERGY.get(), fe);
        return layer;
    }

    /** A core with a wireless access mount and a cabled test drive; returns the drive. */
    private static TestStorage.TestDevice network(GameTestHelper helper) {
        helper.setBlock(CORE, ControllerContent.CONTROLLER.get());
        helper.setBlock(CORE.east(), ExpansionContent.WIRELESS_ACCESS_MOUNT.get());
        ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(CORE.south()),
                CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
        return NetworkGameTests.device(helper, CORE.south(2));
    }

    // Networks are compared by identity.
    @SuppressWarnings("ReferenceEquality")
    private static void ready(GameTestHelper helper, TestStorage.TestDevice drive) {
        ControllerBlockEntity core = NetworkGameTests.controllerEntity(helper, CORE);
        helper.assertValueEqual(core.services(NetworkService.WIRELESS_ACCESS), 1, "mount active");
        helper.assertTrue(drive.network() == NetworkGameTests.state(helper, CORE), "drive joined");
        NetworkGameTests.power(helper, CORE, 100_000);
    }

    private static ItemStack bound(GameTestHelper helper, ItemStack tool) {
        tool.set(ModDataComponents.WIRELESS_LINK.get(),
                GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(CORE)));
        return tool;
    }

    /** A player at the core whose team completed Wireless. */
    private static ServerPlayer researched(GameTestHelper helper, String name) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = NetworkGameTests.player(level, name);
        ResearchTeam team = Research.team(player, true);
        Research.addPoints(level.getServer(), team, 2000);
        for (String node : List.of("port_tier_2", "antennas", "wireless")) {
            helper.assertValueEqual(Research.spendPoints(level.getServer(), team, MaxtechStorage.id(node)),
                    Research.Result.DONE, node);
        }
        player.setPos(Vec3.atCenterOf(helper.absolutePos(CORE)));
        return player;
    }
}
