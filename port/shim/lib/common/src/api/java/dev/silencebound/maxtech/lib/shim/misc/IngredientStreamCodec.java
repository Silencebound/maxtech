package dev.silencebound.maxtech.lib.shim.misc;

import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * 26.1's {@code Ingredient.CONTENTS_STREAM_CODEC} sends a tag ingredient as its tag; 1.21.1's sends the tag's items,
 * so a recipe synced to the client would match only what the tag held on the server at sync time and save back as an
 * item list. This codec sends tag and item values as themselves and anything else (NeoForge's custom ingredients)
 * through 1.21.1's codec. Both ends are Lib, so the wire format is Lib's own.
 */
public final class IngredientStreamCodec {
    /** Values above this from the network are a bad packet. */
    private static final int MAX_VALUES = 256;

    public static final StreamCodec<RegistryFriendlyByteBuf, Ingredient> INSTANCE = new StreamCodec<>() {
        @Override
        public void encode(RegistryFriendlyByteBuf buffer, Ingredient ingredient) {
            if (!ingredient.isCustom() && plain(ingredient.getValues())) {
                Ingredient.Value[] values = ingredient.getValues();
                buffer.writeVarInt(values.length);
                for (Ingredient.Value value : values) {
                    if (value instanceof Ingredient.TagValue tag) {
                        buffer.writeBoolean(true);
                        ResourceLocation.STREAM_CODEC.encode(buffer, tag.tag().location());
                    } else {
                        buffer.writeBoolean(false);
                        ItemStack.STREAM_CODEC.encode(buffer, ((Ingredient.ItemValue) value).item());
                    }
                }
            } else {
                buffer.writeVarInt(-1);
                Ingredient.CONTENTS_STREAM_CODEC.encode(buffer, ingredient);
            }
        }

        @Override
        public Ingredient decode(RegistryFriendlyByteBuf buffer) {
            int size = buffer.readVarInt();
            if (size == -1) {
                return Ingredient.CONTENTS_STREAM_CODEC.decode(buffer);
            }
            if (size < 0 || size > MAX_VALUES) {
                throw new DecoderException("Ingredient with " + size + " values");
            }
            List<Ingredient.Value> values = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                values.add(buffer.readBoolean()
                        ? new Ingredient.TagValue(TagKey.create(Registries.ITEM, ResourceLocation.STREAM_CODEC.decode(buffer)))
                        : new Ingredient.ItemValue(ItemStack.STREAM_CODEC.decode(buffer)));
            }
            return Ingredient.fromValues(values.stream());
        }
    };

    private IngredientStreamCodec() {
    }

    private static boolean plain(Ingredient.Value[] values) {
        for (Ingredient.Value value : values) {
            if (!(value instanceof Ingredient.TagValue) && !(value instanceof Ingredient.ItemValue)) {
                return false;
            }
        }
        return values.length <= MAX_VALUES;
    }
}
