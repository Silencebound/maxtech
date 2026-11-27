package dev.silencebound.maxtech.lib.api.conduit;

import java.util.Optional;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.ApiStatus;

/**
 * The conduit in one of Lib's conduit blocks, with what its network logic and tools may change. Lib owns the block
 * entity: it saves and syncs everything here and recomputes links on neighbour changes, so addons never write to the
 * block entity themselves.
 * <p>
 * The mutators act on the server thread only and do nothing on the client (returning false or empty). None of them may
 * be called from inside capability invalidation or while Lib's network graph runs a callback that forbids topology
 * changes ({@code NetworkLogic}). Get one with {@link Conduits#host}.
 * <p>
 * Experimental (Lib 0.14.0, for Farms, FM1) until Farms, its first addon user, settles it.
 */
@ApiStatus.Experimental
public interface ConduitHost extends ConduitView {
    /**
     * Sets the sides that face a block this conduit can transfer with ({@link #blockFaces()}), if the conduit joins
     * {@code networkType}; only that network type's logic should call this. Draws arms and rings towards those sides,
     * and syncs only when the mask changed.
     */
    void setBlockFaces(Identifier networkType, int blockFaces);

    /**
     * Sets one face. Towards a conduit it could link with a face takes only {@link ConduitFaceMode#BOTH} or
     * {@link ConduitFaceMode#DISABLED}; towards anything else only the type's {@link ConduitType#supportedFaceModes()}.
     * Cutting or restoring a link is a topology change; any other change has the network re-read the node. Returns
     * whether the mode changed.
     */
    boolean setFaceMode(Direction side, ConduitFaceMode mode);

    /**
     * Moves one face to the next mode in the wrench's cycle ({@link ConduitFaceMode#next}) under the rules of
     * {@link #setFaceMode}. Returns the new mode, or empty if the face could not change (or on the client).
     */
    Optional<ConduitFaceMode> cycleFace(Direction side);

    /**
     * Recomputes the links of this conduit and its loaded neighbours, asking their {@link ConduitConnectionRule}s
     * again. Call after something a rule reads changed, such as the conduit's {@link ConduitState}; Lib does it itself
     * on neighbour changes, dyes and face mode changes.
     */
    void refreshConnections();

    /**
     * The conduit's state changed something its network or clients see: rebuilds the hit shape, marks the block entity
     * for saving, has the network re-read the node and sends the block entity to clients.
     */
    void syncedStateChanged();

    /**
     * Sets the value the conduit's client model reads ({@link #renderValue()}); what it means is up to the addon. A
     * change saves the conduit and sends it to clients, which re-mesh it; setting the value it already has does
     * nothing, so a network may call this whenever it recomputes. The client model's part cache keys on the value, so
     * keep the set of distinct values small (a palette, not a counter). Server thread; does nothing on the client.
     */
    void setRenderValue(int renderValue);

    /**
     * Sets what the conduit shows inside it ({@link #contents()}): resource, fill step, upstream side and second lane.
     * A change saves the conduit (the fluid by registry name) and sends it to clients, which re-mesh it; setting the
     * contents it already has does nothing, so a network may call this whenever it recomputes. The client model's part
     * cache keys on the contents, so a network should change them only when the look changes (a fill step, not every
     * amount). {@link ConduitContents#EMPTY} clears them. Server thread; does nothing on the client.
     */
    void setContents(ConduitContents contents);

    /** Hit shape of the conduit (core, arms, stubs and rings at the type's thickness); cached, never null. */
    VoxelShape shape();

    /**
     * The face a hit targets (ADR-0005): the side of the arm or stub that was hit or, for a hit on the core, the
     * clicked side of the core. With a facade the hit should be traced against {@link #shape()} first. Any thread that
     * owns the block entity.
     */
    Direction faceAt(BlockHitResult hit);
}
