package dev.silencebound.maxtech.storage.network.snapshot;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.recovery.RecoveryBin;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * {@code /maxtech network snapshot ...} (M7C item 27), for game masters: saves a storage network's device settings
 * under a name, lists, restores and deletes snapshots. Part of the {@code network} literal, which checks the
 * permission. What a snapshot holds is in {@link SnapshotDevices}; where it is kept in {@link NetworkSnapshots}.
 * <ul>
 * <li>{@code save <pos> [name]}: the network of the storage node at {@code pos} in the caller's dimension;</li>
 * <li>{@code list [pos]}: every snapshot, or those of the network at {@code pos};</li>
 * <li>{@code restore <name>}: applies the settings to the devices still there, in the snapshot's dimension;</li>
 * <li>{@code delete <name>}.</li>
 * </ul>
 */
public final class SnapshotCommand {
    /** Skipped devices a restore names one by one; the rest are counted. */
    static final int SKIPPED_LINES = 8;
    private static final String SAVED_KEY = key("saved");
    private static final String NO_NETWORK_KEY = key("no_network");
    private static final String BAD_NAME_KEY = key("bad_name");
    private static final String NAME_TAKEN_KEY = key("name_taken");
    private static final String NETWORK_FULL_KEY = key("network_full");
    private static final String SERVER_FULL_KEY = key("server_full");
    private static final String LIST_KEY = key("list");
    private static final String LIST_AT_KEY = key("list_at");
    private static final String ROW_KEY = key("row");
    private static final String NONE_KEY = key("none");
    private static final String UNKNOWN_KEY = key("unknown");
    private static final String NO_DIMENSION_KEY = key("no_dimension");
    private static final String RESTORED_KEY = key("restored");
    private static final String SKIPPED_KEY = key("skipped");
    private static final String SKIPPED_MORE_KEY = key("skipped_more");
    private static final String DELETED_KEY = key("deleted");
    private static final String SKIP_GONE_KEY = key("skip.gone");
    private static final String SKIP_CHANGED_KEY = key("skip.changed");
    private static final String SKIP_UNLOADED_KEY = key("skip.unloaded");
    private static final String NAME_KEPT_KEY = key("name_kept");
    private static final SuggestionProvider<CommandSourceStack> NAMES = (context, builder) -> {
        List<String> names = new ArrayList<>();
        for (NetworkSnapshot snapshot : NetworkSnapshots.get(context.getSource().getServer()).all()) {
            names.add(snapshot.name());
        }
        return SharedSuggestionProvider.suggest(names, builder);
    };

    static {
        ModContent.CONTENT.translation(SAVED_KEY, "Saved snapshot %s of the network at %s: %s devices");
        ModContent.CONTENT.translation(NO_NETWORK_KEY, "No storage network at %s");
        ModContent.CONTENT.translation(BAD_NAME_KEY,
                "Snapshot names are 1 to %s characters of a-z, 0-9, _ and -; %s is not one");
        ModContent.CONTENT.translation(NAME_TAKEN_KEY, "There is already a snapshot named %s");
        ModContent.CONTENT.translation(NETWORK_FULL_KEY,
                "The network at %s already has %s snapshots, the most allowed; delete one first");
        ModContent.CONTENT.translation(SERVER_FULL_KEY,
                "The server already keeps %s snapshots, the most allowed; delete one first");
        ModContent.CONTENT.translation(LIST_KEY, "Network snapshots (%s):");
        ModContent.CONTENT.translation(LIST_AT_KEY, "Snapshots of the network at %s (%s):");
        ModContent.CONTENT.translation(ROW_KEY, "%s: %s devices in %s at %s, by %s, %s ago");
        ModContent.CONTENT.translation(NONE_KEY, "No network snapshots");
        ModContent.CONTENT.translation(UNKNOWN_KEY, "No network snapshot named %s");
        ModContent.CONTENT.translation(NO_DIMENSION_KEY, "The dimension %s of snapshot %s is not loaded");
        ModContent.CONTENT.translation(RESTORED_KEY,
                "Restored snapshot %s: settings put back on %s devices, %s skipped");
        ModContent.CONTENT.translation(SKIPPED_KEY, "Skipped %s: %s");
        ModContent.CONTENT.translation(SKIPPED_MORE_KEY, "... and %s more skipped");
        ModContent.CONTENT.translation(DELETED_KEY, "Deleted snapshot %s");
        ModContent.CONTENT.translation(SKIP_GONE_KEY, "no device there now");
        ModContent.CONTENT.translation(SKIP_CHANGED_KEY, "another kind of device there now");
        ModContent.CONTENT.translation(SKIP_UNLOADED_KEY, "not loaded");
        ModContent.CONTENT.translation(NAME_KEPT_KEY,
                "Another network is called %s now, so the controller kept its current name");
    }

    private SnapshotCommand() {
    }

    private static String key(String path) {
        return "commands." + MaxtechStorage.MOD_ID + ".network.snapshot." + path;
    }

    /** The {@code snapshot} literal. */
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("snapshot").then(Commands.literal("save").then(Commands
                .argument("pos", BlockPosArgument.blockPos())
                .executes(context -> save(context.getSource(), BlockPosArgument.getBlockPos(context, "pos"), null))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> save(context.getSource(), BlockPosArgument.getBlockPos(context, "pos"),
                                StringArgumentType.getString(context, "name"))))))
                .then(Commands.literal("list").executes(context -> list(context.getSource(), null))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(
                                context -> list(context.getSource(), BlockPosArgument.getBlockPos(context, "pos")))))
                .then(Commands.literal("restore")
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(NAMES)
                                .executes(context -> restore(context.getSource(),
                                        StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("delete")
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(NAMES)
                                .executes(context -> delete(context.getSource(),
                                        StringArgumentType.getString(context, "name")))));
    }

    /** The network's anchor: its single controller's block when it has one, else {@code pos}. */
    private static BlockPos anchor(NetworkState network, BlockPos pos) {
        NetworkDevice controller = network.controllerDevice();
        return controller == null ? pos.immutable() : BlockPos.of(controller.blockPos());
    }

    /**
     * Saves the settings of the network at {@code pos} as {@code name}, or the first free {@code snapshot-<n>}. Returns
     * the devices saved (at least 1), 0 when refused.
     */
    public static int save(CommandSourceStack source, BlockPos pos, @Nullable String name) {
        ServerLevel level = source.getLevel();
        NetworkState network = StorageNetworks.network(level, StorageNetworks.ID, pos);
        String at = pos.toShortString();
        if (network == null) {
            source.sendFailure(Component.translatable(NO_NETWORK_KEY, at));
            return 0;
        }
        NetworkSnapshots snapshots = NetworkSnapshots.get(source.getServer());
        String wanted = name == null ? snapshots.freeName() : name.toLowerCase(Locale.ROOT);
        if (!NetworkSnapshots.validName(wanted)) {
            source.sendFailure(Component.translatable(BAD_NAME_KEY, NetworkSnapshots.MAX_NAME, wanted));
            return 0;
        }
        List<NetworkSnapshot.Device> devices = SnapshotDevices.capture(network);
        NetworkSnapshot snapshot = new NetworkSnapshot(wanted, level.dimension(), anchor(network, pos),
                source.getServer().overworld().getGameTime(), source.getTextName(), devices);
        int perNetwork = StorageServerConfig.SNAPSHOTS_MAX_PER_NETWORK.getAsInt();
        int total = StorageServerConfig.SNAPSHOTS_MAX_TOTAL.getAsInt();
        switch (snapshots.add(snapshot, perNetwork, total)) {
            case NAME_TAKEN -> {
                source.sendFailure(Component.translatable(NAME_TAKEN_KEY, wanted));
                return 0;
            }
            case NETWORK_FULL -> {
                source.sendFailure(Component.translatable(NETWORK_FULL_KEY, at, perNetwork));
                return 0;
            }
            case SERVER_FULL -> {
                source.sendFailure(Component.translatable(SERVER_FULL_KEY, total));
                return 0;
            }
            case ADDED -> {
                // Saved below.
            }
        }
        int count = devices.size();
        source.sendSuccess(() -> Component.translatable(SAVED_KEY, wanted, at, count), true);
        return Math.max(1, count);
    }

    /** Lists every snapshot, or those of the network at {@code pos}. Returns how many it listed. */
    public static int list(CommandSourceStack source, @Nullable BlockPos pos) {
        NetworkSnapshots snapshots = NetworkSnapshots.get(source.getServer());
        List<NetworkSnapshot> shown;
        Component header;
        if (pos == null) {
            shown = snapshots.all();
            header = Component.translatable(LIST_KEY, shown.size());
        } else {
            NetworkState network = StorageNetworks.network(source.getLevel(), StorageNetworks.ID, pos);
            if (network == null) {
                source.sendFailure(Component.translatable(NO_NETWORK_KEY, pos.toShortString()));
                return 0;
            }
            shown = snapshots.of(source.getLevel().dimension(), anchor(network, pos));
            header = Component.translatable(LIST_AT_KEY, pos.toShortString(), shown.size());
        }
        if (shown.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(NONE_KEY), false);
            return 0;
        }
        source.sendSuccess(() -> header, false);
        long now = source.getServer().overworld().getGameTime();
        for (NetworkSnapshot snapshot : shown) {
            Component row = Component.translatable(ROW_KEY, snapshot.name(), snapshot.devices().size(),
                    snapshot.dimension().identifier().toString(), snapshot.anchor().toShortString(),
                    snapshot.createdBy(), RecoveryBin.duration(Math.max(0, now - snapshot.createdAt())));
            source.sendSuccess(() -> row, false);
        }
        return shown.size();
    }

    /** Restores the snapshot called {@code name}. Returns the devices restored, 0 when there is no such snapshot. */
    public static int restore(CommandSourceStack source, String name) {
        String wanted = name.toLowerCase(Locale.ROOT);
        NetworkSnapshot snapshot = NetworkSnapshots.get(source.getServer()).get(wanted).orElse(null);
        if (snapshot == null) {
            source.sendFailure(Component.translatable(UNKNOWN_KEY, wanted));
            return 0;
        }
        ServerLevel level = source.getServer().getLevel(snapshot.dimension());
        if (level == null) {
            source.sendFailure(
                    Component.translatable(NO_DIMENSION_KEY, snapshot.dimension().identifier().toString(), wanted));
            return 0;
        }
        SnapshotDevices.Result result = SnapshotDevices.restore(level, snapshot);
        List<SnapshotDevices.Skipped> skipped = result.skipped();
        source.sendSuccess(() -> Component.translatable(RESTORED_KEY, wanted, result.restored(), skipped.size()), true);
        int lines = Math.min(SKIPPED_LINES, skipped.size());
        for (int i = 0; i < lines; i++) {
            SnapshotDevices.Skipped skip = skipped.get(i);
            String where = skip.pos().toShortString() + skip.face().map(face -> " " + face.getName()).orElse("");
            Component line = Component.translatable(SKIPPED_KEY, where, Component.translatable(switch (skip.why()) {
                case GONE -> SKIP_GONE_KEY;
                case CHANGED -> SKIP_CHANGED_KEY;
                case UNLOADED -> SKIP_UNLOADED_KEY;
            }));
            source.sendSuccess(() -> line, false);
        }
        if (skipped.size() > lines) {
            int more = skipped.size() - lines;
            source.sendSuccess(() -> Component.translatable(SKIPPED_MORE_KEY, more), false);
        }
        result.nameTaken()
                .ifPresent(taken -> source.sendSuccess(() -> Component.translatable(NAME_KEPT_KEY, taken), false));
        return result.restored();
    }

    /** Deletes the snapshot called {@code name}. Returns 1, or 0 when there is none. */
    public static int delete(CommandSourceStack source, String name) {
        String wanted = name.toLowerCase(Locale.ROOT);
        if (!NetworkSnapshots.get(source.getServer()).remove(wanted)) {
            source.sendFailure(Component.translatable(UNKNOWN_KEY, wanted));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(DELETED_KEY, wanted), true);
        return 1;
    }

    /** Loads the class during mod construction so its lang lines are declared. */
    public static void init() {
        // The static block does the work.
    }
}
