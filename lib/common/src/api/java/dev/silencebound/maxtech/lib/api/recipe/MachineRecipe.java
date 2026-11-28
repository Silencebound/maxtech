package dev.silencebound.maxtech.lib.api.recipe;

import dev.silencebound.maxtech.lib.api.resource.KindKey;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.ApiStatus;

/**
 * A recipe of a machine whose type comes from {@link MachineRecipeType#define}. Every such type has this JSON form:
 *
 * <pre>
 * {"type": "maxtech_farms:mixer", "mode": "fine",
 *  "inputs": [{"ingredient": "#c:dusts/redstone", "count": 2}, {"ingredient": "#c:gems/lapis", "consumed": false}],
 *  "tank_inputs": [{"kind": "maxtech_lib:fluid", "tag": "c:water", "amount": 1000}],
 *  "outputs": [{"item": {"id": "maxtech_farms:blend", "count": 2}, "chance": 5000}],
 *  "tank_outputs": [{"resource": {"kind": "maxtech_lib:fluid", "id": "minecraft:lava"}, "amount": 250}],
 *  "energy_per_tick": 20, "duration": 100}
 * </pre>
 *
 * Every list and {@code mode} may be left out, {@code energy_per_tick} defaults to 0; {@code duration} is required. A
 * recipe that does not fit its type's {@link MachineRecipeShape}, or names an item, fluid or resource kind no loaded
 * mod has, fails to decode: the game logs it and skips that recipe, and every other recipe loads. Balance numbers are
 * recipe data. Immutable; safe from any thread.
 *
 * @param type
 *            the recipe type, from the serializer that read it
 * @param mode
 *            the machine mode it runs in; empty for a machine without modes
 * @param inputs
 *            item inputs; consumed ones come from the input slots, catalysts from the catalyst slots
 * @param tankInputs
 *            resources drained from the input tanks
 * @param outputs
 *            item outputs, each with its chance
 * @param tankOutputs
 *            resources put into the output tanks
 * @param energyPerTick
 *            FE drawn each tick of a run, 0 for none
 * @param duration
 *            ticks per run, at least 1
 */
@ApiStatus.Experimental
public record MachineRecipe(MachineRecipeType type, Optional<String> mode, List<ItemInput> inputs,
        List<KindInput> tankInputs, List<ItemOutput> outputs, List<KindOutput> tankOutputs, int energyPerTick,
        int duration) implements Recipe<MachineRecipeInput> {
    /**
     * Copies the lists; throws {@link IllegalArgumentException} for negative energy or a duration below 1. Fitting the
     * shape is checked by the codec and the builder, not here.
     */
    public MachineRecipe {
        inputs = List.copyOf(inputs);
        tankInputs = List.copyOf(tankInputs);
        outputs = List.copyOf(outputs);
        tankOutputs = List.copyOf(tankOutputs);
        if (energyPerTick < 0 || duration < 1) {
            throw new IllegalArgumentException("Energy per tick " + energyPerTick + " or duration " + duration);
        }
    }

    /**
     * Whether {@code input} is in this recipe's mode and holds every input in the counts and amounts a run needs; fills
     * {@link MachineRecipeInput#plan()} with where they come from. Items match by their {@link ItemInput}'s ingredient
     * (tags, components), tank resources by {@link KindInput#test}. Allocates one small view.
     */
    @Override
    public boolean matches(MachineRecipeInput input, Level level) {
        if (mode.isPresent() ? !mode.get().equals(input.mode()) : !input.mode().isEmpty()) {
            return false;
        }
        return RecipePlanner.plan(new Needs(this), new Have(this, input), input.plan());
    }

    /** The first item output, or empty; machines use {@link #outputs} and {@link #tankOutputs} instead. */
    @Override
    public ItemStack assemble(MachineRecipeInput input) {
        return outputs.isEmpty() ? ItemStack.EMPTY : outputs.getFirst().item().create();
    }

    /** Machine recipes never appear in the vanilla recipe book. */
    @Override
    public boolean isSpecial() {
        return true;
    }

    @Override
    public boolean showNotification() {
        return false;
    }

    @Override
    public String group() {
        return "";
    }

    @Override
    public RecipeSerializer<MachineRecipe> getSerializer() {
        return type.serializer();
    }

    @Override
    public RecipeType<MachineRecipe> getType() {
        return type.recipeType();
    }

    @Override
    public PlacementInfo placementInfo() {
        return PlacementInfo.NOT_PLACEABLE;
    }

    @Override
    public RecipeBookCategory recipeBookCategory() {
        return type.bookCategory();
    }

    private record Needs(MachineRecipe recipe) implements RecipePlanner.Needs {
        @Override
        public int itemInputs() {
            return recipe.inputs.size();
        }

        @Override
        public int count(int input) {
            return recipe.inputs.get(input).count();
        }

        @Override
        public boolean consumed(int input) {
            return recipe.inputs.get(input).consumed();
        }

        @Override
        public int tankInputs() {
            return recipe.tankInputs.size();
        }

        @Override
        public long amount(int input) {
            return recipe.tankInputs.get(input).amount();
        }
    }

    private record Have(MachineRecipe recipe, MachineRecipeInput input) implements RecipePlanner.Have {
        @Override
        public int inputSlots() {
            return input.inputSlots();
        }

        @Override
        public int catalystSlots() {
            return input.catalystSlots();
        }

        @Override
        public int count(int slot) {
            return input.getItem(slot).getCount();
        }

        @Override
        public boolean accepts(int index, int slot) {
            return recipe.inputs.get(index).test(input.getItem(slot));
        }

        @Override
        public int tanks() {
            return input.inputTanks();
        }

        @Override
        public long amount(int tank) {
            return input.tankAmount(tank);
        }

        @Override
        public boolean tankAccepts(int index, int tank) {
            Optional<KindKey<?>> resource = input.tankResource(tank);
            return resource.isPresent() && recipe.tankInputs.get(index).test(resource.get());
        }
    }
}
