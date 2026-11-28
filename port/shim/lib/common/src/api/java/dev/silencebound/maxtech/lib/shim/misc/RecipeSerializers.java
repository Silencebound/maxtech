package dev.silencebound.maxtech.lib.shim.misc;

import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;

/**
 * 26.1 makes a recipe serializer with {@code new RecipeSerializer<>(codec, streamCodec)}; on 1.21.1 it is an interface.
 */
public final class RecipeSerializers {
    private RecipeSerializers() {
    }

    /** A serializer reading and writing with the given codecs. */
    public static <T extends Recipe<?>> RecipeSerializer<T> of(MapCodec<T> codec,
            StreamCodec<RegistryFriendlyByteBuf, T> streamCodec) {
        return new RecipeSerializer<>() {
            @Override
            public MapCodec<T> codec() {
                return codec;
            }

            @Override
            public StreamCodec<RegistryFriendlyByteBuf, T> streamCodec() {
                return streamCodec;
            }
        };
    }
}
