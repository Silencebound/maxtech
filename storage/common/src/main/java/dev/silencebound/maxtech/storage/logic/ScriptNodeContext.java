package dev.silencebound.maxtech.storage.logic;

import java.util.List;

/**
 * What a script node sees while it runs ({@link ScriptNodeLogic}): its inputs, outputs and memory, by the names its
 * type declared. One context per evaluator, reused for every script node and run; valid only during the call. A name
 * the type does not have, a port of another type or a memory slot out of range throws, which pauses the graph with that
 * message. Numbers stay within {@link SettingSpec#MAX_NUMBER} either way. Server thread only; allocates nothing.
 */
public abstract class ScriptNodeContext {
    ScriptNodeContext() {
    }

    /** The type of the node running now. */
    abstract NodeType type();

    abstract long inputValue(int port);

    abstract boolean inputFired(int port);

    abstract void setOutput(int port, long value);

    abstract void fireOutput(int port);

    abstract long memoryAt(int slot);

    abstract void memorySet(int slot, long value);

    /** The game time of the run. */
    public abstract long gameTime();

    /** Number input {@code name}: its wire's value, else its setting, else 0. */
    public long number(String name) {
        return clamp(inputValue(input(name, PortType.NUMBER)));
    }

    /** Yes/no input {@code name}: its wire's value, else its setting, else no. */
    public boolean yes(String name) {
        return inputValue(input(name, PortType.YES_NO)) != 0;
    }

    /** Whether the trigger into input {@code name} fired in this run. */
    public boolean fired(String name) {
        return inputFired(input(name, PortType.EVENT));
    }

    /** Sets number output {@code name}; nodes it feeds run when the value changed. */
    public void setNumber(String name, long value) {
        setOutput(output(name, PortType.NUMBER), clamp(value));
    }

    /** Sets yes/no output {@code name}. */
    public void setYes(String name, boolean value) {
        setOutput(output(name, PortType.YES_NO), value ? 1 : 0);
    }

    /** Fires trigger output {@code name}: the nodes it feeds run in this run. */
    public void fire(String name) {
        fireOutput(output(name, PortType.EVENT));
    }

    /** Memory slot {@code slot} (0 until set); kept between runs and saved with the graph. */
    public long memory(int slot) {
        return memoryAt(memorySlot(slot));
    }

    /** Sets memory slot {@code slot}. */
    public void setMemory(int slot, long value) {
        memorySet(memorySlot(slot), clamp(value));
    }

    private int input(String name, PortType type) {
        return find(type().inputs(), name, type, "input");
    }

    private int output(String name, PortType type) {
        return find(type().declaredOutputs(), name, type, "output");
    }

    private int find(List<Port> ports, String name, PortType type, String what) {
        for (int i = 0; i < ports.size(); i++) {
            Port port = ports.get(i);
            if (port.name().equals(name)) {
                if (port.type() != type) {
                    throw new IllegalArgumentException(
                            "The " + what + " " + name + " is a " + port.type().id() + ", not a " + type.id());
                }
                return i;
            }
        }
        throw new IllegalArgumentException("This node has no " + what + " called " + name);
    }

    private int memorySlot(int slot) {
        if (slot < 0 || slot >= type().memory()) {
            throw new IllegalArgumentException(
                    "Memory slot " + slot + " is outside 0 to " + (type().memory() - 1) + " of this node");
        }
        return slot;
    }

    private static long clamp(long value) {
        return Math.clamp(value, -SettingSpec.MAX_NUMBER, SettingSpec.MAX_NUMBER);
    }
}
