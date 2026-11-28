package dev.silencebound.maxtech.lib.api.recipe;

import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * Finds the recipe of one machine's type that its inputs match, remembering the last one: while that recipe still
 * matches, a lookup checks only it and allocates nothing (no stream, the same {@code Optional}); only when it no longer
 * matches does the lookup ask the recipe manager, which streams over the type's recipes. A datapack reload makes a new
 * recipe manager, which drops what was remembered. One per machine (it holds that machine's last recipe); call it when
 * the inputs or mode change, not every tick. Not thread-safe: use it on the server thread.
 */
@ApiStatus.Experimental
public final class MachineRecipeLookup {
    private final MachineRecipeType type;
    private @Nullable RecipeManager manager;
    private Optional<RecipeHolder<MachineRecipe>> last = Optional.empty();

    /** A lookup of {@code type}'s recipes. */
    public MachineRecipeLookup(MachineRecipeType type) {
        this.type = type;
    }

    /**
     * The first recipe of the type, the remembered one first, that {@code input} matches (see
     * {@link MachineRecipe#matches}); {@code input}'s plan then holds where its inputs come from. Empty when none
     * matches. Returns the same {@code Optional} instance while the remembered recipe keeps matching.
     */
    public Optional<RecipeHolder<MachineRecipe>> find(ServerLevel level, MachineRecipeInput input) {
        return find(level.recipeAccess(), level, input);
    }

    /**
     * {@link #find(ServerLevel, MachineRecipeInput)} against {@code recipes}; a manager other than the last one's (a
     * reload) drops the remembered recipe first.
     */
    @SuppressWarnings("ReferenceEquality") // a reload makes a new manager; identity is the point
    public Optional<RecipeHolder<MachineRecipe>> find(RecipeManager recipes, Level level, MachineRecipeInput input) {
        if (recipes != manager) {
            manager = recipes;
            last = Optional.empty();
        }
        if (last.isPresent() && last.get().value().matches(input, level)) {
            return last;
        }
        Optional<RecipeHolder<MachineRecipe>> found = recipes.getRecipeFor(type.recipeType(), input, level);
        if (found.isPresent()) {
            last = found;
        }
        return found;
    }

    /** The recipe the last successful {@link #find} returned; empty before one, and after a reload until the next. */
    public Optional<RecipeHolder<MachineRecipe>> last() {
        return last;
    }

    /** Forgets the remembered recipe, so the next {@link #find} asks the recipe manager. */
    public void clear() {
        last = Optional.empty();
    }
}
