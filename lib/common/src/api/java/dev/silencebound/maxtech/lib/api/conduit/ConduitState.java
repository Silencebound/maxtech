package dev.silencebound.maxtech.lib.api.conduit;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Server-side state one conduit keeps in its block entity and saves with it. Networks are not persisted, so transport
 * state that must survive a save or a chunk unload, such as the parts mounted in a cable, lives here and the network
 * logic re-reads it when the node loads. Created by {@link ConduitType#createState()} for every conduit of that type,
 * including on the client, where it stays empty: it is never synced. Server thread only.
 */
public interface ConduitState {
    /**
     * Writes everything that must survive a save or chunk unload; called when the block entity saves. Server thread.
     */
    void save(ValueOutput output);

    /**
     * Reads what {@link #save} wrote. Called once on a fresh state, normally before the block entity is in a level;
     * missing entries mean an empty state, never an error.
     */
    void load(ValueInput input);

    /**
     * The conduit left the level for good: its block was broken or replaced. Never called for a chunk unload, whose
     * save keeps the state. Drop whatever the conduit holds; server only.
     */
    default void onRemoved(ServerLevel level, BlockPos pos) {
    }
}
