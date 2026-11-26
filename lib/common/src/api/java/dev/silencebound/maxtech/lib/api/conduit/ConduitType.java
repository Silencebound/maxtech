package dev.silencebound.maxtech.lib.api.conduit;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ItemLike;

/**
 * One kind of conduit, e.g. a storage cable tier. A conduit is Lib's generic framework for blocks that connect to their
 * neighbours (cables now, later pipes and wires): one type per block, face modes, shapes and network membership. It is
 * unrelated to vanilla's Conduit block.
 *
 * <p>
 * Registered in {@link ConduitTypes#REGISTRY_KEY}; instances are compared by identity. Each conduit type has a block of
 * its own, holding one conduit drawn {@link #thickness()} wide.
 *
 * <p>
 * Tiers and sizes of the same kind share a {@link #networkType()} and differ in {@link #tier()}. Conduits of one
 * network type connect across tiers, sizes and materials; conduits of different network types never do.
 * <p>
 * Threading: created during registration; its getters are safe from any thread. Subclasses must keep it immutable.
 */
public class ConduitType {
    /** The thickness of a conduit that names none: half a block. */
    public static final float DEFAULT_THICKNESS = 0.5F;

    private static final Set<ConduitFaceMode> ALL_FACE_MODES = Collections
            .unmodifiableSet(EnumSet.allOf(ConduitFaceMode.class));

    private final Identifier networkType;
    private final String tier;
    private final int baseColour;
    private final float thickness;
    private final Supplier<? extends ItemLike> item;

    /**
     * A conduit of {@link #DEFAULT_THICKNESS}.
     *
     * @param networkType
     *            the network this conduit joins; conduits connect only within one network type. Must be a network type
     *            whose logic is registered with Lib's network framework, such as Storage's storage cable network; a
     *            conduit with any other id joins no network and does nothing
     * @param tier
     *            tier or variant id within the network type, e.g. {@code "tier1"}
     * @param baseColour
     *            {@code 0xRRGGBB} body colour of the conduit, drawn wherever its look uses the type's colour (the
     *            default look does); a dye on the conduit tints it
     * @param item
     *            the item this conduit drops and is placed from; resolved lazily because items register after conduit
     *            types
     */
    public ConduitType(Identifier networkType, String tier, int baseColour, Supplier<? extends ItemLike> item) {
        this(networkType, tier, baseColour, DEFAULT_THICKNESS, item);
    }

    /**
     * As {@link #ConduitType(Identifier, String, int, Supplier)}, with the conduit's width.
     *
     * @param thickness
     *            width of the conduit's core and arms as a fraction of a block, above 0 and at most 1, e.g.
     *            {@code 0.125} for a two-pixel cable. Throws {@link IllegalArgumentException} outside that range
     */
    public ConduitType(Identifier networkType, String tier, int baseColour, float thickness,
            Supplier<? extends ItemLike> item) {
        if (!(thickness > 0.0F && thickness <= 1.0F)) {
            throw new IllegalArgumentException("Conduit thickness must be in (0, 1]: " + thickness);
        }
        this.networkType = networkType;
        this.tier = tier;
        this.baseColour = baseColour;
        this.thickness = thickness;
        this.item = item;
    }

    /** The network this conduit joins; conduits connect only within one network type. Never null. */
    public Identifier networkType() {
        return networkType;
    }

    /** Tier or variant id within the network type, e.g. {@code "tier1"}. */
    public String tier() {
        return tier;
    }

    /**
     * {@code 0xRRGGBB} body colour of the conduit, drawn wherever its look uses the type's colour (the default look
     * does); a dye on the conduit tints it. Any thread.
     */
    public int baseColour() {
        return baseColour;
    }

    /**
     * Width of the conduit's core and arms as a fraction of a block, in (0, 1]; its block's collision and selection
     * shape and its model follow it. {@link #DEFAULT_THICKNESS} unless the constructor named one; a subclass whose
     * superclass constructor takes no thickness (e.g. a sized energy cable) may override it, but must return the same
     * value on every call. Any thread.
     */
    public float thickness() {
        return thickness;
    }

    /**
     * The item this conduit drops and is placed from. Resolves the supplier given to the constructor, so call it only
     * after items are registered. Any thread.
     */
    public ItemLike item() {
        return item.get();
    }

    /**
     * A new state for one conduit of this type, or empty for conduits that keep nothing beyond type, colour and
     * connections (the default). Called whenever a block entity of this type's block is created, on both sides. Never
     * null.
     */
    public Optional<ConduitState> createState() {
        return Optional.empty();
    }

    /**
     * Face modes a player can set on this conduit's faces towards blocks, in no particular order. Always contains
     * {@link #defaultFaceMode()} and {@link ConduitFaceMode#DISABLED}; faces towards other conduits use only
     * {@link ConduitFaceMode#BOTH} and {@code DISABLED} regardless. All four by default. Must return the same
     * unmodifiable set on every call; any thread.
     */
    public Set<ConduitFaceMode> supportedFaceModes() {
        return ALL_FACE_MODES;
    }

    /** The mode a face towards a block starts in. {@link ConduitFaceMode#BOTH} by default. Any thread. */
    public ConduitFaceMode defaultFaceMode() {
        return ConduitFaceMode.BOTH;
    }

    @Override
    public String toString() {
        return "ConduitType[" + networkType + "/" + tier + "]";
    }
}
