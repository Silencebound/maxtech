package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.gui.transfer.CraftingGridMenus;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.storagechest.StorageChestBlockEntity;
import dev.silencebound.maxtech.storage.storagechest.StorageChestContent;
import dev.silencebound.maxtech.storage.terminal.TerminalAction;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Terminal comfort (M6e area D) on a storage chest, which serves the same menu as every terminal: the deposit key
 * stores only what is already stored and leaves the hotbar alone, the restock key tops the hotbar up, and a recipe
 * viewer's transfer pulls a recipe's ingredients into the inventory (one set, or as many as fit with max).
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class TerminalComfortGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_16x12x16");
    private static final String[] NAMES = {"terminal_comfort_deposit_stored", "terminal_comfort_restock_hotbar",
            "terminal_comfort_pull_recipe"};
    private static final BlockPos CHEST = new BlockPos(2, 2, 2);

    static {
        StorageTestEnvironment.init();
        register(NAMES[0], TerminalComfortGameTests::depositStored);
        register(NAMES[1], TerminalComfortGameTests::restockHotbar);
        register(NAMES[2], TerminalComfortGameTests::pullRecipe);
    }

    private TerminalComfortGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("terminal_comfort"), StorageTestEnvironment.plain());
        for (String name : NAMES) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, STRUCTURE, 100, 0, true)));
        }
    }

    private static StorageChestBlockEntity chest(GameTestHelper helper) {
        helper.setBlock(CHEST, StorageChestContent.STORAGE_CHEST.get());
        return helper.getBlockEntity(CHEST, StorageChestBlockEntity.class);
    }

    private static void insert(StorageChestBlockEntity chest, ItemStack stack) {
        try (Transaction transaction = Transaction.openRoot()) {
            ResourceHandlerUtil.insertStacking(chest.items(), ItemResource.of(stack), stack.getCount(), transaction);
            transaction.commit();
        }
    }

    private static long amount(StorageChestBlockEntity chest, StackKey key) {
        for (Int2LongMap.Entry entry : chest.contents().int2LongEntrySet()) {
            if (key.equals(chest.key(entry.getIntKey()))) {
                return entry.getLongValue();
            }
        }
        return 0;
    }

    private static ServerPlayer player(GameTestHelper helper) {
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "comfort_user");
        player.setPos(helper.absoluteVec(CHEST.above().getCenter()));
        return player;
    }

    private static TerminalMenu menu(ServerPlayer player, StorageChestBlockEntity chest) {
        return TerminalMenu.server(1, player.getInventory(), chest, update -> {
        });
    }

    private static boolean click(TerminalMenu menu, ServerPlayer player, TerminalAction.Kind kind) {
        return menu.clickMenuButton(player, new TerminalAction(kind, -1).buttonId());
    }

    private static int count(Inventory inventory, net.minecraft.world.item.Item item) {
        int count = 0;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** Main-inventory stacks of stored items go in; unstored items and the hotbar stay. */
    private static void depositStored(GameTestHelper helper) {
        StorageChestBlockEntity chest = chest(helper);
        insert(chest, new ItemStack(Items.COBBLESTONE, 1));
        ServerPlayer player = player(helper);
        Inventory inventory = player.getInventory();
        inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 10));
        inventory.setItem(9, new ItemStack(Items.COBBLESTONE, 20));
        inventory.setItem(10, new ItemStack(Items.DIRT, 5));
        TerminalMenu menu = menu(player, chest);
        helper.assertTrue(click(menu, player, TerminalAction.Kind.DEPOSIT_STORED), "deposit acted");
        helper.assertValueEqual(amount(chest, ItemKey.of(Items.COBBLESTONE)), 21L, "the stored kind went in");
        helper.assertValueEqual(amount(chest, ItemKey.of(Items.DIRT)), 0L, "an unstored kind stayed out");
        helper.assertValueEqual(inventory.getItem(0).getCount(), 10, "the hotbar is left alone");
        helper.assertTrue(inventory.getItem(9).isEmpty(), "the inventory stack left");
        helper.assertValueEqual(inventory.getItem(10).getCount(), 5, "the dirt stayed");
        helper.assertFalse(click(menu, player, TerminalAction.Kind.DEPOSIT_STORED), "nothing left to deposit");
        helper.succeed();
    }

    /** Hotbar stacks fill up to a stack from the chest; empty hotbar slots and full stacks stay as they are. */
    private static void restockHotbar(GameTestHelper helper) {
        StorageChestBlockEntity chest = chest(helper);
        insert(chest, new ItemStack(Items.TORCH, 100));
        insert(chest, new ItemStack(Items.ENDER_PEARL, 3));
        ServerPlayer player = player(helper);
        Inventory inventory = player.getInventory();
        inventory.setItem(0, new ItemStack(Items.TORCH, 10));
        inventory.setItem(1, new ItemStack(Items.ENDER_PEARL, 14));
        inventory.setItem(9, new ItemStack(Items.TORCH, 1));
        TerminalMenu menu = menu(player, chest);
        helper.assertTrue(click(menu, player, TerminalAction.Kind.RESTOCK_HOTBAR), "restock acted");
        helper.assertValueEqual(inventory.getItem(0).getCount(), 64, "torches topped up");
        helper.assertValueEqual(inventory.getItem(1).getCount(), 16, "pearls up to their stack size");
        helper.assertValueEqual(inventory.getItem(9).getCount(), 1, "outside the hotbar nothing changes");
        helper.assertTrue(inventory.getItem(2).isEmpty(), "an empty hotbar slot stays empty");
        helper.assertValueEqual(amount(chest, ItemKey.of(Items.TORCH)), 46L, "torches came from the chest");
        helper.assertValueEqual(amount(chest, ItemKey.of(Items.ENDER_PEARL)), 1L, "two pearls came out");
        helper.succeed();
    }

    private static List<List<ItemStack>> torchRecipe() {
        List<List<ItemStack>> cells = new ArrayList<>();
        for (int cell = 0; cell < 9; cell++) {
            cells.add(List.of());
        }
        // A plank cell that accepts two kinds; the chest has only the second.
        cells.set(1, List.of(new ItemStack(Items.COAL), new ItemStack(Items.CHARCOAL)));
        cells.set(4, List.of(new ItemStack(Items.STICK)));
        cells.set(7, List.of(new ItemStack(Items.STICK)));
        return cells;
    }

    /**
     * One set of a recipe arrives in the inventory, choosing a candidate the chest has; max pulls as many sets as the
     * scarcest ingredient allows; the transfer payload reaches the open terminal menu.
     */
    private static void pullRecipe(GameTestHelper helper) {
        StorageChestBlockEntity chest = chest(helper);
        insert(chest, new ItemStack(Items.CHARCOAL, 5));
        insert(chest, new ItemStack(Items.STICK, 64));
        ServerPlayer player = player(helper);
        Inventory inventory = player.getInventory();
        TerminalMenu menu = menu(player, chest);
        menu.receiveRecipe(player, torchRecipe(), false);
        helper.assertValueEqual(count(inventory, Items.CHARCOAL), 1, "one charcoal for one set");
        helper.assertValueEqual(count(inventory, Items.STICK), 2, "two sticks for one set");
        helper.assertValueEqual(count(inventory, Items.COAL), 0, "no coal invented");
        helper.assertValueEqual(amount(chest, ItemKey.of(Items.STICK)), 62L, "sticks left the chest");
        menu.receiveRecipe(player, torchRecipe(), true);
        helper.assertValueEqual(count(inventory, Items.CHARCOAL), 5, "max: every charcoal left");
        helper.assertValueEqual(count(inventory, Items.STICK), 10, "max: two sticks per charcoal");
        helper.assertValueEqual(amount(chest, ItemKey.of(Items.CHARCOAL)), 0L, "the chest gave its charcoal");
        player.containerMenu = menu;
        insert(chest, new ItemStack(Items.CHARCOAL, 1));
        helper.assertTrue(CraftingGridMenus
                .receive(new CraftingGridMenus.Transfer(menu.containerId, torchRecipe(), false), player),
                "the terminal takes a recipe viewer's transfer");
        helper.assertValueEqual(count(inventory, Items.CHARCOAL), 6, "through the payload too");
        helper.succeed();
    }
}
