/**
 * Port shim, work package transfer (port/errors.md): Lib's bridge between the vendored transfer api and NeoForge
 * 21.1's capabilities. {@link dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy.LegacyBridge} registers both
 * directions; the {@code Legacy*} wrappers present non-transactional 21.1 handlers as resource handlers by simulating
 * first and applying on the root commit, so a rollback never has to undo a real move. This package is Lib's code, not
 * part of the vendored LGPL-2.1 api.
 */
@NullMarked
package dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy;

import org.jspecify.annotations.NullMarked;
