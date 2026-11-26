package dev.silencebound.maxtech.storage.condition;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/** The condition switch block's entity: holds the {@link ConditionWorker} and shows its output. Never ticks. */
public final class ConditionSwitchBlockEntity extends StorageDeviceBlockEntity
        implements
            ConditionDevice,
            ConditionWorker.Host {
    static final double RANGE_BUFFER = 4.0;

    private final ConditionWorker worker = new ConditionWorker(this, this);

    public ConditionSwitchBlockEntity(BlockPos pos, BlockState state) {
        super(ConditionContent.BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Role role() {
        return Role.OTHER;
    }

    @Override
    public ConditionWorker worker() {
        return worker;
    }

    @Override
    public boolean stillValid(Player player) {
        return !isRemoved() && player.isWithinBlockInteractionRange(getBlockPos(), RANGE_BUFFER);
    }

    @Override
    public @Nullable ServerLevel serverLevel() {
        return level instanceof ServerLevel server ? server : null;
    }

    /**
     * Sets {@link ConditionSwitchBlock#POWERED}; the block update tells the neighbours. A strength change in logic mode
     * that keeps the block powered tells them directly.
     */
    @Override
    public void outputChanged() {
        setChanged();
        BlockState state = getBlockState();
        if (level == null || isRemoved() || !state.hasProperty(ConditionSwitchBlock.POWERED)) {
            return;
        }
        if (state.getValue(ConditionSwitchBlock.POWERED) != worker.output()) {
            level.setBlock(worldPosition, state.setValue(ConditionSwitchBlock.POWERED, worker.output()),
                    Block.UPDATE_ALL);
        } else {
            level.updateNeighborsAt(worldPosition, state.getBlock());
        }
    }

    /**
     * The strongest redstone signal into the block from any side; -1 while a chunk the reading would touch is not
     * loaded (a powered neighbour looks at its own neighbours), so reading never loads one.
     */
    @Override
    public int redstoneIn() {
        if (level == null || !level.hasChunksAt(worldPosition.offset(-2, 0, -2), worldPosition.offset(2, 0, 2))) {
            return -1;
        }
        return level.getBestNeighborSignal(worldPosition);
    }

    @Override
    public void setOwner(OwnerRecord owner) {
        super.setOwner(owner);
        worker.ownerChanged();
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

    /** Broken or unloaded: job ends no longer need to reach it. */
    @Override
    public void setRemoved() {
        worker.stop();
        super.setRemoved();
    }

    @Override
    public void addGlance(BlockInfoRequest request, BlockInfoSink sink) {
        ConditionTexts.glance(worker, sink);
    }

    @Override
    public void addDetails(BlockInfoRequest request, BlockInfoSink sink) {
        ConditionTexts.info(worker, sink);
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        ConditionTexts.write(components, worker.settings());
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        worker.setSettings(ConditionTexts.read(components));
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
