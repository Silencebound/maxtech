package dev.silencebound.maxtech.storage.machine;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.function.Predicate;

/**
 * The machines sleeping in {@link MachineState#RESEARCH_NEEDED}, so research can wake exactly those when a team
 * completes a node instead of machines polling for it. A machine enters when it starts waiting and leaves when its
 * state changes or it is removed (broken or unloaded). Server thread.
 */
public final class ResearchWaits {
    private static final ReferenceOpenHashSet<StorageMachineBlockEntity> WAITING = new ReferenceOpenHashSet<>();

    private ResearchWaits() {
    }

    static void waiting(StorageMachineBlockEntity machine, boolean waiting) {
        if (waiting) {
            WAITING.add(machine);
        } else {
            WAITING.remove(machine);
        }
    }

    /** Wakes every waiting machine whose owner {@code owners} accepts; they look at their research again. */
    public static void wake(Predicate<OwnerRecord> owners) {
        if (WAITING.isEmpty()) {
            return;
        }
        // Copied: a woken machine may leave the set before this loop ends.
        for (StorageMachineBlockEntity machine : WAITING.toArray(new StorageMachineBlockEntity[0])) {
            if (owners.test(machine.ownerRecord())) {
                machine.wake();
            }
        }
    }

    /** Forgets every waiting machine; called when the server stops, so no machine outlives its world here. */
    public static void clear() {
        WAITING.clear();
    }

    /** How many machines wait for research; for tests. */
    public static int count() {
        return WAITING.size();
    }
}
