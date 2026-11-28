package dev.silencebound.maxtech.lib.shim.saving;

/** NeoForge 26.1's {@code common.util.ValueIOSerializable}: an object that saves to and loads from a child. */
public interface ValueIOSerializable {
    void serialize(ValueOutput output);

    void deserialize(ValueInput input);
}
