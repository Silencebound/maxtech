package dev.silencebound.maxtech.lib.api.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.lib.api.registry.Registrars;
import dev.silencebound.maxtech.lib.api.registry.RegistryEntry;
import io.netty.handler.codec.DecoderException;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import org.jetbrains.annotations.ApiStatus;

/**
 * A machine's recipe type: the vanilla {@link RecipeType}, its serializer (one JSON codec, one stream codec for sync)
 * and a recipe book category, all registered under the mod's id through its {@link Registrars}, plus the
 * {@link MachineRecipeShape} every recipe of the type is checked against. The recipes are {@link MachineRecipe}s.
 * Define each type once, during mod construction (a static field of the mod's content class); the getters of the
 * registered objects throw until registration has run. Immutable; safe from any thread.
 */
@ApiStatus.Experimental
public final class MachineRecipeType {
    private final Identifier id;
    private final MachineRecipeShape shape;
    private final MapCodec<MachineRecipe> codec;
    private final StreamCodec<RegistryFriendlyByteBuf, MachineRecipe> streamCodec;
    private final RegistryEntry<RecipeType<?>, RecipeType<MachineRecipe>> type;
    private final RegistryEntry<RecipeSerializer<?>, RecipeSerializer<MachineRecipe>> serializer;
    private final RegistryEntry<RecipeBookCategory, RecipeBookCategory> bookCategory;

    private MachineRecipeType(String modId, String name, MachineRecipeShape shape) {
        this.id = Identifier.fromNamespaceAndPath(modId, name);
        this.shape = shape;
        this.codec = RecordCodecBuilder.<MachineRecipe>mapCodec(i -> i
                .group(Codec.STRING.optionalFieldOf("mode").forGetter(MachineRecipe::mode),
                        ItemInput.CODEC.listOf().optionalFieldOf("inputs", List.of()).forGetter(MachineRecipe::inputs),
                        KindInput.CODEC
                                .listOf().optionalFieldOf("tank_inputs", List.of())
                                .forGetter(MachineRecipe::tankInputs),
                        ItemOutput.CODEC.listOf().optionalFieldOf("outputs", List.of())
                                .forGetter(MachineRecipe::outputs),
                        KindOutput.CODEC.listOf().optionalFieldOf("tank_outputs", List.of())
                                .forGetter(MachineRecipe::tankOutputs),
                        ExtraCodecs.NON_NEGATIVE_INT.optionalFieldOf("energy_per_tick", 0)
                                .forGetter(MachineRecipe::energyPerTick),
                        ExtraCodecs.POSITIVE_INT.fieldOf("duration").forGetter(MachineRecipe::duration))
                .apply(i,
                        (mode, inputs, tankInputs, outputs, tankOutputs, energy, duration) -> new MachineRecipe(this,
                                mode, inputs, tankInputs, outputs, tankOutputs, energy, duration)))
                .validate(this::validate);
        StreamCodec<RegistryFriendlyByteBuf, MachineRecipe> fields = StreamCodec.composite(
                ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8), MachineRecipe::mode,
                ItemInput.STREAM_CODEC.apply(ByteBufCodecs.list()), MachineRecipe::inputs,
                KindInput.STREAM_CODEC.apply(ByteBufCodecs.list()), MachineRecipe::tankInputs,
                ItemOutput.STREAM_CODEC.apply(ByteBufCodecs.list()), MachineRecipe::outputs,
                KindOutput.STREAM_CODEC.apply(ByteBufCodecs.list()), MachineRecipe::tankOutputs, ByteBufCodecs.VAR_INT,
                MachineRecipe::energyPerTick, ByteBufCodecs.VAR_INT, MachineRecipe::duration,
                (mode, inputs, tankInputs, outputs, tankOutputs, energy, duration) -> new MachineRecipe(this, mode,
                        inputs, tankInputs, outputs, tankOutputs, energy, duration));
        this.streamCodec = StreamCodec.of(fields, buffer -> {
            try {
                return fields.decode(buffer);
            } catch (IllegalArgumentException e) {
                // A value the constructors refuse (count, chance, amount, duration) is a bad packet, not a crash.
                throw new DecoderException("Bad " + id + " recipe: " + e.getMessage(), e);
            }
        });
        this.type = Registrars.create(modId, Registries.RECIPE_TYPE).register(name, () -> RecipeType.simple(id));
        this.serializer = Registrars.create(modId, Registries.RECIPE_SERIALIZER).register(name,
                () -> new RecipeSerializer<>(codec, streamCodec));
        this.bookCategory = Registrars.create(modId, Registries.RECIPE_BOOK_CATEGORY).register(name,
                RecipeBookCategory::new);
    }

    /**
     * Defines and registers the recipe type {@code modId:name} for a machine of {@code shape}. Call during mod
     * construction.
     *
     * @throws IllegalStateException
     *             before Lib has initialised, or when {@code modId} is not a loaded mod (see {@link Registrars#create})
     */
    public static MachineRecipeType define(String modId, String name, MachineRecipeShape shape) {
        return new MachineRecipeType(modId, name, shape);
    }

    /** The type's id, which is also the {@code "type"} in its recipes' JSON. */
    public Identifier id() {
        return id;
    }

    /** What the machine has room for; every recipe of the type fits it. */
    public MachineRecipeShape shape() {
        return shape;
    }

    /** The JSON form, which refuses a recipe that does not fit {@link #shape()}. */
    public MapCodec<MachineRecipe> codec() {
        return codec;
    }

    /**
     * The network form, for syncing recipes to clients; a value out of bounds fails with a {@code DecoderException}.
     */
    public StreamCodec<RegistryFriendlyByteBuf, MachineRecipe> streamCodec() {
        return streamCodec;
    }

    /** The registered recipe type; throws until registration has run. */
    public RecipeType<MachineRecipe> recipeType() {
        return type.get();
    }

    /** The registered serializer; throws until registration has run. */
    public RecipeSerializer<MachineRecipe> serializer() {
        return serializer.get();
    }

    /** The registered recipe book category every recipe of the type reports; none shows in the recipe book. */
    public RecipeBookCategory bookCategory() {
        return bookCategory.get();
    }

    @Override
    public String toString() {
        return "MachineRecipeType[" + id + "]";
    }

    private DataResult<MachineRecipe> validate(MachineRecipe recipe) {
        Optional<String> problem = shape.check(recipe);
        return problem.isPresent() ? DataResult.error(() -> id + ": " + problem.get()) : DataResult.success(recipe);
    }
}
