package dev.silencebound.maxtech.lib.api.recipe;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.KindKey;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
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
import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * Builds a {@link MachineRecipe} for datagen: item inputs by {@code c:} tag (or an item without one), tank inputs by
 * fluid tag or exact resource, outputs as items with an optional chance and as tank resources. {@link #build()} checks
 * the recipe against its type's shape as the JSON codec does, so a recipe datagen writes always loads. Not thread-safe;
 * one builder per recipe.
 */
@ApiStatus.Experimental
public final class MachineRecipeBuilder {
    private final MachineRecipeType type;
    private final HolderGetter<Item> items;
    private final List<ItemInput> inputs = new ArrayList<>();
    private final List<KindInput> tankInputs = new ArrayList<>();
    private final List<ItemOutput> outputs = new ArrayList<>();
    private final List<KindOutput> tankOutputs = new ArrayList<>();
    private @Nullable String mode;
    private int energyPerTick;
    private int duration;

    private MachineRecipeBuilder(MachineRecipeType type, HolderGetter<Item> items) {
        this.type = type;
        this.items = items;
    }

    /** A recipe of {@code type}; {@code items} resolves item tags (the datagen provider's item lookup). */
    public static MachineRecipeBuilder of(MachineRecipeType type, HolderGetter<Item> items) {
        return new MachineRecipeBuilder(type, items);
    }

    /** The mode the recipe runs in, one of the shape's. */
    public MachineRecipeBuilder mode(String id) {
        this.mode = id;
        return this;
    }

    /** {@code count} items of {@code tag}, used up by each run. */
    public MachineRecipeBuilder input(TagKey<Item> tag, int count) {
        return input(Ingredient.of(items.getOrThrow(tag)), count);
    }

    /** {@code count} of an {@code item} that has no convention tag, used up by each run. */
    public MachineRecipeBuilder input(ItemLike item, int count) {
        return input(Ingredient.of(item), count);
    }

    /** {@code count} items {@code ingredient} accepts (a loader's component ingredient, say), used up by each run. */
    public MachineRecipeBuilder input(Ingredient ingredient, int count) {
        inputs.add(new ItemInput(ingredient, count, true));
        return this;
    }

    /** One item of {@code tag} that must sit in a catalyst slot and is kept, such as a mask. */
    public MachineRecipeBuilder catalyst(TagKey<Item> tag) {
        return catalyst(Ingredient.of(items.getOrThrow(tag)));
    }

    /** One {@code item} without a convention tag that must sit in a catalyst slot and is kept. */
    public MachineRecipeBuilder catalyst(ItemLike item) {
        return catalyst(Ingredient.of(item));
    }

    /** One item {@code ingredient} accepts that must sit in a catalyst slot and is kept. */
    public MachineRecipeBuilder catalyst(Ingredient ingredient) {
        inputs.add(new ItemInput(ingredient, 1, false));
        return this;
    }

    /** {@code amount} mB of any fluid in {@code tag}, drained by each run. */
    public MachineRecipeBuilder tankInput(TagKey<Fluid> tag, long amount) {
        return tankInput(KindInput.tag(ResourceKinds.FLUID, tag.location(), amount));
    }

    /** A tank input of any kind, by tag or exact resource. */
    public MachineRecipeBuilder tankInput(KindInput input) {
        tankInputs.add(input);
        return this;
    }

    /** {@code count} of {@code item}, always produced. */
    public MachineRecipeBuilder output(ItemLike item, int count) {
        return output(item, count, ItemOutput.ALWAYS);
    }

    /**
     * {@code count} of {@code item}, each copy made with {@code chance} hundredths of a percent; room is kept for all
     * of them either way. Throws {@link IllegalArgumentException} for a chance outside 1..{@value ItemOutput#ALWAYS}.
     */
    public MachineRecipeBuilder output(ItemLike item, int count, int chance) {
        outputs.add(new ItemOutput(new ItemStackTemplate(item.asItem(), count), chance));
        return this;
    }

    /** {@code amount} mB of {@code fluid}, without components. */
    public MachineRecipeBuilder tankOutput(Fluid fluid, long amount) {
        return tankOutput(new KindKey<>(ResourceKinds.FLUID, FluidKey.of(fluid)), amount);
    }

    /** {@code amount} of exactly {@code resource}, of any kind. */
    public MachineRecipeBuilder tankOutput(KindKey<?> resource, long amount) {
        tankOutputs.add(new KindOutput(resource, amount));
        return this;
    }

    /** FE drawn on each tick of a run; 0 (no energy) unless set. */
    public MachineRecipeBuilder energyPerTick(int fe) {
        this.energyPerTick = fe;
        return this;
    }

    /** Ticks per run, at least 1; every recipe sets it. */
    public MachineRecipeBuilder duration(int ticks) {
        this.duration = ticks;
        return this;
    }

    /**
     * The recipe; throws {@link IllegalStateException} if the duration was never set or the recipe does not fit its
     * type's shape, and {@link IllegalArgumentException} for negative energy or a duration below 1.
     */
    public MachineRecipe build() {
        if (duration == 0) {
            throw new IllegalStateException(type.id() + ": recipe without a duration");
        }
        MachineRecipe recipe = new MachineRecipe(type, Optional.ofNullable(mode), inputs, tankInputs, outputs,
                tankOutputs, energyPerTick, duration);
        Optional<String> problem = type.shape().check(recipe);
        if (problem.isPresent()) {
            throw new IllegalStateException(type.id() + ": " + problem.get());
        }
        return recipe;
    }

    /** Builds the recipe and hands it to {@code output} under {@code id}, without an advancement. */
    public void save(RecipeOutput output, Identifier id) {
        output.accept(ResourceKey.create(Registries.RECIPE, id), build(), null);
    }
}
