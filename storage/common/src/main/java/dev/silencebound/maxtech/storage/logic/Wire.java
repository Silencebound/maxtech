package dev.silencebound.maxtech.storage.logic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * A wire from one node's output to another node's input, by node id and port name. Immutable.
 *
 * @param from
 *            the id of the node whose output feeds the wire
 * @param output
 *            the output port's name
 * @param to
 *            the id of the node whose input the wire goes into
 * @param input
 *            the input port's name
 */
public record Wire(int from, String output, int to, String input) {
    public static final Codec<Wire> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("from").forGetter(Wire::from), Codec.string(1, 32).fieldOf("out").forGetter(Wire::output),
            Codec.INT.fieldOf("to").forGetter(Wire::to), Codec.string(1, 32).fieldOf("in").forGetter(Wire::input))
            .apply(instance, Wire::new));
}
