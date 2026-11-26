package dev.silencebound.maxtech.storage.controller;

import net.minecraft.util.StringRepresentable;

/**
 * What the controller block shows: its block state property, so the look and light change only when the network's state
 * does, never per tick.
 */
public enum ControllerStatus implements StringRepresentable {
    /** In no working network, or without the energy for an operation. Dark. */
    OFF("off", 0),
    /** The network works and has power. The face's grooves glow cyan and the block lights its surroundings. */
    ONLINE("online", 10),
    /** Two controllers, or a structure the network refuses. The face's grooves glow red. */
    ERROR("error", 6);

    private final String name;
    private final int light;

    ControllerStatus(String name, int light) {
        this.name = name;
        this.light = light;
    }

    /** The block light level in this status. */
    public int light() {
        return light;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
