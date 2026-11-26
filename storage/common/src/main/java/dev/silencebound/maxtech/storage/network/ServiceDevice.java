package dev.silencebound.maxtech.storage.network;

/**
 * A device that gives its network a {@link NetworkService} (M7R item 4). The network lists it per service while it is a
 * member (it holds a channel like any device, unless it is {@link ChannelFree}). Server thread only.
 */
public interface ServiceDevice {
    /** The service this device gives; must not change while the block stays. */
    NetworkService service();

    /**
     * The network's controller in charge changed (one joined, left or another took over) while this device is a member:
     * a device whose service needs a working controller looks again. Does nothing by default.
     */
    default void controllerChanged() {
    }
}
