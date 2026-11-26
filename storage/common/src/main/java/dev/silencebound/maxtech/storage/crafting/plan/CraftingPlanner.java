package dev.silencebound.maxtech.storage.crafting.plan;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.concurrent.BackgroundJob;
import dev.silencebound.maxtech.lib.concurrent.WorkerPool;
import dev.silencebound.maxtech.storage.api.StorageContents;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.crafting.pattern.NetworkPatterns;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternReturns;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternTable;
import dev.silencebound.maxtech.storage.network.NetworkState;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.level.material.Fluid;
import org.jspecify.annotations.Nullable;

/**
 * Plans a crafting request against a storage network off the server thread (M7 C27): the server thread copies the
 * network's totals (key and amount arrays from its contents snapshot) and pattern snapshot, a {@link WorkerPool} thread
 * resolves tag inputs to the keys that match (stored keys and craftable outputs) and runs the {@link Planner}, and back
 * on the server thread the result is checked against the live totals before it is handed over. If the stock it counted
 * on is gone, it plans again, at most {@link #RETRIES} times, then hands over the last result marked stale; the job's
 * start then extracts only what the network holds. Copying the totals is linear in the number of distinct keys; that
 * runs once per request, not per tick.
 */
public final class CraftingPlanner {
    /** Re-plans when the stock moved while planning, before the result is handed over as it is. */
    static final int RETRIES = 2;
    /** Requests one order list plans at most; the list code's bound. */
    public static final int MAX_ROOTS = 256;

    /** One request of a plan: {@code amount} of {@code key}. */
    public record Root(StackKey key, long amount) {
    }

    /**
     * A finished plan.
     *
     * @param key
     *            the first request's key
     * @param amount
     *            the first request's amount
     * @param patterns
     *            the patterns the steps' pattern ids index
     * @param stale
     *            planned against stock that moved before it could be handed over
     * @param more
     *            the order list's further requests (M7C item 6), planned after the first in this order; empty for a
     *            single request
     */
    public record Result(StackKey key, long amount, PlanTree<StackKey> tree, List<CraftingPattern> patterns,
            boolean stale, List<Root> more) {
        public Result {
            more = List.copyOf(more);
        }

        /** A single request's result. */
        public Result(StackKey key, long amount, PlanTree<StackKey> tree, List<CraftingPattern> patterns,
                boolean stale) {
            this(key, amount, tree, patterns, stale, List.of());
        }

        /** This result marked as planned against stock that has moved since. */
        public Result asStale() {
            return new Result(key, amount, tree, patterns, true, more);
        }

        /** Every request of the plan, the first one first; the tree has one root per request, in this order. */
        public List<Root> roots() {
            List<Root> all = new ArrayList<>(1 + more.size());
            all.add(new Root(key, amount));
            all.addAll(more);
            return all;
        }
    }

    /** A result for a plan that could not be computed at all ({@link PlanTree.Failure#ERROR}). */
    public static Result failed(StackKey key, long amount) {
        return new Result(key, amount, PlanTree.failed(PlanTree.Failure.ERROR, 0), List.of(), false);
    }

    /**
     * What happens to a result the pool did not commit: a computation that threw hands the requester a failed result; a
     * result whose stock moved is planned again through {@code retry} while {@code attempt} is below {@link #RETRIES}
     * and the pool takes it, else it is handed over marked {@linkplain Result#stale() stale}, so the job start extracts
     * only what the network really holds. The requester always gets exactly one answer. Server thread; public for the
     * GameTests.
     */
    public static void discard(@Nullable Result result, StackKey key, long amount, int attempt, BooleanSupplier retry,
            Consumer<Result> done) {
        if (result == null) {
            done.accept(failed(key, amount));
        } else if (attempt >= RETRIES || !retry.getAsBoolean()) {
            done.accept(result.asStale());
        }
    }

    private CraftingPlanner() {
    }

    /**
     * Queues a plan for {@code amount} of {@code key} on {@code network}; {@code done} gets the result on the server
     * thread. Returns false when the worker pool is full, and then nothing runs. Server thread only.
     */
    public static boolean submit(NetworkState network, StackKey key, long amount, Consumer<Result> done) {
        return WorkerPool.server().submit(new Job(network, key, amount, List.of(), List.of(), false, done, 0));
    }

    /**
     * As {@link #submit}, but the plan crafts the whole {@code amount} instead of first taking what the network already
     * holds of {@code key}. Every request path uses this: a player's request for N crafts N, like AE2 (owner decision
     * 2026-10-05), and stock keepers and crafting cards ask for exactly what is missing. Server thread only.
     */
    public static boolean submitCrafted(NetworkState network, StackKey key, long amount, Consumer<Result> done) {
        return WorkerPool.server().submit(new Job(network, key, amount, List.of(), List.of(), true, done, 0));
    }

    /**
     * Plans an order list (M7C item 6): every root crafted in full, as {@link #submitCrafted} does for one, in one tree
     * against one stock, so the roots share what is stored. {@code roots} holds one to {@link #MAX_ROOTS} requests.
     * Returns false when the worker pool is full or the list is empty or too long, and then nothing runs. Server thread
     * only.
     */
    public static boolean submitOrder(NetworkState network, List<Root> roots, Consumer<Result> done) {
        return submitOrder(network, roots, List.of(), done);
    }

    /**
     * {@link #submitOrder(NetworkState, List, Consumer)}, planned as if storage held {@code reserved} less: what a
     * resumed order already delivered stays for its requester instead of feeding the order's other requests.
     */
    public static boolean submitOrder(NetworkState network, List<Root> roots, List<Root> reserved,
            Consumer<Result> done) {
        if (roots.isEmpty() || roots.size() > MAX_ROOTS) {
            return false;
        }
        return WorkerPool.server().submit(new Job(network, roots.getFirst().key(), roots.getFirst().amount(),
                roots.subList(1, roots.size()), reserved, true, done, 0));
    }

    /** {@link #submitOrder} synchronously on the calling thread; for tests. */
    public static Result planOrderNow(NetworkState network, List<Root> roots) {
        return new Job(network, roots.getFirst().key(), roots.getFirst().amount(), roots.subList(1, roots.size()),
                List.of(), true, result -> {
                }, 0).compute();
    }

    /** Plans synchronously on the calling thread; for tests and the perf GameTest. */
    public static Result planNow(NetworkState network, StackKey key, long amount) {
        return new Job(network, key, amount, List.of(), List.of(), false, result -> {
        }, 0).compute();
    }

    private static final class Job implements BackgroundJob<Result> {
        private final NetworkState network;
        private final StackKey key;
        private final long amount;
        private final List<Root> more;
        /** Taken off the stock before planning. */
        private final List<Root> reserved;
        private final boolean craftRoot;
        private final Consumer<Result> done;
        private final int attempt;
        private final StackKey[] keys;
        private final long[] amounts;
        private final List<CraftingPattern> patterns;
        private final IntList priorities;
        /** Keys generator providers make (M7C item 11), with their highest priority; read-only. */
        private final Object2IntMap<StackKey> generators;
        private final int nodeCap;
        private final long timeLimitNanos;
        private final int chanceCap;

        Job(NetworkState network, StackKey key, long amount, List<Root> more, List<Root> reserved, boolean craftRoot,
                Consumer<Result> done, int attempt) {
            this.network = network;
            this.key = key;
            this.amount = amount;
            this.more = List.copyOf(more);
            this.reserved = List.copyOf(reserved);
            this.craftRoot = craftRoot;
            this.done = done;
            this.attempt = attempt;
            StorageContents contents = network.contents();
            Int2LongMap totals = contents.snapshot().totals();
            keys = new StackKey[totals.size()];
            amounts = new long[totals.size()];
            int n = 0;
            for (Int2LongMap.Entry entry : totals.int2LongEntrySet()) {
                StackKey stored = contents.key(entry.getIntKey());
                if (stored != null) {
                    keys[n] = stored;
                    amounts[n] = entry.getLongValue();
                    n++;
                }
            }
            NetworkPatterns.Snapshot snapshot = network.patterns().snapshot();
            this.patterns = snapshot.patterns();
            this.priorities = snapshot.priorities();
            this.generators = network.patterns().generatorPriorities();
            this.nodeCap = StorageServerConfig.CRAFTING_PLANNER_NODE_CAP.getAsInt();
            this.timeLimitNanos = StorageServerConfig.CRAFTING_PLANNER_TIME_LIMIT_MS.getAsInt() * 1_000_000L;
            this.chanceCap = StorageServerConfig.CRAFTING_CHANCE_REPEAT_CAP.getAsInt();
        }

        @Override
        public Result compute() {
            long start = System.nanoTime();
            Object2LongOpenHashMap<StackKey> stock = new Object2LongOpenHashMap<>(keys.length);
            for (int i = 0; i < keys.length && keys[i] != null; i++) {
                stock.put(keys[i], amounts[i]);
            }
            for (Root held : reserved) {
                stock.put(held.key(), Math.max(0, stock.getLong(held.key()) - held.amount()));
            }
            Object2ObjectOpenHashMap<StackKey, List<Planner.Pattern<StackKey>>> byOutput = new Object2ObjectOpenHashMap<>();
            for (int i = 0; i < patterns.size(); i++) {
                Planner.Pattern<StackKey> pattern = convert(i, patterns.get(i), stock);
                if (pattern != null) {
                    byOutput.computeIfAbsent(pattern.output(), k -> new ArrayList<>()).add(pattern);
                }
            }
            // A generator provider is an infinite source without inputs. It joins its output's list first, so the
            // stable priority sort below keeps it ahead of real patterns with the same priority (owner decision
            // 2026-10-10: generators have a priority; on a tie the generator goes first, it never runs short).
            for (Object2IntMap.Entry<StackKey> generated : generators.object2IntEntrySet()) {
                List<Planner.Pattern<StackKey>> options = new ArrayList<>(
                        byOutput.getOrDefault(generated.getKey(), List.of()));
                options.addFirst(
                        new Planner.Pattern<>(PlanTree.GENERATOR, generated.getKey(), 1, 1f, List.of(), List.of()));
                byOutput.put(generated.getKey(), options);
            }
            // Highest priority first, each pattern once (M7C item 2); the planner falls back down the list. The
            // generator entry's id is not a pattern index: its priority comes from the generators by its output.
            for (var entries = byOutput.object2ObjectEntrySet().fastIterator(); entries.hasNext();) {
                var entry = entries.next();
                if (entry.getValue().size() > 1) {
                    entry.setValue(PatternOrder.order(entry.getValue(), this::priority, this::samePattern));
                }
            }
            Planner<StackKey> planner = new Planner<>(stock, k -> byOutput.getOrDefault(k, List.of()),
                    new Planner.Limits(nodeCap, start + timeLimitNanos, chanceCap, System::nanoTime));
            if (more.isEmpty()) {
                return new Result(key, amount, planner.plan(key, amount, craftRoot), patterns, false);
            }
            List<StackKey> keys = new ArrayList<>(1 + more.size());
            long[] amounts = new long[1 + more.size()];
            keys.add(key);
            amounts[0] = amount;
            for (int i = 0; i < more.size(); i++) {
                keys.add(more.get(i).key());
                amounts[i + 1] = more.get(i).amount();
            }
            return new Result(key, amount, planner.plan(keys, amounts, craftRoot), patterns, false, more);
        }

        /** The priority of a planner entry: a real pattern's own, or for the generator entry its generators'. */
        private int priority(Planner.Pattern<StackKey> pattern) {
            return pattern.id() == PlanTree.GENERATOR
                    ? generators.getInt(pattern.output())
                    : priorities.getInt(pattern.id());
        }

        /** Whether two entries for one output are the same pattern; the generator entry is only itself. */
        private boolean samePattern(Planner.Pattern<StackKey> a, Planner.Pattern<StackKey> b) {
            if (a.id() < 0 || b.id() < 0) {
                return a.id() == b.id();
            }
            return patterns.get(a.id()).equals(patterns.get(b.id()));
        }

        /** The planner's view of pattern {@code id}; null when a tag input matches nothing at all. */
        private static Planner.@Nullable Pattern<StackKey> convert(int id, CraftingPattern pattern,
                Object2LongMap<StackKey> stock) {
            List<Planner.Input<StackKey>> inputs = new ArrayList<>(pattern.inputs().size());
            // Most patterns leave no remainder: the list is made for the first one.
            @Nullable
            List<Planner.Remainder<StackKey>> remainders = null;
            for (int i = 0; i < pattern.inputs().size(); i++) {
                PatternInput input = pattern.inputs().get(i);
                List<StackKey> candidates = candidates(input.ingredient(), stock);
                if (candidates.isEmpty()) {
                    return null;
                }
                // Marked returned, or a catalyst the recipe lists as an output too (M7C item 3).
                boolean returned = PatternReturns.returned(pattern, i);
                inputs.add(new Planner.Input<>(candidates, input.amount(), returned));
                if (!returned && PatternTable.hasRemainders(pattern) && candidates.getFirst() instanceof ItemKey item) {
                    ItemStackTemplate remainder = item.item().getCraftingRemainder(item.toStack(1));
                    if (remainder != null) {
                        if (remainders == null) {
                            remainders = new ArrayList<>(1);
                        }
                        remainders.add(
                                new Planner.Remainder<>(ItemKey.of(remainder.item().value(), remainder.components()),
                                        remainder.count() * input.amount()));
                    }
                }
            }
            PatternOutput main = pattern.mainOutput();
            return new Planner.Pattern<>(id, main.key(), main.amount(), main.chance(), inputs,
                    remainders == null ? List.of() : remainders);
        }

        /**
         * The keys {@code ingredient} accepts: the exact key, or for a tag every stored key that matches, else the
         * tag's first entry, so a shortfall names something. Tag checks read the registries' bound tags, which do not
         * change outside a reload.
         */
        private static List<StackKey> candidates(PatternIngredient ingredient, Object2LongMap<StackKey> stock) {
            if (ingredient instanceof PatternIngredient.Exact exact) {
                return List.of(exact.key());
            }
            List<StackKey> matches = new ArrayList<>();
            for (StackKey stored : stock.keySet()) {
                if (ingredient.matches(stored)) {
                    matches.add(stored);
                }
            }
            Optional<StackKey> first = firstOfTag(ingredient);
            if (first.isPresent() && !matches.contains(first.get())) {
                matches.add(first.get());
            }
            return matches;
        }

        private static Optional<StackKey> firstOfTag(PatternIngredient ingredient) {
            if (ingredient instanceof PatternIngredient.ItemTag tag) {
                Optional<HolderSet.Named<Item>> set = BuiltInRegistries.ITEM.get(tag.tag());
                if (set.isPresent()) {
                    for (Holder<Item> holder : set.get()) {
                        return Optional.of(ItemKey.of(holder.value()));
                    }
                }
            } else if (ingredient instanceof PatternIngredient.FluidTag tag) {
                Optional<HolderSet.Named<Fluid>> set = BuiltInRegistries.FLUID.get(tag.tag());
                if (set.isPresent()) {
                    for (Holder<Fluid> holder : set.get()) {
                        return Optional.of(FluidKey.of(holder.value()));
                    }
                }
            }
            return Optional.empty();
        }

        /** Whether everything the plan takes from stock is still there. */
        @Override
        public boolean revalidate(Result result) {
            StorageContents contents = network.contents();
            for (Object2LongMap.Entry<StackKey> entry : result.tree().used().object2LongEntrySet()) {
                if (contents.amount(entry.getKey()) < entry.getLongValue()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void commit(Result result) {
            done.accept(result);
        }

        @Override
        public void discarded(@Nullable Result result, @Nullable Throwable error) {
            discard(result, key, amount, attempt, () -> WorkerPool.server()
                    .submit(new Job(network, key, amount, more, reserved, craftRoot, done, attempt + 1)), done);
        }
    }
}
