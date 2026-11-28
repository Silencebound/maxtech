/**
 * Port shim, work package transfer (port/errors.md): Lib's bridge between the vendored transfer api and NeoForge
 * 21.1's capabilities. {@link dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy.LegacyBridge} registers both
 * directions; the {@code Legacy*} wrappers present non-transactional 21.1 handlers as resource handlers through a
 * {@link dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy.LegacyLedger} shared per real inventory (see there
 * for what is simulated, what is applied at once and what happens when a handler changes in between). This package is
 * Lib's code, not part of the vendored LGPL-2.1 api.
 */
@NullMarked
package dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy;

import org.jspecify.annotations.NullMarked;
