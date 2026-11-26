package dev.silencebound.maxtech.lib.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import org.junit.jupiter.api.Test;

class ServerSyncedListTest {
    private final List<SyncedListUpdate<TestEntries>> sent = new ArrayList<>();
    private int definitionsAsked;
    private final ServerSyncedList<TestEntries> server = new ServerSyncedList<>(TestEntries.TYPE, 7, id -> {
        definitionsAsked++;
        return id < 0 ? null : new TestEntries("entry " + id, "test", List.of());
    }, sent::add);
    private final ClientSyncedList<TestEntries> client = new ClientSyncedList<>();

    @Test
    void mergesChangesPerIdUntilTheInterval() {
        server.open(new Int2LongOpenHashMap(), 0);
        sent.clear();
        server.changed(1, 5);
        server.changed(1, 9);
        server.changed(2, 3);
        assertFalse(server.tick(3, 5), "nothing before the interval");
        assertEquals(2, server.pendingCount(), "two ids pending, the first one merged");
        assertTrue(server.tick(5, 5));
        assertEquals(1, sent.size());
        applyAll();
        assertEquals(9, client.count(1), "the last count of id 1 wins");
        assertEquals(3, client.count(2));
        assertFalse(server.tick(20, 5), "nothing pending, nothing sent");
    }

    @Test
    void sendsEachDefinitionOnce() {
        server.open(new Int2LongOpenHashMap(), 0);
        server.changed(4, 1);
        server.tick(10, 1);
        server.changed(4, 2);
        server.tick(11, 1);
        assertEquals(1, definitionsAsked, "a known id is not described again");
        assertEquals(0, sent.get(2).definitionCount());
        applyAll();
        assertEquals("entry 4", Objects.requireNonNull(client.entry(4)).name());
        assertEquals(2, client.count(4));
    }

    @Test
    void removalForgetsTheDefinitionAndSkipsUnknownIds() {
        server.open(map(4, 1), 0);
        server.changed(4, 0);
        server.changed(8, 0);
        server.tick(10, 1);
        SyncedListUpdate<TestEntries> removal = sent.get(sent.size() - 1);
        assertEquals(1, removal.countCount(), "only the id the client knew is removed");
        applyAll();
        assertNull(client.entry(4));
        assertEquals(0, client.size());
        server.changed(4, 6);
        server.tick(20, 1);
        assertEquals(1, sent.get(sent.size() - 1).definitionCount(), "a returning id is described again");
    }

    @Test
    void goneDefinitionIsNotSent() {
        server.open(new Int2LongOpenHashMap(), 0);
        server.changed(-3, 5);
        server.tick(10, 1);
        assertEquals(1, server.sentBatches(), "only the dump: nothing to send for an id without definition");
    }

    @Test
    void splitsLargeDumpsAndResetsOnlyOnce() {
        Int2LongOpenHashMap contents = new Int2LongOpenHashMap();
        for (int id = 0; id < 5000; id++) {
            contents.put(id, id + 1L);
        }
        client.apply(TestEntries.TYPE.update(7, false, new int[]{99999},
                List.of(new TestEntries("stale", "x", List.of())), new int[]{99999}, new long[]{1}));
        server.open(contents, 0);
        assertEquals(3, sent.size(), "2048 + 2048 + 904");
        assertTrue(sent.get(0).reset());
        assertFalse(sent.get(1).reset());
        assertFalse(sent.get(2).reset());
        applyAll();
        assertEquals(5000, client.size(), "the stale entry is gone and every entry arrived");
        assertEquals(5000, client.count(4999));
    }

    @Test
    void capsBatchesByEncodedSize() {
        List<SyncedListUpdate<TestEntries>> big = new ArrayList<>();
        // Each entry's tags encode to about 100 KiB, so five fit under the 512 KiB budget and the sixth starts a new
        // batch; entry 0 is six times as large and goes alone.
        String part = "x".repeat(25 * 1024);
        List<String> tags = List.of(part, part, part, part);
        List<String> huge = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            huge.addAll(tags);
        }
        ServerSyncedList<TestEntries> sized = new ServerSyncedList<>(TestEntries.TYPE, 7,
                id -> new TestEntries("entry " + id, "test", id == 0 ? huge : tags), big::add,
                TestEntries.TYPE.sizer(RegistryAccess.EMPTY));
        Int2LongOpenHashMap contents = new Int2LongOpenHashMap();
        for (int id = 0; id < 21; id++) {
            contents.put(id, 1);
        }
        sized.open(contents, 0);
        int entries = 0;
        boolean aloneOversized = false;
        for (SyncedListUpdate<TestEntries> update : big) {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            TestEntries.TYPE.codec().encode(buf, update);
            if (update.countCount() == 1 && update.definition(0).tags().size() > tags.size()) {
                aloneOversized = true;
            } else {
                assertTrue(buf.writerIndex() <= SyncedListUpdate.MAX_BYTES,
                        "a batch of " + update.countCount() + " encodes to " + buf.writerIndex() + " bytes");
            }
            client.apply(TestEntries.TYPE.codec().decode(buf));
            entries += update.countCount();
        }
        assertTrue(aloneOversized, "an entry over the budget goes alone");
        assertEquals(21, entries);
        assertEquals(5, big.size(), "the oversized entry alone, then 20 entries five per batch");
        assertTrue(big.get(0).reset());
        assertEquals(21, client.size());
    }

    @Test
    void emptyDumpStillResets() {
        server.open(new Int2LongOpenHashMap(), 0);
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).reset());
    }

    @Test
    void codecRoundTripsAndRefusesOversizedBatches() {
        server.open(map(3, 12), 0);
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        TestEntries.TYPE.codec().encode(buf, sent.get(0));
        SyncedListUpdate<TestEntries> decoded = TestEntries.TYPE.codec().decode(buf);
        assertEquals(7, decoded.containerId());
        assertTrue(decoded.reset());
        assertEquals("entry 3", decoded.definition(0).name());
        assertEquals(12, decoded.count(0));

        RegistryFriendlyByteBuf bad = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        VarInt.write(bad, 7);
        bad.writeBoolean(false);
        VarInt.write(bad, SyncedListUpdate.MAX_ENTRIES + 1);
        assertThrows(DecoderException.class, () -> TestEntries.TYPE.codec().decode(bad));
    }

    @Test
    void intervalFollowsTheRate() {
        assertEquals(5, ServerSyncedList.intervalTicks(4));
        assertEquals(1, ServerSyncedList.intervalTicks(20));
        assertEquals(20, ServerSyncedList.intervalTicks(1));
    }

    private void applyAll() {
        for (SyncedListUpdate<TestEntries> update : sent) {
            client.apply(update);
        }
    }

    private static Int2LongOpenHashMap map(int id, long count) {
        Int2LongOpenHashMap map = new Int2LongOpenHashMap();
        map.put(id, count);
        return map;
    }
}
