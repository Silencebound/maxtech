package dev.silencebound.maxtech.storage.sensor;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.part.PartType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * The sensor as a part on a storage cable's face: the same {@link SensorWorker} as the block, reading the block its
 * face points at. Never ticks.
 */
public final class SensorPart extends CablePart implements SensorDevice, SensorWorker.Host {
    private final SensorWorker worker = new SensorWorker(this, this);

    public SensorPart(PartType type, CableParts host, Direction face) {
        super(type, host, face);
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

    @Override
    public boolean stillValid(Player player) {
        return !isRemoved() && player.isWithinBlockInteractionRange(pos(), SensorBlockEntity.RANGE_BUFFER);
    }

    @Override
    public @Nullable ServerLevel serverLevel() {
        Level level = host().level();
        return level instanceof ServerLevel server ? server : null;
    }

    @Override
    public BlockPos sensorPos() {
        return pos();
    }

    @Override
    public Direction facing() {
        return face();
    }

    @Override
    public void settingsChanged() {
        host().changed();
    }

    @Override
    public void networkChanged(@Nullable StorageNetwork network) {
        worker.networkChanged(network);
    }

    @Override
    protected void start(ServerLevel level) {
        worker.start();
    }

    /**
     * Only the block the part faces matters. A block update names no position, so it may be the target: read with fresh
     * lookups; a comparator update from the target reads with the cached ones.
     */
    @Override
    public void neighbourChanged(@Nullable BlockPos from) {
        if (from == null) {
            worker.targetChanged(true);
        } else if (from.equals(facedPos())) {
            worker.targetChanged(false);
        }
    }

    /** Opens the screen for players the owner lets in. */
    @Override
    public InteractionResult use(Player player) {
        if (player instanceof ServerPlayer serverPlayer && NetworkAccess.canConfigure(player, this)) {
            SensorMenu.open(serverPlayer, this, pos(), type().item().getName(toItem()));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void addGlance(BlockInfoRequest request, BlockInfoSink sink) {
        SensorTexts.glance(worker, sink);
    }

    @Override
    public void addDetails(BlockInfoRequest request, BlockInfoSink sink) {
        SensorTexts.info(worker, sink);
    }

    /** The part's item with its settings, like the block's. */
    @Override
    public ItemStack toItem() {
        ItemStack stack = super.toItem();
        DataComponentMap.Builder components = DataComponentMap.builder();
        SensorTexts.write(components, worker.settings());
        stack.applyComponents(components.build());
        return stack;
    }

    @Override
    protected void readItem(ItemStack stack) {
        worker.loadSettings(SensorTexts.read(stack));
    }

    @Override
    protected void save(ValueOutput output) {
        super.save(output);
        worker.save(output);
    }

    @Override
    protected void load(ValueInput input) {
        super.load(input);
        worker.load(input);
    }
}
