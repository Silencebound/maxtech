package dev.silencebound.maxtech.storage.controller;

import dev.silencebound.maxtech.storage.energy.ItemEnergy;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.controller.module.SecuritySettings;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

/** The controller core's item: its tooltip names the FE and the security rules it keeps from the broken block. */
public final class ControllerItem extends BlockItem {
    public ControllerItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, lines, flag);
        long stored = ItemEnergy.get(stack, ModDataComponents.CONTROLLER_ENERGY.get());
        if (stored > 0) {
            lines.accept(Component.translatable(ControllerContent.ENERGY_TOOLTIP_KEY, InfoFormat.number(stored)));
        }
        SecuritySettings security = stack.get(ModDataComponents.NETWORK_SECURITY.get());
        if (security != null) {
            lines.accept(Component.translatable(ExpansionContent.SECURITY_TOOLTIP_KEY,
                    InfoFormat.number(security.players().size() + security.teams().size())));
        }
    }
}
