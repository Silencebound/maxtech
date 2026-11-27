package dev.silencebound.maxtech.storage.signal;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.lib.api.network.NetworkManager;
import dev.silencebound.maxtech.lib.api.network.NetworkType;
import dev.silencebound.maxtech.storage.network.RemoteLinkNode;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * An antenna (M6d): a storage device that links to the cables and devices it touches like any other and, once paired
 * with the linking tool, to one far antenna in the same dimension. That link is one segment of the route tree with the
 * lower tier's channels ({@code signal.antenna.channelsTier*}) while the two are within the lower tier's range. Paired
 * with an antenna mount ({@link AntennaMountBlockEntity}, M7R item 4) the link is the same, with this antenna's tier
 * and range. Both ends save the pairing; breaking one unpairs the other. Never ticks.
 */
public final class AntennaBlockEntity extends StorageDeviceBlockEntity implements RemoteLinkNode {
    private static final String PARTNER = "partner";
    private static final String PARTNER_MOUNT = "partner_mount";
    private static final String PARTNER_TIER = "partner_tier";

    private long partner = Antennas.NONE;
    private boolean partnerMount;
    /** The far antenna's tier as of pairing, for the width while its chunk is unloaded. */
    private int partnerTier = 1;

    public AntennaBlockEntity(BlockPos pos, BlockState state) {
        super(SignalContent.ANTENNA_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Role role() {
        return Role.OTHER;
    }

    /** The antenna's tier, 1 to {@link SignalMath#MAX_TIER}. */
    public int tier() {
        return getBlockState().getBlock() instanceof AntennaBlock block ? block.tier() : 1;
    }

    /** The paired antenna or antenna mount, or {@link Antennas#NONE}. */
    public long partner() {
        return partner;
    }

    /** Whether {@link #partner()} is an antenna mount rather than another antenna. */
    public boolean partnerIsMount() {
        return partnerMount;
    }

    /** Whether this antenna is paired with the antenna mount at {@code mount}, in range, and not removed. */
    boolean pairedWithMount(BlockPos mount) {
        return partnerMount && partner == mount.asLong() && !isRemoved() && reaches(partner, tier());
    }

    /** Whether {@code pos} is within the range of a link to a partner of tier {@code otherTier}. */
    boolean reaches(long pos, int otherTier) {
        return Antennas.inRange(worldPosition.asLong(), pos, Antennas.range(tier(), otherTier));
    }

    /**
     * Pairs this antenna with {@code pos}, an antenna of tier {@code tier} or ({@code mount}) an antenna mount, and
     * re-reads the links of both ends' nodes. Server side; the caller unpairs the previous partners first.
     */
    void pair(long pos, boolean mount, int tier) {
        partner = pos;
        partnerMount = mount;
        partnerTier = tier;
        setChanged();
        relink();
    }

    /** Drops the pairing, if any, and re-reads the links. Server side. */
    public void unpair() {
        if (partner == Antennas.NONE) {
            return;
        }
        long old = partner;
        boolean wasMount = partnerMount;
        partner = Antennas.NONE;
        partnerMount = false;
        setChanged();
        relink();
        if (level instanceof ServerLevel server) {
            invalidate(server, old);
            BlockPos far = BlockPos.of(old);
            // The far end lets go too, so neither keeps a one-sided pairing.
            if (!server.isLoaded(far)) {
                return;
            }
            BlockEntity other = server.getBlockEntity(far);
            if (wasMount && other instanceof AntennaMountBlockEntity mount) {
                mount.unpairFrom(worldPosition.asLong());
            } else if (!wasMount && other instanceof AntennaBlockEntity antenna
                    && antenna.partner == worldPosition.asLong() && !antenna.partnerMount) {
                antenna.unpair();
            }
        }
    }

    /** Re-reads this antenna's links and its partner's. */
    void relinkPair() {
        relink();
    }

    /** Re-reads this antenna's links and its partner's. */
    private void relink() {
        if (level instanceof ServerLevel server) {
            invalidate(server, worldPosition.asLong());
            if (partner != Antennas.NONE) {
                invalidate(server, partner);
            }
        }
    }

    private void invalidate(ServerLevel server, long pos) {
        NetworkType type = NetworkType.byId(networkId()).orElse(null);
        if (type != null) {
            NetworkManager.get(server).invalidateNode(type, BlockPos.of(pos));
        }
    }

    @Override
    public void remoteLinks(LongList out) {
        if (partner == Antennas.NONE || level == null) {
            return;
        }
        BlockPos pos = BlockPos.of(partner);
        if (!ConduitBlockEntity.isChunkPresent(level, pos.getX(), pos.getZ())) {
            // Kept as a cut edge, so networks.unloadedChunkMode applies to the far side.
            out.add(partner);
            return;
        }
        BlockEntity far = level.getBlockEntity(pos);
        if (partnerMount) {
            if (far instanceof AntennaMountBlockEntity mount && !mount.isRemoved()
                    && mount.antenna() == worldPosition.asLong() && reaches(partner, tier())) {
                out.add(partner);
            }
        } else if (far instanceof AntennaBlockEntity other && !other.isRemoved()
                && other.partner == worldPosition.asLong() && !other.partnerMount && reaches(partner, other.tier())) {
            out.add(partner);
        }
    }

    @Override
    public long remoteLinkWidth(long pos) {
        if (partnerMount) {
            return Antennas.width(tier(), tier());
        }
        Level current = level;
        int other = current != null && current.isLoaded(BlockPos.of(pos))
                && current.getBlockEntity(BlockPos.of(pos)) instanceof AntennaBlockEntity far
                        ? far.tier()
                        : partnerTier;
        return Antennas.width(tier(), other);
    }

    // PLATFORM-LEAK: NeoForge's IBlockEntityExtension hook, through the base class.
    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) {
            Antennas.add(server, this);
            adoptMount(server);
        }
    }

    /**
     * A mount from before M7R item 4 saved no pairing, only this antenna did: when that mount is loaded and paired with
     * nothing, it takes the pairing from here.
     */
    private void adoptMount(ServerLevel server) {
        if (!partnerMount || partner == Antennas.NONE) {
            return;
        }
        BlockPos pos = BlockPos.of(partner);
        if (server.isLoaded(pos) && server.getBlockEntity(pos) instanceof AntennaMountBlockEntity mount
                && mount.antenna() == Antennas.NONE && reaches(partner, tier())) {
            mount.pair(worldPosition.asLong(), tier());
            relink();
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level instanceof ServerLevel server) {
            Antennas.remove(server, this);
        }
    }

    /** Broken (not unloaded): the far antenna or mount is unpaired. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level instanceof ServerLevel) {
            unpair();
        }
    }

    @Override
    public void addDetails(BlockInfoRequest request, BlockInfoSink sink) {
        if (partner == Antennas.NONE) {
            sink.line(Component.translatable(SignalContent.UNPAIRED_KEY));
        } else {
            BlockPos pos = BlockPos.of(partner);
            sink.line(Component.translatable(partnerMount ? SignalContent.PAIRED_MOUNT_KEY : SignalContent.PAIRED_KEY,
                    InfoFormat.number(pos.getX()), InfoFormat.number(pos.getY()), InfoFormat.number(pos.getZ())));
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (partner != Antennas.NONE) {
            output.putLong(PARTNER, partner);
            output.putBoolean(PARTNER_MOUNT, partnerMount);
            output.putInt(PARTNER_TIER, partnerTier);
        }
    }

    @Override
    protected void loadSaved(ValueInput input) {
        super.loadSaved(input);
        partner = input.getLongOr(PARTNER, Antennas.NONE);
        partnerMount = input.getBooleanOr(PARTNER_MOUNT, false);
        partnerTier = input.getIntOr(PARTNER_TIER, 1);
    }
}
