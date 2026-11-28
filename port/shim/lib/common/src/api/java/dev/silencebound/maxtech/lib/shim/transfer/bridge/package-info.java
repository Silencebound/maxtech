/*
 * SPDX-License-Identifier: LGPL-2.1-only
 */
/*
 * Added for MaxTech Lib's 1.21.1 build (port/NOTICE-transfer.md); part of the vendored transfer api, under its
 * licence.
 */

/**
 * Port shim, work package transfer (port/errors.md): the 26.1-style resource-handler capabilities
 * ({@link dev.silencebound.maxtech.lib.shim.transfer.bridge.Capabilities}) that Lib's code looks up. Lib's own
 * adapters to NeoForge 21.1's IItemHandler/IFluidHandler/IEnergyStorage live in {@code bridge.legacy}, a separate
 * package so the vendored api can ship as its own jar.
 */
@NullMarked
package dev.silencebound.maxtech.lib.shim.transfer.bridge;

import org.jspecify.annotations.NullMarked;
