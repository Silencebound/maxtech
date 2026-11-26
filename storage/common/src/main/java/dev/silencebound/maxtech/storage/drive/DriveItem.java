package dev.silencebound.maxtech.storage.drive;

import dev.silencebound.maxtech.storage.priority.PriorityControl;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * The drive's item. A drive broken while holding cells keeps them in vanilla's block entity data (their contents stay
 * in the save data) and its priority, and the tooltip names both (CLAUDE.md: stored state shows on the item). Reads the
 * saved tag only, so it works on the client.
 */
public final class DriveItem extends BlockItem {
    public static final String CELLS_KEY = "tooltip.maxtech_storage.drive.cells";

    public DriveItem(Block block, Properties properties) {
        super(block, properties);
    }

    /** Cells in a drive item's block entity data; 0 without data. */
    public static int cells(ItemStack stack) {
        TypedEntityData<BlockEntityType<?>> data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (data == null) {
            return 0;
        }
        ListTag stacks = data.copyTagWithoutId().getCompoundOrEmpty("cells").getListOrEmpty("stacks");
        int count = 0;
        for (Tag entry : stacks) {
            if (entry instanceof CompoundTag compound && compound.contains("id")) {
                count++;
            }
        }
        return count;
    }

    @Override
    @SuppressWarnings("deprecation") // overriding is how items add tooltip lines; only calling it is deprecated
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, lines, flag);
        int cells = cells(stack);
        if (cells > 0) {
            lines.accept(Component.translatable(CELLS_KEY, cells));
        }
        int priority = priority(stack);
        if (priority != 0) {
            lines.accept(PriorityControl.line(priority));
        }
    }

    /** The priority in a drive item's block entity data; 0 without data. */
    public static int priority(ItemStack stack) {
        TypedEntityData<BlockEntityType<?>> data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        return data == null ? 0 : data.copyTagWithoutId().getIntOr("priority", 0);
    }
}
