package dev.silencebound.maxtech.storage.world;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import dev.silencebound.maxtech.storage.tool.blueprint.DeviceSettings;
import dev.silencebound.maxtech.storage.tool.blueprint.SettingsCopyable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * The block entity of a collector, placer or breaker block: the kind's {@link WorldWorker} on the block's own node. A
 * placer or breaker links to the network on every side but its front, where it works. Never ticks.
 */
public final class WorldDeviceBlockEntity extends StorageDeviceBlockEntity implements SettingsCopyable {
    private final WorldWorker worker;

    public WorldDeviceBlockEntity(BlockPos pos, BlockState state) {
        super(WorldContent.DEVICE_BLOCK_ENTITY.get(), pos, state);
        WorldKind kind = state.getBlock() instanceof WorldDeviceBlock block ? block.kind() : WorldKind.COLLECTOR;
        worker = WorldWorker.create(kind, new WorldWorker.Host(deviceLink(), this, this::getLevel, this::getBlockPos,
                this::facing, this::isRemoved, this::setChanged));
    }

    @Override
    public Role role() {
        return Role.TRANSFER;
    }

    public WorldWorker worker() {
        return worker;
    }

    private Direction facing() {
        return getBlockState().getValue(WorldDeviceBlock.FACING);
    }

    @Override
    public boolean linksOn(Direction side) {
        return worker.kind() == WorldKind.COLLECTOR || side != facing();
    }

    @Override
    public void networkChanged(@Nullable StorageNetwork network) {
        if (isRemoved()) {
            worker.removedFromLevel();
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) {
            worker.start(server);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        worker.removedFromLevel();
    }

    /** The placer's action at a glance (B09 item 25). */
    @Override
    public void addGlance(BlockInfoRequest request, BlockInfoSink sink) {
        worker.addGlance(request, sink);
    }

    /** The mode and the placer's action. */
    @Override
    public void addDetails(BlockInfoRequest request, BlockInfoSink sink) {
        worker.addDetails(request, sink);
    }

    /** The settings and tool go into the dropped item ({@link WorldSettings}). */
    @Override
    public DeviceSettings copyableSettings() {
        return WorldSettings.copyable(worker, this::setChanged);
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        WorldSettings.write(components, worker);
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        worker.apply(WorldSettings.read(components));
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
