package dev.silencebound.maxtech.lib.neoforge.compat.jade;

import dev.silencebound.maxtech.lib.energy.EnergyLookups;
import dev.silencebound.maxtech.lib.info.InfoLookups;
import dev.silencebound.maxtech.lib.info.InfoEntry;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.StreamServerDataProvider;

/**
 * Sends a block's info entries to the Jade client. Providers read server state, so the entries are built here and only
 * the finished entries travel; the client asks for FULL only while it wants details (its "show details" key, sneak, or
 * the full detail level, see {@link MaxtechJadePlugin}), so the plain overlay costs one BRIEF query per Jade request.
 */
final class BlockInfoServerProvider implements StreamServerDataProvider<BlockAccessor, List<InfoEntry>> {
    static final BlockInfoServerProvider INSTANCE = new BlockInfoServerProvider();

    private static final StreamCodec<RegistryFriendlyByteBuf, List<InfoEntry>> CODEC = InfoEntry.STREAM_CODEC
            .apply(ByteBufCodecs.list(BlockInfoLines.MAX_LINES));

    private BlockInfoServerProvider() {
    }

    @Override
    public Identifier getUid() {
        return BlockInfoLines.UID;
    }

    /**
     * Runs on the client for the block under the crosshair every client tick, so Jade only sends requests for blocks
     * that have a provider or an energy endpoint. The capabilities are registered on both sides; neither is called
     * here.
     */
    @Override
    public boolean shouldRequestData(BlockAccessor accessor) {
        return InfoLookups.BLOCK.find(accessor.getLevel(), accessor.getPosition(), accessor.getSide()) != null
                || EnergyLookups.BLOCK.find(accessor.getLevel(), accessor.getPosition(), accessor.getSide()) != null;
    }

    @Override
    public @Nullable List<InfoEntry> streamData(BlockAccessor accessor) {
        if (!(accessor.getLevel() instanceof ServerLevel level)) {
            return null;
        }
        List<InfoEntry> entries = BlockInfoLines.collect(level, accessor.getPosition(), accessor.getSide(),
                accessor.getHitResult().getLocation(), accessor.showDetails());
        return entries.isEmpty() ? null : entries;
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, List<InfoEntry>> streamCodec() {
        return CODEC;
    }
}
