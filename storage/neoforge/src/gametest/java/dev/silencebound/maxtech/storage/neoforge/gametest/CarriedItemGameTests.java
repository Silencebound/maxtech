package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.authlib.GameProfile;
import dev.silencebound.maxtech.lib.api.carried.CarriedItemMatcher;
import dev.silencebound.maxtech.lib.api.carried.CarriedItemSource;
import dev.silencebound.maxtech.lib.api.carried.CarriedItems;
import dev.silencebound.maxtech.lib.api.carried.CarriedSlot;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.cell.CellData;
import dev.silencebound.maxtech.storage.cell.PortableCellItem;
import dev.silencebound.maxtech.storage.cell.PortableCellSource;
import dev.silencebound.maxtech.storage.cell.StorageCellItem;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.ModuleKind;
import dev.silencebound.maxtech.storage.logic.remote.LogicRemoteItem;
import dev.silencebound.maxtech.storage.logic.remote.LogicRemoteMenu;
import dev.silencebound.maxtech.storage.logic.remote.RemoteContent;
import dev.silencebound.maxtech.storage.logic.remote.RemoteNotices;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import dev.silencebound.maxtech.storage.network.NetworkService;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import dev.silencebound.maxtech.storage.signal.SignalContent;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalItem;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalSource;
import dev.silencebound.maxtech.storage.tool.belt.BeltContent;
import dev.silencebound.maxtech.storage.tool.belt.BeltMarks;
import dev.silencebound.maxtech.storage.tool.belt.BeltRestock;
import dev.silencebound.maxtech.storage.tool.carried.CarriedGadget;
import dev.silencebound.maxtech.storage.tool.carried.CarriedOpen;
import dev.silencebound.maxtech.storage.tool.carried.GadgetPick;
import dev.silencebound.maxtech.storage.tool.tablet.CraftingTabletContent;
import dev.silencebound.maxtech.storage.tool.tablet.CraftingTabletItem;
import dev.silencebound.maxtech.storage.tool.tablet.TabletAccess;
import dev.silencebound.maxtech.storage.tool.uplink.PickupOverflow;
import dev.silencebound.maxtech.storage.tool.uplink.UplinkContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Lib's carried-item finder ({@link CarriedItems}) and Storage's gadgets on it: with a fake extra slot standing in for
 * an accessory mod's (Curios in play), the restock belt refills, the pocket uplink takes overflow, the logic remote
 * hears notices, and the wireless terminal, crafting tablet, logic remote and portable cell open and stay valid from
 * that slot, and stop once it is emptied. Without the extra slot the inventory lookups are unchanged. The fake source
 * is registered once and answers only for the players these tests put in it.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class CarriedItemGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();
    private static final BlockPos CORE = new BlockPos(5, 1, 2);
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    /** The fake extra slot's stack per player. */
    private static final Map<UUID, ItemStack> EXTRA = new ConcurrentHashMap<>();
    private static final FakeSlotSource FAKE = new FakeSlotSource();
    private static final Map<UUID, List<String>> PINGS = new ConcurrentHashMap<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("carried_lookup_without_extra_slots", CarriedItemGameTests::withoutExtraSlots);
        register("carried_belt_and_uplink_from_extra_slot", CarriedItemGameTests::beltAndUplink);
        register("carried_gadgets_open_from_extra_slot", CarriedItemGameTests::gadgetsOpen);
        register("carried_remote_hears_notice_in_extra_slot", CarriedItemGameTests::remoteNotice);
        register("carried_pick_one_key", CarriedItemGameTests::pickOneKey);
    }

    private CarriedItemGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        // The notice test swaps the remote notices' static sender, so these tests share one batch of their own.
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("carried"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 200);
        }
    }

    /** One extra slot per player, held in {@link #EXTRA}; stands in for an accessory mod's slots. */
    private static final class FakeSlotSource implements CarriedItemSource {
        @Override
        public <C> ItemStack find(Player player, CarriedItemMatcher<C> matcher, C context) {
            ItemStack stack = EXTRA.getOrDefault(player.getUUID(), ItemStack.EMPTY);
            return !stack.isEmpty() && matcher.matches(player, stack, context) ? stack : ItemStack.EMPTY;
        }

        @Override
        public <C> Optional<CarriedSlot> locate(Player player, CarriedItemMatcher<C> matcher, C context) {
            UUID id = player.getUUID();
            return find(player, matcher, context).isEmpty()
                    ? Optional.empty()
                    : Optional.of(() -> EXTRA.getOrDefault(id, ItemStack.EMPTY));
        }
    }

    /** Registers the fake source once; registering again would ask it twice. */
    private static synchronized void installFake() {
        CarriedItems.unregister(FAKE);
        CarriedItems.register(FAKE);
    }

    /**
     * With nothing in an extra slot the finder is the inventory walk: a belt in slot 8 is found, a gadget in the
     * selected hotbar slot or the offhand is located as that hand, one elsewhere by its slot, and a player carrying
     * none gets empty and null, and no gadget opens by key.
     */
    private static void withoutExtraSlots(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "carried_plain"));
        Inventory inventory = player.getInventory();
        helper.assertTrue(BeltRestock.beltOf(player).isEmpty(), "belt without one");
        helper.assertTrue(CarriedItems.locate(player, WirelessTerminalItem.class).isEmpty(), "terminal without one");
        helper.assertFalse(CarriedOpen.open(player, CarriedGadget.WIRELESS_TERMINAL), "opened without one");

        ItemStack belt = new ItemStack(BeltContent.RESTOCK_BELT.get());
        inventory.setItem(8, belt);
        helper.assertTrue(BeltRestock.beltOf(player) == belt, "belt in slot 8");

        ItemStack terminal = new ItemStack(SignalContent.WIRELESS_TERMINAL.get());
        inventory.setSelectedSlot(3);
        inventory.setItem(3, terminal);
        helper.assertValueEqual(CarriedItems.locate(player, WirelessTerminalItem.class),
                Optional.of(CarriedSlot.hand(player, InteractionHand.MAIN_HAND)), "selected slot");
        inventory.setItem(3, ItemStack.EMPTY);
        inventory.setItem(Inventory.SLOT_OFFHAND, terminal);
        helper.assertValueEqual(CarriedItems.locate(player, WirelessTerminalItem.class),
                Optional.of(CarriedSlot.hand(player, InteractionHand.OFF_HAND)), "offhand");
        inventory.setItem(Inventory.SLOT_OFFHAND, ItemStack.EMPTY);
        inventory.setItem(20, terminal);
        Optional<CarriedSlot> slot = CarriedItems.locate(player, WirelessTerminalItem.class);
        helper.assertValueEqual(slot, Optional.of(CarriedSlot.inventory(player, 20)), "main slot");
        helper.assertTrue(slot.isPresent() && slot.get().get() == terminal, "slot reads the stack");
        helper.succeed();
    }

    /**
     * A belt and an uplink only in the extra slot: the belt is found and refills slot 0 from the network, and with a
     * full inventory the uplink stores a picked-up stack. Emptied, neither is found any more.
     */
    private static void beltAndUplink(GameTestHelper helper) {
        installFake();
        TestStorage.TestDevice drive = network(helper);
        ServerPlayer player = researched(helper, "carried_belt");
        UUID id = player.getUUID();
        helper.startSequence().thenWaitUntil(() -> ready(helper, drive)).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, CORE);
            helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 100), 100L, "seeded");
            ItemStack belt = bound(helper, new ItemStack(BeltContent.RESTOCK_BELT.get()));
            belt.set(BeltContent.MARKS.get(), BeltMarks.EMPTY.with(0, ItemKey.of(Items.COBBLESTONE), 32, 36));
            EXTRA.put(id, belt);
            ItemStack found = BeltRestock.beltOf(player);
            helper.assertTrue(found == belt, "belt in the extra slot");
            helper.assertValueEqual(BeltRestock.restock(player, found), 32, "refilled");
            helper.assertValueEqual(player.getInventory().getItem(0).getCount(), 32, "slot 0");

            EXTRA.put(id, bound(helper, new ItemStack(UplinkContent.POCKET_UPLINK.get())));
            fill(player.getInventory());
            helper.assertValueEqual(PickupOverflow.overflow(player, item(helper, player, 10)), 10, "overflow stored");
            helper.assertValueEqual(network.contents().amount(ItemKey.of(Items.DIRT)), 10L, "dirt stored");

            EXTRA.remove(id);
            helper.assertTrue(BeltRestock.beltOf(player).isEmpty(), "belt after the slot emptied");
            helper.assertValueEqual(PickupOverflow.overflow(player, item(helper, player, 10)), 0,
                    "overflow after the slot emptied");
        }).thenExecute(() -> EXTRA.remove(id)).thenSucceed();
    }

    /**
     * The wireless terminal, crafting tablet, logic remote and portable cell in the extra slot: each is located there,
     * opens over that slot (the terminal's and tablet's charge is read from it, the cell mounts and gets its id there)
     * and stays valid until the slot is emptied. Opening by key finds the cell in the extra slot.
     */
    private static void gadgetsOpen(GameTestHelper helper) {
        installFake();
        TestStorage.TestDevice drive = network(helper);
        ServerPlayer player = researched(helper, "carried_gadgets");
        UUID id = player.getUUID();
        helper.startSequence().thenWaitUntil(() -> ready(helper, drive)).thenExecute(() -> {
            ItemStack terminal = charged(bound(helper, new ItemStack(SignalContent.WIRELESS_TERMINAL.get())));
            EXTRA.put(id, terminal);
            Optional<CarriedSlot> slot = CarriedItems.locate(player, WirelessTerminalItem.class);
            helper.assertTrue(slot.isPresent() && slot.get().get() == terminal, "terminal located");
            WirelessTerminalSource source = slot.isEmpty() ? null : WirelessTerminalItem.source(player, slot.get());
            helper.assertTrue(source != null && source.stillValid(player) && source.energy() == 10_000,
                    "terminal open from the slot");
            EXTRA.remove(id);
            helper.assertTrue(source != null && !source.stillValid(player) && source.energy() == 0,
                    "terminal after the slot emptied");

            ItemStack tablet = charged(bound(helper, new ItemStack(CraftingTabletContent.TABLET.get())));
            EXTRA.put(id, tablet);
            slot = CarriedItems.locate(player, CraftingTabletItem.class);
            TabletAccess access = slot.isEmpty() ? null : CraftingTabletItem.access(player, slot.get());
            helper.assertTrue(access != null && access.canView() && access.energy() == 10_000,
                    "tablet open from the slot");
            EXTRA.remove(id);
            helper.assertTrue(access != null && !access.canView() && access.energy() == 0,
                    "tablet after the slot emptied");

            ItemStack remote = charged(bound(helper, new ItemStack(RemoteContent.REMOTE.get())));
            EXTRA.put(id, remote);
            slot = CarriedItems.locate(player, LogicRemoteItem.class);
            helper.assertTrue(slot.isPresent(), "remote located");
            LogicRemoteMenu menu = LogicRemoteMenu.server(1, player, slot.orElse(CarriedSlot.EMPTY));
            helper.assertTrue(menu.stillValid(player), "remote open from the slot");
            EXTRA.remove(id);
            helper.assertFalse(menu.stillValid(player), "remote after the slot emptied");

            ItemStack cell = new ItemStack(CellContent.PORTABLE_STORAGE_CELL.get());
            EXTRA.put(id, cell);
            slot = CarriedItems.locate(player, PortableCellItem.class);
            PortableCellSource mounted = slot.isEmpty() ? null : PortableCellSource.open(player, slot.get());
            helper.assertTrue(mounted != null && mounted.stillValid(player), "cell open from the slot");
            CellData data = StorageCellItem.data(cell);
            helper.assertTrue(data != null && data.hasId(), "cell got its id in the slot");
            EXTRA.remove(id);
            helper.assertTrue(mounted != null && !mounted.stillValid(player), "cell after the slot emptied");
            if (mounted != null) {
                mounted.release();
            }

            // By key: a player without a connection cannot get a menu, so the cell only mounts and is let go again.
            FakePlayer keyed = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "carried_key"));
            ItemStack keyedCell = new ItemStack(CellContent.PORTABLE_STORAGE_CELL.get());
            EXTRA.put(keyed.getUUID(), keyedCell);
            helper.assertTrue(CarriedOpen.open(keyed, CarriedGadget.PORTABLE_CELL), "cell found by key");
            CellData keyedData = StorageCellItem.data(keyedCell);
            helper.assertTrue(keyedData != null && keyedData.hasId(), "cell mounted by key");
            EXTRA.remove(keyed.getUUID());
            helper.assertFalse(CarriedOpen.open(keyed, CarriedGadget.PORTABLE_CELL), "found after the slot emptied");
        }).thenExecute(() -> EXTRA.remove(id)).thenSucceed();
    }

    /**
     * A notice posted to the network reaches a holder whose logic remote is only in the extra slot, and is kept on that
     * remote.
     */
    @SuppressWarnings("removal") // makeMockServerPlayerInLevel is the way to put a player on the player list
    private static void remoteNotice(GameTestHelper helper) {
        installFake();
        RemoteNotices.setSender((player, ping) -> PINGS.computeIfAbsent(player.getUUID(), k -> new ArrayList<>())
                .add(ping.notice().text()));
        TestStorage.TestDevice drive = network(helper);
        // Powered, so no low power alarm (M7C item 16) pings the holder first.
        NetworkGameTests.power(helper, CORE, 1_000_000);
        ServerPlayer holder = researched(helper, helper.makeMockServerPlayerInLevel());
        UUID id = holder.getUUID();
        PINGS.remove(id);
        ItemStack remote = charged(bound(helper, new ItemStack(RemoteContent.REMOTE.get())));
        EXTRA.put(id, remote);
        helper.startSequence().thenWaitUntil(() -> ready(helper, drive)).thenExecute(() -> {
            holder.setPos(Vec3.atCenterOf(helper.absolutePos(CORE)));
            NetworkGameTests.state(helper, CORE).notices().post(new NetworkNotices.Notice(1, "", "belt empty", ""));
            helper.assertValueEqual(PINGS.getOrDefault(id, List.of()), List.of("belt empty"), "pings");
            helper.assertValueEqual(LogicRemoteItem.notices(remote).size(), 1, "kept on the remote in the slot");
        }).thenExecute(() -> {
            RemoteNotices.setSender(null);
            EXTRA.remove(id);
            helper.getLevel().getServer().getPlayerList().remove(holder);
        }).thenSucceed();
    }

    /** A core with a wireless access mount and a cabled test drive; returns the drive. */
    static TestStorage.TestDevice network(GameTestHelper helper) {
        helper.setBlock(CORE, ControllerContent.CONTROLLER.get());
        helper.setBlock(CORE.east(), ExpansionContent.WIRELESS_ACCESS_MOUNT.get());
        ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(CORE.south()),
                CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
        return NetworkGameTests.device(helper, CORE.south(2));
    }

    /**
     * The one gadget key (owner decision 2026-10-09): carrying nothing says so, one kind opens at once (the portable
     * cell mounts), and two kinds, one of them in an accessory slot, offer the picker with both in gadget order.
     */
    private static void pickOneKey(GameTestHelper helper) {
        installFake();
        FakePlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "carried_pick"));
        helper.assertValueEqual(CarriedOpen.pick(player), Optional.of(GadgetPick.Outcome.NONE), "nothing carried");
        ItemStack cell = new ItemStack(CellContent.PORTABLE_STORAGE_CELL.get());
        player.getInventory().setItem(20, cell);
        helper.assertValueEqual(CarriedOpen.pick(player), Optional.of(GadgetPick.Outcome.OPEN), "one carried");
        CellData data = StorageCellItem.data(cell);
        helper.assertTrue(data != null && data.hasId(), "the one gadget opened (the cell mounted)");
        EXTRA.put(player.getUUID(), new ItemStack(SignalContent.WIRELESS_TERMINAL.get()));
        try {
            helper.assertValueEqual(CarriedOpen.carried(player),
                    List.of(CarriedGadget.WIRELESS_TERMINAL, CarriedGadget.PORTABLE_CELL), "both kinds, in order");
            helper.assertValueEqual(CarriedOpen.pick(player), Optional.of(GadgetPick.Outcome.CHOOSE), "two carried");
        } finally {
            EXTRA.remove(player.getUUID());
        }
        helper.succeed();
    }

    @SuppressWarnings("ReferenceEquality") // networks are compared by identity
    static void ready(GameTestHelper helper, TestStorage.TestDevice drive) {
        ControllerBlockEntity core = NetworkGameTests.controllerEntity(helper, CORE);
        helper.assertValueEqual(core.services(NetworkService.WIRELESS_ACCESS), 1, "mount active");
        helper.assertTrue(drive.network() == NetworkGameTests.state(helper, CORE), "drive joined");
        NetworkGameTests.power(helper, CORE, 100_000);
    }

    static ItemStack bound(GameTestHelper helper, ItemStack tool) {
        tool.set(ModDataComponents.WIRELESS_LINK.get(),
                GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(CORE)));
        return tool;
    }

    static ItemStack charged(ItemStack tool) {
        tool.set(ModDataComponents.WIRELESS_ENERGY.get(), 10_000);
        return tool;
    }

    /** Fills every empty main inventory slot with a full stack of stone. */
    private static void fill(Inventory inventory) {
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                inventory.setItem(slot, new ItemStack(Items.STONE, 64));
            }
        }
    }

    /** {@code count} dirt on the ground at the player, ready to pick up (slot 0 holds cobblestone). */
    private static ItemEntity item(GameTestHelper helper, ServerPlayer player, int count) {
        ItemEntity entity = new ItemEntity(helper.getLevel(), player.getX(), player.getY(), player.getZ(),
                new ItemStack(Items.DIRT, count));
        entity.setNoPickUpDelay();
        helper.getLevel().addFreshEntity(entity);
        return entity;
    }

    static ServerPlayer researched(GameTestHelper helper, String name) {
        return researched(helper, NetworkGameTests.player(helper.getLevel(), name));
    }

    /** Gives {@code player}'s team the Wireless research and puts the player at the core. */
    private static <P extends ServerPlayer> P researched(GameTestHelper helper, P player) {
        ServerLevel level = helper.getLevel();
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
