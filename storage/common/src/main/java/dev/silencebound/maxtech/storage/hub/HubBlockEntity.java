package dev.silencebound.maxtech.storage.hub;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.network.ChannelHub;
import dev.silencebound.maxtech.storage.network.Channels;
import dev.silencebound.maxtech.storage.network.NetworkInfo;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A hub's block entity: a storage device that holds nothing and does nothing on its own; its network counts the devices
 * routed through it against {@link #hubCapacity()} ({@link ChannelHub}). Its info line shows that use. Never ticks.
 */
public final class HubBlockEntity extends StorageDeviceBlockEntity implements ChannelHub {
    private final HubTier tier;

    public HubBlockEntity(BlockPos pos, BlockState state) {
        super(HubContent.BLOCK_ENTITY.get(), pos, state);
        tier = state.getBlock() instanceof HubBlock block ? block.tier() : HubTier.TIER_1;
    }

    public HubTier tier() {
        return tier;
    }

    @Override
    public Role role() {
        return Role.OTHER;
    }

    @Override
    public int hubCapacity() {
        return tier.capacity();
    }

    /** The channels in use by the devices behind it, as a bar. */
    @Override
    public void addGlance(BlockInfoRequest request, BlockInfoSink sink) {
        NetworkInfo.channels(used(), capacity(), infinite(), sink);
    }

    /** The same as pips: "Hub: 12 of 16 channels in use by the devices behind it" ("of ∞" without channel limits). */
    @Override
    public void addDetails(BlockInfoRequest request, BlockInfoSink sink) {
        int used = used();
        int capacity = capacity();
        boolean infinite = infinite();
        NetworkInfo.channelPips(Component.translatable(NetworkInfo.HUB_KEY, InfoFormat.number(used),
                NetworkInfo.capacityText(capacity, infinite)), used, capacity, infinite, sink);
    }

    private boolean infinite() {
        NetworkState current = network();
        return current != null ? current.channelsInfinite() : Channels.infinite();
    }

    private int capacity() {
        NetworkState current = network();
        long pos = getBlockPos().asLong();
        return current != null && current.isHub(pos) ? current.hubCapacity(pos) : tier.capacity();
    }

    private int used() {
        NetworkState current = network();
        return current != null ? current.hubChannels(getBlockPos().asLong()) : 0;
    }
}
