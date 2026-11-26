package dev.silencebound.maxtech.storage.world;

import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/** The network chest block's block entity: a {@link NetworkChestView} on the block's own node. Never ticks. */
public final class NetworkChestBlockEntity extends StorageDeviceBlockEntity {
    private final NetworkChestView view = new NetworkChestView(deviceLink(), this, this::getLevel, this::getBlockPos,
            this::isRemoved, this::setChanged);

    public NetworkChestBlockEntity(BlockPos pos, BlockState state) {
        super(WorldContent.NETWORK_CHEST_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Role role() {
        return Role.ACCESS;
    }

    public NetworkChestView view() {
        return view;
    }

    @Override
    public void networkChanged(@Nullable StorageNetwork network) {
        view.networkChanged();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide()) {
            view.start();
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        view.removedFromLevel();
    }

    /** The settings go into the dropped item ({@link WorldSettings}). */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        WorldSettings.write(components, view);
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        view.apply(WorldSettings.read(components));
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        view.save(output);
    }

    @Override
    protected void loadSaved(ValueInput input) {
        super.loadSaved(input);
        view.load(input);
    }
}
