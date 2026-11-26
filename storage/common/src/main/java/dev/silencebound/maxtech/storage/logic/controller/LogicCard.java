package dev.silencebound.maxtech.storage.logic.controller;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * The rule on a written logic card (M7L2 sharing): its name and the graph exactly as {@code LogicKey.GRAPH_CODEC} saves
 * it (nodes with settings and canvas positions, wires; no device positions, no memory), kept as saved data rather than
 * a decoded graph so a card naming a node type or item that is gone keeps its data and says why when loaded. The data
 * is untrusted (a player in creative mode can put anything on an item): loading it goes through
 * {@code GraphText.fromData} with every import check. The tag is copied in and out, so the component stays immutable.
 *
 * @param name
 *            the rule's name, at most {@link GraphSlot#MAX_NAME} characters
 * @param graph
 *            the graph's saved data
 */
public record LogicCard(String name, CompoundTag graph) {
    public static final Codec<LogicCard> CODEC = RecordCodecBuilder
            .create(instance -> instance
                    .group(Codec.string(0, GraphSlot.MAX_NAME).optionalFieldOf("name", "").forGetter(LogicCard::name),
                            CompoundTag.CODEC.fieldOf("graph").forGetter(LogicCard::graph))
                    .apply(instance, LogicCard::new));
    public static final StreamCodec<ByteBuf, LogicCard> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(GraphSlot.MAX_NAME), LogicCard::name, ByteBufCodecs.COMPOUND_TAG, LogicCard::graph,
            LogicCard::new);

    public LogicCard {
        name = name.length() > GraphSlot.MAX_NAME ? name.substring(0, GraphSlot.MAX_NAME) : name;
        graph = graph.copy();
    }

    /** A copy of the graph's saved data. */
    @Override
    public CompoundTag graph() {
        return graph.copy();
    }

    /** How many nodes the data lists, for the tooltip; not checked (loading checks). */
    public int nodes() {
        return graph.getListOrEmpty("nodes").size();
    }
}
