package dev.silencebound.maxtech.storage.machine;

import dev.silencebound.maxtech.lib.api.recipe.ItemInput;
import dev.silencebound.maxtech.lib.api.recipe.ItemOutput;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.ItemLike;
import org.jspecify.annotations.Nullable;

/**
 * Builds a {@link StorageMachineRecipe} for datagen, and later for the KubeJS schema, which drives the same calls:
 * inputs by tag, outputs as items. {@link #build()} checks the recipe against its machine as the JSON codec does.
 */
public final class StorageMachineRecipeBuilder {
    private final MachineKind kind;
    private final HolderGetter<Item> items;
    private final List<ItemInput> inputs = new ArrayList<>();
    private final List<ItemOutput> outputs = new ArrayList<>();
    private @Nullable String mode;
    private int energyPerTick;
    private int duration = 1;
    private @Nullable Identifier research;

    private StorageMachineRecipeBuilder(MachineKind kind, HolderGetter<Item> items) {
        if (!kind.processes()) {
            throw new IllegalArgumentException(kind.id() + " runs no recipes");
        }
        this.kind = kind;
        this.items = items;
    }

    /** A recipe for {@code kind}, a processing machine; {@code items} resolves input tags. */
    public static StorageMachineRecipeBuilder of(MachineKind kind, HolderGetter<Item> items) {
        return new StorageMachineRecipeBuilder(kind, items);
    }

    /** The mode the recipe runs in, one of the machine's. */
    public StorageMachineRecipeBuilder mode(String mode) {
        this.mode = mode;
        return this;
    }

    /** {@code count} items of {@code tag}, used up by each run. */
    public StorageMachineRecipeBuilder input(TagKey<Item> tag, int count) {
        inputs.add(new ItemInput(Ingredient.of(items.getOrThrow(tag)), count, true));
        return this;
    }

    /** {@code count} of a vanilla {@code item} that has no convention tag (honeycomb), used up by each run. */
    public StorageMachineRecipeBuilder input(ItemLike item, int count) {
        inputs.add(new ItemInput(Ingredient.of(item), count, true));
        return this;
    }

    /** One item of {@code tag} that must sit in the catalyst slot and is kept, such as a lithograph mask. */
    public StorageMachineRecipeBuilder catalyst(TagKey<Item> tag) {
        inputs.add(new ItemInput(Ingredient.of(items.getOrThrow(tag)), 1, false));
        return this;
    }

    public StorageMachineRecipeBuilder output(ItemLike item, int count) {
        return output(item, count, ItemOutput.ALWAYS);
    }

    /** An output made with {@code chance} hundredths of a percent per run; room is kept for it either way. */
    public StorageMachineRecipeBuilder output(ItemLike item, int count, int chance) {
        outputs.add(new ItemOutput(new ItemStackTemplate(item.asItem(), count), chance));
        return this;
    }

    /** {@code energyPerTick} FE drawn on each of {@code duration} ticks. */
    public StorageMachineRecipeBuilder energy(int energyPerTick, int duration) {
        this.energyPerTick = energyPerTick;
        this.duration = duration;
        return this;
    }

    /** The research unlock ({@code research.ResearchGates}) a machine's owner needs before a run starts. */
    public StorageMachineRecipeBuilder research(Identifier unlock) {
        this.research = unlock;
        return this;
    }

    /** The recipe; throws {@link IllegalStateException} if it does not fit its machine. */
    public StorageMachineRecipe build() {
        StorageMachineRecipe recipe = new StorageMachineRecipe(kind, Optional.ofNullable(mode), inputs, outputs,
                energyPerTick, Math.max(1, duration), Optional.ofNullable(research));
        return StorageMachineRecipe.validate(recipe).getOrThrow(IllegalStateException::new);
    }

    /** Builds and hands the recipe to {@code output} under {@code id}. */
    public void save(RecipeOutput output, Identifier id) {
        output.accept(ResourceKey.create(Registries.RECIPE, id), build(), null);
    }
}
