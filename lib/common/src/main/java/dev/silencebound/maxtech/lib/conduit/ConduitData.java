package dev.silencebound.maxtech.lib.conduit;

import dev.silencebound.maxtech.lib.api.conduit.ConduitConnections;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceMode;
import dev.silencebound.maxtech.lib.api.conduit.ConduitFaceModes;
import dev.silencebound.maxtech.lib.api.conduit.ConduitState;
import dev.silencebound.maxtech.lib.api.conduit.ConduitType;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import org.jspecify.annotations.Nullable;

/**
 * State of the conduit in a conduit block: what rendering needs, plus the type's optional {@link ConduitState}. Network
 * logic keeps its caches in the network; only state that must survive saves and chunk unloads goes into the
 * {@link #state()}.
 *
 * <p>
 * Four side masks (one bit per {@link Direction#get3DDataValue()}) describe how the conduit meets its neighbours
 * (ADR-0005). {@link #connections()} are live links to a neighbouring conduit and drive the network graph.
 * {@link #conduitFaces()} are sides where a conduit it could link with sits, linked or cut by a disabled face.
 * {@link #blockFaces()} are sides where a block the conduit can transfer with sits; only the network logic sets them.
 * {@link #faceModes()} packs one {@link ConduitFaceMode} per side, see {@link ConduitFaceModes}.
 */
public final class ConduitData {
    private final ConduitType type;
    private final @Nullable ConduitState state;
    private @Nullable DyeColor colour;
    private int connections;
    private int conduitFaces;
    private int blockFaces;
    private int faceModes;

    ConduitData(ConduitType type, @Nullable DyeColor colour, int connections) {
        this(type, colour, connections, connections, 0, ConduitFaceModes.all(type.defaultFaceMode()));
    }

    ConduitData(ConduitType type, @Nullable DyeColor colour, int connections, int conduitFaces, int blockFaces,
            int faceModes) {
        this.type = type;
        this.state = type.createState().orElse(null);
        this.colour = colour;
        this.connections = connections & ConduitConnections.ALL_SIDES;
        this.conduitFaces = (conduitFaces | connections) & ConduitConnections.ALL_SIDES;
        this.blockFaces = blockFaces & ConduitConnections.ALL_SIDES;
        this.faceModes = ConduitFaceModes.sanitise(type, faceModes, this.conduitFaces);
    }

    public ConduitType type() {
        return type;
    }

    /** The saved per-conduit state from {@link ConduitType#createState()}, or {@code null} if the type keeps none. */
    public @Nullable ConduitState state() {
        return state;
    }

    /** The dye colour, or {@code null} when uncoloured. */
    public @Nullable DyeColor colour() {
        return colour;
    }

    /** Sides linked to a neighbouring conduit; these are the network graph's edges. */
    public int connections() {
        return connections;
    }

    /** Sides facing a conduit this one could link with, whether the link is live or cut by a disabled face. */
    public int conduitFaces() {
        return conduitFaces;
    }

    /** Sides facing a block this conduit can transfer with, as last seen by its network logic. */
    public int blockFaces() {
        return blockFaces;
    }

    /** Every side's mode, packed as {@link ConduitFaceModes} describes. */
    public int faceModes() {
        return faceModes;
    }

    public ConduitFaceMode faceMode(Direction side) {
        return ConduitFaceModes.get(faceModes, side);
    }

    /** Sides that draw an arm or a stub: links plus faces towards blocks. */
    public int renderMask() {
        return connections | blockFaces;
    }

    /** Disabled faces that face something, drawn as a short stub instead of an arm. */
    public int stubMask() {
        return ConduitFaceModes.sidesWith(faceModes, ConduitFaceMode.DISABLED) & (blockFaces | conduitFaces);
    }

    /** Sides that draw a full arm. */
    public int armMask() {
        return renderMask() & ~ConduitFaceModes.sidesWith(faceModes, ConduitFaceMode.DISABLED);
    }

    /** Arms that end at a block and carry a connector ring coloured by the face's mode. */
    public int ringMask() {
        return armMask() & blockFaces & ~connections;
    }

    void setColour(@Nullable DyeColor colour) {
        this.colour = colour;
    }

    void setConnections(int connections) {
        this.connections = connections;
    }

    void setConduitFaces(int conduitFaces) {
        this.conduitFaces = conduitFaces;
    }

    void setBlockFaces(int blockFaces) {
        this.blockFaces = blockFaces & ConduitConnections.ALL_SIDES;
    }

    void setFaceMode(Direction side, ConduitFaceMode mode) {
        this.faceModes = ConduitFaceModes.with(faceModes, side, mode);
    }
}
