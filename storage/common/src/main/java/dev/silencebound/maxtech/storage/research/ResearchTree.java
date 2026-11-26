package dev.silencebound.maxtech.storage.research;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The loaded research tree, compiled from the {@link ResearchNode#REGISTRY} entries for lookups: nodes by id and by the
 * unlock ids they grant, each node's dependants, the branches in order, and the task counters with the tasks that read
 * each. The registry loads with the world and never changes while it runs, so one tree is kept per registry instance.
 * Immutable after construction; server thread (and the client for drawing).
 */
public final class ResearchTree {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Branches the draft tree uses, in the order the screen lists them; others follow by name. */
    private static final List<String> BRANCH_ORDER = List.of("processors", "memory", "cells", "network", "void",
            "crafting");
    /** One tree per registry instance: an integrated server and its client each have their own. */
    private static final Map<Registry<ResearchNode>, ResearchTree> CACHE = new WeakHashMap<>();
    private static final ResearchTree EMPTY = new ResearchTree(null, Set.of());
    /** The tree asked for last, read without locking: the server asks for the same one all the time. */
    private static volatile @Nullable ResearchTree lastTree;

    private final @Nullable Registry<ResearchNode> registry;

    private final List<Entry> entries;
    private final Map<Identifier, Entry> byId = new HashMap<>();
    private final Map<Identifier, List<Entry>> byUnlock = new HashMap<>();
    private final List<String> branches = new ArrayList<>();
    private final Object2IntOpenHashMap<String> counterIndex = new Object2IntOpenHashMap<>();
    private final List<Counter> counters = new ArrayList<>();

    /** One node with its place in the tree. */
    public static final class Entry {
        private final Identifier id;
        private final ResearchNode node;
        private final List<Entry> dependants = new ArrayList<>();
        /** The counter each option reads; -1 for options that are no task. */
        private final int[] optionCounters;

        Entry(Identifier id, ResearchNode node) {
            this.id = id;
            this.node = node;
            this.optionCounters = new int[node.anyOf().size()];
        }

        public Identifier id() {
            return id;
        }

        public ResearchNode node() {
            return node;
        }

        /**
         * Whether completing this node can grant {@code unlock} ({@link ResearchUnlocks#isUnlocked}): it is that node
         * or lists it among its unlocks.
         */
        public boolean grants(Identifier unlock) {
            return id.equals(unlock) || node.unlocks().contains(unlock);
        }

        /** Nodes that require this one. */
        public List<Entry> dependants() {
            return dependants;
        }

        /** The counter of option {@code option}, or -1 when it is no task. */
        public int counter(int option) {
            return optionCounters[option];
        }

        /** Complete from the start: a node without options. */
        public boolean startNode() {
            return node.anyOf().isEmpty();
        }
    }

    /**
     * A team counter and the task options reading it.
     *
     * @param name
     *            {@link ResearchOption.Task#counter()}, the name saved with teams
     * @param task
     *            the first task naming it, whose type and filter define what is counted
     */
    public record Counter(String name, ResearchOption.Task task, List<Entry> readers) {
    }

    private ResearchTree(@Nullable Registry<ResearchNode> registry,
            Set<Map.Entry<ResourceKey<ResearchNode>, ResearchNode>> nodes) {
        this.registry = registry;
        List<Entry> all = new ArrayList<>();
        for (Map.Entry<ResourceKey<ResearchNode>, ResearchNode> e : nodes) {
            all.add(new Entry(e.getKey().identifier(), e.getValue()));
        }
        all.sort(Comparator.comparingInt((Entry e) -> branchRank(e.node.branch())).thenComparing(e -> e.node.branch())
                .thenComparingInt(e -> e.node.order()).thenComparing(e -> e.id));
        this.entries = List.copyOf(all);
        counterIndex.defaultReturnValue(-1);
        for (Entry entry : entries) {
            byId.put(entry.id, entry);
            if (!branches.contains(entry.node.branch())) {
                branches.add(entry.node.branch());
            }
            for (Identifier unlock : entry.node.unlocks()) {
                byUnlock.computeIfAbsent(unlock, u -> new ArrayList<>()).add(entry);
            }
            List<ResearchOption> options = entry.node.anyOf();
            for (int i = 0; i < options.size(); i++) {
                entry.optionCounters[i] = options.get(i) instanceof ResearchOption.Task task
                        ? counter(task, entry)
                        : -1;
            }
        }
        for (Entry entry : entries) {
            for (Identifier required : entry.node.requires()) {
                Entry parent = byId.get(required);
                if (parent == null) {
                    LOGGER.warn("Research node {} requires unknown node {}; the requirement is ignored", entry.id,
                            required);
                } else {
                    parent.dependants.add(entry);
                }
            }
        }
    }

    private int counter(ResearchOption.Task task, Entry reader) {
        String name = task.counter();
        int index = counterIndex.getInt(name);
        if (index < 0) {
            index = counters.size();
            counterIndex.put(name, index);
            counters.add(new Counter(name, task, new ObjectArrayList<>()));
        }
        counters.get(index).readers().add(reader);
        return index;
    }

    private static int branchRank(String branch) {
        int rank = BRANCH_ORDER.indexOf(branch);
        return rank < 0 ? BRANCH_ORDER.size() : rank;
    }

    /** The tree of {@code access}'s research registry; an empty tree when the registry is missing. */
    // Registries are compared by identity: each server and client holds its own instance.
    @SuppressWarnings("ReferenceEquality")
    public static ResearchTree of(RegistryAccess access) {
        Registry<ResearchNode> registry = access.lookup(ResearchNode.REGISTRY).orElse(null);
        if (registry == null) {
            return EMPTY;
        }
        ResearchTree last = lastTree;
        if (last != null && last.registry == registry) {
            return last;
        }
        synchronized (CACHE) {
            ResearchTree tree = CACHE.computeIfAbsent(registry, r -> new ResearchTree(r, r.entrySet()));
            lastTree = tree;
            return tree;
        }
    }

    /** Every node, by branch, order and id. */
    public List<Entry> entries() {
        return entries;
    }

    /** The branches in screen order. */
    public List<String> branches() {
        return branches;
    }

    public @Nullable Entry node(Identifier id) {
        return byId.get(id);
    }

    /** The nodes granting {@code unlock}; empty when none does. */
    public List<Entry> granting(Identifier unlock) {
        return byUnlock.getOrDefault(unlock, List.of());
    }

    /** The counter with {@code name}, or -1. */
    public int counterIndex(String name) {
        return counterIndex.getInt(name);
    }

    public List<Counter> counters() {
        return counters;
    }

    public Counter counter(int index) {
        return counters.get(index);
    }
}
