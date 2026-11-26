package dev.silencebound.maxtech.storage.logic.admin;

import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.logic.controller.GraphSlot;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * One rule in the admin overview, as gathered when an operator asks.
 *
 * @param dimension
 *            the controller's dimension id
 * @param pos
 *            the controller's position
 * @param graph
 *            the rule's row on its controller, from 0
 * @param name
 *            the rule's name; may be empty
 * @param owner
 *            the controller owner's name; empty when unowned
 * @param status
 *            its status ({@code LogicWorker.Status#id})
 * @param checksPerSecond
 *            checks per second it paid recently ({@code RecentRate})
 * @param lastCost
 *            checks its last run cost
 * @param actionPointsPerSecond
 *            action points per second it paid recently ({@code RecentRate})
 * @param lastPoints
 *            action points its last run's actions cost
 * @param lastRunAgo
 *            ticks since its last run; -1 when it has not run since it was saved or loaded
 * @param errors
 *            how many compile or loop errors pause it
 * @param error
 *            the first error's lang key; empty without errors
 * @param errorArg
 *            the first error's argument; may be empty
 */
public record AdminRow(String dimension, BlockPos pos, int graph, String name, String owner, int status,
        float checksPerSecond, int lastCost, float actionPointsPerSecond, int lastPoints, long lastRunAgo, int errors,
        String error, String errorArg) {
    private static final int MAX_TEXT = 256;
    /** The overview's packet codec. */
    public static final StreamCodec<ByteBuf, AdminRow> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public AdminRow decode(ByteBuf buf) {
            return new AdminRow(ByteBufCodecs.stringUtf8(MAX_TEXT).decode(buf), BlockPos.STREAM_CODEC.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.stringUtf8(GraphSlot.MAX_NAME).decode(buf),
                    ByteBufCodecs.stringUtf8(OwnerRecord.MAX_NAME_LENGTH).decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.FLOAT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.FLOAT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_LONG.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.stringUtf8(MAX_TEXT).decode(buf),
                    ByteBufCodecs.stringUtf8(MAX_TEXT).decode(buf));
        }

        @Override
        public void encode(ByteBuf buf, AdminRow row) {
            ByteBufCodecs.stringUtf8(MAX_TEXT).encode(buf, row.dimension());
            BlockPos.STREAM_CODEC.encode(buf, row.pos());
            ByteBufCodecs.VAR_INT.encode(buf, row.graph());
            ByteBufCodecs.stringUtf8(GraphSlot.MAX_NAME).encode(buf, row.name());
            ByteBufCodecs.stringUtf8(OwnerRecord.MAX_NAME_LENGTH).encode(buf, row.owner());
            ByteBufCodecs.VAR_INT.encode(buf, row.status());
            ByteBufCodecs.FLOAT.encode(buf, row.checksPerSecond());
            ByteBufCodecs.VAR_INT.encode(buf, row.lastCost());
            ByteBufCodecs.FLOAT.encode(buf, row.actionPointsPerSecond());
            ByteBufCodecs.VAR_INT.encode(buf, row.lastPoints());
            ByteBufCodecs.VAR_LONG.encode(buf, row.lastRunAgo());
            ByteBufCodecs.VAR_INT.encode(buf, row.errors());
            ByteBufCodecs.stringUtf8(MAX_TEXT).encode(buf, row.error());
            ByteBufCodecs.stringUtf8(MAX_TEXT).encode(buf, row.errorArg());
        }
    };

    public AdminRow {
        dimension = cut(dimension);
        name = name.length() > GraphSlot.MAX_NAME ? name.substring(0, GraphSlot.MAX_NAME) : name;
        owner = owner.length() > OwnerRecord.MAX_NAME_LENGTH ? owner.substring(0, OwnerRecord.MAX_NAME_LENGTH) : owner;
        error = cut(error);
        errorArg = cut(errorArg);
    }

    private static String cut(String text) {
        return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
    }

    /** The rule's name, or its row number as "#n" when it has none. */
    public String shownName() {
        return name.isEmpty() ? "#" + (graph + 1) : name;
    }
}
