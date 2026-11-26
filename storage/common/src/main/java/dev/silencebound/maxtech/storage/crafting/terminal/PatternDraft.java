package dev.silencebound.maxtech.storage.crafting.terminal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternCodecs;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternFaces;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternTable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * What a pattern terminal's grid shows before it is encoded: the mode, nine input slots and three output slots, each
 * possibly empty, and the recipe a recipe viewer filled it from. Immutable; every edit returns a new draft, which the
 * terminal saves and sends to open screens as a whole (it is small). A mode uses only its first {@link Mode#inputs()}
 * inputs and {@link Mode#outputs()} outputs; the others are kept but not encoded.
 *
 * @param mode
 *            crafting, processing, stonecutting or smithing
 * @param inputs
 *            exactly {@link #INPUTS} entries
 * @param outputs
 *            exactly {@link #OUTPUTS} entries; the first filled one is the main output
 * @param recipeType
 *            the recipe type a recipe viewer filled the grid from
 * @param recipe
 *            the recipe it filled the grid from
 */
public record PatternDraft(Mode mode, List<Optional<Input>> inputs, List<Optional<Output>> outputs,
        Optional<Identifier> recipeType, Optional<Identifier> recipe) {
    public static final int INPUTS = 9;
    public static final int OUTPUTS = 3;
    public static final PatternDraft EMPTY = new PatternDraft(Mode.CRAFTING,
            Collections.nCopies(INPUTS, Optional.empty()), Collections.nCopies(OUTPUTS, Optional.empty()),
            Optional.empty(), Optional.empty());

    /**
     * The terminal's modes (B09 item 16 added stonecutting and smithing), saved by fixed ids. Every mode but processing
     * encodes a {@link CraftingPattern.Kind#CRAFTING} pattern for the crafting assembler, told apart by recipe type
     * ({@link PatternTable}); such a pattern takes one of each input and finds its output by recipe.
     */
    public enum Mode {
        CRAFTING("crafting", PatternDraft.INPUTS, 1), PROCESSING("processing", PatternDraft.INPUTS,
                PatternDraft.OUTPUTS), STONECUTTING("stonecutting", 1, 1), SMITHING("smithing", 3, 1);

        public static final Codec<Mode> CODEC = Codec.STRING.comapFlatMap(Mode::byId, Mode::id);

        private final String id;
        private final int inputs;
        private final int outputs;

        Mode(String id, int inputs, int outputs) {
            this.id = id;
            this.inputs = inputs;
            this.outputs = outputs;
        }

        /** The saved id; never changes. */
        public String id() {
            return id;
        }

        /** How many input slots the mode uses, from the first. */
        public int inputs() {
            return inputs;
        }

        /** How many output slots the mode uses, from the first. */
        public int outputs() {
            return outputs;
        }

        /** Whether the mode runs a table recipe on the assembler: one of each input, output from the recipe. */
        public boolean table() {
            return this != PROCESSING;
        }

        /** The assembler table of a table mode, or null for processing. */
        public @Nullable PatternTable patternTable() {
            return switch (this) {
                case CRAFTING -> PatternTable.CRAFTING;
                case STONECUTTING -> PatternTable.STONECUTTING;
                case SMITHING -> PatternTable.SMITHING;
                case PROCESSING -> null;
            };
        }

        /** The mode that edits {@code pattern}. */
        public static Mode of(CraftingPattern pattern) {
            PatternTable table = PatternTable.of(pattern);
            if (table == null) {
                return PROCESSING;
            }
            return switch (table) {
                case CRAFTING -> CRAFTING;
                case STONECUTTING -> STONECUTTING;
                case SMITHING -> SMITHING;
            };
        }

        private static DataResult<Mode> byId(String id) {
            for (Mode mode : values()) {
                if (mode.id.equals(id)) {
                    return DataResult.success(mode);
                }
            }
            return DataResult.error(() -> "unknown pattern terminal mode: " + id);
        }
    }

    /**
     * One input slot.
     *
     * @param key
     *            the key shown, and used when the input is exact
     * @param amount
     *            per craft, at least 1
     * @param tag
     *            a tag ingredient the key came from, if any
     * @param exact
     *            whether the input is locked to {@code key} although it has a tag
     * @param face
     *            the face override
     * @param returned
     *            whether the input comes back after the craft
     */
    public record Input(StackKey key, long amount, Optional<PatternIngredient> tag, boolean exact,
            Optional<Direction> face, boolean returned) {
        public static final Codec<Input> CODEC = RecordCodecBuilder.create(instance -> instance
                .group(StackKey.CODEC.fieldOf("key").forGetter(Input::key),
                        Codec.LONG.fieldOf("amount").forGetter(Input::amount),
                        PatternCodecs.INGREDIENT.optionalFieldOf("tag").forGetter(Input::tag),
                        Codec.BOOL.optionalFieldOf("exact", false).forGetter(Input::exact),
                        Direction.CODEC.optionalFieldOf("face").forGetter(Input::face),
                        Codec.BOOL.optionalFieldOf("returned", false).forGetter(Input::returned))
                .apply(instance, Input::new));

        public Input {
            amount = Math.clamp(amount, 1, PatternCodecs.MAX_AMOUNT);
            if (tag.isPresent() && tag.get() instanceof PatternIngredient.Exact) {
                tag = Optional.empty();
            }
        }

        /** A plain exact input. */
        public static Input of(StackKey key, long amount) {
            return new Input(key, amount, Optional.empty(), false, Optional.empty(), false);
        }

        /** What the pattern accepts: the tag unless locked, else the key. */
        public PatternIngredient ingredient() {
            return tag.isPresent() && !exact ? tag.get() : new PatternIngredient.Exact(key);
        }

        public Input withAmount(long value) {
            return new Input(key, value, tag, exact, face, returned);
        }

        public Input withFace(Optional<Direction> value) {
            return new Input(key, amount, tag, exact, value, returned);
        }

        public Input withReturned(boolean value) {
            return new Input(key, amount, tag, exact, face, value);
        }

        public Input withExact(boolean value) {
            return new Input(key, amount, tag, value, face, returned);
        }
    }

    /** One output slot: key, amount per craft and chance. */
    public record Output(StackKey key, long amount, float chance) {
        public static final Codec<Output> CODEC = PatternCodecs.OUTPUT.xmap(
                output -> new Output(output.key(), output.amount(), output.chance()),
                output -> new PatternOutput(output.key, output.amount, output.chance));

        public Output {
            amount = Math.clamp(amount, 1, PatternCodecs.MAX_AMOUNT);
            chance = chance > 0f && chance <= 1f ? chance : 1f;
        }

        public Output withAmount(long value) {
            return new Output(key, value, chance);
        }
    }

    /** Drafts saved before B09 item 16 have only {@code crafting}; newer ones write both, {@code mode} wins. */
    public static final Codec<PatternDraft> CODEC = RecordCodecBuilder.create(instance -> instance
            .group(Codec.BOOL.optionalFieldOf("crafting", true).forGetter(PatternDraft::crafting),
                    Mode.CODEC.optionalFieldOf("mode").forGetter(draft -> Optional.of(draft.mode)),
                    Input.CODEC.optionalFieldOf("value").codec().listOf().fieldOf("inputs")
                            .forGetter(PatternDraft::inputs),
                    Output.CODEC.optionalFieldOf("value").codec().listOf().fieldOf("outputs")
                            .forGetter(PatternDraft::outputs),
                    Identifier.CODEC.optionalFieldOf("recipe_type").forGetter(PatternDraft::recipeType),
                    Identifier.CODEC.optionalFieldOf("recipe").forGetter(PatternDraft::recipe))
            .apply(instance, (crafting, mode, inputs, outputs, type, recipe) -> new PatternDraft(
                    mode.orElse(crafting ? Mode.CRAFTING : Mode.PROCESSING), inputs, outputs, type, recipe)));
    public static final StreamCodec<RegistryFriendlyByteBuf, PatternDraft> STREAM_CODEC = ByteBufCodecs
            .fromCodecWithRegistries(CODEC);

    public PatternDraft {
        inputs = sized(inputs, INPUTS);
        outputs = sized(outputs, OUTPUTS);
    }

    /** Whether the draft is in crafting mode (a 3 x 3 grid, the output found by recipe). */
    public boolean crafting() {
        return mode == Mode.CRAFTING;
    }

    private static <T> List<Optional<T>> sized(List<Optional<T>> list, int size) {
        List<Optional<T>> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            out.add(i < list.size() ? list.get(i) : Optional.empty());
        }
        return List.copyOf(out);
    }

    /** Whether the pattern substitutes: no tag input is locked to its shown key. */
    public boolean substitutions() {
        for (Optional<Input> slot : inputs) {
            if (slot.isPresent() && slot.get().tag().isPresent() && slot.get().exact()) {
                return false;
            }
        }
        return true;
    }

    /** Whether some input has a tag, so substitutions mean anything. */
    public boolean hasTags() {
        for (Optional<Input> slot : inputs) {
            if (slot.isPresent() && slot.get().tag().isPresent()) {
                return true;
            }
        }
        return false;
    }

    public PatternDraft withMode(Mode value) {
        return new PatternDraft(value, inputs, outputs, Optional.empty(), Optional.empty());
    }

    /** The draft with input {@code slot} set (null clears it); a hand edit forgets the recipe it came from. */
    public PatternDraft withInput(int slot, @Nullable Input input) {
        List<Optional<Input>> list = new ArrayList<>(inputs);
        list.set(slot, Optional.ofNullable(input));
        return new PatternDraft(mode, list, outputs, recipeType, recipe);
    }

    public PatternDraft withOutput(int slot, @Nullable Output output) {
        List<Optional<Output>> list = new ArrayList<>(outputs);
        list.set(slot, Optional.ofNullable(output));
        return new PatternDraft(mode, inputs, list, recipeType, recipe);
    }

    public PatternDraft withRecipe(Optional<Identifier> type, Optional<Identifier> id) {
        return new PatternDraft(mode, inputs, outputs, type, id);
    }

    /**
     * The pattern this draft encodes to, or empty when it has no input or no output. Processing inputs without a face
     * get the face of the known machine for the draft's recipe type ({@link PatternFaces}). Table inputs (crafting,
     * stonecutting, smithing) keep their slot order with empty slots left out, take one each and have no face; only a
     * crafting input keeps its returned mark. Stonecutting and smithing patterns always name their recipe type, so the
     * assembler knows the table.
     */
    public Optional<CraftingPattern> toPattern() {
        boolean table = mode.table();
        List<PatternInput> patternInputs = new ArrayList<>();
        for (int i = 0; i < mode.inputs(); i++) {
            Optional<Input> slot = inputs.get(i);
            if (slot.isPresent()) {
                Input input = slot.get();
                boolean returned = input.returned() && (mode == Mode.CRAFTING || mode == Mode.PROCESSING);
                patternInputs.add(new PatternInput(input.ingredient(), table ? 1 : input.amount(),
                        table ? Optional.empty() : input.face(), returned));
            }
        }
        List<PatternOutput> patternOutputs = new ArrayList<>();
        for (int i = 0; i < mode.outputs(); i++) {
            Optional<Output> slot = outputs.get(i);
            slot.ifPresent(output -> patternOutputs.add(new PatternOutput(output.key, output.amount, output.chance)));
        }
        if (patternInputs.isEmpty() || patternOutputs.isEmpty()) {
            return Optional.empty();
        }
        PatternTable patternTable = mode.patternTable();
        Optional<Identifier> type = patternTable != null && patternTable != PatternTable.CRAFTING
                ? Optional.of(patternTable.recipeType())
                : recipeType;
        CraftingPattern pattern = new CraftingPattern(
                table ? CraftingPattern.Kind.CRAFTING : CraftingPattern.Kind.PROCESSING, patternInputs, patternOutputs,
                type, recipe);
        PatternFaces.Rule rule = type.isPresent() ? PatternFaces.forRecipeType(type.get()) : null;
        return Optional.of(PatternFaces.fill(pattern, rule));
    }

    /** Why the amounts cannot be scaled; the screen names it in the button's tooltip. */
    public enum ScaleBlock {
        /** Only processing patterns have amounts of their own. */
        NOT_PROCESSING,
        /** Nothing in the grid. */
        EMPTY,
        /** A product would be above {@link PatternCodecs#MAX_AMOUNT}. */
        TOO_LARGE,
        /** A quotient would not be a whole number. */
        NOT_WHOLE
    }

    /**
     * Why every amount cannot be multiplied ({@code multiply}) or divided by {@code factor}, or null when it can. A
     * quotient of a positive whole amount that is itself whole is at least 1, so whole numbers are the only rule for
     * dividing.
     */
    public @Nullable ScaleBlock scaleBlock(int factor, boolean multiply) {
        if (mode != Mode.PROCESSING) {
            return ScaleBlock.NOT_PROCESSING;
        }
        boolean any = false;
        for (int i = 0; i < INPUTS + OUTPUTS; i++) {
            long amount = amount(i);
            if (amount > 0) {
                any = true;
                if (multiply && amount > PatternCodecs.MAX_AMOUNT / factor) {
                    return ScaleBlock.TOO_LARGE;
                }
                if (!multiply && amount % factor != 0) {
                    return ScaleBlock.NOT_WHOLE;
                }
            }
        }
        return any ? null : ScaleBlock.EMPTY;
    }

    /**
     * The draft with every input and output amount multiplied ({@code multiply}) or divided by {@code factor} (at least
     * 2), or this draft unchanged when {@link #scaleBlock} refuses. Faces, tags, marks, chances and the recipe stay.
     */
    public PatternDraft scaled(int factor, boolean multiply) {
        if (factor < 2 || scaleBlock(factor, multiply) != null) {
            return this;
        }
        List<Optional<Input>> scaledInputs = new ArrayList<>(INPUTS);
        for (Optional<Input> slot : inputs) {
            scaledInputs.add(slot.map(input -> input.withAmount(scale(input.amount(), factor, multiply))));
        }
        List<Optional<Output>> scaledOutputs = new ArrayList<>(OUTPUTS);
        for (Optional<Output> slot : outputs) {
            scaledOutputs.add(slot.map(output -> output.withAmount(scale(output.amount(), factor, multiply))));
        }
        return new PatternDraft(mode, scaledInputs, scaledOutputs, recipeType, recipe);
    }

    private static long scale(long amount, int factor, boolean multiply) {
        return multiply ? amount * factor : amount / factor;
    }

    /** The amount in grid slot {@code slot} (inputs 0 to 8, outputs 9 to 11), 0 for an empty slot. */
    private long amount(int slot) {
        if (slot < INPUTS) {
            Optional<Input> input = inputs.get(slot);
            return input.isPresent() ? input.get().amount() : 0;
        }
        Optional<Output> output = outputs.get(slot - INPUTS);
        return output.isPresent() ? output.get().amount() : 0;
    }
}
