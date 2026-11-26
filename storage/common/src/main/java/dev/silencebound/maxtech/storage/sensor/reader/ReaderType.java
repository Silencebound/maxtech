package dev.silencebound.maxtech.storage.sensor.reader;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/** What a pack reader's value is, which decides how it is read and shown. */
public enum ReaderType implements StringRepresentable {
    /**
     * A number, multiplied by the value's scale and rounded; a list or array counts its entries. Logic reads it as is.
     */
    NUMBER("number"),
    /** Yes (1) when the number there is not 0, no (0) when it is. */
    YES_NO("yes_no"),
    /** Text, shown on the screen only; logic reads it as 0. */
    TEXT("text");

    public static final Codec<ReaderType> CODEC = StringRepresentable.fromEnum(ReaderType::values);

    private final String name;

    ReaderType(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
