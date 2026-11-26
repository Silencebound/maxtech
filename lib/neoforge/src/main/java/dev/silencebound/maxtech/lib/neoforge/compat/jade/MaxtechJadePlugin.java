package dev.silencebound.maxtech.lib.neoforge.compat.jade;

import dev.silencebound.maxtech.lib.config.ClientConfig;
import dev.silencebound.maxtech.lib.info.InfoLookups;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import snownee.jade.api.Accessor;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * Lib's Jade plugin (PRD 8.2): shows every block's {@code BlockInfoProvider} entries, BRIEF normally and FULL while the
 * client wants details. Jade finds it through the {@link WailaPlugin} annotation in the mod scan data and instantiates
 * it reflectively, so nothing else refers to this package and it never loads without Jade.
 *
 * <p>
 * Both providers are registered for {@link Block}, so any block from any mod that exposes the capability is covered
 * without per-block code; blocks without it cost one capability lookup per client tick while targeted, and no packet.
 *
 * <p>
 * Lib's client options pick the detail level ({@link ClientConfig#overlayFullDetail},
 * {@link ClientConfig#overlaySneakForDetails}). The server only sees the accessor's "show details" flag, so a ray trace
 * callback sets that flag on accessors of blocks with a provider; other blocks keep Jade's own behaviour.
 */
@WailaPlugin
public final class MaxtechJadePlugin implements IWailaPlugin {
    public MaxtechJadePlugin() {
    }

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(BlockInfoServerProvider.INSTANCE, Block.class);
    }

    /** Jade calls this on a physical client only. */
    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(BlockInfoComponentProvider.INSTANCE, Block.class);
        registration.addRayTraceCallback((hit, accessor, original) -> withDetailLevel(registration, accessor));
    }

    /**
     * {@code accessor}, or a copy whose "show details" flag follows Lib's client options for a block with a provider.
     * Runs every client tick: with the default options the flag already matches, so it returns before any lookup; the
     * provider lookup is cached per targeted block and side, and a copy is made only when an option overrides the key.
     */
    private static Accessor<?> withDetailLevel(IWailaClientRegistration registration, Accessor<?> accessor) {
        if (!(accessor instanceof BlockAccessor block)) {
            return accessor;
        }
        boolean wanted = BlockInfoLines.wantsDetails(block.showDetails(), ClientConfig.overlayFullDetail(),
                ClientConfig.overlaySneakForDetails());
        if (wanted == block.showDetails() || !hasProvider(block)) {
            return accessor;
        }
        return registration.blockAccessor().from(block).showDetails(wanted).build();
    }

    // The client thread's last target; only the ray trace callback reads and writes these.
    private static @Nullable Level lastLevel;
    private static long lastPos = Long.MIN_VALUE;
    private static @Nullable Direction lastSide;
    private static @Nullable BlockState lastState;
    private static boolean lastHasProvider;

    /**
     * Whether the targeted block has a provider; looked up again only when the target, its side or its block state
     * changes (a block replaced in place while targeted).
     */
    // Level and block state are compared by identity on purpose: a new level object is a new world, and block states
    // are interned.
    @SuppressWarnings("ReferenceEquality")
    private static boolean hasProvider(BlockAccessor block) {
        Level level = block.getLevel();
        long pos = block.getPosition().asLong();
        Direction side = block.getSide();
        BlockState state = block.getBlockState();
        if (level != lastLevel || pos != lastPos || side != lastSide || state != lastState) {
            lastState = state;
            lastLevel = level;
            lastPos = pos;
            lastSide = side;
            lastHasProvider = InfoLookups.BLOCK.find(level, block.getPosition(), side) != null;
        }
        return lastHasProvider;
    }
}
