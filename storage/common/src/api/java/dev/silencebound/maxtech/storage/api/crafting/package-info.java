/**
 * Autocrafting as other mods see it (storage/PRD.md §7.2, Autocrafting): the pattern format both pattern paths write,
 * and {@link dev.silencebound.maxtech.storage.api.crafting.PatternProvider}, which Storage's pattern provider and, from
 * M7b, routed crafting pipes implement so the network can send them work.
 * <p>
 * Experimental: no implementation outside Storage exists yet, so every type here may change in any Storage minor
 * version, also after 1.0.
 */
@ApiStatus.Experimental
@NullMarked
package dev.silencebound.maxtech.storage.api.crafting;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;
