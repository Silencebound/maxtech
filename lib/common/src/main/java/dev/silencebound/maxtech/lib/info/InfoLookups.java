package dev.silencebound.maxtech.lib.info;

import dev.silencebound.maxtech.lib.api.info.BlockInfoProvider;
import dev.silencebound.maxtech.lib.platform.BlockLookup;
import dev.silencebound.maxtech.lib.platform.Platform;

/** Suite-internal handle for the capability declared by {@link BlockInfoProvider}. */
public final class InfoLookups {
    public static final BlockLookup<BlockInfoProvider> BLOCK = Platform.get().capabilities()
            .sided(BlockInfoProvider.BLOCK_CAPABILITY, BlockInfoProvider.class);

    private InfoLookups() {
    }
}
