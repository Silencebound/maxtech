package dev.silencebound.maxtech.storage.logic;

/**
 * One input or output of a node type. An input takes at most one wire; an output may feed any number of inputs. An
 * input with a setting of the same name uses that setting while no wire goes into it.
 *
 * @param name
 *            the port's name, unique among the node's inputs (or outputs) and stable across versions, since wires name
 *            it
 * @param type
 *            what the port carries
 * @param required
 *            whether the graph is incomplete without a wire (or a same-named setting) here
 * @param fallback
 *            for a number or yes/no input without a wire or setting, the value it reads (1 is yes)
 * @param nameKey
 *            the port's player-facing name
 */
public record Port(String name, PortType type, boolean required, long fallback, String nameKey) {
    static Port required(String name, PortType type, String english) {
        return new Port(name, type, true, 0, key(name, english));
    }

    static Port optional(String name, PortType type, String english) {
        return new Port(name, type, false, 0, key(name, english));
    }

    static Port optional(String name, PortType type, long fallback, String english) {
        return new Port(name, type, false, fallback, key(name, english));
    }

    static Port output(String name, PortType type, String english) {
        return new Port(name, type, false, 0, key(name, english));
    }

    private static String key(String name, String english) {
        return LogicLang.shared("port." + name, english);
    }
}
