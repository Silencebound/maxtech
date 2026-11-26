package dev.silencebound.maxtech.storage.port;

import dev.silencebound.maxtech.storage.priority.PriorityControl;
import dev.silencebound.maxtech.storage.redstone.RedstoneControl;
import dev.silencebound.maxtech.storage.redstone.RedstoneMode;
import dev.silencebound.maxtech.storage.redstone.RedstoneTexts;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.research.ResearchNode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

/**
 * A port's settings in its item, the same for the block and the part and for every tier: the filter in vanilla's
 * {@code DataComponents.CONTAINER} (nine slots, listed by vanilla's tooltip), the mode in
 * {@code maxtech_storage:port_mode}, the speed cards in {@code maxtech_storage:port_speed_cards} and the storage bus's
 * access and priority in {@code maxtech_storage:port_access} and {@code maxtech_storage:port_priority} and the redstone
 * mode in {@code maxtech_storage:redstone_mode}, each only when it differs from a new port's, so a fresh port stacks
 * with others.
 */
public final class PortItems {
    private PortItems() {
    }

    /**
     * Writes {@code filter}, {@code mode}, the worker's held cards and its storage access and priority into
     * {@code components}, leaving out defaults.
     */
    static void write(DataComponentMap.Builder components, PortFilter filter, PortMode mode, PortWorker worker) {
        write(components, filter, mode, worker.cards());
        if (worker.order() != PortOrder.FILTER) {
            components.set(ModDataComponents.PORT_ORDER.get(), worker.order());
        }
        if (worker.busAccess() != BusAccess.BOTH) {
            components.set(ModDataComponents.PORT_ACCESS.get(), worker.busAccess());
        }
        if (worker.priority() != 0) {
            components.set(ModDataComponents.PORT_PRIORITY.get(), worker.priority());
        }
        worker.redstone().write(components);
    }

    /**
     * Sets the worker's storage access and priority from the item's components; defaults when it names none. Through
     * the worker's setters, so a live port saves and looks again.
     */
    static void readBus(DataComponentGetter components, PortWorker worker) {
        worker.setBusAccess(components.getOrDefault(ModDataComponents.PORT_ACCESS.get(), BusAccess.BOTH));
        worker.setPriority(components.getOrDefault(ModDataComponents.PORT_PRIORITY.get(), 0));
        worker.setOrder(components.getOrDefault(ModDataComponents.PORT_ORDER.get(), PortOrder.FILTER));
        worker.redstone().setMode(RedstoneControl.mode(components));
    }

    /** {@link #readBus} for a port being placed or loaded from its item: sets the values without saving or waking. */
    static void restoreBus(DataComponentGetter components, PortWorker worker) {
        worker.restoreBus(components.getOrDefault(ModDataComponents.PORT_ACCESS.get(), BusAccess.BOTH),
                components.getOrDefault(ModDataComponents.PORT_PRIORITY.get(), 0));
        worker.restoreOrder(components.getOrDefault(ModDataComponents.PORT_ORDER.get(), PortOrder.FILTER));
        worker.redstone().restore(RedstoneControl.mode(components));
    }

    /** Writes {@code filter}, {@code mode} and the held {@code cards} into {@code components}, leaving out defaults. */
    private static void write(DataComponentMap.Builder components, PortFilter filter, PortMode mode, PortCards cards) {
        if (!filter.isEmpty()) {
            List<ItemStack> slots = new ArrayList<>(PortFilter.SIZE);
            for (int i = 0; i < PortFilter.SIZE; i++) {
                slots.add(filter.getItem(i).copy());
            }
            components.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(slots));
            List<PortEntrySettings> entries = filter.settings();
            if (!entries.isEmpty()) {
                components.set(ModDataComponents.PORT_ENTRIES.get(), entries);
            }
        }
        if (mode != PortMode.IMPORT) {
            components.set(ModDataComponents.PORT_MODE.get(), mode);
        }
        if (cards.held() > 0) {
            components.set(ModDataComponents.PORT_SPEED_CARDS.get(), cards.held());
        }
        if (cards.heldCraftingCard()) {
            components.set(ModDataComponents.PORT_CRAFTING_CARD.get(), true);
        }
    }

    /** Puts the settings on {@code stack}. */
    static void write(ItemStack stack, PortFilter filter, PortMode mode, PortWorker worker) {
        DataComponentMap.Builder components = DataComponentMap.builder();
        write(components, filter, mode, worker);
        stack.applyComponents(components.build());
    }

    /**
     * Fills {@code filter} and its entries' settings from the item's components without saving or waking; a filter-less
     * item empties it, and an item without settings (made before they existed) leaves every slot exact.
     */
    static void readFilter(DataComponentGetter components, PortFilter filter) {
        filter.getItems().clear();
        ItemContainerContents contents = components.get(DataComponents.CONTAINER);
        if (contents != null) {
            contents.copyInto(filter.getItems());
        }
        filter.loadSettings(components.getOrDefault(ModDataComponents.PORT_ENTRIES.get(), List.of()));
        filter.rebuild();
    }

    /** Fills {@code cards} with the item's speed cards without saving or waking; none when it names none. */
    static void readCards(DataComponentGetter components, PortCards cards) {
        cards.fill(cards(components), components.getOrDefault(ModDataComponents.PORT_CRAFTING_CARD.get(), false));
    }

    /** The mode the item names; import when it names none. */
    static PortMode mode(DataComponentGetter components) {
        return components.getOrDefault(ModDataComponents.PORT_MODE.get(), PortMode.IMPORT);
    }

    /** The speed cards the item holds. */
    public static int cards(DataComponentGetter components) {
        return components.getOrDefault(ModDataComponents.PORT_SPEED_CARDS.get(), 0);
    }

    /**
     * The tooltip summary of a port of {@code tier}: the mode, the speed cards, and the research its tier needs (the
     * filter's items are vanilla's container lines).
     */
    public static void appendTooltip(PortTier tier, ItemStack stack, Consumer<Component> lines) {
        PortMode mode = mode(stack);
        lines.accept(Component.translatable(PortContent.MODE_KEY, Component.translatable(mode.translationKey())));
        if (mode == PortMode.STORAGE) {
            BusAccess access = stack.getOrDefault(ModDataComponents.PORT_ACCESS.get(), BusAccess.BOTH);
            lines.accept(
                    Component.translatable(PortContent.ACCESS_KEY, Component.translatable(access.translationKey())));
            int priority = stack.getOrDefault(ModDataComponents.PORT_PRIORITY.get(), 0);
            if (priority != 0) {
                lines.accept(PriorityControl.line(priority));
            }
        }
        if (mode == PortMode.EXPORT) {
            PortOrder order = stack.getOrDefault(ModDataComponents.PORT_ORDER.get(), PortOrder.FILTER);
            if (order != PortOrder.FILTER) {
                lines.accept(
                        Component.translatable(PortContent.ORDER_KEY, Component.translatable(order.translationKey())));
            }
        }
        RedstoneMode redstone = RedstoneControl.mode(stack);
        if (redstone != RedstoneMode.IGNORE) {
            lines.accept(RedstoneTexts.line(redstone));
        }
        int cards = cards(stack);
        if (cards > 0) {
            lines.accept(Component.translatable(PortContent.CARDS_KEY, cards));
        }
        if (stack.getOrDefault(ModDataComponents.PORT_CRAFTING_CARD.get(), false)) {
            lines.accept(Component.translatable(PortContent.CRAFTING_CARD_KEY));
        }
        Identifier research = tier.research();
        if (research != null) {
            lines.accept(researchLine(research));
        }
    }

    /** "Needs research: ..." with the unlock's name from the research lang keys. */
    public static Component researchLine(Identifier unlock) {
        return Component.translatable(PortContent.RESEARCH_KEY, Component.translatable(ResearchNode.unlockKey(unlock)));
    }

    /** The block port's item, with the settings summary in its tooltip. */
    public static final class PortBlockItem extends BlockItem {
        private final PortTier tier;

        public PortBlockItem(Block block, Properties properties) {
            super(block, properties);
            this.tier = block instanceof PortBlock port ? port.tier() : PortTier.TIER_1;
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                Consumer<Component> lines, TooltipFlag flag) {
            super.appendHoverText(stack, context, display, lines, flag);
            appendTooltip(tier, stack, lines);
        }
    }
}
