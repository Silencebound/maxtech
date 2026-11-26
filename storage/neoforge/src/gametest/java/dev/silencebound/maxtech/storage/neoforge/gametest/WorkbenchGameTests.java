package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.cell.CellData;
import dev.silencebound.maxtech.storage.cell.CellFilter;
import dev.silencebound.maxtech.storage.cell.CellInventory;
import dev.silencebound.maxtech.storage.cell.CellStorage;
import dev.silencebound.maxtech.storage.cell.PortableCellItem;
import dev.silencebound.maxtech.storage.cell.PortableCellSource;
import dev.silencebound.maxtech.storage.cell.StorageCellItem;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.research.ResearchGates;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.storage.workbench.CellWorkbenchBlockEntity;
import dev.silencebound.maxtech.storage.workbench.CellWorkbenchMenu;
import dev.silencebound.maxtech.storage.workbench.WorkbenchContent;
import dev.silencebound.maxtech.storage.workbench.WorkbenchGates;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The cell workbench and the portable cell (M6b area B): building and stripping a cell through the menu with every part
 * coming back, refusals that leave cell, cursor and inventory as they were, shift-clicks, the filter's ghost slots and
 * buttons, the research check point, the dropped cell, and the portable cell's terminal, battery and charging.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class WorkbenchGameTests {
    private static final BlockPos BENCH = new BlockPos(1, 1, 1);
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final String RESEARCH_TEST = "workbench_research_gates";

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);
    private static final List<String> NAMES = new ArrayList<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        test("workbench_build_and_strip_keeps_parts", WorkbenchGameTests::buildAndStrip);
        test("workbench_refuses_what_contents_need", WorkbenchGameTests::refusesWhatContentsNeed);
        test("workbench_shift_clicks", WorkbenchGameTests::shiftClicks);
        test("workbench_filter_and_buttons", WorkbenchGameTests::filterAndButtons);
        test("workbench_clear_unknown_filter", WorkbenchGameTests::clearUnknownFilter);
        test(RESEARCH_TEST, WorkbenchGameTests::researchGates);
        test("workbench_drops_its_cell", WorkbenchGameTests::dropsItsCell);
        test("workbench_portable_cell_terminal", WorkbenchGameTests::portableCell);
    }

    private WorkbenchGameTests() {
    }

    private static void test(String name, Consumer<GameTestHelper> function) {
        NAMES.add(name);
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(function));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        // Research off: these tests are about the workbench's mechanics; workbench_research_gates runs with it on.
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("workbench"),
                new StorageTestEnvironment("research.preset", "off", false));
        Holder<TestEnvironmentDefinition<?>> research = event
                .registerEnvironment(MaxtechStorage.id("workbench_research"), StorageTestEnvironment.plain());
        for (String name : NAMES) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(name.equals(RESEARCH_TEST) ? research : environment, StorageTests.SMALL, 200,
                                    0, true)));
        }
    }

    /**
     * A small casing, a chip, two memory modules and an add-on go in by cursor clicks and come out again; the player
     * ends with exactly the items they started with, and the casing is a casing again.
     */
    private static void buildAndStrip(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        CellWorkbenchMenu menu = menu(helper, player);
        List<ItemStack> parts = List.of(new ItemStack(CellContent.SMALL_CELL_CASING.get()),
                new ItemStack(CellContent.ITEM_CONTROLLER_CHIP.get()), new ItemStack(CellContent.MEMORY_MODULE_1.get()),
                new ItemStack(CellContent.MEMORY_MODULE_1.get()), new ItemStack(CellContent.PARTITION_ADDON.get()));
        Map<Item, Integer> before = new HashMap<>();
        for (ItemStack part : parts) {
            before.merge(part.getItem(), 1, Integer::sum);
        }
        placeWith(menu, player, parts.get(0), CellWorkbenchMenu.CASING_SLOT);
        placeWith(menu, player, parts.get(1), CellWorkbenchMenu.CHIP_SLOT);
        placeWith(menu, player, parts.get(2), CellWorkbenchMenu.FIRST_MEMORY);
        placeWith(menu, player, parts.get(3), CellWorkbenchMenu.FIRST_MEMORY + 1);
        placeWith(menu, player, parts.get(4), CellWorkbenchMenu.FIRST_ADDON);
        CellData data = CellGameTests.data(helper, menu.cell());
        helper.assertTrue(menu.cell().is(CellContent.SMALL_STORAGE_CELL.get()), "built " + menu.cell());
        helper.assertValueEqual(data.memory().size(), 2, "memory modules");
        helper.assertValueEqual(data.addons().size(), 1, "add-ons");
        helper.assertValueEqual(data.totalBytes(), 2048L, "capacity shown");
        helper.assertTrue(menu.filterShown(), "filter not shown with a partition");
        click(menu, player, CellWorkbenchMenu.FIRST_MEMORY + 2, 0);
        helper.assertTrue(menu.getCarried().isEmpty(), "a third slot on a small casing gave something");

        takeToInventory(menu, player, CellWorkbenchMenu.FIRST_ADDON);
        takeToInventory(menu, player, CellWorkbenchMenu.FIRST_MEMORY);
        takeToInventory(menu, player, CellWorkbenchMenu.FIRST_MEMORY);
        takeToInventory(menu, player, CellWorkbenchMenu.CHIP_SLOT);
        helper.assertTrue(menu.cell().is(CellContent.SMALL_CELL_CASING.get()), "stripped to " + menu.cell());
        takeToInventory(menu, player, CellWorkbenchMenu.CASING_SLOT);
        helper.assertTrue(menu.cell().isEmpty(), "the casing slot kept " + menu.cell());
        helper.assertValueEqual(counts(player), before, "the player's items after building and stripping");
        helper.succeed();
    }

    /**
     * A cell holding 7,500 bytes in two memory modules II: taking a module and swapping one for module I are refused
     * with the no-fit notice, the cell, cursor and inventory unchanged; a cell whose copy sits in a drive is refused as
     * in use.
     */
    private static void refusesWhatContentsNeed(GameTestHelper helper) {
        DriveBlockEntity drive = CellGameTests.drive(helper, new BlockPos(5, 1, 1));
        Item two = CellContent.MEMORY_MODULE_2.get();
        drive.insertCell(CellPartsGameTests.cell(CellContent.MEDIUM_STORAGE_CELL.get(), List.of(two, two), List.of(),
                CellFilter.EMPTY));
        helper.startSequence().thenWaitUntil(() -> CellGameTests.mounted(helper, drive, 0)).thenExecute(() -> {
            CellGameTests.insert(CellGameTests.mounted(helper, drive, 0), COBBLE, 60_000);
            ItemStack cell = drive.removeCell();
            ServerPlayer player = player(helper);
            CellWorkbenchMenu menu = menu(helper, player);
            placeWith(menu, player, cell.copy(), CellWorkbenchMenu.CASING_SLOT);
            ItemStack before = menu.cell().copy();

            click(menu, player, CellWorkbenchMenu.FIRST_MEMORY, 0);
            helper.assertTrue(menu.getCarried().isEmpty(), "took a module the contents need");
            helper.assertValueEqual(menu.notice(), CellWorkbenchMenu.NOTICE_NO_FIT, "notice");
            menu.setCarried(new ItemStack(CellContent.MEMORY_MODULE_1.get()));
            click(menu, player, CellWorkbenchMenu.FIRST_MEMORY, 0);
            helper.assertTrue(menu.getCarried().is(CellContent.MEMORY_MODULE_1.get()), "swapped module II for I");
            menu.setCarried(ItemStack.EMPTY);
            takeToInventory(menu, player, CellWorkbenchMenu.CHIP_SLOT);
            helper.assertTrue(ItemStack.matches(menu.cell(), before), "a refused change changed the cell");
            helper.assertTrue(player.getInventory().isEmpty(), "a refused change gave the player something");

            drive.insertCell(menu.cell().copy());
            menu.setCarried(new ItemStack(CellContent.MEMORY_MODULE_3.get()));
            click(menu, player, CellWorkbenchMenu.FIRST_MEMORY + 2, 0);
            helper.assertValueEqual(menu.notice(), CellWorkbenchMenu.NOTICE_IN_USE, "notice for a cell in a drive");
            helper.assertTrue(menu.getCarried().is(CellContent.MEMORY_MODULE_3.get()), "module III went in");
            menu.setCarried(ItemStack.EMPTY);
        }).thenSucceed();
    }

    /**
     * Shift-clicking a casing and parts from the inventory fits them; shift-clicking a part out with a full inventory
     * is refused with the no-room notice and the part stays.
     */
    private static void shiftClicks(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        CellWorkbenchMenu menu = menu(helper, player);
        player.getInventory().setItem(0, new ItemStack(CellContent.MEDIUM_CELL_CASING.get()));
        player.getInventory().setItem(1, new ItemStack(CellContent.FLUID_CONTROLLER_CHIP.get()));
        player.getInventory().setItem(2, new ItemStack(CellContent.MEMORY_MODULE_1.get(), 3));
        int hotbar = CellWorkbenchMenu.FIRST_INVENTORY + 27;
        menu.clicked(hotbar, 0, ContainerInput.QUICK_MOVE, player);
        menu.clicked(hotbar + 1, 0, ContainerInput.QUICK_MOVE, player);
        menu.clicked(hotbar + 2, 0, ContainerInput.QUICK_MOVE, player);
        menu.clicked(hotbar + 2, 0, ContainerInput.QUICK_MOVE, player);
        CellData data = CellGameTests.data(helper, menu.cell());
        helper.assertTrue(menu.cell().is(CellContent.MEDIUM_STORAGE_CELL.get()), "built " + menu.cell());
        helper.assertValueEqual(data.type(), CellContent.FLUID_TYPE.id(), "type");
        helper.assertValueEqual(data.memory().size(), 2, "modules after two shift-clicks");
        helper.assertValueEqual(player.getInventory().getItem(2).getCount(), 1, "modules left");
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) {
                player.getInventory().setItem(slot, new ItemStack(Items.DIRT, 64));
            }
        }
        player.getInventory().setItem(2, new ItemStack(Items.DIRT, 64));
        takeToInventory(menu, player, CellWorkbenchMenu.FIRST_MEMORY);
        helper.assertValueEqual(menu.notice(), CellWorkbenchMenu.NOTICE_NO_ROOM, "notice with a full inventory");
        helper.assertValueEqual(CellGameTests.data(helper, menu.cell()).memory().size(), 2, "modules after it");
        helper.succeed();
    }

    /**
     * With a partition add-on, a carried stack names itself in a ghost slot (the stack stays on the cursor), an empty
     * right click gives the entry a tag, an empty left click clears it; the fuzzy button flips the mode.
     */
    private static void filterAndButtons(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        CellWorkbenchMenu menu = menu(helper, player);
        ItemStack cell = CellPartsGameTests.cell(CellContent.SMALL_STORAGE_CELL.get(),
                List.of(CellContent.MEMORY_MODULE_1.get()), List.of(CellContent.PARTITION_ADDON.get()),
                CellFilter.EMPTY);
        placeWith(menu, player, cell, CellWorkbenchMenu.CASING_SLOT);
        menu.setCarried(new ItemStack(Items.COBBLESTONE, 5));
        click(menu, player, CellWorkbenchMenu.FIRST_FILTER, 0);
        helper.assertValueEqual(menu.getCarried().getCount(), 5, "cursor after naming a filter entry");
        menu.setCarried(new ItemStack(Items.WATER_BUCKET));
        click(menu, player, CellWorkbenchMenu.FIRST_FILTER + 1, 0);
        menu.setCarried(ItemStack.EMPTY);
        CellFilter filter = CellGameTests.data(helper, menu.cell()).filter();
        helper.assertValueEqual(filter.entries().size(), 2, "entries");
        helper.assertValueEqual(filter.entries().get(0).key(), COBBLE, "first entry");
        helper.assertValueEqual(filter.entries().get(1).key().kind(), StackKey.Kind.FLUID, "bucket names its fluid");
        click(menu, player, CellWorkbenchMenu.FIRST_FILTER, 1);
        helper.assertTrue(CellGameTests.data(helper, menu.cell()).filter().entries().get(0).tag().isPresent(),
                "right click gave no tag");
        click(menu, player, CellWorkbenchMenu.FIRST_FILTER + 1, 0);
        helper.assertValueEqual(CellGameTests.data(helper, menu.cell()).filter().entries().size(), 1, "after clear");
        menu.clickMenuButton(player, CellWorkbenchMenu.FUZZY);
        helper.assertTrue(CellGameTests.data(helper, menu.cell()).filter().fuzzy(), "fuzzy after the button");
        takeToInventory(menu, player, CellWorkbenchMenu.FIRST_ADDON);
        helper.assertTrue(!menu.filterShown(), "filter shown without partition or void");
        helper.succeed();
    }

    /**
     * A partition filter keeping two entries of a removed mod next to a known one: they stay until the player clicks
     * the clear-unknown button, which drops only them; the known entry, the filter's modes and the cell's kept stored
     * contents stay.
     */
    private static void clearUnknownFilter(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        CellWorkbenchMenu menu = menu(helper, player);
        long id = KeptEntriesGameTests.storedWithUnknown(helper, 10);
        List<Tag> kept = List.of(entryTag(), entryTag());
        CellFilter filter = new CellFilter(List.of(CellFilter.Entry.of(COBBLE)), true, true, kept);
        ItemStack cell = CellPartsGameTests.cell(CellContent.SMALL_STORAGE_CELL.get(),
                List.of(CellContent.MEMORY_MODULE_1.get()), List.of(CellContent.PARTITION_ADDON.get()), filter);
        cell.set(ModDataComponents.CELL.get(), CellGameTests.data(helper, cell).withId(id));
        placeWith(menu, player, cell, CellWorkbenchMenu.CASING_SLOT);
        helper.assertValueEqual(menu.unknownFilterEntries(), 2, "unknown filter entries shown");
        menu.clickMenuButton(player, CellWorkbenchMenu.FUZZY);
        menu.clickMenuButton(player, CellWorkbenchMenu.FUZZY);
        helper.assertValueEqual(CellGameTests.data(helper, menu.cell()).filter().kept(), kept,
                "kept entries after other filter edits");

        // Without a filter add-on the filter is hidden (so is the button) and the action does nothing.
        takeToInventory(menu, player, CellWorkbenchMenu.FIRST_ADDON);
        helper.assertTrue(!menu.filterShown(), "filter shown without a filter add-on");
        helper.assertTrue(!menu.clickMenuButton(player, CellWorkbenchMenu.CLEAR_UNKNOWN),
                "cleared without a filter add-on");
        helper.assertValueEqual(CellGameTests.data(helper, menu.cell()).filter().kept(), kept,
                "kept entries without a filter add-on");
        placeWith(menu, player, new ItemStack(CellContent.PARTITION_ADDON.get()), CellWorkbenchMenu.FIRST_ADDON);
        helper.assertTrue(menu.filterShown(), "filter hidden after the add-on went back in");

        // A cell in use in a drive (its id mounted) is refused like any other edit; nothing changes.
        CellStorage storage = CellGameTests.storage(helper);
        CellInventory inDrive = storage.mount(id, CellContent.ITEM_TYPE.get(),
                CellContent.SMALL_STORAGE_CELL.get().casing(), CellGameTests.data(helper, menu.cell()), () -> {
                });
        if (inDrive == null) {
            throw helper.assertionException(Component.literal("could not mount the cell as a drive would"));
        }
        helper.assertTrue(!menu.clickMenuButton(player, CellWorkbenchMenu.CLEAR_UNKNOWN), "cleared a cell in use");
        helper.assertValueEqual(menu.notice(), CellWorkbenchMenu.NOTICE_IN_USE, "notice for a cell in use");
        helper.assertValueEqual(CellGameTests.data(helper, menu.cell()).filter().kept(), kept,
                "kept entries of a cell in use");
        storage.unmount(inDrive);

        helper.assertTrue(menu.clickMenuButton(player, CellWorkbenchMenu.CLEAR_UNKNOWN), "clear refused");
        CellData data = CellGameTests.data(helper, menu.cell());
        helper.assertValueEqual(data.filter().kept(), List.<Tag>of(), "kept entries after clearing");
        helper.assertValueEqual(data.filter().entries(), List.of(CellFilter.Entry.of(COBBLE)), "known entries");
        helper.assertTrue(data.filter().fuzzy() && data.filter().deny(), "filter modes changed");
        helper.assertValueEqual(data.id(), id, "cell id");
        helper.assertValueEqual(storage.keptKinds(id), 1, "kept stored kinds");
        helper.assertValueEqual(storage.amount(id, COBBLE), 10L, "stored cobblestone");
        helper.assertValueEqual(menu.unknownFilterEntries(), 0, "unknown filter entries after clearing");
        helper.succeed();
    }

    /** A saved filter entry naming the unknown item. */
    private static Tag entryTag() {
        CompoundTag entry = new CompoundTag();
        entry.put("key", KeptEntriesGameTests.unknownKey());
        return entry;
    }

    /**
     * The check point names each gated part's unlock; small casings, chips and memory module I need none. With research
     * on (the standalone preset), a new player's medium casing and memory module II are refused with the research
     * notice and stay with the player, while a small casing and its chip go in.
     */
    private static void researchGates(GameTestHelper helper) {
        helper.assertValueEqual(gate(new ItemStack(CellContent.MEMORY_MODULE_2.get())),
                ResearchGates.MEMORY_MODULE_2.toString(), "memory II");
        helper.assertValueEqual(gate(new ItemStack(CellContent.MEMORY_MODULE_3.get())),
                ResearchGates.MEMORY_MODULE_3.toString(), "memory III");
        helper.assertValueEqual(gate(new ItemStack(CellContent.MEDIUM_CELL_CASING.get())),
                ResearchGates.MEDIUM_CASING.toString(), "medium casing");
        helper.assertValueEqual(gate(new ItemStack(CellContent.LARGE_STORAGE_CELL.get())),
                ResearchGates.LARGE_CASING.toString(), "large cell");
        helper.assertValueEqual(gate(new ItemStack(CellContent.BULK_CELL_CASING.get())),
                ResearchGates.BULK_CASING.toString(), "bulk casing");
        helper.assertValueEqual(gate(new ItemStack(CellContent.PORTABLE_CELL_CASING.get())),
                ResearchGates.PORTABLE_CASING.toString(), "portable casing");
        helper.assertValueEqual(gate(new ItemStack(CellContent.PARTITION_ADDON.get())),
                ResearchGates.PARTITION_ADDON.toString(), "partition");
        helper.assertValueEqual(gate(new ItemStack(CellContent.VOID_OVERFLOW_ADDON.get())),
                ResearchGates.VOID_ADDON.toString(), "void");
        helper.assertValueEqual(gate(new ItemStack(CellContent.READ_ONLY_ADDON.get())),
                ResearchGates.LOCK_ADDON.toString(), "lock");
        helper.assertTrue(
                WorkbenchGates.unlock(new ItemStack(CellContent.SMALL_CELL_CASING.get())) == null
                        && WorkbenchGates.unlock(new ItemStack(CellContent.ITEM_CONTROLLER_CHIP.get())) == null
                        && WorkbenchGates.unlock(new ItemStack(CellContent.MEMORY_MODULE_1.get())) == null,
                "an ungated part has an unlock");

        ServerPlayer player = player(helper);
        CellWorkbenchMenu menu = menu(helper, player);
        menu.setCarried(new ItemStack(CellContent.MEDIUM_CELL_CASING.get()));
        click(menu, player, CellWorkbenchMenu.CASING_SLOT, 0);
        helper.assertTrue(menu.cell().isEmpty(), "a locked casing went in");
        helper.assertValueEqual(menu.notice(), CellWorkbenchMenu.NOTICE_RESEARCH, "notice for a locked casing");
        helper.assertTrue(menu.getCarried().is(CellContent.MEDIUM_CELL_CASING.get()), "the casing left the cursor");
        menu.setCarried(ItemStack.EMPTY);
        placeWith(menu, player, new ItemStack(CellContent.SMALL_CELL_CASING.get()), CellWorkbenchMenu.CASING_SLOT);
        placeWith(menu, player, new ItemStack(CellContent.ITEM_CONTROLLER_CHIP.get()), CellWorkbenchMenu.CHIP_SLOT);
        helper.assertTrue(menu.cell().is(CellContent.SMALL_STORAGE_CELL.get()), "small casing and chip " + menu.cell());
        menu.setCarried(new ItemStack(CellContent.MEMORY_MODULE_2.get()));
        click(menu, player, CellWorkbenchMenu.FIRST_MEMORY, 0);
        helper.assertValueEqual(menu.notice(), CellWorkbenchMenu.NOTICE_RESEARCH, "notice for memory module II");
        helper.assertTrue(menu.getCarried().is(CellContent.MEMORY_MODULE_2.get()), "module II left the cursor");
        menu.setCarried(ItemStack.EMPTY);
        helper.succeed();
    }

    /** Breaking the workbench drops the block and the cell in it. */
    private static void dropsItsCell(GameTestHelper helper) {
        helper.setBlock(BENCH, WorkbenchContent.CELL_WORKBENCH.get());
        CellWorkbenchBlockEntity bench = helper.getBlockEntity(BENCH, CellWorkbenchBlockEntity.class);
        bench.setCell(CellGameTests.cell(CellContent.ITEM_TYPE.id(), 1));
        helper.getLevel().destroyBlock(helper.absolutePos(BENCH), true);
        boolean cell = false;
        boolean block = false;
        for (ItemEntity drop : helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new AABB(helper.absolutePos(BENCH)).inflate(2))) {
            cell |= drop.getItem().getItem() instanceof StorageCellItem;
            block |= drop.getItem().is(WorkbenchContent.CELL_WORKBENCH.item().get());
            drop.discard();
        }
        helper.assertTrue(cell && block, "drops: cell " + cell + ", workbench " + block);
        helper.succeed();
    }

    /**
     * A portable cell charged from a controller opens its terminal source: inserts and extracts cost battery FE, FE a
     * charger adds while it is open is kept, a rollback costs nothing, a copy cannot open while it is open, closing
     * writes its stats and lets go, an empty battery moves nothing, and the cell leaving the hand closes it.
     */
    private static void portableCell(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 3);
        NetworkGameTests.power(helper, controller, 5_000);
        ServerPlayer player = player(helper);
        ItemStack stack = new ItemStack(CellContent.PORTABLE_STORAGE_CELL.get());
        stack.set(ModDataComponents.CELL.get(),
                CellData.of(CellContent.ITEM_TYPE.id(), List.of(CellContent.MEMORY_MODULE_1.get())));
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        player.setShiftKeyDown(true);
        BlockPos at = helper.absolutePos(NetworkGameTests.at(3));
        stack.getItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false)));
        player.setShiftKeyDown(false);
        ItemStack held = player.getMainHandItem();
        helper.assertValueEqual(PortableCellItem.energy(held), 5_000, "charged from the controller");

        PortableCellSource source = PortableCellSource.open(player, InteractionHand.MAIN_HAND);
        if (source == null) {
            throw helper.assertionException(Component.literal("portable cell did not open"));
        }
        TerminalMenu menu = TerminalMenu.server(1, player.getInventory(), source, update -> {
        });
        helper.assertTrue(PortableCellSource.open(player, InteractionHand.MAIN_HAND) == null, "opened twice");
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertValueEqual(source.insert(COBBLE, 100, transaction), 100L, "stored");
            helper.assertValueEqual(source.extract(COBBLE, 10, transaction), 10L, "taken");
            transaction.commit();
        }
        helper.assertValueEqual(PortableCellItem.energy(held), 5_000 - 4 - 4, "battery after two operations");
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertValueEqual(source.insert(ItemKey.of(held), 1, transaction), 0L, "the cell into itself");
        }
        // A charger adds FE while the GUI is open, through the item energy capability (which may swap the stack).
        EnergyHandler battery = ItemAccess.forPlayerSlot(player, player.getInventory().getSelectedSlot())
                .getCapability(Capabilities.Energy.ITEM);
        if (battery == null) {
            throw helper.assertionException(Component.literal("no item energy capability on the portable cell"));
        }
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertValueEqual(battery.insert(1_000, transaction), 1_000, "charged while open");
            transaction.commit();
        }
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertValueEqual(source.insert(COBBLE, 10, transaction), 10L, "stored after charging");
            transaction.commit();
        }
        held = player.getMainHandItem();
        helper.assertValueEqual(PortableCellItem.energy(held), 5_000 - 4 - 4 + 1_000 - 4,
                "battery keeps the charge added while open");
        try (Transaction transaction = Transaction.openRoot()) {
            source.extract(COBBLE, 10, transaction);
        }
        helper.assertValueEqual(PortableCellItem.energy(held), 5_988, "battery after a rolled-back extract");
        menu.removed(player);
        helper.assertTrue(!CellGameTests.storage(helper).isMounted(CellGameTests.data(helper, held).id()),
                "mounted after closing");
        helper.assertValueEqual(CellGameTests.data(helper, held).kinds(), 1, "stats written on closing");

        held.set(ModDataComponents.PORTABLE_ENERGY.get(), 0);
        PortableCellSource empty = PortableCellSource.open(player, InteractionHand.MAIN_HAND);
        if (empty == null) {
            throw helper.assertionException(Component.literal("portable cell did not reopen"));
        }
        helper.assertValueEqual(empty.cell().amount(COBBLE), 100L, "contents after reopening");
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertValueEqual(empty.extract(COBBLE, 10, transaction), 0L, "taken without energy");
        }
        // The stack leaves the hand: the source is no longer valid, and the closing menu lets go of the cell.
        TerminalMenu second = TerminalMenu.server(2, player.getInventory(), empty, update -> {
        });
        long id = CellGameTests.data(helper, held).id();
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.getInventory().setItem(9, held);
        helper.assertTrue(!second.stillValid(player), "still valid with the cell out of the hand");
        second.removed(player);
        helper.assertTrue(!CellGameTests.storage(helper).isMounted(id), "mounted after the cell left the hand");
        helper.assertValueEqual(CellGameTests.storage(helper).amount(id, COBBLE), 100L, "contents after that");
        helper.succeed();
    }

    private static String gate(ItemStack stack) {
        return String.valueOf(WorkbenchGates.unlock(stack));
    }

    private static ServerPlayer player(GameTestHelper helper) {
        return NetworkGameTests.player(helper.getLevel(), "workbench_user");
    }

    private static CellWorkbenchMenu menu(GameTestHelper helper, ServerPlayer player) {
        helper.setBlock(BENCH, WorkbenchContent.CELL_WORKBENCH.get());
        return CellWorkbenchMenu.server(1, player.getInventory(),
                helper.getBlockEntity(BENCH, CellWorkbenchBlockEntity.class));
    }

    private static void click(CellWorkbenchMenu menu, ServerPlayer player, int slot, int button) {
        menu.clicked(slot, button, ContainerInput.PICKUP, player);
    }

    /** Clicks {@code slot} with {@code stack} on the cursor, as a player putting it there. */
    private static void placeWith(CellWorkbenchMenu menu, ServerPlayer player, ItemStack stack, int slot) {
        menu.setCarried(stack);
        click(menu, player, slot, 0);
        if (!menu.getCarried().isEmpty()) {
            player.getInventory().add(menu.getCarried());
            menu.setCarried(ItemStack.EMPTY);
        }
    }

    private static void takeToInventory(CellWorkbenchMenu menu, ServerPlayer player, int slot) {
        menu.clicked(slot, 0, ContainerInput.QUICK_MOVE, player);
    }

    private static Map<Item, Integer> counts(ServerPlayer player) {
        Map<Item, Integer> counts = new HashMap<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }
}
