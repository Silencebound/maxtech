
package dev.silencebound.maxtech.lib.shim.saving;
public interface ValueIOSerializable {
    void serialize(ValueOutput output);
    void deserialize(ValueInput input);
}
