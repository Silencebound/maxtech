package dev.silencebound.maxtech.lib.profile;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /maxtech_lib profile [seconds]} and {@code /maxtech_lib profile stop}, for game masters (permission level 2):
 * runs a {@link LibProfiler} session and, when it ends, prints Lib's scheduler and network time per tick, the
 * scheduler's time by mod, the most expensive task classes and network types, and the largest networks.
 */
public final class ProfileCommand {
    /** Microseconds in one 20 Hz tick. */
    private static final double TICK_MICROS = 50_000;
    public static final String STARTED_KEY = key("started");
    public static final String RUNNING_KEY = key("running");
    public static final String NOT_RUNNING_KEY = key("not_running");
    public static final String STOPPED_KEY = key("stopped");
    public static final String HEADER_KEY = key("header");
    public static final String EMPTY_KEY = key("empty");
    public static final String OWNERS_KEY = key("owners");
    public static final String OWNER_ROW_KEY = key("owner_row");
    public static final String KINDS_KEY = key("kinds");
    public static final String KIND_ROW_KEY = key("kind_row");
    public static final String KIND_TASK_KEY = key("kind.task");
    public static final String KIND_NETWORK_KEY = key("kind.network");
    public static final String NETWORKS_KEY = key("networks");
    public static final String NETWORK_ROW_KEY = key("network_row");

    /** Who started the running session, to tell a stop by someone else. */
    private static String starter = "";

    private ProfileCommand() {
    }

    private static String key(String path) {
        return "commands." + MaxtechLib.MOD_ID + ".profile." + path;
    }

    /** The {@code profile} literal; the {@code maxtech_lib} literal above it checks the permission. */
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("profile").executes(context -> start(context.getSource(), LibProfiler.DEFAULT_SECONDS))
                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, LibProfiler.MAX_SECONDS)).executes(
                        context -> start(context.getSource(), IntegerArgumentType.getInteger(context, "seconds"))))
                .then(Commands.literal("stop").executes(context -> stop(context.getSource())));
    }

    /** Starts a session of {@code seconds} that reports to {@code source}. Returns 1, or 0 while one runs. */
    static int start(CommandSourceStack source, int seconds) {
        if (!LibProfiler.start(source.getServer(), seconds * 20, report -> report(source, report))) {
            source.sendFailure(Component.translatable(RUNNING_KEY));
            return 0;
        }
        starter = source.getTextName();
        source.sendSuccess(() -> Component.translatable(STARTED_KEY, seconds), false);
        return 1;
    }

    /**
     * Ends the running session now; its report goes to whoever started it, and to the stopper too when that is someone
     * else. Returns 1, or 0 when none ran.
     */
    static int stop(CommandSourceStack source) {
        String startedBy = starter;
        Optional<LibProfiler.Report> report = LibProfiler.stop(source.getServer());
        if (report.isEmpty()) {
            source.sendFailure(Component.translatable(NOT_RUNNING_KEY));
            return 0;
        }
        if (!source.getTextName().equals(startedBy)) {
            source.sendSuccess(() -> Component.translatable(STOPPED_KEY, startedBy), false);
            report(source, report.get());
        }
        return 1;
    }

    private static void report(CommandSourceStack source, LibProfiler.Report report) {
        for (Component line : lines(report)) {
            source.sendSuccess(() -> line, false);
        }
    }

    /** The chat lines of {@code report}: a header, then scheduler time by mod, the costliest kinds and networks. */
    public static List<Component> lines(LibProfiler.Report report) {
        List<Component> lines = new ArrayList<>();
        double scheduler = report.microsPerTick(report.schedulerNanos());
        double networks = report.microsPerTick(report.networkNanos());
        lines.add(Component.translatable(HEADER_KEY, InfoFormat.number(report.ticks()), micros(scheduler),
                micros(networks), percent((scheduler + networks) * 100 / TICK_MICROS),
                InfoFormat.number(report.runs())));
        if (report.kinds().isEmpty() && report.largest().isEmpty()) {
            lines.add(Component.translatable(EMPTY_KEY).withStyle(ChatFormatting.GRAY));
            return lines;
        }
        if (!report.owners().isEmpty()) {
            lines.add(heading(OWNERS_KEY));
            for (LibProfiler.OwnerRow row : report.owners()) {
                lines.add(Component.translatable(OWNER_ROW_KEY, row.owner(), time(report, row.nanos()),
                        InfoFormat.number(row.runs())));
            }
        }
        if (!report.kinds().isEmpty()) {
            lines.add(heading(KINDS_KEY));
            List<LibProfiler.KindRow> kinds = report.kinds();
            for (int i = 0; i < kinds.size(); i++) {
                LibProfiler.KindRow row = kinds.get(i);
                Component kind = Component
                        .translatable(row.kind() == LibProfiler.Kind.TASK ? KIND_TASK_KEY : KIND_NETWORK_KEY);
                lines.add(Component.translatable(KIND_ROW_KEY, i + 1, row.name(), kind, row.owner(),
                        time(report, row.nanos()), InfoFormat.number(row.runs())));
            }
        }
        lines.add(heading(NETWORKS_KEY, InfoFormat.number(report.networks())));
        List<LibProfiler.NetworkRow> largest = report.largest();
        for (int i = 0; i < largest.size(); i++) {
            LibProfiler.NetworkRow row = largest.get(i);
            lines.add(Component.translatable(NETWORK_ROW_KEY, i + 1, row.type(), InfoFormat.number(row.nodes()),
                    row.dimension(), row.pos().toShortString(), time(report, row.nanos())));
        }
        return lines;
    }

    private static Component heading(String key, Object... args) {
        return Component.translatable(key, args).withStyle(ChatFormatting.GOLD);
    }

    private static String time(LibProfiler.Report report, long nanos) {
        return micros(report.microsPerTick(nanos));
    }

    private static String micros(double micros) {
        return String.format(Locale.ROOT, "%.1f", micros);
    }

    private static String percent(double percent) {
        return String.format(Locale.ROOT, "%.1f %%", percent);
    }
}
