package dev.silencebound.maxtech.storage.port;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.lib.tool.ToolTags;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.part.PartType;
import dev.silencebound.maxtech.storage.network.StorageProvider;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * A port of one {@link PortTier} as a part on a storage cable's face: the same {@link PortWorker} as the block, working
 * on the block beyond the cable's face, at the same rates and on the network's scheduler. Its mode is the part's own
 * (the look variant: {@link PortMode#id()}, 0 import, 1 export, 2 storage); its filter, mode, speed cards and storage
 * settings stay in the item when it comes off. Never ticks.
 */
public final class PortPart extends CablePart implements PortDevice {
    private static final String MODE = "mode";

    private final PortWorker worker;
    private PortMode mode = PortMode.IMPORT;

    public PortPart(PartType type, CableParts host, Direction face, PortTier tier) {
        super(type, host, face);
        worker = new PortWorker(deviceLink(), tier, this, this::mode, host::changed);
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

    @Override
    public PortMode mode() {
        return mode;
    }

    @Override
    public void setMode(PortMode mode) {
        if (this.mode == mode) {
            return;
        }
        PortMode before = this.mode;
        this.mode = mode;
        host().changed();
        host().lookChanged();
        worker.modeChanged(before);
    }

    /** In storage mode, the inventory beyond the face as network storage ({@link PortStorage}); else nothing. */
    @Override
    public List<StorageProvider> providers() {
        return worker.providers();
    }

    @Override
    public void stoppedChanged(boolean stopped) {
        worker.stoppedChanged(stopped);
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
    public int lookVariant() {
        return mode.id();
    }

    @Override
    public boolean stillValid(Player player) {
        return !isRemoved() && player.isWithinBlockInteractionRange(pos(), PortBlockEntity.RANGE_BUFFER);
    }

    @Override
    protected void start(ServerLevel level) {
        worker.start(level, pos(), face(), () -> !isRemoved());
    }

    /**
     * Only the block the part faces matters for the inventory; an unknown neighbour may be it. A block update
     * ({@code from} null) may also be the redstone signal at the cable (B09 item 9).
     */
    @Override
    public void neighbourChanged(@Nullable BlockPos from) {
        if (from == null) {
            worker.redstoneChanged();
        }
        if (from == null || from.equals(facedPos())) {
            worker.inventoryChanged();
        }
    }

    /** Opens the port's GUI for players its owner lets in. */
    @Override
    public InteractionResult use(Player player) {
        if (player instanceof ServerPlayer serverPlayer && NetworkAccess.canConfigure(player, this)) {
            PortMenu.open(serverPlayer, this, pos(), type().item().getName(toItem()));
        }
        return InteractionResult.SUCCESS;
    }

    /** A wrench switches the mode, as on the block. */
    @Override
    public InteractionResult useItemOn(ItemStack stack, Player player, InteractionHand hand) {
        if (!stack.is(ToolTags.ACTS_AS_WRENCH)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if (!player.level().isClientSide() && NetworkAccess.canConfigure(player, this)) {
            setMode(mode.next());
        }
        return InteractionResult.SUCCESS;
    }

    /** The part's item with its filter, mode and speed cards ({@link PortItems}). */
    @Override
    public ItemStack toItem() {
        ItemStack stack = super.toItem();
        PortItems.write(stack, filter(), mode, worker);
        return stack;
    }

    @Override
    protected void readItem(ItemStack stack) {
        PortItems.readFilter(stack, filter());
        PortItems.readCards(stack, worker.cards());
        PortItems.restoreBus(stack, worker);
        mode = PortItems.mode(stack);
    }

    @Override
    protected void save(ValueOutput output) {
        super.save(output);
        output.putString(MODE, mode.getSerializedName());
        worker.save(output);
    }

    @Override
    protected void load(ValueInput input) {
        super.load(input);
        mode = PortMode.byName(input.getStringOr(MODE, ""));
        worker.load(input);
    }
}
