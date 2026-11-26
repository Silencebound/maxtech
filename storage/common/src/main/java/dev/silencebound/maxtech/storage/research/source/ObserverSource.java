package dev.silencebound.maxtech.storage.research.source;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.machine.MachineKind;
import dev.silencebound.maxtech.storage.machine.StorageMachineBlockEntity;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Hands every finished machine run to the observers facing that machine. A run is rare next to ticks, so looking at the
 * machine's six neighbours then is cheaper than keeping an index of observers in step with placing, breaking and chunk
 * loading; neighbours in chunks that are not fully loaded are skipped, never loaded. Server thread.
 */
final class ObserverSource {
    private static final Direction[] DIRECTIONS = Direction.values();

    private ObserverSource() {
    }

    /** A {@link dev.silencebound.maxtech.storage.machine.MachineRuns} listener. */
    static void finished(ServerLevel level, StorageMachineBlockEntity machine, OwnerRecord owner, MachineKind kind,
            Optional<String> mode, List<ItemStack> made) {
        BlockPos pos = machine.getBlockPos();
        // A local cursor: awarding points can complete research, and its listeners must not see a shared one move.
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction side : DIRECTIONS) {
            cursor.setWithOffset(pos, side);
            LevelChunk chunk = level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(cursor.getX()),
                    SectionPos.blockToSectionCoord(cursor.getZ()));
            if (chunk == null) {
                continue;
            }
            BlockState state = chunk.getBlockState(cursor);
            // The observer watches the block in front of it, which is the machine when it faces back across this side.
            if (state.is(SourceContent.MACHINE_OBSERVER.get())
                    && state.getValue(MachineObserverBlock.FACING) == side.getOpposite()
                    && chunk.getBlockEntity(cursor) instanceof MachineObserverBlockEntity observer) {
                observer.machineRan(level);
            }
        }
    }
}
