package dev.silencebound.maxtech.storage.workbench;

import dev.silencebound.maxtech.storage.cell.CellAssembly;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import dev.silencebound.maxtech.lib.save.SaveFormat;
import dev.silencebound.maxtech.lib.save.VersionedBlockEntity;
import dev.silencebound.maxtech.storage.save.StorageSaveFormats;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * The cell workbench's one slot: the casing or cell being built. The parts live in the cell's own data, so this is all
 * the block keeps; the cell drops when the block is broken. No ticker, no power.
 */
public final class CellWorkbenchBlockEntity extends VersionedBlockEntity {
    private static final String CELL = "cell";

    private final SimpleContainer slot = new SimpleContainer(1) {
        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean canPlaceItem(int index, ItemStack stack) {
            return CellAssembly.casing(stack) != null;
        }

        @Override
        public void setChanged() {
            super.setChanged();
            CellWorkbenchBlockEntity.this.setChanged();
        }
    };

    public CellWorkbenchBlockEntity(BlockPos pos, BlockState state) {
        super(WorkbenchContent.BLOCK_ENTITY.get(), pos, state);
    }

    /** The casing slot, as the menu shows it. */
    public SimpleContainer slot() {
        return slot;
    }

    /** The casing or cell in the workbench; empty for none. */
    public ItemStack cell() {
        return slot.getItem(0);
    }

    /** Replaces what is in the workbench. */
    public void setCell(ItemStack stack) {
        slot.setItem(0, stack);
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level != null) {
            Containers.dropContents(level, pos, slot);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ItemStack stack = slot.getItem(0);
        output.storeNullable(CELL, ItemStack.CODEC, stack.isEmpty() ? null : stack);
    }

    @Override
    protected final SaveFormat saveFormat() {
        return StorageSaveFormats.BLOCK_ENTITIES;
    }

    @Override
    protected void loadSaved(ValueInput input) {
        super.loadSaved(input);
        // Not setItem: loading must not mark the block entity changed.
        slot.getItems().set(0, input.read(CELL, ItemStack.CODEC).orElse(ItemStack.EMPTY));
    }
}
