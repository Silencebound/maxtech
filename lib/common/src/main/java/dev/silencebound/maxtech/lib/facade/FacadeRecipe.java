package dev.silencebound.maxtech.lib.facade;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.lib.registry.ModDataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Shapeless: one block a facade may mimic ({@link Facades#canMimic}, judged on its default state) plus one
 * {@code binder} gives {@code result}, with the block in its {@link ModDataComponents#FACADE} component. A special
 * recipe, since the output depends on which block went in; the binder and the result count are data.
 */
public final class FacadeRecipe extends CustomRecipe {
    public static final MapCodec<FacadeRecipe> MAP_CODEC = RecordCodecBuilder
            .mapCodec(i -> i
                    .group(Ingredient.CODEC.fieldOf("binder").forGetter(r -> r.binder),
                            ItemStackTemplate.CODEC.fieldOf("result").forGetter(r -> r.result))
                    .apply(i, FacadeRecipe::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, FacadeRecipe> STREAM_CODEC = StreamCodec.composite(
            Ingredient.CONTENTS_STREAM_CODEC, r -> r.binder, ItemStackTemplate.STREAM_CODEC, r -> r.result,
            FacadeRecipe::new);
    public static final RecipeSerializer<FacadeRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    private final Ingredient binder;
    private final ItemStackTemplate result;

    public FacadeRecipe(Ingredient binder, ItemStackTemplate result) {
        this.binder = binder;
        this.result = result;
    }

    /** What binds the block into a facade; the progression checks read it, since the recipe has no display. */
    public Ingredient binder() {
        return binder;
    }

    /** The facade this makes, without its block. */
    public ItemStackTemplate result() {
        return result;
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return input.ingredientCount() == 2 && block(input) != null;
    }

    @Override
    public ItemStack assemble(CraftingInput input) {
        BlockState block = block(input);
        if (block == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = result.create();
        stack.set(ModDataComponents.FACADE.get(), block);
        return stack;
    }

    /** The block the grid's facade would mimic: exactly one binder and one usable block, nothing else. */
    private @Nullable BlockState block(CraftingInput input) {
        boolean bound = false;
        BlockState block = null;
        for (int slot = 0; slot < input.size(); slot++) {
            ItemStack stack = input.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (!bound && binder.test(stack)) {
                bound = true;
                continue;
            }
            BlockState mimic = Facades.mimicOf(stack);
            if (mimic == null || block != null) {
                return null;
            }
            block = mimic;
        }
        return bound ? block : null;
    }

    @Override
    public RecipeSerializer<FacadeRecipe> getSerializer() {
        return SERIALIZER;
    }
}
