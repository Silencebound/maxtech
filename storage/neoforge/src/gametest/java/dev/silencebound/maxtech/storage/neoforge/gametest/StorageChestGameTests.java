package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.sync.ClientSyncedList;
import dev.silencebound.maxtech.lib.sync.ListSort;
import dev.silencebound.maxtech.lib.sync.SyncedListIndex;
import dev.silencebound.maxtech.lib.sync.SyncedListUpdate;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialContent;
import dev.silencebound.maxtech.storage.storagechest.StorageChestBlockEntity;
import dev.silencebound.maxtech.storage.storagechest.StorageChestContent;
import dev.silencebound.maxtech.storage.terminal.StackKeyDescriber;
import dev.silencebound.maxtech.storage.terminal.TerminalAction;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The storage chest (M6a area 3): hoppers fill and empty it and an open terminal GUI follows every change; its list
 * searches and sorts by name, mod and tag; it keeps its contents in its item and across a save; and it crafts from a
 * chest and early materials.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class StorageChestGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_16x12x16");
    private static final String[] NAMES = {"storage_chest_hoppers_and_view", "storage_chest_search_and_sort",
            "storage_chest_keeps_contents", "storage_chest_recipe"};
    private static final BlockPos CHEST = new BlockPos(2, 2, 2);

    static {
        StorageTestEnvironment.init();
        register(NAMES[0], StorageChestGameTests::hoppersAndView);
        register(NAMES[1], StorageChestGameTests::searchAndSort);
        register(NAMES[2], StorageChestGameTests::keepsContents);
        register(NAMES[3], StorageChestGameTests::recipe);
    }

    private StorageChestGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("storage_chest"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, STRUCTURE, 400, 0, true)));
        }
    }

    private static StorageChestBlockEntity chest(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, StorageChestContent.STORAGE_CHEST.get());
        return helper.getBlockEntity(pos, StorageChestBlockEntity.class);
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
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "chest_user");
        player.setPos(helper.absoluteVec(CHEST.above().getCenter()));
        return player;
    }

    /**
     * A hopper above fills the chest while its GUI is open, and the menu's list follows; a stack taken in the GUI
     * leaves the chest; a hopper below empties what is left.
     */
    private static void hoppersAndView(GameTestHelper helper) {
        StorageChestBlockEntity chest = chest(helper, CHEST);
        ServerPlayer player = player(helper);
        List<SyncedListUpdate<StackKey>> sent = new ArrayList<>();
        TerminalMenu menu = TerminalMenu.server(1, player.getInventory(), chest, sent::add);
        menu.clickMenuButton(player, new TerminalAction(TerminalAction.Kind.REFRESH, -1).buttonId());
        menu.broadcastChanges();
        helper.setBlock(CHEST.above(), Blocks.HOPPER);
        HopperBlockEntity above = helper.getBlockEntity(CHEST.above(), HopperBlockEntity.class);
        above.setItem(0, new ItemStack(Items.COBBLESTONE, 10));
        helper.onEachTick(menu::broadcastChanges);
        StackKey cobble = ItemKey.of(Items.COBBLESTONE);
        helper.startSequence().thenWaitUntil(() -> helper.assertValueEqual(amount(chest, cobble), 10L, "filled"))
                .thenIdle(6).thenExecute(() -> {
                    ClientSyncedList<StackKey> client = replay(sent);
                    int id = idOf(helper, client, cobble);
                    helper.assertValueEqual(client.count(id), 10L, "the GUI saw the hopper's items");
                    helper.assertTrue(menu.clickMenuButton(player,
                            new TerminalAction(TerminalAction.Kind.TAKE_HALF, id).buttonId()), "take half");
                    helper.assertValueEqual(menu.getCarried().getCount(), 5, "half of ten");
                    helper.assertValueEqual(amount(chest, cobble), 5L, "five left in the chest");
                    helper.setBlock(CHEST.below(), Blocks.HOPPER);
                }).thenWaitUntil(() -> {
                    Container below = helper.getBlockEntity(CHEST.below(), HopperBlockEntity.class);
                    helper.assertValueEqual(below.countItem(Items.COBBLESTONE), 5, "the hopper below emptied it");
                }).thenExecute(() -> helper.assertValueEqual(chest.usedSlots(), 0, "empty")).thenSucceed();
    }

    private static int idOf(GameTestHelper helper, ClientSyncedList<StackKey> client, StackKey key) {
        for (int id : client.ids()) {
            if (key.equals(client.entry(id))) {
                return id;
            }
        }
        throw helper.assertionException(Component.literal(key + " not in the list"));
    }

    private static ClientSyncedList<StackKey> replay(List<SyncedListUpdate<StackKey>> sent) {
        ClientSyncedList<StackKey> client = new ClientSyncedList<>();
        for (SyncedListUpdate<StackKey> update : sent) {
            client.apply(update);
        }
        return client;
    }

    /**
     * The GUI's index over the chest's list: words match names, {@code @} the mod, {@code #} tags; sorting by count,
     * name and mod, each reversible.
     */
    private static void searchAndSort(GameTestHelper helper) {
        StorageChestBlockEntity chest = chest(helper, CHEST);
        insert(chest, new ItemStack(Items.COBBLESTONE, 64));
        insert(chest, new ItemStack(Items.COBBLESTONE, 36));
        insert(chest, new ItemStack(Items.DIAMOND, 5));
        insert(chest, new ItemStack(Items.IRON_INGOT, 30));
        insert(chest, new ItemStack(MaterialContent.LUMEN_QUARTZ.get(), 12));
        ItemStack named = new ItemStack(Items.STONE, 2);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Aardvark Stone"));
        insert(chest, named);
        ServerPlayer player = player(helper);
        List<SyncedListUpdate<StackKey>> sent = new ArrayList<>();
        TerminalMenu menu = TerminalMenu.server(1, player.getInventory(), chest, sent::add);
        menu.clickMenuButton(player, new TerminalAction(TerminalAction.Kind.REFRESH, -1).buttonId());
        menu.broadcastChanges();
        ClientSyncedList<StackKey> client = replay(sent);
        SyncedListIndex<StackKey> index = new SyncedListIndex<>(client, StackKeyDescriber.INSTANCE);
        helper.assertValueEqual(client.size(), 5, "kinds, the cobblestone of two slots merged");
        helper.assertValueEqual(client.count(idOf(helper, client, ItemKey.of(Items.COBBLESTONE))), 100L, "cobble");
        helper.assertValueEqual(names(client, index),
                List.of("Cobblestone", "Iron Ingot", "Lumen Quartz", "Diamond", "Aardvark Stone"),
                "by count, largest first");
        index.setSort(ListSort.COUNT, true);
        helper.assertValueEqual(names(client, index).get(0), "Aardvark Stone", "by count, smallest first");
        index.setSort(ListSort.NAME, false);
        helper.assertValueEqual(names(client, index),
                List.of("Aardvark Stone", "Cobblestone", "Diamond", "Iron Ingot", "Lumen Quartz"), "by name");
        index.setSort(ListSort.MOD, false);
        helper.assertValueEqual(names(client, index).get(0), "Lumen Quartz",
                "by mod, maxtech_storage before minecraft");
        index.setSearch("@maxtech_storage");
        helper.assertValueEqual(names(client, index), List.of("Lumen Quartz"), "@mod");
        index.setSearch("#c:ingots");
        helper.assertValueEqual(names(client, index), List.of("Iron Ingot"), "#tag");
        index.setSearch("aardvark");
        helper.assertValueEqual(names(client, index), List.of("Aardvark Stone"), "a custom name");
        index.setSearch("o t");
        helper.assertValueEqual(names(client, index).size(), 3, "every word must match: cobblestone, iron, stone");
        helper.succeed();
    }

    private static List<String> names(ClientSyncedList<StackKey> client, SyncedListIndex<StackKey> index) {
        List<String> names = new ArrayList<>();
        IntList view = index.view();
        for (int i = 0; i < view.size(); i++) {
            names.add(StackKeyDescriber.INSTANCE.name(Objects.requireNonNull(client.entry(view.getInt(i)))));
        }
        return names;
    }

    /**
     * A chest that holds items drops with them and a tooltip count; the contents come back in a placed chest, with
     * their totals. An empty chest drops plain.
     */
    private static void keepsContents(GameTestHelper helper) {
        StorageChestBlockEntity chest = chest(helper, CHEST);
        ItemStack plain = chest.toItemStack(helper.getLevel().registryAccess());
        helper.assertTrue(plain.get(DataComponents.BLOCK_ENTITY_DATA) == null, "an empty chest drops plain");
        insert(chest, new ItemStack(Items.COBBLESTONE, 100));
        insert(chest, new ItemStack(Items.DIAMOND, 3));
        List<ItemStack> drops = Block.getDrops(helper.getBlockState(CHEST), helper.getLevel(),
                helper.absolutePos(CHEST), chest, null, ItemStack.EMPTY);
        helper.assertValueEqual(drops.size(), 1, "drops");
        TypedEntityData<?> data = drops.get(0).get(DataComponents.BLOCK_ENTITY_DATA);
        if (data == null) {
            throw helper.assertionException(Component.literal("contents not kept on the item"));
        }
        helper.assertValueEqual(data.copyTagWithoutId().getIntOr("used_slots", 0), 3, "used slots for the tooltip");
        BlockPos other = CHEST.east(3);
        StorageChestBlockEntity restored = chest(helper, other);
        restored.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(),
                data.copyTagWithoutId()));
        helper.assertValueEqual(amount(restored, ItemKey.of(Items.COBBLESTONE)), 100L, "cobblestone restored");
        helper.assertValueEqual(amount(restored, ItemKey.of(Items.DIAMOND)), 3L, "diamonds restored");
        helper.assertValueEqual(restored.usedSlots(), 3, "slots restored");
        helper.succeed();
    }

    /** The chest crafts from a chest, lumen quartz, copper and display glass. */
    private static void recipe(GameTestHelper helper) {
        ItemStack quartz = new ItemStack(MaterialContent.LUMEN_QUARTZ.get());
        ItemStack glass = new ItemStack(MaterialContent.DISPLAY_GLASS.get());
        ItemStack copper = new ItemStack(Items.COPPER_INGOT);
        TerminalGameTests.crafts(helper,
                List.of(quartz, glass, quartz, copper, new ItemStack(Items.CHEST), copper, quartz, copper, quartz),
                StorageChestContent.STORAGE_CHEST.item().get());
        helper.succeed();
    }
}
