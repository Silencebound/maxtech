package dev.silencebound.maxtech.storage.crafting.request;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.crafting.generator.GeneratorContent;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJob;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.crafting.plan.PlanTree;
import dev.silencebound.maxtech.storage.machine.MachineContent;
import dev.silencebound.maxtech.storage.machine.MachineKind;
import dev.silencebound.maxtech.storage.machine.StorageMachineRecipe;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.ToLongFunction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * What a crafting screen shows of a plan or a job: the request, its status and, in extended mode, the tree's nodes in
 * the planner's pre-order with per-node needed / stored / to craft / missing counts, the provider that runs each node
 * and an estimated duration. Keys and provider labels are palettes the nodes index, so a key used by many nodes is sent
 * once. A tree larger than {@link #MAX_NODES} keeps its first nodes (pre-order, so they still form a tree from the
 * root) and counts the rest in {@code hidden}; classic mode sends no nodes at all (C29), but the per-key tiles in both
 * modes (the AE2-style grid, owner decision 2026-10-06). Immutable.
 *
 * @param stale
 *            planned against stock that moved before the plan came back
 * @param hidden
 *            nodes of the tree that are not in {@code nodes}
 * @param missing
 *            what cannot be taken or made, per key; at most {@link #MAX_MISSING} entries
 * @param ticks
 *            estimated ticks for the whole tree when every node runs one after the other, or -1 when some node's
 *            duration is unknown
 * @param tiles
 *            the whole tree summed per key ({@link TileSums}): taken from storage, to craft and missing; sent in both
 *            modes (the AE2-style tile grid), at most {@link #MAX_TILES}
 * @param extended
 *            planned in extended mode: the screens offer the tree, a CPU tier and a partial start
 */
public record PlanView(StackKey key, long amount, Status status, boolean stale, List<StackKey> keys,
        List<Component> providers, List<Node> nodes, int hidden, List<Missing> missing, long ticks, List<Tile> tiles,
        boolean extended) {
    /** Nodes sent per view; a performance bound on the payload, not a balance value. */
    public static final int MAX_NODES = 2048;
    /** Missing keys sent per view. */
    public static final int MAX_MISSING = 256;
    /** Tiles (distinct keys) sent per view; a performance bound on the payload. */
    public static final int MAX_TILES = 512;
    private static final int MAX_PALETTE = MAX_NODES + 1;

    /** Whether the request can run, and why not. */
    public enum Status {
        /** Everything can be taken or made. */
        COMPLETE(0),
        /** Some leaves are missing. */
        MISSING(1),
        /** The tree has more nodes than the planner may visit. */
        TOO_MANY_NODES(2),
        /** Planning took too long. */
        TIME_LIMIT(3),
        /** Planning failed; the server log has the cause. */
        ERROR(4);

        public static final StreamCodec<ByteBuf, Status> STREAM_CODEC = ByteBufCodecs.idMapper(Status::byId,
                Status::id);

        private final int id;

        Status(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        public static Status byId(int id) {
            for (Status status : values()) {
                if (status.id == id) {
                    return status;
                }
            }
            return ERROR;
        }

        /** The status of {@code tree}. */
        public static Status of(PlanTree<?> tree) {
            if (tree.failure().isPresent()) {
                return switch (tree.failure().get()) {
                    case TOO_MANY_NODES -> TOO_MANY_NODES;
                    case TIME_LIMIT -> TIME_LIMIT;
                    case ERROR -> ERROR;
                };
            }
            return tree.missing().isEmpty() ? COMPLETE : MISSING;
        }
    }

    /**
     * One node of the tree.
     *
     * @param parent
     *            the parent's index in {@code nodes}, -1 for the root
     * @param key
     *            index into the view's key palette
     * @param provider
     *            index into the view's provider labels, -1 for a node that crafts nothing
     * @param ticks
     *            estimated ticks for this node's crafts, -1 when unknown or when it crafts nothing
     */
    public record Node(int parent, int key, long needed, long stored, long toCraft, long missing, long crafts,
            int provider, long ticks) {
        public static final StreamCodec<ByteBuf, Node> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public Node decode(ByteBuf buffer) {
                return new Node(ByteBufCodecs.VAR_INT.decode(buffer) - 1, ByteBufCodecs.VAR_INT.decode(buffer),
                        ByteBufCodecs.VAR_LONG.decode(buffer), ByteBufCodecs.VAR_LONG.decode(buffer),
                        ByteBufCodecs.VAR_LONG.decode(buffer), ByteBufCodecs.VAR_LONG.decode(buffer),
                        ByteBufCodecs.VAR_LONG.decode(buffer), ByteBufCodecs.VAR_INT.decode(buffer) - 1,
                        ByteBufCodecs.VAR_LONG.decode(buffer) - 1);
            }

            @Override
            public void encode(ByteBuf buffer, Node node) {
                ByteBufCodecs.VAR_INT.encode(buffer, node.parent + 1);
                ByteBufCodecs.VAR_INT.encode(buffer, node.key);
                ByteBufCodecs.VAR_LONG.encode(buffer, node.needed);
                ByteBufCodecs.VAR_LONG.encode(buffer, node.stored);
                ByteBufCodecs.VAR_LONG.encode(buffer, node.toCraft);
                ByteBufCodecs.VAR_LONG.encode(buffer, node.missing);
                ByteBufCodecs.VAR_LONG.encode(buffer, node.crafts);
                ByteBufCodecs.VAR_INT.encode(buffer, node.provider + 1);
                ByteBufCodecs.VAR_LONG.encode(buffer, node.ticks + 1);
            }
        };
    }

    /** A missing key and how much of it. */
    public record Missing(StackKey key, long count) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Missing> STREAM_CODEC = StreamCodec
                .composite(StackKey.STREAM_CODEC, Missing::key, ByteBufCodecs.VAR_LONG, Missing::count, Missing::new);
    }

    /**
     * One key of the tile grid. For a plan: {@code stored} taken from storage, {@code crafting} to craft,
     * {@code missing} missing, {@code scheduled} 0. For a running job ({@link RequestPayloads.JobStatus}):
     * {@code stored} held in the CPU, {@code crafting} in flight in machines, {@code scheduled} still to send,
     * {@code missing} waiting for because storage lacks it.
     */
    public record Tile(StackKey key, long stored, long crafting, long scheduled, long missing) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Tile> STREAM_CODEC = StreamCodec.composite(
                StackKey.STREAM_CODEC, Tile::key, ByteBufCodecs.VAR_LONG, Tile::stored, ByteBufCodecs.VAR_LONG,
                Tile::crafting, ByteBufCodecs.VAR_LONG, Tile::scheduled, ByteBufCodecs.VAR_LONG, Tile::missing,
                Tile::new);
        /** A list of at most {@link #MAX_TILES}. */
        public static final StreamCodec<RegistryFriendlyByteBuf, List<Tile>> LIST_CODEC = STREAM_CODEC
                .apply(ByteBufCodecs.list(MAX_TILES));

        /** The tiles of {@code sums}. */
        public static List<Tile> of(List<TileSums.Sum<StackKey>> sums) {
            List<Tile> out = new ArrayList<>(sums.size());
            for (TileSums.Sum<StackKey> sum : sums) {
                out.add(new Tile(sum.key(), sum.stored(), sum.crafting(), sum.scheduled(), sum.missing()));
            }
            return out;
        }
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, PlanView> STREAM_CODEC = new StreamCodec<>() {
        private final StreamCodec<RegistryFriendlyByteBuf, List<StackKey>> keys = StackKey.STREAM_CODEC
                .apply(ByteBufCodecs.list(MAX_PALETTE));
        private final StreamCodec<RegistryFriendlyByteBuf, List<Component>> providers = ComponentSerialization.TRUSTED_STREAM_CODEC
                .apply(ByteBufCodecs.list(MAX_PALETTE));
        private final StreamCodec<ByteBuf, List<Node>> nodes = Node.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_NODES));
        private final StreamCodec<RegistryFriendlyByteBuf, List<Missing>> missing = Missing.STREAM_CODEC
                .apply(ByteBufCodecs.list(MAX_MISSING));

        @Override
        public PlanView decode(RegistryFriendlyByteBuf buffer) {
            return new PlanView(StackKey.STREAM_CODEC.decode(buffer), ByteBufCodecs.VAR_LONG.decode(buffer),
                    Status.STREAM_CODEC.decode(buffer), ByteBufCodecs.BOOL.decode(buffer), keys.decode(buffer),
                    providers.decode(buffer), nodes.decode(buffer), ByteBufCodecs.VAR_INT.decode(buffer),
                    missing.decode(buffer), ByteBufCodecs.VAR_LONG.decode(buffer) - 1, Tile.LIST_CODEC.decode(buffer),
                    ByteBufCodecs.BOOL.decode(buffer));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, PlanView view) {
            StackKey.STREAM_CODEC.encode(buffer, view.key);
            ByteBufCodecs.VAR_LONG.encode(buffer, view.amount);
            Status.STREAM_CODEC.encode(buffer, view.status);
            ByteBufCodecs.BOOL.encode(buffer, view.stale);
            keys.encode(buffer, view.keys);
            providers.encode(buffer, view.providers);
            nodes.encode(buffer, view.nodes);
            ByteBufCodecs.VAR_INT.encode(buffer, view.hidden);
            missing.encode(buffer, view.missing);
            ByteBufCodecs.VAR_LONG.encode(buffer, view.ticks + 1);
            Tile.LIST_CODEC.encode(buffer, view.tiles);
            ByteBufCodecs.BOOL.encode(buffer, view.extended);
        }
    };

    public PlanView {
        keys = List.copyOf(keys);
        providers = List.copyOf(providers);
        nodes = List.copyOf(nodes);
        missing = List.copyOf(missing);
        tiles = List.copyOf(tiles);
    }

    /** Whether the request can start as it is. */
    public boolean complete() {
        return status == Status.COMPLETE;
    }

    /** The key of node {@code index}. */
    public StackKey nodeKey(int index) {
        return keys.get(nodes.get(index).key);
    }

    /** The view of a fresh plan; {@code extended} false sends the status, missing keys and tiles only (classic). */
    public static PlanView of(CraftingPlanner.Result result, ServerLevel level, boolean extended) {
        return of(result, level, extended, key -> 0);
    }

    /**
     * The view of a fresh plan; {@code generatorTicks} gives the measured ticks per 1,000 units of the generator
     * providers for a key (0 when unknown), for the estimate of generator nodes (M7C item 11).
     */
    public static PlanView of(CraftingPlanner.Result result, ServerLevel level, boolean extended,
            ToLongFunction<StackKey> generatorTicks) {
        PlanTree<StackKey> tree = result.tree();
        List<PlanTree.Step<StackKey>> steps = tree.steps();
        Builder builder = new Builder(level, extended, steps.size(), generatorTicks);
        for (int i = 0; i < steps.size(); i++) {
            PlanTree.Step<StackKey> step = steps.get(i);
            builder.tile(step.key(), step.stored(), step.toCraft(), step.missing());
            if (builder.wants()) {
                builder.add(step.parent(), step.key(), step.needed(), step.stored(), step.toCraft(), step.missing(),
                        step.crafts(), step.pattern() >= 0 ? result.patterns().get(step.pattern()) : null,
                        step.pattern() == PlanTree.GENERATOR);
            }
        }
        return builder.build(result.key(), result.amount(), Status.of(tree), result.stale(), tree.missing());
    }

    /** The view of a saved job. */
    public static PlanView of(CraftingJob job, ServerLevel level, boolean extended) {
        return of(job, level, extended, key -> 0);
    }

    /** The view of a saved job, with generator estimates as for a plan. */
    public static PlanView of(CraftingJob job, ServerLevel level, boolean extended,
            ToLongFunction<StackKey> generatorTicks) {
        List<CraftingJob.Step> steps = job.steps();
        Builder builder = new Builder(level, extended, steps.size(), generatorTicks);
        for (int i = 0; i < steps.size(); i++) {
            CraftingJob.Step step = steps.get(i);
            builder.tile(step.key(), step.stored(), step.toCraft(), step.missing());
            if (builder.wants()) {
                builder.add(step.parent(), step.key(), step.needed(), step.stored(), step.toCraft(), step.missing(),
                        step.crafts(), job.pattern(i).orElse(null), step.pattern() == PlanTree.GENERATOR);
            }
        }
        Object2LongLinkedOpenHashMap<StackKey> missing = new Object2LongLinkedOpenHashMap<>();
        for (CraftingJob.Step step : steps) {
            if (step.missing() > 0) {
                missing.addTo(step.key(), step.missing());
            }
        }
        return builder.build(job.key(), job.amount(), missing.isEmpty() ? Status.COMPLETE : Status.MISSING, false,
                missing);
    }

    /** Collects nodes and palettes; the estimate is the sum of every node's ticks. */
    private static final class Builder {
        private final ServerLevel level;
        private final boolean extended;
        private final int total;
        private final List<StackKey> keys = new ArrayList<>();
        private final Object2IntOpenHashMap<StackKey> keyIndex = new Object2IntOpenHashMap<>();
        private final List<Component> providers = new ArrayList<>();
        private final Object2IntOpenHashMap<CraftingPattern> providerIndex = new Object2IntOpenHashMap<>();
        private final List<Node> nodes = new ArrayList<>();
        private final TileSums<StackKey> tiles = new TileSums<>();
        private final ToLongFunction<StackKey> generatorTicks;
        /** The generator provider's label in {@link #providers}, -1 before the first generator node. */
        private int generatorLabel = -1;
        private long ticks;

        Builder(ServerLevel level, boolean extended, int total, ToLongFunction<StackKey> generatorTicks) {
            this.level = level;
            this.extended = extended;
            this.total = total;
            this.generatorTicks = generatorTicks;
            keyIndex.defaultReturnValue(-1);
            providerIndex.defaultReturnValue(-1);
        }

        void tile(StackKey key, long stored, long toCraft, long missing) {
            tiles.add(key, stored, toCraft, 0, missing);
        }

        boolean wants() {
            return extended && nodes.size() < MAX_NODES;
        }

        void add(int parent, StackKey key, long needed, long stored, long toCraft, long missing, long crafts,
                @Nullable CraftingPattern pattern, boolean generator) {
            int k = keyIndex.getInt(key);
            if (k < 0) {
                k = keys.size();
                keys.add(key);
                keyIndex.put(key, k);
            }
            int provider = -1;
            long nodeTicks = -1;
            if (generator) {
                // A generator node runs at its measured rate; unmeasured, it adds nothing to the estimate.
                if (generatorLabel < 0) {
                    generatorLabel = providers.size();
                    providers.add(name(GeneratorContent.GENERATOR.get()));
                }
                provider = generatorLabel;
                long perThousand = generatorTicks.applyAsLong(key);
                if (perThousand > 0) {
                    nodeTicks = saturatedMultiply(perThousand, crafts) / 1000;
                    if (ticks >= 0) {
                        ticks += nodeTicks;
                    }
                }
            } else if (pattern != null) {
                provider = providerIndex.getInt(pattern);
                if (provider < 0) {
                    provider = providers.size();
                    providers.add(providerLabel(pattern));
                    providerIndex.put(pattern, provider);
                }
                long perCraft = ticksPerCraft(level, pattern);
                nodeTicks = perCraft < 0 ? -1 : perCraft * crafts;
            }
            if (ticks >= 0 && pattern != null) {
                ticks = nodeTicks < 0 ? -1 : ticks + nodeTicks;
            }
            nodes.add(new Node(parent, k, needed, stored, toCraft, missing, crafts, provider, nodeTicks));
        }

        PlanView build(StackKey key, long amount, Status status, boolean stale, Object2LongMap<StackKey> missing) {
            List<Missing> missingList = new ArrayList<>(Math.min(missing.size(), MAX_MISSING));
            for (Object2LongMap.Entry<StackKey> entry : missing.object2LongEntrySet()) {
                if (missingList.size() >= MAX_MISSING) {
                    break;
                }
                missingList.add(new Missing(entry.getKey(), entry.getLongValue()));
            }
            boolean shown = nodes.size() == total;
            return new PlanView(key, amount, status, stale, keys, providers, nodes, total - nodes.size(), missingList,
                    extended && shown ? ticks : -1, Tile.of(tiles.result(MAX_TILES)), extended);
        }
    }

    /**
     * What runs a pattern, for the tree: a crafting assembler for crafting patterns, else the block that makes the
     * pattern's recipe type (the vanilla cookers, the brewing stand, Storage's machines), else the recipe type's id.
     */
    static Component providerLabel(CraftingPattern pattern) {
        if (pattern.kind() == CraftingPattern.Kind.CRAFTING) {
            return Component.translatable(RequestContent.PROVIDER_CRAFTING_KEY);
        }
        Optional<Identifier> type = pattern.recipeType();
        if (type.isEmpty()) {
            return Component.translatable(RequestContent.PROVIDER_MACHINE_KEY);
        }
        Block block = switch (type.get().toString()) {
            case "minecraft:smelting" -> Blocks.FURNACE;
            case "minecraft:blasting" -> Blocks.BLAST_FURNACE;
            case "minecraft:smoking" -> Blocks.SMOKER;
            case "minecraft:campfire_cooking" -> Blocks.CAMPFIRE;
            case "minecraft:brewing" -> Blocks.BREWING_STAND;
            case "minecraft:stonecutting" -> Blocks.STONECUTTER;
            case "minecraft:smithing" -> Blocks.SMITHING_TABLE;
            default -> machineFor(type.get());
        };
        return block == null ? Component.literal(type.get().toString()) : name(block);
    }

    private static @Nullable Block machineFor(Identifier type) {
        for (MachineKind kind : MachineKind.values()) {
            if (kind.processes() && MaxtechStorage.id(kind.id()).equals(type)) {
                return MachineContent.block(kind);
            }
        }
        return null;
    }

    private static long saturatedMultiply(long a, long b) {
        long high = Math.multiplyHigh(a, b);
        long low = a * b;
        return high == 0 && low >= 0 ? low : Long.MAX_VALUE;
    }

    private static Component name(Block block) {
        return Component.translatable(block.getDescriptionId());
    }

    /**
     * Ticks one craft of {@code pattern} takes, from its recipe when the server has it and the recipe states a time
     * (cooking recipes, Storage's machine recipes); -1 otherwise.
     */
    static long ticksPerCraft(ServerLevel level, CraftingPattern pattern) {
        if (pattern.recipe().isEmpty()) {
            return -1;
        }
        Optional<RecipeHolder<?>> holder = level.recipeAccess()
                .byKey(ResourceKey.create(Registries.RECIPE, pattern.recipe().get()));
        if (holder.isEmpty()) {
            return -1;
        }
        Recipe<?> recipe = holder.get().value();
        if (recipe instanceof AbstractCookingRecipe cooking) {
            return cooking.cookingTime();
        }
        if (recipe instanceof StorageMachineRecipe machine) {
            return machine.duration();
        }
        return -1;
    }
}
