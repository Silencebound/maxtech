/**
 * Resources and their keys. {@link dev.silencebound.maxtech.lib.api.resource.StackKey} (an item or a fluid with its
 * data components, PRD 7.2) is the type networks intern to int ids, save in palettes and send to clients, shared by
 * Storage's networks and autocrafting. Resource kinds (ADR-0017, experimental) let tanks, gauges, menu values and
 * conduit contents carry any kind of resource by one key type,
 * {@link dev.silencebound.maxtech.lib.api.resource.KindKey}: items, fluids and energy are built in
 * ({@link dev.silencebound.maxtech.lib.api.resource.ResourceKinds}), and the saved contents of a kind whose mod was
 * removed are kept as an {@link dev.silencebound.maxtech.lib.api.resource.UnknownResource}.
 */
@NullMarked
package dev.silencebound.maxtech.lib.api.resource;

import org.jspecify.annotations.NullMarked;
