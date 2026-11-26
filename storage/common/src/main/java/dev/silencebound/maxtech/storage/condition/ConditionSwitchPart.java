package dev.silencebound.maxtech.storage.condition;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
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
 * The condition switch as a part on a storage cable's face: the same {@link ConditionWorker} as the block, giving its
 * weak redstone signal to the block its face points at only. Its output is the look variant (0 off, 1 on). For a link
 * load condition it watches the route from its cable, so it can sit on the trunk it reports. Never ticks.
 */
public final class ConditionSwitchPart extends CablePart implements ConditionDevice, ConditionWorker.Host {
    private final ConditionWorker worker = new ConditionWorker(this, this);

    public ConditionSwitchPart(PartType type, CableParts host, Direction face) {
        super(type, host, face);
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
        return !isRemoved() && player.isWithinBlockInteractionRange(pos(), ConditionSwitchBlockEntity.RANGE_BUFFER);
    }

    @Override
    public int signal() {
        return worker.strength();
    }

    @Override
    public int lookVariant() {
        return worker.output() ? 1 : 0;
    }

    @Override
    public @Nullable ServerLevel serverLevel() {
        Level level = host().level();
        return level instanceof ServerLevel server ? server : null;
    }

    @Override
    public void outputChanged() {
        if (isRemoved()) {
            return;
        }
        host().changed();
        host().lookChanged();
        signalChanged();
    }

    /** The signal the faced block gives towards the cable; -1 while a chunk the reading would touch is not loaded. */
    @Override
    public int redstoneIn() {
        Level level = host().level();
        BlockPos faced = facedPos();
        if (level == null || !level.hasChunksAt(faced.offset(-1, 0, -1), faced.offset(1, 0, 1))) {
            return -1;
        }
        return level.getSignal(faced, face());
    }

    @Override
    public void neighbourChanged(@Nullable BlockPos from) {
        if (from == null || from.equals(facedPos())) {
            worker.inputChanged();
        }
    }

    @Override
    public void setOwner(OwnerRecord owner) {
        super.setOwner(owner);
        worker.ownerChanged();
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
     * Taken off or its cable broken: job ends no longer need to reach it. A cable unloading with its chunk gives no
     * call; the crafting dispatch drops the switch then because it reads as removed.
     */
    @Override
    protected void removed() {
        worker.stop();
    }

    /** Opens the GUI for players the owner lets in. */
    @Override
    public InteractionResult use(Player player) {
        if (player instanceof ServerPlayer serverPlayer && NetworkAccess.canConfigure(player, this)) {
            ConditionMenu.open(serverPlayer, this, pos(), type().item().getName(toItem()));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void addGlance(BlockInfoRequest request, BlockInfoSink sink) {
        ConditionTexts.glance(worker, sink);
    }

    @Override
    public void addDetails(BlockInfoRequest request, BlockInfoSink sink) {
        ConditionTexts.info(worker, sink);
    }

    /** The part's item with its settings, like the block's. */
    @Override
    public ItemStack toItem() {
        ItemStack stack = super.toItem();
        DataComponentMap.Builder components = DataComponentMap.builder();
        ConditionTexts.write(components, worker.settings());
        stack.applyComponents(components.build());
        return stack;
    }

    @Override
    protected void readItem(ItemStack stack) {
        worker.loadSettings(ConditionTexts.read(stack));
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
