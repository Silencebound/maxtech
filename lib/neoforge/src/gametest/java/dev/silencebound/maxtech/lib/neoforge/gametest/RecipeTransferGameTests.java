package dev.silencebound.maxtech.lib.neoforge.gametest;

import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.gui.transfer.CraftingGridMenu;
import dev.silencebound.maxtech.lib.gui.transfer.CraftingGridMenus;
import dev.silencebound.maxtech.lib.gui.transfer.IngredientSource;
import dev.silencebound.maxtech.lib.highlight.HighlightRequest;
import dev.silencebound.maxtech.lib.highlight.Highlights;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
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
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Recipe transfer into a crafting grid, server side (the JEI half needs a client): the grid is emptied into the
 * ingredient source, refilled from the source first and the player's inventory second, nothing is created or lost, and
 * the server refuses a wrong menu or a second transfer in one tick. Also checks that a locate request reaches the
 * highlight observer with the asked-for boxes.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class RecipeTransferGameTests {
    private static final String[] NAMES = {"recipe_transfer_fills_grid", "highlight_locate_request"};
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);
    private static final int CONTAINER_ID = 42;

    static {
        FUNCTIONS.register(NAMES[0], () -> RecipeTransferGameTests::fillsGrid);
        FUNCTIONS.register(NAMES[1], () -> RecipeTransferGameTests::locateRequest);
    }

    private RecipeTransferGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechLib.id("recipe_transfer"));
        for (String name : NAMES) {
            Identifier id = MaxtechLib.id(name);
            event.registerTest(id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                    new TestData<>(environment, LibGameTests.EMPTY_STRUCTURE, 20, 0, true)));
        }
    }

    private static void fillsGrid(GameTestHelper helper) {
        ServerPlayer player = SecurityGameTests.player(helper.getLevel(), "transfer_test");
        player.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 5));
        player.getInventory().setItem(1, new ItemStack(Items.STICK, 3));
        SimpleContainer network = new SimpleContainer(9);
        network.setItem(0, new ItemStack(Items.IRON_INGOT, 10));
        GridMenu menu = new GridMenu(network);
        menu.grid.setItem(0, new ItemStack(Items.COBBLESTONE, 2));
        player.containerMenu = menu;
        List<List<ItemStack>> cells = new ArrayList<>();
        cells.add(List.of(new ItemStack(Items.IRON_INGOT)));
        cells.add(List.of(new ItemStack(Items.OAK_PLANKS)));
        cells.add(List.of(new ItemStack(Items.DIAMOND), new ItemStack(Items.STICK)));
        cells.add(List.of(new ItemStack(Items.GOLD_INGOT)));
        for (int i = 4; i < CraftingGridMenu.GRID_SIZE; i++) {
            cells.add(List.of());
        }
        long before = total(player, menu, network);

        helper.assertTrue(CraftingGridMenus.receive(new CraftingGridMenus.Transfer(CONTAINER_ID, cells, false), player),
                "transfer refused");
        assertGrid(helper, menu, 0, Items.IRON_INGOT, 1);
        assertGrid(helper, menu, 1, Items.OAK_PLANKS, 1);
        assertGrid(helper, menu, 2, Items.STICK, 1);
        helper.assertTrue(menu.grid.getItem(3).isEmpty(), "nothing to place for gold");
        helper.assertValueEqual(network.countItem(Items.COBBLESTONE), 2, "the old grid went to the source");
        helper.assertValueEqual(network.countItem(Items.IRON_INGOT), 9, "iron came from the source");
        helper.assertValueEqual(total(player, menu, network), before, "items only moved");
        helper.assertFalse(CraftingGridMenus.receive(new CraftingGridMenus.Transfer(CONTAINER_ID, cells, true), player),
                "a second transfer in the same tick");
        helper.assertFalse(
                CraftingGridMenus.receive(new CraftingGridMenus.Transfer(CONTAINER_ID + 1, cells, true), player),
                "another container id");

        helper.runAfterDelay(1, () -> {
            helper.assertTrue(
                    CraftingGridMenus.receive(new CraftingGridMenus.Transfer(CONTAINER_ID, cells, true), player),
                    "max transfer refused");
            assertGrid(helper, menu, 0, Items.IRON_INGOT, 10);
            assertGrid(helper, menu, 1, Items.OAK_PLANKS, 5);
            assertGrid(helper, menu, 2, Items.STICK, 3);
            helper.assertValueEqual(total(player, menu, network), before, "items only moved");
            helper.succeed();
        });
    }

    private static void locateRequest(GameTestHelper helper) {
        ServerPlayer player = SecurityGameTests.player(helper.getLevel(), "highlight_test");
        List<HighlightRequest> seen = new ArrayList<>();
        Highlights.setObserver((target, request) -> {
            if (target.getUUID().equals(player.getUUID())) {
                seen.add(request);
            }
        });
        try {
            Highlights.locate(player, 60, new BlockPos(1, 2, 3), new BlockPos(4, 5, 6));
        } finally {
            Highlights.setObserver(null);
        }
        helper.assertValueEqual(seen.size(), 1, "requests seen");
        HighlightRequest request = seen.get(0);
        helper.assertValueEqual(request.channel(), Highlights.LOCATE, "channel");
        helper.assertTrue(request.throughWalls(), "locate draws through walls");
        helper.assertValueEqual(request.boxCount(), 2, "one box per position");
        helper.assertValueEqual(request.durationTicks(), 60, "duration");
        helper.succeed();
    }

    private static void assertGrid(GameTestHelper helper, GridMenu menu, int cell, Item item, int count) {
        ItemStack stack = menu.grid.getItem(cell);
        helper.assertTrue(stack.is(item) && stack.getCount() == count,
                "cell " + cell + " holds " + stack + ", expected " + count + " " + item);
    }

    /** Every item the test handles, wherever it is. */
    private static long total(ServerPlayer player, GridMenu menu, Container network) {
        long sum = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            sum += player.getInventory().getItem(i).getCount();
        }
        for (int i = 0; i < menu.grid.getContainerSize(); i++) {
            sum += menu.grid.getItem(i).getCount();
        }
        for (int i = 0; i < network.getContainerSize(); i++) {
            sum += network.getItem(i).getCount();
        }
        return sum;
    }

    /** A bare menu with a 3x3 grid over a container, as a terminal would have, sourcing from {@code network}. */
    private static final class GridMenu extends AbstractContainerMenu implements CraftingGridMenu {
        final SimpleContainer grid = new SimpleContainer(CraftingGridMenu.GRID_SIZE);
        private final SimpleContainer network;

        GridMenu(SimpleContainer network) {
            super(null, CONTAINER_ID);
            this.network = network;
            for (int i = 0; i < CraftingGridMenu.GRID_SIZE; i++) {
                addSlot(new Slot(grid, i, i % 3 * 18, i / 3 * 18));
            }
        }

        @Override
        public int gridSlot(int cell) {
            return cell;
        }

        @Override
        public IngredientSource ingredientSource(ServerPlayer player) {
            return new IngredientSource() {
                @Override
                public ItemStack extract(ItemStack like, int amount) {
                    for (int i = 0; i < network.getContainerSize(); i++) {
                        if (ItemStack.isSameItemSameComponents(network.getItem(i), like)) {
                            return network.removeItem(i, amount);
                        }
                    }
                    return ItemStack.EMPTY;
                }

                @Override
                public ItemStack insert(ItemStack stack) {
                    return network.addItem(stack);
                }
            };
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }
}
