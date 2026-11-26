package dev.silencebound.maxtech.storage.crafting.pattern;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternProvider;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntLists;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The patterns one storage network knows (M7a network seam): every registered provider with the patterns it had when it
 * last reported, and the craftable-key index, main output key to the patterns making it, which terminals and the
 * planner read. Generator providers ({@link GeneratorSource}, M7C item 11) have no patterns but are listed by their
 * output, which counts as craftable. Like the network it belongs to it is rebuilt with the network and never saved.
 * Server thread only; the planner gets an immutable {@link #snapshot()}.
 *
 * <p>
 * A network that sees another through a subnet view also runs that network's patterns (M7 C30): {@link #snapshot()},
 * {@link #isCraftable}, {@link #craftableKeys()}, {@link #allPatternsFor} and {@link #generation()} include the
 * registries {@link #link linked} to this one, one level deep (their own patterns, not what they link), while
 * {@link #patternsFor} and {@link #providerCount()} stay this network's own.
 */
public final class NetworkPatterns {
    /** One pattern a provider runs, with its priority ({@link PatternPriorities}; 0 when the provider has none). */
    public record Entry(PatternProvider provider, CraftingPattern pattern, int priority) {
    }

    private final Reference2ObjectOpenHashMap<PatternProvider, List<Entry>> providers = new Reference2ObjectOpenHashMap<>();
    private final Object2ObjectOpenHashMap<StackKey, ObjectArrayList<Entry>> byOutput = new Object2ObjectOpenHashMap<>();
    /** Generator providers (M7C item 11) by the output they make; a registered provider is in at most one list. */
    private final Object2ObjectOpenHashMap<StackKey, ObjectArrayList<GeneratorSource>> generators = new Object2ObjectOpenHashMap<>();
    /** The output each registered generator was listed under, to drop it again. */
    private final Reference2ObjectOpenHashMap<PatternProvider, StackKey> generatorKeys = new Reference2ObjectOpenHashMap<>();
    private long generation;
    private Snapshot snapshot = new Snapshot(0, List.of(), IntList.of());
    private Supplier<List<NetworkPatterns>> links = List::of;
    /** The linked registries and their generations as of {@link #snapshot}. */
    private List<NetworkPatterns> snapshotLinks = List.of();
    private long[] snapshotLinkGenerations = new long[0];

    /** Adds {@code provider} with its current patterns, or re-reads them if it is registered already. */
    public void register(PatternProvider provider) {
        unregister(provider);
        List<CraftingPattern> patterns = List.copyOf(provider.patterns());
        List<Entry> entries = new ObjectArrayList<>(patterns.size());
        for (int i = 0; i < patterns.size(); i++) {
            CraftingPattern pattern = patterns.get(i);
            Entry entry = new Entry(provider, pattern,
                    provider instanceof PatternPriorities priorities ? priorities.priority(i) : 0);
            entries.add(entry);
            byOutput.computeIfAbsent(pattern.mainOutput().key(), key -> new ObjectArrayList<>()).add(entry);
        }
        providers.put(provider, List.copyOf(entries));
        if (provider instanceof GeneratorSource generator && generator.output().isPresent()) {
            StackKey key = generator.output().get();
            generators.computeIfAbsent(key, k -> new ObjectArrayList<>()).add(generator);
            generatorKeys.put(provider, key);
        }
        generation++;
    }

    /** Drops {@code provider} and its patterns; nothing happens for one never registered. */
    public void unregister(PatternProvider provider) {
        List<Entry> patterns = providers.remove(provider);
        if (patterns == null) {
            return;
        }
        StackKey generated = generatorKeys.remove(provider);
        if (generated != null) {
            ObjectArrayList<GeneratorSource> list = generators.get(generated);
            if (list != null) {
                list.removeIf(source -> source.equals(provider));
                if (list.isEmpty()) {
                    generators.remove(generated);
                }
            }
        }
        for (int i = 0; i < patterns.size(); i++) {
            StackKey key = patterns.get(i).pattern().mainOutput().key();
            ObjectArrayList<Entry> entries = byOutput.get(key);
            if (entries != null) {
                entries.removeIf(entry -> entry.provider.equals(provider));
                if (entries.isEmpty()) {
                    byOutput.remove(key);
                }
            }
        }
        generation++;
    }

    /**
     * Where the registries of linked networks come from (M7 C30): the owning network's subnet views. Called once by the
     * network; the supplier is asked on every read that includes links, so it must be cheap when there are none.
     */
    public void link(Supplier<List<NetworkPatterns>> source) {
        links = source;
    }

    /** The set of linked registries may have changed (a view joined or left): bumps the generation. */
    public void linksChanged() {
        generation++;
    }

    /** Whether some pattern on the network or a linked one makes {@code key} as its main output. O(1) without links. */
    public boolean isCraftable(StackKey key) {
        if (byOutput.containsKey(key) || generators.containsKey(key)) {
            return true;
        }
        List<NetworkPatterns> linked = links.get();
        for (int i = 0; i < linked.size(); i++) {
            if (linked.get(i).byOutput.containsKey(key) || linked.get(i).generators.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    /** The generator providers making {@code key} on this network, empty when none; read-only. Server thread. */
    public List<GeneratorSource> generatorsFor(StackKey key) {
        ObjectArrayList<GeneratorSource> list = generators.get(key);
        return list == null ? List.of() : Collections.unmodifiableList(list);
    }

    /**
     * Every key a generator provider here or on a linked network makes (M7C item 11), with the highest priority among
     * the generators making it, as a copy the planner may read on any thread. Priorities are read live, so a change in
     * a generator's screen counts from the next plan. Allocates; called once per plan.
     */
    public Object2IntMap<StackKey> generatorPriorities() {
        List<NetworkPatterns> linked = links.get();
        if (generators.isEmpty() && linked.isEmpty()) {
            return Object2IntMaps.emptyMap();
        }
        Object2IntOpenHashMap<StackKey> all = new Object2IntOpenHashMap<>();
        addGeneratorPriorities(generators, all);
        for (int i = 0; i < linked.size(); i++) {
            addGeneratorPriorities(linked.get(i).generators, all);
        }
        return Object2IntMaps.unmodifiable(all);
    }

    private static void addGeneratorPriorities(
            Object2ObjectOpenHashMap<StackKey, ObjectArrayList<GeneratorSource>> generators,
            Object2IntOpenHashMap<StackKey> into) {
        for (var entry : generators.object2ObjectEntrySet()) {
            ObjectArrayList<GeneratorSource> list = entry.getValue();
            for (int i = 0; i < list.size(); i++) {
                int priority = list.get(i).priority();
                if (!into.containsKey(entry.getKey()) || into.getInt(entry.getKey()) < priority) {
                    into.put(entry.getKey(), priority);
                }
            }
        }
    }

    /**
     * The patterns making {@code key} here and on linked networks, own ones first; read-only. Allocates only when a
     * linked network has some. Server thread.
     */
    public List<Entry> allPatternsFor(StackKey key) {
        List<Entry> own = patternsFor(key);
        List<NetworkPatterns> linked = links.get();
        if (linked.isEmpty()) {
            return own;
        }
        ObjectArrayList<Entry> all = null;
        for (int i = 0; i < linked.size(); i++) {
            List<Entry> theirs = linked.get(i).patternsFor(key);
            if (!theirs.isEmpty()) {
                if (all == null) {
                    all = new ObjectArrayList<>(own);
                }
                all.addAll(theirs);
            }
        }
        return all == null ? own : Collections.unmodifiableList(all);
    }

    /** The patterns making {@code key}, empty when none do; read-only, and changes with the next registration. */
    public List<Entry> patternsFor(StackKey key) {
        ObjectArrayList<Entry> entries = byOutput.get(key);
        return entries == null ? List.of() : Collections.unmodifiableList(entries);
    }

    /** Every key some pattern here or on a linked network makes; a read-only view, a copy when there are links. */
    public Set<StackKey> craftableKeys() {
        List<NetworkPatterns> linked = links.get();
        if (linked.isEmpty() && generators.isEmpty()) {
            return Collections.unmodifiableSet(byOutput.keySet());
        }
        ObjectOpenHashSet<StackKey> all = new ObjectOpenHashSet<>(byOutput.keySet());
        all.addAll(generators.keySet());
        for (int i = 0; i < linked.size(); i++) {
            all.addAll(linked.get(i).byOutput.keySet());
            all.addAll(linked.get(i).generators.keySet());
        }
        return Collections.unmodifiableSet(all);
    }

    /** Registered providers. */
    public int providerCount() {
        return providers.size();
    }

    /**
     * This network's own registered providers (not linked ones), including those without patterns; a new list. For the
     * crafter manager (B09 item 23), never per tick. Server thread.
     */
    public List<PatternProvider> providers() {
        return new ObjectArrayList<>(providers.keySet());
    }

    /**
     * Every pattern of this network's own providers (not linked ones), grouped by provider, as last registered; a new
     * list. For the pattern overview (M7C item 1), never per tick. Server thread.
     */
    public List<Entry> entries() {
        ObjectArrayList<Entry> all = new ObjectArrayList<>();
        for (List<Entry> entries : providers.values()) {
            all.addAll(entries);
        }
        return all;
    }

    /**
     * Changes with every registration change here or on a linked network and whenever the links change; terminals
     * compare it to refresh their craftable marks. Not monotonic once links are involved: compare for equality only.
     */
    public long generation() {
        long total = generation;
        List<NetworkPatterns> linked = links.get();
        for (int i = 0; i < linked.size(); i++) {
            total += linked.get(i).generation;
        }
        return total;
    }

    /**
     * Every pattern as of now, linked networks' included, immutable and safe on any thread; the same object while
     * nothing changed.
     */
    public Snapshot snapshot() {
        List<NetworkPatterns> linked = links.get();
        if (snapshot.generation() != generation || !sameLinks(linked)) {
            ObjectArrayList<CraftingPattern> all = new ObjectArrayList<>();
            IntArrayList priorities = new IntArrayList();
            add(providers, all, priorities);
            long[] generations = new long[linked.size()];
            for (int i = 0; i < linked.size(); i++) {
                NetworkPatterns other = linked.get(i);
                generations[i] = other.generation;
                add(other.providers, all, priorities);
            }
            snapshotLinks = List.copyOf(linked);
            snapshotLinkGenerations = generations;
            snapshot = new Snapshot(generation, List.copyOf(all), IntLists.unmodifiable(priorities));
        }
        return snapshot;
    }

    private static void add(Reference2ObjectOpenHashMap<PatternProvider, List<Entry>> from,
            ObjectArrayList<CraftingPattern> patterns, IntArrayList priorities) {
        for (List<Entry> entries : from.values()) {
            for (int i = 0; i < entries.size(); i++) {
                patterns.add(entries.get(i).pattern());
                priorities.add(entries.get(i).priority());
            }
        }
    }

    // The registries are compared by identity: each belongs to one network.
    @SuppressWarnings("ReferenceEquality")
    private boolean sameLinks(List<NetworkPatterns> linked) {
        if (linked.size() != snapshotLinks.size()) {
            return false;
        }
        for (int i = 0; i < linked.size(); i++) {
            if (linked.get(i) != snapshotLinks.get(i) || linked.get(i).generation != snapshotLinkGenerations[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * The network's patterns as of {@code generation}, duplicates from several providers included, with each pattern's
     * priority at the same index.
     */
    public record Snapshot(long generation, List<CraftingPattern> patterns, IntList priorities) {
    }
}
