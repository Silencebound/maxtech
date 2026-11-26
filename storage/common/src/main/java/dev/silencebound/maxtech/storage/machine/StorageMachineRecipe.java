package dev.silencebound.maxtech.storage.machine;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.lib.api.recipe.ItemInput;
import dev.silencebound.maxtech.lib.api.recipe.ItemOutput;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

/**
 * A recipe of one of Storage's processing machines (PRD 7.2): the lithograph, fabricator and assembler each have a
 * recipe type and serializer of their own ({@code maxtech_storage:<machine>}), all with this JSON form:
 *
 * <pre>
 * {"type": "maxtech_storage:lithograph", "mode": "print",
 *  "inputs": [{"ingredient": "#maxtech_storage:doped_wafer"}, {"ingredient": "#maxtech_storage:logic_mask", "consumed": false}],
 *  "outputs": [{"item": {"id": "maxtech_storage:logic_die", "count": 2}}],
 *  "energy_per_tick": 12, "duration": 160}
 * </pre>
 *
 * An optional {@code "research"} names the research unlock a machine's owner needs before a run starts
 * ({@code research.ResearchGates}).
 *
 * Inputs are Lib's {@link ItemInput}s (an unconsumed input is a catalyst, held in the catalyst slot), outputs Lib's
 * {@link ItemOutput}s (with a chance in hundredths of a percent). Balance values (FE per tick, ticks per run) are
 * recipe data, as for every machine recipe. A recipe that does not fit its machine (too many inputs, an unknown mode)
 * fails to decode with an error, so a bad datapack recipe is reported and skipped. Immutable; safe from any thread.
 *
 * @param kind
 *            the machine, from the serializer that read it
 * @param mode
 *            the machine mode it runs in; empty for machines without modes
 * @param inputs
 *            items needed per run; consumed ones come from the input slots
 * @param outputs
 *            items produced per run
 * @param energyPerTick
 *            FE drawn each tick of a run
 * @param duration
 *            ticks per run, at least 1
 * @param research
 *            the research unlock the machine's owner needs to start a run; empty for none
 */
public record StorageMachineRecipe(MachineKind kind, Optional<String> mode, List<ItemInput> inputs,
        List<ItemOutput> outputs, int energyPerTick, int duration,
        Optional<Identifier> research) implements Recipe<StorageMachineRecipe.Input>, RecipeMatcher.Requirements {

    public StorageMachineRecipe {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
    }

    /** The JSON form for {@code kind}'s recipes. */
    public static MapCodec<StorageMachineRecipe> codec(MachineKind kind) {
        return RecordCodecBuilder
                .<StorageMachineRecipe>mapCodec(i -> i
                        .group(Codec.STRING.optionalFieldOf("mode").forGetter(StorageMachineRecipe::mode),
                                ItemInput.CODEC.listOf().fieldOf("inputs").forGetter(StorageMachineRecipe::inputs),
                                ItemOutput.CODEC.listOf().fieldOf("outputs").forGetter(StorageMachineRecipe::outputs),
                                ExtraCodecs.NON_NEGATIVE_INT.fieldOf("energy_per_tick")
                                        .forGetter(StorageMachineRecipe::energyPerTick),
                                ExtraCodecs.POSITIVE_INT.fieldOf("duration").forGetter(StorageMachineRecipe::duration),
                                Identifier.CODEC.optionalFieldOf("research").forGetter(StorageMachineRecipe::research))
                        .apply(i,
                                (mode, inputs, outputs, energy, duration, research) -> new StorageMachineRecipe(kind,
                                        mode, inputs, outputs, energy, duration, research)))
                .validate(StorageMachineRecipe::validate);
    }

    /** The network form for {@code kind}'s recipes. */
    public static StreamCodec<RegistryFriendlyByteBuf, StorageMachineRecipe> streamCodec(MachineKind kind) {
        return StreamCodec.composite(ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8), StorageMachineRecipe::mode,
                ItemInput.STREAM_CODEC.apply(ByteBufCodecs.list()), StorageMachineRecipe::inputs,
                ItemOutput.STREAM_CODEC.apply(ByteBufCodecs.list()), StorageMachineRecipe::outputs,
                ByteBufCodecs.VAR_INT, StorageMachineRecipe::energyPerTick, ByteBufCodecs.VAR_INT,
                StorageMachineRecipe::duration, ByteBufCodecs.optional(Identifier.STREAM_CODEC),
                StorageMachineRecipe::research, (mode, inputs, outputs, energy, duration,
                        research) -> new StorageMachineRecipe(kind, mode, inputs, outputs, energy, duration, research));
    }

    /** Whether the recipe fits its machine; the reason when not. */
    static DataResult<StorageMachineRecipe> validate(StorageMachineRecipe recipe) {
        MachineKind kind = recipe.kind;
        int consumed = 0;
        for (ItemInput input : recipe.inputs) {
            if (input.consumed()) {
                consumed++;
            }
        }
        int used = consumed;
        int catalysts = recipe.inputs.size() - consumed;
        if (used < 1 || used > kind.inputs() || catalysts > kind.catalysts()) {
            return DataResult.error(() -> kind.id() + " recipes take 1 to " + kind.inputs() + " consumed inputs and at"
                    + " most " + kind.catalysts() + " catalysts, not " + used + " and " + catalysts);
        }
        if (recipe.outputs.isEmpty() || recipe.outputs.size() > kind.outputs()) {
            return DataResult.error(() -> kind.id() + " recipes have 1 to " + kind.outputs() + " outputs");
        }
        if (kind.modes().isEmpty() ? recipe.mode.isPresent() : !kind.modes().contains(recipe.mode.orElse(""))) {
            return DataResult.error(() -> kind.id() + " recipes need a mode out of " + kind.modes() + ", not "
                    + recipe.mode.orElse("none"));
        }
        return DataResult.success(recipe);
    }

    @Override
    public int inputCount() {
        return inputs.size();
    }

    @Override
    public int count(int input) {
        return inputs.get(input).count();
    }

    @Override
    public boolean consumed(int input) {
        return inputs.get(input).consumed();
    }

    /** Whether {@code input} is in this recipe's mode and holds every input; allocates one small view. */
    @Override
    public boolean matches(Input input, Level level) {
        if (mode.isPresent() && !mode.get().equals(input.mode())) {
            return false;
        }
        return RecipeMatcher.plan(this, new SlotView(this, input), input.scratch());
    }

    /** The first output; machines use {@link #outputs} instead. */
    @Override
    public ItemStack assemble(Input input) {
        return outputs.getFirst().item().create();
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
    public RecipeSerializer<StorageMachineRecipe> getSerializer() {
        return MachineContent.serializer(kind);
    }

    @Override
    public RecipeType<StorageMachineRecipe> getType() {
        return MachineContent.recipeType(kind);
    }

    @Override
    public PlacementInfo placementInfo() {
        return PlacementInfo.NOT_PLACEABLE;
    }

    @Override
    public RecipeBookCategory recipeBookCategory() {
        return MachineContent.BOOK_CATEGORY.get();
    }

    /**
     * A machine's slots as recipes read them: the input slots, then the catalyst slots, in {@link #getItem} order. The
     * block entity hands out one live view.
     */
    public interface Input extends net.minecraft.world.item.crafting.RecipeInput {
        /** The selected mode, or an empty string for a machine without modes. */
        String mode();

        int inputSlots();

        int catalystSlots();

        /** A plan array at least as long as the input and catalyst slots together, reused between matches. */
        int[] scratch();
    }

    private record SlotView(StorageMachineRecipe recipe, Input input) implements RecipeMatcher.Slots {
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
    }
}
