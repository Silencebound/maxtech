package dev.silencebound.maxtech.storage.neoforge.compat.rei;

import dev.silencebound.maxtech.lib.api.recipe.ItemInput;
import dev.silencebound.maxtech.lib.api.recipe.ItemOutput;
import dev.silencebound.maxtech.storage.machine.StorageMachineRecipe;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.Display;
import me.shedaniel.rei.api.common.display.DisplaySerializer;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.util.EntryIngredients;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.jspecify.annotations.Nullable;

/**
 * One Storage machine recipe as REI shows it. Built on the client from the synced recipe, never sent, so it has no
 * serializer. Inputs and outputs are in the recipe's order, which is the slot order of the page layout.
 */
final class StorageMachineDisplay implements Display {
    private final CategoryIdentifier<StorageMachineDisplay> category;
    private final RecipeHolder<StorageMachineRecipe> holder;
    private final List<EntryIngredient> inputs;
    private final List<EntryIngredient> outputs;

    StorageMachineDisplay(CategoryIdentifier<StorageMachineDisplay> category,
            RecipeHolder<StorageMachineRecipe> holder) {
        this.category = category;
        this.holder = holder;
        StorageMachineRecipe recipe = holder.value();
        List<EntryIngredient> in = new ArrayList<>();
        for (ItemInput input : recipe.inputs()) {
            List<ItemStack> stacks = new ArrayList<>();
            input.ingredient().items().forEach(item -> stacks.add(new ItemStack(item, input.count())));
            in.add(EntryIngredients.ofItemStacks(stacks));
        }
        List<EntryIngredient> out = new ArrayList<>();
        for (ItemOutput output : recipe.outputs()) {
            out.add(EntryIngredient.of(EntryStacks.of(output.item().create())));
        }
        this.inputs = List.copyOf(in);
        this.outputs = List.copyOf(out);
    }

    RecipeHolder<StorageMachineRecipe> holder() {
        return holder;
    }

    @Override
    public List<EntryIngredient> getInputEntries() {
        return inputs;
    }

    @Override
    public List<EntryIngredient> getOutputEntries() {
        return outputs;
    }

    @Override
    public CategoryIdentifier<?> getCategoryIdentifier() {
        return category;
    }

    @Override
    public Optional<Identifier> getDisplayLocation() {
        return Optional.of(holder.id().identifier());
    }

    @Override
    public @Nullable DisplaySerializer<? extends Display> getSerializer() {
        return null;
    }
}
