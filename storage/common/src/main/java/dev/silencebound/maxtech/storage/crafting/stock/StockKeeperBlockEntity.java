package dev.silencebound.maxtech.storage.crafting.stock;

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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/** The stock keeper's block entity: holds the {@link StockKeeperWorker} and the redstone input. Never ticks. */
public final class StockKeeperBlockEntity extends StorageDeviceBlockEntity implements StockKeeperWorker.Host {
    static final double RANGE_BUFFER = 4.0;
    private static final String POWERED = "powered";

    private final StockKeeperWorker worker = new StockKeeperWorker(this, this);
    private boolean powered;

    public StockKeeperBlockEntity(BlockPos pos, BlockState state) {
        super(StockKeeperContent.BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Role role() {
        return Role.OTHER;
    }

    public StockKeeperWorker worker() {
        return worker;
    }

    /** Whether {@code player} may keep the GUI open: the keeper is still there and in reach. */
    public boolean stillValid(Player player) {
        return !isRemoved() && player.isWithinBlockInteractionRange(getBlockPos(), RANGE_BUFFER);
    }

    @Override
    public @Nullable ServerLevel serverLevel() {
        return level instanceof ServerLevel server ? server : null;
    }

    @Override
    public boolean powered() {
        return powered;
    }

    void setPowered(boolean powered) {
        if (this.powered != powered) {
            this.powered = powered;
            setChanged();
            worker.inputsChanged();
        }
    }

    @Override
    public void rulesChanged() {
        setChanged();
    }

    @Override
    public void setOwner(OwnerRecord owner) {
        super.setOwner(owner);
        worker.inputsChanged();
    }

    @Override
    public void networkChanged(@Nullable StorageNetwork network) {
        worker.networkChanged(network);
    }

    // PLATFORM-LEAK: NeoForge's IBlockEntityExtension hook, through the base class.
    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) {
            powered = server.hasNeighborSignal(worldPosition);
            worker.start();
        }
    }

    @Override
    public void addDetails(BlockInfoRequest request, BlockInfoSink sink) {
        StockTexts.info(worker, sink);
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        StockTexts.write(components, worker.usedRules());
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        worker.setRules(StockTexts.read(components));
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        worker.save(output);
        output.putBoolean(POWERED, powered);
    }

    @Override
    protected void loadSaved(ValueInput input) {
        super.loadSaved(input);
        worker.load(input);
        powered = input.getBooleanOr(POWERED, false);
    }
}
