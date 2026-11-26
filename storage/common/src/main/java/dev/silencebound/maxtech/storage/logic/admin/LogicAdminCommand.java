package dev.silencebound.maxtech.storage.logic.admin;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.logic.GraphChange;
import dev.silencebound.maxtech.storage.logic.GraphError;
import dev.silencebound.maxtech.storage.logic.controller.ControllerTexts;
import dev.silencebound.maxtech.storage.logic.controller.GraphSlot;
import dev.silencebound.maxtech.storage.logic.controller.HistoryEntry;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicWorker;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * {@code /maxtech logic} for game masters (M7L2 chunk 23):
 * <ul>
 * <li>{@code list [cost|errors|owner] [page]}: every rule of the loaded logic controllers on the server, the costliest
 * first by default, with owner, place, recent checks per second, last run and errors; each entry has chat links to
 * teleport there, show the rule's details and pause or resume it;</li>
 * <li>{@code pause <pos> <rule>} and {@code resume <pos> <rule>}: switch a rule (numbered from 1, as on its controller)
 * of the controller at a position in the caller's dimension off or on, recorded in its history;</li>
 * <li>{@code info <pos> <rule>}: one rule's state, errors and newest history entries, read only;</li>
 * <li>{@code screen}: opens the overview screen for the calling player.</li>
 * </ul>
 * Built from vanilla's command types; Storage's NeoForge glue registers it under {@code maxtech}.
 */
public final class LogicAdminCommand {
    private static final String HEADER_KEY = key("header");
    private static final String UNLOADED_KEY = key("unloaded");
    private static final String NONE_KEY = key("none");
    private static final String ROW_KEY = key("row");
    private static final String RAN_KEY = key("ran");
    private static final String NEVER_KEY = key("never");
    private static final String ERROR_KEY = key("error");
    private static final String TELEPORT_KEY = key("teleport");
    private static final String TELEPORT_TIP_KEY = key("teleport_tip");
    private static final String INFO_KEY = key("info");
    private static final String INFO_TIP_KEY = key("info_tip");
    private static final String PAUSE_KEY = key("pause");
    private static final String RESUME_KEY = key("resume");
    private static final String SWITCH_TIP_KEY = key("switch_tip");
    private static final String NEXT_KEY = key("next");
    private static final String PAUSED_KEY = key("paused");
    private static final String RESUMED_KEY = key("resumed");
    private static final String ALREADY_PAUSED_KEY = key("already_paused");
    private static final String ALREADY_RUNNING_KEY = key("already_running");
    private static final String NO_CONTROLLER_KEY = key("no_controller");
    private static final String NO_RULE_KEY = key("no_rule");
    private static final String INFO_HEAD_KEY = key("info_head");
    private static final String INFO_STATE_KEY = key("info_state");
    private static final String INFO_HISTORY_KEY = key("info_history");
    private static final int HISTORY_SHOWN = 3;
    private static final String RULE = "rule";
    private static final String POS = "pos";
    private static final String PAGE = "page";

    static {
        ModContent.CONTENT.translation(HEADER_KEY,
                "Logic rules by %s, page %s of %s: %s rules in %s loaded controllers");
        ModContent.CONTENT.translation(UNLOADED_KEY, "Controllers in unloaded chunks are not listed.");
        ModContent.CONTENT.translation(NONE_KEY, "No logic rules are loaded on this server");
        ModContent.CONTENT.translation(ROW_KEY, "%s %s, owner %s, %s %s: %s checks/s, %s action points/s, last run %s");
        ModContent.CONTENT.translation(RAN_KEY, "%s s ago (%s)");
        ModContent.CONTENT.translation(NEVER_KEY, "not yet");
        ModContent.CONTENT.translation(ERROR_KEY, "Error: %s");
        ModContent.CONTENT.translation(TELEPORT_KEY, "[Teleport]");
        ModContent.CONTENT.translation(TELEPORT_TIP_KEY, "Teleports you to the logic controller");
        ModContent.CONTENT.translation(INFO_KEY, "[Info]");
        ModContent.CONTENT.translation(INFO_TIP_KEY, "Shows the rule's state, errors and recent changes");
        ModContent.CONTENT.translation(PAUSE_KEY, "[Pause]");
        ModContent.CONTENT.translation(RESUME_KEY, "[Resume]");
        ModContent.CONTENT.translation(SWITCH_TIP_KEY,
                "Puts the command into the chat; it switches the rule off or on and records you in its history");
        ModContent.CONTENT.translation(NEXT_KEY, "[Next page]");
        ModContent.CONTENT.translation(PAUSED_KEY, "Paused rule %s of the logic controller at %s");
        ModContent.CONTENT.translation(RESUMED_KEY, "Resumed rule %s of the logic controller at %s");
        ModContent.CONTENT.translation(ALREADY_PAUSED_KEY, "Rule %s of the logic controller at %s is paused already");
        ModContent.CONTENT.translation(ALREADY_RUNNING_KEY, "Rule %s of the logic controller at %s is on already");
        ModContent.CONTENT.translation(NO_CONTROLLER_KEY, "No loaded logic controller at %s");
        ModContent.CONTENT.translation(NO_RULE_KEY, "The logic controller at %s has no rule %s");
        ModContent.CONTENT.translation(INFO_HEAD_KEY, "Rule %s (number %s) at %s %s, owner %s");
        ModContent.CONTENT.translation(INFO_STATE_KEY, "%s: %s checks/s and %s action points/s recently, last run %s");
        ModContent.CONTENT.translation(INFO_HISTORY_KEY, "Day %s, %s: %s");
        for (AdminList.Sort sort : AdminList.Sort.values()) {
            ModContent.CONTENT.translation(sortKey(sort), switch (sort) {
                case COST -> "cost";
                case ACTIONS -> "actions";
                case ERRORS -> "errors";
                case OWNER -> "owner";
            });
        }
    }

    private LogicAdminCommand() {
    }

    private static String key(String path) {
        return "commands." + MaxtechStorage.MOD_ID + ".logic." + path;
    }

    /** The lang key of a sort order's name. */
    public static String sortKey(AdminList.Sort sort) {
        return key("sort." + sort.id());
    }

    /** The {@code logic} literal with its subcommands; game masters only. */
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> list = Commands.literal("list")
                .executes(context -> list(context.getSource(), AdminList.Sort.COST, 1));
        for (AdminList.Sort sort : AdminList.Sort.values()) {
            list.then(Commands.literal(sort.id()).executes(context -> list(context.getSource(), sort, 1))
                    .then(Commands.argument(PAGE, IntegerArgumentType.integer(1))
                            .executes(context -> list(context.getSource(), sort,
                                    IntegerArgumentType.getInteger(context, PAGE)))));
        }
        return Commands.literal("logic").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).then(list)
                .then(Commands.literal("pause").then(rule(true, false)))
                .then(Commands.literal("resume").then(rule(true, true)))
                .then(Commands.literal("info").then(rule(false, false)))
                .then(Commands.literal("screen").executes(context -> screen(context.getSource())));
    }

    /** {@code <pos> <rule>} running a switch to {@code enabled}, or the info when {@code change} is false. */
    private static ArgumentBuilder<CommandSourceStack, ?> rule(boolean change, boolean enabled) {
        return Commands.argument(POS, BlockPosArgument.blockPos())
                .then(Commands.argument(RULE, IntegerArgumentType.integer(1, GraphSlot.HARD_MAX)).executes(context -> {
                    BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, POS);
                    int index = IntegerArgumentType.getInteger(context, RULE) - 1;
                    return change
                            ? setEnabled(context.getSource(), pos, index, enabled)
                            : info(context.getSource(), pos, index);
                }));
    }

    /** Lists page {@code page} of the loaded rules in {@code sort}'s order. Returns how many rules are loaded. */
    static int list(CommandSourceStack source, AdminList.Sort sort, int page) {
        LogicAdmin.Snapshot snapshot = LogicAdmin.gather(source.getServer());
        List<AdminRow> rows = AdminList.sorted(snapshot.rows(), sort);
        if (rows.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(NONE_KEY), false);
            source.sendSuccess(() -> Component.translatable(UNLOADED_KEY).withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        int pages = AdminList.pages(rows.size(), AdminList.PAGE_SIZE);
        int shown = Math.clamp(page, 1, pages);
        Component header = Component.translatable(HEADER_KEY, Component.translatable(sortKey(sort)), shown, pages,
                InfoFormat.number(rows.size()), InfoFormat.number(snapshot.controllers()));
        source.sendSuccess(() -> header, false);
        source.sendSuccess(() -> Component.translatable(UNLOADED_KEY).withStyle(ChatFormatting.GRAY), false);
        for (AdminRow row : AdminList.page(rows, shown, AdminList.PAGE_SIZE)) {
            Component line = row(row);
            source.sendSuccess(() -> line, false);
            if (row.errors() > 0) {
                Component error = Component.translatable(ERROR_KEY, Component.translatable(row.error(), row.errorArg()))
                        .withStyle(ChatFormatting.RED);
                source.sendSuccess(() -> error, false);
            }
        }
        if (shown < pages) {
            Component next = link(Component.translatable(NEXT_KEY), ChatFormatting.AQUA,
                    new ClickEvent.RunCommand("/maxtech logic list " + sort.id() + " " + (shown + 1)), null);
            source.sendSuccess(() -> next, false);
        }
        return rows.size();
    }

    private static Component row(AdminRow row) {
        LogicWorker.Status status = LogicWorker.Status.byId(row.status());
        MutableComponent line = Component.translatable(ROW_KEY,
                Component.translatable(ControllerTexts.chipKey(status)).withStyle(colour(status)),
                Component.literal(row.shownName()).withStyle(ChatFormatting.WHITE),
                row.owner().isEmpty() ? "-" : row.owner(), row.dimension(), row.pos().toShortString(),
                rate(row.checksPerSecond()), rate(row.actionPointsPerSecond()),
                ran(row.lastRunAgo(), row.lastCost(), row.lastPoints()));
        String at = "/execute in " + row.dimension() + " run ";
        String target = row.pos().getX() + " " + row.pos().getY() + " " + row.pos().getZ() + " " + (row.graph() + 1);
        line.append(" ").append(link(Component.translatable(TELEPORT_KEY), ChatFormatting.AQUA,
                new ClickEvent.RunCommand(
                        at + "tp @s " + row.pos().getX() + " " + (row.pos().getY() + 1) + " " + row.pos().getZ()),
                Component.translatable(TELEPORT_TIP_KEY)));
        line.append(" ").append(link(Component.translatable(INFO_KEY), ChatFormatting.AQUA,
                new ClickEvent.RunCommand(at + "maxtech logic info " + target), Component.translatable(INFO_TIP_KEY)));
        boolean off = status == LogicWorker.Status.DISABLED;
        line.append(" ")
                .append(link(Component.translatable(off ? RESUME_KEY : PAUSE_KEY), ChatFormatting.GOLD,
                        new ClickEvent.SuggestCommand(at + "maxtech logic " + (off ? "resume " : "pause ") + target),
                        Component.translatable(SWITCH_TIP_KEY)));
        return line;
    }

    private static MutableComponent link(Component text, ChatFormatting colour, ClickEvent click,
            @Nullable Component tip) {
        return text.copy().withStyle(style -> style.withColor(colour).withClickEvent(click)
                .withHoverEvent(tip == null ? null : new HoverEvent.ShowText(tip)));
    }

    private static ChatFormatting colour(LogicWorker.Status status) {
        return switch (status) {
            case RUNNING -> ChatFormatting.GREEN;
            case WAITING, DISABLED -> ChatFormatting.YELLOW;
            case PAUSED -> ChatFormatting.RED;
            case IDLE -> ChatFormatting.GRAY;
        };
    }

    private static String rate(float perSecond) {
        return String.format(Locale.ROOT, "%.1f", perSecond);
    }

    private static Component ran(long ago, int cost, int points) {
        return ago < 0
                ? Component.translatable(NEVER_KEY)
                : Component.translatable(RAN_KEY, InfoFormat.number(ago / 20), ControllerTexts.costParts(cost, points));
    }

    /** Switches rule {@code index} at {@code pos} to {@code enabled}. Returns 1 when it changed, else 0. */
    static int setEnabled(CommandSourceStack source, BlockPos pos, int index, boolean enabled) {
        String number = String.valueOf(index + 1);
        String at = pos.toShortString();
        LogicAdmin.Result result = LogicAdmin.setEnabled(source, pos, index, enabled);
        switch (result) {
            case DONE ->
                source.sendSuccess(() -> Component.translatable(enabled ? RESUMED_KEY : PAUSED_KEY, number, at), true);
            case UNCHANGED -> source.sendSuccess(
                    () -> Component.translatable(enabled ? ALREADY_RUNNING_KEY : ALREADY_PAUSED_KEY, number, at),
                    false);
            case NO_CONTROLLER -> source.sendFailure(Component.translatable(NO_CONTROLLER_KEY, at));
            case NO_RULE -> source.sendFailure(Component.translatable(NO_RULE_KEY, at, number));
        }
        return result == LogicAdmin.Result.DONE ? 1 : 0;
    }

    /** Shows rule {@code index} at {@code pos}: state, errors and newest history entries. Returns 1, or 0 for none. */
    static int info(CommandSourceStack source, BlockPos pos, int index) {
        String at = pos.toShortString();
        LogicControllerBlockEntity controller = LogicAdmin.controller(source.getLevel(), pos);
        if (controller == null) {
            source.sendFailure(Component.translatable(NO_CONTROLLER_KEY, at));
            return 0;
        }
        LogicWorker worker = controller.worker();
        if (index < 0 || index >= worker.size()) {
            source.sendFailure(Component.translatable(NO_RULE_KEY, at, String.valueOf(index + 1)));
            return 0;
        }
        long now = source.getLevel().getGameTime();
        String name = worker.name(index).isEmpty() ? "#" + (index + 1) : worker.name(index);
        String owner = controller.ownerRecord().isOwned() ? controller.ownerRecord().ownerName() : "-";
        Component head = Component.translatable(INFO_HEAD_KEY, name, index + 1,
                source.getLevel().dimension().identifier().toString(), at, owner);
        source.sendSuccess(() -> head, false);
        LogicWorker.Status status = worker.status(index);
        long lastRun = worker.lastRun(index);
        Component state = Component.translatable(INFO_STATE_KEY,
                Component.translatable(ControllerTexts.chipKey(status)).withStyle(colour(status)),
                rate((float) worker.recentChecksPerSecond(index, now)),
                rate((float) worker.recentActionPointsPerSecond(index, now)),
                ran(lastRun < 0 ? -1 : Math.max(0, now - lastRun), worker.lastCost(index),
                        worker.lastActionPoints(index)));
        source.sendSuccess(() -> state, false);
        for (GraphError error : worker.errors(index)) {
            Component line = Component.translatable(ERROR_KEY, Component.translatable(error.key(), error.arg()))
                    .withStyle(ChatFormatting.RED);
            source.sendSuccess(() -> line, false);
        }
        List<HistoryEntry> history = worker.history(index);
        for (int i = history.size() - 1; i >= Math.max(0, history.size() - HISTORY_SHOWN); i--) {
            HistoryEntry entry = history.get(i);
            Component line = Component.translatable(INFO_HISTORY_KEY, entry.day(), entry.name(), changes(entry))
                    .withStyle(ChatFormatting.GRAY);
            source.sendSuccess(() -> line, false);
        }
        return 1;
    }

    private static Component changes(HistoryEntry entry) {
        MutableComponent out = Component.empty();
        List<GraphChange> changes = entry.changes();
        for (int i = 0; i < changes.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            GraphChange change = changes.get(i);
            Object[] args = new Object[change.args().size()];
            for (int a = 0; a < args.length; a++) {
                GraphChange.Arg arg = change.args().get(a);
                args[a] = arg.translate() ? Component.translatable(arg.text()) : arg.text();
            }
            out.append(Component.translatable(change.key(), args));
        }
        return out;
    }

    /** Opens the overview screen for the calling player. Returns how many rules are loaded. */
    static int screen(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        LogicAdmin.Snapshot snapshot = LogicAdmin.gather(source.getServer());
        Platform.get().network().sendToPlayer(player, LogicAdmin.view(snapshot));
        return snapshot.rows().size();
    }

    /** Loads the class during mod construction so its lang lines are declared. */
    static void init() {
    }
}
