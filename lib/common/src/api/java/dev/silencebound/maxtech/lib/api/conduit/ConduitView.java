package dev.silencebound.maxtech.lib.api.conduit;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;

/**
 * Read-only view of the conduit in one of Lib's conduit blocks: its type, dye colour, side masks, face modes, state and
 * facade. Side masks hold one bit per {@link Direction#get3DDataValue()} ({@link ConduitConnections#bit}).
 * <p>
 * Read on the thread that owns the block entity (the server thread, or the client main thread for a client-side
 * conduit). Nothing here allocates, so network logic and {@link ConduitConnectionRule}s may call it freely. Get one
 * with {@link Conduits#host}.
 * <p>
 * Experimental (Lib 0.14.0, for Farms, FM1) until Farms, its first addon user, settles it.
 */
@ApiStatus.Experimental
public interface ConduitView {
    /** The conduit's type, fixed by its block. */
    ConduitType type();

    /** Where the conduit is. */
    BlockPos pos();

    /** The dye colour, or empty while uncoloured. Conduits of different colours never link. */
    Optional<DyeColor> dyeColour();

    /** Sides linked to a neighbouring conduit; these are the network graph's edges. */
    int connections();

    /** Sides facing a conduit this one could link with, whether the link is live or cut by a disabled face. */
    int conduitFaces();

    /** Sides facing a block this conduit can transfer with, as its network logic last set them. */
    int blockFaces();

    /** Every side's mode, packed as {@link ConduitFaceModes} describes. */
    int faceModes();

    /** The mode of {@code side}'s face. */
    ConduitFaceMode faceMode(Direction side);

    /**
     * The state {@link ConduitType#createState()} made for this conduit, or empty if the type keeps none. It exists on
     * the client too, but only states Lib syncs itself (Storage's cable parts) carry anything there; an addon's state
     * stays as created, since {@link ConduitState} is saved, not synced.
     */
    Optional<ConduitState> findState();

    /** The block the conduit is disguised as, or empty without a facade. A facade is render-only. */
    Optional<BlockState> findFacade();

    /**
     * The value the conduit's addon last set with {@link ConduitHost#setRenderValue}, 0 until then. What it means is up
     * to the addon (Farms' lab tube keeps the colour of its network's medium here); Lib saves it, syncs it to clients
     * and hands it to the client model in the conduit's body key, but its own looks ignore it.
     */
    int renderValue();
}
