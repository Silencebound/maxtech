package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.bulk.BulkContent;
import dev.silencebound.maxtech.storage.bulk.BulkContents;
import dev.silencebound.maxtech.storage.bulk.BulkDriveBlockEntity;
import dev.silencebound.maxtech.storage.bulk.BulkStore;
import dev.silencebound.maxtech.storage.bulk.BulkTexts;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.tool.blueprint.Blueprint;
import dev.silencebound.maxtech.storage.tool.blueprint.Blueprints;
import dev.silencebound.maxtech.storage.save.KeptValue;
import dev.silencebound.maxtech.storage.tool.wrench.StorageWrenchItem;
import dev.silencebound.maxtech.storage.tool.wrench.WrenchContent;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * The bulk drive (M6f area B): one kind, no bytes, a count capped by config, contents that leave only with the drive's
 * item. Layouts run along x at y = 1, z = 1: a powered controller at x = 1 and bulk drives from x = 2.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class BulkDriveGameTests {
    private static final String[] NAMES = {"bulk_drive_lock_and_clear", "bulk_drive_transactions", "bulk_drive_cap",
            "bulk_drive_priority", "bulk_drive_drops_once", "bulk_drive_save_size", "bulk_drive_hand_and_access",
            "bulk_drive_wrench_and_blueprint"};
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey DIRT = ItemKey.of(Items.DIRT);
    private static final long POWER = 100_000;

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register(NAMES[0], BulkDriveGameTests::lockAndClear);
        register(NAMES[1], BulkDriveGameTests::transactions);
        register(NAMES[2], BulkDriveGameTests::cap);
        register(NAMES[3], BulkDriveGameTests::priority);
        register(NAMES[4], BulkDriveGameTests::dropsOnce);
        register(NAMES[5], BulkDriveGameTests::saveSize);
        register(NAMES[6], BulkDriveGameTests::handAndAccess);
        register(NAMES[7], BulkDriveGameTests::wrenchAndBlueprint);
    }

    private BulkDriveGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("bulk_drive"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 200);
        }
    }

    /** A powered controller at x = 1. */
    private static void controller(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, POWER);
    }

    static BulkDriveBlockEntity drive(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, BulkContent.BULK_DRIVE.get());
        return helper.getBlockEntity(pos, BulkDriveBlockEntity.class);
    }

    /** The drive's network once it has indexed the drive's store. */
    private static NetworkState joined(GameTestHelper helper, BulkDriveBlockEntity drive) {
        NetworkState network = drive.network();
        if (network == null) {
            throw helper.assertionException(Component.literal("the bulk drive is in no network"));
        }
        return network;
    }

    private static long insert(StackKey key, long amount, BulkStore store) {
        try (Transaction transaction = Transaction.openRoot()) {
            long moved = store.insert(key, amount, transaction);
            transaction.commit();
            return moved;
        }
    }

    private static long extract(NetworkState network, StackKey key, long amount) {
        try (Transaction transaction = Transaction.openRoot()) {
            long moved = network.extract(key, amount, transaction);
            transaction.commit();
            return moved;
        }
    }

    /**
     * The first kind locks the drive and other kinds are refused; emptied, the lock clears and another kind goes in.
     * The network's totals follow every step.
     */
    private static void lockAndClear(GameTestHelper helper) {
        controller(helper);
        BulkDriveBlockEntity drive = drive(helper, NetworkGameTests.at(2));
        helper.startSequence().thenWaitUntil(() -> joined(helper, drive)).thenExecute(() -> {
            NetworkState network = joined(helper, drive);
            helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 100), 100L, "cobblestone stored");
            helper.assertValueEqual(NetworkGameTests.insert(network, DIRT, 5), 0L, "dirt into a cobblestone drive");
            helper.assertValueEqual(insert(FluidKey.of(Fluids.WATER), 1000, drive.store()), 0L,
                    "water into a bulk drive");
            helper.assertValueEqual(network.contents().amount(COBBLE), 100L, "indexed cobblestone");
            helper.assertValueEqual(extract(network, COBBLE, 40), 40L, "cobblestone taken");
            same(helper, drive.store().key(), COBBLE, "lock while items remain");
            helper.assertValueEqual(extract(network, COBBLE, 1000), 60L, "the rest taken");
            helper.assertTrue(drive.store().key() == null, "still locked once empty");
            helper.assertValueEqual(network.contents().amount(COBBLE), 0L, "indexed cobblestone once empty");
            helper.assertValueEqual(NetworkGameTests.insert(network, DIRT, 5), 5L, "dirt into the emptied drive");
            same(helper, drive.store().key(), DIRT, "lock after refilling");
            helper.assertValueEqual(network.contents().amount(DIRT), 5L, "indexed dirt");
        }).thenSucceed();
    }

    /**
     * A rollback restores kind and count and reports nothing; a drive emptied inside a transaction keeps its kind until
     * the commit, so another kind cannot slip in; a kind swapped in one transaction is reported as both changes.
     */
    private static void transactions(GameTestHelper helper) {
        controller(helper);
        BulkDriveBlockEntity drive = drive(helper, NetworkGameTests.at(2));
        BulkStore store = drive.store();
        helper.startSequence().thenWaitUntil(() -> joined(helper, drive)).thenExecute(() -> {
            NetworkState network = joined(helper, drive);
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(store.insert(COBBLE, 10, transaction), 10L, "insert before the rollback");
            }
            helper.assertTrue(store.key() == null && store.count() == 0, "a rolled-back insert stayed");
            helper.assertValueEqual(network.contents().amount(COBBLE), 0L, "indexed after the rollback");
            insert(COBBLE, 10, store);
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(store.extract(COBBLE, 10, transaction), 10L, "emptied in a transaction");
                helper.assertValueEqual(store.insert(DIRT, 3, transaction), 0L, "another kind before the commit");
                try (Transaction nested = Transaction.open(transaction)) {
                    store.insert(COBBLE, 4, nested);
                }
                helper.assertValueEqual(store.count(), 0L, "after a nested rollback");
            }
            helper.assertValueEqual(store.count(), 10L, "after the outer rollback");
            same(helper, store.key(), COBBLE, "kind after the outer rollback");
            helper.assertValueEqual(network.contents().amount(COBBLE), 10L, "indexed after the rollbacks");
        }).thenSucceed();
    }

    /** Inserts stop at {@code bulkDrive.maxCount}, whatever is offered, and nothing past it is reported as stored. */
    private static void cap(GameTestHelper helper) {
        BulkDriveBlockEntity drive = drive(helper, NetworkGameTests.at(2));
        long max = StorageServerConfig.BULK_DRIVE_MAX_COUNT.getAsLong();
        helper.assertValueEqual(insert(COBBLE, Long.MAX_VALUE, drive.store()), max, "an offer of Long.MAX_VALUE");
        helper.assertValueEqual(insert(COBBLE, 1, drive.store()), 0L, "one more into a full drive");
        helper.assertValueEqual(drive.store().count(), max, "count of a full drive");
        helper.assertValueEqual(BulkStore.room(max - 5, max, 64), 5L, "room below the cap");
        helper.assertValueEqual(BulkStore.room(max + 5, max, 64), 0L, "room above a lowered cap");
        helper.succeed();
    }

    /** A new kind goes to the empty drive with the higher priority; the priority survives a reload. */
    private static void priority(GameTestHelper helper) {
        controller(helper);
        BulkDriveBlockEntity low = drive(helper, NetworkGameTests.at(2));
        BulkDriveBlockEntity high = drive(helper, NetworkGameTests.at(3));
        high.setPriority(5);
        high.setPriority(Integer.MAX_VALUE);
        helper.assertValueEqual(high.priority(), BulkDriveBlockEntity.MAX_PRIORITY, "full-range priority");
        helper.startSequence().thenWaitUntil(() -> {
            joined(helper, low);
            joined(helper, high);
        }).thenExecute(() -> {
            NetworkState network = joined(helper, high);
            helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 7), 7L, "cobblestone stored");
            helper.assertValueEqual(high.store().count(), 7L, "cobblestone in the high-priority drive");
            helper.assertValueEqual(low.store().count(), 0L, "cobblestone in the low-priority drive");
            high.setPriority(-3);
            helper.assertValueEqual(NetworkGameTests.insert(network, DIRT, 2), 2L, "dirt stored");
            helper.assertValueEqual(low.store().count(), 2L, "dirt in the drive now first");
            CompoundTag saved = high.saveWithoutMetadata(helper.getLevel().registryAccess());
            BulkDriveBlockEntity copy = new BulkDriveBlockEntity(high.getBlockPos(), high.getBlockState());
            copy.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(ProblemReporter.DISCARDING,
                    helper.getLevel().registryAccess(), saved));
            helper.assertValueEqual(copy.priority(), -3, "priority after a reload");
        }).thenSucceed();
    }

    /**
     * Broken, the drive drops exactly one item carrying its contents and priority, and its items leave the network;
     * placed again, they come back once. Broken in creative mode it still drops itself, once.
     */
    private static void dropsOnce(GameTestHelper helper) {
        controller(helper);
        BlockPos pos = NetworkGameTests.at(2);
        BulkDriveBlockEntity drive = drive(helper, pos);
        helper.startSequence().thenWaitUntil(() -> joined(helper, drive)).thenExecute(() -> {
            NetworkState network = joined(helper, drive);
            NetworkGameTests.insert(network, COBBLE, 5_000);
            drive.setPriority(7);
            ServerLevel level = helper.getLevel();
            level.destroyBlock(helper.absolutePos(pos), true);
            ItemStack item = drops(helper, pos);
            same(helper, item.get(BulkContent.CONTENTS.get()), KeptValue.of(new BulkContents((ItemKey) COBBLE, 5_000)),
                    "contents of the dropped drive");
            same(helper, item.get(BulkContent.PRIORITY.get()), 7, "priority of the dropped drive");
            helper.assertValueEqual(args(helper, tooltip(item), BulkTexts.HOLDS).get(0), "5,000", "tooltip count");
            helper.assertValueEqual(network.contents().amount(COBBLE), 0L, "indexed after breaking");

            helper.setBlock(pos.below(), Blocks.STONE);
            Player player = helper.makeMockPlayer(GameType.SURVIVAL);
            player.setItemInHand(InteractionHand.MAIN_HAND, item);
            BlockPos below = helper.absolutePos(pos.below());
            item.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(below), Direction.UP, below, false)));
            helper.assertTrue(player.getMainHandItem().isEmpty(), "the drive item stayed in the hand");
        }).thenWaitUntil(() -> {
            BulkDriveBlockEntity placed = helper.getBlockEntity(pos, BulkDriveBlockEntity.class);
            NetworkState network = joined(helper, placed);
            helper.assertValueEqual(network.contents().amount(COBBLE), 5_000L, "indexed after placing again");
        }).thenExecute(() -> {
            BulkDriveBlockEntity placed = helper.getBlockEntity(pos, BulkDriveBlockEntity.class);
            helper.assertValueEqual(placed.priority(), 7, "priority after placing again");
            Player creative = helper.makeMockPlayer(GameType.CREATIVE);
            // The mock player's abilities do not follow its game mode.
            creative.getAbilities().instabuild = true;
            BlockState state = helper.getBlockState(pos);
            BlockPos absolute = helper.absolutePos(pos);
            state.getBlock().playerWillDestroy(helper.getLevel(), absolute, state, creative);
            helper.getLevel().removeBlock(absolute, false);
            ItemStack item = drops(helper, pos);
            same(helper, item.get(BulkContent.CONTENTS.get()), KeptValue.of(new BulkContents((ItemKey) COBBLE, 5_000)),
                    "contents of the drive broken in creative mode");
            List<ItemStack> empty = Block.getDrops(BulkContent.BULK_DRIVE.get().defaultBlockState(), helper.getLevel(),
                    absolute, drive(helper, pos));
            helper.assertValueEqual(empty.size(), 1, "drops of an empty drive");
            helper.assertTrue(empty.get(0).get(BulkContent.CONTENTS.get()) == null, "an empty drive carried contents");
        }).thenSucceed();
    }

    /** The one dropped bulk drive item near {@code pos}; fails on none or more than one. Discards the entities. */
    private static ItemStack drops(GameTestHelper helper, BlockPos pos) {
        List<ItemEntity> entities = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new AABB(helper.absolutePos(pos)).inflate(2));
        List<ItemStack> drives = new ArrayList<>();
        for (ItemEntity entity : entities) {
            if (entity.getItem().is(BulkContent.BULK_DRIVE.item().get())) {
                drives.add(entity.getItem().copy());
            }
            entity.discard();
        }
        helper.assertValueEqual(drives.size(), 1, "dropped bulk drives");
        return drives.get(0);
    }

    /** One entry whatever the count: a full drive saves within a few bytes of one holding a single item. */
    private static void saveSize(GameTestHelper helper) {
        BulkDriveBlockEntity drive = drive(helper, NetworkGameTests.at(2));
        insert(COBBLE, 1, drive.store());
        int one = bytes(drive.saveWithoutMetadata(helper.getLevel().registryAccess()));
        insert(COBBLE, Long.MAX_VALUE, drive.store());
        CompoundTag full = drive.saveWithoutMetadata(helper.getLevel().registryAccess());
        int size = bytes(full);
        helper.assertTrue(size <= one + 16, "a full drive saves " + size + " bytes, one item " + one);
        helper.assertTrue(size < 128, "a full drive saves " + size + " bytes: " + full);
        ItemStack item = drive.toItemStack();
        same(helper, item.get(BulkContent.CONTENTS.get()),
                KeptValue.of(new BulkContents((ItemKey) COBBLE, StorageServerConfig.BULK_DRIVE_MAX_COUNT.getAsLong())),
                "contents of the full drive's item");
        helper.succeed();
    }

    /**
     * Using the drive with a stack puts it in and sneak-using it with an empty hand takes a stack out; a stranger can
     * do neither and keeps the stack.
     */
    private static void handAndAccess(GameTestHelper helper) {
        BlockPos pos = NetworkGameTests.at(2);
        BulkDriveBlockEntity drive = drive(helper, pos);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_INGOT, 64));
        helper.useBlock(pos, player);
        helper.assertTrue(player.getMainHandItem().isEmpty(), "the stack stayed in the hand");
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT, 3));
        helper.useBlock(pos, player);
        helper.assertValueEqual(player.getMainHandItem().getCount(), 3, "dirt left after using a iron drive");
        helper.assertValueEqual(drive.store().count(), 64L, "count after using the drive");
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.setShiftKeyDown(true);
        helper.useBlock(pos, player);
        helper.assertValueEqual(player.getMainHandItem().getCount(), 64, "stack taken by sneak-using");
        helper.assertValueEqual(drive.store().count(), 0L, "count after taking the stack");

        drive.setOwner(OwnerRecord.of(NetworkGameTests.player(helper.getLevel(), "bulk_owner")));
        player.setShiftKeyDown(false);
        helper.useBlock(pos, player);
        helper.assertValueEqual(player.getMainHandItem().getCount(), 64, "a stranger lost the stack");
        helper.assertValueEqual(drive.store().count(), 0L, "a stranger stored items");
        helper.succeed();
    }

    /** The tag's size as written to disk, uncompressed. */
    private static int bytes(CompoundTag tag) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            NbtIo.write(tag, new DataOutputStream(out));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.size();
    }

    private static void same(GameTestHelper helper, @Nullable Object actual, Object expected, String what) {
        if (!expected.equals(actual)) {
            throw helper.assertionException(Component.literal(what + ": expected " + expected + ", got " + actual));
        }
    }

    /**
     * The blueprint card copies the priority only, never the lock or the items; the storage wrench picks the drive up
     * as one item that carries its contents.
     */
    private static void wrenchAndBlueprint(GameTestHelper helper) {
        BlockPos first = NetworkGameTests.at(2);
        BulkDriveBlockEntity source = drive(helper, first);
        BulkDriveBlockEntity target = drive(helper, NetworkGameTests.at(4));
        insert(COBBLE, 50, source.store());
        source.setPriority(9);
        Blueprint blueprint = Blueprints.copy(source);
        helper.assertTrue(Blueprints.paste(target, blueprint), "the card refused a bulk drive");
        helper.assertValueEqual(target.priority(), 9, "pasted priority");
        helper.assertValueEqual(target.store().count(), 0L, "items pasted onto the target");
        helper.assertTrue(target.store().key() == null, "the lock was pasted onto the target");
        helper.assertValueEqual(source.store().count(), 50L, "items left in the source");

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        StorageWrenchItem.pickUp(helper.getLevel(), helper.absolutePos(first), player,
                new ItemStack(WrenchContent.WRENCH.get()));
        helper.assertBlockNotPresent(BulkContent.BULK_DRIVE.get(), first);
        int drives = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(BulkContent.BULK_DRIVE.item().get())) {
                drives += stack.getCount();
                same(helper, stack.get(BulkContent.CONTENTS.get()),
                        KeptValue.of(new BulkContents((ItemKey) COBBLE, 50)), "contents of the picked-up drive");
                same(helper, stack.get(BulkContent.PRIORITY.get()), 9, "priority of the picked-up drive");
            }
        }
        helper.assertValueEqual(drives, 1, "bulk drives picked up");
        helper.succeed();
    }

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
}
