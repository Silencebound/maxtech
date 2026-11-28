package dev.silencebound.maxtech.lib.api.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import dev.silencebound.maxtech.lib.api.resource.TestKinds;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

class PortSettingsTest {
    private static final Codec<PortSettings> CODEC = PortSettings.codec(TestKinds::byId);

    private static PortSettings sample() {
        return PortSettings.EMPTY.withMode(TestKinds.TEXT, Direction.UP, PortFaceMode.PUSH)
                .withFilter(TestKinds.TEXT,
                        Optional.of(PortFilter.of(TestKinds.TEXT, PortFilter.Mode.DENY, List.of("apple"), List.of())))
                .withMode(TestKinds.NUMBER, Direction.WEST, PortFaceMode.PULL).withRate(TestKinds.NUMBER, 250);
    }

    @Test
    void settingsArePerKind() {
        PortSettings settings = sample();
        assertEquals(PortFaceMode.PUSH, settings.mode(TestKinds.TEXT, Direction.UP));
        assertEquals(PortFaceMode.OFF, settings.mode(TestKinds.NUMBER, Direction.UP));
        assertEquals(PortFaceMode.PULL, settings.mode(TestKinds.NUMBER, Direction.WEST));
        assertEquals(0, settings.rate(TestKinds.TEXT));
        assertEquals(250, settings.rate(TestKinds.NUMBER));
        assertFalse(settings.allows(TestKinds.TEXT, "apple"));
        assertTrue(settings.allows(TestKinds.TEXT, "pear"));
        assertTrue(settings.allows(TestKinds.NUMBER, 1));
        assertEquals(List.<ResourceKind<?>>of(TestKinds.TEXT, TestKinds.NUMBER), settings.kinds());
    }

    @Test
    void kindsBackAtTheirDefaultsAreDropped() {
        PortSettings settings = sample().withFaces(TestKinds.NUMBER, PortFaces.NONE).withRate(TestKinds.NUMBER, 0);
        assertEquals(List.<ResourceKind<?>>of(TestKinds.TEXT), settings.kinds());
        assertEquals(PortSettings.EMPTY,
                settings.withFaces(TestKinds.TEXT, PortFaces.NONE).withFilter(TestKinds.TEXT, Optional.empty()));
    }

    @Test
    void codecRoundTrips() {
        PortSettings settings = sample();
        Tag saved = CODEC.encodeStart(NbtOps.INSTANCE, settings).getOrThrow();
        assertEquals(settings, CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow());
        assertSame(PortSettings.EMPTY, CODEC.parse(NbtOps.INSTANCE, new CompoundTag()).getOrThrow());
    }

    @Test
    void unknownKindsAreKeptAndSavedBack() {
        CompoundTag gone = new CompoundTag();
        gone.putString("kind", "test:gone");
        gone.putInt("faces", 5);
        gone.putString("anything", "kept as is");
        CompoundTag saved = (CompoundTag) CODEC.encodeStart(NbtOps.INSTANCE, sample()).getOrThrow();
        saved.getListOrEmpty("kinds").add(gone);

        PortSettings loaded = CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        assertEquals(List.<Tag>of(gone), loaded.unknownKinds());
        assertEquals(saved, CODEC.encodeStart(NbtOps.INSTANCE, loaded).getOrThrow());
    }

    @Test
    void entriesThatDoNotLoadAreKeptUntilTheirKindIsSetAgain() {
        CompoundTag broken = new CompoundTag();
        broken.putString("kind", "test:number");
        broken.putString("faces", "not a number");
        CompoundTag badBits = new CompoundTag();
        badBits.putString("kind", "test:text");
        badBits.putInt("faces", 3 | (1 << 2) | (1 << 30)); // DOWN holds no mode, UP pushes
        ListTag kinds = new ListTag();
        kinds.add(broken);
        kinds.add(badBits);
        CompoundTag saved = new CompoundTag();
        saved.put("kinds", kinds);

        PortSettings loaded = CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        assertEquals(List.<Tag>of(broken), loaded.unknownKinds());
        assertEquals(1 << 2, loaded.faces(TestKinds.TEXT));
        assertEquals(List.of(), loaded.withRate(TestKinds.NUMBER, 10).unknownKinds());
    }
}
