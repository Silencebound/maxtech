package dev.silencebound.maxtech.storage.crafting.pattern;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.resource.StackKeys;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.save.KeptValue;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
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
 * An encoded pattern: a {@link CraftingPattern} in the {@code encoded_pattern} data component. Its tooltip lists what
 * the pattern makes and takes; sneak-using it clears it back into blank patterns. Pattern providers (M7c) hold these.
 */
public final class PatternItem extends Item {
    /** The lowest pattern priority (M7C item 2). */
    public static final int MIN_PRIORITY = -999;
    /** The highest pattern priority. */
    public static final int MAX_PRIORITY = 999;

    public PatternItem(Properties properties) {
        super(properties);
    }

    /**
     * The pattern {@code stack} carries, or null for a blank or foreign stack and for a pattern that no longer loads
     * ({@link #unreadable}), so such a pattern is never offered for crafting.
     */
    public static @Nullable CraftingPattern pattern(ItemStack stack) {
        KeptValue<CraftingPattern> pattern = stack.get(ModDataComponents.ENCODED_PATTERN.get());
        return pattern == null ? null : pattern.value();
    }

    /**
     * Whether {@code stack} carries a pattern that no longer loads (an ingredient or output of a removed mod): it is
     * kept as saved and works again once the mod is back.
     */
    public static boolean unreadable(ItemStack stack) {
        KeptValue<CraftingPattern> pattern = stack.get(ModDataComponents.ENCODED_PATTERN.get());
        return pattern != null && pattern.isKept();
    }

    /** An encoded pattern stack carrying {@code pattern}. */
    public static ItemStack encode(CraftingPattern pattern) {
        ItemStack stack = new ItemStack(PatternContent.ENCODED_PATTERN.get());
        stack.set(ModDataComponents.ENCODED_PATTERN.get(), KeptValue.of(pattern));
        return stack;
    }

    /**
     * The priority of the pattern {@code stack} carries (M7C item 2): among patterns making the same item the planner
     * tries the highest first. 0 when none is set.
     */
    public static int priority(ItemStack stack) {
        Integer priority = stack.get(ModDataComponents.PATTERN_PRIORITY.get());
        return priority == null ? 0 : priority;
    }

    /** Sets {@code stack}'s priority, clamped to {@link #MIN_PRIORITY} and {@link #MAX_PRIORITY}; 0 removes it. */
    public static void setPriority(ItemStack stack, int priority) {
        int clamped = Math.clamp(priority, MIN_PRIORITY, MAX_PRIORITY);
        if (clamped == 0) {
            stack.remove(ModDataComponents.PATTERN_PRIORITY.get());
        } else {
            stack.set(ModDataComponents.PATTERN_PRIORITY.get(), clamped);
        }
    }

    /** Sneak-use clears the stack into as many blank patterns. */
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!player.isSecondaryUseActive()) {
            return InteractionResult.PASS;
        }
        if (level instanceof ServerLevel) {
            ItemStack held = player.getItemInHand(hand);
            player.setItemInHand(hand, new ItemStack(PatternContent.BLANK_PATTERN.get(), held.getCount()));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> builder, TooltipFlag flag) {
        CraftingPattern pattern = pattern(stack);
        if (pattern == null) {
            if (unreadable(stack)) {
                builder.accept(Component.translatable(PatternContent.UNREADABLE_KEY).withStyle(ChatFormatting.RED));
                return;
            }
            builder.accept(Component.translatable(PatternContent.INVALID_KEY).withStyle(ChatFormatting.RED));
            return;
        }
        PatternTable table = PatternTable.of(pattern);
        String kind = table == null ? PatternContent.PROCESSING_KEY : switch (table) {
            case CRAFTING -> PatternContent.CRAFTING_KEY;
            case STONECUTTING -> PatternContent.STONECUTTING_KEY;
            case SMITHING -> PatternContent.SMITHING_KEY;
        };
        builder.accept(Component.translatable(kind).withStyle(ChatFormatting.DARK_AQUA));
        int priority = priority(stack);
        if (priority != 0) {
            builder.accept(
                    Component.translatable(PatternContent.PRIORITY_KEY, priority).withStyle(ChatFormatting.GRAY));
        }
        builder.accept(Component.translatable(PatternContent.MAKES_KEY).withStyle(ChatFormatting.GRAY));
        for (int i = 0; i < pattern.outputs().size(); i++) {
            PatternOutput output = pattern.outputs().get(i);
            MutableComponent line = Component.literal("  ").append(amount(output.key(), output.amount())).append(" ")
                    .append(name(output.key()));
            if (!output.guaranteed()) {
                line.append(Component.translatable(PatternContent.CHANCE_KEY, Math.round(output.chance() * 100)));
            }
            if (i > 0) {
                line.append(Component.translatable(PatternContent.BYPRODUCT_KEY));
            }
            builder.accept(line.withStyle(ChatFormatting.WHITE));
        }
        builder.accept(Component.translatable(PatternContent.TAKES_KEY).withStyle(ChatFormatting.GRAY));
        for (int i = 0; i < pattern.inputs().size(); i++) {
            PatternInput input = pattern.inputs().get(i);
            MutableComponent line = Component.literal("  ").append(amount(input.ingredient(), input.amount()))
                    .append(" ").append(name(input.ingredient()));
            input.face().ifPresent(face -> line.append(Component.translatable(PatternContent.FACE_KEY,
                    Component.translatable(PatternContent.faceKey(face)))));
            if (PatternReturns.returned(pattern, i)) {
                line.append(Component.translatable(PatternContent.RETURNED_KEY));
            }
            builder.accept(line.withStyle(ChatFormatting.WHITE));
        }
    }

    private static String amount(PatternIngredient ingredient, long amount) {
        return ingredient instanceof PatternIngredient.FluidTag
                || ingredient instanceof PatternIngredient.Exact(FluidKey fluid) ? amount + " mB" : amount + "x";
    }

    private static String amount(StackKey key, long amount) {
        return key instanceof FluidKey ? amount + " mB" : amount + "x";
    }

    /** The display name of a key: an item's hover name, a fluid's name. */
    public static Component name(StackKey key) {
        return switch (key) {
            case ItemKey item -> item.toStack(1).getHoverName();
            case FluidKey fluid -> StackKeys.toResource(fluid).getHoverName();
        };
    }

    /** An ingredient's display name: the key's, or {@code #tag} for a tag. */
    public static Component name(PatternIngredient ingredient) {
        return switch (ingredient) {
            case PatternIngredient.Exact exact -> name(exact.key());
            case PatternIngredient.ItemTag tag -> Component.literal("#" + tag.tag().location());
            case PatternIngredient.FluidTag tag -> Component.literal("#" + tag.tag().location());
        };
    }
}
