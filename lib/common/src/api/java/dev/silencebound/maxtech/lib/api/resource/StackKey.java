package dev.silencebound.maxtech.lib.api.resource;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/**
 * What a network stores and counts: an item or a fluid together with its data component patch, without an amount (PRD
 * 7.2, Storage data model). Two keys are equal when their kind, item or fluid, and patch are equal; hash codes are
 * computed once, so keys are cheap to use in hash maps. Networks intern keys to int ids and keep only the ids in their
 * hot structures.
 *
 * <p>
 * Immutable and safe on any thread. Saved with {@link #CODEC} (needs registry access for most components) and sent with
 * {@link #STREAM_CODEC}.
 */
public sealed interface StackKey permits ItemKey, FluidKey {
    /** {@code {"kind": "item" | "fluid", "id": ..., "components": {...}}}; components may be left out when empty. */
    Codec<StackKey> CODEC = Kind.CODEC.dispatch("kind", StackKey::kind, Kind::codec);

    /** A kind byte, then the item's or fluid's registry id and the patch. */
    StreamCodec<RegistryFriendlyByteBuf, StackKey> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public StackKey decode(RegistryFriendlyByteBuf buffer) {
            return buffer.readBoolean() ? FluidKey.STREAM_CODEC.decode(buffer) : ItemKey.STREAM_CODEC.decode(buffer);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, StackKey key) {
            switch (key) {
                case ItemKey item -> {
                    buffer.writeBoolean(false);
                    ItemKey.STREAM_CODEC.encode(buffer, item);
                }
                case FluidKey fluid -> {
                    buffer.writeBoolean(true);
                    FluidKey.STREAM_CODEC.encode(buffer, fluid);
                }
            }
        }
    };

    /** Whether this is an item or a fluid key. */
    Kind kind();

    /** The components that differ from the item's or fluid's defaults; {@link DataComponentPatch#EMPTY} for none. */
    DataComponentPatch components();

    /**
     * The {@code Item} or {@code Fluid} without components; keys of one base are variants of each other (damaged,
     * enchanted, named), which fuzzy filters match together.
     */
    Object base();

    /** Whether the key carries components; such keys cost more to store (PRD 7.2, cell bytes). */
    default boolean hasComponents() {
        return !components().isEmpty();
    }

    /** The two kinds of key. */
    enum Kind implements StringRepresentable {
        /** An {@link ItemKey}. */
        ITEM("item"),
        /** A {@link FluidKey}. */
        FLUID("fluid");

        /** The kind's lower-case name. */
        public static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);

        private final String name;

        Kind(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }

        private MapCodec<? extends StackKey> codec() {
            return this == ITEM ? ItemKey.MAP_CODEC : FluidKey.MAP_CODEC;
        }
    }
}
