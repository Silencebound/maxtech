package dev.silencebound.maxtech.lib.conduit;

import dev.silencebound.maxtech.lib.api.conduit.ConduitState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * A {@link ConduitState} that clients see as well: things that sit in the conduit's block space and must be drawn and
 * hit there, such as Storage's cable parts. Unlike a plain state it is bound to its block entity, and a part of it
 * travels with the block entity's update packet ({@link #saveSynced}). Its {@link #shape()} joins the conduit's hit
 * shape and its {@link #renderKey()} reaches the block's model as {@link ConduitBlockEntity#STATE_RENDER_DATA}.
 *
 * <p>
 * The state calls {@link ConduitBlockEntity#syncedStateChanged()} on the server after changing anything clients see.
 * Everything here runs on the thread that owns the block entity (the server or the client main thread).
 */
public interface SyncedConduitState extends ConduitState {
    /**
     * Binds the state to the block entity that holds it; called once after the state is created or loaded, before the
     * block entity is in a level.
     */
    void bind(ConduitBlockEntity host);

    /** Writes what clients need, both to saves and to the update packet. */
    void saveSynced(ValueOutput output);

    /**
     * Reads what {@link #saveSynced} wrote, on both sides; on the server after {@link #load}. Missing entries mean an
     * empty state.
     */
    void loadSynced(ValueInput input);

    /**
     * An immutable, value-compared description of what the state looks like, or {@code null} when it draws nothing.
     * Read on the client main thread when the model data is refreshed; chunk meshing reads the snapshot.
     */
    @Nullable
    Object renderKey();

    /**
     * The extra hit shape inside the block, {@link Shapes#empty()} for none. Cache it: it is read on every hit test.
     */
    VoxelShape shape();
}
