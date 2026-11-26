package dev.silencebound.maxtech.storage.ioport;

import dev.silencebound.maxtech.storage.drive.DriveItem;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

/**
 * The IO port's item. A port broken while holding cells keeps them in vanilla's block entity data (their contents stay
 * in the save data), and the tooltip says how many. Reads the saved tag only, so it works on the client.
 */
public final class IoPortItem extends BlockItem {
    public IoPortItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    @SuppressWarnings("deprecation") // overriding is how items add tooltip lines; only calling it is deprecated
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, lines, flag);
        // The port saves its slots under the drive's name, so the drive's count reads them.
        int cells = DriveItem.cells(stack);
        if (cells > 0) {
            lines.accept(Component.translatable(DriveItem.CELLS_KEY, cells));
        }
    }
}
