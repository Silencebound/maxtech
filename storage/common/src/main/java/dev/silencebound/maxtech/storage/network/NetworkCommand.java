package dev.silencebound.maxtech.storage.network;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.controller.recovery.RecoveryCommand;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.network.snapshot.SnapshotCommand;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /maxtech network} (PRD 7.2, Performance: server caps and visibility), for game masters, in the caller's
 * dimension:
 * <ul>
 * <li>{@code list [count]}: the heaviest storage networks (by their scheduler's last pass), with owner, location (the
 * route root), size and time;</li>
 * <li>{@code inspect <pos>}: the network of the storage node at a position, with its team's use of the server caps and
 * its offline cells;</li>
 * <li>{@code team <player>}: what the player's team holds in this dimension against the caps;</li>
 * <li>{@code profile [seconds|stop]}: times every network on the server for a while, see
 * {@link NetworkProfileCommand};</li>
 * <li>{@code recovery <pos>}: the network's recovery bin, see {@link RecoveryCommand};</li>
 * <li>{@code snapshot save|list|restore|delete}: device settings snapshots, see {@link SnapshotCommand}.</li>
 * </ul>
 * Built here from vanilla's command types; Storage's NeoForge glue registers it under {@code maxtech}.
 */
public final class NetworkCommand {
    private static final String LIST_KEY = key("list");
    private static final String ROW_KEY = key("row");
    private static final String NONE_KEY = key("none");
    private static final String NO_NETWORK_KEY = key("no_network");
    private static final String CAPS_KEY = key("caps");
    private static final String OFFLINE_KEY = key("offline");
    private static final int DEFAULT_COUNT = 10;

    static {
        ModContent.CONTENT.translation(LIST_KEY, "Heaviest storage networks in this dimension (%s of %s):");
        ModContent.CONTENT.translation(ROW_KEY,
                "#%s %s at %s: %s nodes, %s devices, %s zones, last pass %s µs, %s rebuilds, %s patches");
        ModContent.CONTENT.translation(NONE_KEY, "No storage networks in this dimension");
        ModContent.CONTENT.translation(NO_NETWORK_KEY, "No storage network at %s");
        ModContent.CONTENT.translation(CAPS_KEY,
                "Team %s: %s of %s devices, %s of %s zones, %s of %s bytes in this dimension");
        ModContent.CONTENT.translation(OFFLINE_KEY, "Cells offline: %s");
    }

    private NetworkCommand() {
    }

    private static String key(String path) {
        return "commands." + MaxtechStorage.MOD_ID + ".network." + path;
    }

    /** The {@code network} literal with its subcommands; game masters only. */
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("network").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).then(Commands
                .literal("list").executes(context -> list(context.getSource(), DEFAULT_COUNT))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 100)).executes(
                        context -> list(context.getSource(), IntegerArgumentType.getInteger(context, "count")))))
                .then(Commands.literal("inspect").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(
                        context -> inspect(context.getSource(), BlockPosArgument.getLoadedBlockPos(context, "pos")))))
                .then(Commands.literal("team")
                        .then(Commands.argument("player", EntityArgument.player()).executes(
                                context -> team(context.getSource(), EntityArgument.getPlayer(context, "player")))))
                .then(NetworkProfileCommand.build()).then(RecoveryCommand.build()).then(SnapshotCommand.build());
    }

    /** Lists the {@code count} heaviest networks of the caller's dimension. Returns how many it listed. */
    static int list(CommandSourceStack source, int count) {
        StorageNetworkLogic logic = StorageNetworks.logic(source.getLevel(), StorageNetworks.ID);
        List<NetworkState> networks = logic == null ? List.of() : new ArrayList<>(logic.networks());
        if (networks.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(NONE_KEY), false);
            return 0;
        }
        networks.sort(Comparator.comparingLong((NetworkState state) -> state.scheduler().lastPassNanos()).reversed()
                .thenComparing(Comparator.comparingInt(NetworkState::nodeCount).reversed()));
        int shown = Math.min(count, networks.size());
        int total = networks.size();
        source.sendSuccess(() -> Component.translatable(LIST_KEY, shown, total), false);
        for (int i = 0; i < shown; i++) {
            Component row = row(networks.get(i));
            source.sendSuccess(() -> row, false);
        }
        return shown;
    }

    /** Describes the network at {@code pos}. Returns 1, or 0 when there is none. */
    static int inspect(CommandSourceStack source, BlockPos pos) {
        NetworkState network = StorageNetworks.network(source.getLevel(), StorageNetworks.ID, pos);
        if (network == null) {
            source.sendFailure(Component.translatable(NO_NETWORK_KEY, pos.toShortString()));
            return 0;
        }
        Component row = row(network);
        source.sendSuccess(() -> row, false);
        UUID team = network.capTeam();
        if (team != null) {
            Component caps = caps(team.toString(), network.teamDevicesUsed(), network.teamZonesUsed(),
                    network.teamBytesUsed());
            source.sendSuccess(() -> caps, false);
        }
        int offline = network.offlineCells();
        source.sendSuccess(() -> Component.translatable(OFFLINE_KEY, offline), false);
        return 1;
    }

    /** What {@code player}'s team holds in the caller's dimension against the caps. Returns the devices. */
    static int team(CommandSourceStack source, ServerPlayer player) throws CommandSyntaxException {
        ServerLevel level = source.getLevel();
        UUID team = TeamCaps.team(level, OwnerRecord.of(player));
        StorageNetworkLogic logic = StorageNetworks.logic(level, StorageNetworks.ID);
        int devices = 0;
        int zones = 0;
        long bytes = 0;
        if (logic != null && team != null) {
            for (NetworkState network : logic.networks()) {
                if (team.equals(network.capTeam())) {
                    devices += network.limitedDevices();
                    zones += network.zoneCount();
                    bytes += network.usedBytes();
                }
            }
        }
        Component caps = caps(player.getGameProfile().name(), devices, zones, bytes);
        source.sendSuccess(() -> caps, false);
        return devices;
    }

    private static Component row(NetworkState network) {
        String owner = network.owner().isOwned() ? network.owner().ownerName() : "-";
        long root = network.routeRoot();
        String at = root == Long.MIN_VALUE ? "-" : BlockPos.of(root).toShortString();
        return Component.translatable(ROW_KEY, network.id(), owner, at, InfoFormat.number(network.nodeCount()),
                InfoFormat.number(network.deviceCount()), InfoFormat.number(network.zoneCount()),
                InfoFormat.number(network.scheduler().lastPassNanos() / 1000), network.rebuilds(), network.patches());
    }

    private static Component caps(String team, int devices, int zones, long bytes) {
        return Component.translatable(CAPS_KEY, team, InfoFormat.number(devices),
                cap(StorageServerConfig.CAP_DEVICES_PER_TEAM.getAsInt()), InfoFormat.number(zones),
                cap(StorageServerConfig.CAP_ZONES_PER_TEAM.getAsInt()), InfoFormat.number(bytes),
                cap(StorageServerConfig.CAP_BYTES_PER_TEAM.getAsLong()));
    }

    private static String cap(long cap) {
        return cap > 0 ? InfoFormat.number(cap) : "-";
    }

    /** Loads the class during mod construction so its lang lines are declared. */
    public static void init() {
        NetworkProfileCommand.init();
        SnapshotCommand.init();
    }
}
