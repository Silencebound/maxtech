package dev.silencebound.maxtech.lib.api.resource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/**
 * {@link KindKey}'s save and network codecs over test kinds; the built-in kinds need registries and run in the
 * {@code resource_kinds_*} GameTests.
 */
class KindKeyTest {
    private static final Identifier TEXT = Identifier.fromNamespaceAndPath("test", "text");
    private static final Identifier NUMBER = Identifier.fromNamespaceAndPath("test", "number");
    private static final Identifier GONE = Identifier.fromNamespaceAndPath("test", "gone");

    /** A map kind, a plain-value kind, and a kind only the sender has. */
    private final KindTable sender = new KindTable();
    private final ResourceKind<String> text = sender.register(TEXT, Codec.STRING.fieldOf("text").codec(),
            ByteBufCodecs.STRING_UTF8.cast(), String::toLowerCase, AmountUnit.ITEMS);
    private final ResourceKind<Integer> number = sender.register(NUMBER, Codec.INT, ByteBufCodecs.VAR_INT.cast(),
            key -> key, AmountUnit.MILLIBUCKETS);
    private final ResourceKind<Integer> gone = sender.register(GONE, Codec.INT, ByteBufCodecs.VAR_INT.cast(),
            key -> key, AmountUnit.ITEMS);
    private final KindTable receiver = new KindTable();
    private final ResourceKind<String> receiverText = receiver.register(TEXT, text.codec(), text.streamCodec(),
            key -> key, AmountUnit.ITEMS);
    private final ResourceKind<Integer> receiverNumber = receiver.register(NUMBER, number.codec(), number.streamCodec(),
            key -> key, AmountUnit.ITEMS);

    private final Codec<KindKey<?>> senderCodec = KindKey.codec(sender::byId);
    private final Codec<KindKey<?>> receiverCodec = KindKey.codec(receiver::byId);

    @Test
    void mapKindsSaveTheirFieldsNextToKind() {
        Tag saved = senderCodec.encodeStart(NbtOps.INSTANCE, new KindKey<>(text, "Hello")).getOrThrow();
        CompoundTag expected = new CompoundTag();
        expected.putString("kind", "test:text");
        expected.putString("text", "Hello");
        assertEquals(expected, saved);
        assertEquals(new KindKey<>(text, "Hello"), senderCodec.parse(NbtOps.INSTANCE, saved).getOrThrow());
    }

    @Test
    void otherKindsSaveTheirValueUnderValue() {
        Tag saved = senderCodec.encodeStart(NbtOps.INSTANCE, new KindKey<>(number, 42)).getOrThrow();
        CompoundTag expected = new CompoundTag();
        expected.putString("kind", "test:number");
        expected.putInt("value", 42);
        assertEquals(expected, saved);
        assertEquals(new KindKey<>(number, 42), senderCodec.parse(NbtOps.INSTANCE, saved).getOrThrow());
    }

    @Test
    void unknownKindLoadsAsUnknownAndSavesBackByteIdentical() throws IOException {
        CompoundTag saved = new CompoundTag();
        saved.putString("kind", "othermod:chemical");
        saved.putString("id", "othermod:hydrogen");
        saved.putLong("amount", 123_456_789_000L);
        CompoundTag nested = new CompoundTag();
        ListTag list = new ListTag();
        list.add(StringTag.valueOf("a"));
        list.add(StringTag.valueOf("b"));
        nested.put("list", list);
        nested.put("ints", new IntArrayTag(new int[]{1, 2, 3}));
        saved.put("components", nested);
        byte[] before = bytes(saved);

        KindKey<?> loaded = receiverCodec.parse(NbtOps.INSTANCE, saved).getOrThrow();
        assertTrue(loaded.isUnknown());
        UnknownResource unknown = assertInstanceOf(UnknownResource.class, loaded.key());
        assertEquals(Identifier.fromNamespaceAndPath("othermod", "chemical"), unknown.kind());
        assertEquals(unknown.kind(), loaded.kindId());
        assertEquals(Optional.empty(), loaded.as(text));

        Tag again = receiverCodec.encodeStart(NbtOps.INSTANCE, loaded).getOrThrow();
        assertArrayEquals(before, bytes(assertInstanceOf(CompoundTag.class, again)));
        saved.putString("id", "changed");
        assertEquals("othermod:hydrogen", unknown.raw() instanceof CompoundTag raw ? raw.getStringOr("id", "") : "",
                "the unknown resource shares the loaded tag");
    }

    @Test
    void unitKindsSaveOnlyTheirKind() {
        ResourceKind<EnergyKey> energy = sender.register(Identifier.fromNamespaceAndPath("test", "energy"),
                EnergyKey.CODEC, EnergyKey.STREAM_CODEC, key -> key, AmountUnit.MU);
        Tag saved = senderCodec.encodeStart(NbtOps.INSTANCE, new KindKey<>(energy, EnergyKey.INSTANCE)).getOrThrow();
        CompoundTag expected = new CompoundTag();
        expected.putString("kind", "test:energy");
        assertEquals(expected, saved);
        assertEquals(new KindKey<>(energy, EnergyKey.INSTANCE), senderCodec.parse(NbtOps.INSTANCE, saved).getOrThrow());
    }

    @Test
    void keyWithoutKindFails() {
        CompoundTag saved = new CompoundTag();
        saved.putString("text", "Hello");
        DataResult<KindKey<?>> result = senderCodec.parse(NbtOps.INSTANCE, saved);
        assertTrue(result.isError());
    }

    @Test
    void receiverSkipsKindsItLacks() {
        RegistryFriendlyByteBuf buffer = buffer();
        StreamCodec<RegistryFriendlyByteBuf, KindKey<?>> out = KindKey.streamCodec(sender::byId);
        out.encode(buffer, new KindKey<>(text, "before"));
        out.encode(buffer, new KindKey<>(gone, 300_000));
        out.encode(buffer,
                UnknownResource.key(Identifier.fromNamespaceAndPath("othermod", "chemical"), new CompoundTag()));
        out.encode(buffer, new KindKey<>(number, 7));

        StreamCodec<RegistryFriendlyByteBuf, KindKey<?>> in = KindKey.streamCodec(receiver::byId);
        assertEquals(new KindKey<>(receiverText, "before"), in.decode(buffer));
        KindKey<?> skipped = in.decode(buffer);
        assertTrue(skipped.isUnknown());
        assertEquals(GONE, skipped.kindId());
        KindKey<?> unknown = in.decode(buffer);
        assertTrue(unknown.isUnknown());
        assertEquals(Identifier.fromNamespaceAndPath("othermod", "chemical"), unknown.kindId());
        assertEquals(new KindKey<>(receiverNumber, 7), in.decode(buffer));
        assertEquals(0, buffer.readableBytes());
    }

    @Test
    void payloadOfTheWrongLengthFailsDecoding() {
        KindTable shortReader = new KindTable();
        shortReader.register(NUMBER, Codec.INT, StreamCodec.of((buffer, value) -> {
        }, buffer -> 0), key -> key, AmountUnit.ITEMS);
        RegistryFriendlyByteBuf buffer = buffer();
        KindKey.streamCodec(sender::byId).encode(buffer, new KindKey<>(number, 7));
        assertThrows(DecoderException.class, () -> KindKey.streamCodec(shortReader::byId).decode(buffer));
    }

    @Test
    void drawBaseKeepsTheKeyWhenNothingChanges() {
        KindKey<String> lower = new KindKey<>(text, "plain");
        assertSame(lower, lower.drawBase());
        assertEquals(new KindKey<>(text, "mixed"), new KindKey<>(text, "MiXed").drawBase());
        assertEquals(Optional.of("plain"), lower.as(text));
        assertEquals(Optional.empty(), lower.as(number));
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    private static byte[] bytes(CompoundTag tag) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.write(tag, new DataOutputStream(out));
        return out.toByteArray();
    }
}
