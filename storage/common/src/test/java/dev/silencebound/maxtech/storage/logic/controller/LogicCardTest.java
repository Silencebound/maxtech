package dev.silencebound.maxtech.storage.logic.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import dev.silencebound.maxtech.storage.logic.GraphText;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.template.BuiltInTemplates;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

/** A logic card's data: written from a graph, saved, sent and read back to the same graph, with the import checks. */
class LogicCardTest {
    private static final Codec<LogicGraph<String>> CODEC = LogicGraph.codec(Codec.STRING);

    private static LogicGraph<String> graph() {
        return java.util.Objects.requireNonNull(BuiltInTemplates.all(item -> item).get("button_moves_out")).graph();
    }

    private static LogicCard card(String name, LogicGraph<String> graph) {
        return new LogicCard(name, GraphText.encode(CODEC, NbtOps.INSTANCE, graph).orElseThrow());
    }

    private static GraphText.Read<String> read(LogicCard card, int maxNodes) {
        return GraphText.fromData(new Dynamic<Tag>(NbtOps.INSTANCE, card.graph()), card.name(), maxNodes, CODEC);
    }

    @Test
    void savedAndSentCardReadsBackToTheSameGraph() {
        LogicCard card = card("Outbox button", graph());
        Tag saved = LogicCard.CODEC.encodeStart(NbtOps.INSTANCE, card).getOrThrow();
        LogicCard loaded = LogicCard.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        assertEquals(card, loaded);
        ByteBuf buffer = Unpooled.buffer();
        LogicCard.STREAM_CODEC.encode(buffer, card);
        assertEquals(card, LogicCard.STREAM_CODEC.decode(buffer));

        GraphText.Read<String> read = read(loaded, 64);
        assertTrue(read.ok(), read.error());
        assertEquals(graph(), read.graph().orElseThrow());
        assertEquals("Outbox button", read.name());
        assertEquals(graph().nodes().size(), loaded.nodes());
    }

    @Test
    void theCardKeepsItsOwnCopy() {
        CompoundTag data = GraphText.encode(CODEC, NbtOps.INSTANCE, graph()).orElseThrow();
        LogicCard card = new LogicCard("x", data);
        data.put("nodes", new ListTag());
        card.graph().put("nodes", new ListTag());
        assertEquals(graph().nodes().size(), card.nodes());
    }

    @Test
    void longNamesAreCut() {
        assertEquals(GraphSlot.MAX_NAME, card("y".repeat(80), graph()).name().length());
    }

    @Test
    void cardDataGetsTheImportChecks() {
        GraphText.Read<String> tooMany = read(card("big", graph()), 2);
        assertFalse(tooMany.ok());
        assertEquals(GraphText.TOO_MANY_NODES, tooMany.error());

        CompoundTag data = card("odd", graph()).graph();
        ((CompoundTag) data.getListOrEmpty("nodes").get(0)).put("type", StringTag.valueOf("no_such_node"));
        GraphText.Read<String> unknown = read(new LogicCard("odd", data), 64);
        assertEquals(GraphText.UNKNOWN_NODE, unknown.error());

        GraphText.Read<String> empty = read(new LogicCard("none", new CompoundTag()), 64);
        assertFalse(empty.ok(), "no graph data");
    }
}
