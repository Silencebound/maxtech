package dev.silencebound.maxtech.storage.p2p;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/** The P2P block's block entity: a {@link P2PEndpoint} shared with the part form. Never ticks. */
public final class P2PBlockEntity extends StorageDeviceBlockEntity implements P2PDevice {
    private final P2PEndpoint endpoint;

    public P2PBlockEntity(BlockPos pos, BlockState state) {
        super(P2PContent.BLOCK_ENTITY.get(), pos, state);
        P2PKind kind = state.getBlock() instanceof P2PBlock block ? block.kind() : P2PKind.ITEM;
        this.endpoint = new P2PEndpoint(kind, this, this::setChanged, this::signalChanged);
    }

    @Override
    public Role role() {
        return Role.TRANSFER;
    }

    /** One operation a second at rest; a link moves in bursts of its neighbours' calls. */
    @Override
    public int operationsPerSecond() {
        return 1;
    }

    @Override
    public P2PEndpoint endpoint() {
        return endpoint;
    }

    /** The side the faced block is on. */
    public Direction facing() {
        return getBlockState().getValue(P2PBlock.FACING);
    }

    /** Every side but the front links to the network. */
    @Override
    public boolean linksOn(Direction side) {
        return side != facing();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) {
            endpoint.start(server, worldPosition, facing());
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level instanceof ServerLevel) {
            endpoint.stop();
        }
    }

    @Override
    public void networkChanged(@Nullable StorageNetwork network) {
        endpoint.networkChanged();
    }

    private void signalChanged() {
        Level current = level;
        if (current != null && !current.isClientSide()) {
            current.updateNeighborsAt(worldPosition, getBlockState().getBlock());
        }
    }

    @Override
    public void addGlance(BlockInfoRequest request, BlockInfoSink sink) {
        endpoint.addGlance(sink);
    }

    @Override
    public void addDetails(BlockInfoRequest request, BlockInfoSink sink) {
        endpoint.addDetails(sink);
    }

    /** The link goes into the dropped item. */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        P2PItems.write(components, endpoint.settings());
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        endpoint.read(P2PItems.read(components));
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        endpoint.save(output);
    }

    @Override
    protected void loadSaved(ValueInput input) {
        super.loadSaved(input);
        endpoint.load(input);
    }
}
