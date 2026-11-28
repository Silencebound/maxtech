package dev.silencebound.maxtech.lib.api.recipe;

import dev.silencebound.maxtech.lib.api.resource.KindKey;
import java.util.Optional;
import net.minecraft.world.item.crafting.RecipeInput;
import org.jetbrains.annotations.ApiStatus;

/**
 * A machine's inputs as its recipes read them: {@link #getItem} covers the input slots, then the catalyst slots; the
 * input tanks come after. A machine hands out one live view over its own slots and tanks (its {@code ItemSlots} and
 * {@code ResourceTank}s) and reuses it for every match. Read on the machine's thread only.
 */
@ApiStatus.Experimental
public interface MachineRecipeInput extends RecipeInput {
    /** The selected mode id, or an empty string for a machine without modes. */
    String mode();

    /** Input slots, {@code getItem(0)} to {@code getItem(inputSlots() - 1)}. */
    int inputSlots();

    /** Catalyst slots, right after the input slots. */
    int catalystSlots();

    /** Input and catalyst slots together. */
    @Override
    default int size() {
        return inputSlots() + catalystSlots();
    }

    /**
     * Whether every slot and every input tank is empty. Vanilla's recipe lookup finds nothing for an empty input
     * without asking the recipes, and {@link RecipeInput#isEmpty()} counts only items, so a tank-only recipe would
     * never be found.
     */
    @Override
    default boolean isEmpty() {
        for (int tank = 0; tank < inputTanks(); tank++) {
            if (tankAmount(tank) > 0) {
                return false;
            }
        }
        for (int slot = 0; slot < size(); slot++) {
            if (!getItem(slot).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Input tanks. */
    int inputTanks();

    /** The resource in input tank {@code tank}; empty when the tank is. Should not allocate (a tank's own Optional). */
    Optional<KindKey<?>> tankResource(int tank);

    /** The amount in input tank {@code tank}; 0 when empty. */
    long tankAmount(int tank);

    /** The plan {@link MachineRecipe#matches} fills, at least as large as this input. */
    MachineRecipePlan plan();
}
