package dev.silencebound.maxtech.storage.terminal;

import dev.silencebound.maxtech.storage.MaxtechStorage;

/** What a terminal's status line says; sent as a data slot by {@link #index()}. */
public enum TerminalStatus {
    NO_NETWORK(0, "no_network"), ONLINE(1, "online"), NO_POWER(2, "no_power"), STOPPED(3, "stopped"),
    /** A storage chest: its own slots, no network. */
    LOCAL(4, "local");

    private final int index;
    private final String name;

    TerminalStatus(int index, String name) {
        this.index = index;
        this.name = name;
    }

    public int index() {
        return index;
    }

    public String translationKey() {
        return "gui." + MaxtechStorage.MOD_ID + ".terminal.status." + name;
    }

    /** The status with {@code index}; {@link #NO_NETWORK} for an unknown one. */
    public static TerminalStatus byIndex(int index) {
        for (TerminalStatus status : values()) {
            if (status.index == index) {
                return status;
            }
        }
        return NO_NETWORK;
    }
}
