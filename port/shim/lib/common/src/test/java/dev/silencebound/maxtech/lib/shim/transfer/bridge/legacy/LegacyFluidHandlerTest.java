package dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.silencebound.maxtech.lib.shim.transfer.fluid.FluidResource;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.Transaction;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.junit.jupiter.api.Test;

/**
 * The fluid bridge over NeoForge 21.1's {@link FluidTank}. FluidStack needs the game bootstrapped, which NeoForge
 * 21.1 only allows inside FML; outside it (plain JUnit) these tests are skipped, not failed.
 */
class LegacyFluidHandlerTest {
    private static FluidTank tank(int water) {
        var tank = new FluidTank(4000);
        if (water > 0) {
            tank.fill(new FluidStack(Fluids.WATER, water), IFluidHandler.FluidAction.EXECUTE);
        }
        return tank;
    }

    @Test
    void rollbackLeavesTheTankUntouched() {
        assumeTrue(GameBootstrap.available(), "needs the game bootstrapped (FML unit tests)");
        var tank = tank(1000);
        var handler = new LegacyFluidHandler(tank);
        var water = FluidResource.of(Fluids.WATER);
        try (var tx = Transaction.openRoot()) {
            assertEquals(1000, handler.extract(water, 3000, tx));
            assertEquals(2000, handler.insert(water, 2000, tx));
        }
        assertEquals(1000, tank.getFluidAmount());
    }

    @Test
    void commitAppliesFillAndDrainExactly() {
        assumeTrue(GameBootstrap.available(), "needs the game bootstrapped (FML unit tests)");
        var tank = tank(1000);
        var handler = new LegacyFluidHandler(tank);
        var water = FluidResource.of(Fluids.WATER);
        try (var tx = Transaction.openRoot()) {
            assertEquals(2500, handler.insert(water, 2500, tx));
            assertEquals(500, handler.insert(water, 2500, tx));
            assertEquals(1000, handler.extract(water, 1000, tx));
            tx.commit();
        }
        assertEquals(3000, tank.getFluidAmount());
    }

    @Test
    void aSecondFluidIsRefusedWhileOneIsPending() {
        assumeTrue(GameBootstrap.available(), "needs the game bootstrapped (FML unit tests)");
        var tank = tank(0);
        var handler = new LegacyFluidHandler(tank);
        try (var tx = Transaction.openRoot()) {
            assertEquals(1000, handler.insert(FluidResource.of(Fluids.WATER), 1000, tx));
            assertEquals(0, handler.insert(FluidResource.of(Fluids.LAVA), 1000, tx));
            tx.commit();
        }
        assertEquals(1000, tank.getFluidAmount());
    }

    @Test
    void newHandlerPerQueryOnOneBlockSharesTheLedger() {
        assumeTrue(GameBootstrap.available(), "needs the game bootstrapped (FML unit tests)");
        var tank = tank(3000);
        var block = new LegacyLedger.Location(new Object(), 1L);
        var water = FluidResource.of(Fluids.WATER);
        int filled = 0;
        try (var tx = Transaction.openRoot()) {
            filled += new LegacyFluidHandler(tank, block).insert(water, 800, tx);
            filled += new LegacyFluidHandler(tank, block).insert(water, 800, tx);
            tx.commit();
        }
        assertEquals(1000, filled);
        assertEquals(4000, tank.getFluidAmount());
    }

    @Test
    void tankChangedBeforeCommitIsReported() {
        assumeTrue(GameBootstrap.available(), "needs the game bootstrapped (FML unit tests)");
        var tank = tank(0);
        var handler = new LegacyFluidHandler(tank);
        var water = FluidResource.of(Fluids.WATER);
        int before = LegacyLedger.MISMATCHES.get();
        try (var tx = Transaction.openRoot()) {
            assertEquals(4000, handler.insert(water, 4000, tx));
            tank.fill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE);
            tx.commit();
        }
        assertEquals(4000, tank.getFluidAmount());
        assertEquals(before + 1, LegacyLedger.MISMATCHES.get());
    }
}
