package dev.silencebound.maxtech.storage.priority;

import net.minecraft.world.entity.player.Player;

/**
 * A menu whose screen has the priority tab. It syncs the priority with {@link PriorityControl#sync} and takes the
 * page's {@link PriorityPayloads.Set} through {@link #applyPriority}.
 */
public interface PriorityMenu {
    /** The priority as last synced; on the server the block's own. */
    int priority();

    /**
     * Sets the block's priority after checking that {@code player} may configure it; false, changing nothing, when the
     * block is gone or the player may not. Server side.
     */
    boolean applyPriority(Player player, int priority);

    /** The lowest priority the block takes; the page limits its field to it. */
    default int minPriority() {
        return PriorityControl.MIN;
    }

    /** The highest priority the block takes. */
    default int maxPriority() {
        return PriorityControl.MAX;
    }
}
