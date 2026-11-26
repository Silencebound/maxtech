package dev.silencebound.maxtech.storage.sensor;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/** The sensor block's entity: holds the {@link SensorWorker} for the block its front faces. Never ticks. */
public final class SensorBlockEntity extends StorageDeviceBlockEntity implements SensorDevice, SensorWorker.Host {
    static final double RANGE_BUFFER = 4.0;

    private final SensorWorker worker = new SensorWorker(this, this);

    public SensorBlockEntity(BlockPos pos, BlockState state) {
        super(SensorContent.BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Role role() {
        return Role.OTHER;
    }

    /** Logic can turn it off (M7L): its work pauses until it is turned on again. */
    @Override
    public boolean switchable() {
        return true;
    }

    @Override
    public SensorWorker sensor() {
        return worker;
    }

    /** Links to cables on every side but its front, where the target is. */
    @Override
    public boolean linksOn(Direction side) {
        return side != facing();
    }

    @Override
    public Direction facing() {
        BlockState state = getBlockState();
        return state.hasProperty(SensorBlock.FACING) ? state.getValue(SensorBlock.FACING) : Direction.NORTH;
    }

    @Override
    public BlockPos sensorPos() {
        return worldPosition;
    }

    @Override
    public boolean stillValid(Player player) {
        return !isRemoved() && player.isWithinBlockInteractionRange(getBlockPos(), RANGE_BUFFER);
    }

    @Override
    public @Nullable ServerLevel serverLevel() {
        return level instanceof ServerLevel server ? server : null;
    }

    @Override
    public void settingsChanged() {
        setChanged();
    }

    @Override
    public void networkChanged(@Nullable StorageNetwork network) {
        worker.networkChanged(network);
    }

    // PLATFORM-LEAK: NeoForge's IBlockEntityExtension hook, through the base class.
    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel) {
            worker.start();
        }
    }

    @Override
    public void addGlance(BlockInfoRequest request, BlockInfoSink sink) {
        SensorTexts.glance(worker, sink);
    }

    @Override
    public void addDetails(BlockInfoRequest request, BlockInfoSink sink) {
        SensorTexts.info(worker, sink);
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        SensorTexts.write(components, worker.settings());
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        worker.loadSettings(SensorTexts.read(components));
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
