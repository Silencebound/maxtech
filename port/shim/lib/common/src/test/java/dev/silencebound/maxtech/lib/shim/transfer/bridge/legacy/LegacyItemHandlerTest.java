package dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.silencebound.maxtech.lib.shim.transfer.ResourceHandler;
import dev.silencebound.maxtech.lib.shim.transfer.item.ItemResource;
import dev.silencebound.maxtech.lib.shim.transfer.item.ItemStacksResourceHandler;
import dev.silencebound.maxtech.lib.shim.transfer.port.ItemResourceHandlerAdapter;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.Transaction;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The item bridge over real 1.21.1 handlers. ItemStack needs the game bootstrapped, which NeoForge 21.1 only allows
 * inside FML; outside it (plain JUnit) these tests are skipped, not failed.
 */
class LegacyItemHandlerTest {
    private static boolean bootstrapped;

    @BeforeAll
    static void bootstrap() {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        } catch (Throwable e) {
            bootstrapped = false;
        }
    }

    @Test
    void twoInvWrappersOverOneContainerShareTheirLedger() {
        assumeTrue(bootstrapped, "needs the game bootstrapped (FML unit tests)");
        var container = new SimpleContainer(1);
        container.setItem(0, new ItemStack(Items.STONE, 40));
        var stone = ItemResource.of(Items.STONE);
        int moved = 0;
        try (var tx = Transaction.openRoot()) {
            // NeoForge's vanilla providers return a new InvWrapper per lookup.
            moved += new LegacyItemHandler(new InvWrapper(container)).insert(0, stone, 24, tx);
            moved += new LegacyItemHandler(new InvWrapper(container)).insert(0, stone, 24, tx);
            tx.commit();
        }
        assertEquals(24, moved);
        assertEquals(64, container.getItem(0).getCount());
    }

    @Test
    void rollbackOfAnExtractionRestoresTheStack() {
        assumeTrue(bootstrapped, "needs the game bootstrapped (FML unit tests)");
        var chest = new ItemStackHandler(2);
        chest.setStackInSlot(0, new ItemStack(Items.STONE, 40));
        var handler = new LegacyItemHandler(chest);
        try (var tx = Transaction.openRoot()) {
            assertEquals(40, handler.extract(0, ItemResource.of(Items.STONE), 64, tx));
            assertEquals(0, chest.getStackInSlot(0).getCount());
        }
        assertEquals(40, chest.getStackInSlot(0).getCount());
    }

    @Test
    void adapterSimulationLeavesTheLibHandlerUnchanged() {
        assumeTrue(bootstrapped, "needs the game bootstrapped (FML unit tests)");
        var lib = new ItemStacksResourceHandler(1);
        IItemHandler adapter = new ItemResourceHandlerAdapter(lib);
        assertEquals(0, adapter.insertItem(0, new ItemStack(Items.STONE, 10), true).getCount());
        assertEquals(0, lib.getAmountAsLong(0));
        assertEquals(0, adapter.insertItem(0, new ItemStack(Items.STONE, 10), false).getCount());
        assertEquals(10, lib.getAmountAsLong(0));
        assertEquals(4, adapter.extractItem(0, 4, true).getCount());
        assertEquals(10, lib.getAmountAsLong(0));
    }

    @Test
    void bridgingTwiceUnwraps() {
        assumeTrue(bootstrapped, "needs the game bootstrapped (FML unit tests)");
        IItemHandler legacy = new ItemStackHandler(1);
        assertSame(legacy, LegacyBridge.toLegacy(LegacyBridge.fromLegacy(legacy, null, null)));
        ResourceHandler<ItemResource> lib = new ItemStacksResourceHandler(1);
        assertSame(lib, LegacyBridge.fromLegacy(LegacyBridge.toLegacy(lib), null, null));
    }
}
