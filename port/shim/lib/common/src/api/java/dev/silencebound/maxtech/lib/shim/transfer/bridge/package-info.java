/**
 * Port shim, work package transfer (port/errors.md). Bridges between the vendored transfer api and NeoForge 21.1's
 * capabilities: ResourceHandler<ItemResource> over IItemHandler, ResourceHandler<FluidResource> over IFluidHandler,
 * EnergyHandler over IEnergyStorage (both directions), and the 26.1-style Capabilities.Item/Fluid/Energy lookups Lib
 * uses. A bridge to a non-transactional 1.21.1 handler simulates first and applies on the outer commit (journal), so a
 * rollback never has to undo a real move.
 */
package dev.silencebound.maxtech.lib.shim.transfer.bridge;
