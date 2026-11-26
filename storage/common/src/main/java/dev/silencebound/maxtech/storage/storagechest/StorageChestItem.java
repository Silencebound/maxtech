package dev.silencebound.maxtech.storage.storagechest;

import java.util.function.Consumer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * The storage chest's item: a chest broken while holding something keeps it in vanilla's block entity data, and the
 * tooltip says how many slots are used (CLAUDE.md: stored state shows on the item). Reads the saved count only, so it
 * works on the client.
 */
public final class StorageChestItem extends BlockItem {
    public static final String HOLDS_KEY = "tooltip.maxtech_storage.storage_chest.holds";

    public StorageChestItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    @SuppressWarnings("deprecation") // overriding is how items add tooltip lines; only calling it is deprecated
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, lines, flag);
        TypedEntityData<BlockEntityType<?>> data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        int used = data == null ? 0 : data.copyTagWithoutId().getIntOr("used_slots", 0);
        if (used > 0) {
            lines.accept(Component.translatable(HOLDS_KEY, used, StorageChestBlockEntity.SLOTS));
        }
    }
}
