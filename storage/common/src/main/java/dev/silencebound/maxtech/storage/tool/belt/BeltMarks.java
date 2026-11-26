package dev.silencebound.maxtech.storage.tool.belt;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ExtraCodecs;
import org.jspecify.annotations.Nullable;

/**
 * The slots a restock belt keeps filled: per marked inventory slot (0 to 35, hotbar first), the item with its
 * components and the count to keep there. At most one mark per slot.
 *
 * @param marks
 *            the marks, in the order they were made
 */
public record BeltMarks(List<Mark> marks) {
    /** Inventory slots a mark may name: the hotbar and the main inventory. */
    public static final int SLOTS = 36;
    public static final BeltMarks EMPTY = new BeltMarks(List.of());

    public static final Codec<BeltMarks> CODEC = Mark.CODEC.sizeLimitedListOf(SLOTS).xmap(BeltMarks::new,
            BeltMarks::marks);
    public static final StreamCodec<RegistryFriendlyByteBuf, BeltMarks> STREAM_CODEC = Mark.STREAM_CODEC
            .apply(ByteBufCodecs.list(SLOTS)).map(BeltMarks::new, BeltMarks::marks);

    public BeltMarks {
        marks = List.copyOf(marks);
    }

    /**
     * One marked slot.
     *
     * @param slot
     *            the inventory slot, 0 to 35
     * @param key
     *            the item kept there
     * @param count
     *            how many to keep there, at least 1
     */
    public record Mark(int slot, ItemKey key, int count) {
        public static final Codec<Mark> CODEC = RecordCodecBuilder
                .create(instance -> instance
                        .group(Codec.intRange(0, SLOTS - 1).fieldOf("slot").forGetter(Mark::slot),
                                ItemKey.MAP_CODEC.codec().fieldOf("item").forGetter(Mark::key),
                                ExtraCodecs.POSITIVE_INT.fieldOf("count").forGetter(Mark::count))
                        .apply(instance, Mark::new));
        public static final StreamCodec<RegistryFriendlyByteBuf, Mark> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Mark::slot, ItemKey.STREAM_CODEC, Mark::key, ByteBufCodecs.VAR_INT, Mark::count,
                Mark::new);
    }

    /** The mark on {@code slot}, or null. */
    public @Nullable Mark at(int slot) {
        for (int i = 0; i < marks.size(); i++) {
            Mark mark = marks.get(i);
            if (mark.slot() == slot) {
                return mark;
            }
        }
        return null;
    }

    /**
     * These marks with {@code slot} marked for {@code count} of {@code key} (replacing a mark there); unchanged when
     * the slot is out of range, the count below 1, or a new mark would pass {@code max}.
     */
    public BeltMarks with(int slot, ItemKey key, int count, int max) {
        if (slot < 0 || slot >= SLOTS || count < 1) {
            return this;
        }
        List<Mark> changed = new ArrayList<>(marks.size() + 1);
        boolean replaced = false;
        for (Mark mark : marks) {
            if (mark.slot() == slot) {
                changed.add(new Mark(slot, key, count));
                replaced = true;
            } else {
                changed.add(mark);
            }
        }
        if (!replaced) {
            if (marks.size() >= max) {
                return this;
            }
            changed.add(new Mark(slot, key, count));
        }
        return new BeltMarks(changed);
    }

    /** These marks without the one on {@code slot}. */
    public BeltMarks without(int slot) {
        List<Mark> changed = new ArrayList<>(marks.size());
        for (Mark mark : marks) {
            if (mark.slot() != slot) {
                changed.add(mark);
            }
        }
        return changed.size() == marks.size() ? this : new BeltMarks(changed);
    }

    /** These marks with {@code slot}'s count moved by {@code delta}, kept between 1 and {@code limit}. */
    public BeltMarks adjusted(int slot, int delta, int limit) {
        Mark mark = at(slot);
        if (mark == null) {
            return this;
        }
        int count = (int) Math.max(1, Math.min((long) limit, (long) mark.count() + delta));
        return count == mark.count() ? this : with(slot, mark.key(), count, SLOTS);
    }
}
