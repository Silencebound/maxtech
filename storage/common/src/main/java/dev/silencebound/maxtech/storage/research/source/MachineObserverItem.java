package dev.silencebound.maxtech.storage.research.source;

import java.util.function.Consumer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * The machine observer's item. A broken observer keeps its count, day and points given that day in vanilla's block
 * entity data, so the daily cap survives breaking and placing it again, and the tooltip shows them (CLAUDE.md: stored
 * state shows on the item). Reads the saved tag only, so it works on the client.
 */
public final class MachineObserverItem extends BlockItem {
    public static final String POINTS_KEY = "tooltip.maxtech_storage.machine_observer.points";
    public static final String RUNS_KEY = "tooltip.maxtech_storage.machine_observer.runs";

    public MachineObserverItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    @SuppressWarnings("deprecation") // overriding is how items add tooltip lines; only calling it is deprecated
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, lines, flag);
        TypedEntityData<BlockEntityType<?>> data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (data == null) {
            return;
        }
        CompoundTag tag = data.copyTagWithoutId();
        lines.accept(Component.translatable(POINTS_KEY, tag.getIntOr("points_today", 0), tag.getLongOr("day", 0L)));
        lines.accept(Component.translatable(RUNS_KEY, tag.getIntOr("runs", 0)));
    }
}
