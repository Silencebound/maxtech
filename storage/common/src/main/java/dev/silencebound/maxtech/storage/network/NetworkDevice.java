package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.api.security.Owned;
import dev.silencebound.maxtech.storage.api.StorageDevice;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A storage device as its network sees it: a device block ({@link StorageDeviceBlockEntity}) or a part on a storage
 * cable's face. Several devices can sit on one graph node (a cable with parts); each joins and leaves its network on
 * its own, keeps its tasks in its {@link DeviceLink} and holds one channel along the route of its {@link #nodePos()}
 * ({@link ChannelFree} devices hold none). Server thread only.
 */
public interface NetworkDevice extends StorageDevice, Owned {
    /** The device's network membership and scheduled tasks. */
    DeviceLink deviceLink();

    /** The graph node the device sits on: its own block, or the cable a part is on. */
    long nodePos();

    /**
     * The block the device belongs to: {@link #nodePos()} for most, the neighbouring block for a device attached to a
     * node from outside (a subnet controller's uplink), so the cable there shows a stub towards that block.
     */
    default long blockPos() {
        return nodePos();
    }

    /**
     * The providers this device adds to its network's storage index; empty for devices that hold nothing. Read when the
     * device joins a network and after {@link DeviceLink#providersChanged()}. The list must not change while the
     * network holds it; return a new list instead.
     */
    List<StorageProvider> providers();

    /**
     * What the device shows at a glance in overlays after its status ({@link NetworkInfo#device}): at most two entries,
     * its most important number first (a bar, an icon with a short text, pips) and then, if any, item icons. Nothing by
     * default. Called for both detail levels, so it must be O(1) or close: read cached values only.
     */
    default void addGlance(BlockInfoRequest request, BlockInfoSink sink) {
    }

    /**
     * The device's own details for {@link BlockInfoRequest.Detail#FULL}, after its label and before its network.
     * Nothing by default.
     */
    default void addDetails(BlockInfoRequest request, BlockInfoSink sink) {
    }

    /** The device's label (M7L), which logic graphs address it by; {@link DeviceLabel#NONE} without one. */
    default DeviceLabel label() {
        return deviceLink().label();
    }

    /**
     * Sets the device's label and updates its network's label index. Block entities and cable parts override this to
     * save the change; other devices keep a label only while loaded. Server thread.
     */
    default void setLabel(DeviceLabel label) {
        deviceLink().setLabel(label);
    }

    /**
     * Whether a logic graph can turn the device off (M7L): devices that do work (ports, interfaces, pattern providers,
     * sensors). Storage (drives, bulk drives, chests) and the network's own blocks stay on, so their contents stay
     * available. Must not change while the device exists. False by default.
     */
    default boolean switchable() {
        return false;
    }

    /** Whether a logic graph turned the device off; see {@link DeviceLink#setSwitchedOff}. */
    default boolean switchedOff() {
        return deviceLink().switchedOff();
    }

    /** The graph that turned the device off, when known; see {@link DeviceLink#switchedOffBy}. */
    default Optional<SwitchSource> switchedOffBy() {
        return deviceLink().switchedOffBy();
    }

    /** Turns the device off or on for logic with no known source. Server thread. */
    default void setSwitchedOff(boolean off) {
        setSwitchedOff(off, null);
    }

    /**
     * Turns the device off or on for logic, off by the graph {@code by} when known. Block entities and cable parts
     * override this to save the change, like the label. Server thread.
     */
    default void setSwitchedOff(boolean off, @Nullable SwitchSource by) {
        deviceLink().setSwitchedOff(off, by);
    }

    /**
     * Whether load shedding can pause the device (M7L2 row 21): devices logic can switch off, and displays. Everything
     * else is essential and never pauses, so storage, terminals and the network's own blocks stay available. Must not
     * change while the device exists.
     */
    default boolean sheddable() {
        return switchable();
    }

    /**
     * The device's power priority until a player or graph sets one ({@link PowerPriority}): normal for devices that do
     * work, optional for displays. Read only for {@link #sheddable()} devices; must not change while the device exists.
     */
    default int defaultPowerPriority() {
        return PowerPriority.NORMAL;
    }

    /** The device's power priority; see {@link DeviceLink#powerPriority()}. */
    default int powerPriority() {
        return deviceLink().powerPriority();
    }

    /**
     * Sets the device's power priority ({@link DeviceLink#setPowerPriority}). Block entities and cable parts override
     * this to save the change, like the label. Server thread.
     */
    default void setPowerPriority(int priority) {
        deviceLink().setPowerPriority(priority);
    }

    /** Whether the network's load shedding paused the device now. */
    default boolean powerPaused() {
        return deviceLink().powerPaused();
    }

    /**
     * Called when {@link DeviceLink#stopped()} changes: logic switched the device off or on, or load shedding paused or
     * resumed it. For devices whose effect outlives their tasks (a redstone signal) and must stop with them. Server
     * thread; does nothing by default.
     */
    default void stoppedChanged(boolean stopped) {
    }

    /**
     * The look of the accent line on the device's front changed ({@link LinkLook}): the network calls this once per
     * change, after topology, channel or power changes, never per tick. Devices whose front carries the line show it (a
     * block state, a cable part's synced look); does nothing by default. Server thread.
     */
    default void linkLookChanged(LinkLook look) {
    }

    /**
     * Whether a land claim keeps the device from the block it faces, at its last check ({@link FacingAccess}); false
     * for devices that use no faced block. Server thread.
     */
    default boolean claimBlocked() {
        return false;
    }

    /** Whether the device is gone (broken or unloaded); a removed device leaves its network on the next rebuild. */
    boolean isRemoved();
}
