package dev.silencebound.maxtech.storage.world;

import dev.silencebound.maxtech.lib.api.security.Owned;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchUnlocks;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * One world device's research gate: the device works only while its owner's team has the unlock (an unowned device only
 * when research is off), as the machines and tier-2 ports do. Gating work rather than crafting or placement covers
 * every way a device gets into the world. The answer is kept until the owner changes or research completes for the
 * owner's team: a device without the research waits here, and a completed node wakes exactly those devices instead of
 * their polling. Server thread.
 */
public final class WorldGate {
    private static final ReferenceOpenHashSet<WorldGate> WAITING = new ReferenceOpenHashSet<>();

    private final Identifier unlock;
    private final Owned device;
    private final Runnable wake;
    private @Nullable OwnerRecord checkedFor;
    private boolean unlocked;

    /**
     * @param wake
     *            runs when research may have completed for the device's owner (its task should run again)
     */
    public WorldGate(Identifier unlock, Owned device, Runnable wake) {
        this.unlock = unlock;
        this.device = device;
        this.wake = wake;
    }

    /** Hooks the gates into research completion and server shutdown; called once during mod construction. */
    static void init() {
        Research.onCompleted((server, team, node) -> {
            if (WAITING.isEmpty()) {
                return;
            }
            // Copied: a woken device may leave the set before this loop ends.
            for (WorldGate gate : WAITING.toArray(new WorldGate[0])) {
                if (Research.inTeam(server, gate.device.ownerRecord(), team)) {
                    gate.checkedFor = null;
                    WAITING.remove(gate);
                    gate.wake.run();
                }
            }
        });
        Platform.get().lifecycle().onServerStopped(server -> WAITING.clear());
    }

    /** The unlock id this gate checks. */
    public Identifier unlock() {
        return unlock;
    }

    /** Whether the owner's team has the unlock; asks research only when the owner changed or research completed. */
    public boolean open(ServerLevel level) {
        OwnerRecord owner = device.ownerRecord();
        if (!owner.equals(checkedFor)) {
            checkedFor = owner;
            unlocked = ResearchUnlocks.isUnlocked(level, owner, unlock);
            if (unlocked) {
                WAITING.remove(this);
            } else {
                WAITING.add(this);
            }
        }
        return unlocked;
    }

    /** The last answer without asking again; true until first asked. */
    public boolean lastAnswer() {
        return checkedFor == null || unlocked;
    }

    /** The device is gone: it no longer waits. */
    public void removed() {
        WAITING.remove(this);
    }

    /** Devices waiting for research; for tests. */
    public static int waiting() {
        return WAITING.size();
    }
}
