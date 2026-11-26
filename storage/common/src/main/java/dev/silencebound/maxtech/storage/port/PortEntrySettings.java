package dev.silencebound.maxtech.storage.port;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;

/**
 * One filter slot's settings beyond its item (B09 items 18 and 22): how it matches, the tag it matches by in tag mode,
 * and how many of what it matches an export port keeps in the inventory (0: push everything). Saved, and held by the
 * port's item, as a list of these for the slots that differ from the default (exact, keep 0); a port saved before they
 * existed has none, so every slot loads as exact.
 *
 * @param slot
 *            the filter slot, 0 to {@link PortFilter#SIZE} - 1
 * @param match
 *            how the slot matches
 * @param tag
 *            the tag in {@link MatchMode#TAG}; ignored otherwise
 * @param keep
 *            how many an export port keeps in the inventory; 0 for no limit
 */
public record PortEntrySettings(int slot, MatchMode match, Optional<Identifier> tag, int keep) {
    public static final Codec<PortEntrySettings> CODEC = RecordCodecBuilder.create(i -> i
            .group(Codec.intRange(0, PortFilter.SIZE - 1).fieldOf("slot").forGetter(PortEntrySettings::slot),
                    StringRepresentable.fromEnum(MatchMode::values).optionalFieldOf("match", MatchMode.EXACT)
                            .forGetter(PortEntrySettings::match),
                    Identifier.CODEC.optionalFieldOf("tag").forGetter(PortEntrySettings::tag),
                    Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("keep", 0).forGetter(PortEntrySettings::keep))
            .apply(i, PortEntrySettings::new));
    /** The settings of a whole filter, as saves and items hold them. */
    public static final Codec<List<PortEntrySettings>> LIST_CODEC = CODEC.sizeLimitedListOf(PortFilter.SIZE);

    /** Whether these are a fresh slot's settings, which nothing writes. */
    public boolean isDefault() {
        return match == MatchMode.EXACT && keep == 0;
    }
}
