package dev.silencebound.maxtech.storage.sensor.reader;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The pack readers the server loaded ({@link PackReaderListener}), ordered by file id. Sensors ask which readers apply
 * to their target only when the target's block or {@link #generation()} changes, so a reload or a tag change reaches
 * every sensor on its next read. Server thread only.
 */
public final class PackReaders {
    /** One loaded reader file. */
    public record Entry(Identifier id, PackReader reader) {
    }

    /** One value a block gets from its readers after overrides: which reader gives it, and how. */
    public record Merged(String name, Identifier reader, ReaderValue value) {
    }

    // By the id as text (namespace, then path): Identifier#compareTo compares the path first.
    private static final Comparator<Entry> BY_ID = Comparator.comparing(entry -> entry.id().toString());

    // Safe across threads: in single player the tag update that bumps the generation may come from the client thread.
    private static volatile List<Entry> readers = List.of();
    private static final AtomicInteger GENERATION = new AtomicInteger();

    private PackReaders() {
    }

    /** Takes the readers of a (re)load, replacing all earlier ones. */
    public static void apply(Map<Identifier, PackReader> loaded) {
        List<Entry> sorted = new ArrayList<>(loaded.size());
        for (Map.Entry<Identifier, PackReader> entry : loaded.entrySet()) {
            sorted.add(new Entry(entry.getKey(), entry.getValue()));
        }
        sorted.sort(BY_ID);
        readers = List.copyOf(sorted);
        GENERATION.incrementAndGet();
    }

    /** Tags were bound anew: which blocks a {@code #tag} names may have changed. */
    public static void tagsChanged() {
        GENERATION.incrementAndGet();
    }

    /** Bumped by every reload and tag change. */
    public static int generation() {
        return GENERATION.get();
    }

    /** Every loaded reader, by file id. */
    public static List<Entry> all() {
        return readers;
    }

    /**
     * The values {@code state} gets from the readers that name its block, after overrides ({@link #merge}). Allocates;
     * sensors call it only when their target's block or the generation changed.
     */
    public static List<Merged> valuesFor(BlockState state) {
        List<Entry> matching = new ArrayList<>();
        for (int i = 0; i < readers.size(); i++) {
            Entry entry = readers.get(i);
            if (entry.reader().blocks().matches(state)) {
                matching.add(entry);
            }
        }
        return merge(matching);
    }

    /**
     * The values of {@code entries} (in file id order) as one list: each reader's values in its file order, readers in
     * file id order; a value name that a later reader names again is taken from the later reader and listed with it.
     * Pure.
     */
    public static List<Merged> merge(List<Entry> entries) {
        Map<String, Merged> byName = new LinkedHashMap<>();
        for (Entry entry : entries) {
            for (Map.Entry<String, ReaderValue> value : entry.reader().values().entrySet()) {
                byName.remove(value.getKey());
                byName.put(value.getKey(), new Merged(value.getKey(), entry.id(), value.getValue()));
            }
        }
        List<Merged> merged = new ArrayList<>(byName.values());
        // Grouped by reader for the screen, keeping file id order between readers.
        List<Merged> grouped = new ArrayList<>(merged.size());
        for (Entry entry : entries) {
            for (Merged value : merged) {
                if (value.reader().equals(entry.id())) {
                    grouped.add(value);
                }
            }
        }
        return grouped;
    }
}
