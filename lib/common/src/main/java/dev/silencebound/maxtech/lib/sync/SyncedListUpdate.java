package dev.silencebound.maxtech.lib.sync;

import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.VarLong;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * One server-to-client batch of a synced list for the menu {@code containerId}. {@code reset} starts a full dump: the
 * client drops what it had first. {@code definitionIds}/{@code definitions} define entries the client has not seen
 * (each comes with a count in the same batch); {@code ids}/{@code counts} set counts, where 0 removes the entry and its
 * definition. A final class rather than a record because it holds arrays.
 */
public final class SyncedListUpdate<E> implements CustomPacketPayload {
    /** Most definitions or counts one batch may carry; the server splits longer batches, the client refuses them. */
    public static final int MAX_ENTRIES = 2048;
    /**
     * Most encoded bytes the server puts in one batch (512 KiB), counted with each definition's real encoded size. Half
     * of vanilla's 1 MiB cap on a custom payload and a quarter of the 2 MiB frame limit, so a dump of component-heavy
     * entries (written books, filled shulker boxes) never builds a packet the connection refuses. One entry larger than
     * this still goes, alone in its batch.
     */
    public static final int MAX_BYTES = 512 * 1024;
    /** Upper bound of a batch's fixed part: container id, reset flag and the two list sizes. */
    static final int HEADER_BYTES = 5 + 1 + 5 + 5;
    /** Upper bound of one count: a var-int id and a var-long count. */
    static final int COUNT_BYTES = 5 + 10;
    /** Upper bound of a definition's id before its encoded entry. */
    static final int DEFINITION_ID_BYTES = 5;

    private final Type<SyncedListUpdate<E>> type;
    private final int containerId;
    private final boolean reset;
    private final int[] definitionIds;
    private final List<E> definitions;
    private final int[] ids;
    private final long[] counts;

    SyncedListUpdate(Type<SyncedListUpdate<E>> type, int containerId, boolean reset, int[] definitionIds,
            List<E> definitions, int[] ids, long[] counts) {
        if (definitionIds.length != definitions.size() || ids.length != counts.length) {
            throw new IllegalArgumentException("Mismatched update arrays");
        }
        this.type = type;
        this.containerId = containerId;
        this.reset = reset;
        this.definitionIds = definitionIds;
        this.definitions = definitions;
        this.ids = ids;
        this.counts = counts;
    }

    static <E> StreamCodec<RegistryFriendlyByteBuf, SyncedListUpdate<E>> codec(Type<SyncedListUpdate<E>> type,
            StreamCodec<? super RegistryFriendlyByteBuf, E> entryCodec) {
        return StreamCodec.of((buf, update) -> update.write(buf, entryCodec), buf -> read(type, buf, entryCodec));
    }

    private void write(RegistryFriendlyByteBuf buf, StreamCodec<? super RegistryFriendlyByteBuf, E> entryCodec) {
        VarInt.write(buf, containerId);
        buf.writeBoolean(reset);
        VarInt.write(buf, definitionIds.length);
        for (int i = 0; i < definitionIds.length; i++) {
            VarInt.write(buf, definitionIds[i]);
            entryCodec.encode(buf, definitions.get(i));
        }
        VarInt.write(buf, ids.length);
        for (int i = 0; i < ids.length; i++) {
            VarInt.write(buf, ids[i]);
            VarLong.write(buf, counts[i]);
        }
    }

    private static <E> SyncedListUpdate<E> read(Type<SyncedListUpdate<E>> type, RegistryFriendlyByteBuf buf,
            StreamCodec<? super RegistryFriendlyByteBuf, E> entryCodec) {
        int containerId = VarInt.read(buf);
        boolean reset = buf.readBoolean();
        int definitionCount = checkedSize(VarInt.read(buf));
        int[] definitionIds = new int[definitionCount];
        List<E> definitions = new ArrayList<>(definitionCount);
        for (int i = 0; i < definitionCount; i++) {
            definitionIds[i] = VarInt.read(buf);
            definitions.add(entryCodec.decode(buf));
        }
        int countCount = checkedSize(VarInt.read(buf));
        int[] ids = new int[countCount];
        long[] counts = new long[countCount];
        for (int i = 0; i < countCount; i++) {
            ids[i] = VarInt.read(buf);
            counts[i] = VarLong.read(buf);
            if (counts[i] < 0) {
                throw new DecoderException("Negative count " + counts[i]);
            }
        }
        return new SyncedListUpdate<>(type, containerId, reset, definitionIds, definitions, ids, counts);
    }

    private static int checkedSize(int size) {
        if (size < 0 || size > MAX_ENTRIES) {
            throw new DecoderException("Synced list batch of " + size + " entries");
        }
        return size;
    }

    public int containerId() {
        return containerId;
    }

    public boolean reset() {
        return reset;
    }

    public int definitionCount() {
        return definitionIds.length;
    }

    public int definitionId(int index) {
        return definitionIds[index];
    }

    public E definition(int index) {
        return definitions.get(index);
    }

    public int countCount() {
        return ids.length;
    }

    public int id(int index) {
        return ids[index];
    }

    public long count(int index) {
        return counts[index];
    }

    @Override
    public Type<SyncedListUpdate<E>> type() {
        return type;
    }
}
