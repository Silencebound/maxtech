package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What a {@link NetworkDevice} keeps for its network: the network it is in, its scheduled {@link StorageTask}s and its
 * {@link KeyListener}s, which all move with it between networks, and its own {@link StorageTransfer}. Block devices and
 * cable parts each hold one. Server thread only.
 */
public final class DeviceLink {
    final NetworkDevice device;
    @Nullable
    NetworkState network;
    /** The network that turned the device away because it holds its controller's most devices; until it rebuilds. */
    @Nullable
    NetworkState refusedBy;
    /** The network that keeps the device offline because a link on its route has no channel left (M7N). */
    @Nullable
    NetworkState offlineBy;
    /** Why {@link #offlineBy} keeps the device offline; null while it does not. */
    @Nullable
    ChannelShortage shortage;
    /**
     * When the device first joined a network (M7N): a level-wide rising number, saved with the device, so the devices
     * that joined last are the ones that go offline when channels run short, also after a reload. 0 until it joins.
     */
    long joinSeq;
    /** Whether the device holds one channel on every link of its route in {@link #network}. */
    boolean holdsChannel;
    /** The front line's look last pushed to the device ({@link NetworkState#showLink}); null before the first. */
    @Nullable
    LinkLook shownLook;
    /** Every task scheduled for this device, in or out of a network. */
    final ObjectArrayList<TaskAdapter> tasks = new ObjectArrayList<>(1);
    /** Keys listened to, paired by index with {@link #listeners}; in or out of a network. */
    final ObjectArrayList<StackKey> listenedKeys = new ObjectArrayList<>(0);
    final ObjectArrayList<KeyListener> listeners = new ObjectArrayList<>(0);
    private @Nullable LinkTransfer transfer;
    private DeviceLabel label = DeviceLabel.NONE;
    /** Turned off by a logic graph (M7L): the device's tasks sleep; only for {@link NetworkDevice#switchable()}. */
    boolean switchedOff;
    /** The graph that turned the device off, when known; null while on. */
    private @Nullable SwitchSource switchedOffBy;
    /** The power priority a player or graph set (M7L2 load shedding); 0 for the device's default. */
    private int powerPriority;
    /** Paused by its network's load shedding: the device's tasks sleep like {@link #switchedOff}. Not saved. */
    boolean powerPaused;
    /** Kept from work by its redstone mode (B09 item 9): the device's tasks sleep like {@link #switchedOff}. */
    boolean redstoneOff;
    /** The device's index in its network's {@link LoadShedding} list, -1 while not listed. */
    int shedSlot = -1;
    /** Server time of the device's tasks in the running or latest {@link NetworkProfiler} session. */
    final ProfileTally profile = new ProfileTally();

    public DeviceLink(NetworkDevice device) {
        this.device = device;
    }

    /** The device's label; {@link DeviceLabel#NONE} without one. */
    public DeviceLabel label() {
        return label;
    }

    /**
     * Sets the device's label and moves the device in its network's label index ({@link NetworkLabels}); kept while the
     * device is in no network and indexed when it joins one. Returns whether it changed; devices save it through
     * {@link NetworkDevice#setLabel}.
     */
    public boolean setLabel(DeviceLabel next) {
        DeviceLabel previous = label;
        if (previous.equals(next)) {
            return false;
        }
        label = next;
        NetworkState current = network;
        if (current != null) {
            current.relabel(device, previous, next);
        }
        return true;
    }

    /** Whether a logic graph turned the device off (M7L); always false for devices that are not switchable. */
    public boolean switchedOff() {
        return switchedOff;
    }

    /** The graph that turned the device off, when known; empty while on or when it came off another way. */
    public Optional<SwitchSource> switchedOffBy() {
        return Optional.ofNullable(switchedOffBy);
    }

    /**
     * Turns the device off or on for logic with no known source; see {@link #setSwitchedOff(boolean, SwitchSource)}.
     */
    public boolean setSwitchedOff(boolean off) {
        return setSwitchedOff(off, null);
    }

    /**
     * Turns the device off or on for logic (M7L), off by {@code by} when known (kept until it is on again). Off, its
     * scheduled tasks sleep (the scheduler skips them) and the device refuses work of its own; on again, every task
     * wakes. Devices that are not {@link NetworkDevice#switchable()} stay on. Tells the network's device watchers when
     * the state changes, so graphs reading the device's state see it. Returns whether the state or its source changed;
     * devices save it through {@link NetworkDevice#setSwitchedOff}.
     */
    public boolean setSwitchedOff(boolean off, @Nullable SwitchSource by) {
        boolean next = off && device.switchable();
        SwitchSource source = next ? by : null;
        if (next == switchedOff) {
            if (Objects.equals(source, switchedOffBy)) {
                return false;
            }
            switchedOffBy = source;
            return true;
        }
        switchedOff = next;
        switchedOffBy = source;
        if (!next && !powerPaused && !redstoneOff) {
            wakeAll();
        }
        if (!powerPaused && !redstoneOff) {
            device.stoppedChanged(next);
        }
        NetworkState current = network;
        if (current != null) {
            current.deviceStateChanged();
        }
        return true;
    }

    /**
     * Whether the device does no work now: turned off by logic, paused by load shedding or kept off by its redstone
     * mode. Every work path of a device checks this, and the scheduler skips its tasks.
     */
    public boolean stopped() {
        return switchedOff || powerPaused || redstoneOff;
    }

    /** Whether the device's redstone mode keeps it from working (B09 item 9). */
    public boolean redstoneOff() {
        return redstoneOff;
    }

    /**
     * Keeps the device from working, or lets it work again, for its redstone mode (B09 item 9; the device's
     * {@code RedstoneControl} decides). Off, its tasks sleep like a device turned off by logic; on again, every task
     * wakes. Not saved here: the device saves its mode and reading. Returns whether it changed.
     */
    public boolean setRedstoneOff(boolean off) {
        if (off == redstoneOff) {
            return false;
        }
        redstoneOff = off;
        if (!off && !switchedOff && !powerPaused) {
            wakeAll();
        }
        if (!switchedOff && !powerPaused) {
            device.stoppedChanged(off);
        }
        return true;
    }

    /** Whether the network's load shedding paused the device (M7L2); false while it is in no network. */
    public boolean powerPaused() {
        return powerPaused;
    }

    /**
     * The device's power priority ({@link PowerPriority}): the one set, else the device's default. Always
     * {@link PowerPriority#ESSENTIAL} for devices that are not {@link NetworkDevice#sheddable()}.
     */
    public int powerPriority() {
        if (!device.sheddable()) {
            return PowerPriority.ESSENTIAL;
        }
        return powerPriority != 0 ? powerPriority : PowerPriority.clamp(device.defaultPowerPriority());
    }

    /**
     * Sets the device's power priority (clamped to 1 to 5); its network pauses or resumes it at once by its current
     * shed level. Ignored for devices that are not sheddable. Returns whether it changed; devices save it through
     * {@link NetworkDevice#setPowerPriority}.
     */
    public boolean setPowerPriority(int priority) {
        if (!device.sheddable()) {
            return false;
        }
        int before = powerPriority();
        int next = PowerPriority.clamp(priority);
        powerPriority = next == PowerPriority.clamp(device.defaultPowerPriority()) ? 0 : next;
        if (next == before) {
            return false;
        }
        NetworkState current = network;
        if (current != null) {
            current.repriority(this, before);
        }
        return true;
    }

    /** The priority set, 0 for the device's default; what devices save. */
    public int savedPowerPriority() {
        return powerPriority;
    }

    /** Takes what {@link #savedPowerPriority()} gave back: 0 (or nothing saved) is the device's default. */
    public boolean loadPowerPriority(int saved) {
        return setPowerPriority(saved <= 0 ? device.defaultPowerPriority() : saved);
    }

    /**
     * Pauses or resumes the device for load shedding; resumed, every task wakes. The network tells its device watchers
     * once per level change, not once per device.
     */
    void setPowerPaused(boolean paused) {
        if (paused == powerPaused) {
            return;
        }
        powerPaused = paused;
        if (!paused && !switchedOff && !redstoneOff) {
            wakeAll();
        }
        if (!switchedOff && !redstoneOff) {
            device.stoppedChanged(paused);
        }
    }

    /** The network the device is in, or {@code null} while it is in none (just placed, or between rebuilds). */
    public @Nullable NetworkState network() {
        return network;
    }

    /**
     * The network that refused the device because it is full ({@link NetworkState#deviceLimit()}), or {@code null}. The
     * network tries the device again when a device leaves it.
     */
    public @Nullable NetworkState refusedBy() {
        return refusedBy;
    }

    /**
     * The network that keeps the device offline because a cable, link, root link or the allowance of a network without
     * a controller has no channel left for it (M7N), or {@code null}. The device is in no network meanwhile; the
     * network tries it again when channels free up or the topology changes.
     */
    public @Nullable NetworkState offlineBy() {
        return offlineBy;
    }

    /**
     * Whether the device shows "no channel" (its front line and its status): a network keeps it offline or refused it,
     * and that network counts channels with limits. Without limits ({@link ChannelMode#INFINITE}) a device turned away
     * for its zone or a device cap shows offline instead. Server thread.
     */
    public boolean noChannel() {
        NetworkState by = offlineBy != null ? offlineBy : refusedBy;
        return by != null && !by.channelsInfinite();
    }

    /** Why the device is offline for channels; empty while it is not. */
    public Optional<ChannelShortage> channelShortage() {
        return offlineBy == null ? Optional.empty() : Optional.ofNullable(shortage);
    }

    /** The device's join order for channels; 0 before it first joined a network. What devices save. */
    public long joinSeq() {
        return joinSeq;
    }

    /** Takes a saved {@link #joinSeq()}; 0 (nothing saved) leaves the next join to number the device. */
    public void loadJoinSeq(long saved) {
        joinSeq = Math.max(0, saved);
    }

    /**
     * The look of the line on the device's front as last shown ({@link LinkLook}); offline before its network first
     * worked it out.
     */
    public LinkLook linkLook() {
        LinkLook shown = shownLook;
        return shown == null ? LinkLook.OFFLINE : shown;
    }

    /** Whether the device holds a channel along its route now; false while routes wait for a rebuild. */
    public boolean holdsChannel() {
        return holdsChannel;
    }

    /** Tells the network that the device's patterns changed (a pattern went in or out of a provider). */
    public void patternsChanged() {
        NetworkState current = network;
        if (current != null) {
            current.repattern(device);
        }
    }

    /**
     * Tells the network that the device's providers changed (a cell went in or out): it drops the old ones from its
     * index and adds the new ones.
     */
    public void providersChanged() {
        NetworkState current = network;
        if (current != null) {
            current.reindex(device);
        }
    }

    /**
     * Hands {@code task} to the device's network's scheduler, now or when the device joins one; it runs on the next
     * pass. Scheduling a task again only wakes it. Tasks move with the device on merges and splits and leave the
     * scheduler when it leaves; they belong to this link, so a reloaded device schedules them again.
     */
    public void schedule(StorageTask task) {
        TaskAdapter adapter = adapter(task);
        if (adapter == null) {
            adapter = new TaskAdapter(this, task);
            tasks.add(adapter);
        }
        NetworkState current = network;
        if (current != null) {
            current.group().add(adapter);
        }
    }

    /** Takes {@code task} out of the scheduler for good. */
    public void cancel(StorageTask task) {
        TaskAdapter adapter = adapter(task);
        if (adapter != null) {
            tasks.remove(adapter);
            TaskAdapter.leave(adapter);
        }
    }

    /** Tasks scheduled for this device and not finished, in or out of a network. */
    public int scheduledTasks() {
        return tasks.size();
    }

    /** Wakes {@code task} for its network's next pass; for events such as a neighbour change. */
    public void wake(StorageTask task) {
        TaskAdapter adapter = adapter(task);
        if (adapter != null) {
            adapter.wake();
        }
    }

    /**
     * The device's way into its network outside its own tasks, for synchronous work such as an interface answering a
     * pipe's insert: each {@code insert} or {@code extract} is one operation exactly as in a task run (one batch at
     * most, energy paid in the caller's transaction, refused when the network cannot take it). Returns null while the
     * device is in no network, which includes a device offline for lack of a channel.
     *
     * <p>
     * The transfer is the device's own, so calling this while another device's task runs (a port exporting into this
     * device) leaves that task's transfer untouched; calling it again, or the device's own task running, rebinds it.
     * Use it within the current call and do not keep it. Server thread only.
     */
    public @Nullable StorageTransfer transfer() {
        NetworkState current = network;
        return current == null ? null : bind(current, false);
    }

    /** The device's transfer bound to {@code state}, for automation or a player's task; see {@link NetworkState}. */
    LinkTransfer bind(NetworkState state, boolean player) {
        LinkTransfer current = transfer;
        if (current == null) {
            current = new LinkTransfer(this);
            transfer = current;
        }
        return current.bind(state, player);
    }

    /** Wakes every task of this device; a surge regulator lets them run again once the network has energy. */
    void wakeAll() {
        for (int i = 0; i < tasks.size(); i++) {
            tasks.get(i).wake();
        }
    }

    /**
     * Calls {@code listener} whenever {@code key}'s total in the device's network changes, and once for {@code key}
     * whenever the device joins a network; see {@link KeyListener}. The subscription moves with the device on merges
     * and splits and stays while it is in no network; it is not called now. Listening to the same key with the same
     * listener twice adds it once. Not from inside a {@link KeyListener} call.
     */
    // Listeners are the device's own objects, compared by identity like tasks.
    @SuppressWarnings("ReferenceEquality")
    public void listen(StackKey key, KeyListener listener) {
        for (int i = 0; i < listeners.size(); i++) {
            if (listeners.get(i) == listener && listenedKeys.get(i).equals(key)) {
                return;
            }
        }
        listenedKeys.add(key);
        listeners.add(listener);
        NetworkState current = network;
        if (current != null) {
            current.listenKey(key, listener);
        }
    }

    /** Stops calling {@code listener} for {@code key}; nothing happens if it was not listening. */
    @SuppressWarnings("ReferenceEquality") // as in listen
    public void unlisten(StackKey key, KeyListener listener) {
        for (int i = listeners.size() - 1; i >= 0; i--) {
            if (listeners.get(i) == listener && listenedKeys.get(i).equals(key)) {
                listeners.remove(i);
                listenedKeys.remove(i);
                NetworkState current = network;
                if (current != null) {
                    current.unlistenKey(key, listener);
                }
            }
        }
    }

    // A task is the device's own object; scheduling the same one twice must find it, whatever its equals says.
    @SuppressWarnings("ReferenceEquality")
    private @Nullable TaskAdapter adapter(StorageTask task) {
        for (int i = 0; i < tasks.size(); i++) {
            TaskAdapter adapter = tasks.get(i);
            if (adapter.task == task) {
                return adapter;
            }
        }
        return null;
    }
}
