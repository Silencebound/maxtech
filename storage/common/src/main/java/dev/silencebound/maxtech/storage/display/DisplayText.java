package dev.silencebound.maxtech.storage.display;

import dev.silencebound.maxtech.lib.text.RichText;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.sensor.SensorRef;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * Storage's use of Lib's {@link RichText} markup: the placeholders a display's text may hold, what they read, the
 * server's check of a text a player saves, and the plain chat form of a logic notice. Both sides parse a screen's text
 * the same way, so the n-th placeholder of the client's parse is the n-th value the server sends.
 *
 * <p>
 * Placeholders:
 * <ul>
 * <li>{@code {count:minecraft:diamond}} (or {@code {count:diamond}}): how many of an item the screen's network
 * holds;</li>
 * <li>{@code {count:#c:ingots/iron}}: the total of every item in an item tag;</li>
 * <li>{@code {sensor:label:value_id}}: a value of the first sensor on the network with that label (labels with a colon
 * cannot be named), such as {@code {sensor:furnace:energy.stored}}; the value ids are the sensor's
 * ({@link SensorRef}).</li>
 * </ul>
 */
public final class DisplayText {
    /** The placeholder names a display's text may use. */
    public static final String COUNT = "count";
    public static final String SENSOR = "sensor";
    public static final Set<String> PLACEHOLDERS = Set.of(COUNT, SENSOR);

    /** Why a text was refused; lang keys with the offending part as {@code %s} where there is one. */
    public static final String TOO_LONG_KEY = "gui." + MaxtechStorage.MOD_ID + ".display.text.too_long";
    public static final String NO_SUCH_ITEM_KEY = "gui." + MaxtechStorage.MOD_ID + ".display.text.no_such_item";
    public static final String BAD_COUNT_KEY = "gui." + MaxtechStorage.MOD_ID + ".display.text.bad_count";
    public static final String BAD_SENSOR_KEY = "gui." + MaxtechStorage.MOD_ID + ".display.text.bad_sensor";

    private DisplayText() {
    }

    /** {@code source} parsed with the display placeholders. */
    public static RichText parse(String source) {
        return RichText.parse(source, PLACEHOLDERS);
    }

    /** What a {@code count} placeholder counts: one item, or the items of a tag. Exactly one is set. */
    public record CountTarget(@Nullable Item item, @Nullable TagKey<Item> tag) {
    }

    /** What a {@code sensor} placeholder reads: the label's lookup key and the sensor's value id. */
    public record SensorTarget(String labelKey, String valueId) {
    }

    /** The target of a {@code count} placeholder's argument; empty for a malformed one or an unknown item. */
    public static Optional<CountTarget> count(String argument) {
        String text = argument.trim();
        if (text.startsWith("#")) {
            Identifier id = Identifier.tryParse(text.substring(1));
            return id == null
                    ? Optional.empty()
                    : Optional.of(new CountTarget(null, TagKey.create(Registries.ITEM, id)));
        }
        Item item = item(text);
        return item == null ? Optional.empty() : Optional.of(new CountTarget(item, null));
    }

    /** The target of a {@code sensor} placeholder's argument; empty without a label or with an unknown value id. */
    public static Optional<SensorTarget> sensor(String argument) {
        int colon = argument.indexOf(':');
        if (colon < 0) {
            return Optional.empty();
        }
        String label = DeviceLabel.key(argument.substring(0, colon));
        String value = argument.substring(colon + 1).trim();
        return label.isEmpty() || SensorRef.parse(value).isEmpty()
                ? Optional.empty()
                : Optional.of(new SensorTarget(label, value));
    }

    /** The item with id {@code id} ({@code minecraft} without a namespace), or null if there is none. */
    public static @Nullable Item item(String id) {
        Identifier parsed = Identifier.tryParse(id.trim());
        return parsed == null || !BuiltInRegistries.ITEM.containsKey(parsed)
                ? null
                : BuiltInRegistries.ITEM.getValue(parsed);
    }

    /**
     * Checks a text a player wants to save, as the server must for a hostile client: not over the caps, every icon an
     * existing item, every placeholder readable. Returns the refusal as a component, or empty when it is fine.
     */
    public static Optional<Component> problem(String source) {
        if (source.length() > RichText.MAX_LENGTH) {
            return Optional.of(Component.translatable(TOO_LONG_KEY, RichText.MAX_LENGTH));
        }
        RichText text = parse(source);
        if (text.truncated()) {
            return Optional.of(Component.translatable(TOO_LONG_KEY, RichText.MAX_LENGTH));
        }
        for (RichText.Icon icon : text.icons()) {
            if (item(icon.id()) == null) {
                return Optional.of(Component.translatable(NO_SUCH_ITEM_KEY, icon.id()));
            }
        }
        for (RichText.Value value : text.values()) {
            boolean ok = switch (value.name()) {
                case COUNT -> count(value.argument()).isPresent();
                case SENSOR -> sensor(value.argument()).isPresent();
                default -> false;
            };
            if (!ok) {
                return Optional.of(Component.translatable(value.name().equals(COUNT) ? BAD_COUNT_KEY : BAD_SENSOR_KEY,
                        value.argument()));
            }
        }
        return Optional.empty();
    }

    /** The vanilla style of a run's style. */
    public static Style style(RichText.Style style) {
        Style out = Style.EMPTY;
        if (style.colour() != RichText.NO_COLOUR) {
            out = out.withColor(style.colour());
        }
        if (style.bold()) {
            out = out.withBold(true);
        }
        if (style.italic()) {
            out = out.withItalic(true);
        }
        return out;
    }

    /**
     * {@code source} as one chat line for a logic notice (log, toast, chat): colours and bold or italic as styles, an
     * icon as the item's name in brackets, lines joined with a space. Placeholders are not read in notices.
     */
    public static MutableComponent component(String source) {
        MutableComponent out = Component.empty();
        List<RichText.Line> lines = RichText.parse(source).lines();
        for (int l = 0; l < lines.size(); l++) {
            if (l > 0) {
                out.append(" ");
            }
            for (RichText.Run run : lines.get(l).runs()) {
                Style style = style(run.style());
                if (run instanceof RichText.Text text) {
                    out.append(Component.literal(text.text()).withStyle(style));
                } else if (run instanceof RichText.Icon icon) {
                    Item item = item(icon.id());
                    out.append(Component.literal("[").withStyle(style)
                            .append(item == null
                                    ? Component.literal(icon.id())
                                    : Component.translatable(item.getDescriptionId()))
                            .append("]"));
                }
            }
        }
        return out;
    }
}
