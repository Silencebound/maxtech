package dev.silencebound.maxtech.storage.crafting.terminal;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternCodecs;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client to server: a recipe viewer's recipe of any type for the open pattern terminal {@code containerId}, as a
 * processing pattern. Lists are capped at decode and amounts and chances clamped by the draft, since a client sends it.
 *
 * @param inputs
 *            the recipe's inputs, in order, at most {@link #MAX_ENTRIES}
 * @param outputs
 *            its outputs, the main one first, at most {@link #MAX_ENTRIES}
 */
public record PatternTransfer(int containerId, Optional<Identifier> recipeType, Optional<Identifier> recipe,
        List<Entry> inputs, List<Entry> outputs) implements CustomPacketPayload {
    public static final int MAX_ENTRIES = 16;
    public static final Type<PatternTransfer> TYPE = new Type<>(MaxtechStorage.id("pattern_transfer"));

    /** One input or output: its key, amount (mB for fluids), the tag it came from if any, and its chance. */
    public record Entry(StackKey key, long amount, Optional<Identifier> tag, float chance) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> CODEC = StreamCodec.composite(
                StackKey.STREAM_CODEC, Entry::key, ByteBufCodecs.VAR_LONG, Entry::amount,
                ByteBufCodecs.optional(Identifier.STREAM_CODEC), Entry::tag, ByteBufCodecs.FLOAT, Entry::chance,
                Entry::new);

        public Entry {
            amount = Math.clamp(amount, 1, PatternCodecs.MAX_AMOUNT);
            chance = chance > 0f && chance <= 1f ? chance : 1f;
        }
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, PatternTransfer> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PatternTransfer::containerId, ByteBufCodecs.optional(Identifier.STREAM_CODEC),
            PatternTransfer::recipeType, ByteBufCodecs.optional(Identifier.STREAM_CODEC), PatternTransfer::recipe,
            Entry.CODEC.apply(ByteBufCodecs.list(MAX_ENTRIES)), PatternTransfer::inputs,
            Entry.CODEC.apply(ByteBufCodecs.list(MAX_ENTRIES)), PatternTransfer::outputs, PatternTransfer::new);

    public PatternTransfer {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
    }

    @Override
    public Type<PatternTransfer> type() {
        return TYPE;
    }
}
