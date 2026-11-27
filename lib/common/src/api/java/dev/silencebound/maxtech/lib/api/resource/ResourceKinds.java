package dev.silencebound.maxtech.lib.api.resource;

import com.mojang.serialization.Codec;
import java.util.Optional;
import java.util.function.UnaryOperator;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;

/**
 * The resource kinds of this game session (ADR-0017). Mods register their kinds during mod construction; Lib freezes
 * the registry at its common setup, and a later {@link #register} throws. Registration is thread-safe (mods construct
 * in parallel); lookups never lock and are safe from any thread, {@link #byIndex} with a plain array read.
 */
@ApiStatus.Experimental
public final class ResourceKinds {
    private static final KindTable TABLE = new KindTable();

    /** Items, keyed by {@link ItemKey}, saved as {@code {"kind": "maxtech_lib:item", "id": ..., "components": ...}}. */
    public static final ResourceKind<ItemKey> ITEM = register(lib("item"), ItemKey.MAP_CODEC.codec(),
            ItemKey.STREAM_CODEC, key -> key.hasComponents() ? ItemKey.of(key.item()) : key, AmountUnit.ITEMS);
    /** Fluids, keyed by {@link FluidKey}, in mB; saved like {@link #ITEM}. */
    public static final ResourceKind<FluidKey> FLUID = register(lib("fluid"), FluidKey.MAP_CODEC.codec(),
            FluidKey.STREAM_CODEC, key -> key.hasComponents() ? FluidKey.of(key.fluid()) : key,
            AmountUnit.MILLIBUCKETS);
    /**
     * MaxTech energy in MU, with the one key {@link EnergyKey#INSTANCE}; for gauges and menu values only, energy moves
     * through {@code lib.api.energy}.
     */
    public static final ResourceKind<EnergyKey> ENERGY = register(lib("energy"), EnergyKey.CODEC,
            EnergyKey.STREAM_CODEC, key -> key, AmountUnit.MU);

    private ResourceKinds() {
    }

    /**
     * Registers a kind. Call during mod construction; throws {@link IllegalStateException} after Lib's common setup and
     * {@link IllegalArgumentException} for an id already registered or {@link UnknownResource#KIND}'s id.
     *
     * @param id
     *            what saves name the kind by; kinds for other mods' resources use Lib's namespace
     * @param codec
     *            saves a key; a map codec (from {@code MapCodec.codec()}) puts its fields next to {@code kind}
     * @param streamCodec
     *            sends a key
     * @param drawBase
     *            the key without what only tells variants apart, for drawing; see {@link ResourceKind#drawBase}
     * @param unit
     *            how amounts are counted and shown
     */
    public static <K> ResourceKind<K> register(Identifier id, Codec<K> codec,
            StreamCodec<RegistryFriendlyByteBuf, K> streamCodec, UnaryOperator<K> drawBase, AmountUnit unit) {
        return TABLE.register(id, codec, streamCodec, drawBase, unit);
    }

    /** The kind registered under {@code id}, or empty if no loaded mod registers one. */
    public static Optional<ResourceKind<?>> byId(Identifier id) {
        return TABLE.byId(id);
    }

    /**
     * The kind with {@link ResourceKind#index()} {@code index}; throws {@link IndexOutOfBoundsException} if there is
     * none.
     */
    public static ResourceKind<?> byIndex(int index) {
        return TABLE.byIndex(index);
    }

    /** How many kinds are registered: every index below it names one. */
    public static int count() {
        return TABLE.count();
    }

    /** Whether registration is over (after Lib's common setup). */
    public static boolean isFrozen() {
        return TABLE.isFrozen();
    }

    /** Ends registration; Lib calls it at its common setup. Calling it again does nothing. */
    @ApiStatus.Internal
    public static void freeze() {
        TABLE.freeze();
    }

    private static Identifier lib(String path) {
        return Identifier.fromNamespaceAndPath("maxtech_lib", path);
    }
}
