package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.serialization.DynamicOps;
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
import dev.silencebound.maxtech.storage.tool.belt.BeltContent;
import dev.silencebound.maxtech.storage.tool.belt.BeltMarks;
import dev.silencebound.maxtech.storage.tool.belt.BeltMenu;
import dev.silencebound.maxtech.storage.tool.belt.BeltRestock;
import dev.silencebound.maxtech.storage.tool.belt.RestockBeltItem;
import dev.silencebound.maxtech.storage.tool.uplink.PickupOverflow;
import dev.silencebound.maxtech.storage.tool.uplink.PocketUplinkItem;
import dev.silencebound.maxtech.storage.tool.uplink.UplinkContent;
import dev.silencebound.maxtech.storage.tool.uplink.UplinkSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.TriState;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;

/**
 * M6e pocket tools: the pocket uplink stores picked-up items in its network only once the inventory is full, honours
 * its filter and on/off switch, and needs coverage and deposit access; the restock belt tops marked slots up from the
 * network, leaves slots holding other items alone, needs full access, and its menu marks, clears and counts without
 * moving items.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
// Networks are compared by identity.
@SuppressWarnings("ReferenceEquality")
public final class PocketToolGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();
    private static final BlockPos CORE = new BlockPos(5, 1, 2);
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("pocket_uplink_overflow", PocketToolGameTests::uplinkOverflow);
        register("pocket_uplink_access_and_range", PocketToolGameTests::uplinkAccessAndRange);
        register("pocket_belt_restock", PocketToolGameTests::beltRestock);
        register("pocket_belt_menu", PocketToolGameTests::beltMenu);
    }

    private PocketToolGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("pocket_tools"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 200);
        }
    }

    /**
     * Picked-up cobblestone stays out of the network while the inventory has room; once full, the uplink stores the
     * whole stack through the pickup event and removes the item entity. An item off the filter, or with overflow
     * switched off, stays on the ground; one on the filter goes in.
     */
    private static void uplinkOverflow(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ServerPlayer player = researched(helper, "uplink_owner");
        ItemStack uplink = bound(helper, new ItemStack(UplinkContent.POCKET_UPLINK.get()));
        helper.startSequence().thenWaitUntil(() -> ready(helper, drive)).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, CORE);
            Inventory inventory = player.getInventory();
            inventory.setItem(0, uplink);
            helper.assertValueEqual(PickupOverflow.overflow(player, item(helper, player, Items.COBBLESTONE, 10)), 0,
                    "stored with room left");
            fill(inventory);
            ItemEntity cobble = item(helper, player, Items.COBBLESTONE, 10);
            ItemEntityPickupEvent.Pre event = NeoForge.EVENT_BUS.post(new ItemEntityPickupEvent.Pre(player, cobble));
            helper.assertValueEqual(event.canPickup(), TriState.FALSE, "vanilla pickup after the overflow");
            helper.assertTrue(cobble.isRemoved(), "item entity kept");
            helper.assertValueEqual(network.contents().amount(COBBLE), 10L, "stored when full");

            // On the filter: only listed items overflow.
            PocketUplinkItem.configure(uplink, new ItemStack(Items.DIRT), false);
            helper.assertValueEqual(PocketUplinkItem.settings(uplink).filter(), List.of(Items.DIRT), "filter");
            ItemEntity off = item(helper, player, Items.COBBLESTONE, 5);
            helper.assertValueEqual(PickupOverflow.overflow(player, off), 0, "stored off the filter");
            helper.assertValueEqual(off.getItem().getCount(), 5, "entity off the filter");
            helper.assertValueEqual(PickupOverflow.overflow(player, item(helper, player, Items.DIRT, 7)), 7,
                    "stored on the filter");

            // Switched off: nothing overflows; sneak-use clears the filter.
            PocketUplinkItem.configure(uplink, ItemStack.EMPTY, false);
            helper.assertFalse(PocketUplinkItem.settings(uplink).active(), "switched off");
            helper.assertValueEqual(PickupOverflow.overflow(player, item(helper, player, Items.DIRT, 3)), 0,
                    "stored while off");
            PocketUplinkItem.configure(uplink, ItemStack.EMPTY, true);
            helper.assertValueEqual(PocketUplinkItem.settings(uplink).filter(), List.of(), "cleared");
            helper.assertValueEqual(network.contents().amount(ItemKey.of(Items.DIRT)), 7L, "dirt stored");
        }).thenSucceed();
    }

    /**
     * Overflow needs the bound network to reach: a stranger without access on an owned network keeps the item on the
     * ground, as does the owner out of wireless range or with an unbound uplink.
     */
    private static void uplinkAccessAndRange(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ServerPlayer owner = researched(helper, "uplink_range_owner");
        ServerPlayer stranger = researched(helper, "uplink_stranger");
        // Owned before the network forms, so the network takes the owner from its controller.
        NetworkGameTests.controllerEntity(helper, CORE).setOwner(OwnerRecord.of(owner));
        helper.startSequence().thenWaitUntil(() -> ready(helper, drive)).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, CORE);
            stranger.getInventory().setItem(0, bound(helper, new ItemStack(UplinkContent.POCKET_UPLINK.get())));
            fill(stranger.getInventory());
            helper.assertValueEqual(PickupOverflow.overflow(stranger, item(helper, stranger, Items.COBBLESTONE, 4)), 0,
                    "stranger stored");

            owner.getInventory().setItem(0, new ItemStack(UplinkContent.POCKET_UPLINK.get()));
            fill(owner.getInventory());
            helper.assertValueEqual(PickupOverflow.overflow(owner, item(helper, owner, Items.COBBLESTONE, 4)), 0,
                    "unbound stored");
            bound(helper, owner.getInventory().getItem(0));
            helper.assertValueEqual(PickupOverflow.overflow(owner, item(helper, owner, Items.COBBLESTONE, 4)), 4,
                    "owner in range");
            owner.setPos(Vec3.atCenterOf(helper.absolutePos(CORE)).add(80, 0, 0));
            helper.assertValueEqual(PickupOverflow.overflow(owner, item(helper, owner, Items.COBBLESTONE, 4)), 0,
                    "stored out of range");
            helper.assertValueEqual(network.contents().amount(COBBLE), 4L, "network contents");
        }).thenSucceed();
    }

    /**
     * A belt marking 32 cobblestone in slot 0, 16 torches in slot 1 and cobblestone in slot 2 (which holds dirt): one
     * pass fills slot 0 from the network, leaves slot 1 empty (no torches stored) and the dirt alone; after slot 0 is
     * used down to 5, the next pass tops it up to 32 again. A belt-carrying stranger without access takes nothing.
     */
    private static void beltRestock(GameTestHelper helper) {
        TestStorage.TestDevice drive = network(helper);
        ServerPlayer player = researched(helper, "belt_owner");
        ServerPlayer stranger = researched(helper, "belt_stranger");
        NetworkGameTests.controllerEntity(helper, CORE).setOwner(OwnerRecord.of(player));
        helper.startSequence().thenWaitUntil(() -> ready(helper, drive)).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, CORE);
            helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 100), 100L, "seeded");
            ItemStack belt = bound(helper, new ItemStack(BeltContent.RESTOCK_BELT.get()));
            belt.set(BeltContent.MARKS.get(), BeltMarks.EMPTY.with(0, ItemKey.of(Items.COBBLESTONE), 32, 36)
                    .with(1, ItemKey.of(Items.TORCH), 16, 36).with(2, ItemKey.of(Items.COBBLESTONE), 8, 36));
            Inventory inventory = player.getInventory();
            inventory.setItem(8, belt);
            inventory.setItem(2, new ItemStack(Items.DIRT, 3));
            helper.assertValueEqual(BeltRestock.restock(player, belt), 32, "first pass");
            helper.assertValueEqual(inventory.getItem(0).getCount(), 32, "slot 0");
            helper.assertTrue(inventory.getItem(1).isEmpty(), "slot 1 without torches stored");
            helper.assertTrue(inventory.getItem(2).is(Items.DIRT) && inventory.getItem(2).getCount() == 3,
                    "slot with another item");
            inventory.getItem(0).setCount(5);
            helper.assertValueEqual(BeltRestock.restock(player, belt), 27, "second pass");
            helper.assertValueEqual(inventory.getItem(0).getCount(), 32, "topped up");
            helper.assertValueEqual(BeltRestock.restock(player, belt), 0, "nothing missing");
            helper.assertValueEqual(network.contents().amount(COBBLE), 41L, "network after the passes");

            ItemStack strangerBelt = belt.copy();
            stranger.getInventory().setItem(8, strangerBelt);
            helper.assertValueEqual(BeltRestock.restock(stranger, strangerBelt), 0, "stranger took");
            helper.assertTrue(stranger.getInventory().getItem(0).isEmpty(), "stranger's slot");
        }).thenSucceed();
    }

    /**
     * The belt's menu: a left-click marks a slot at its count, the wheel buttons move the count between 1 and the stack
     * size, a right-click clears it, and no click moves an item; a full belt refuses another mark. The marks and the
     * uplink's settings survive a save and load.
     */
    private static void beltMenu(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = NetworkGameTests.player(level, "belt_menu");
        Inventory inventory = player.getInventory();
        inventory.setItem(0, new ItemStack(BeltContent.RESTOCK_BELT.get()));
        inventory.setSelectedSlot(0);
        inventory.setItem(9, new ItemStack(Items.COBBLESTONE, 20));
        BeltMenu menu = BeltMenu.server(1, inventory);
        int slot = menuSlot(menu, 9);
        menu.clicked(slot, 0, ContainerInput.PICKUP, player);
        helper.assertTrue(menu.getCarried().isEmpty() && inventory.getItem(9).getCount() == 20, "an item moved");
        BeltMarks.Mark mark = menu.marks().at(9);
        helper.assertTrue(mark != null && mark.count() == 20 && mark.key().equals(COBBLE), "marked at the count");
        helper.assertTrue(menu.clickMenuButton(player, BeltMenu.button(9, 16)), "+16");
        helper.assertTrue(menu.clickMenuButton(player, BeltMenu.button(9, 16)), "+16 again");
        helper.assertValueEqual(count(menu, 9), 52, "after +32");
        menu.clickMenuButton(player, BeltMenu.button(9, 16));
        helper.assertValueEqual(count(menu, 9), 64, "capped at the stack size");
        for (int i = 0; i < 5; i++) {
            menu.clickMenuButton(player, BeltMenu.button(9, -16));
        }
        helper.assertValueEqual(count(menu, 9), 1, "at least one");
        helper.assertFalse(menu.clickMenuButton(player, BeltMenu.button(10, 1)), "count on an unmarked slot");
        helper.assertFalse(menu.clickMenuButton(player, 36 * BeltMenu.OPS), "button past the slots");
        menu.clicked(menuSlot(menu, 0), 0, ContainerInput.PICKUP, player);
        helper.assertTrue(menu.marks().at(0) == null, "the belt marked itself");
        menu.clicked(slot, 1, ContainerInput.PICKUP, player);
        helper.assertTrue(menu.marks().at(9) == null, "right-click kept the mark");

        int max = RestockBeltItem.maxMarks();
        for (int i = 0; i < max; i++) {
            inventory.setItem(9 + i, new ItemStack(Items.STONE, 1 + i));
            menu.clicked(menuSlot(menu, 9 + i), 0, ContainerInput.PICKUP, player);
        }
        inventory.setItem(9 + max, new ItemStack(Items.STONE, 1));
        menu.clicked(menuSlot(menu, 9 + max), 0, ContainerInput.PICKUP, player);
        helper.assertValueEqual(menu.marks().marks().size(), max, "marks past the cap");
        helper.assertTrue(menu.stillValid(player), "valid with the belt in hand");
        inventory.setSelectedSlot(1);
        helper.assertFalse(menu.stillValid(player), "valid without the belt in hand");

        DynamicOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, level.registryAccess());
        BeltMarks marks = RestockBeltItem.marks(inventory.getItem(0));
        Tag saved = BeltMarks.CODEC.encodeStart(ops, marks).getOrThrow();
        helper.assertValueEqual(BeltMarks.CODEC.parse(ops, saved).getOrThrow(), marks, "marks round trip");
        UplinkSettings settings = UplinkSettings.DEFAULT.toggled(Items.DIRT, 9).withActive(false);
        Tag savedSettings = UplinkSettings.CODEC.encodeStart(ops, settings).getOrThrow();
        helper.assertValueEqual(UplinkSettings.CODEC.parse(ops, savedSettings).getOrThrow(), settings,
                "uplink settings round trip");
        helper.succeed();
    }

    private static int count(BeltMenu menu, int slot) {
        BeltMarks.Mark mark = menu.marks().at(slot);
        return mark == null ? -1 : mark.count();
    }

    private static int menuSlot(BeltMenu menu, int inventorySlot) {
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.getContainerSlot() == inventorySlot) {
                return i;
            }
        }
        throw new IllegalStateException("no menu slot for " + inventorySlot);
    }

    /** A core with a wireless access mount and a cabled test drive; returns the drive. */
    private static TestStorage.TestDevice network(GameTestHelper helper) {
        helper.setBlock(CORE, ControllerContent.CONTROLLER.get());
        helper.setBlock(CORE.east(), ExpansionContent.WIRELESS_ACCESS_MOUNT.get());
        ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(CORE.south()),
                CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
        return NetworkGameTests.device(helper, CORE.south(2));
    }

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

    /** Fills every empty main inventory slot with a full stack of stone. */
    private static void fill(Inventory inventory) {
        for (int slot = 0; slot < 36; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                inventory.setItem(slot, new ItemStack(Items.STONE, 64));
            }
        }
    }

    /** An item entity of {@code count} {@code item} at the player, ready to pick up. */
    private static ItemEntity item(GameTestHelper helper, ServerPlayer player, net.minecraft.world.item.Item item,
            int count) {
        ItemEntity entity = new ItemEntity(helper.getLevel(), player.getX(), player.getY(), player.getZ(),
                new ItemStack(item, count));
        entity.setNoPickUpDelay();
        helper.getLevel().addFreshEntity(entity);
        return entity;
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
