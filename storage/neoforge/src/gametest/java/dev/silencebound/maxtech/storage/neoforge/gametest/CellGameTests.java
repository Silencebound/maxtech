package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.lib.resource.KeyPalette;
import dev.silencebound.maxtech.storage.cell.CellBytes;
import dev.silencebound.maxtech.storage.cell.CellCasing;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.cell.CellData;
import dev.silencebound.maxtech.storage.cell.CellInventory;
import dev.silencebound.maxtech.storage.cell.CellStorage;
import dev.silencebound.maxtech.storage.cell.StorageCellItem;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.drive.DriveContent;
import dev.silencebound.maxtech.storage.drive.DriveItem;
import dev.silencebound.maxtech.storage.material.MaterialContent;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageProvider;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * Cells and drives (M6a area 2): byte limits with the per-kind and component overheads, fluid mB, rollbacks, contents
 * through save and load and between drives, saving only changed cells, many kinds, a copied cell id, the drive in a
 * network, its item handler, hand use, drops, tooltips and the crafting recipes.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class CellGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey STONE = ItemKey.of(Items.STONE);
    private static final StackKey WATER = FluidKey.of(Fluids.WATER);
    private static final long KB = 1024;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);
    private static final List<String> NAMES = new ArrayList<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        test("cell_byte_limits", CellGameTests::byteLimits);
        test("cell_fluid_mb", CellGameTests::fluidMb);
        test("cell_rollback_leaves_contents", CellGameTests::rollback);
        test("cell_save_and_load", CellGameTests::saveAndLoad);
        test("cell_moves_between_drives", CellGameTests::movesBetweenDrives);
        test("cell_only_changed_cells_saved", CellGameTests::onlyChangedSaved);
        test("cell_save_matches_palette_codec", CellGameTests::saveMatchesCodec);
        test("cell_many_kinds", CellGameTests::manyKinds);
        test("cell_copied_id_stays_inactive", CellGameTests::copiedId);
        test("cell_tooltip_and_name", CellGameTests::tooltipAndName);
        test("cell_recipes", CellGameTests::recipes);
        test("drive_network_totals", CellGameTests::networkTotals);
        test("drive_item_handler_takes_cells", CellGameTests::itemHandler);
        test("drive_hand_insert_and_remove", CellGameTests::handUse);
        test("drive_drops_with_cells", CellGameTests::dropsWithCells);
    }

    private CellGameTests() {
    }

    private static void test(String name, Consumer<GameTestHelper> function) {
        NAMES.add(name);
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(function));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("cells"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, STRUCTURE, 200, 0, true)));
        }
    }

    /**
     * A starter cell takes 8,128 plain items of one kind (1 KB less the 8-byte kind) and then nothing new; a second
     * cell charges 9 bytes for each plain kind and 65 for a kind with components, and a held kind fills its last byte
     * first.
     */
    private static void byteLimits(GameTestHelper helper) {
        DriveBlockEntity drive = drive(helper, new BlockPos(1, 1, 1));
        drive.insertCell(cell(CellContent.ITEM_TYPE.id(), 1));
        drive.insertCell(cell(CellContent.ITEM_TYPE.id(), 1));
        helper.startSequence().thenWaitUntil(() -> mounted(helper, drive, 1)).thenExecute(() -> {
            CellInventory full = mounted(helper, drive, 0);
            helper.assertValueEqual(insert(full, COBBLE, 10_000), 8128L, "plain items in a starter cell");
            helper.assertValueEqual(full.usedBytes(), KB, "bytes used");
            helper.assertValueEqual(insert(full, STONE, 1), 0L, "a new kind in a full cell");
            helper.assertValueEqual(DriveBlockEntity.band(drive.bands(), 0), CellBytes.BAND_FULL, "band of slot 0");

            CellInventory mixed = mounted(helper, drive, 1);
            insert(mixed, COBBLE, 1);
            insert(mixed, STONE, 1);
            helper.assertValueEqual(mixed.usedBytes(), 18L, "two plain kinds of one item");
            helper.assertValueEqual(insert(mixed, named("a"), 1), 1L, "a kind with components");
            helper.assertValueEqual(mixed.usedBytes(), 18L + 65, "bytes with a component kind");
            helper.assertValueEqual(mixed.kinds(), 3, "kinds");
            helper.assertValueEqual(insert(mixed, COBBLE, 100_000), 7 + (KB - 83) * 8, "cobblestone to the brim");
            helper.assertValueEqual(mixed.usedBytes(), KB, "bytes once full");
            helper.assertValueEqual(extract(mixed, COBBLE, 8), 8L, "extracted");
            helper.assertValueEqual(mixed.usedBytes(), KB - 1, "a byte freed");
            helper.assertValueEqual(DriveBlockEntity.band(drive.bands(), 1), CellBytes.BAND_HIGH, "band of slot 1");
            helper.assertValueEqual(DriveBlockEntity.band(drive.bands(), 2), DriveBlockEntity.BAND_EMPTY, "slot 2");
        }).thenSucceed();
    }

    /** A fluid cell holds 1,016 bytes of 8,000 mB of one fluid, takes no items, and an item cell takes no fluid. */
    private static void fluidMb(GameTestHelper helper) {
        DriveBlockEntity drive = drive(helper, new BlockPos(1, 1, 1));
        drive.insertCell(cell(CellContent.FLUID_TYPE.id(), 1));
        drive.insertCell(cell(CellContent.ITEM_TYPE.id(), 1));
        helper.startSequence().thenWaitUntil(() -> mounted(helper, drive, 1)).thenExecute(() -> {
            CellInventory fluid = mounted(helper, drive, 0);
            CellInventory items = mounted(helper, drive, 1);
            helper.assertValueEqual(insert(fluid, WATER, 10_000_000), 8_128_000L, "mB in a starter fluid cell");
            helper.assertValueEqual(fluid.usedBytes(), KB, "bytes used");
            helper.assertValueEqual(insert(fluid, COBBLE, 1), 0L, "items in a fluid cell");
            helper.assertValueEqual(insert(items, WATER, 1), 0L, "fluid in an item cell");
            helper.assertValueEqual(extract(fluid, WATER, 500), 500L, "extracted");
            helper.assertValueEqual(fluid.usedBytes(), KB, "a started byte still counts");
            helper.assertValueEqual(extract(fluid, WATER, 8000), 8000L, "extracted a byte's worth");
            helper.assertValueEqual(fluid.usedBytes(), KB - 1, "bytes after freeing one");
            helper.assertValueEqual(fluid.amount(WATER), 8_119_500L, "water left");
        }).thenSucceed();
    }

    /**
     * Nothing a rolled-back transaction did stays, neither the counts nor the bytes; a nested part rolled back inside a
     * committed root leaves only what committed, and the listener hears each key once per root commit, with its sum.
     */
    private static void rollback(GameTestHelper helper) {
        DriveBlockEntity drive = drive(helper, new BlockPos(1, 1, 1));
        drive.insertCell(cell(CellContent.ITEM_TYPE.id(), 1));
        helper.startSequence().thenWaitUntil(() -> mounted(helper, drive, 0)).thenExecute(() -> {
            CellInventory cell = mounted(helper, drive, 0);
            List<String> heard = new ArrayList<>();
            cell.setListener((key, delta) -> heard.add(key + " " + delta));
            try (Transaction root = Transaction.openRoot()) {
                helper.assertValueEqual(cell.insert(COBBLE, 50, root), 50L, "simulated insert");
                cell.insert(named("b"), 1, root);
            }
            helper.assertValueEqual(cell.amount(COBBLE), 0L, "cobblestone after a rollback");
            helper.assertValueEqual(cell.usedBytes(), 0L, "bytes after a rollback");
            helper.assertValueEqual(cell.kinds(), 0, "kinds after a rollback");
            helper.assertTrue(heard.isEmpty(), "heard of a rolled-back change: " + heard);
            try (Transaction root = Transaction.openRoot()) {
                try (Transaction nested = Transaction.open(root)) {
                    cell.insert(COBBLE, 30, nested);
                    nested.commit();
                }
                try (Transaction nested = Transaction.open(root)) {
                    cell.insert(COBBLE, 20, nested);
                    cell.insert(STONE, 20, nested);
                }
                cell.insert(COBBLE, 5, root);
                cell.extract(COBBLE, 2, root);
                root.commit();
            }
            helper.assertValueEqual(cell.amount(COBBLE), 33L, "cobblestone after the commit");
            helper.assertValueEqual(cell.amount(STONE), 0L, "stone of the rolled-back part");
            helper.assertValueEqual(cell.usedBytes(), CellBytes.bytes(33, 8, 8), "bytes after the commit");
            helper.assertValueEqual(heard, List.of(COBBLE + " 33"), "what the listener heard");
            try (Transaction root = Transaction.openRoot()) {
                try (Transaction nested = Transaction.open(root)) {
                    cell.extract(COBBLE, 33, nested);
                    nested.commit();
                }
            }
            helper.assertValueEqual(cell.amount(COBBLE), 33L, "cobblestone after an outer rollback");
            helper.assertValueEqual(heard.size(), 1, "reports");
        }).thenSucceed();
    }

    /**
     * The level's cell storage written with the save's codec and read back holds the same contents per cell id, items
     * with components and fluids included, and hands out ids above the loaded ones.
     */
    private static void saveAndLoad(GameTestHelper helper) {
        DriveBlockEntity drive = drive(helper, new BlockPos(1, 1, 1));
        drive.insertCell(cell(CellContent.ITEM_TYPE.id(), 1));
        drive.insertCell(cell(CellContent.FLUID_TYPE.id(), 1));
        helper.startSequence().thenWaitUntil(() -> mounted(helper, drive, 1)).thenExecute(() -> {
            CellInventory items = mounted(helper, drive, 0);
            CellInventory fluid = mounted(helper, drive, 1);
            insert(items, COBBLE, 100);
            insert(items, named("c"), 2);
            insert(fluid, WATER, 5000);
            CellStorage storage = storage(helper);
            helper.assertTrue(storage.isDirty(), "storage not dirty after a change");
            RegistryOps<Tag> ops = ops(helper);
            Tag saved = CellStorage.CODEC.encodeStart(ops, storage).getOrThrow();
            CellStorage loaded = CellStorage.CODEC.parse(ops, saved).getOrThrow();
            helper.assertValueEqual(loaded.amount(items.id(), COBBLE), 100L, "cobblestone after loading");
            helper.assertValueEqual(loaded.amount(items.id(), named("c")), 2L, "named diamonds after loading");
            helper.assertValueEqual(loaded.kinds(items.id()), 2, "kinds after loading");
            helper.assertValueEqual(loaded.amount(fluid.id(), WATER), 5000L, "water after loading");
            helper.assertTrue(loaded.newId() > Math.max(items.id(), fluid.id()), "new id not above the loaded ones");
            Tag again = CellStorage.CODEC.encodeStart(ops, loaded).getOrThrow();
            CellStorage reloaded = CellStorage.CODEC.parse(ops, again).getOrThrow();
            helper.assertValueEqual(reloaded.amount(items.id(), named("c")), 2L, "after a save of the loaded data");
        }).thenSucceed();
    }

    /** A cell taken out of one drive carries its id and stats, and in another drive holds what it held. */
    private static void movesBetweenDrives(GameTestHelper helper) {
        DriveBlockEntity first = drive(helper, new BlockPos(1, 1, 1));
        DriveBlockEntity second = drive(helper, new BlockPos(5, 1, 1));
        first.insertCell(cell(CellContent.ITEM_TYPE.id(), 1));
        long[] id = new long[1];
        helper.startSequence().thenWaitUntil(() -> mounted(helper, first, 0)).thenExecute(() -> {
            CellInventory cell = mounted(helper, first, 0);
            insert(cell, COBBLE, 64);
            id[0] = cell.id();
            ItemStack moved = first.removeCell();
            helper.assertTrue(first.providers().isEmpty(), "the first drive still offers the cell");
            CellData data = data(helper, moved);
            helper.assertValueEqual(data.id(), id[0], "id on the item");
            helper.assertValueEqual(data.usedBytes(), 16L, "cached bytes");
            helper.assertValueEqual(data.kinds(), 1, "cached kinds");
            helper.assertTrue(!storage(helper).isMounted(id[0]), "still mounted after leaving its drive");
            helper.assertTrue(second.insertCell(moved), "the second drive refused the cell");
            helper.assertTrue(moved.isEmpty(), "the cell stayed in the hand stack");
        }).thenWaitUntil(() -> mounted(helper, second, 0)).thenExecute(() -> {
            CellInventory cell = mounted(helper, second, 0);
            helper.assertValueEqual(cell.id(), id[0], "id in the second drive");
            helper.assertValueEqual(cell.amount(COBBLE), 64L, "contents in the second drive");
            helper.assertValueEqual(second.providers().size(), 1, "providers of the second drive");
        }).thenSucceed();
    }

    /**
     * A save encodes only cells that changed since the last one; the others reuse their saved tag. A cell left empty
     * when it leaves its drive is forgotten.
     */
    private static void onlyChangedSaved(GameTestHelper helper) {
        DriveBlockEntity drive = drive(helper, new BlockPos(1, 1, 1));
        for (int i = 0; i < 3; i++) {
            drive.insertCell(cell(CellContent.ITEM_TYPE.id(), 1));
        }
        helper.startSequence().thenWaitUntil(() -> mounted(helper, drive, 2)).thenExecute(() -> {
            for (int slot = 0; slot < 3; slot++) {
                insert(mounted(helper, drive, slot), COBBLE, 10 + slot);
            }
            CellStorage storage = storage(helper);
            RegistryOps<Tag> ops = ops(helper);
            CellStorage.CODEC.encodeStart(ops, storage).getOrThrow();
            helper.assertTrue(storage.lastEncodedCells() >= 3, "the changed cells were not encoded");
            CellStorage.CODEC.encodeStart(ops, storage).getOrThrow();
            helper.assertValueEqual(storage.lastEncodedCells(), 0, "cells encoded without a change");
            insert(mounted(helper, drive, 1), STONE, 1);
            Tag saved = CellStorage.CODEC.encodeStart(ops, storage).getOrThrow();
            helper.assertValueEqual(storage.lastEncodedCells(), 1, "cells encoded after one changed");
            CellStorage loaded = CellStorage.CODEC.parse(ops, saved).getOrThrow();
            for (int slot = 0; slot < 3; slot++) {
                long id = mounted(helper, drive, slot).id();
                helper.assertValueEqual(loaded.amount(id, COBBLE), 10L + slot, "reused contents of slot " + slot);
            }
            helper.assertValueEqual(loaded.amount(mounted(helper, drive, 1).id(), STONE), 1L, "the change");

            CellInventory emptied = mounted(helper, drive, 0);
            long held = mounted(helper, drive, 1).id();
            extract(emptied, COBBLE, 10);
            drive.removeCell();
            drive.removeCell();
            drive.removeCell();
            helper.assertTrue(!storage.contains(emptied.id()), "an empty cell out of its drive is still stored");
            helper.assertTrue(storage.contains(held), "a cell holding items was forgotten");
        }).thenSucceed();
    }

    /**
     * The save writes each cell's contents exactly as the palette codec writes them, for keys shared by several cells
     * (one with components) and for a cell keeping a key of a removed mod, and every cell loads its shared keys.
     */
    private static void saveMatchesCodec(GameTestHelper helper) {
        RegistryOps<Tag> ops = ops(helper);
        CellStorage storage = new CellStorage();
        CellData data = CellData.of(CellContent.ITEM_TYPE.id(),
                Collections.nCopies(4, CellContent.MEMORY_MODULE_1.get()));
        StackKey named = named("shared");
        long[] ids = new long[3];
        for (int i = 0; i < ids.length; i++) {
            CellInventory cell = storage.mount(storage.newId(), CellContent.ITEM_TYPE.get(), CellCasing.SMALL, data,
                    () -> {
                    });
            if (cell == null) {
                throw helper.assertionException(Component.literal("cell " + i + " not mounted"));
            }
            ids[i] = cell.id();
            insert(cell, COBBLE, 10 + i);
            insert(cell, named, 1 + i);
            if (i == 2) {
                insert(cell, STONE, 5);
            }
        }
        long keptId = storage.newId();
        CompoundTag keptContents = (CompoundTag) KeyPalette.codec(StackKey.CODEC)
                .encodeStart(ops, new KeyPalette<>(List.of(COBBLE), new int[]{0}, new long[]{7})).getOrThrow();
        KeptEntriesGameTests.addUnknown(keptContents);
        helper.assertTrue(storage.restoreForTests(keptId, keptContents), "the cell with an unknown key not restored");

        CompoundTag saved = (CompoundTag) CellStorage.CODEC.encodeStart(ops, storage).getOrThrow();
        helper.assertValueEqual(storage.lastEncodedCells(), 4, "cells encoded");
        Codec<KeyPalette<Dynamic<?>>> raw = KeyPalette.codec(Codec.PASSTHROUGH);
        ListTag entries = saved.getListOrEmpty("cells");
        helper.assertValueEqual(entries.size(), 4, "saved cells");
        for (Tag entry : entries) {
            Tag contents = ((CompoundTag) entry).get("contents");
            if (contents == null) {
                throw helper.assertionException(Component.literal("a saved cell without contents"));
            }
            KeyPalette<Dynamic<?>> palette = raw.parse(ops, contents).getOrThrow();
            helper.assertValueEqual(raw.encodeStart(ops, palette).getOrThrow(), contents,
                    "contents as the palette codec writes them");
        }

        CellStorage loaded = CellStorage.CODEC.parse(ops, saved).getOrThrow();
        for (int i = 0; i < ids.length; i++) {
            helper.assertValueEqual(loaded.amount(ids[i], COBBLE), 10L + i, "cobblestone in cell " + i);
            helper.assertValueEqual(loaded.amount(ids[i], named), 1L + i, "named diamonds in cell " + i);
            helper.assertValueEqual(loaded.kinds(ids[i]), i == 2 ? 3 : 2, "kinds in cell " + i);
        }
        helper.assertValueEqual(loaded.amount(keptId, COBBLE), 7L, "cobblestone next to the unknown key");
        helper.assertValueEqual(loaded.keptKinds(keptId), 1, "kept kinds after the save");
        CompoundTag again = (CompoundTag) CellStorage.CODEC.encodeStart(ops, loaded).getOrThrow();
        helper.assertValueEqual(again, saved, "a save of the loaded storage");
        helper.assertValueEqual(CellStorage.CODEC.encodeStart(ops, loaded).getOrThrow(), saved,
                "an unchanged second save");
        loaded.setHome(ids[0], "minecraft:overworld", 42);
        CompoundTag homed = (CompoundTag) CellStorage.CODEC.encodeStart(ops, loaded).getOrThrow();
        CellStorage withHome = CellStorage.CODEC.parse(ops, homed).getOrThrow();
        helper.assertValueEqual(withHome.homeDimension(ids[0]), "minecraft:overworld", "home after a save");
        helper.assertValueEqual(withHome.homePos(ids[0]), 42L, "home position after a save");
        helper.succeed();
    }

    /**
     * A cell of 32 memory modules in a powered network holds 1,500 plain kinds and 100 named ones, the network's index
     * counts all of them, and they survive a save.
     */
    private static void manyKinds(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        DriveBlockEntity drive = drive(helper, NetworkGameTests.at(2));
        drive.insertCell(cell(CellContent.ITEM_TYPE.id(), 32));
        List<StackKey> keys = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (!new ItemStack(item).isEmpty() && keys.size() < 1500) {
                keys.add(ItemKey.of(item));
            }
        }
        for (int i = 0; i < 100; i++) {
            keys.add(named("kind " + i));
        }
        helper.startSequence().thenWaitUntil(() -> {
            mounted(helper, drive, 0);
            helper.assertTrue(drive.network() != null, "no network");
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, controller, 10_000);
            NetworkState network = NetworkGameTests.state(helper, controller);
            for (StackKey key : keys) {
                helper.assertValueEqual(NetworkGameTests.insert(network, key, 1), 1L, "inserted " + key);
            }
            CellInventory cell = mounted(helper, drive, 0);
            helper.assertValueEqual(cell.kinds(), keys.size(), "kinds in the cell");
            helper.assertValueEqual(cell.usedBytes(), 1500L * 9 + 100L * 65, "bytes of the kinds");
            helper.assertValueEqual(network.contents().size(), keys.size(), "kinds in the network");
            RegistryOps<Tag> ops = ops(helper);
            CellStorage loaded = CellStorage.CODEC
                    .parse(ops, CellStorage.CODEC.encodeStart(ops, storage(helper)).getOrThrow()).getOrThrow();
            helper.assertValueEqual(loaded.kinds(cell.id()), keys.size(), "kinds after loading");
            helper.assertValueEqual(loaded.amount(cell.id(), keys.get(keys.size() - 1)), 1L, "last kind");
        }).thenSucceed();
    }

    /** A copy of a cell already in a drive (same id) stays inactive in a second drive, so nothing is counted twice. */
    private static void copiedId(GameTestHelper helper) {
        DriveBlockEntity first = drive(helper, new BlockPos(1, 1, 1));
        DriveBlockEntity second = drive(helper, new BlockPos(5, 1, 1));
        first.insertCell(cell(CellContent.ITEM_TYPE.id(), 1));
        helper.startSequence().thenWaitUntil(() -> mounted(helper, first, 0)).thenExecute(() -> {
            CellInventory original = mounted(helper, first, 0);
            insert(original, COBBLE, 5);
            ItemStack copy = cell(CellContent.ITEM_TYPE.id(), 1);
            copy.set(ModDataComponents.CELL.get(), data(helper, copy).withId(original.id()));
            second.insertCell(copy);
        }).thenIdle(2).thenExecute(() -> {
            helper.assertTrue(second.cell(0) == null, "the copy was mounted");
            helper.assertTrue(second.providers().isEmpty(), "the copy is a provider");
            helper.assertValueEqual(DriveBlockEntity.band(second.bands(), 0), DriveBlockEntity.BAND_INACTIVE, "band");
            helper.assertValueEqual(mounted(helper, first, 0).amount(COBBLE), 5L, "the original's contents");
        }).thenSucceed();
    }

    /** The cell's name carries its type and its tooltip the bytes and kinds; a new cell shows its capacity. */
    private static void tooltipAndName(GameTestHelper helper) {
        DriveBlockEntity drive = drive(helper, new BlockPos(1, 1, 1));
        ItemStack fresh = cell(CellContent.FLUID_TYPE.id(), 1);
        List<Object> freshBytes = args(helper, tooltip(fresh), StorageCellItem.BYTES_KEY);
        helper.assertValueEqual(freshBytes, List.<Object>of(0L, KB), "bytes of a new cell");
        Component name = fresh.getHoverName();
        helper.assertTrue(name.getContents() instanceof TranslatableContents contents && contents.getArgs().length == 1
                && contents.getArgs()[0] instanceof Component type
                && type.getContents() instanceof TranslatableContents typeKey
                && typeKey.getKey().equals("cell_type.maxtech_storage.fluid"), "name " + name);
        drive.insertCell(fresh);
        helper.startSequence().thenWaitUntil(() -> mounted(helper, drive, 0)).thenExecute(() -> {
            insert(mounted(helper, drive, 0), WATER, 16_000);
            ItemStack out = drive.removeCell();
            List<Component> lines = tooltip(out);
            helper.assertValueEqual(args(helper, lines, StorageCellItem.BYTES_KEY), List.<Object>of(10L, KB),
                    "bytes in the tooltip");
            helper.assertValueEqual(args(helper, lines, StorageCellItem.KINDS_KEY), List.<Object>of(1), "kinds");
        }).thenSucceed();
    }

    /** The starter cell, chip, memory module and drive recipes craft from Storage's parts, in the vanilla Crafter. */
    private static void recipes(GameTestHelper helper) {
        ItemStack plate = new ItemStack(MaterialContent.CASING_PLATE.get());
        ItemStack glass = new ItemStack(MaterialContent.DISPLAY_GLASS.get());
        ItemStack iron = new ItemStack(Items.IRON_INGOT);
        ItemStack memory = new ItemStack(CellContent.MEMORY_MODULE_1.get());
        ItemStack fluidChip = new ItemStack(CellContent.FLUID_CONTROLLER_CHIP.get());
        ItemStack itemChip = new ItemStack(CellContent.ITEM_CONTROLLER_CHIP.get());
        ItemStack fluidCell = craft(helper, 3, 3,
                List.of(plate, glass, plate, iron, memory, iron, plate, fluidChip, plate));
        helper.assertTrue(fluidCell.is(CellContent.SMALL_STORAGE_CELL.get()), "crafted " + fluidCell);
        helper.assertValueEqual(data(helper, fluidCell), CellContent.starter(CellContent.FLUID_TYPE.id()), "data");
        ItemStack itemCell = craft(helper, 3, 3,
                List.of(plate, glass, plate, iron, memory, iron, plate, itemChip, plate));
        helper.assertValueEqual(data(helper, itemCell), CellContent.starter(CellContent.ITEM_TYPE.id()), "item data");

        ItemStack logic = new ItemStack(MaterialContent.BASIC_LOGIC_PROCESSOR.get());
        ItemStack io = new ItemStack(MaterialContent.BASIC_IO_PROCESSOR.get());
        ItemStack board = new ItemStack(MaterialContent.CIRCUIT_BOARD.get());
        helper.assertTrue(craft(helper, 2, 2, List.of(logic, io, board, new ItemStack(Items.CHEST)))
                .is(CellContent.ITEM_CONTROLLER_CHIP.get()), "item chip");
        helper.assertTrue(craft(helper, 2, 2, List.of(logic, io, board, new ItemStack(Items.BUCKET)))
                .is(CellContent.FLUID_CONTROLLER_CHIP.get()), "fluid chip");
        ItemStack memoryProcessor = new ItemStack(MaterialContent.BASIC_MEMORY_PROCESSOR.get());
        ItemStack wire = new ItemStack(MaterialContent.INSULATED_COPPER_WIRE.get());
        helper.assertTrue(craft(helper, 3, 2,
                List.of(memoryProcessor, new ItemStack(Items.REDSTONE), memoryProcessor, wire, board, wire))
                .is(CellContent.MEMORY_MODULE_1.get()), "memory module");
        helper.assertTrue(craft(helper, 3, 3,
                List.of(plate, io, plate, wire, new ItemStack(Items.CHEST), wire, plate, logic, plate))
                .is(DriveContent.DRIVE.item().get()), "drive");
        helper.succeed();
    }

    /**
     * A drive in a powered network adds its cells to the index: a player insert fills the first cell and spills into
     * the second, taking a cell out drops its share from the totals and putting it back restores them.
     */
    private static void networkTotals(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        DriveBlockEntity drive = drive(helper, NetworkGameTests.at(2));
        drive.insertCell(cell(CellContent.ITEM_TYPE.id(), 1));
        drive.insertCell(cell(CellContent.ITEM_TYPE.id(), 1));
        helper.startSequence().thenWaitUntil(() -> {
            mounted(helper, drive, 1);
            helper.assertTrue(drive.network() != null, "no network");
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, controller, 10_000);
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 9000), 9000L, "inserted");
            helper.assertValueEqual(network.contents().amount(COBBLE), 9000L, "total");
            long first = mounted(helper, drive, 0).amount(COBBLE);
            long second = mounted(helper, drive, 1).amount(COBBLE);
            helper.assertTrue(first + second == 9000 && Math.max(first, second) == 8128, first + " and " + second);
            ItemStack out = drive.removeCell();
            helper.assertValueEqual(network.contents().amount(COBBLE), first, "total without the second cell");
            drive.insertCell(out);
            helper.assertValueEqual(network.contents().amount(COBBLE), 9000L, "total with it back");
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(network.extract(COBBLE, 100, transaction), 100L, "extracted");
                transaction.commit();
            }
            helper.assertValueEqual(network.contents().amount(COBBLE), 8900L, "total after extracting");
        }).thenSucceed();
    }

    /**
     * The drive's item capability takes cells only, one a slot; a rolled-back insert mounts nothing, a committed one
     * mounts the cell with a new id, and extracting gives the cell with its current stats.
     */
    private static void itemHandler(GameTestHelper helper) {
        DriveBlockEntity drive = drive(helper, new BlockPos(1, 1, 1));
        helper.startSequence().thenIdle(2).thenExecute(() -> {
            ResourceHandler<ItemResource> handler = helper.getLevel().getCapability(Capabilities.Item.BLOCK,
                    helper.absolutePos(new BlockPos(1, 1, 1)), null);
            if (handler == null) {
                throw helper.assertionException(Component.literal("no item handler on the drive"));
            }
            ItemResource cell = ItemResource.of(cell(CellContent.ITEM_TYPE.id(), 1));
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(handler.insert(ItemResource.of(Items.DIRT), 1, transaction), 0, "dirt");
                helper.assertValueEqual(handler.insert(cell, 3, transaction), 3, "three cells in three slots");
            }
            helper.assertValueEqual(drive.cellCount(), 0, "cells after a rollback");
            try (Transaction transaction = Transaction.openRoot()) {
                handler.insert(cell, 1, transaction);
                transaction.commit();
            }
            CellInventory mounted = mounted(helper, drive, 0);
            helper.assertTrue(mounted.id() != CellData.NO_ID, "no id");
            insert(mounted, COBBLE, 17);
            ItemResource inSlot = handler.getResource(0);
            ItemStack out;
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(handler.extract(0, inSlot, 1, transaction), 1, "extracted");
                transaction.commit();
                out = inSlot.toStack(1);
            }
            helper.assertValueEqual(data(helper, out).usedBytes(), 11L, "stats of the extracted cell");
            helper.assertTrue(drive.cell(0) == null && drive.providers().isEmpty(), "the cell is still mounted");
        }).thenSucceed();
    }

    /**
     * Using the drive with a cell puts it in and with an empty hand takes it out; a player without access to the
     * owner's drive can do neither.
     */
    private static void handUse(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        DriveBlockEntity drive = drive(helper, pos);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, cell(CellContent.ITEM_TYPE.id(), 1));
        helper.useBlock(pos, player);
        helper.assertTrue(player.getMainHandItem().isEmpty(), "the cell stayed in the hand");
        helper.assertValueEqual(drive.cellCount(), 1, "cells after using the drive with one");
        // An empty hand opens the screen and takes nothing; sneaking takes the last cell out.
        helper.useBlock(pos, player);
        helper.assertValueEqual(drive.cellCount(), 1, "cells after an empty-handed use");
        player.setShiftKeyDown(true);
        helper.useBlock(pos, player);
        player.setShiftKeyDown(false);
        helper.assertTrue(player.getMainHandItem().getItem() instanceof StorageCellItem, "no cell in the hand");
        helper.assertValueEqual(drive.cellCount(), 0, "cells after taking it out");

        ServerLevel level = helper.getLevel();
        drive.setOwner(OwnerRecord.of(NetworkGameTests.player(level, "drive_owner")));
        helper.useBlock(pos, player);
        helper.assertValueEqual(drive.cellCount(), 0, "a stranger put a cell in");
        helper.assertTrue(!player.getMainHandItem().isEmpty(), "a stranger lost the cell");
        helper.succeed();
    }

    /** A broken drive drops itself with its cells; placed again it mounts them with their contents. */
    private static void dropsWithCells(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        DriveBlockEntity drive = drive(helper, pos);
        drive.insertCell(cell(CellContent.ITEM_TYPE.id(), 1));
        long[] id = new long[1];
        @Nullable
        DriveBlockEntity[] placed = new DriveBlockEntity[1];
        helper.startSequence().thenWaitUntil(() -> mounted(helper, drive, 0)).thenExecute(() -> {
            CellInventory cell = mounted(helper, drive, 0);
            insert(cell, COBBLE, 5);
            id[0] = cell.id();
            ServerLevel level = helper.getLevel();
            level.destroyBlock(helper.absolutePos(pos), true);
            List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class,
                    new AABB(helper.absolutePos(pos)).inflate(2));
            ItemStack item = ItemStack.EMPTY;
            for (ItemEntity drop : drops) {
                if (drop.getItem().getItem() instanceof DriveItem) {
                    item = drop.getItem().copy();
                }
                drop.discard();
            }
            helper.assertValueEqual(DriveItem.cells(item), 1, "cells in the dropped drive");
            helper.assertTrue(!storage(helper).isMounted(id[0]), "mounted after the drive broke");
            List<Component> lines = tooltip(item);
            helper.assertValueEqual(args(helper, lines, DriveItem.CELLS_KEY), List.<Object>of(1), "drive tooltip");
            TypedEntityData<BlockEntityType<?>> data = item.get(DataComponents.BLOCK_ENTITY_DATA);
            if (data == null) {
                throw helper.assertionException(Component.literal("no block entity data on the drive item"));
            }
            DriveBlockEntity again = drive(helper, pos);
            placed[0] = again;
            data.loadInto(again, level.registryAccess());
        }).thenWaitUntil(() -> mounted(helper, placed[0], 0)).thenExecute(() -> {
            CellInventory cell = mounted(helper, placed[0], 0);
            helper.assertValueEqual(cell.id(), id[0], "id after placing");
            helper.assertValueEqual(cell.amount(COBBLE), 5L, "contents after placing");
        }).thenSucceed();
    }

    static DriveBlockEntity drive(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, DriveContent.DRIVE.get());
        return helper.getBlockEntity(pos, DriveBlockEntity.class);
    }

    /** A new small cell of {@code type} with {@code modules} memory modules I. */
    static ItemStack cell(Identifier type, int modules) {
        ItemStack stack = new ItemStack(CellContent.SMALL_STORAGE_CELL.get());
        stack.set(ModDataComponents.CELL.get(),
                CellData.of(type, Collections.nCopies(modules, CellContent.MEMORY_MODULE_1.get())));
        return stack;
    }

    /** The mounted cell in {@code slot}; fails the test (to be retried) while there is none. */
    static CellInventory mounted(GameTestHelper helper, @Nullable DriveBlockEntity drive, int slot) {
        CellInventory cell = drive == null ? null : drive.cell(slot);
        if (cell == null) {
            throw helper.assertionException(Component.literal("No cell mounted in slot " + slot));
        }
        return cell;
    }

    static CellData data(GameTestHelper helper, ItemStack stack) {
        CellData data = StorageCellItem.data(stack);
        if (data == null) {
            throw helper.assertionException(Component.literal("No cell data on " + stack));
        }
        return data;
    }

    static ItemKey named(String name) {
        ItemStack stack = new ItemStack(Items.DIAMOND);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return ItemKey.of(stack);
    }

    static long insert(StorageProvider provider, StackKey key, long amount) {
        try (Transaction transaction = Transaction.openRoot()) {
            long moved = provider.insert(key, amount, transaction);
            transaction.commit();
            return moved;
        }
    }

    static long extract(StorageProvider provider, StackKey key, long amount) {
        try (Transaction transaction = Transaction.openRoot()) {
            long moved = provider.extract(key, amount, transaction);
            transaction.commit();
            return moved;
        }
    }

    static CellStorage storage(GameTestHelper helper) {
        return CellStorage.get(helper.getLevel().getServer());
    }

    static RegistryOps<Tag> ops(GameTestHelper helper) {
        return helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
    }

    @SuppressWarnings("deprecation") // the override hook is deprecated for callers; tests call it like the tooltip
    private static List<Component> tooltip(ItemStack stack) {
        List<Component> lines = new ArrayList<>();
        stack.getItem().appendHoverText(stack, Item.TooltipContext.EMPTY, TooltipDisplay.DEFAULT, lines::add,
                TooltipFlag.NORMAL);
        return lines;
    }

    private static List<Object> args(GameTestHelper helper, List<Component> lines, String key) {
        for (Component line : lines) {
            if (line.getContents() instanceof TranslatableContents contents && contents.getKey().equals(key)) {
                return List.of(contents.getArgs());
            }
        }
        throw helper.assertionException(Component.literal("No tooltip line " + key + " in " + lines));
    }

    private static ItemStack craft(GameTestHelper helper, int width, int height, List<ItemStack> grid) {
        CraftingInput input = CraftingInput.of(width, height, new ArrayList<>(grid));
        Optional<RecipeHolder<CraftingRecipe>> recipe = CrafterBlock.getPotentialResults(helper.getLevel(), input);
        if (recipe.isEmpty()) {
            throw helper.assertionException(Component.literal("No crafting recipe for " + grid));
        }
        return recipe.get().value().assemble(input);
    }
}
