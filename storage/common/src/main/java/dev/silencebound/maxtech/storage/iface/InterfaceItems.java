package dev.silencebound.maxtech.storage.iface;

import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.storage.cell.StorageCellItem;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.save.KeptValue;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

/**
 * An interface's settings and stock in its item, the same for the block and the part (see
 * {@link InterfaceWorker#write}), and the tooltip summary of both items.
 */
public final class InterfaceItems {
    /** Stock slots as {@code maxtech_storage:interface_kept_items} holds them: a slot is kept or read on its own. */
    public static final Codec<List<KeptValue<ItemStack>>> KEPT_ITEMS_CODEC = KeptValue.codec(ItemStack.OPTIONAL_CODEC)
            .listOf();

    private InterfaceItems() {
    }

    static DataComponentType<ItemContainerContents> configType() {
        return ModDataComponents.INTERFACE_CONFIG.get();
    }

    static DataComponentType<ItemContainerContents> cardType() {
        return ModDataComponents.INTERFACE_CARD.get();
    }

    static DataComponentType<List<KeptValue<FluidStock>>> fluidsType() {
        return ModDataComponents.INTERFACE_FLUIDS.get();
    }

    static DataComponentType<List<KeptValue<ItemStack>>> keptItemsType() {
        return ModDataComponents.INTERFACE_KEPT_ITEMS.get();
    }

    /**
     * The tooltip summary: how many stock slots are set and the fluid held (the items held are vanilla's container
     * lines).
     */
    public static void appendTooltip(ItemStack stack, Consumer<Component> lines) {
        ItemContainerContents config = stack.get(configType());
        if (config != null) {
            long set = config.nonEmptyItemCopyStream().count();
            if (set > 0) {
                lines.accept(Component.translatable(InterfaceContent.TOOLTIP_SLOTS_KEY, set));
            }
        }
        long fluid = 0;
        int kept = 0;
        for (KeptValue<FluidStock> tank : stack.getOrDefault(fluidsType(), List.<KeptValue<FluidStock>>of())) {
            FluidStock held = tank.value();
            if (held == null) {
                kept++;
            } else if (!held.isEmpty()) {
                fluid += held.amount();
            }
        }
        for (KeptValue<ItemStack> slot : stack.getOrDefault(keptItemsType(), List.<KeptValue<ItemStack>>of())) {
            if (slot.isKept()) {
                kept++;
            }
        }
        if (fluid > 0) {
            lines.accept(Component.translatable(InterfaceContent.TOOLTIP_FLUID_KEY, fluid));
        }
        if (kept > 0) {
            lines.accept(Component.translatable(StorageCellItem.KEPT_KEY, kept).withStyle(ChatFormatting.GOLD));
        }
        ItemContainerContents card = stack.get(cardType());
        if (card != null) {
            card.nonEmptyItemCopyStream().findFirst().ifPresent(held -> lines
                    .accept(Component.translatable(InterfaceContent.TOOLTIP_CARD_KEY, held.getHoverName())));
        }
    }

    /** The interface block's item, with the summary in its tooltip. */
    public static final class InterfaceBlockItem extends BlockItem {
        public InterfaceBlockItem(Block block, Properties properties) {
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
