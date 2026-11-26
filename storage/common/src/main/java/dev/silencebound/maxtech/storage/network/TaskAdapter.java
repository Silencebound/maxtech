package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.schedule.ScheduledTask;
import dev.silencebound.maxtech.lib.schedule.TaskGroup;
import dev.silencebound.maxtech.lib.schedule.TaskPriority;

/**
 * Runs one device's {@link StorageTask} in its network's Lib {@link TaskGroup}, handing it the network's transfer view
 * bound to the device. The adapter stays with the device, so moving the device to another network is one
 * {@code group.add}. Server thread only.
 */
final class TaskAdapter extends ScheduledTask {
    final DeviceLink link;
    final StorageTask task;

    TaskAdapter(DeviceLink link, StorageTask task) {
        super(task.playerPriority() ? TaskPriority.PLAYER : TaskPriority.BACKGROUND);
        this.link = link;
        this.task = task;
    }

    @Override
    protected int run(TaskGroup group, long gameTime) {
        NetworkState network = link.network;
        if (network == null || network.group() != group || network.quarantined()) {
            // A quarantined network's tasks sleep until the tag is lifted, which wakes them.
            return WAIT;
        }
        if (link.switchedOff || link.redstoneOff || (link.powerPaused && !task.runsWhileShed())) {
            // Turned off by logic or redstone, or paused by load shedding: sleeps until it is on again, which wakes
            // every task.
            return WAIT;
        }
        int next;
        if (NetworkProfiler.on) {
            long started = System.nanoTime();
            next = task.run(network.transfer(link.device, task.playerPriority()), gameTime);
            long elapsed = System.nanoTime() - started;
            link.profile.add(elapsed);
            network.profile.add(elapsed);
        } else {
            next = task.run(network.transfer(link.device, task.playerPriority()), gameTime);
        }
        if (next < WAIT) {
            link.tasks.remove(this);
        }
        return next;
    }

    /** Takes the adapter out of whatever group holds it. */
    static void leave(TaskAdapter adapter) {
        TaskGroup group = adapter.group();
        if (group != null) {
            group.remove(adapter);
        }
    }
}
