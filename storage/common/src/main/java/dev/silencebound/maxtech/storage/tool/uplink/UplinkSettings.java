package dev.silencebound.maxtech.storage.tool.uplink;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A pocket uplink's settings: whether overflow is on, and the items it sends (any item when the filter is empty). The
 * filter matches the item only, whatever its components, so damaged tools and named items still match.
 *
 * @param active
 *            whether picked-up items overflow to the network at all
 * @param filter
 *            the items that overflow; empty for every item
 */
public record UplinkSettings(boolean active, List<Item> filter) {
    /** Most filter entries any config allows; the codecs refuse longer lists. */
    public static final int MAX_FILTER = 64;
    public static final UplinkSettings DEFAULT = new UplinkSettings(true, List.of());

    public static final Codec<UplinkSettings> CODEC = RecordCodecBuilder.create(instance -> instance
            .group(Codec.BOOL.optionalFieldOf("active", true).forGetter(UplinkSettings::active),
                    BuiltInRegistries.ITEM.byNameCodec().sizeLimitedListOf(MAX_FILTER)
                            .optionalFieldOf("filter", List.of()).forGetter(UplinkSettings::filter))
            .apply(instance, UplinkSettings::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, UplinkSettings> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, UplinkSettings::active,
            ByteBufCodecs.registry(Registries.ITEM).apply(ByteBufCodecs.list(MAX_FILTER)), UplinkSettings::filter,
            UplinkSettings::new);

    public UplinkSettings {
        filter = List.copyOf(filter);
    }

    /** Whether {@code stack} overflows under these settings. Allocates nothing. */
    public boolean accepts(ItemStack stack) {
        if (!active) {
            return false;
        }
        if (filter.isEmpty()) {
            return true;
        }
        Item item = stack.getItem();
        for (int i = 0; i < filter.size(); i++) {
            if (filter.get(i).equals(item)) {
                return true;
            }
        }
        return false;
    }

    public UplinkSettings withActive(boolean value) {
        return new UplinkSettings(value, filter);
    }

    /**
     * {@code item} added to the filter, or taken off when it is on it already; unchanged when the filter already holds
     * {@code max} items.
     */
    public UplinkSettings toggled(Item item, int max) {
        List<Item> changed = new ArrayList<>(filter);
        if (!changed.remove(item)) {
            if (changed.size() >= Math.min(max, MAX_FILTER)) {
                return this;
            }
            changed.add(item);
        }
        return new UplinkSettings(active, changed);
    }

    public UplinkSettings cleared() {
        return new UplinkSettings(active, List.of());
    }
}
