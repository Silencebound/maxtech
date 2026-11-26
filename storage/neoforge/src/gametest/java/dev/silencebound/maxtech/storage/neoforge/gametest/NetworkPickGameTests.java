package dev.silencebound.maxtech.storage.neoforge.gametest;

import net.minecraft.world.level.GameType;

import net.minecraft.server.level.ClientInformation;

import java.util.UUID;

import com.mojang.authlib.GameProfile;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.ModuleKind;
import dev.silencebound.maxtech.storage.network.NetworkService;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import dev.silencebound.maxtech.storage.signal.NetworkPick;
import dev.silencebound.maxtech.storage.signal.SignalContent;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalItem;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Pick block from the network (M7C item 7): middle-clicking a stone bricks block (the test mods give cobblestone
 * another item) with a bound, charged wireless terminal in the inventory puts a stack from the network into the hotbar
 * and selects it, and the terminal pays its FE; the next pick finds it carried. Refused, with nothing moved: no
 * terminal, an unbound one, a block out of reach, a stranger without access, and a full inventory. A controller with a
 * wireless access mount, a cable and a test storage device; the block sits two above the controller, the players stand
 * at the controller.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class NetworkPickGameTests {
    private static final String[] NAMES = {"network_pick_stack", "network_pick_refused", "network_pick_swap",
            "network_pick_ignored_and_rate"};
    private static final BlockPos CORE = new BlockPos(5, 1, 2);
    private static final BlockPos TARGET = CORE.above(2);
    private static final BlockPos NOT_STORED = TARGET.east();
    private static final StackKey BRICKS = ItemKey.of(Items.STONE_BRICKS);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register(NAMES[0], NetworkPickGameTests::stack);
        register(NAMES[1], NetworkPickGameTests::refused);
        register(NAMES[2], NetworkPickGameTests::swap);
        register(NAMES[3], NetworkPickGameTests::ignoredAndRate);
    }

    private NetworkPickGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("network_pick"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 200);
        }
    }

    private static TestStorage.TestDevice network(GameTestHelper helper) {
        helper.setBlock(CORE, ControllerContent.CONTROLLER.get());
        helper.setBlock(CORE.east(), ExpansionContent.WIRELESS_ACCESS_MOUNT.get());
        ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(CORE.south()),
                CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
        helper.setBlock(TARGET, Blocks.STONE_BRICKS);
        helper.setBlock(NOT_STORED, Blocks.BOOKSHELF);
        return NetworkGameTests.device(helper, CORE.south(2));
    }

    private static void ready(GameTestHelper helper, TestStorage.TestDevice drive) {
        ControllerBlockEntity core = NetworkGameTests.controllerEntity(helper, CORE);
        helper.assertValueEqual(core.services(NetworkService.WIRELESS_ACCESS), 1, "mount active");
        helper.assertTrue(drive.network() != null, "drive joined");
        NetworkGameTests.power(helper, CORE, 100_000);
    }

    private static NetworkState state(GameTestHelper helper) {
        return NetworkGameTests.state(helper, CORE);
    }

    private static ItemStack terminal(GameTestHelper helper, boolean bound) {
        ItemStack terminal = new ItemStack(SignalContent.WIRELESS_TERMINAL.get());
        if (bound) {
            terminal.set(ModDataComponents.WIRELESS_LINK.get(),
                    GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(CORE)));
        }
        terminal.set(ModDataComponents.WIRELESS_ENERGY.get(), 10_000);
        return terminal;
    }

    /** A player whose team has the Wireless research, standing at the controller. */
    private static ServerPlayer researched(GameTestHelper helper, String name) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = NetworkGameTests.player(level, name);
        ResearchTeam team = Research.team(player, true);
        Research.addPoints(level.getServer(), team, 2000);
        for (String node : List.of("port_tier_2", "antennas", "wireless")) {
            helper.assertValueEqual(Research.spendPoints(level.getServer(), team, MaxtechStorage.id(node)),
                    Research.Result.DONE, node);
        }
        // The test server may hand out creative players, whose pick is vanilla's.
        player.getAbilities().instabuild = false;
        player.setPos(Vec3.atCenterOf(helper.absolutePos(CORE)));
        return player;
    }

    /**
     * A stack of 64 lands in the first free hotbar slot, which is selected; the network keeps the rest and the terminal
     * paid FE. The next pick, after the rate limit, finds the stack carried and moves nothing.
     */
    private static void stack(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ServerPlayer player = researched(helper, "pick_user");
        Inventory inventory = player.getInventory();
        ItemStack terminal = terminal(helper, true);
        inventory.setItem(0, new ItemStack(Items.DIRT, 5));
        inventory.setItem(20, terminal);
        BlockPos target = helper.absolutePos(TARGET);
        helper.startSequence().thenWaitUntil(() -> ready(helper, drive)).thenExecute(() -> {
            helper.assertValueEqual(NetworkGameTests.insert(state(helper), BRICKS, 100), 100L, "seeded");
            helper.assertValueEqual(NetworkPick.pick(player, target), NetworkPick.Outcome.PICKED, "picked");
            helper.assertValueEqual(inventory.getItem(1).getCount(), 64, "a stack in the first free hotbar slot");
            helper.assertTrue(inventory.getItem(1).is(Items.STONE_BRICKS), "stone bricks");
            helper.assertValueEqual(inventory.getSelectedSlot(), 1, "selected");
            helper.assertValueEqual(inventory.getItem(0).getCount(), 5, "the dirt stays");
            helper.assertValueEqual(state(helper).contents().amount(BRICKS), 36L, "the rest stored");
            helper.assertTrue(WirelessTerminalItem.energy(terminal) < 10_000, "the terminal paid");
        }).thenIdle(5).thenExecute(() -> {
            helper.assertValueEqual(NetworkPick.pick(player, target), NetworkPick.Outcome.CARRIED, "carried now");
            helper.assertValueEqual(state(helper).contents().amount(BRICKS), 36L, "nothing more taken");
        }).thenSucceed();
    }

    /**
     * Nothing moves for a player without a terminal, with an unbound one, for a block beyond reach, for a stranger the
     * owner has not let in, or with every slot full (the hotbar's stack would have nowhere to go); no terminal pays FE
     * for a refused pick.
     */
    private static void refused(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ServerPlayer owner = researched(helper, "pick_owner");
        ServerPlayer bare = researched(helper, "pick_bare");
        ServerPlayer unbound = researched(helper, "pick_unbound");
        ServerPlayer stranger = researched(helper, "pick_stranger");
        ServerPlayer full = researched(helper, "pick_full");
        unbound.getInventory().setItem(20, terminal(helper, false));
        stranger.getInventory().setItem(20, terminal(helper, true));
        owner.getInventory().setItem(20, terminal(helper, true));
        full.getInventory().setItem(20, terminal(helper, true));
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            if (full.getInventory().getItem(slot).isEmpty()) {
                full.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
            }
        }
        BlockPos target = helper.absolutePos(TARGET);
        helper.startSequence().thenWaitUntil(() -> ready(helper, drive)).thenExecute(() -> {
            NetworkGameTests.insert(state(helper), BRICKS, 100);
            helper.assertValueEqual(NetworkPick.pick(bare, target), NetworkPick.Outcome.NO_TERMINAL, "no terminal");
            helper.assertValueEqual(NetworkPick.pick(unbound, target), NetworkPick.Outcome.NO_TERMINAL, "unbound");
            helper.assertValueEqual(NetworkPick.pick(owner, target.above(20)), NetworkPick.Outcome.IGNORED,
                    "beyond reach");
            helper.assertValueEqual(NetworkPick.pick(full, target), NetworkPick.Outcome.NO_ROOM, "full inventory");
            NetworkGameTests.controllerEntity(helper, CORE).setOwner(OwnerRecord.of(owner));
        }).thenWaitUntil(() -> helper.assertTrue(state(helper).owner().isOwner(owner.getUUID()), "owned"))
                .thenExecute(() -> {
                    helper.assertValueEqual(NetworkPick.pick(stranger, target), NetworkPick.Outcome.DENIED, "stranger");
                    helper.assertValueEqual(state(helper).contents().amount(BRICKS), 100L, "nothing taken");
                    for (ServerPlayer player : List.of(bare, unbound, stranger)) {
                        helper.assertValueEqual(player.getInventory().countItem(Items.STONE_BRICKS), 0,
                                player.getGameProfile().name() + " got nothing");
                    }
                    for (ServerPlayer player : List.of(unbound, stranger, owner, full)) {
                        helper.assertValueEqual(WirelessTerminalItem.energy(player.getInventory().getItem(20)), 10_000,
                                player.getGameProfile().name() + "'s terminal charge");
                    }
                }).thenSucceed();
    }

    /**
     * With every hotbar slot used, the pick takes the selected slot (as vanilla's does) and moves its stack to the
     * first free inventory slot: nothing is lost and the stack lands selected. A block whose item the network holds
     * none of moves nothing and costs no FE.
     */
    private static void swap(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ServerPlayer player = researched(helper, "pick_swap");
        ServerPlayer none = researched(helper, "pick_none");
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            inventory.setItem(slot, new ItemStack(Items.DIRT, 10 + slot));
        }
        inventory.setItem(20, terminal(helper, true));
        none.getInventory().setItem(20, terminal(helper, true));
        BlockPos target = helper.absolutePos(TARGET);
        helper.startSequence().thenWaitUntil(() -> ready(helper, drive)).thenExecute(() -> {
            NetworkGameTests.insert(state(helper), BRICKS, 100);
            helper.assertValueEqual(NetworkPick.pick(player, target), NetworkPick.Outcome.PICKED, "picked");
            helper.assertTrue(inventory.getItem(0).is(Items.STONE_BRICKS), "the selected slot holds the stack");
            helper.assertValueEqual(inventory.getItem(0).getCount(), 64, "a full stack");
            helper.assertValueEqual(inventory.getSelectedSlot(), 0, "selected");
            helper.assertTrue(inventory.getItem(9).is(Items.DIRT) && inventory.getItem(9).getCount() == 10,
                    "the slot's dirt moved to the first free slot");
            helper.assertValueEqual(inventory.countItem(Items.DIRT), 9 * 10 + 36, "no dirt lost");
            helper.assertValueEqual(state(helper).contents().amount(BRICKS), 36L, "the rest stored");

            helper.assertValueEqual(NetworkPick.pick(none, helper.absolutePos(NOT_STORED)),
                    NetworkPick.Outcome.NOT_STORED, "no bookshelves stored");
            helper.assertValueEqual(none.getInventory().countItem(Items.BOOKSHELF), 0, "nothing given");
            helper.assertValueEqual(WirelessTerminalItem.energy(none.getInventory().getItem(20)), 10_000, "no FE paid");
        }).thenSucceed();
    }

    /**
     * Creative and spectator players are left to vanilla; one player picks at most once per four server ticks.
     */
    private static void ignoredAndRate(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ServerPlayer creative = researched(helper, "pick_creative");
        creative.getAbilities().instabuild = true;
        ServerLevel level = helper.getLevel();
        ServerPlayer spectator = new ServerPlayer(level.getServer(), level,
                new GameProfile(UUID.randomUUID(), "pick_spectator"), ClientInformation.createDefault()) {
            @Override
            public GameType gameMode() {
                return GameType.SPECTATOR;
            }
        };
        spectator.setPos(Vec3.atCenterOf(helper.absolutePos(CORE)));
        ServerPlayer quick = researched(helper, "pick_quick");
        for (ServerPlayer player : List.of(creative, spectator, quick)) {
            player.getInventory().setItem(20, terminal(helper, true));
        }
        BlockPos target = helper.absolutePos(TARGET);
        helper.startSequence().thenWaitUntil(() -> ready(helper, drive)).thenExecute(() -> {
            NetworkGameTests.insert(state(helper), BRICKS, 200);
            helper.assertValueEqual(NetworkPick.pick(creative, target), NetworkPick.Outcome.IGNORED, "creative");
            helper.assertValueEqual(NetworkPick.pick(spectator, target), NetworkPick.Outcome.IGNORED, "spectator");
            helper.assertValueEqual(state(helper).contents().amount(BRICKS), 200L, "nothing taken");
            helper.assertValueEqual(NetworkPick.pick(quick, target), NetworkPick.Outcome.PICKED, "first pick");
            quick.getInventory().setItem(0, ItemStack.EMPTY);
            helper.assertValueEqual(NetworkPick.pick(quick, target), NetworkPick.Outcome.IGNORED, "too soon");
            helper.assertValueEqual(state(helper).contents().amount(BRICKS), 136L, "one stack taken");
        }).thenIdle(5).thenExecute(() -> {
            helper.assertValueEqual(NetworkPick.pick(quick, target), NetworkPick.Outcome.PICKED, "after the wait");
            helper.assertValueEqual(state(helper).contents().amount(BRICKS), 72L, "a second stack");
        }).thenSucceed();
    }
}
