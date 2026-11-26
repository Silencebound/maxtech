/**
 * The seam between loader-independent game logic and the mod loader (PRD 4.1). {@code common} code reaches NeoForge
 * only through {@link dev.silencebound.maxtech.lib.platform.Platform}; there is exactly one implementation, in
 * {@code lib/neoforge}. Add a method only when {@code common} needs something loader-specific.
 */
@NullMarked
package dev.silencebound.maxtech.lib.platform;

import org.jspecify.annotations.NullMarked;
