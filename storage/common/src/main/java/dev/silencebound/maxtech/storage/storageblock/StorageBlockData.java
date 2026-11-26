package dev.silencebound.maxtech.storage.storageblock;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.cell.CellData;
import dev.silencebound.maxtech.storage.priority.PriorityControl;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A storage block item's data component ({@code maxtech_storage:storage_block}): the id of its cell in
 * {@code CellStorage}, its priority and a cache of its stats for the tooltip. The contents live in the cell save data
 * keyed by the id, never on the item, so the item stays the same size whatever the block holds (PRD 7.2, Persistence).
 * An empty block with priority 0 drops without the component, so it stacks with new ones.
 *
 * @param id
 *            the cell's id; {@link CellData#NO_ID} for a block that held nothing
 * @param priority
 *            the block's priority
 * @param usedBytes
 *            cached: bytes used when the block was broken
 * @param totalBytes
 *            cached: its capacity then
 * @param kinds
 *            cached: kinds held then, kept ones included
 * @param kept
 *            cached: kinds kept then because their keys no longer load (a removed mod)
 */
public record StorageBlockData(long id, int priority, long usedBytes, long totalBytes, int kinds, int kept) {
    public static final Codec<StorageBlockData> CODEC = RecordCodecBuilder.create(i -> i
            .group(Codec.LONG.optionalFieldOf("id", CellData.NO_ID).forGetter(StorageBlockData::id),
                    Codec.intRange(PriorityControl.MIN, PriorityControl.MAX).optionalFieldOf("priority", 0)
                            .forGetter(StorageBlockData::priority),
                    Codec.LONG.optionalFieldOf("used_bytes", 0L).forGetter(StorageBlockData::usedBytes),
                    Codec.LONG.optionalFieldOf("total_bytes", 0L).forGetter(StorageBlockData::totalBytes),
                    Codec.INT.optionalFieldOf("kinds", 0).forGetter(StorageBlockData::kinds),
                    Codec.INT.optionalFieldOf("kept", 0).forGetter(StorageBlockData::kept))
            .apply(i, StorageBlockData::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, StorageBlockData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, StorageBlockData::id, ByteBufCodecs.VAR_INT, StorageBlockData::priority,
            ByteBufCodecs.VAR_LONG, StorageBlockData::usedBytes, ByteBufCodecs.VAR_LONG, StorageBlockData::totalBytes,
            ByteBufCodecs.VAR_INT, StorageBlockData::kinds, ByteBufCodecs.VAR_INT, StorageBlockData::kept,
            StorageBlockData::new);
}
