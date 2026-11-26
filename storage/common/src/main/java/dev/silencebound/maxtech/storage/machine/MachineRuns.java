package dev.silencebound.maxtech.storage.machine;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * Tells listeners about every finished run of a lithograph, fabricator or assembler, so research can count machine runs
 * and what they made without the machines knowing about research. Server thread.
 */
public final class MachineRuns {
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private MachineRuns() {
    }

    /** Hears what a finished run made. */
    @FunctionalInterface
    public interface Listener {
        /**
         * {@code machine} (owned by {@code owner}, possibly unowned) finished a run in {@code mode} and put
         * {@code made} into its outputs. The stacks are the run's own; read them, don't keep or change them.
         */
        void finished(ServerLevel level, StorageMachineBlockEntity machine, OwnerRecord owner, MachineKind kind,
                Optional<String> mode, List<ItemStack> made);
    }

    /** Adds a listener; call during mod construction or setup. */
    public static void listen(Listener listener) {
        LISTENERS.add(listener);
    }

    static boolean anyListener() {
        return !LISTENERS.isEmpty();
    }

    static void fire(ServerLevel level, StorageMachineBlockEntity machine, Optional<String> mode,
            List<ItemStack> made) {
        for (Listener listener : LISTENERS) {
            listener.finished(level, machine, machine.ownerRecord(), machine.kind(), mode, made);
        }
    }
}
