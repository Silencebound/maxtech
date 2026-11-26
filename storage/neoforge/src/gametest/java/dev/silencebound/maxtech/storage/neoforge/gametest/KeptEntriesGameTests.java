package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.bridge.BridgeBlockEntity;
import dev.silencebound.maxtech.storage.bridge.BridgeContent;
import dev.silencebound.maxtech.storage.bulk.BulkContent;
import dev.silencebound.maxtech.storage.bulk.BulkDriveBlockEntity;
import dev.silencebound.maxtech.storage.cell.CellBytes;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.cell.CellData;
import dev.silencebound.maxtech.storage.cell.CellFilter;
import dev.silencebound.maxtech.storage.cell.CellInventory;
import dev.silencebound.maxtech.storage.cell.CellStorage;
import dev.silencebound.maxtech.storage.cell.CellType;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.controller.recovery.RecoveryBin;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJobs;
import dev.silencebound.maxtech.storage.crafting.job.CraftingRuns;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlockEntity;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.mail.MailData;
import dev.silencebound.maxtech.storage.save.KeptList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Saved data naming an item of a removed mod: the unknown entry is kept as saved and written back unchanged, while
 * everything else stays usable (cells, bulk drives, kept lists such as recovery rows and crafting jobs).
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class KeptEntriesGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    /** An item id no mod registers. */
    private static final String MISSING = "maxtech_missing:gizmo";
    static final long KEPT_COUNT = 640;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);
    private static final List<String> NAMES = new ArrayList<>();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        test("kept_cell_unknown_key", KeptEntriesGameTests::cellUnknownKey);
        test("kept_cell_only_unknown_stays", KeptEntriesGameTests::cellOnlyUnknown);
        test("kept_list_round_trip", KeptEntriesGameTests::listRoundTrip);
        test("kept_bulk_drive_contents", KeptEntriesGameTests::bulkDrive);
        test("kept_filter_and_memory", KeptEntriesGameTests::filterAndMemory);
        test("kept_block_data", KeptEntriesGameTests::blockData);
    }

    private KeptEntriesGameTests() {
    }

    private static void test(String name, Consumer<GameTestHelper> function) {
        NAMES.add(name);
        FUNCTIONS.register(name, () -> StorageTestEnvironment.clearing(function));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("kept"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            event.registerTest(MaxtechStorage.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechStorage.id(name)),
                            new TestData<>(environment, STRUCTURE, 200, 0, true)));
        }
    }

    /**
     * A cell whose save holds cobblestone, named diamonds and a key of a removed mod loads with the known items
     * reachable and extractable, the unknown one kept: in no view, taking its bytes and its kind, and written back
     * unchanged, also after the cell changed (save, load, save gives identical data).
     */
    private static void cellUnknownKey(GameTestHelper helper) {
        DriveBlockEntity drive = CellGameTests.drive(helper, new BlockPos(1, 1, 1));
        drive.insertCell(CellGameTests.cell(CellContent.ITEM_TYPE.id(), 1));
        StackKey named = CellGameTests.named("kept");
        helper.startSequence().thenWaitUntil(() -> CellGameTests.mounted(helper, drive, 0)).thenExecute(() -> {
            CellInventory original = CellGameTests.mounted(helper, drive, 0);
            CellGameTests.insert(original, COBBLE, 100);
            CellGameTests.insert(original, named, 2);
            long id = original.id();
            RegistryOps<Tag> ops = CellGameTests.ops(helper);
            CompoundTag saved = (CompoundTag) CellStorage.CODEC.encodeStart(ops, CellGameTests.storage(helper))
                    .getOrThrow();
            CompoundTag contents = contents(helper, saved, id);
            addUnknown(contents);
            CompoundTag withUnknown = contents.copy();

            CellStorage loaded = CellStorage.CODEC.parse(ops, saved).getOrThrow();
            helper.assertValueEqual(loaded.amount(id, COBBLE), 100L, "cobblestone next to an unknown key");
            helper.assertValueEqual(loaded.amount(id, named), 2L, "named diamonds next to an unknown key");
            helper.assertValueEqual(loaded.keptKinds(id), 1, "kept kinds");
            helper.assertValueEqual(loaded.kinds(id), 3, "kinds, kept included");
            CompoundTag unchanged = (CompoundTag) CellStorage.CODEC.encodeStart(ops, loaded).getOrThrow();
            helper.assertValueEqual(contents(helper, unchanged, id), withUnknown, "contents saved back unchanged");

            CellType type = CellContent.ITEM_TYPE.get();
            CellData data = CellData
                    .of(CellContent.ITEM_TYPE.id(), Collections.nCopies(1, CellContent.MEMORY_MODULE_1.get()))
                    .withId(id);
            CellInventory cell = loaded.mount(id, type, CellContent.SMALL_STORAGE_CELL.get().casing(), data, () -> {
            });
            if (cell == null) {
                throw helper.assertionException(Component.literal("a cell with a kept key did not mount"));
            }
            long keptBytes = CellBytes.bytes(KEPT_COUNT, StorageServerConfig.CELL_KIND_BYTES.getAsLong(),
                    type.unitsPerByte(StackKey.Kind.ITEM));
            helper.assertValueEqual(cell.usedBytes(), original.usedBytes() + keptBytes, "bytes, kept included");
            helper.assertValueEqual(loaded.requiredBytes(id, type), cell.usedBytes(), "required bytes");
            helper.assertValueEqual(cell.kinds(), 3, "mounted kinds, kept included");
            helper.assertValueEqual(cell.keptKinds(), 1, "mounted kept kinds");
            List<StackKey> shown = new ArrayList<>();
            cell.forEachStored((key, amount) -> shown.add(key));
            helper.assertValueEqual(shown.size(), 2, "keys in views");
            helper.assertValueEqual(CellGameTests.extract(cell, COBBLE, 100), 100L, "cobblestone extracted");
            helper.assertValueEqual(cell.usedBytes(), keptBytes + bytesOf(named, 2), "bytes after extracting");

            CompoundTag changed = (CompoundTag) CellStorage.CODEC.encodeStart(ops, loaded).getOrThrow();
            CompoundTag changedContents = contents(helper, changed, id);
            helper.assertTrue(changedContents.getListOrEmpty("keys").contains(unknownKey()),
                    "the unknown key is saved after a change");
            CellStorage reloaded = CellStorage.CODEC.parse(ops, changed).getOrThrow();
            helper.assertValueEqual(reloaded.amount(id, COBBLE), 0L, "cobblestone after the reload");
            helper.assertValueEqual(reloaded.keptKinds(id), 1, "kept kinds after the reload");
            CompoundTag again = (CompoundTag) CellStorage.CODEC.encodeStart(ops, reloaded).getOrThrow();
            helper.assertValueEqual(contents(helper, again, id), changedContents, "save, load, save");
            CellStorage last = CellStorage.CODEC.parse(ops, again).getOrThrow();
            long count = 0;
            // Re-encode once more from the loaded counts (not the reused tag) by changing the cell back and forth.
            CellInventory remount = last.mount(id, type, CellContent.SMALL_STORAGE_CELL.get().casing(), data, () -> {
            });
            if (remount == null) {
                throw helper.assertionException(Component.literal("the reloaded cell did not mount"));
            }
            CellGameTests.insert(remount, COBBLE, 1);
            CellGameTests.extract(remount, COBBLE, 1);
            CompoundTag reencoded = (CompoundTag) CellStorage.CODEC.encodeStart(ops, last).getOrThrow();
            helper.assertTrue(last.lastEncodedCells() > 0, "the cell was not encoded again");
            CompoundTag reencodedContents = contents(helper, reencoded, id);
            ListTag keys = reencodedContents.getListOrEmpty("keys");
            int index = keys.indexOf(unknownKey());
            helper.assertTrue(index >= 0, "the unknown key after a re-encode");
            int[] indices = reencodedContents.getIntArray("indices").orElseThrow();
            long[] counts = reencodedContents.getLongArray("counts").orElseThrow();
            for (int i = 0; i < indices.length; i++) {
                if (indices[i] == index) {
                    count += counts[i];
                }
            }
            helper.assertValueEqual(count, KEPT_COUNT, "the unknown key's count after a re-encode");
        }).thenSucceed();
    }

    private static long bytesOf(StackKey key, long count) {
        long overhead = key.hasComponents()
                ? StorageServerConfig.CELL_COMPONENT_KIND_BYTES.getAsLong()
                : StorageServerConfig.CELL_KIND_BYTES.getAsLong();
        return CellBytes.bytes(count, overhead, CellContent.ITEM_TYPE.get().unitsPerByte(StackKey.Kind.ITEM));
    }

    /**
     * A cell left holding only a kept key is not forgotten when it leaves its drive, and its destroyed item hands out
     * the known items but leaves the kept one stored.
     */
    private static void cellOnlyUnknown(GameTestHelper helper) {
        DriveBlockEntity drive = CellGameTests.drive(helper, new BlockPos(1, 1, 1));
        drive.insertCell(CellGameTests.cell(CellContent.ITEM_TYPE.id(), 1));
        helper.startSequence().thenWaitUntil(() -> CellGameTests.mounted(helper, drive, 0)).thenExecute(() -> {
            CellInventory original = CellGameTests.mounted(helper, drive, 0);
            CellGameTests.insert(original, COBBLE, 10);
            long id = original.id();
            RegistryOps<Tag> ops = CellGameTests.ops(helper);
            CompoundTag saved = (CompoundTag) CellStorage.CODEC.encodeStart(ops, CellGameTests.storage(helper))
                    .getOrThrow();
            addUnknown(contents(helper, saved, id));
            CellStorage loaded = CellStorage.CODEC.parse(ops, saved).getOrThrow();
            List<StackKey> handed = new ArrayList<>();
            helper.assertTrue(loaded.takeAll(id, (key, amount) -> handed.add(key)), "take all refused");
            helper.assertValueEqual(handed, List.of(COBBLE), "keys handed out");
            helper.assertTrue(loaded.contains(id), "a cell keeping an unknown key was forgotten");
            helper.assertValueEqual(loaded.keptKinds(id), 1, "kept kinds after take all");

            CellInventory cell = loaded.mount(id, CellContent.ITEM_TYPE.get(),
                    CellContent.SMALL_STORAGE_CELL.get().casing(),
                    CellData.of(CellContent.ITEM_TYPE.id(), Collections.nCopies(1, CellContent.MEMORY_MODULE_1.get()))
                            .withId(id),
                    () -> {
                    });
            if (cell == null) {
                throw helper.assertionException(Component.literal("the cell did not mount"));
            }
            loaded.unmount(cell);
            helper.assertTrue(loaded.contains(id), "a cell holding only a kept key was forgotten on unmount");
        }).thenSucceed();
    }

    /**
     * A kept list reads the entries it can and keeps the others; written back, it gives the same list. Crafting jobs
     * and runs whose entries cannot be read keep them instead of losing every job.
     */
    private static void listRoundTrip(GameTestHelper helper) {
        RegistryOps<Tag> ops = CellGameTests.ops(helper);
        Tag known = RecoveryBin.Row.CODEC.encodeStart(ops, new RecoveryBin.Row(0, COBBLE, 5, 7)).getOrThrow();
        CompoundTag unknown = ((CompoundTag) known).copy();
        unknown.put("key", unknownKey());
        ListTag list = new ListTag();
        list.add(known);
        list.add(unknown);
        Codec<KeptList<RecoveryBin.Row>> codec = KeptList.codec(RecoveryBin.Row.CODEC);
        KeptList<RecoveryBin.Row> read = codec.parse(ops, list).getOrThrow();
        helper.assertValueEqual(read.known().size(), 1, "known rows");
        helper.assertValueEqual(read.known().getFirst().amount(), 5L, "known row amount");
        helper.assertValueEqual(read.kept(), List.<Tag>of(unknown), "kept rows");
        helper.assertValueEqual(codec.encodeStart(ops, read).getOrThrow(), list, "rows written back");

        ListTag jobs = new ListTag();
        CompoundTag job = new CompoundTag();
        job.put("key", unknownKey());
        jobs.add(job);
        CraftingJobs loadedJobs = CraftingJobs.CODEC.parse(ops, jobs).getOrThrow();
        helper.assertValueEqual(CraftingJobs.CODEC.encodeStart(ops, loadedJobs).getOrThrow(), jobs, "jobs kept");
        CraftingRuns loadedRuns = CraftingRuns.CODEC.parse(ops, jobs).getOrThrow();
        helper.assertValueEqual(CraftingRuns.CODEC.encodeStart(ops, loadedRuns).getOrThrow(), jobs, "runs kept");
        helper.succeed();
    }

    /**
     * A bulk drive saved holding an item of a removed mod keeps those contents: it takes nothing in, saves them back
     * unchanged and carries them in its dropped item's block entity data.
     */
    private static void bulkDrive(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, BulkContent.BULK_DRIVE.get());
        BulkDriveBlockEntity drive = helper.getBlockEntity(pos, BulkDriveBlockEntity.class);
        CompoundTag tag = drive.saveWithoutMetadata(helper.getLevel().registryAccess());
        CompoundTag contents = new CompoundTag();
        CompoundTag key = new CompoundTag();
        key.putString("id", MISSING);
        contents.put("key", key);
        contents.putLong("count", 500);
        tag.put("contents", contents);
        drive.loadWithComponents(
                TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), tag));
        helper.assertValueEqual(drive.store().count(), 0L, "count of a drive with unknown contents");
        helper.assertValueEqual(CellGameTests.insert(drive.store(), COBBLE, 5), 0L, "inserted into a kept drive");
        CompoundTag again = drive.saveWithoutMetadata(helper.getLevel().registryAccess());
        helper.assertValueEqual(again.getCompoundOrEmpty("contents"), contents, "contents saved back");
        ItemStack item = drive.toItemStack();
        TypedEntityData<BlockEntityType<?>> data = item.get(DataComponents.BLOCK_ENTITY_DATA);
        if (data == null) {
            throw helper.assertionException(Component.literal("the drive item carries no block entity data"));
        }
        helper.assertValueEqual(data.copyTagWithoutId().getCompoundOrEmpty("contents"), contents,
                "contents on the item");

        // Placed again from that item, as vanilla's BlockItem does: block entity data, then the item's components.
        BlockPos other = new BlockPos(3, 1, 1);
        helper.setBlock(other, BulkContent.BULK_DRIVE.get());
        BulkDriveBlockEntity placed = helper.getBlockEntity(other, BulkDriveBlockEntity.class);
        data.loadInto(placed, helper.getLevel().registryAccess());
        placed.applyComponentsFromItemStack(item);
        helper.assertValueEqual(CellGameTests.insert(placed.store(), COBBLE, 5), 0L, "inserted into a placed drive");
        helper.assertValueEqual(
                placed.saveWithoutMetadata(helper.getLevel().registryAccess()).getCompoundOrEmpty("contents"), contents,
                "contents of the placed drive");
        helper.succeed();
    }

    /**
     * A cell filter entry naming an item of a removed mod is kept, matches nothing and keeps the filter from being
     * blank: an allow list of only such entries takes nothing (not everything), a deny list of them takes everything,
     * and the entry is saved back unchanged. A cell whose memory module was removed still loads, with its other
     * modules.
     */
    private static void filterAndMemory(GameTestHelper helper) {
        RegistryOps<Tag> ops = CellGameTests.ops(helper);
        CompoundTag entry = new CompoundTag();
        entry.put("key", unknownKey());
        ListTag entries = new ListTag();
        entries.add(entry);
        CompoundTag saved = new CompoundTag();
        saved.put("entries", entries);
        CellFilter allow = CellFilter.CODEC.parse(ops, saved).getOrThrow();
        helper.assertTrue(!allow.isBlank(), "an allow list of a kept entry is blank");
        helper.assertTrue(!allow.matcher().accepts(COBBLE), "an allow list of a kept entry takes cobblestone");
        helper.assertTrue(!allow.matcher().voids(COBBLE), "an allow list of a kept entry voids cobblestone");
        helper.assertValueEqual(CellFilter.CODEC.encodeStart(ops, allow).getOrThrow(), saved, "filter saved back");
        CellFilter edited = allow.with(0, CellFilter.Entry.of(COBBLE));
        helper.assertValueEqual(edited.kept().size(), 1, "kept entries after an edit");
        helper.assertTrue(edited.matcher().accepts(COBBLE), "the added entry");
        helper.assertTrue(allow.withDeny(true).matcher().accepts(COBBLE), "a deny list of a kept entry");

        ListTag memory = new ListTag();
        memory.add(StringTag.valueOf(BuiltInRegistries.ITEM.getKey(CellContent.MEMORY_MODULE_1.get()).toString()));
        memory.add(StringTag.valueOf(MISSING));
        CompoundTag cell = new CompoundTag();
        cell.putString("type", CellContent.ITEM_TYPE.id().toString());
        cell.put("memory", memory);
        CellData data = CellData.CODEC.parse(ops, cell).getOrThrow();
        helper.assertValueEqual(data.memory(), List.<Item>of(CellContent.MEMORY_MODULE_1.get()), "memory modules");
        helper.succeed();
    }

    /**
     * Mail, recovery rows, a bridge's held store and a pattern provider's bookkeeping keep entries naming an item of a
     * removed mod and save them back; a bridge holding only such entries still counts as holding something, so its item
     * carries them.
     */
    private static void blockData(GameTestHelper helper) {
        RegistryOps<Tag> ops = CellGameTests.ops(helper);
        ItemStack cobble = new ItemStack(Items.COBBLESTONE, 3);
        CompoundTag unknownStack = ((CompoundTag) ItemStack.CODEC.encodeStart(ops, cobble).getOrThrow()).copy();
        unknownStack.putString("id", MISSING);
        UUID player = UUID.randomUUID();
        CompoundTag inbox = new CompoundTag();
        inbox.store("player", UUIDUtil.CODEC, player);
        inbox.putString("name", "kept");
        ListTag stacks = new ListTag();
        stacks.add(ItemStack.CODEC.encodeStart(ops, cobble).getOrThrow());
        stacks.add(unknownStack);
        inbox.put("stacks", stacks);
        ListTag inboxes = new ListTag();
        inboxes.add(inbox);
        MailData mail = MailData.CODEC.parse(ops, inboxes).getOrThrow();
        helper.assertValueEqual(mail.size(player), 1, "readable mail");
        helper.assertValueEqual(mail.room(player, 3), 1, "room, kept mail included");
        helper.assertValueEqual(MailData.CODEC.encodeStart(ops, mail).getOrThrow(), inboxes, "mail saved back");

        CompoundTag row = (CompoundTag) RecoveryBin.Row.CODEC.encodeStart(ops, new RecoveryBin.Row(0, COBBLE, 5, 7))
                .getOrThrow();
        CompoundTag unknownRow = row.copy();
        unknownRow.put("key", unknownKey());
        ListTag rows = new ListTag();
        rows.add(unknownRow);
        BlockPos controllerPos = new BlockPos(1, 1, 1);
        helper.setBlock(controllerPos, ControllerContent.CONTROLLER.get());
        ControllerBlockEntity controller = helper.getBlockEntity(controllerPos, ControllerBlockEntity.class);
        CompoundTag controllerTag = controller.saveWithoutMetadata(helper.getLevel().registryAccess());
        controllerTag.put("recovery", rows);
        load(helper, controller, controllerTag);
        helper.assertValueEqual(
                controller.saveWithoutMetadata(helper.getLevel().registryAccess()).getListOrEmpty("recovery"), rows,
                "recovery rows saved back");

        CompoundTag held = new CompoundTag();
        held.put("key", unknownKey());
        held.putLong("amount", 9);
        ListTag heldList = new ListTag();
        heldList.add(held);
        BlockPos bridgePos = new BlockPos(3, 1, 1);
        helper.setBlock(bridgePos, BridgeContent.BRIDGE_TIER_1.get());
        BridgeBlockEntity bridge = helper.getBlockEntity(bridgePos, BridgeBlockEntity.class);
        CompoundTag bridgeTag = bridge.saveWithoutMetadata(helper.getLevel().registryAccess());
        bridgeTag.put("held", heldList);
        load(helper, bridge, bridgeTag);
        helper.assertTrue(bridge.holdsAnything(), "a bridge with kept held entries holds nothing");
        helper.assertValueEqual(bridge.saveWithoutMetadata(helper.getLevel().registryAccess()).getListOrEmpty("held"),
                heldList, "held entries saved back");
        ItemStack bridgeItem = new ItemStack(BridgeContent.BRIDGE_TIER_1.get());
        bridge.writeHeld(bridgeItem, helper.getLevel().registryAccess());
        TypedEntityData<BlockEntityType<?>> bridgeData = bridgeItem.get(DataComponents.BLOCK_ENTITY_DATA);
        helper.assertValueEqual(
                bridgeData == null ? new ListTag() : bridgeData.copyTagWithoutId().getListOrEmpty("held"), heldList,
                "held entries on the bridge item");

        CompoundTag expected = new CompoundTag();
        expected.put("key", unknownKey());
        expected.putLong("amount", 4);
        ListTag expectedList = new ListTag();
        expectedList.add(expected);
        BlockPos providerPos = new BlockPos(5, 1, 1);
        helper.setBlock(providerPos, ProviderContent.PROVIDER.get());
        PatternProviderBlockEntity provider = helper.getBlockEntity(providerPos, PatternProviderBlockEntity.class);
        CompoundTag providerTag = provider.saveWithoutMetadata(helper.getLevel().registryAccess());
        providerTag.put("expected", expectedList);
        load(helper, provider, providerTag);
        helper.assertValueEqual(
                provider.saveWithoutMetadata(helper.getLevel().registryAccess()).getListOrEmpty("expected"),
                expectedList, "expected outputs saved back");
        helper.succeed();
    }

    private static void load(GameTestHelper helper, BlockEntity entity, CompoundTag tag) {
        entity.loadWithComponents(
                TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), tag));
    }

    /**
     * A new cell id in the server's cell storage whose saved contents hold {@code cobble} cobblestone (none for 0) and
     * {@link #KEPT_COUNT} of the unknown key, loaded as a world load would: the unknown key is a real kept entry of the
     * live storage. The cell is not mounted.
     */
    static long storedWithUnknown(GameTestHelper helper, long cobble) {
        CellStorage server = CellGameTests.storage(helper);
        long id = server.newId();
        CellStorage scratch = new CellStorage();
        CellInventory filling = scratch.mount(id, CellContent.ITEM_TYPE.get(),
                CellContent.SMALL_STORAGE_CELL.get().casing(),
                CellData.of(CellContent.ITEM_TYPE.id(), List.of(CellContent.MEMORY_MODULE_1.get())).withId(id), () -> {
                });
        if (filling == null) {
            throw helper.assertionException(Component.literal("could not fill the scratch cell"));
        }
        CellGameTests.insert(filling, COBBLE, Math.max(cobble, 1));
        RegistryOps<Tag> ops = CellGameTests.ops(helper);
        CompoundTag saved = (CompoundTag) CellStorage.CODEC.encodeStart(ops, scratch).getOrThrow();
        CompoundTag contents = contents(helper, saved, id);
        if (cobble > 0) {
            addUnknown(contents);
        } else {
            ListTag keys = new ListTag();
            keys.add(unknownKey());
            contents.put("keys", keys);
            contents.putIntArray("indices", new int[]{0});
            contents.putLongArray("counts", new long[]{KEPT_COUNT});
        }
        Tag entry = null;
        for (Tag cell : saved.getListOrEmpty("cells")) {
            if (cell instanceof CompoundTag tag && tag.getLongOr("id", 0) == id) {
                entry = tag;
            }
        }
        if (entry == null) {
            throw helper.assertionException(Component.literal("No saved scratch cell " + id));
        }
        // Into the live storage, not a replacement of it: other tests have cells mounted there.
        server.loadSavedCellForTests(ops, entry);
        helper.assertValueEqual(server.keptKinds(id), 1, "kept kinds of the loaded cell");
        helper.assertValueEqual(server.amount(id, COBBLE), cobble, "cobblestone of the loaded cell");
        return id;
    }

    /** The unknown key: an item no mod registers. */
    static CompoundTag unknownKey() {
        CompoundTag key = new CompoundTag();
        key.putString("kind", "item");
        key.putString("id", MISSING);
        return key;
    }

    /** Adds the unknown key with {@link #KEPT_COUNT} to a saved palette. */
    static void addUnknown(CompoundTag contents) {
        ListTag keys = contents.getListOrEmpty("keys");
        keys.add(unknownKey());
        contents.put("keys", keys);
        int[] indices = contents.getIntArray("indices").orElseThrow();
        long[] counts = contents.getLongArray("counts").orElseThrow();
        int[] moreIndices = java.util.Arrays.copyOf(indices, indices.length + 1);
        long[] moreCounts = java.util.Arrays.copyOf(counts, counts.length + 1);
        moreIndices[indices.length] = keys.size() - 1;
        moreCounts[counts.length] = KEPT_COUNT;
        contents.putIntArray("indices", moreIndices);
        contents.putLongArray("counts", moreCounts);
    }

    /** The saved contents of cell {@code id} in a saved cell storage; the live tag, so changes go into the save. */
    static CompoundTag contents(GameTestHelper helper, CompoundTag storage, long id) {
        for (Tag entry : storage.getListOrEmpty("cells")) {
            if (entry instanceof CompoundTag cell && cell.getLongOr("id", 0) == id) {
                return cell.getCompound("contents").orElseThrow();
            }
        }
        throw helper.assertionException(Component.literal("No saved cell " + id));
    }
}
