package dev.silencebound.maxtech.storage.network;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/**
 * {@code /maxtech network profile [seconds]} and {@code /maxtech network profile stop} (M7C item 26), for game masters:
 * runs a {@link NetworkProfiler} session over every dimension and, when it ends, tells the caller which networks,
 * devices, device kinds and logic rules cost the most server time, in microseconds per tick and as a share of all
 * storage networks' time, with chat links to teleport there. Part of {@link NetworkCommand}'s {@code network} literal.
 */
public final class NetworkProfileCommand {
    /** Rows shown per list. */
    static final int TOP = 5;
    /** Microseconds in one 20 Hz tick. */
    private static final double TICK_MICROS = 50_000;
    private static final String STARTED_KEY = key("started");
    private static final String RUNNING_KEY = key("running");
    private static final String NOT_RUNNING_KEY = key("not_running");
    private static final String HEADER_KEY = key("header");
    private static final String EMPTY_KEY = key("empty");
    private static final String UNLOADED_KEY = key("unloaded");
    private static final String NETWORKS_KEY = key("networks");
    private static final String DEVICES_KEY = key("devices");
    private static final String TYPES_KEY = key("types");
    private static final String RULES_KEY = key("rules");
    private static final String NETWORK_ROW_KEY = key("network_row");
    private static final String DEVICE_ROW_KEY = key("device_row");
    private static final String TYPE_ROW_KEY = key("type_row");
    private static final String RULE_ROW_KEY = key("rule_row");
    private static final String TELEPORT_KEY = key("teleport");
    private static final String TELEPORT_TIP_KEY = key("teleport_tip");
    private static final String INFO_KEY = key("info");
    private static final String INFO_TIP_KEY = key("info_tip");

    static {
        ModContent.CONTENT.translation(STARTED_KEY,
                "Profiling every storage network for %s s; the report follows. /maxtech network profile stop ends it"
                        + " early.");
        ModContent.CONTENT.translation(RUNNING_KEY,
                "A network profile is running already; /maxtech network profile stop ends it");
        ModContent.CONTENT.translation(NOT_RUNNING_KEY, "No network profile is running");
        ModContent.CONTENT.translation(HEADER_KEY,
                "Network profile over %s ticks: storage networks used %s µs/tick in all, %s of a tick");
        ModContent.CONTENT.translation(EMPTY_KEY, "No storage network did any work while profiling");
        ModContent.CONTENT.translation(UNLOADED_KEY,
                "Networks, devices and rules that unloaded or were rebuilt meanwhile are not listed.");
        ModContent.CONTENT.translation(NETWORKS_KEY, "Networks:");
        ModContent.CONTENT.translation(DEVICES_KEY, "Devices:");
        ModContent.CONTENT.translation(TYPES_KEY, "Device kinds:");
        ModContent.CONTENT.translation(RULES_KEY, "Logic rules:");
        ModContent.CONTENT.translation(NETWORK_ROW_KEY, "%s. Owner %s, %s %s, %s devices: %s µs/tick (%s)");
        ModContent.CONTENT.translation(DEVICE_ROW_KEY, "%s. %s, %s %s: %s µs/tick (%s), %s runs");
        ModContent.CONTENT.translation(TYPE_ROW_KEY, "%s. %s, %s devices: %s µs/tick (%s)");
        ModContent.CONTENT.translation(RULE_ROW_KEY, "%s. %s (rule %s), owner %s, %s %s: %s µs/tick (%s), %s runs");
        ModContent.CONTENT.translation(TELEPORT_KEY, "[Teleport]");
        ModContent.CONTENT.translation(TELEPORT_TIP_KEY, "Teleports you there");
        ModContent.CONTENT.translation(INFO_KEY, "[Info]");
        ModContent.CONTENT.translation(INFO_TIP_KEY, "Shows the rule's state, errors and recent changes");
    }

    private NetworkProfileCommand() {
    }

    private static String key(String path) {
        return "commands." + MaxtechStorage.MOD_ID + ".network.profile." + path;
    }

    /** The {@code profile} literal; the {@code network} literal above it checks the permission. */
    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("profile")
                .executes(context -> start(context.getSource(), NetworkProfiler.DEFAULT_SECONDS))
                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, NetworkProfiler.MAX_SECONDS))
                        .executes(context -> start(context.getSource(),
                                IntegerArgumentType.getInteger(context, "seconds"))))
                .then(Commands.literal("stop").executes(context -> stop(context.getSource())));
    }

    /** Starts a session of {@code seconds} that reports to {@code source}. Returns 1, or 0 while one runs. */
    static int start(CommandSourceStack source, int seconds) {
        if (!NetworkProfiler.start(source.getServer(), seconds * 20, report -> report(source, report))) {
            source.sendFailure(Component.translatable(RUNNING_KEY));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(STARTED_KEY, seconds), false);
        return 1;
    }

    /** Ends the running session now; its report goes to whoever started it. Returns 1, or 0 when none ran. */
    static int stop(CommandSourceStack source) {
        if (NetworkProfiler.stop(source.getServer()).isEmpty()) {
            source.sendFailure(Component.translatable(NOT_RUNNING_KEY));
            return 0;
        }
        return 1;
    }

    /** Prints {@code report} to {@code source}: a header, then the costliest networks, devices, kinds and rules. */
    static void report(CommandSourceStack source, NetworkProfiler.Report report) {
        double total = report.microsPerTick(report.totalNanos());
        Component header = Component.translatable(HEADER_KEY, InfoFormat.number(report.ticks()), micros(total),
                percent(total * 100 / TICK_MICROS));
        source.sendSuccess(() -> header, false);
        if (report.networks().isEmpty()) {
            source.sendSuccess(() -> Component.translatable(EMPTY_KEY), false);
            return;
        }
        source.sendSuccess(() -> Component.translatable(UNLOADED_KEY).withStyle(ChatFormatting.GRAY), false);
        heading(source, NETWORKS_KEY);
        List<NetworkProfiler.NetworkRow> networks = report.networks();
        for (int i = 0; i < Math.min(TOP, networks.size()); i++) {
            NetworkProfiler.NetworkRow row = networks.get(i);
            MutableComponent line = Component.translatable(NETWORK_ROW_KEY, i + 1,
                    row.owner().isEmpty() ? "-" : row.owner(), row.dimension(), row.pos().toShortString(),
                    InfoFormat.number(row.devices()), time(report, row.nanos()), share(report, row.nanos()));
            send(source, teleport(line, row.dimension(), row.pos()));
        }
        heading(source, DEVICES_KEY);
        List<NetworkProfiler.DeviceRow> devices = report.devices();
        for (int i = 0; i < Math.min(TOP, devices.size()); i++) {
            NetworkProfiler.DeviceRow row = devices.get(i);
            MutableComponent line = Component.translatable(DEVICE_ROW_KEY, i + 1, row.name(), row.dimension(),
                    row.pos().toShortString(), time(report, row.nanos()), share(report, row.nanos()),
                    InfoFormat.number(row.runs()));
            send(source, teleport(line, row.dimension(), row.pos()));
        }
        heading(source, TYPES_KEY);
        List<NetworkProfiler.TypeRow> types = report.types();
        for (int i = 0; i < Math.min(TOP, types.size()); i++) {
            NetworkProfiler.TypeRow row = types.get(i);
            send(source, Component.translatable(TYPE_ROW_KEY, i + 1, row.name(), InfoFormat.number(row.devices()),
                    time(report, row.nanos()), share(report, row.nanos())));
        }
        List<NetworkProfiler.RuleRow> rules = report.rules();
        if (rules.isEmpty()) {
            return;
        }
        heading(source, RULES_KEY);
        for (int i = 0; i < Math.min(TOP, rules.size()); i++) {
            NetworkProfiler.RuleRow row = rules.get(i);
            String name = row.name().isEmpty() ? "#" + (row.rule() + 1) : row.name();
            MutableComponent line = Component.translatable(RULE_ROW_KEY, i + 1, name, row.rule() + 1,
                    row.owner().isEmpty() ? "-" : row.owner(), row.dimension(), row.pos().toShortString(),
                    time(report, row.nanos()), share(report, row.nanos()), InfoFormat.number(row.runs()));
            teleport(line, row.dimension(), row.pos());
            BlockPos pos = row.pos();
            line.append(" ")
                    .append(link(Component.translatable(INFO_KEY),
                            new ClickEvent.RunCommand("/execute in " + row.dimension() + " run maxtech logic info "
                                    + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + (row.rule() + 1)),
                            Component.translatable(INFO_TIP_KEY)));
            send(source, line);
        }
    }

    private static void heading(CommandSourceStack source, String key) {
        source.sendSuccess(() -> Component.translatable(key).withStyle(ChatFormatting.GOLD), false);
    }

    private static void send(CommandSourceStack source, Component line) {
        source.sendSuccess(() -> line, false);
    }

    /** Appends a link that teleports the reader to just above {@code pos} in {@code dimension}. */
    private static MutableComponent teleport(MutableComponent line, String dimension, BlockPos pos) {
        return line.append(" ")
                .append(link(
                        Component.translatable(TELEPORT_KEY), new ClickEvent.RunCommand("/execute in " + dimension
                                + " run tp @s " + pos.getX() + " " + (pos.getY() + 1) + " " + pos.getZ()),
                        Component.translatable(TELEPORT_TIP_KEY)));
    }

    private static MutableComponent link(Component text, ClickEvent click, Component tip) {
        return text.copy().withStyle(style -> style.withColor(ChatFormatting.AQUA).withClickEvent(click)
                .withHoverEvent(new HoverEvent.ShowText(tip)));
    }

    private static String time(NetworkProfiler.Report report, long nanos) {
        return micros(report.microsPerTick(nanos));
    }

    private static String share(NetworkProfiler.Report report, long nanos) {
        return percent(report.percent(nanos));
    }

    private static String micros(double micros) {
        return String.format(Locale.ROOT, "%.1f", micros);
    }

    private static String percent(double percent) {
        return String.format(Locale.ROOT, "%.1f %%", percent);
    }

    /** Loads the class during mod construction so its lang lines are declared. */
    static void init() {
    }
}
