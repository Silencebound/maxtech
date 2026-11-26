package dev.silencebound.maxtech.lib.viewer;

import dev.silencebound.maxtech.lib.api.recipe.ItemInput;
import dev.silencebound.maxtech.lib.api.recipe.ItemOutput;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Recipe viewer text: category title keys, the duration and mode lines and the input and output tooltip lines.
 * Storage's JEI categories use it.
 */
public final class RecipeViewText {
    public static final String DURATION_KEY = "recipe_viewer.maxtech.duration";
    public static final String MODE_KEY = "recipe_viewer.maxtech.mode";
    public static final String CHANCE_KEY = "recipe_viewer.maxtech.chance";
    public static final String NOT_CONSUMED_KEY = "recipe_viewer.maxtech.not_consumed";

    private RecipeViewText() {
    }

    /** The tooltip line of a catalyst input ({@code ItemInput#consumed} false), or empty for a consumed one. */
    public static Optional<Component> notConsumedLine(ItemInput input) {
        return input.consumed() ? Optional.empty() : Optional.of(Component.translatable(NOT_CONSUMED_KEY));
    }

    /**
     * The tooltip line of an output with {@code chance} in hundredths of a percent ({@code ItemOutput#chance}), e.g.
     * "Chance: 12.5%"; empty for a guaranteed output.
     */
    public static Optional<Component> chanceLine(int chance) {
        if (chance >= ItemOutput.ALWAYS) {
            return Optional.empty();
        }
        return Optional.of(Component.translatable(CHANCE_KEY, InfoFormat.decimal(chance / 100.0)));
    }

    /**
     * Lang key of a category's title in recipe viewers, {@code recipe_category.<namespace>.<path>}; the mod that
     * registers the category adds it, e.g. "Fabricating".
     */
    public static String titleKey(Identifier categoryId) {
        return "recipe_category." + categoryId.getNamespace() + "." + categoryId.getPath();
    }
}
