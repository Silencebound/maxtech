package dev.silencebound.maxtech.storage.machine;

import java.util.function.Consumer;
import net.minecraft.core.component.DataComponents;
import dev.silencebound.maxtech.storage.save.StorageSaveFormats;
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
 * A Storage machine's item. A machine broken while holding something keeps it in vanilla's block entity data, and the
 * tooltip summarises it (CLAUDE.md: stored state shows on the item): the stored FE and whether items are inside. Reads
 * the saved tag only, so it works on the client.
 */
public final class StorageMachineItem extends BlockItem {
    public static final String ENERGY_KEY = "tooltip.maxtech_storage.machine.energy";
    public static final String ITEMS_KEY = "tooltip.maxtech_storage.machine.items";

    public StorageMachineItem(Block block, Properties properties) {
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
        long energy = StorageSaveFormats.savedMu(tag);
        if (energy > 0) {
            lines.accept(Component.translatable(ENERGY_KEY, energy));
        }
        if (tag.getBooleanOr("holds_items", false)) {
            lines.accept(Component.translatable(ITEMS_KEY));
        }
    }
}
