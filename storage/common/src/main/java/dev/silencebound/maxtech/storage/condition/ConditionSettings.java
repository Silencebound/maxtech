package dev.silencebound.maxtech.storage.condition;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;

/**
 * A condition switch's settings: what it watches, the threshold and its side, and whether the output is inverted. The
 * condition holds when the watched value is above (or below) the threshold; a research condition holds when its node is
 * done. Immutable; saved with the device and kept in its item ({@code maxtech_storage:condition}).
 *
 * @param type
 *            what is watched
 * @param above
 *            whether the condition holds above the threshold rather than below it
 * @param threshold
 *            the count, or the percentage for {@link ConditionType#percent()} types
 * @param item
 *            the item an item count watches
 * @param tag
 *            the item tag a tag count watches
 * @param node
 *            the research node a research condition watches
 * @param inverted
 *            whether the redstone output is on while the condition does not hold; for a channel switch, 15 minus the
 *            value
 * @param channel
 *            the network redstone channel a channel switch reads or writes, as a dye colour id (0 white to 15 black)
 */
public record ConditionSettings(ConditionType type, boolean above, long threshold, Optional<ItemKey> item,
        Optional<TagKey<Item>> tag, Optional<Identifier> node, boolean inverted, int channel) {
    /** Counts stay well inside a long so a threshold step never overflows. */
    public static final long MAX_COUNT = Long.MAX_VALUE / 16;
    public static final ConditionSettings DEFAULT = new ConditionSettings(ConditionType.ITEM_COUNT, true, 0,
            Optional.empty(), Optional.empty(), Optional.empty(), false, 0);
    public static final Codec<ConditionSettings> CODEC = RecordCodecBuilder.create(instance -> instance
            .group(StringRepresentable.fromEnum(ConditionType::values).fieldOf("type")
                    .forGetter(ConditionSettings::type),
                    Codec.BOOL.optionalFieldOf("above", true).forGetter(ConditionSettings::above),
                    Codec.LONG.optionalFieldOf("threshold", 0L).forGetter(ConditionSettings::threshold),
                    ItemKey.MAP_CODEC.codec().optionalFieldOf("item").forGetter(ConditionSettings::item),
                    TagKey.codec(Registries.ITEM).optionalFieldOf("tag").forGetter(ConditionSettings::tag),
                    Identifier.CODEC.optionalFieldOf("node").forGetter(ConditionSettings::node),
                    Codec.BOOL.optionalFieldOf("inverted", false).forGetter(ConditionSettings::inverted),
                    Codec.intRange(0, 15).optionalFieldOf("channel", 0).forGetter(ConditionSettings::channel))
            .apply(instance, ConditionSettings::new));

    public ConditionSettings {
        threshold = Math.clamp(threshold, 0, type.percent() ? 100 : MAX_COUNT);
        channel = Math.floorMod(channel, 16);
    }

    public ConditionSettings withChannel(int channel) {
        return new ConditionSettings(type, above, threshold, item, tag, node, inverted, channel);
    }

    public ConditionSettings withType(ConditionType type) {
        return new ConditionSettings(type, above, threshold, item, tag, node, inverted, channel);
    }

    public ConditionSettings withAbove(boolean above) {
        return new ConditionSettings(type, above, threshold, item, tag, node, inverted, channel);
    }

    public ConditionSettings withThreshold(long threshold) {
        return new ConditionSettings(type, above, threshold, item, tag, node, inverted, channel);
    }

    public ConditionSettings withItem(Optional<ItemKey> item) {
        return new ConditionSettings(type, above, threshold, item, tag, node, inverted, channel);
    }

    public ConditionSettings withTag(Optional<TagKey<Item>> tag) {
        return new ConditionSettings(type, above, threshold, item, tag, node, inverted, channel);
    }

    public ConditionSettings withNode(Optional<Identifier> node) {
        return new ConditionSettings(type, above, threshold, item, tag, node, inverted, channel);
    }

    public ConditionSettings withInverted(boolean inverted) {
        return new ConditionSettings(type, above, threshold, item, tag, node, inverted, channel);
    }

    /** Whether {@code value} meets the threshold on the configured side; never at the threshold itself. */
    public boolean compare(long value) {
        return above ? value > threshold : value < threshold;
    }
}
