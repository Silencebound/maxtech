package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cell.CellAddonItem;
import dev.silencebound.maxtech.storage.cell.CellAssembly;
import dev.silencebound.maxtech.storage.cell.CellCasing;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.cell.CellData;
import dev.silencebound.maxtech.storage.cell.CellFilter;
import dev.silencebound.maxtech.storage.cell.CellInventory;
import dev.silencebound.maxtech.storage.cell.CellStorage;
import dev.silencebound.maxtech.storage.cell.MemoryModuleItem;
import dev.silencebound.maxtech.storage.cell.StorageCellItem;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * Cell casings, memory modules II and III and add-ons (M6b area B): each casing's slots, the bytes of the new modules,
 * the bulk cell's one kind and multiplier, every add-on's insert rule with rollbacks, the workbench rules refusing a
 * change the contents would not fit, and cells made before M6b.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class CellPartsGameTests {
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey STONE = ItemKey.of(Items.STONE);
    private static final long KB = 1024;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);
    private static final List<String> NAMES = new ArrayList<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        test("cell_parts_casing_limits", CellPartsGameTests::casingLimits);
        test("cell_parts_memory_bytes", CellPartsGameTests::memoryBytes);
        test("cell_parts_bulk_one_kind", CellPartsGameTests::bulkOneKind);
        test("cell_parts_partition", CellPartsGameTests::partition);
        test("cell_parts_void_overflow", CellPartsGameTests::voidOverflow);
        test("cell_parts_read_only", CellPartsGameTests::readOnly);
        test("cell_parts_old_priority_cell", CellPartsGameTests::oldPriorityCell);
        test("cell_parts_refits_only_when_contents_fit", CellPartsGameTests::refits);
        test("cell_parts_old_starter_cell", CellPartsGameTests::oldStarterCell);
    }

    private CellPartsGameTests() {
    }

    private static void test(String name, Consumer<GameTestHelper> function) {
        NAMES.add(name);
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(function));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("cell_parts"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, StorageTests.SMALL, 200, 0, true)));
        }
    }

    /**
     * Each casing takes as many memory modules and add-ons as its config says (small 2/1, medium 4/2, large 8/4) and
     * refuses one more, refuses a second add-on of a kind, and needs its chip before any other part.
     */
    private static void casingLimits(GameTestHelper helper) {
        CellStorage storage = CellGameTests.storage(helper);
        helper.assertValueEqual(slots(CellCasing.SMALL), List.of(2, 1), "small casing slots");
        helper.assertValueEqual(slots(CellCasing.MEDIUM), List.of(4, 2), "medium casing slots");
        helper.assertValueEqual(slots(CellCasing.LARGE), List.of(8, 4), "large casing slots");
        for (CellCasing casing : CellCasing.values()) {
            ItemStack stack = new ItemStack(CellContent.casingItem(casing));
            helper.assertTrue(CellAssembly.putMemory(stack, 0, CellContent.MEMORY_MODULE_1.get(), storage) == null,
                    casing + ": memory before the chip");
            stack = require(helper, CellAssembly.setChip(stack, CellContent.ITEM_CONTROLLER_CHIP.get(), storage),
                    casing + ": chip");
            helper.assertTrue(stack.is(CellContent.cellItem(casing)), casing + ": the casing became " + stack);
            for (int slot = 0; slot < casing.memorySlots(); slot++) {
                stack = require(helper, CellAssembly.putMemory(stack, slot, CellContent.MEMORY_MODULE_1.get(), storage),
                        casing + ": memory slot " + slot);
            }
            helper.assertTrue(CellAssembly.putMemory(stack, casing.memorySlots(), CellContent.MEMORY_MODULE_1.get(),
                    storage) == null, casing + ": one memory module too many");
            List<CellAddonItem> addons = List.of(CellContent.PARTITION_ADDON.get(),
                    CellContent.VOID_OVERFLOW_ADDON.get(), CellContent.READ_ONLY_ADDON.get());
            if (casing.addonSlots() > 1) {
                ItemStack one = require(helper, CellAssembly.putAddon(stack, 0, addons.get(0), storage),
                        casing + ": first add-on");
                helper.assertTrue(CellAssembly.putAddon(one, 1, addons.get(0), storage) == null,
                        casing + ": a second partition add-on");
            }
            for (int slot = 0; slot < Math.min(casing.addonSlots(), addons.size()); slot++) {
                stack = require(helper, CellAssembly.putAddon(stack, slot, addons.get(slot), storage),
                        casing + ": add-on slot " + slot);
            }
            if (casing.addonSlots() < addons.size()) {
                helper.assertTrue(CellAssembly.putAddon(stack, casing.addonSlots(), addons.get(casing.addonSlots()),
                        storage) == null, casing + ": one add-on too many");
            }
            CellData data = CellGameTests.data(helper, stack);
            helper.assertValueEqual(data.memory().size(), casing.memorySlots(), casing + ": memory modules");
            helper.assertValueEqual(data.totalBytes(), casing.capacity(KB * casing.memorySlots()),
                    casing + ": cached capacity");
        }
        helper.succeed();
    }

    /** Memory module II adds 4 KB and III 16 KB; a medium cell with both holds 20 KB less the kind's 8 bytes. */
    private static void memoryBytes(GameTestHelper helper) {
        helper.assertValueEqual(CellContent.MEMORY_MODULE_2.get().bytes(), 4 * KB, "memory module II");
        helper.assertValueEqual(CellContent.MEMORY_MODULE_3.get().bytes(), 16 * KB, "memory module III");
        DriveBlockEntity drive = CellGameTests.drive(helper, new BlockPos(1, 1, 1));
        drive.insertCell(cell(CellContent.MEDIUM_STORAGE_CELL.get(),
                List.of(CellContent.MEMORY_MODULE_2.get(), CellContent.MEMORY_MODULE_3.get()), List.of(),
                CellFilter.EMPTY));
        helper.startSequence().thenWaitUntil(() -> CellGameTests.mounted(helper, drive, 0)).thenExecute(() -> {
            CellInventory cell = CellGameTests.mounted(helper, drive, 0);
            helper.assertValueEqual(cell.totalBytes(), 20 * KB, "capacity");
            helper.assertValueEqual(CellGameTests.insert(cell, COBBLE, 1_000_000), (20 * KB - 8) * 8, "to the brim");
        }).thenSucceed();
    }

    /**
     * A bulk cell holds eight times its memory, of one kind: a second kind is refused while the first is in, and taken
     * once the cell is empty again.
     */
    private static void bulkOneKind(GameTestHelper helper) {
        DriveBlockEntity drive = CellGameTests.drive(helper, new BlockPos(1, 1, 1));
        drive.insertCell(cell(CellContent.BULK_STORAGE_CELL.get(), List.of(CellContent.MEMORY_MODULE_1.get()),
                List.of(), CellFilter.EMPTY));
        helper.startSequence().thenWaitUntil(() -> CellGameTests.mounted(helper, drive, 0)).thenExecute(() -> {
            CellInventory cell = CellGameTests.mounted(helper, drive, 0);
            helper.assertValueEqual(cell.totalBytes(), 8 * KB, "capacity of a bulk cell with one module I");
            helper.assertValueEqual(CellGameTests.insert(cell, COBBLE, 10), 10L, "the first kind");
            helper.assertValueEqual(CellGameTests.insert(cell, STONE, 10), 0L, "a second kind");
            helper.assertValueEqual(CellGameTests.insert(cell, COBBLE, 1_000_000), (8 * KB - 8) * 8 - 10,
                    "the first kind to the brim");
            CellGameTests.extract(cell, COBBLE, Long.MAX_VALUE);
            helper.assertValueEqual(CellGameTests.insert(cell, STONE, 10), 10L, "another kind once empty");
        }).thenSucceed();
    }

    /**
     * A partition takes only what its filter names: a key, a tag (planks) or, in fuzzy mode, every variant of a key; a
     * rolled-back insert of a named key leaves nothing. A cell whose filter is blank takes everything.
     */
    private static void partition(GameTestHelper helper) {
        ItemKey named = CellGameTests.named("a");
        CellFilter exact = new CellFilter(
                List.of(CellFilter.Entry.of(COBBLE), CellFilter.Entry.of(named),
                        new CellFilter.Entry(ItemKey.of(Items.OAK_PLANKS), Optional.of(ItemTags.PLANKS.location()))),
                false);
        List<Item> partition = List.of(CellContent.PARTITION_ADDON.get());
        List<Item> memory = List.of(CellContent.MEMORY_MODULE_1.get());
        DriveBlockEntity drive = CellGameTests.drive(helper, new BlockPos(1, 1, 1));
        drive.insertCell(cell(CellContent.SMALL_STORAGE_CELL.get(), memory, partition, exact));
        drive.insertCell(cell(CellContent.SMALL_STORAGE_CELL.get(), memory, partition, exact.withFuzzy(true)));
        drive.insertCell(cell(CellContent.SMALL_STORAGE_CELL.get(), memory, partition, CellFilter.EMPTY));
        helper.startSequence().thenWaitUntil(() -> CellGameTests.mounted(helper, drive, 2)).thenExecute(() -> {
            CellInventory strict = CellGameTests.mounted(helper, drive, 0);
            helper.assertValueEqual(CellGameTests.insert(strict, COBBLE, 5), 5L, "a named key");
            helper.assertValueEqual(CellGameTests.insert(strict, STONE, 5), 0L, "a key not named");
            helper.assertValueEqual(CellGameTests.insert(strict, ItemKey.of(Items.BIRCH_PLANKS), 5), 5L,
                    "planks by tag");
            helper.assertValueEqual(CellGameTests.insert(strict, named, 1), 1L, "the named variant");
            helper.assertValueEqual(CellGameTests.insert(strict, ItemKey.of(Items.DIAMOND), 1), 0L,
                    "another variant without fuzzy mode");
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(strict.insert(COBBLE, 7, transaction), 7L, "simulated");
            }
            helper.assertValueEqual(strict.amount(COBBLE), 5L, "cobblestone after a rollback");

            CellInventory fuzzy = CellGameTests.mounted(helper, drive, 1);
            helper.assertValueEqual(CellGameTests.insert(fuzzy, ItemKey.of(Items.DIAMOND), 1), 1L, "plain variant");
            helper.assertValueEqual(CellGameTests.insert(fuzzy, CellGameTests.named("b"), 1), 1L, "other name");
            helper.assertValueEqual(CellGameTests.insert(fuzzy, STONE, 1), 0L, "a key not named, fuzzy");

            CellInventory blank = CellGameTests.mounted(helper, drive, 2);
            helper.assertValueEqual(CellGameTests.insert(blank, STONE, 3), 3L, "blank filter");
        }).thenSucceed();
    }

    /**
     * Void overflow takes every named key and destroys what does not fit, reporting only what it stored; a key it does
     * not name stops at the cell's bytes. A rolled-back insert leaves the contents and reports nothing.
     */
    private static void voidOverflow(GameTestHelper helper) {
        CellFilter filter = new CellFilter(List.of(CellFilter.Entry.of(COBBLE)), false);
        DriveBlockEntity drive = CellGameTests.drive(helper, new BlockPos(1, 1, 1));
        drive.insertCell(cell(CellContent.SMALL_STORAGE_CELL.get(), List.of(CellContent.MEMORY_MODULE_1.get()),
                List.of(CellContent.VOID_OVERFLOW_ADDON.get()), filter));
        helper.startSequence().thenWaitUntil(() -> CellGameTests.mounted(helper, drive, 0)).thenExecute(() -> {
            CellInventory cell = CellGameTests.mounted(helper, drive, 0);
            List<Long> heard = new ArrayList<>();
            cell.setListener((key, delta) -> heard.add(delta));
            helper.assertValueEqual(CellGameTests.insert(cell, STONE, 1000), 1000L, "stone, not named");
            helper.assertValueEqual(CellGameTests.insert(cell, COBBLE, 100_000), 100_000L, "named, taken whole");
            helper.assertValueEqual(cell.amount(COBBLE), (KB - 8 - 125 - 8) * 8, "cobblestone stored");
            helper.assertValueEqual(heard, List.of(1000L, cell.amount(COBBLE)), "reported deltas");
            helper.assertValueEqual(CellGameTests.insert(cell, COBBLE, 50), 50L, "into a full cell");
            helper.assertValueEqual(CellGameTests.insert(cell, STONE, 50), 0L, "stone into a full cell");
            long before = cell.amount(COBBLE);
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(cell.extract(COBBLE, 100, transaction), 100L, "simulated extract");
                helper.assertValueEqual(cell.insert(COBBLE, 500, transaction), 500L, "simulated insert");
            }
            helper.assertValueEqual(cell.amount(COBBLE), before, "cobblestone after a rollback");
            helper.assertValueEqual(heard.size(), 2, "reports after a rollback");
        }).thenSucceed();
    }

    /** A read-only cell refuses every insert, also of a key it holds, and still gives its contents out. */
    private static void readOnly(GameTestHelper helper) {
        CellStorage storage = CellGameTests.storage(helper);
        DriveBlockEntity drive = CellGameTests.drive(helper, new BlockPos(1, 1, 1));
        drive.insertCell(cell(CellContent.SMALL_STORAGE_CELL.get(), List.of(CellContent.MEMORY_MODULE_1.get()),
                List.of(), CellFilter.EMPTY));
        helper.startSequence().thenWaitUntil(() -> CellGameTests.mounted(helper, drive, 0)).thenExecute(() -> {
            CellGameTests.insert(CellGameTests.mounted(helper, drive, 0), COBBLE, 100);
            ItemStack locked = require(helper,
                    CellAssembly.putAddon(drive.removeCell(), 0, CellContent.READ_ONLY_ADDON.get(), storage),
                    "lock fitted");
            drive.insertCell(locked);
        }).thenWaitUntil(() -> CellGameTests.mounted(helper, drive, 0)).thenExecute(() -> {
            CellInventory cell = CellGameTests.mounted(helper, drive, 0);
            helper.assertValueEqual(CellGameTests.insert(cell, COBBLE, 10), 0L, "a held key");
            helper.assertValueEqual(CellGameTests.insert(cell, STONE, 10), 0L, "a new key");
            helper.assertValueEqual(CellGameTests.extract(cell, COBBLE, 40), 40L, "extracted");
            helper.assertValueEqual(cell.amount(COBBLE), 60L, "left");
        }).thenSucceed();
    }

    /**
     * A cell saved while cells had their own priority (the priority add-on fitted, priority 7) still loads: the deleted
     * add-on drops, its partition add-on stays. In a drive at priority 4 it sits at the drive's 4, not its old 7, and
     * it follows the drive's changes.
     */
    private static void oldPriorityCell(GameTestHelper helper) {
        JsonObject json = JsonParser
                .parseString(
                        """
                                {"id": "maxtech_storage:small_storage_cell", "count": 1, "components": {"maxtech_storage:cell":
                                {"type": "maxtech_storage:item", "memory": ["maxtech_storage:memory_module_1"],
                                "addons": ["maxtech_storage:priority_addon", "maxtech_storage:partition_addon"], "priority": 7, "used_bytes": 0, "total_bytes": 0,
                                "kinds": 0}}}""")
                .getAsJsonObject();
        ItemStack stack = ItemStack.CODEC
                .parse(helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE), json)
                .getOrThrow(message -> helper.assertionException(Component.literal("old cell: " + message)));
        CellData data = CellGameTests.data(helper, stack);
        helper.assertValueEqual(data.addons(), List.<Item>of(CellContent.PARTITION_ADDON.get()), "add-ons");
        helper.assertValueEqual(data.memory(), List.<Item>of(CellContent.MEMORY_MODULE_1.get()), "memory");
        DriveBlockEntity drive = CellGameTests.drive(helper, new BlockPos(1, 1, 1));
        drive.setPriority(4);
        drive.insertCell(stack);
        helper.startSequence().thenWaitUntil(() -> CellGameTests.mounted(helper, drive, 0)).thenExecute(() -> {
            CellInventory cell = CellGameTests.mounted(helper, drive, 0);
            helper.assertValueEqual(cell.priority(), 4, "priority of the old cell in the drive");
            drive.setPriority(-2);
            helper.assertValueEqual(cell.priority(), -2, "priority after the drive's changed");
            helper.assertValueEqual(CellGameTests.insert(cell, COBBLE, 10), 10L, "the old cell takes items");
        }).thenSucceed();
    }

    /**
     * The workbench rules keep the contents whole: a module that would leave too few bytes, a chip that refuses what
     * the cell holds and taking the chip from a cell with contents are refused, leaving the stack unchanged; a cell in
     * a drive cannot be changed through a copy; adding memory first lets a module go; an emptied cell stripped of its
     * parts is its casing again.
     */
    private static void refits(GameTestHelper helper) {
        CellStorage storage = CellGameTests.storage(helper);
        MemoryModuleItem two = CellContent.MEMORY_MODULE_2.get();
        DriveBlockEntity drive = CellGameTests.drive(helper, new BlockPos(1, 1, 1));
        drive.insertCell(cell(CellContent.MEDIUM_STORAGE_CELL.get(), List.of(two, two), List.of(), CellFilter.EMPTY));
        helper.startSequence().thenWaitUntil(() -> CellGameTests.mounted(helper, drive, 0)).thenExecute(() -> {
            CellInventory mounted = CellGameTests.mounted(helper, drive, 0);
            CellGameTests.insert(mounted, COBBLE, 60_000);
            ItemStack cell = drive.removeCell();
            // A copy of the item in a drive: its contents are in use, so the other copy cannot be changed.
            drive.insertCell(cell.copy());
            helper.assertTrue(CellAssembly.putMemory(cell, 2, two, storage) == null, "changed a cell in a drive");
            drive.removeCell();
            ItemStack before = cell.copy();
            CellData data = CellGameTests.data(helper, cell);
            helper.assertValueEqual(data.usedBytes(), 60_000 / 8 + 8L, "bytes used");
            helper.assertTrue(CellAssembly.takeMemory(cell, 0, storage) == null, "a module the contents need");
            helper.assertTrue(CellAssembly.setChip(cell, CellContent.FLUID_CONTROLLER_CHIP.get(), storage) == null,
                    "a fluid chip for items");
            helper.assertTrue(CellAssembly.removeChip(cell, storage) == null, "the chip of a cell with contents");
            helper.assertTrue(ItemStack.matches(cell, before), "a refused change changed the stack");
            cell = require(helper, CellAssembly.putMemory(cell, 2, CellContent.MEMORY_MODULE_3.get(), storage),
                    "memory module III added");
            cell = require(helper, CellAssembly.takeMemory(cell, 0, storage), "module II taken after adding III");
            helper.assertValueEqual(CellGameTests.data(helper, cell).totalBytes(), 20 * KB, "capacity after both");
            helper.assertValueEqual(storage.amount(CellGameTests.data(helper, cell).id(), COBBLE), 60_000L,
                    "contents after refitting");
            drive.insertCell(cell);
        }).thenWaitUntil(() -> CellGameTests.mounted(helper, drive, 0)).thenExecute(() -> {
            CellGameTests.extract(CellGameTests.mounted(helper, drive, 0), COBBLE, Long.MAX_VALUE);
            ItemStack cell = drive.removeCell();
            cell = require(helper, CellAssembly.takeMemory(cell, 0, storage), "a module of an empty cell");
            cell = require(helper, CellAssembly.takeMemory(cell, 0, storage), "the last module");
            ItemStack casing = require(helper, CellAssembly.removeChip(cell, storage), "the chip of an empty cell");
            helper.assertTrue(casing.is(CellContent.MEDIUM_CELL_CASING.get()), "stripped cell is " + casing);
        }).thenSucceed();
    }

    /**
     * A starter cell item saved before M6b (no add-ons, no filter in its component) still loads, mounts and holds 1 KB.
     */
    private static void oldStarterCell(GameTestHelper helper) {
        JsonObject json = JsonParser.parseString("""
                {"id": "maxtech_storage:small_storage_cell", "count": 1, "components": {"maxtech_storage:cell":
                {"type": "maxtech_storage:item", "memory": ["maxtech_storage:memory_module_1"], "priority": 0,
                "used_bytes": 0, "total_bytes": 0, "kinds": 0}}}""").getAsJsonObject();
        ItemStack stack = ItemStack.CODEC
                .parse(helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE), json)
                .getOrThrow(message -> helper.assertionException(Component.literal("old cell: " + message)));
        CellData data = CellGameTests.data(helper, stack);
        helper.assertTrue(data.addons().isEmpty() && data.filter().isBlank(), "parts of an old cell " + data);
        DriveBlockEntity drive = CellGameTests.drive(helper, new BlockPos(1, 1, 1));
        drive.insertCell(stack);
        helper.startSequence().thenWaitUntil(() -> CellGameTests.mounted(helper, drive, 0)).thenExecute(() -> {
            CellInventory cell = CellGameTests.mounted(helper, drive, 0);
            helper.assertValueEqual(cell.totalBytes(), KB, "capacity");
            helper.assertValueEqual(CellGameTests.insert(cell, COBBLE, 10_000), 8128L, "plain items");
        }).thenSucceed();
    }

    private static List<Integer> slots(CellCasing casing) {
        return List.of(casing.memorySlots(), casing.addonSlots());
    }

    /** A new cell of {@code item} with these parts. */
    static ItemStack cell(StorageCellItem item, List<Item> memory, List<Item> addons, CellFilter filter) {
        ItemStack stack = new ItemStack(item);
        stack.set(ModDataComponents.CELL.get(),
                new CellData(CellData.NO_ID, CellContent.ITEM_TYPE.id(), memory, addons, filter, 0, 0, 0, 0));
        return stack;
    }

    static ItemStack require(GameTestHelper helper, @Nullable ItemStack stack, String what) {
        if (stack == null) {
            throw helper.assertionException(Component.literal("Refused: " + what));
        }
        return stack;
    }
}
