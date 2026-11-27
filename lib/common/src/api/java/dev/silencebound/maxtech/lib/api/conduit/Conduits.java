package dev.silencebound.maxtech.lib.api.conduit;

import dev.silencebound.maxtech.lib.api.network.NodeConnectivity;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * Where an addon registers its own conduits and reaches the conduits in the world. Lib owns every conduit block and its
 * block entity ({@code maxtech_lib:conduit}): it saves, syncs and draws them, takes dyes and facades on them and
 * reports them to the network of their type's {@link ConduitType#networkType()}. An addon brings the
 * {@link ConduitType}, a {@code NetworkType} with its logic ({@code lib.api.network}) and the resources, and changes a
 * conduit's behaviour only through the {@link Builder}'s hooks, never by subclassing the block.
 * <p>
 * Lib sets this up in its own construction, so an addon must declare its {@code maxtech_lib} dependency with
 * {@code ordering = "AFTER"} in {@code neoforge.mods.toml}.
 * <p>
 * Experimental (Lib 0.14.0, for Farms, FM1) until Farms, its first addon user, settles it.
 */
@ApiStatus.Experimental
public final class Conduits {
    private static volatile Factory factory = new Unset();

    private Conduits() {
    }

    /**
     * Starts registering a conduit of the addon {@code modId}: its conduit type made by {@code type}, Lib's conduit
     * block for it and the item it is placed from, all under {@code name}. {@code type} gets the item, resolved lazily,
     * since the type is built before items register. Call during mod construction, like any registrar.
     * <p>
     * Lib's conduit model draws the block. With a {@link Builder#look look}, Lib supplies the block state file
     * ({@code assets/<modid>/blockstates/<name>.json}) and the item model definition
     * ({@code assets/<modid>/items/<name>.json}, naming the model {@code <modid>:item/<name>}) from a built-in client
     * pack below the mods' own resources, so a file the addon ships still wins. The addon ships that item model and the
     * English name; a loot table ({@code data/<modid>/loot_table/blocks/<name>.json}) only if the conduit should drop
     * something other than its item, which Lib drops without one (a facade drops on its own either way).
     */
    public static <T extends ConduitType> Builder<T> builder(String modId, String name,
            Function<Supplier<ItemLike>, T> type) {
        return new Builder<>(modId, name, type);
    }

    /**
     * The conduit at {@code pos}, or empty if there is none or its chunk is not present ({@link #isChunkPresent});
     * never loads a chunk. Allocates the {@code Optional}, so keep it out of per-tick loops; network logic reads its
     * own nodes when the graph reports them. On a server level call it from the server thread.
     */
    public static Optional<ConduitHost> host(Level level, BlockPos pos) {
        return factory.host(level, pos);
    }

    /** The block Lib made for {@code type}, or empty if the type has none (registered without a builder). */
    public static Optional<Block> blockOf(ConduitType type) {
        return factory.blockOf(type);
    }

    /**
     * The connectivity of the conduits of {@code networkType} in {@code level}, read from the links Lib keeps in their
     * block entities; hand it to {@code NetworkType.register} as the connectivity factory's result. A link keeps its
     * bit while the neighbour's chunk is unloaded, so the graph sees a cut edge there.
     */
    public static NodeConnectivity connectivity(ServerLevel level, Identifier networkType) {
        return factory.connectivity(level, networkType);
    }

    /**
     * Whether the chunk holding block column {@code (x, z)} is fully present, as {@link NodeConnectivity#isLoaded}
     * defines it. Never loads the chunk; read neighbours only after it answers true. Server thread on a server level.
     */
    public static boolean isChunkPresent(Level level, int x, int z) {
        return factory.isChunkPresent(level, x, z);
    }

    /**
     * Places an uncoloured conduit of {@code type} at {@code pos} on the server, as a player placing its item would;
     * its links are worked out at once. Returns false if the type has no block or the position is not replaceable.
     */
    public static boolean place(Level level, BlockPos pos, ConduitType type) {
        return factory.place(level, pos, type, null);
    }

    /** As {@link #place(Level, BlockPos, ConduitType)}, dyed {@code colour}. */
    public static boolean place(Level level, BlockPos pos, ConduitType type, DyeColor colour) {
        return factory.place(level, pos, type, colour);
    }

    /**
     * Uses a wrench on the conduit at {@code pos}, for an addon's own wrench item (ADR-0005): the type's
     * {@link ConduitWrenchHook} first, then Lib's handling: cycling the hit face, or with {@code dismantle} taking off
     * the facade, else the conduit into the player's inventory. Returns {@link InteractionResult#PASS} if there is no
     * conduit, otherwise {@code SUCCESS} on both sides; only the server changes anything.
     */
    public static InteractionResult useWrench(Level level, BlockPos pos, Player player, BlockHitResult hit,
            boolean dismantle) {
        return factory.useWrench(level, pos, player, hit, dismantle);
    }

    /** Sets what this class delegates to; Lib calls it once during init. Unit tests may set a fake. */
    @ApiStatus.Internal
    public static void setFactory(Factory source) {
        factory = source;
    }

    /**
     * One conduit's registration; see {@link Conduits#builder}. Nothing is registered until {@link #register()}. Use
     * each builder once, during mod construction.
     *
     * @param <T>
     *            the conduit type's class
     */
    @ApiStatus.Experimental
    public static final class Builder<T extends ConduitType> {
        private final String modId;
        private final String name;
        private final Function<Supplier<ItemLike>, T> type;
        private Optional<ResourceKey<CreativeModeTab>> creativeTab = Optional.empty();
        private Optional<ConduitLook> look = Optional.empty();
        private ConduitWrenchHook wrench = ConduitWrenchHook.NONE;
        private ConduitConnectionRule connectionRule = ConduitConnectionRule.ANY;

        private Builder(String modId, String name, Function<Supplier<ItemLike>, T> type) {
            this.modId = modId;
            this.name = name;
            this.type = type;
        }

        /**
         * Lists the conduit's item in {@code tab} (e.g. one of {@code CreativeModeTabs}, or the addon's own tab's key),
         * after what the tab already holds. Not listed anywhere if not called.
         */
        public Builder<T> creativeTab(ResourceKey<CreativeModeTab> tab) {
            this.creativeTab = Optional.of(tab);
            return this;
        }

        /**
         * How the block is drawn. Lib then supplies the conduit's block state file and item model definition (see
         * {@link Conduits#builder}); without a look the addon ships both itself.
         */
        public Builder<T> look(ConduitLook look) {
            this.look = Optional.of(look);
            return this;
        }

        /** Handles a wrench before Lib does; see {@link ConduitWrenchHook}. Lib's handling alone if not set. */
        public Builder<T> onWrench(ConduitWrenchHook hook) {
            this.wrench = hook;
            return this;
        }

        /**
         * Narrows which neighbouring conduits this conduit links with; see {@link ConduitConnectionRule}. Lib's own
         * rules alone if not set.
         */
        public Builder<T> connectionRule(ConduitConnectionRule rule) {
            this.connectionRule = rule;
            return this;
        }

        /** Queues the type, block and item for registration and returns their entries. */
        public ConduitEntry<T> register() {
            return factory.register(new Registration<>(modId, name, type, creativeTab, look, wrench, connectionRule));
        }
    }

    /**
     * What a {@link Builder} collected, handed to Lib's {@link Factory}.
     *
     * @param <T>
     *            the conduit type's class
     * @param modId
     *            the addon's mod id
     * @param name
     *            the registry name of the type, block and item
     * @param type
     *            makes the conduit type from its lazily resolved item
     * @param creativeTab
     *            the tab to list the item in, if any
     * @param look
     *            how the block is drawn, if Lib supplies its resources
     * @param wrench
     *            the wrench hook
     * @param connectionRule
     *            the connection rule
     */
    @ApiStatus.Internal
    public record Registration<T extends ConduitType>(String modId, String name, Function<Supplier<ItemLike>, T> type,
            Optional<ResourceKey<CreativeModeTab>> creativeTab, Optional<ConduitLook> look, ConduitWrenchHook wrench,
            ConduitConnectionRule connectionRule) {
    }

    /** Lib's side of {@link Conduits}. */
    @ApiStatus.Internal
    public interface Factory {
        /** See {@link Builder#register()}. */
        <T extends ConduitType> ConduitEntry<T> register(Registration<T> registration);

        /** See {@link Conduits#host}. */
        Optional<ConduitHost> host(Level level, BlockPos pos);

        /** See {@link Conduits#blockOf}. */
        Optional<Block> blockOf(ConduitType type);

        /** See {@link Conduits#connectivity}. */
        NodeConnectivity connectivity(ServerLevel level, Identifier networkType);

        /** See {@link Conduits#isChunkPresent}. */
        boolean isChunkPresent(Level level, int x, int z);

        /** See {@link Conduits#place}; {@code colour} null for uncoloured. */
        boolean place(Level level, BlockPos pos, ConduitType type, @Nullable DyeColor colour);

        /** See {@link Conduits#useWrench}. */
        InteractionResult useWrench(Level level, BlockPos pos, Player player, BlockHitResult hit, boolean dismantle);
    }

    private static final class Unset implements Factory {
        private static IllegalStateException unset() {
            return new IllegalStateException("MaxTech Lib has not set up conduits yet");
        }

        @Override
        public <T extends ConduitType> ConduitEntry<T> register(Registration<T> registration) {
            throw unset();
        }

        @Override
        public Optional<ConduitHost> host(Level level, BlockPos pos) {
            throw unset();
        }

        @Override
        public Optional<Block> blockOf(ConduitType type) {
            throw unset();
        }

        @Override
        public NodeConnectivity connectivity(ServerLevel level, Identifier networkType) {
            throw unset();
        }

        @Override
        public boolean isChunkPresent(Level level, int x, int z) {
            throw unset();
        }

        @Override
        public boolean place(Level level, BlockPos pos, ConduitType type, @Nullable DyeColor colour) {
            throw unset();
        }

        @Override
        public InteractionResult useWrench(Level level, BlockPos pos, Player player, BlockHitResult hit,
                boolean dismantle) {
            throw unset();
        }
    }
}
