package dev.silencebound.maxtech.storage.p2p;

import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

/**
 * A P2P end's link in its item, the same for the block and the part: {@code maxtech_storage:p2p_link}, only when
 * linked, so fresh ends stack.
 */
public final class P2PItems {
    private P2PItems() {
    }

    static void write(DataComponentMap.Builder components, P2PSettings settings) {
        if (settings.isLinked()) {
            components.set(ModDataComponents.P2P_LINK.get(), settings);
        }
    }

    static P2PSettings read(DataComponentGetter components) {
        return components.getOrDefault(ModDataComponents.P2P_LINK.get(), P2PSettings.NONE);
    }

    /** The tooltip summary: role and frequency, when linked. */
    public static void appendTooltip(ItemStack stack, Consumer<Component> lines) {
        P2PSettings settings = read(stack);
        if (settings.isLinked()) {
            lines.accept(Component.translatable(P2PContent.TOOLTIP_LINK_KEY,
                    Component.translatable(settings.role().translationKey()),
                    P2PEndpoint.frequencyText(settings.frequency())));
        }
    }

    /** A P2P block's item, with the link in its tooltip. */
    public static final class P2PBlockItem extends BlockItem {
        public P2PBlockItem(Block block, Properties properties) {
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
