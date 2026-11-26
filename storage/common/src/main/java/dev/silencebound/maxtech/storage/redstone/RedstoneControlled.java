package dev.silencebound.maxtech.storage.redstone;

/** A device with a redstone mode (B09 item 9): ports, IO ports and interfaces. Server thread. */
public interface RedstoneControlled {
    /** The device's redstone mode and last reading. */
    RedstoneControl redstone();
}
