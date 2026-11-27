package dev.silencebound.maxtech.storage.signal;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.conduit.ConduitBlockEntity;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.lib.api.network.NetworkManager;
import dev.silencebound.maxtech.lib.api.network.NetworkType;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.ServiceDeviceBlockEntity;
import dev.silencebound.maxtech.storage.network.RemoteLinkNode;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * An antenna mount (M6d; a standalone network device since M7R item 4): a device on its network like any other that,
 * once paired with an antenna through the linking tool, links to that antenna as one segment of the route tree with the
 * antenna's tier's channels, while the antenna is within its own range. The devices touching the far antenna join the
 * network through it, which replaces M6d's remote cluster pieces. Both ends save the pairing; breaking one unpairs the
 * other. A mount of an old world saved no pairing (only its antenna did): it takes it from the antenna once either of
 * them loads. Never ticks.
 */
public final class AntennaMountBlockEntity extends ServiceDeviceBlockEntity implements RemoteLinkNode {
    private static final String ANTENNA = "antenna";
    private static final String ANTENNA_TIER = "antenna_tier";

    private long antenna = Antennas.NONE;
    /** The antenna's tier as of pairing, for the width while its chunk is unloaded. */
    private int antennaTier = 1;

    public AntennaMountBlockEntity(BlockPos pos, BlockState state) {
        super(ExpansionContent.SERVICE_DEVICE_BLOCK_ENTITY.get(), pos, state);
    }

    /** The paired antenna's position, or {@link Antennas#NONE}. */
    public long antenna() {
        return antenna;
    }

    /** Pairs this mount with the antenna at {@code pos} of tier {@code tier} and re-reads its links. Server side. */
    void pair(long pos, int tier) {
        antenna = pos;
        antennaTier = tier;
        setChanged();
        relink();
    }

    /** The antenna at {@code pos} let go of this mount: the mount lets go too, if it was paired with that one. */
    void unpairFrom(long pos) {
        if (antenna == pos) {
            antenna = Antennas.NONE;
            setChanged();
            relink();
        }
    }

    private void relink() {
        Level current = level;
        NetworkType type = NetworkType.byId(networkId()).orElse(null);
        if (current instanceof ServerLevel server && type != null) {
            NetworkManager.get(server).invalidateNode(type, worldPosition);
        }
    }

    @Override
    public void remoteLinks(LongList out) {
        Level current = level;
        if (antenna == Antennas.NONE || current == null) {
            return;
        }
        BlockPos pos = BlockPos.of(antenna);
        if (!ConduitBlockEntity.isChunkPresent(current, pos.getX(), pos.getZ())) {
            // Kept as a cut edge, so networks.unloadedChunkMode applies to the far side.
            out.add(antenna);
            return;
        }
        if (current.getBlockEntity(pos) instanceof AntennaBlockEntity far && far.pairedWithMount(worldPosition)) {
            out.add(antenna);
        }
    }

    @Override
    public long remoteLinkWidth(long pos) {
        Level current = level;
        int tier = current != null && current.isLoaded(BlockPos.of(pos))
                && current.getBlockEntity(BlockPos.of(pos)) instanceof AntennaBlockEntity far
                        ? far.tier()
                        : antennaTier;
        return Antennas.width(tier, tier);
    }

    // PLATFORM-LEAK: NeoForge's IBlockEntityExtension hook, through the base class.
    @Override
    public void onLoad() {
        super.onLoad();
        if (antenna == Antennas.NONE && level instanceof ServerLevel server) {
            // A mount from before M7R item 4 saved no pairing; a loaded antenna paired with it hands it over.
            long found = Antennas.antennaOf(server, worldPosition.asLong());
            AntennaBlockEntity far = found == Antennas.NONE ? null : Antennas.at(server, found);
            if (far != null) {
                pair(found, far.tier());
                far.relinkPair();
            }
        }
    }

    /** Broken (not unloaded): the antenna lets go of this mount. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level instanceof ServerLevel server && antenna != Antennas.NONE) {
            BlockPos far = BlockPos.of(antenna);
            if (server.isLoaded(far) && server.getBlockEntity(far) instanceof AntennaBlockEntity other
                    && other.pairedWithMount(worldPosition)) {
                other.unpair();
            }
        }
    }

    @Override
    public void addDetails(BlockInfoRequest request, BlockInfoSink sink) {
        if (antenna == Antennas.NONE) {
            sink.line(Component.translatable(SignalContent.UNPAIRED_KEY));
        } else {
            BlockPos pos = BlockPos.of(antenna);
            sink.line(Component.translatable(SignalContent.PAIRED_KEY, InfoFormat.number(pos.getX()),
                    InfoFormat.number(pos.getY()), InfoFormat.number(pos.getZ())));
        }
        super.addDetails(request, sink);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (antenna != Antennas.NONE) {
            output.putLong(ANTENNA, antenna);
            output.putInt(ANTENNA_TIER, antennaTier);
        }
    }

    @Override
    protected void loadSaved(ValueInput input) {
        super.loadSaved(input);
        antenna = input.getLongOr(ANTENNA, Antennas.NONE);
        antennaTier = input.getIntOr(ANTENNA_TIER, 1);
    }
}
