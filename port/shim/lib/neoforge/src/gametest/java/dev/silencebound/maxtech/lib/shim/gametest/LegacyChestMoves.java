package dev.silencebound.maxtech.lib.shim.gametest;

import dev.silencebound.maxtech.lib.shim.transfer.ResourceHandler;
import dev.silencebound.maxtech.lib.shim.transfer.bridge.Capabilities;
import dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy.LegacyDirect;
import dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy.LegacyForeignLast;
import dev.silencebound.maxtech.lib.shim.transfer.item.ItemResource;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.SnapshotJournal;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.Transaction;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.TransactionContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Port only (1.21.1): the part of {@code transfer_bridge_chest} that replaces 26.1's rollback checks. A 1.21.1 chest
 * has no transactions, so Lib moves items into and out of it foreign side last ({@link LegacyForeignLast}); these moves
 * run against a real chest, also when the chest moves less than it simulated and when Lib's side then comes up short
 * on the redo, and count that nothing is lost or duplicated. port/rules/saving.py calls {@link #check}.
 */
public final class LegacyChestMoves {
    private static final ItemResource STONE = ItemResource.of(Items.STONE);

    private LegacyChestMoves() {
    }

    /** Runs the moves on the (single) chest at {@code pos}, an absolute position; empties the chest first. */
    public static void check(GameTestHelper helper, BlockPos pos) {
        Container container = (Container) helper.getLevel().getBlockEntity(pos);
        helper.assertTrue(container != null, "a chest at " + pos);
        container.clearContent();
        @Nullable ResourceHandler<ItemResource> handler = helper.getLevel().getCapability(Capabilities.Item.BLOCK,
                pos, Direction.UP);
        LegacyDirect<ItemResource> chest = LegacyDirect.of(handler);
        helper.assertTrue(chest != null, "a 1.21.1 chest takes direct moves");

        // An honest push: everything Lib's side offers goes in.
        LibSlot lib = new LibSlot(30);
        push(helper, lib, new ShortChest(chest, Integer.MAX_VALUE, Integer.MAX_VALUE), 30, 30);
        counts(helper, container, lib, 30, 0, "honest push");

        // The chest takes less than it simulated: Lib's side gives exactly that.
        lib = new LibSlot(64);
        push(helper, lib, new ShortChest(chest, 10, Integer.MAX_VALUE), 64, 10);
        counts(helper, container, lib, 40, 54, "push the chest took less of");

        // ... and Lib's side then gives less on the redo: the rest goes back out of the chest.
        lib = new LibSlot(64);
        lib.redoGivesAtMost = 4;
        push(helper, lib, new ShortChest(chest, 10, Integer.MAX_VALUE), 64, 4);
        counts(helper, container, lib, 44, 60, "push with a short redo");

        // The chest gives less than it simulated: Lib's side keeps exactly that.
        lib = new LibSlot(0);
        pull(helper, lib, new ShortChest(chest, Integer.MAX_VALUE, 10), 44, 10);
        counts(helper, container, lib, 34, 10, "pull the chest gave less of");

        // ... and Lib's side then takes less on the redo: the rest goes back into the chest.
        lib = new LibSlot(0);
        lib.redoTakesAtMost = 4;
        pull(helper, lib, new ShortChest(chest, Integer.MAX_VALUE, 10), 34, 4);
        counts(helper, container, lib, 30, 4, "pull with a short redo");
        container.clearContent();
    }

    private static void push(GameTestHelper helper, LibSlot lib, ShortChest chest, int amount, int expected) {
        try (Transaction root = Transaction.openRoot()) {
            helper.assertValueEqual(LegacyForeignLast.into(lib, chest, STONE, amount, root), expected, "pushed");
            root.commit();
        }
    }

    private static void pull(GameTestHelper helper, LibSlot lib, ShortChest chest, int amount, int expected) {
        try (Transaction root = Transaction.openRoot()) {
            helper.assertValueEqual(LegacyForeignLast.from(chest, lib, STONE, amount, root), expected, "pulled");
            root.commit();
        }
    }

    private static void counts(GameTestHelper helper, Container chest, LibSlot lib, int inChest, int inLib,
            String what) {
        int count = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            count += chest.getItem(i).is(Items.STONE) ? chest.getItem(i).getCount() : 0;
        }
        helper.assertValueEqual(count, inChest, what + ": stone in the chest");
        helper.assertValueEqual(lib.count, inLib, what + ": stone on Lib's side");
    }

    /** One transactional slot of stone on Lib's side; a redo (a move straight in the root) can come up short. */
    private static final class LibSlot extends SnapshotJournal<Integer> implements LegacyForeignLast.LibSide<ItemResource> {
        int count;
        int redoGivesAtMost = Integer.MAX_VALUE;
        int redoTakesAtMost = Integer.MAX_VALUE;

        LibSlot(int count) {
            this.count = count;
        }

        @Override
        public int insert(ItemResource resource, int amount, TransactionContext transaction) {
            int put = Math.min(amount, transaction.depth() == 0 ? redoTakesAtMost : Integer.MAX_VALUE);
            if (put > 0) {
                updateSnapshots(transaction);
                count += put;
            }
            return Math.max(0, put);
        }

        @Override
        public int extract(ItemResource resource, int amount, TransactionContext transaction) {
            int taken = Math.min(Math.min(amount, transaction.depth() == 0 ? redoGivesAtMost : Integer.MAX_VALUE),
                    count);
            if (taken > 0) {
                updateSnapshots(transaction);
                count -= taken;
            }
            return Math.max(0, taken);
        }

        @Override
        protected Integer createSnapshot() {
            return count;
        }

        @Override
        protected void revertToSnapshot(Integer snapshot) {
            count = snapshot;
        }
    }

    /**
     * The real chest, whose real moves take or give at most a set amount per call while its simulation answers for
     * the chest as it is: a handler that moves less than it simulated.
     */
    private record ShortChest(LegacyDirect<ItemResource> chest, int takesAtMost, int givesAtMost)
            implements LegacyDirect<ItemResource> {
        @Override
        public boolean direct() {
            return true;
        }

        @Override
        public int simulateInsertNow(ItemResource resource, int amount) {
            return chest.simulateInsertNow(resource, amount);
        }

        @Override
        public int simulateExtractNow(ItemResource resource, int amount) {
            return chest.simulateExtractNow(resource, amount);
        }

        @Override
        public int insertNow(ItemResource resource, int amount) {
            return chest.insertNow(resource, Math.min(amount, takesAtMost));
        }

        @Override
        public int extractNow(ItemResource resource, int amount) {
            return chest.extractNow(resource, Math.min(amount, givesAtMost));
        }
    }
}
