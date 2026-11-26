package dev.silencebound.maxtech.storage.display;

import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * A screen's settings in its item, the same for the panel block and the part: {@code maxtech_storage:display_settings},
 * written only when they differ from a new panel's so fresh panels stack.
 */
public final class DisplayItems {
    private DisplayItems() {
    }

    /** The settings {@code components} carry, or null for a fresh item. */
    static @Nullable DisplaySettings read(DataComponentGetter components) {
        return components.get(ModDataComponents.DISPLAY_SETTINGS.get());
    }

    /** Puts {@code settings} on {@code stack}, or nothing for the defaults. */
    static void write(ItemStack stack, DisplaySettings settings) {
        if (!settings.equals(DisplaySettings.DEFAULT)) {
            stack.set(ModDataComponents.DISPLAY_SETTINGS.get(), settings);
        }
    }

    /** The tooltip summary: the mode and source the screen shows. */
    public static void appendTooltip(ItemStack stack, Consumer<Component> lines) {
        DisplaySettings settings = read(stack);
        if (settings == null) {
            return;
        }
        lines.accept(Component
                .translatable(DisplayContent.SHOWS_KEY, Component.translatable(settings.source().translationKey()),
                        Component.translatable(settings.mode().translationKey()))
                .withStyle(ChatFormatting.GRAY));
    }

    /** The panel block's item, with the settings summary in its tooltip. */
    public static final class PanelItem extends BlockItem {
        public PanelItem(Block block, Item.Properties properties) {
            super(block, properties);
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                Consumer<Component> lines, TooltipFlag flag) {
            super.appendHoverText(stack, context, display, lines, flag);
            appendTooltip(stack, lines);
        }
    }
}
