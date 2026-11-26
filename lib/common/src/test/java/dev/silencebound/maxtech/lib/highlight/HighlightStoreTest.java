package dev.silencebound.maxtech.lib.highlight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class HighlightStoreTest {
    private static final Identifier A = Identifier.fromNamespaceAndPath("test", "a");
    private static final Identifier B = Identifier.fromNamespaceAndPath("test", "b");

    @Test
    void requestsExpireAndReplaceTheirChannel() {
        HighlightStore store = new HighlightStore();
        store.show(HighlightRequest.builder(A).duration(3).block(BlockPos.ZERO, -1).build());
        store.show(HighlightRequest.builder(B).duration(10).block(BlockPos.ZERO, -1).block(BlockPos.ZERO, -1).build());
        store.show(HighlightRequest.builder(B).duration(10).block(BlockPos.ZERO, -1).build());
        assertEquals(2, store.active().size(), "the second request on B replaced the first");
        assertEquals(1, store.active().get(1).request().boxCount());
        store.tick();
        store.tick();
        assertEquals(2, store.active().size());
        store.tick();
        assertEquals(1, store.active().size(), "A ends after three ticks");
        store.show(HighlightRequest.clear(B));
        assertTrue(store.isEmpty(), "a clearing request empties its channel");
    }

    @Test
    void dropsTheOldestChannelPastTheCap() {
        HighlightStore store = new HighlightStore();
        for (int i = 0; i <= HighlightStore.MAX_CHANNELS; i++) {
            store.show(HighlightRequest.builder(Identifier.fromNamespaceAndPath("test", "c" + i))
                    .block(BlockPos.ZERO, -1).build());
        }
        assertEquals(HighlightStore.MAX_CHANNELS, store.active().size());
        assertEquals("c1", store.active().get(0).request().channel().getPath());
    }

    @Test
    void codecRoundTripsAndRefusesTooManyShapes() {
        HighlightRequest request = HighlightRequest.builder(A).throughWalls(true).duration(40)
                .block(new BlockPos(1, 2, 3), 0xFF00FF00).line(new Vec3(0, 0, 0), new Vec3(5, 6, 7), 0xFFFF0000)
                .build();
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        HighlightRequest.CODEC.encode(buf, request);
        HighlightRequest decoded = HighlightRequest.CODEC.decode(buf);
        assertEquals(A, decoded.channel());
        assertTrue(decoded.throughWalls());
        assertEquals(40, decoded.durationTicks());
        assertEquals(0.995, decoded.box(0, 0), 1e-9);
        assertEquals(4.005, decoded.box(0, 5), 1e-9);
        assertEquals(7, decoded.line(0, 5), 1e-9);
        assertEquals(0xFFFF0000, decoded.lineColour(0));

        FriendlyByteBuf bad = new FriendlyByteBuf(Unpooled.buffer());
        bad.writeIdentifier(A);
        VarInt.write(bad, 20);
        bad.writeBoolean(false);
        VarInt.write(bad, HighlightRequest.MAX_SHAPES + 1);
        assertThrows(DecoderException.class, () -> HighlightRequest.CODEC.decode(bad));
    }
}
