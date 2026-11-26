package dev.silencebound.maxtech.storage.logic.admin;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.logic.GraphChange;
import dev.silencebound.maxtech.storage.logic.GraphError;
import dev.silencebound.maxtech.storage.logic.LogicLang;
import dev.silencebound.maxtech.storage.logic.admin.client.AdminClient;
import dev.silencebound.maxtech.storage.logic.controller.HistoryEntry;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicWorker;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageNetworkLogic;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * What the admin overview reads and changes: every rule of the logic controllers that are loaded now, in every
 * dimension, found through the storage networks (a controller is always a member of one); and an operator's pause or
 * resume, recorded in the rule's history with the operator as the actor. Gathered only when asked; controllers in
 * unloaded chunks are not listed and no chunk is loaded. Server thread.
 */
public final class LogicAdmin {
    /** Most rows the overview screen gets, the costliest first; the header says how many there are in all. */
    public static final int MAX_SCREEN_ROWS = 1000;

    /** What came of a pause or resume. */
    public enum Result {
        DONE, UNCHANGED, NO_CONTROLLER, NO_RULE
    }

    /** The loaded rules, unsorted, and how many loaded controllers hold them. */
    public record Snapshot(List<AdminRow> rows, int controllers) {
        public Snapshot {
            rows = List.copyOf(rows);
        }
    }

    /**
     * Server to client: opens the overview screen.
     *
     * @param rows
     *            the costliest rules, at most {@link #MAX_SCREEN_ROWS}
     * @param total
     *            how many rules are loaded
     * @param controllers
     *            how many loaded controllers hold them
     */
    public record View(List<AdminRow> rows, int total, int controllers) implements CustomPacketPayload {
        public static final Type<View> TYPE = new Type<>(MaxtechStorage.id("logic_admin_view"));
        public static final StreamCodec<ByteBuf, View> STREAM_CODEC = StreamCodec.composite(
                AdminRow.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_SCREEN_ROWS)), View::rows, ByteBufCodecs.VAR_INT,
                View::total, ByteBufCodecs.VAR_INT, View::controllers, View::new);

        public View {
            rows = List.copyOf(rows);
        }

        @Override
        public Type<View> type() {
            return TYPE;
        }
    }

    private LogicAdmin() {
    }

    /** Every rule of every loaded logic controller on {@code server}. */
    public static Snapshot gather(MinecraftServer server) {
        List<AdminRow> rows = new ArrayList<>();
        int controllers = 0;
        for (ServerLevel level : server.getAllLevels()) {
            StorageNetworkLogic logic = StorageNetworks.logic(level, StorageNetworks.ID);
            if (logic == null) {
                continue;
            }
            String dimension = level.dimension().identifier().toString();
            long now = level.getGameTime();
            for (NetworkState network : logic.networks()) {
                for (NetworkDevice device : network.devices()) {
                    if (device instanceof LogicControllerBlockEntity controller && !controller.isRemoved()) {
                        controllers++;
                        add(rows, controller, dimension, now);
                    }
                }
            }
        }
        return new Snapshot(rows, controllers);
    }

    private static void add(List<AdminRow> rows, LogicControllerBlockEntity controller, String dimension, long now) {
        LogicWorker worker = controller.worker();
        String owner = controller.ownerRecord().isOwned() ? controller.ownerRecord().ownerName() : "";
        BlockPos pos = controller.getBlockPos().immutable();
        for (int i = 0; i < worker.size(); i++) {
            List<GraphError> errors = worker.errors(i);
            GraphError first = errors.isEmpty() ? null : errors.getFirst();
            long lastRun = worker.lastRun(i);
            rows.add(new AdminRow(dimension, pos, i, worker.name(i), owner, worker.status(i).id(),
                    (float) worker.recentChecksPerSecond(i, now), worker.lastCost(i),
                    (float) worker.recentActionPointsPerSecond(i, now), worker.lastActionPoints(i),
                    lastRun < 0 ? -1 : Math.max(0, now - lastRun), errors.size(), first == null ? "" : first.key(),
                    first == null ? "" : first.arg()));
        }
    }

    /** The overview screen's packet for {@code snapshot}: the costliest rows first. */
    public static View view(Snapshot snapshot) {
        List<AdminRow> sorted = AdminList.sorted(snapshot.rows(), AdminList.Sort.COST);
        return new View(sorted.subList(0, Math.min(sorted.size(), MAX_SCREEN_ROWS)), sorted.size(),
                snapshot.controllers());
    }

    /** The logic controller at {@code pos} in {@code level}, when its chunk is loaded. */
    public static @Nullable LogicControllerBlockEntity controller(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockEntity(pos) instanceof LogicControllerBlockEntity controller
                ? controller
                : null;
    }

    /**
     * Switches rule {@code index} of the controller at {@code pos} in {@code source}'s level on or off for an operator,
     * as the controller's On/Off button does, and records it in the rule's history with the source as the actor (a
     * player by UUID and name, else the source's name, such as "Server"). Nothing is recorded when nothing changed.
     */
    public static Result setEnabled(CommandSourceStack source, BlockPos pos, int index, boolean enabled) {
        LogicControllerBlockEntity controller = controller(source.getLevel(), pos);
        if (controller == null) {
            return Result.NO_CONTROLLER;
        }
        LogicWorker worker = controller.worker();
        if (index < 0 || index >= worker.size()) {
            return Result.NO_RULE;
        }
        if (worker.enabled(index) == enabled) {
            return Result.UNCHANGED;
        }
        worker.setEnabled(index, enabled);
        ServerPlayer player = source.getPlayer();
        Optional<UUID> actor = player == null ? Optional.empty() : Optional.of(player.getUUID());
        String name = player == null ? source.getTextName() : player.getGameProfile().name();
        worker.record(index, new HistoryEntry(source.getLevel().getGameTime(), System.currentTimeMillis(), actor, name,
                List.of(GraphChange.of(enabled ? LogicLang.HISTORY_ADMIN_ON : LogicLang.HISTORY_ADMIN_OFF))));
        return Result.DONE;
    }

    /** Registers the overview packet: the client opens the screen. */
    static void register() {
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, View.TYPE, View.STREAM_CODEC,
                (payload, player) -> AdminClient.open(payload));
    }

    /** Loads the texts and registers the packet; called during mod construction. */
    public static void init() {
        LogicAdminCommand.init();
        AdminTexts.init();
        register();
    }
}
