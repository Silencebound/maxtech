package dev.silencebound.maxtech.storage.port;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import dev.silencebound.maxtech.storage.network.StorageProvider;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * The port block's block entity: a {@link PortWorker} (shared with the port part) on the inventory the block faces,
 * with its mode in the block state. Never ticks.
 */
public final class PortBlockEntity extends StorageDeviceBlockEntity implements PortDevice {
    /** How far beyond block reach the menu stays usable, as for vanilla containers. */
    static final double RANGE_BUFFER = 4.0;

    private final PortWorker worker;

    public PortBlockEntity(BlockPos pos, BlockState state) {
        super(PortContent.PORT_BLOCK_ENTITY.get(), pos, state);
        PortTier tier = state.getBlock() instanceof PortBlock port ? port.tier() : PortTier.TIER_1;
        worker = new PortWorker(deviceLink(), tier, this, this::mode, this::setChanged);
    }

    @Override
    public Role role() {
        return Role.TRANSFER;
    }

    /** Logic can turn it off (M7L): its work pauses until it is turned on again. */
    @Override
    public boolean switchable() {
        return true;
    }

    @Override
    public int operationsPerSecond() {
        return worker.operationsPerSecond();
    }

    /** Only the back, where the cable stub ends, links to the network. */
    @Override
    public boolean linksOn(Direction side) {
        return side == facing().getOpposite();
    }

    /** The side the faced inventory is on. */
    public Direction facing() {
        return getBlockState().getValue(PortBlock.FACING);
    }

    @Override
    public PortMode mode() {
        return getBlockState().getValue(PortBlock.MODE);
    }

    @Override
    public PortFilter filter() {
        return worker.filter();
    }

    @Override
    public boolean claimBlocked() {
        return worker.claimBlocked();
    }

    @Override
    public PortWorker worker() {
        return worker;
    }

    @Override
    public void addGlance(BlockInfoRequest request, BlockInfoSink sink) {
        PortDevice.glance(this, sink);
    }

    @Override
    public void addDetails(BlockInfoRequest request, BlockInfoSink sink) {
        PortDevice.details(this, sink);
    }

    @Override
    public void setMode(PortMode mode) {
        Level current = level;
        PortMode before = mode();
        if (current == null || current.isClientSide() || before == mode) {
            return;
        }
        current.setBlock(worldPosition, getBlockState().setValue(PortBlock.MODE, mode), Block.UPDATE_CLIENTS);
        setChanged();
        worker.modeChanged(before);
    }

    /** In storage mode, the mounted inventory as network storage ({@link PortStorage}); else nothing. */
    @Override
    public List<StorageProvider> providers() {
        return worker.providers();
    }

    @Override
    public void stoppedChanged(boolean stopped) {
        worker.stoppedChanged(stopped);
    }

    @Override
    public boolean stillValid(Player player) {
        return !isRemoved() && player.isWithinBlockInteractionRange(getBlockPos(), RANGE_BUFFER);
    }

    /** A neighbour changed: the redstone signal may have (B09 item 9). */
    @Override
    public void onNeighbourChanged() {
        worker.redstoneChanged();
    }

    /** The faced inventory changed or was replaced: there may be work again. */
    void inventoryChanged() {
        worker.inventoryChanged();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) {
            worker.start(server, worldPosition, facing(), this::isLoadedHere);
        }
    }

    private boolean isLoadedHere() {
        return !isRemoved();
    }

    /** The filter and mode go into the dropped item ({@link PortItems}). */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        PortItems.write(components, filter(), mode(), worker);
    }

    /**
     * A placed item's filter and speed cards. Its mode is set by the block's placement state; reading it here too marks
     * it as taken, so it does not linger in the block entity's leftover components and come back on a later drop.
     */
    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        PortItems.readFilter(components, filter());
        PortItems.readCards(components, worker.cards());
        PortItems.restoreBus(components, worker);
        PortItems.mode(components);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        worker.save(output);
    }

    @Override
    protected void loadSaved(ValueInput input) {
        super.loadSaved(input);
        worker.load(input);
    }
}
