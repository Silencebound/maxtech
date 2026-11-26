package dev.silencebound.maxtech.storage.network;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/**
 * What a player may do with a storage network and its devices (PRD 7.2; a controller's security module sets it per
 * player and team). Each level includes the ones before it.
 */
public enum AccessLevel implements StringRepresentable {
    /** Nothing: terminals and device screens stay closed. */
    NONE("none"),
    /** Open terminals and see the contents. */
    VIEW("view"),
    /** Also put items and fluids in. */
    DEPOSIT("deposit"),
    /** Also request crafting jobs and use pattern terminals (M7). */
    CRAFT("craft"),
    /** Also open a Logic Controller and edit its graphs (M7L). */
    LOGIC("logic"),
    /** Everything: take out, configure devices, break parts out of cables. */
    FULL("full");

    public static final Codec<AccessLevel> CODEC = StringRepresentable.fromEnum(AccessLevel::values);
    /** Sends a level as its {@link #id()}; an unknown id reads as {@link #NONE}. */
    public static final StreamCodec<ByteBuf, AccessLevel> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(AccessLevel::byId,
            AccessLevel::id);
    private static final AccessLevel[] VALUES = values();

    private final String name;

    AccessLevel(String name) {
        this.name = name;
    }

    /** Whether this level includes {@code other}. */
    public boolean atLeast(AccessLevel other) {
        return compareTo(other) >= 0;
    }

    /** The next level, wrapping from {@link #FULL} to {@link #NONE}; for cycling buttons. */
    public AccessLevel next() {
        return byId((id() + 1) % VALUES.length);
    }

    /** A small number for sending: 0 to 5. Not saved; saved levels use their names. */
    public int id() {
        return switch (this) {
            case NONE -> 0;
            case VIEW -> 1;
            case DEPOSIT -> 2;
            case CRAFT -> 3;
            case LOGIC -> 4;
            case FULL -> 5;
        };
    }

    /** The level with {@link #id()} {@code id}, or {@link #NONE} for anything else. */
    public static AccessLevel byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : NONE;
    }

    /** The lang key of the level's name. */
    public String translationKey() {
        return "gui.maxtech_storage.access." + name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
