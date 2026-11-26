package dev.silencebound.maxtech.storage.crafting.job;

import dev.silencebound.maxtech.storage.save.StorageSaveFormats;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * The deliveries ({@link CraftDelivery}) of a level's unfinished jobs by job id, saved with the level beside
 * {@link CraftingJobs} so a delivery survives a restart. An entry leaves when its job completes (after the one try) or
 * ends. Server thread.
 */
public final class CraftDeliveries extends SavedData {
    private record Entry(UUID job, CraftDelivery delivery) {
        static final Codec<Entry> CODEC = RecordCodecBuilder
                .create(i -> i
                        .group(UUIDUtil.CODEC.fieldOf("job").forGetter(Entry::job),
                                CraftDelivery.CODEC.fieldOf("delivery").forGetter(Entry::delivery))
                        .apply(i, Entry::new));
    }

    public static final Codec<CraftDeliveries> CODEC = Entry.CODEC.listOf().xmap(CraftDeliveries::new,
            CraftDeliveries::entries);
    public static final SavedDataType<CraftDeliveries> TYPE = StorageSaveFormats
            .savedData(MaxtechStorage.id("crafting_deliveries"), CraftDeliveries::new, CODEC);

    private final LinkedHashMap<UUID, CraftDelivery> deliveries = new LinkedHashMap<>();

    public CraftDeliveries() {
    }

    private CraftDeliveries(List<Entry> saved) {
        for (Entry entry : saved) {
            deliveries.put(entry.job(), entry.delivery());
        }
    }

    public static CraftDeliveries get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    private List<Entry> entries() {
        List<Entry> out = new ArrayList<>(deliveries.size());
        for (Map.Entry<UUID, CraftDelivery> entry : deliveries.entrySet()) {
            out.add(new Entry(entry.getKey(), entry.getValue()));
        }
        return out;
    }

    /** Sends job {@code job}'s finished craft to {@code delivery} besides storage. */
    public void put(UUID job, CraftDelivery delivery) {
        deliveries.put(job, delivery);
        setDirty();
    }

    /** Job {@code job}'s delivery; null when it has none (it goes to storage). */
    public @Nullable CraftDelivery get(UUID job) {
        return deliveries.get(job);
    }

    /** Forgets job {@code job}'s delivery. */
    public void remove(UUID job) {
        if (deliveries.remove(job) != null) {
            setDirty();
        }
    }

    /** How many deliveries wait; for tests. */
    public int size() {
        return deliveries.size();
    }
}
