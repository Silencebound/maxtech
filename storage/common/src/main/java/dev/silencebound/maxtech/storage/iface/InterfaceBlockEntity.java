package dev.silencebound.maxtech.storage.iface;

import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** The interface block's block entity: an {@link InterfaceWorker} shared with the part form. Never ticks. */
public final class InterfaceBlockEntity extends StorageDeviceBlockEntity implements InterfaceDevice {
    /** How far beyond block reach the menu stays usable, as for vanilla containers. */
    static final double RANGE_BUFFER = 4.0;

    private final InterfaceWorker worker = new InterfaceWorker(this, this::setChanged);

    public InterfaceBlockEntity(BlockPos pos, BlockState state) {
        super(InterfaceContent.BLOCK_ENTITY.get(), pos, state);
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

    /** One operation a second at rest; stock refills and neighbours' calls come in bursts. */
    @Override
    public int operationsPerSecond() {
        return 1;
    }

    @Override
    public InterfaceWorker worker() {
        return worker;
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

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) {
            worker.start(server);
        }
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        worker.write(components);
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        worker.read(components);
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
