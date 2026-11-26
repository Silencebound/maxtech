package dev.silencebound.maxtech.storage.network;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.DyeColor;
import org.jspecify.annotations.Nullable;

/**
 * A network device's label (M7L area C): free text as the player typed it plus an optional dye colour. Logic graphs
 * address devices by label, case-insensitively ({@link #key()}); several devices may share one. A label with no text is
 * {@link #NONE}, whatever colour was picked. Build labels with {@link #of}, which trims, drops control and formatting
 * characters and cuts the text to {@link #MAX_LENGTH}. Immutable.
 *
 * @param text
 *            the text as typed, already cleaned; empty for {@link #NONE}
 * @param colour
 *            the dye colour, or null for none
 */
public record DeviceLabel(String text, @Nullable DyeColor colour) {
    /** Longest label text in characters; a GUI and packet bound, not a balance value. */
    public static final int MAX_LENGTH = 32;
    /** No label. */
    public static final DeviceLabel NONE = new DeviceLabel("", null);

    public static final Codec<DeviceLabel> CODEC = RecordCodecBuilder
            .create(instance -> instance
                    .group(Codec.STRING.fieldOf("text").forGetter(DeviceLabel::text),
                            DyeColor.CODEC.optionalFieldOf("colour")
                                    .forGetter(label -> Optional.ofNullable(label.colour())))
                    .apply(instance, (text, colour) -> of(text, colour.orElse(null))));
    public static final StreamCodec<ByteBuf, DeviceLabel> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_LENGTH), DeviceLabel::text, ByteBufCodecs.optional(DyeColor.STREAM_CODEC),
            label -> Optional.ofNullable(label.colour()), (text, colour) -> of(text, colour.orElse(null)));

    /** The label for {@code raw} text and {@code colour}, cleaned; {@link #NONE} when no text is left. */
    public static DeviceLabel of(String raw, @Nullable DyeColor colour) {
        String text = clean(raw);
        return text.isEmpty() ? NONE : new DeviceLabel(text, colour);
    }

    /**
     * {@code raw} as a label text: control characters and the section sign (formatting codes) removed, trimmed, and cut
     * to {@link #MAX_LENGTH} characters without splitting a surrogate pair.
     */
    public static String clean(String raw) {
        StringBuilder out = new StringBuilder(Math.min(raw.length(), MAX_LENGTH + 8));
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!Character.isISOControl(c) && c != '§') {
                out.append(c);
            }
        }
        String text = out.toString().strip();
        if (text.length() > MAX_LENGTH) {
            int end = Character.isHighSurrogate(text.charAt(MAX_LENGTH - 1)) ? MAX_LENGTH - 1 : MAX_LENGTH;
            text = text.substring(0, end).strip();
        }
        return text;
    }

    /**
     * The lookup key of typed text: cleaned and lower-cased, so "Smelters" and " smelters " find the same devices.
     * Empty for text that is no label.
     */
    public static String key(String raw) {
        return clean(raw).toLowerCase(Locale.ROOT);
    }

    /** This label's lookup key; see {@link #key(String)}. */
    public String key() {
        return text.toLowerCase(Locale.ROOT);
    }

    /** Whether this is {@link #NONE}. */
    public boolean isEmpty() {
        return text.isEmpty();
    }

    /** The text as a component in the label's colour (the dye's text colour); plain without a colour. */
    public MutableComponent component() {
        MutableComponent out = Component.literal(text);
        DyeColor dye = colour;
        return dye == null ? out : out.withColor(dye.getTextColor());
    }
}
