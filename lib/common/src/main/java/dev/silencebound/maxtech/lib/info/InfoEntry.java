package dev.silencebound.maxtech.lib.info;

import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.api.info.InfoStack;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * One entry a {@link BlockInfoSink} received, as data, so overlays can send it to the client and draw each kind with
 * their own elements. Which fields matter depends on the kind; the others are 0, empty or an empty list.
 *
 * @param kind
 *            what the entry is
 * @param text
 *            the entry's text; for a bar its label, for the others what it says in words
 * @param rgb
 *            a status's or bar's colour as {@code 0xRRGGBB}
 * @param value
 *            a bar's value, or the used pips
 * @param max
 *            a bar's maximum, or the pips' capacity
 * @param sprite
 *            a status's or icon's sprite (GUI sprite id)
 * @param stacks
 *            the items and fluids of a stacks entry, at most {@link #MAX_STACKS}
 */
public record InfoEntry(Kind kind, Component text, int rgb, long value, long max, Optional<Identifier> sprite,
        List<InfoStack> stacks) {
    /** Most stacks one entry keeps; the rest are dropped before sending. */
    public static final int MAX_STACKS = 8;

    /** What an entry is. */
    public enum Kind {
        TEXT(0), STATUS(1), PROGRESS(2), PIPS(3), STACKS(4), ICON(5);

        /** The kind's id on the wire; fixed per kind. */
        final int id;

        Kind(int id) {
            this.id = id;
        }

        /** The kind with {@code id}; text for an unknown id (sent by another version). */
        static Kind byId(int id) {
            for (Kind kind : values()) {
                if (kind.id == id) {
                    return kind;
                }
            }
            return TEXT;
        }
    }

    private static final StreamCodec<RegistryFriendlyByteBuf, InfoStack> STACK_CODEC = StreamCodec.composite(
            StackKey.STREAM_CODEC, InfoStack::key, ByteBufCodecs.VAR_LONG, InfoStack::amount, InfoStack::new);

    public static final StreamCodec<RegistryFriendlyByteBuf, InfoEntry> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public InfoEntry decode(RegistryFriendlyByteBuf buffer) {
            Kind kind = Kind.byId(buffer.readVarInt());
            Component text = ComponentSerialization.STREAM_CODEC.decode(buffer);
            int rgb = buffer.readInt();
            long value = buffer.readVarLong();
            long max = buffer.readVarLong();
            Optional<Identifier> sprite = ByteBufCodecs.optional(Identifier.STREAM_CODEC).decode(buffer);
            List<InfoStack> stacks = STACK_CODEC.apply(ByteBufCodecs.list(MAX_STACKS)).decode(buffer);
            return new InfoEntry(kind, text, rgb, value, max, sprite, stacks);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, InfoEntry entry) {
            buffer.writeVarInt(entry.kind.id);
            ComponentSerialization.STREAM_CODEC.encode(buffer, entry.text);
            buffer.writeInt(entry.rgb);
            buffer.writeVarLong(entry.value);
            buffer.writeVarLong(entry.max);
            ByteBufCodecs.optional(Identifier.STREAM_CODEC).encode(buffer, entry.sprite);
            STACK_CODEC.apply(ByteBufCodecs.list(MAX_STACKS)).encode(buffer, entry.stacks);
        }
    };

    /** A plain line. */
    public static InfoEntry text(Component text) {
        return new InfoEntry(Kind.TEXT, text, 0, 0, 0, Optional.empty(), List.of());
    }

    /** A status in {@code tone}'s colour, drawn as {@code sprite} if there is one. */
    public static InfoEntry status(BlockInfoSink.Tone tone, Component text, Optional<Identifier> sprite) {
        return new InfoEntry(Kind.STATUS, text, tone.rgb(), 0, 0, sprite, List.of());
    }

    /** A bar filled {@code value} of {@code max}, clamped to {@code 0..max}; empty when {@code max} is 0 or less. */
    public static InfoEntry progress(Component text, long value, long max, int rgb) {
        long top = Math.max(0, max);
        return new InfoEntry(Kind.PROGRESS, text, rgb & 0xFFFFFF, Math.clamp(value, 0, top), top, Optional.empty(),
                List.of());
    }

    /** {@code used} of {@code capacity} channel pips. */
    public static InfoEntry pips(Component text, int used, int capacity) {
        int top = Math.max(0, capacity);
        return new InfoEntry(Kind.PIPS, text, 0, Math.clamp(used, 0, top), top, Optional.empty(), List.of());
    }

    /** Item and fluid icons, the first {@link #MAX_STACKS} of {@code stacks}. */
    public static InfoEntry stacks(Component text, List<InfoStack> stacks) {
        List<InfoStack> kept = stacks.size() <= MAX_STACKS
                ? List.copyOf(stacks)
                : List.copyOf(new ArrayList<>(stacks.subList(0, MAX_STACKS)));
        return new InfoEntry(Kind.STACKS, text, 0, 0, 0, Optional.empty(), kept);
    }

    /** {@code sprite} followed by {@code text}. */
    public static InfoEntry icon(Identifier sprite, Component text) {
        return new InfoEntry(Kind.ICON, text, 0, 0, 0, Optional.of(sprite), List.of());
    }

    /** This entry with {@code sprite} (a bar's leading icon). */
    public InfoEntry withSprite(Identifier sprite) {
        return new InfoEntry(kind, text, rgb, value, max, Optional.of(sprite), stacks);
    }

    /** How full a bar is, 0 to 1; 0 for an empty maximum. */
    public float fraction() {
        return max <= 0 ? 0 : (float) ((double) value / max);
    }
}
