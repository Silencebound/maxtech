package dev.silencebound.maxtech.storage.iface;

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
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * The interface as a part on a storage cable's face: the same {@link InterfaceWorker} as the block, offering its
 * handlers only to the block beyond its face (Storage's NeoForge glue asks the cable's part on the queried side). Its
 * settings and stock stay in the item when it comes off. Never ticks.
 */
public final class InterfacePart extends CablePart implements InterfaceDevice {
    private final InterfaceWorker worker = new InterfaceWorker(this, host()::changed);

    public InterfacePart(PartType type, CableParts host, Direction face) {
        super(type, host, face);
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

    /** As the block: one operation a second at rest. */
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
        return !isRemoved() && player.isWithinBlockInteractionRange(pos(), InterfaceBlockEntity.RANGE_BUFFER);
    }

    /** Starts the worker and makes the faced block ask the cable for handlers again. */
    @Override
    protected void start(ServerLevel level) {
        worker.start(level);
        // PLATFORM-LEAK: NeoForge's capability cache; the cable now offers handlers on this face.
        level.invalidateCapabilities(pos());
    }

    /** A block update at the cable may be the redstone signal (B09 item 9). */
    @Override
    public void neighbourChanged(@Nullable BlockPos from) {
        if (from == null) {
            worker.redstoneChanged();
        }
    }

    /** Opens the interface's GUI for players its owner lets in. */
    @Override
    public InteractionResult use(Player player) {
        if (player instanceof ServerPlayer serverPlayer && NetworkAccess.canConfigure(player, this)) {
            InterfaceMenu.open(serverPlayer, this, pos(), type().item().getName(toItem()));
        }
        return InteractionResult.SUCCESS;
    }

    /** The part's item with its settings and stock. */
    @Override
    public ItemStack toItem() {
        ItemStack stack = super.toItem();
        DataComponentMap.Builder components = DataComponentMap.builder();
        worker.write(components);
        stack.applyComponents(components.build());
        return stack;
    }

    @Override
    protected void readItem(ItemStack stack) {
        worker.read(stack);
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
