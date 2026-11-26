package dev.silencebound.maxtech.storage.crafting.pattern;

import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Which vanilla table recipe a {@link CraftingPattern.Kind#CRAFTING crafting} pattern runs on the crafting assembler
 * (B09 item 16). The pattern format has no field for it: the pattern's recipe type tells, by vanilla's fixed recipe
 * type ids, so stonecutting and smithing patterns are written in the same format by every path and older patterns
 * (crafting, or no recipe type) load unchanged as crafting patterns. A processing pattern with one of these recipe
 * types (from a recipe viewer before item 16, or for another mod's machine) stays a processing pattern. Pure; any
 * thread.
 */
public enum PatternTable {
    /** A crafting-table recipe on a grid up to 3 x 3; the inputs are the filled cells in grid order. */
    CRAFTING,
    /** A stonecutter recipe: one input of one item. */
    STONECUTTING,
    /** A smithing-table recipe: template, base and addition in that order, an empty one left out. */
    SMITHING;

    /** The vanilla recipe type id that marks a pattern of this table. */
    public Identifier recipeType() {
        return switch (this) {
            case CRAFTING -> Ids.CRAFTING;
            case STONECUTTING -> Ids.STONECUTTING;
            case SMITHING -> Ids.SMITHING;
        };
    }

    /** Whether the recipe's own crafting remainders come back (buckets of a cake); only the crafting table has them. */
    public boolean remainders() {
        return this == CRAFTING;
    }

    /** The table {@code pattern} runs on, or null for a processing pattern. */
    public static @Nullable PatternTable of(CraftingPattern pattern) {
        if (pattern.kind() != CraftingPattern.Kind.CRAFTING) {
            return null;
        }
        return byType(pattern.recipeType());
    }

    /** The table of a crafting pattern with recipe type {@code type}: crafting unless it names another table. */
    public static PatternTable byType(Optional<Identifier> type) {
        if (type.isPresent()) {
            for (PatternTable table : values()) {
                if (table.recipeType().equals(type.get())) {
                    return table;
                }
            }
        }
        return CRAFTING;
    }

    /** Whether {@code pattern} is a crafting pattern whose recipe leaves its crafting remainders behind. */
    public static boolean hasRemainders(CraftingPattern pattern) {
        PatternTable table = of(pattern);
        return table != null && table.remainders();
    }

    /** The ids, outside the enum so its constants hold no mutable-looking state. */
    private static final class Ids {
        static final Identifier CRAFTING = Identifier.withDefaultNamespace("crafting");
        static final Identifier STONECUTTING = Identifier.withDefaultNamespace("stonecutting");
        static final Identifier SMITHING = Identifier.withDefaultNamespace("smithing");
    }
}
