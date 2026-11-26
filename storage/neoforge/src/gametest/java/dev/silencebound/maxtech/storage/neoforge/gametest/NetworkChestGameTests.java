package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import dev.silencebound.maxtech.storage.world.NetworkChestBlockEntity;
import dev.silencebound.maxtech.storage.world.NetworkChestMenu;
import dev.silencebound.maxtech.storage.world.NetworkChestPart;
import dev.silencebound.maxtech.storage.world.NetworkChestView;
import dev.silencebound.maxtech.storage.world.WorldContent;
import dev.silencebound.maxtech.storage.world.WorldFilter;
import dev.silencebound.maxtech.storage.world.WorldMenu;
import dev.silencebound.maxtech.storage.world.WorldSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The network chest (M6c area E): a filtered view of the network for hoppers, pipes and players, where every external
 * call is one network operation and a stale view moves nothing that is not there. Layouts run along x at y = 1, z = 1:
 * controller, cable, test drive, then the chest block (or a cable with the chest part).
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class NetworkChestGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_16x12x16");
    private static final String[] NAMES = {"world_chest_view_and_filter", "world_chest_hoppers_conserve",
            "world_chest_part_round_trip", "world_chest_player_menus", "world_chest_access_and_research",
            "world_chest_off_thread_moves_nothing"};
    private static final StackKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final StackKey GOLD = ItemKey.of(Items.GOLD_INGOT);
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final TagKey<Item> INGOTS = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath("c", "ingots"));
    private static final long POWER = 10_000;
    /** FE of a one-item operation at the default config: 4 per operation, nothing per item. */
    private static final long ONE_ITEM_FE = 4;
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register(NAMES[0], NetworkChestGameTests::viewAndFilter);
        register(NAMES[1], NetworkChestGameTests::hoppersConserve);
        register(NAMES[2], NetworkChestGameTests::partRoundTrip);
        register(NAMES[3], NetworkChestGameTests::playerMenus);
        register(NAMES[4], NetworkChestGameTests::accessAndResearch);
        register(NAMES[5], NetworkChestGameTests::offThreadMovesNothing);
    }

    private NetworkChestGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("world_chest"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, STRUCTURE, 400, 0, true)));
        }
    }

    /** A powered controller, a cable and a test drive at x = 1 to 3; returns the controller's position. */
    private static BlockPos network(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, POWER);
        NetworkGameTests.cables(helper, 2, 2, CableContent.STORAGE_CABLE_TIER_1.type().get());
        NetworkGameTests.device(helper, NetworkGameTests.at(3));
        return controller;
    }

    /** The network chest block at x = 4, touching the drive. */
    private static NetworkChestBlockEntity chest(GameTestHelper helper) {
        BlockPos pos = NetworkGameTests.at(4);
        helper.setBlock(pos, WorldContent.NETWORK_CHEST.get());
        return helper.getBlockEntity(pos, NetworkChestBlockEntity.class);
    }

    private static long amount(GameTestHelper helper, StackKey key) {
        return NetworkGameTests.state(helper, NetworkGameTests.at(1)).contents().amount(key);
    }

    private static void slot(GameTestHelper helper, ResourceHandler<ItemResource> handler, int index, Item item,
            long amount) {
        ItemResource resource = handler.getResource(index);
        if (new ItemStack(item).isEmpty()) {
            helper.assertTrue(resource.isEmpty(), "slot " + index + " should be empty, shows " + resource);
            return;
        }
        helper.assertTrue(resource.is(item), "slot " + index + " shows " + resource + ", not " + item);
        helper.assertValueEqual(handler.getAmountAsLong(index), amount, "slot " + index + "'s amount");
    }

    private static int tagCycles(WorldFilter filter, int slot, TagKey<Item> wanted) {
        for (int i = 0; i < 64; i++) {
            if (wanted.equals(filter.tag(slot))) {
                return i;
            }
            filter.cycleTag(slot);
        }
        return -1;
    }

    private static long insert(ResourceHandler<ItemResource> handler, ItemResource resource, int amount) {
        try (Transaction transaction = Transaction.openRoot()) {
            long moved = handler.insert(resource, amount, transaction);
            transaction.commit();
            return moved;
        }
    }

    private static long extract(ResourceHandler<ItemResource> handler, int index, ItemResource resource, int amount) {
        try (Transaction transaction = Transaction.openRoot()) {
            long moved = handler.extract(index, resource, amount, transaction);
            transaction.commit();
            return moved;
        }
    }

    /**
     * A blank filter shows nothing and takes anything; an exact entry shows its key over stacks, a tag entry adds what
     * the tag matches and nothing else; a filter refuses what it does not name; the mode sets 27 or 54 slots; a slot
     * whose items left the network behind the view's back moves nothing, and an extract checks the slot's item.
     */
    private static void viewAndFilter(GameTestHelper helper) {
        network(helper);
        NetworkChestBlockEntity chest = chest(helper);
        NetworkChestView view = chest.view();
        ResourceHandler<ItemResource> handler = view.handler();
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.state(helper, NetworkGameTests.at(4)))
                .thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, NetworkGameTests.at(1));
                    NetworkGameTests.insert(network, IRON, 100);
                    NetworkGameTests.insert(network, GOLD, 10);
                    NetworkGameTests.insert(network, COBBLE, 5);
                    helper.assertValueEqual(handler.size(), 27, "slots in 3-row mode");
                    for (int i = 0; i < handler.size(); i++) {
                        slot(helper, handler, i, Items.AIR, 0);
                    }
                    helper.assertValueEqual(insert(handler, ItemResource.of(Items.COBBLESTONE), 3), 3L,
                            "a blank filter takes anything");
                    view.filter().setItem(0, new ItemStack(Items.IRON_INGOT));
                }).thenIdle(1).thenExecute(() -> {
                    slot(helper, handler, 0, Items.IRON_INGOT, 64);
                    slot(helper, handler, 1, Items.IRON_INGOT, 36);
                    slot(helper, handler, 2, Items.AIR, 0);
                    view.filter().setItem(1, new ItemStack(Items.GOLD_INGOT));
                    helper.assertTrue(tagCycles(view.filter(), 1, INGOTS) >= 0, "gold names no #c:ingots");
                }).thenIdle(1).thenExecute(() -> {
                    slot(helper, handler, 0, Items.IRON_INGOT, 64);
                    slot(helper, handler, 1, Items.IRON_INGOT, 36);
                    slot(helper, handler, 2, Items.GOLD_INGOT, 10);
                    slot(helper, handler, 3, Items.AIR, 0);
                    helper.assertValueEqual(insert(handler, ItemResource.of(Items.COBBLESTONE), 5), 0L,
                            "the filter refuses cobblestone");
                    helper.assertValueEqual(insert(handler, ItemResource.of(Items.IRON_INGOT), 5), 5L,
                            "the filter takes iron");
                    helper.assertValueEqual(amount(helper, IRON), 105L, "iron stored through the chest");
                    view.setMode(1);
                }).thenIdle(4).thenExecute(() -> {
                    helper.assertValueEqual(handler.size(), 54, "slots in 6-row mode");
                    slot(helper, handler, 0, Items.IRON_INGOT, 64);
                    slot(helper, handler, 1, Items.IRON_INGOT, 41);
                    // The view was read this tick, so it stays as it is while the network loses its iron.
                    NetworkState network = NetworkGameTests.state(helper, NetworkGameTests.at(1));
                    try (Transaction transaction = Transaction.openRoot()) {
                        network.extract(IRON, 105, transaction);
                        transaction.commit();
                    }
                    slot(helper, handler, 0, Items.IRON_INGOT, 64);
                    helper.assertValueEqual(extract(handler, 0, ItemResource.of(Items.IRON_INGOT), 64), 0L,
                            "a stale slot moved iron");
                    helper.assertValueEqual(amount(helper, IRON), 0L, "iron after the stale extract");
                    helper.assertValueEqual(extract(handler, 2, ItemResource.of(Items.IRON_INGOT), 5), 0L,
                            "slot 2 holds gold, not iron");
                    helper.assertValueEqual(extract(handler, 2, ItemResource.of(Items.GOLD_INGOT), 4), 4L,
                            "gold taken through the chest");
                    helper.assertValueEqual(amount(helper, GOLD), 6L, "gold left");
                    // A view with a tag entry rescans the network at most every 4 ticks.
                }).thenIdle(4).thenExecute(() -> {
                    slot(helper, handler, 0, Items.GOLD_INGOT, 6);
                    slot(helper, handler, 1, Items.AIR, 0);
                }).thenSucceed();
    }

    /**
     * A call from another thread (another mod's worker) sees an empty chest and moves nothing, while the same calls on
     * the server thread work.
     */
    private static void offThreadMovesNothing(GameTestHelper helper) {
        network(helper);
        NetworkChestBlockEntity chest = chest(helper);
        ResourceHandler<ItemResource> handler = chest.view().handler();
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.state(helper, NetworkGameTests.at(4)))
                .thenExecute(() -> {
                    NetworkGameTests.insert(NetworkGameTests.state(helper, NetworkGameTests.at(1)), IRON, 100);
                    chest.view().filter().setItem(0, new ItemStack(Items.IRON_INGOT));
                }).thenIdle(1).thenExecute(() -> {
                    long[] seen = new long[4];
                    Thread worker = new Thread(() -> {
                        seen[0] = handler.getResource(0).isEmpty() ? 0 : 1;
                        seen[1] = handler.getAmountAsLong(0);
                        seen[2] = extract(handler, 0, ItemResource.of(Items.IRON_INGOT), 10);
                        seen[3] = insert(handler, ItemResource.of(Items.IRON_INGOT), 10);
                    }, "world_chest_off_thread");
                    worker.start();
                    try {
                        worker.join(5_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw helper.assertionException(Component.literal("interrupted"));
                    }
                    helper.assertValueEqual(seen[0] + seen[1] + seen[2] + seen[3], 0L,
                            "what another thread saw or moved");
                    helper.assertValueEqual(amount(helper, IRON), 100L, "iron after the off-thread calls");
                    helper.assertValueEqual(extract(handler, 0, ItemResource.of(Items.IRON_INGOT), 10), 10L,
                            "the server thread takes iron");
                }).thenSucceed();
    }

    /**
     * A hopper above stores iron through the chest and keeps the cobblestone the filter refuses; a hopper below takes
     * iron out. Nothing is created or lost, and each moved item cost one operation's FE.
     */
    private static void hoppersConserve(GameTestHelper helper) {
        network(helper);
        NetworkChestBlockEntity chest = chest(helper);
        long[] before = new long[1];
        BlockPos above = NetworkGameTests.at(4).above();
        BlockPos below = NetworkGameTests.at(4).below();
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.state(helper, NetworkGameTests.at(4)))
                .thenExecute(() -> {
                    NetworkState network = NetworkGameTests.state(helper, NetworkGameTests.at(1));
                    NetworkGameTests.insert(network, IRON, 100);
                    chest.view().filter().setItem(0, new ItemStack(Items.IRON_INGOT));
                    before[0] = network.storedEnergy();
                    helper.setBlock(above, Blocks.HOPPER);
                    HopperBlockEntity top = helper.getBlockEntity(above, HopperBlockEntity.class);
                    top.setItem(0, new ItemStack(Items.IRON_INGOT, 10));
                    top.setItem(1, new ItemStack(Items.COBBLESTONE, 5));
                    helper.setBlock(below, Blocks.HOPPER);
                }).thenIdle(200).thenExecute(() -> {
                    HopperBlockEntity top = helper.getBlockEntity(above, HopperBlockEntity.class);
                    HopperBlockEntity bottom = helper.getBlockEntity(below, HopperBlockEntity.class);
                    long topIron = count(top, Items.IRON_INGOT);
                    long bottomIron = count(bottom, Items.IRON_INGOT);
                    long stored = amount(helper, IRON);
                    helper.assertValueEqual(count(top, Items.COBBLESTONE), 5L, "cobblestone the filter refused");
                    helper.assertValueEqual(topIron, 0L, "iron left in the upper hopper");
                    helper.assertTrue(bottomIron > 0, "the lower hopper took nothing");
                    helper.assertValueEqual(stored + topIron + bottomIron, 110L, "iron in the network and hoppers");
                    long moved = (10 - topIron) + bottomIron;
                    NetworkState network = NetworkGameTests.state(helper, NetworkGameTests.at(1));
                    helper.assertValueEqual(before[0] - network.storedEnergy(), moved * ONE_ITEM_FE,
                            "FE for " + moved + " one-item operations");
                }).thenSucceed();
    }

    private static long count(HopperBlockEntity hopper, Item item) {
        long total = 0;
        for (int i = 0; i < hopper.getContainerSize(); i++) {
            ItemStack stack = hopper.getItem(i);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static ItemStack craft(GameTestHelper helper, ItemStack input) {
        CraftingInput grid = CraftingInput.of(3, 3, PartGameTests.grid(input.copy()));
        return CrafterBlock.getPotentialResults(helper.getLevel(), grid).map(recipe -> recipe.value().assemble(grid))
                .orElseThrow(() -> helper.assertionException(Component.literal("No recipe for " + input)));
    }

    /**
     * The part on a cable's lower face serves the hopper beneath it; its settings (filter, a tag, the mode) go into its
     * item, through the conversion recipe into the block, onto the placed block and into the block's drop, whose
     * tooltip names them; a fresh chest's item carries nothing.
     */
    private static void partRoundTrip(GameTestHelper helper) {
        network(helper);
        NetworkGameTests.cables(helper, 4, 4, CableContent.STORAGE_CABLE_TIER_1.type().get());
        Player placer = helper.makeMockPlayer(GameType.SURVIVAL);
        ItemStack partItem = new ItemStack(WorldContent.NETWORK_CHEST_PART_ITEM.get());
        BlockPos below = NetworkGameTests.at(4).below();
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.state(helper, NetworkGameTests.at(4)))
                .thenExecute(() -> {
                    helper.assertTrue(
                            PartGameTests.usePart(helper, placer, 4, Direction.DOWN, partItem).consumesAction(),
                            "the chest part was not placed");
                    NetworkChestPart part = chestPart(helper);
                    // A mock player's team lacks the research; an unowned device works.
                    part.setOwner(OwnerRecord.UNOWNED);
                    NetworkGameTests.insert(NetworkGameTests.state(helper, NetworkGameTests.at(1)), IRON, 20);
                    part.view().filter().setItem(0, new ItemStack(Items.IRON_INGOT));
                    part.view().filter().setItem(4, new ItemStack(Items.GOLD_INGOT));
                    tagCycles(part.view().filter(), 4, INGOTS);
                    part.view().setMode(1);
                    helper.setBlock(below, Blocks.HOPPER);
                }).thenIdle(40).thenExecute(() -> {
                    HopperBlockEntity hopper = helper.getBlockEntity(below, HopperBlockEntity.class);
                    long taken = count(hopper, Items.IRON_INGOT);
                    helper.assertTrue(taken > 0, "the hopper under the part took nothing");
                    helper.assertValueEqual(amount(helper, IRON) + taken, 20L, "iron in the network and hopper");
                    CablePart removed = PartGameTests.parts(helper, 4).remove(Direction.DOWN);
                    ItemStack takenOff = removed == null ? ItemStack.EMPTY : removed.toItem();
                    assertSettings(helper, takenOff, "after taking the part off");
                    ItemStack block = craft(helper, takenOff);
                    helper.assertTrue(block.is(WorldContent.NETWORK_CHEST.item().get()), "crafted " + block);
                    assertSettings(helper, block, "after crafting the block");
                    BlockPos target = helper.absolutePos(NetworkGameTests.at(6));
                    placer.setItemInHand(InteractionHand.MAIN_HAND, block);
                    helper.assertTrue(block
                            .useOn(new UseOnContext(helper.getLevel(), placer, InteractionHand.MAIN_HAND, block,
                                    new BlockHitResult(target.getCenter(), Direction.UP, target, false)))
                            .consumesAction(), "placing the block");
                    NetworkChestBlockEntity placed = helper.getBlockEntity(NetworkGameTests.at(6),
                            NetworkChestBlockEntity.class);
                    helper.assertTrue(placed.view().filter().getItem(0).is(Items.IRON_INGOT), "placed filter");
                    helper.assertTrue(INGOTS.equals(placed.view().filter().tag(4)), "placed tag");
                    helper.assertValueEqual(placed.view().mode(), 1, "placed mode");
                    CompoundTag saved = placed.saveCustomOnly(helper.getLevel().registryAccess());
                    NetworkChestBlockEntity loaded = new NetworkChestBlockEntity(placed.getBlockPos(),
                            placed.getBlockState());
                    loaded.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING,
                            helper.getLevel().registryAccess(), saved));
                    helper.assertTrue(INGOTS.equals(loaded.view().filter().tag(4)), "loaded tag");
                    helper.assertValueEqual(loaded.view().mode(), 1, "loaded mode");
                    List<ItemStack> drops = Block.getDrops(helper.getBlockState(NetworkGameTests.at(6)),
                            helper.getLevel(), target, placed, null, ItemStack.EMPTY);
                    helper.assertValueEqual(drops.size(), 1, "drops");
                    assertSettings(helper, drops.getFirst(), "in the block's drop");
                    List<Component> lines = new ArrayList<>();
                    drops.getFirst().getItem().appendHoverText(drops.getFirst(), Item.TooltipContext.EMPTY,
                            TooltipDisplay.DEFAULT, lines::add, TooltipFlag.NORMAL);
                    String tooltip = lines.toString();
                    helper.assertTrue(tooltip.contains("c:ingots") && tooltip.contains("rows_6"),
                            "tooltip without the settings: " + tooltip);
                    helper.assertFalse(new ItemStack(WorldContent.NETWORK_CHEST_PART_ITEM.get())
                            .has(ModDataComponents.WORLD_SETTINGS.get()), "a fresh part carries settings");
                    ItemStack fresh = craft(helper, new ItemStack(WorldContent.NETWORK_CHEST.item().get()));
                    helper.assertFalse(fresh.has(ModDataComponents.WORLD_SETTINGS.get()), "a fresh craft has settings");
                }).thenSucceed();
    }

    private static NetworkChestPart chestPart(GameTestHelper helper) {
        if (PartGameTests.part(helper, PartGameTests.parts(helper, 4), Direction.DOWN) instanceof NetworkChestPart p) {
            return p;
        }
        throw helper.assertionException(Component.literal("Not a network chest part"));
    }

    private static void assertSettings(GameTestHelper helper, ItemStack stack, String step) {
        WorldSettings settings = stack.get(ModDataComponents.WORLD_SETTINGS.get());
        if (settings == null) {
            throw helper.assertionException(Component.literal("no settings in the item " + step));
        }
        helper.assertTrue(settings.filter().getFirst().is(Items.IRON_INGOT), "the filter " + step);
        helper.assertValueEqual(settings.tags().get(4), "c:ingots", "the tag " + step);
        helper.assertValueEqual(settings.mode(), 1, "the mode " + step);
    }

    /**
     * The chest view for a player: a click takes a stack (or half with a right-click) onto the cursor, a click with a
     * carried stack stores it, a shift-click takes into the inventory and an inventory shift-click stores; the counts
     * are conserved. The settings GUI names filter slots, moves one to a tag with an empty right-click and switches the
     * mode.
     */
    private static void playerMenus(GameTestHelper helper) {
        network(helper);
        NetworkChestBlockEntity chest = chest(helper);
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "chest_user");
        player.setPos(helper.absoluteVec(NetworkGameTests.at(4).above().getCenter()));
        NetworkChestMenu[] menu = new NetworkChestMenu[1];
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.state(helper, NetworkGameTests.at(4)))
                .thenExecute(() -> {
                    NetworkGameTests.insert(NetworkGameTests.state(helper, NetworkGameTests.at(1)), IRON, 100);
                    WorldMenu settings = WorldMenu.forDevice(1, player.getInventory(), chest.view());
                    settings.setCarried(new ItemStack(Items.IRON_INGOT, 5));
                    settings.clicked(0, 0, ContainerInput.PICKUP, player);
                    settings.setCarried(ItemStack.EMPTY);
                    helper.assertTrue(chest.view().filter().getItem(0).is(Items.IRON_INGOT), "slot not named");
                    helper.assertValueEqual(player.getInventory().countItem(Items.IRON_INGOT), 0,
                            "naming a slot moved real items");
                    settings.clicked(0, 1, ContainerInput.PICKUP, player);
                    helper.assertValueEqual(settings.tagIndex(0), 0, "the first tag after a right-click");
                    helper.assertTrue(chest.view().filter().tag(0) != null, "no tag after a right-click");
                    for (int i = 0; i < WorldFilter.tagsOf(new ItemStack(Items.IRON_INGOT)).size(); i++) {
                        settings.clicked(0, 1, ContainerInput.PICKUP, player);
                    }
                    helper.assertValueEqual(settings.tagIndex(0), WorldFilter.EXACT, "back to the exact item");
                    helper.assertTrue(settings.clickMenuButton(player, WorldMenu.CYCLE_MODE), "mode button");
                    helper.assertValueEqual(chest.view().mode(), 1, "mode after the button");
                    menu[0] = NetworkChestMenu.forView(2, player.getInventory(), chest.view());
                    helper.assertValueEqual(menu[0].getRowCount(), 6, "rows of the menu");
                }).thenIdle(1).thenExecute(() -> {
                    menu[0].clicked(0, 0, ContainerInput.PICKUP, player);
                    helper.assertValueEqual(menu[0].getCarried().getCount(), 64, "a left-click takes a stack");
                    helper.assertValueEqual(amount(helper, IRON), 36L, "iron after the take");
                    menu[0].clicked(10, 1, ContainerInput.PICKUP, player);
                    helper.assertValueEqual(menu[0].getCarried().getCount(), 63, "a right-click stores one");
                    menu[0].clicked(10, 0, ContainerInput.PICKUP, player);
                    helper.assertTrue(menu[0].getCarried().isEmpty(), "a left-click stores the stack");
                    helper.assertValueEqual(amount(helper, IRON), 100L, "iron after storing");
                }).thenIdle(1).thenExecute(() -> {
                    menu[0].clicked(0, 1, ContainerInput.PICKUP, player);
                    helper.assertValueEqual(menu[0].getCarried().getCount(), 32, "a right-click takes half");
                    player.getInventory().add(menu[0].getCarried());
                    menu[0].setCarried(ItemStack.EMPTY);
                }).thenIdle(1).thenExecute(() -> {
                    menu[0].clicked(0, 0, ContainerInput.QUICK_MOVE, player);
                    helper.assertValueEqual(player.getInventory().countItem(Items.IRON_INGOT), 96,
                            "a shift-click takes into the inventory");
                    helper.assertValueEqual(amount(helper, IRON), 4L, "iron left");
                    int inventorySlot = menu[0].getRowCount() * 9;
                    for (int i = 0; i < 36; i++) {
                        menu[0].quickMoveStack(player, inventorySlot + i);
                    }
                    helper.assertValueEqual(player.getInventory().countItem(Items.IRON_INGOT), 0,
                            "inventory shift-clicks store");
                    helper.assertValueEqual(amount(helper, IRON), 100L, "all iron back");
                    helper.assertFalse(menu[0].canDragTo(menu[0].getSlot(0)), "dragging into a chest slot");
                }).thenSucceed();
    }

    /**
     * An owned chest whose owner's team lacks World Devices shows and takes nothing, and its settings say so; the
     * research completing wakes it. A stranger can change no setting and use no chest slot.
     */
    private static void accessAndResearch(GameTestHelper helper) {
        network(helper);
        NetworkChestBlockEntity chest = chest(helper);
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), "chest_owner");
        ServerPlayer stranger = NetworkGameTests.player(helper.getLevel(), "chest_stranger");
        stranger.setPos(helper.absoluteVec(NetworkGameTests.at(4).above().getCenter()));
        chest.setOwner(OwnerRecord.of(owner));
        ResourceHandler<ItemResource> handler = chest.view().handler();
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.state(helper, NetworkGameTests.at(4)))
                .thenExecute(() -> {
                    NetworkGameTests.insert(NetworkGameTests.state(helper, NetworkGameTests.at(1)), IRON, 100);
                    chest.view().filter().setItem(0, new ItemStack(Items.IRON_INGOT));
                    helper.assertFalse(chest.view().researched(), "researched without the node");
                    helper.assertValueEqual(insert(handler, ItemResource.of(Items.IRON_INGOT), 5), 0L,
                            "stored without research");
                }).thenIdle(1).thenExecute(() -> {
                    slot(helper, handler, 0, Items.AIR, 0);
                    WorldMenu settings = WorldMenu.forDevice(1, owner.getInventory(), chest.view());
                    helper.assertFalse(settings.researched(), "the menu's research flag");
                    MinecraftServer server = helper.getLevel().getServer();
                    ResearchTeam team = Research.team(owner, true);
                    Research.addPoints(server, team, 250);
                    helper.assertValueEqual(Research.useToken(server, team, MaxtechStorage.id("world_devices")),
                            Research.Result.DONE, "token on World Devices");
                }).thenIdle(1).thenExecute(() -> {
                    helper.assertTrue(chest.view().researched(), "not researched after the token");
                    slot(helper, handler, 0, Items.IRON_INGOT, 64);
                    WorldMenu settings = WorldMenu.forDevice(1, stranger.getInventory(), chest.view());
                    settings.setCarried(new ItemStack(Items.DIRT));
                    settings.clicked(1, 0, ContainerInput.PICKUP, stranger);
                    helper.assertTrue(chest.view().filter().getItem(1).isEmpty(), "a stranger named a slot");
                    helper.assertFalse(settings.clickMenuButton(stranger, WorldMenu.CYCLE_MODE), "stranger's mode");
                    helper.assertFalse(settings.stillValid(stranger), "the stranger's settings stay open");
                    NetworkChestMenu menu = NetworkChestMenu.forView(2, stranger.getInventory(), chest.view());
                    menu.clicked(0, 0, ContainerInput.PICKUP, stranger);
                    helper.assertTrue(menu.getCarried().isEmpty(), "a stranger took iron");
                    helper.assertFalse(menu.stillValid(stranger), "the stranger's chest stays open");
                    helper.assertValueEqual(amount(helper, IRON), 100L, "iron after the stranger");
                }).thenSucceed();
    }
}
