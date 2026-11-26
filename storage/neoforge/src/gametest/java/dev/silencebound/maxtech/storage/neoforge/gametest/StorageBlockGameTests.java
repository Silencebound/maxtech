package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cell.CellBytes;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.cell.CellInventory;
import dev.silencebound.maxtech.storage.cell.CellStorage;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.recovery.RecoveryBin;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.storageblock.StorageBlock;
import dev.silencebound.maxtech.storage.storageblock.StorageBlockContent;
import dev.silencebound.maxtech.storage.storageblock.StorageBlockData;
import dev.silencebound.maxtech.storage.storageblock.StorageBlockEntity;
import dev.silencebound.maxtech.storage.storageblock.StorageBlockTier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The storage block (B09 item 27): many kinds in one block with a cell of its own, contents that leave only with the
 * block's item, priority against drives, and kept entries of removed mods. Layouts run along x at y = 1, z = 1: a
 * powered controller at x = 1 and devices from x = 2.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class StorageBlockGameTests {
    private static final String[] NAMES = {"storage_block_many_kinds", "storage_block_break_and_place",
            "storage_block_priority", "storage_block_kept_entries", "storage_block_copied_id",
            "storage_block_components_keep_cell"};
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey WATER = FluidKey.of(Fluids.WATER);
    private static final long POWER = 1_000_000;
    private static final int KINDS = 40;

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register(NAMES[0], StorageBlockGameTests::manyKinds);
        register(NAMES[1], StorageBlockGameTests::breakAndPlace);
        register(NAMES[2], StorageBlockGameTests::priority);
        register(NAMES[3], StorageBlockGameTests::keptEntries);
        register(NAMES[4], StorageBlockGameTests::copiedId);
        register(NAMES[5], StorageBlockGameTests::componentsKeepCell);
    }

    private StorageBlockGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("storage_block"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 200);
        }
    }

    /** A powered controller at x = 1; its position. */
    private static BlockPos controller(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, POWER);
        return controller;
    }

    static StorageBlockEntity block(GameTestHelper helper, BlockPos pos, StorageBlockTier tier) {
        helper.setBlock(pos, StorageBlockContent.block(tier).get());
        return helper.getBlockEntity(pos, StorageBlockEntity.class);
    }

    /** The block's network once its cell is mounted and indexed; fails (to be retried) until then. */
    static NetworkState joined(GameTestHelper helper, StorageBlockEntity block) {
        NetworkState network = block.network();
        if (network == null || block.cell() == null) {
            throw helper.assertionException(Component.literal("the storage block is in no network yet"));
        }
        return network;
    }

    private static CellInventory cell(GameTestHelper helper, StorageBlockEntity block) {
        CellInventory cell = block.cell();
        if (cell == null) {
            throw helper.assertionException(Component.literal("the storage block has no cell mounted"));
        }
        return cell;
    }

    /** {@code count} distinct items, none of them air or a storage block. */
    @SuppressWarnings("ReferenceEquality") // items are registry singletons
    static List<StackKey> kinds(int count) {
        List<StackKey> kinds = new ArrayList<>(count);
        for (Item item : BuiltInRegistries.ITEM) {
            if (kinds.size() == count) {
                break;
            }
            if (item != Items.AIR && !(item instanceof BlockItem block && block.getBlock() instanceof StorageBlock)) {
                kinds.add(ItemKey.of(item));
            }
        }
        return kinds;
    }

    private static long extract(NetworkState network, StackKey key, long amount) {
        try (Transaction transaction = Transaction.openRoot()) {
            long moved = network.extract(key, amount, transaction);
            transaction.commit();
            return moved;
        }
    }

    /** Bytes {@code count} of {@code key} take in a storage block with the config now. */
    private static long bytes(StackKey key, long count) {
        long units = key.kind() == StackKey.Kind.FLUID
                ? StorageServerConfig.CELL_FLUID_MB_PER_BYTE.getAsLong()
                : StorageServerConfig.CELL_ITEMS_PER_BYTE.getAsLong();
        return CellBytes.bytes(count, StorageServerConfig.CELL_KIND_BYTES.getAsLong(), units);
    }

    /**
     * Forty item kinds and water go into one storage block through its network and come out exactly; its bytes are a
     * cell's bytes for the same contents, of the tier's configured capacity.
     */
    private static void manyKinds(GameTestHelper helper) {
        controller(helper);
        StorageBlockEntity block = block(helper, NetworkGameTests.at(2), StorageBlockTier.TWO);
        List<StackKey> kinds = kinds(KINDS);
        helper.startSequence().thenWaitUntil(() -> joined(helper, block)).thenExecute(() -> {
            NetworkState network = joined(helper, block);
            CellInventory cell = cell(helper, block);
            helper.assertValueEqual(cell.totalBytes(), StorageServerConfig.STORAGE_BLOCK_2_BYTES.getAsLong(),
                    "capacity of a storage block II");
            long expected = 0;
            for (int i = 0; i < kinds.size(); i++) {
                long amount = 10L * (i + 1);
                helper.assertValueEqual(NetworkGameTests.insert(network, kinds.get(i), amount), amount,
                        "stored " + kinds.get(i));
                expected += bytes(kinds.get(i), amount);
            }
            helper.assertValueEqual(NetworkGameTests.insert(network, WATER, 5_000), 5_000L, "water stored");
            expected += bytes(WATER, 5_000);
            helper.assertValueEqual(block.kinds(), KINDS + 1, "kinds held");
            helper.assertValueEqual(block.usedBytes(), expected, "bytes used");
            for (int i = 0; i < kinds.size(); i++) {
                helper.assertValueEqual(cell.amount(kinds.get(i)), 10L * (i + 1), "held " + kinds.get(i));
                helper.assertValueEqual(extract(network, kinds.get(i), 5L * (i + 1)), 5L * (i + 1),
                        "extracted " + kinds.get(i));
                helper.assertValueEqual(network.contents().amount(kinds.get(i)), 5L * (i + 1),
                        "left of " + kinds.get(i));
            }
            helper.assertValueEqual(extract(network, WATER, 5_000), 5_000L, "water extracted");
            helper.assertValueEqual(block.kinds(), KINDS, "kinds after taking the water out");
            helper.assertTrue(
                    state(helper, block).getTicker(helper.getLevel(), StorageBlockContent.BLOCK_ENTITY.get()) == null,
                    "a storage block ticks");
            helper.assertValueEqual(block.scheduledTasks(), 0, "tasks of a storage block");
        }).thenSucceed();
    }

    private static BlockState state(GameTestHelper helper, StorageBlockEntity block) {
        return helper.getLevel().getBlockState(block.getBlockPos());
    }

    /**
     * Broken, the block drops exactly one item carrying its cell id, priority and stats, and its contents leave the
     * network; placed again, every kind comes back exactly. Broken in creative mode it still drops itself, once. An
     * empty block drops a plain item. Burnt, the item's contents go to the network's recovery bin.
     */
    private static void breakAndPlace(GameTestHelper helper) {
        BlockPos controller = controller(helper);
        BlockPos pos = NetworkGameTests.at(2);
        StorageBlockEntity block = block(helper, pos, StorageBlockTier.ONE);
        List<StackKey> kinds = kinds(KINDS);
        long[] id = new long[1];
        helper.startSequence().thenWaitUntil(() -> joined(helper, block)).thenExecute(() -> {
            NetworkState network = joined(helper, block);
            for (int i = 0; i < kinds.size(); i++) {
                NetworkGameTests.insert(network, kinds.get(i), i + 1);
            }
            NetworkGameTests.insert(network, WATER, 1_000);
            block.setPriority(4);
            long used = block.usedBytes();
            id[0] = block.cellId();
            helper.getLevel().destroyBlock(helper.absolutePos(pos), true);
            ItemStack item = drops(helper, pos);
            StorageBlockData data = data(helper, item);
            helper.assertValueEqual(data.id(), id[0], "cell id of the dropped block");
            helper.assertValueEqual(data.priority(), 4, "priority of the dropped block");
            helper.assertValueEqual(data.kinds(), KINDS + 1, "kinds on the dropped block");
            helper.assertValueEqual(data.usedBytes(), used, "bytes on the dropped block");
            helper.assertValueEqual(network.contents().amount(kinds.get(0)), 0L, "indexed after breaking");
            helper.assertTrue(CellGameTests.storage(helper).contains(id[0]), "the contents left the save data");
            place(helper, pos, item);
        }).thenWaitUntil(() -> joined(helper, helper.getBlockEntity(pos, StorageBlockEntity.class))).thenExecute(() -> {
            StorageBlockEntity placed = helper.getBlockEntity(pos, StorageBlockEntity.class);
            NetworkState network = joined(helper, placed);
            helper.assertValueEqual(placed.cellId(), id[0], "cell id after placing again");
            helper.assertValueEqual(placed.priority(), 4, "priority after placing again");
            for (int i = 0; i < kinds.size(); i++) {
                helper.assertValueEqual(network.contents().amount(kinds.get(i)), i + 1L,
                        "indexed after placing again: " + kinds.get(i));
            }
            helper.assertValueEqual(network.contents().amount(WATER), 1_000L, "water after placing again");

            Player creative = helper.makeMockPlayer(GameType.CREATIVE);
            // The mock player's abilities do not follow its game mode.
            creative.getAbilities().instabuild = true;
            BlockState state = helper.getBlockState(pos);
            BlockPos absolute = helper.absolutePos(pos);
            state.getBlock().playerWillDestroy(helper.getLevel(), absolute, state, creative);
            helper.getLevel().removeBlock(absolute, false);
            ItemStack item = drops(helper, pos);
            helper.assertValueEqual(data(helper, item).id(), id[0], "cell id of the block broken in creative mode");

            StorageBlockEntity empty = block(helper, pos, StorageBlockTier.ONE);
            List<ItemStack> plain = Block.getDrops(empty.getBlockState(), helper.getLevel(), absolute, empty);
            helper.assertValueEqual(plain.size(), 1, "drops of an empty block");
            helper.assertTrue(plain.get(0).get(StorageBlockContent.DATA.get()) == null, "an empty block carried data");

            // Burnt: the contents go to the network's recovery bin, as a destroyed cell's do.
            BlockPos at = helper.absolutePos(NetworkGameTests.at(6));
            ItemEntity entity = new ItemEntity(helper.getLevel(), at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5,
                    item);
            helper.getLevel().addFreshEntity(entity);
            entity.hurtServer(helper.getLevel(), helper.getLevel().damageSources().lava(), 100);
            helper.assertTrue(entity.isRemoved(), "the item burnt");
            ControllerBlockEntity core = NetworkGameTests.controllerEntity(helper, controller);
            helper.assertValueEqual(binned(core, kinds.get(KINDS - 1), helper.getLevel().getGameTime()), (long) KINDS,
                    "in the bin");
            helper.assertTrue(!CellGameTests.storage(helper).contains(id[0]), "still in the save data");
        }).thenSucceed();
    }

    private static long binned(ControllerBlockEntity core, StackKey key, long now) {
        long sum = 0;
        for (RecoveryBin.Row row : core.recovery().rows(now)) {
            if (row.key().equals(key)) {
                sum += row.amount();
            }
        }
        return sum;
    }

    /** Places {@code item} at {@code pos} as a survival player does, on stone below it. */
    private static void place(GameTestHelper helper, BlockPos pos, ItemStack item) {
        helper.setBlock(pos.below(), Blocks.STONE);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, item);
        BlockPos below = helper.absolutePos(pos.below());
        item.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(below), Direction.UP, below, false)));
        helper.assertTrue(player.getMainHandItem().isEmpty(), "the storage block item stayed in the hand");
    }

    private static StorageBlockData data(GameTestHelper helper, ItemStack item) {
        StorageBlockData data = item.get(StorageBlockContent.DATA.get());
        if (data == null) {
            throw helper.assertionException(Component.literal("the storage block item carries no data"));
        }
        return data;
    }

    /** The one dropped storage block item near {@code pos}; fails on none or more than one. Discards the entities. */
    private static ItemStack drops(GameTestHelper helper, BlockPos pos) {
        List<ItemEntity> entities = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new AABB(helper.absolutePos(pos)).inflate(2));
        List<ItemStack> blocks = new ArrayList<>();
        for (ItemEntity entity : entities) {
            if (entity.getItem().getItem() instanceof BlockItem item && item.getBlock() instanceof StorageBlock) {
                blocks.add(entity.getItem().copy());
            }
            entity.discard();
        }
        helper.assertValueEqual(blocks.size(), 1, "dropped storage blocks");
        return blocks.get(0);
    }

    /**
     * A new kind goes to the storage block while its priority is above the drive's, and to the drive once it is below;
     * a kind the block already holds still goes to the block.
     */
    private static void priority(GameTestHelper helper) {
        controller(helper);
        DriveBlockEntity drive = CellGameTests.drive(helper, NetworkGameTests.at(2));
        drive.insertCell(CellGameTests.cell(CellContent.ITEM_TYPE.id(), 1));
        StorageBlockEntity block = block(helper, NetworkGameTests.at(3), StorageBlockTier.ONE);
        block.setPriority(5);
        helper.startSequence().thenWaitUntil(() -> {
            joined(helper, block);
            CellGameTests.mounted(helper, drive, 0);
        }).thenExecute(() -> {
            NetworkState network = joined(helper, block);
            CellInventory driveCell = CellGameTests.mounted(helper, drive, 0);
            CellInventory blockCell = cell(helper, block);
            helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 7), 7L, "cobblestone stored");
            helper.assertValueEqual(blockCell.amount(COBBLE), 7L, "cobblestone in the storage block (priority 5)");
            helper.assertValueEqual(driveCell.amount(COBBLE), 0L, "cobblestone in the drive (priority 0)");
            block.setPriority(-5);
            StackKey dirt = ItemKey.of(Items.DIRT);
            helper.assertValueEqual(NetworkGameTests.insert(network, dirt, 3), 3L, "dirt stored");
            helper.assertValueEqual(driveCell.amount(dirt), 3L, "dirt in the drive, now first");
            helper.assertValueEqual(blockCell.amount(dirt), 0L, "dirt in the storage block, now last");
        }).thenSucceed();
    }

    /**
     * A storage block whose cell keeps an entry of a removed mod: the entry is in no view but takes its kind and bytes,
     * survives breaking and placing, and keeps the item carrying the cell when nothing else is left.
     */
    private static void keptEntries(GameTestHelper helper) {
        BlockPos pos = NetworkGameTests.at(2);
        controller(helper);
        StorageBlockEntity block = block(helper, pos, StorageBlockTier.ONE);
        long[] id = new long[1];
        helper.startSequence().thenWaitUntil(() -> joined(helper, block)).thenExecute(() -> {
            CellInventory cell = cell(helper, block);
            CellGameTests.insert(cell, COBBLE, 100);
            id[0] = block.cellId();
            helper.getLevel().destroyBlock(helper.absolutePos(pos), true);
            ItemStack item = drops(helper, pos);
            // The cell's saved contents with an entry of a removed mod added, as a world saved before the mod left.
            CellStorage storage = CellGameTests.storage(helper);
            RegistryOps<Tag> ops = CellGameTests.ops(helper);
            CompoundTag saved = (CompoundTag) CellStorage.CODEC.encodeStart(ops, storage).getOrThrow();
            CompoundTag contents = KeptEntriesGameTests.contents(helper, saved, id[0]);
            KeptEntriesGameTests.addUnknown(contents);
            helper.assertTrue(storage.restoreForTests(id[0], contents), "restoring the saved contents");
            helper.assertValueEqual(storage.keptKinds(id[0]), 1, "kept kinds in the save data");
            place(helper, pos, item);
        }).thenWaitUntil(() -> joined(helper, helper.getBlockEntity(pos, StorageBlockEntity.class))).thenExecute(() -> {
            StorageBlockEntity placed = helper.getBlockEntity(pos, StorageBlockEntity.class);
            NetworkState network = joined(helper, placed);
            CellInventory cell = cell(helper, placed);
            helper.assertValueEqual(cell.keptKinds(), 1, "kept kinds after placing");
            helper.assertValueEqual(placed.kinds(), 2, "kinds after placing, kept included");
            helper.assertValueEqual(cell.usedBytes(),
                    bytes(COBBLE, 100) + bytes(COBBLE, KeptEntriesGameTests.KEPT_COUNT),
                    "bytes after placing, kept included");
            helper.assertValueEqual(network.contents().amount(COBBLE), 100L, "cobblestone after placing");
            helper.assertValueEqual(extract(network, COBBLE, 100), 100L, "cobblestone extracted");
            helper.getLevel().destroyBlock(helper.absolutePos(pos), true);
            ItemStack item = drops(helper, pos);
            StorageBlockData data = data(helper, item);
            helper.assertValueEqual(data.id(), id[0], "cell id of a block holding only a kept entry");
            helper.assertValueEqual(data.kept(), 1, "kept kinds on the item");
            helper.assertValueEqual(CellGameTests.storage(helper).keptKinds(id[0]), 1, "kept kinds after breaking");
        }).thenSucceed();
    }

    /**
     * A creative copy of a storage block item names the same cell: placed next to the original, only one of the two
     * mounts it and the network holds the contents once. Broken, both drop the id; placed again alone, the copy mounts
     * the cell with exactly the original contents.
     */
    private static void copiedId(GameTestHelper helper) {
        controller(helper);
        BlockPos first = NetworkGameTests.at(2);
        BlockPos second = NetworkGameTests.at(3);
        StorageBlockEntity block = block(helper, first, StorageBlockTier.ONE);
        long[] id = new long[1];
        BlockPos[] inactive = new BlockPos[1];
        helper.startSequence().thenWaitUntil(() -> joined(helper, block)).thenExecute(() -> {
            NetworkState network = joined(helper, block);
            NetworkGameTests.insert(network, COBBLE, 100);
            NetworkGameTests.insert(network, WATER, 1_000);
            id[0] = block.cellId();
            helper.getLevel().destroyBlock(helper.absolutePos(first), true);
            ItemStack item = drops(helper, first);
            ItemStack copy = item.copy();
            place(helper, first, item);
            place(helper, second, copy);
        }).thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, NetworkGameTests.at(1));
            helper.assertValueEqual(network.contents().amount(COBBLE), 100L, "cobblestone with the copy placed");
        }).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, NetworkGameTests.at(1));
            StorageBlockEntity a = helper.getBlockEntity(first, StorageBlockEntity.class);
            StorageBlockEntity b = helper.getBlockEntity(second, StorageBlockEntity.class);
            int active = (a.cell() != null ? 1 : 0) + (b.cell() != null ? 1 : 0);
            helper.assertValueEqual(active, 1, "blocks with the shared cell mounted");
            helper.assertValueEqual(network.contents().amount(WATER), 1_000L, "water with the copy placed");
            BlockPos activePos = a.cell() != null ? first : second;
            inactive[0] = a.cell() != null ? second : first;
            helper.getLevel().destroyBlock(helper.absolutePos(activePos), true);
            helper.assertValueEqual(data(helper, drops(helper, activePos)).id(), id[0], "id of the active block");
            helper.assertValueEqual(network.contents().amount(COBBLE), 0L, "cobblestone with the active one broken");
            helper.getLevel().destroyBlock(helper.absolutePos(inactive[0]), true);
            ItemStack refused = drops(helper, inactive[0]);
            helper.assertValueEqual(data(helper, refused).id(), id[0], "id of the inactive block");
            // Placed again next to the controller: with both blocks gone, the second spot is cut off from it.
            place(helper, first, refused);
        }).thenWaitUntil(() -> joined(helper, helper.getBlockEntity(first, StorageBlockEntity.class)))
                .thenExecute(() -> {
                    StorageBlockEntity placed = helper.getBlockEntity(first, StorageBlockEntity.class);
                    helper.assertValueEqual(placed.cellId(), id[0], "cell id of the copy placed alone");
                    helper.assertValueEqual(CellGameTests.storage(helper).amount(id[0], COBBLE), 100L,
                            "cobblestone in the save data before");
                    helper.assertValueEqual(cell(helper, placed).amount(COBBLE), 100L,
                            "cobblestone in the copy's cell");
                    NetworkState network = joined(helper, placed);
                    helper.assertValueEqual(network.contents().amount(COBBLE), 100L, "cobblestone in the copy");
                    helper.assertValueEqual(network.contents().amount(WATER), 1_000L, "water in the copy");
                    helper.assertValueEqual(CellGameTests.storage(helper).amount(id[0], COBBLE), 100L,
                            "cobblestone in the save data");
                }).thenSucceed();
    }

    /**
     * Another block's item components applied to a loaded storage block that holds something set its priority only: the
     * block keeps its own cell and contents, and the other cell stays where it is.
     */
    private static void componentsKeepCell(GameTestHelper helper) {
        controller(helper);
        StorageBlockEntity a = block(helper, NetworkGameTests.at(2), StorageBlockTier.ONE);
        StorageBlockEntity b = block(helper, NetworkGameTests.at(3), StorageBlockTier.ONE);
        StackKey dirt = ItemKey.of(Items.DIRT);
        helper.startSequence().thenWaitUntil(() -> {
            joined(helper, a);
            joined(helper, b);
        }).thenExecute(() -> {
            NetworkState network = joined(helper, a);
            CellGameTests.insert(cell(helper, a), COBBLE, 10);
            CellGameTests.insert(cell(helper, b), dirt, 20);
            b.setPriority(3);
            long own = a.cellId();
            a.applyComponentsFromItemStack(b.toItemStack());
            helper.assertValueEqual(a.cellId(), own, "cell id after applying another block's item");
            helper.assertValueEqual(cell(helper, a).amount(COBBLE), 10L, "own contents kept");
            helper.assertValueEqual(a.priority(), 3, "priority taken from the item");
            helper.assertValueEqual(network.contents().amount(COBBLE), 10L, "cobblestone in the network");
            helper.assertValueEqual(network.contents().amount(dirt), 20L, "dirt in the network");
        }).thenSucceed();
    }
}
