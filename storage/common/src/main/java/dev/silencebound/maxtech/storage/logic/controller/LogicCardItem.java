package dev.silencebound.maxtech.storage.logic.controller;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.registry.ModContent;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * The logic card (M7L2 sharing): blank, it stacks; the Logic Controller's "To card" writes one rule onto one card
 * ({@link LogicCard} in {@link ModDataComponents#LOGIC_CARD}, with a stack size of one), and "From card" adds that rule
 * to another controller. A written card can be renamed in an anvil and handed to anyone; using it while sneaking wipes
 * it blank again.
 */
public final class LogicCardItem extends Item {
    public static final String RULE = key("rule");
    public static final String NODES = key("nodes");
    public static final String BLANK = key("blank");
    public static final String WIPE = key("wipe");

    static {
        ModContent.CONTENT.translation(RULE, "Rule: %s");
        ModContent.CONTENT.translation(NODES, "%s nodes");
        ModContent.CONTENT.translation(BLANK, "Blank: write a rule on it at a Logic Controller");
        ModContent.CONTENT.translation(WIPE, "Use while sneaking to wipe it");
    }

    public LogicCardItem(Properties properties) {
        super(properties);
    }

    /** Loads the class during mod construction so its lang lines are declared. */
    static void init() {
    }

    private static String key(String path) {
        return "item." + MaxtechStorage.MOD_ID + ".logic_card." + path;
    }

    /** The rule on {@code stack}, or null when it is blank. */
    public static @Nullable LogicCard card(ItemStack stack) {
        return stack.get(ModDataComponents.LOGIC_CARD.get());
    }

    /** Writes {@code card} onto {@code stack} (one card), which then no longer stacks. */
    public static void write(ItemStack stack, LogicCard card) {
        stack.set(ModDataComponents.LOGIC_CARD.get(), card);
        stack.set(DataComponents.MAX_STACK_SIZE, 1);
    }

    /** Wipes {@code stack} blank, so it stacks with other blank cards again. */
    public static void wipe(ItemStack stack) {
        stack.remove(ModDataComponents.LOGIC_CARD.get());
        stack.remove(DataComponents.MAX_STACK_SIZE);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown() || card(stack) == null) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide()) {
            wipe(stack);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    @SuppressWarnings("deprecation") // Overridden, not called: Item marks the hook deprecated.
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> builder, TooltipFlag flag) {
        LogicCard card = card(stack);
        if (card == null) {
            builder.accept(Component.translatable(BLANK).withStyle(ChatFormatting.GRAY));
            return;
        }
        String name = card.name().isEmpty() ? "-" : card.name();
        builder.accept(Component.translatable(RULE, name).withStyle(ChatFormatting.DARK_AQUA));
        builder.accept(Component.translatable(NODES, card.nodes()).withStyle(ChatFormatting.GRAY));
        builder.accept(Component.translatable(WIPE).withStyle(ChatFormatting.DARK_GRAY));
    }
}
