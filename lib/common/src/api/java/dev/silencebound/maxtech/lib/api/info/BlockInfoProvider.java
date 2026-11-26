package dev.silencebound.maxtech.lib.api.info;

import net.minecraft.resources.Identifier;

/**
 * Describes a block for players: its own stats and, where it belongs to one, its network. Exposed as a sided block
 * capability under {@link #BLOCK_CAPABILITY}; a block with several parts exposes one provider that covers all of them.
 * A NeoForge addon with only the api jar reaches the same capability with
 * {@code BlockCapability.createSided(BLOCK_CAPABILITY, BlockInfoProvider.class)}.
 *
 * <p>
 * Called on the server thread, never from a tick loop. {@link BlockInfoRequest.Detail#FULL} comes from an explicit scan
 * and may do modest work, such as summarising a network. {@link BlockInfoRequest.Detail#BRIEF} feeds overlays that
 * re-query several times a second for every player looking at the block, so it must be O(1): read cached values only.
 * Lines use lang keys; never literal English.
 */
public interface BlockInfoProvider {
    /** Id of the sided block capability ({@code Direction} context, nullable). */
    Identifier BLOCK_CAPABILITY = Identifier.fromNamespaceAndPath("maxtech_lib", "block_info");

    /**
     * Describes the block at {@code request.pos()} to {@code sink}, most important first. Adds nothing if there is
     * nothing to say. For {@link BlockInfoRequest.Detail#BRIEF} add at most three short entries that read at a glance:
     * the status ({@link BlockInfoSink#status}) and the block's most important number, as a bar
     * ({@link BlockInfoSink#progress}) where it has a maximum. Everything else (positions, ids, settings, network
     * totals) belongs to {@link BlockInfoRequest.Detail#FULL}, which repeats the brief entries first.
     */
    void addInfo(BlockInfoRequest request, BlockInfoSink sink);
}
