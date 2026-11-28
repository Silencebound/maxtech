/**
 * Transfer ports (ADR-0018): a block moves resources of any
 * {@link dev.silencebound.maxtech.lib.api.resource.ResourceKind} between its own buffers and the blocks beside it, face
 * by face, without ticking. {@link dev.silencebound.maxtech.lib.api.transfer.TransferPorts} makes a
 * {@link dev.silencebound.maxtech.lib.api.transfer.TransferPort} over the owner's item slots, tanks and energy buffer;
 * {@link dev.silencebound.maxtech.lib.api.transfer.PortSettings} holds what players set per kind (face modes, a filter
 * for the whole port, a rate cap) and saves kinds by id, keeping those of removed mods. The NeoForge side
 * ({@code lib.neoforge.api.transfer}) bridges each kind to the capabilities it moves through.
 * <p>
 * Experimental until Farms (FM1) settles it. In this version the types, settings and codec exist; ports themselves do
 * not move anything yet.
 */
@ApiStatus.Experimental
@NullMarked
package dev.silencebound.maxtech.lib.api.transfer;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;
