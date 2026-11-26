package dev.silencebound.maxtech.storage.save;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

/**
 * {@link KeptValue}: a value naming an id that no longer loads (as an item of a removed mod would) is kept as saved and
 * written back byte for byte; readable values read and write as with the plain codec.
 */
class KeptValueTest {
    /** Stands in for a registry: only these ids load. */
    private static final Set<String> KNOWN = Set.of("minecraft:water", "minecraft:lava");

    private record Entry(String id, int amount) {
        static final Codec<String> ID = Codec.STRING.validate(
                id -> KNOWN.contains(id) ? DataResult.success(id) : DataResult.error(() -> "Unknown id " + id));
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i
                .group(ID.fieldOf("id").forGetter(Entry::id), Codec.INT.fieldOf("amount").forGetter(Entry::amount))
                .apply(i, Entry::new));
        static final StreamCodec<ByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8,
                Entry::id, ByteBufCodecs.VAR_INT, Entry::amount, Entry::new);
    }

    private static final Codec<KeptValue<Entry>> CODEC = KeptValue.codec(Entry.CODEC);

    private static CompoundTag saved(String id, int amount) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putInt("amount", amount);
        tag.putString("extra", "from a newer build");
        return tag;
    }

    @Test
    void unknownValueIsKeptAndWrittenBackUnchanged() {
        CompoundTag unknown = saved("removed_mod:goo", 250);
        KeptValue<Entry> read = CODEC.parse(NbtOps.INSTANCE, unknown).getOrThrow();
        assertTrue(read.isKept());
        assertNull(read.value());
        assertEquals(unknown, read.kept());
        assertEquals(unknown, CODEC.encodeStart(NbtOps.INSTANCE, read).getOrThrow());
    }

    @Test
    void readableValueReadsAsWithThePlainCodec() {
        Tag plain = Entry.CODEC.encodeStart(NbtOps.INSTANCE, new Entry("minecraft:water", 1000)).getOrThrow();
        KeptValue<Entry> read = CODEC.parse(NbtOps.INSTANCE, plain).getOrThrow();
        assertFalse(read.isKept());
        assertEquals(new Entry("minecraft:water", 1000), read.value());
        assertEquals(plain, CODEC.encodeStart(NbtOps.INSTANCE, read).getOrThrow());
    }

    @Test
    void listKeepsEachUnknownEntryInItsPlace() {
        Codec<List<KeptValue<Entry>>> list = CODEC.listOf();
        Tag water = Entry.CODEC.encodeStart(NbtOps.INSTANCE, new Entry("minecraft:water", 5)).getOrThrow();
        Tag lava = Entry.CODEC.encodeStart(NbtOps.INSTANCE, new Entry("minecraft:lava", 7)).getOrThrow();
        ListTag saved = new ListTag();
        saved.add(water);
        saved.add(saved("removed_mod:goo", 3));
        saved.add(lava);
        List<KeptValue<Entry>> read = list.parse(NbtOps.INSTANCE, saved).getOrThrow();
        assertEquals(3, read.size());
        assertEquals(new Entry("minecraft:water", 5), read.get(0).value());
        assertTrue(read.get(1).isKept());
        assertEquals(new Entry("minecraft:lava", 7), read.get(2).value());
        assertEquals(saved, list.encodeStart(NbtOps.INSTANCE, read).getOrThrow());
    }

    @Test
    void unknownValueComesBackOnceItsIdLoads() {
        CompoundTag later = saved("minecraft:lava", 9);
        later.remove("extra");
        KeptValue<Entry> kept = KeptValue.ofKept(later);
        Tag written = CODEC.encodeStart(NbtOps.INSTANCE, kept).getOrThrow();
        assertEquals(new Entry("minecraft:lava", 9), CODEC.parse(NbtOps.INSTANCE, written).getOrThrow().value());
    }

    @Test
    void streamCodecCarriesBothKinds() {
        StreamCodec<ByteBuf, KeptValue<Entry>> stream = KeptValue.streamCodec(Entry.STREAM_CODEC);
        KeptValue<Entry> known = KeptValue.of(new Entry("minecraft:water", 2));
        KeptValue<Entry> kept = KeptValue.ofKept(saved("removed_mod:goo", 3));
        ByteBuf buf = Unpooled.buffer();
        stream.encode(buf, known);
        stream.encode(buf, kept);
        assertEquals(known, stream.decode(buf));
        assertEquals(kept, stream.decode(buf));
    }
}
