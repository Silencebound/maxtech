package dev.silencebound.maxtech.storage.neoforge.compat.refinedstorage;

import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.common.api.support.network.InWorldNetworkNodeContainer;
import com.refinedmods.refinedstorage.common.api.support.network.NetworkNodeContainerProvider;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import dev.silencebound.maxtech.storage.bridge.ForeignNetwork;
import dev.silencebound.maxtech.storage.bridge.ForeignNetworks;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * Finds the Refined Storage network in front of a bridge: the RS block's node container provider (RS's NeoForge block
 * capability) on the face touching the bridge, its first container that would accept a connection from the bridge's
 * side (the check RS's own cables make), and that node's network.
 *
 * <p>
 * RS gives a node its network only at the start of the next server tick after it was placed or loaded, so a bridge
 * whose pass runs in that same tick (an RS block placed by something during the level tick) finds a container without a
 * network. The finder then asks again on later ticks by sending the bridge a neighbour update, for up to
 * {@link #RETRY_TICKS} ticks. A player's placement or a chunk load is seen on the next tick, after RS's initialisation.
 */
final class RsFinder {
    /** How many ticks a bridge in front of a node without a network is asked to look again. */
    static final int RETRY_TICKS = 20;
    private static final ObjectArrayList<Retry> RETRIES = new ObjectArrayList<>();

    private RsFinder() {
    }

    /** Registers the finder and the retry tick; call once at mod construction with Refined Storage loaded. */
    static void install() {
        ForeignNetworks.register(RsFinder::find);
        NeoForge.EVENT_BUS.addListener(RsFinder::tick);
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            RETRIES.clear();
            RsExport.clearMounted();
        });
    }

    static @Nullable ForeignNetwork find(ServerLevel level, BlockPos pos, Direction side) {
        NetworkNodeContainerProvider provider = level.getCapability(
                RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability(), pos, side);
        if (provider == null) {
            return null;
        }
        BlockPos bridge = pos.relative(side);
        BlockState bridgeState = level.getBlockState(bridge);
        boolean waiting = false;
        for (InWorldNetworkNodeContainer container : provider.getContainers()) {
            if (container.isRemoved() || !container.canAcceptIncomingConnection(side, bridgeState)) {
                continue;
            }
            Network network = container.getNode().getNetwork();
            if (network != null) {
                int waited = index(level, bridge);
                if (waited >= 0) {
                    RETRIES.remove(waited);
                }
                return new RsNetwork(container, network);
            }
            waiting = true;
        }
        if (waiting) {
            retry(level, bridge);
        }
        return null;
    }

    private static void retry(ServerLevel level, BlockPos bridge) {
        if (index(level, bridge) < 0) {
            RETRIES.add(new Retry(level, bridge.immutable(), level.getGameTime() + RETRY_TICKS));
        }
    }

    @SuppressWarnings("ReferenceEquality") // the same loaded level object, not a value
    private static int index(ServerLevel level, BlockPos bridge) {
        for (int i = 0; i < RETRIES.size(); i++) {
            Retry retry = RETRIES.get(i);
            if (retry.level == level && retry.pos.equals(bridge)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * After each tick (RS initialises its waiting nodes at the start of a tick): sends each waiting bridge a neighbour
     * update, so it looks again on its next pass, until the finder found the network or the retry ran out.
     */
    private static void tick(ServerTickEvent.Post event) {
        for (int i = RETRIES.size() - 1; i >= 0; i--) {
            Retry retry = RETRIES.get(i);
            if (retry.level.getGameTime() > retry.until || !retry.level.isLoaded(retry.pos)) {
                RETRIES.remove(i);
                continue;
            }
            BlockState state = retry.level.getBlockState(retry.pos);
            retry.level.neighborChanged(retry.pos, state.getBlock(), null);
        }
    }

    private record Retry(ServerLevel level, BlockPos pos, long until) {
    }
}
