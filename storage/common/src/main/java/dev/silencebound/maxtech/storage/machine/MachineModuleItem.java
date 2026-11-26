package dev.silencebound.maxtech.storage.machine;

import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import org.jspecify.annotations.Nullable;

/** An upgrade module; its {@link MachineModule} decides where it fits and what it does. One per stack. */
public final class MachineModuleItem extends Item {
    private final MachineModule module;
    private final String tooltipKey;

    public MachineModuleItem(MachineModule module, String tooltipKey, Properties properties) {
        super(properties.stacksTo(1));
        this.module = module;
        this.tooltipKey = tooltipKey;
    }

    public MachineModule module() {
        return module;
    }

    /** The module {@code stack} holds, or {@code null} for anything else. Allocation-free; machines ask per run. */
    public static @Nullable MachineModule of(ItemStack stack) {
        return stack.getItem() instanceof MachineModuleItem item ? item.module : null;
    }

    @Override
    @SuppressWarnings("deprecation") // overriding is how items add tooltip lines; only calling it is deprecated
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, lines, flag);
        lines.accept(Component.translatable(tooltipKey));
    }
}
