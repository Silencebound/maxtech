/**
 * Lib-owned machine inventories (ADR-0016): {@link dev.silencebound.maxtech.lib.api.inventory.ItemSlots}, fixed item
 * slots with a {@link dev.silencebound.maxtech.lib.api.inventory.SlotRole} and an optional filter each, and
 * {@link dev.silencebound.maxtech.lib.api.inventory.ResourceTank}, one resource of a
 * {@link dev.silencebound.maxtech.lib.api.resource.ResourceKind} with an amount and a capacity
 * ({@link dev.silencebound.maxtech.lib.api.inventory.FluidTank} for fluids). Lib implements both over storage that
 * takes part in NeoForge transfer transactions, so pipes, hoppers and Lib's menus move resources in and out of them
 * without duping or losing any; a mod hands them to NeoForge's capabilities through the NeoForge-side api
 * ({@code lib.neoforge.api.inventory.Handlers}); only a machine's own transactional access names NeoForge transfer
 * types.
 * <p>
 * The methods here change contents directly, each as its own committed step: for a machine's own work (burning fuel,
 * putting out a result) on the server thread, outside any transfer transaction;
 * {@link dev.silencebound.maxtech.lib.api.inventory.MachineAccess} gives the machine the same access inside one, so a
 * whole step (inputs taken, results put out) commits or rolls back together. Experimental until Farms (FM1) settles it.
 */
@ApiStatus.Experimental
@NullMarked
package dev.silencebound.maxtech.lib.api.inventory;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;
