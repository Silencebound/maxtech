package dev.silencebound.maxtech.lib.schedule;

/** Which queue a task waits in; a group runs its player queue first each tick. */
public enum TaskPriority {
    /** Work a player is waiting for: a terminal request, a craft just asked for. */
    PLAYER,
    /** Everything else: ports, buses, stock keeping. */
    BACKGROUND
}
