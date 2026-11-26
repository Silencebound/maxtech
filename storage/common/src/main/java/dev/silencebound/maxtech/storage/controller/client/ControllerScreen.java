package dev.silencebound.maxtech.storage.controller.client;

import dev.silencebound.maxtech.storage.devicetab.client.DeviceScreen;
import java.util.function.Supplier;
import dev.silencebound.maxtech.lib.client.gui.accent.AccentButton;
import dev.silencebound.maxtech.storage.machine.client.MenuClick;
import dev.silencebound.maxtech.storage.palette.StatusPalette;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.analyzer.AnalyzerReport;
import dev.silencebound.maxtech.storage.analyzer.AnalyzerTexts;
import dev.silencebound.maxtech.storage.analyzer.NetworkOverview;
import dev.silencebound.maxtech.storage.cluster.ClusterNeeds;
import dev.silencebound.maxtech.storage.cluster.ClusterPreview;
import dev.silencebound.maxtech.storage.controller.ClusterStats;
import dev.silencebound.maxtech.storage.controller.ControllerMenu;
import dev.silencebound.maxtech.storage.controller.NetworkRenamePayload;
import dev.silencebound.maxtech.storage.controller.alarm.Alarm;
import dev.silencebound.maxtech.storage.controller.alarm.AlarmTexts;
import dev.silencebound.maxtech.storage.controller.card.CardContent;
import dev.silencebound.maxtech.storage.controller.card.CardKind;
import dev.silencebound.maxtech.storage.controller.recorder.client.RecordsPanel;
import dev.silencebound.maxtech.storage.controller.recovery.RecoveryBin;
import dev.silencebound.maxtech.storage.controller.recovery.RecoveryTabPayload;
import dev.silencebound.maxtech.storage.controller.recovery.RecoveryTexts;
import dev.silencebound.maxtech.storage.crafting.client.KeyDisplay;
import dev.silencebound.maxtech.storage.display.production.ProductionTexts;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.network.Channels;
import dev.silencebound.maxtech.storage.network.NetworkInfo;
import dev.silencebound.maxtech.storage.network.NetworkLabels;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import dev.silencebound.maxtech.storage.subnet.SubnetMode;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The controller core's screen in the Accent Frame style, with five tabs: Network (status, power, limits, team caps,
 * zones, busiest segments, subnets, antenna links, hubs, labels and warnings, a Locate button on rows with a position),
 * History (M7R item 3: the controller's recorder views, {@link RecordsPanel}: telemetry graphs, the ledger and the
 * redstone alarm rules, each behind its research) and Crafting (the limits for automated crafting jobs with step
 * buttons, and the free CPUs, from the menu's data slots) and Alarms (M7C item 16: the network's alarms, fresh ones
 * first, each fresh one with an Ack button) and Recovery (M7C item 20: the recovery bin's rows with their time left and
 * a Recover button for the network's owner or an admin) and Cards (M7R item 2: the upgrade card slots, what the cards
 * do and the player's inventory). It draws the menu's last {@link NetworkOverview}; rows are rebuilt only when a new
 * one arrives or the installed cards change. Client only.
 */
public final class ControllerScreen extends DeviceScreen<ControllerMenu> {
    private static final int TAB_Y = 18;
    /** Six tabs of 38 with gaps of 2 fill the 240 between the frame's edges. */
    private static final int TAB_WIDTH = 38;
    private static final int TAB_STEP = TAB_WIDTH + 2;
    private static final int TAB_HEIGHT = 14;
    private static final int LIST_X = 8;
    private static final int LIST_Y = 38;
    private static final int ROW_HEIGHT = 11;
    private static final int VISIBLE = 15;
    private static final int LOCATE_WIDTH = 40;
    private static final int HEADER_COLOUR = AccentFrame.ACCENT | 0xFF000000;

    private static final int TAB_NETWORK = 0;
    private static final int TAB_HISTORY = 1;
    private static final int TAB_CRAFTING = 2;
    private static final int TAB_ALARMS = 3;
    private static final int TAB_RECOVERY = 4;
    private static final int TAB_CARDS = 5;
    private static final int TABS = 6;
    private static final int STEP_WIDTH = 16;
    private static final int STEP_HEIGHT = 12;
    private static final int RESET_WIDTH = 90;
    /** A row's button that opens the rename page rather than locating something (M7L2 row 13). */
    private static final int RENAME_BUTTON = Integer.MAX_VALUE;

    private int tab = TAB_NETWORK;
    private int scroll;
    private int alarmScroll;
    private int recoveryScroll;
    private @Nullable NetworkOverview shown;
    /** The installed cards the Network tab's rows were built with, one digit group per kind. */
    private int shownCards = -1;
    private final List<Row> rows = new ArrayList<>();
    /** The Crafting tab's step and reset buttons, shown with that tab only. */
    private final List<AccentButton> limitButtons = new ArrayList<>(5);
    /** The History tab: the controller's recorder views. */
    private final RecordsPanel records;

    /** One line of the Network tab; {@code button} is the menu button that locates it, or -1. */
    private record Row(Component text, int colour, int button) {
    }

    public ControllerScreen(ControllerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, ControllerMenu.WIDTH, ControllerMenu.HEIGHT);
        this.titleLabelY = -100;
        this.inventoryLabelY = -100;
        this.records = new RecordsPanel(menu.containerId);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (tab == TAB_HISTORY) {
            records.tick();
        }
    }

    // The overview is compared by identity: see the comment in the body.
    @SuppressWarnings("ReferenceEquality")
    @Override
    protected void extractPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        AccentFrame.panel(graphics, font, leftPos, topPos, imageWidth, imageHeight, title, mouseX, mouseY);
        menu.showCards(tab == TAB_CARDS);
        if (tab == TAB_CARDS) {
            cards(graphics, mouseX, mouseY);
            return;
        }
        if (tab == TAB_RECOVERY) {
            recovery(graphics, mouseX, mouseY);
            return;
        }
        if (tab == TAB_ALARMS) {
            alarms(graphics, mouseX, mouseY);
            return;
        }
        if (tab == TAB_CRAFTING) {
            crafting(graphics, mouseX, mouseY);
            return;
        }
        if (tab == TAB_HISTORY) {
            records.draw(graphics, font, mouseX, mouseY);
            return;
        }
        NetworkOverview overview = menu.overview();
        if (overview == null) {
            AccentFrame.text(graphics, font, Component.translatable(AnalyzerTexts.WAITING), leftPos + LIST_X,
                    topPos + LIST_Y, imageWidth - 2 * LIST_X, AccentFrame.LABEL, mouseX, mouseY);
            return;
        }
        // Identity on purpose: a new payload is a new object, and equal ones are never sent twice.
        int installed = installedSignature();
        if (overview != shown || installed != shownCards) {
            shown = overview;
            shownCards = installed;
            rebuild(overview);
            scroll = Mth.clamp(scroll, 0, Math.max(0, rows.size() - VISIBLE));
        }
        list(graphics, mouseX, mouseY);
    }

    /** The tab row's label for tab {@code index}; the Alarms tab counts fresh alarms. */
    private Component tabLabel(int index) {
        return switch (index) {
            case TAB_NETWORK -> Component.translatable(AnalyzerTexts.TAB_NETWORK);
            case TAB_HISTORY -> Component.translatable(AnalyzerTexts.TAB_HISTORY);
            case TAB_CRAFTING -> Component.translatable(AnalyzerTexts.TAB_CRAFTING);
            case TAB_ALARMS -> alarmsLabel();
            case TAB_RECOVERY -> Component.translatable(RecoveryTexts.TAB);
            default -> Component.translatable(CardContent.TAB_KEY);
        };
    }

    private void selectTab(int index) {
        if (index == TAB_HISTORY && tab != TAB_HISTORY) {
            records.ask();
        }
        tab = index;
        menu.showCards(tab == TAB_CARDS);
        for (AccentButton button : limitButtons) {
            button.visible = tab == TAB_CRAFTING;
        }
    }

    private boolean mayConfigure() {
        return (menu.limitData(ControllerMenu.DATA_FLAGS) & ControllerMenu.FLAG_MAY_CONFIGURE) != 0;
    }

    /** The installed cards per kind as one number, so the Network tab sees a change; at most 12 of each. */
    private int installedSignature() {
        int signature = 0;
        for (CardKind kind : CardKind.values()) {
            signature = signature * 16 + menu.installed(kind);
        }
        return signature;
    }

    /**
     * The Cards tab: the slots (usable ones framed, the others dim), the slots in use, what the cards do now, and the
     * player's inventory below.
     */
    private void cards(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int x = leftPos + LIST_X;
        int room = imageWidth - 2 * LIST_X;
        int usable = menu.cardSlots();
        int flags = menu.limitData(ControllerMenu.DATA_FLAGS);
        int installed = 0;
        for (CardKind kind : CardKind.values()) {
            installed += menu.installed(kind);
        }
        Component header = (flags & ControllerMenu.FLAG_NO_CARD_RESEARCH) != 0
                ? Component.translatable(CardContent.NO_RESEARCH_KEY)
                : Component.translatable(CardContent.SLOTS_KEY, InfoFormat.number(installed),
                        InfoFormat.number(usable));
        AccentFrame.text(graphics, font, header, x, topPos + LIST_Y, room, HEADER_COLOUR, mouseX, mouseY);
        for (int i = 0; i < ClusterStats.Cards.MOST; i++) {
            int sx = leftPos + ControllerMenu.CARD_X + i * 18;
            int sy = topPos + ControllerMenu.CARD_Y;
            if (i < usable) {
                AccentFrame.slot(graphics, sx, sy);
            } else {
                graphics.fill(sx - 1, sy - 1, sx + 17, sy + 17, AccentFrame.SLOT_EDGE);
            }
        }
        int line = topPos + ControllerMenu.CARD_Y + 22;
        int batch = menu.limitData(ControllerMenu.DATA_BATCH_PERCENT);
        AccentFrame.text(graphics, font,
                Component.translatable(CardContent.BATCH_LINE_KEY, InfoFormat.number(Math.max(100, batch))), x, line,
                room, AccentFrame.TEXT, mouseX, mouseY);
        int energy = menu.limitData(ControllerMenu.DATA_ENERGY_PERCENT);
        AccentFrame.text(graphics, font,
                Component.translatable(CardContent.EFFICIENCY_LINE_KEY, InfoFormat.number(energy <= 0 ? 100 : energy)),
                x, line + 11, room, AccentFrame.TEXT, mouseX, mouseY);
        int surge = menu.limitData(ControllerMenu.DATA_SURGE_PERCENT);
        AccentFrame.text(graphics, font,
                surge > 0
                        ? Component.translatable(CardContent.SURGE_LINE_KEY, InfoFormat.number(surge))
                        : Component.translatable(CardContent.SURGE_OFF_KEY),
                x, line + 22, room, AccentFrame.TEXT, mouseX, mouseY);
        if ((flags & ControllerMenu.FLAG_MAY_CONFIGURE) == 0) {
            AccentFrame.text(graphics, font, Component.translatable(CardContent.NOT_ALLOWED_KEY), x, line + 33, room,
                    StatusPalette.warn(), mouseX, mouseY);
        }
        for (Slot slot : menu.slots) {
            if (slot.isActive() && slot.index >= ClusterStats.Cards.MOST) {
                AccentFrame.slot(graphics, leftPos + slot.x, topPos + slot.y);
            }
        }
    }

    /** "Alarms", with the number of fresh ones when there are any. */
    private Component alarmsLabel() {
        int fresh = 0;
        List<Alarm> alarms = menu.alarms();
        for (int i = 0; i < alarms.size(); i++) {
            if (alarms.get(i).fresh()) {
                fresh++;
            }
        }
        Component label = Component.translatable(AlarmTexts.TAB);
        return fresh == 0 ? label : label.copy().append(" (" + fresh + ")");
    }

    /** The Alarms tab: one line per alarm, an Ack button on fresh ones. */
    private void alarms(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        List<Alarm> alarms = menu.alarms();
        int x = leftPos + LIST_X;
        int width = imageWidth - 2 * LIST_X - 6;
        if (alarms.isEmpty()) {
            AccentFrame.text(graphics, font, Component.translatable(AlarmTexts.ALL_CLEAR), x, topPos + LIST_Y, width,
                    AccentFrame.LABEL, mouseX, mouseY);
            return;
        }
        alarmScroll = Mth.clamp(alarmScroll, 0, Math.max(0, alarms.size() - VISIBLE));
        for (int i = 0; i < VISIBLE && alarmScroll + i < alarms.size(); i++) {
            Alarm alarm = alarms.get(alarmScroll + i);
            int y = topPos + LIST_Y + i * ROW_HEIGHT;
            int room = alarm.fresh() ? width - LOCATE_WIDTH - 4 : width;
            AccentFrame.text(graphics, font, alarmLine(alarm), x, y + 1, room, alarmColour(alarm), mouseX, mouseY);
            if (alarm.fresh()) {
                int bx = x + width - LOCATE_WIDTH;
                boolean hovered = over(mouseX, mouseY, bx, y, LOCATE_WIDTH, ROW_HEIGHT - 1);
                AccentFrame.button(graphics, font, bx, y, LOCATE_WIDTH, ROW_HEIGHT - 1,
                        Component.translatable(AlarmTexts.ACK), hovered);
            }
        }
        if (alarms.size() > VISIBLE) {
            float fraction = (float) alarmScroll / (alarms.size() - VISIBLE);
            AccentFrame.verticalBar(graphics, leftPos + imageWidth - 8, topPos + LIST_Y, 2, VISIBLE * ROW_HEIGHT,
                    1.0F - fraction);
        }
    }

    /**
     * The Recovery tab: one line per row, newest first, with its amount and time left, and a Recover button, greyed for
     * a viewer who may not recover; the time left counts down on the client's game time.
     */
    private void recovery(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        RecoveryTabPayload shownBin = menu.recovery();
        List<RecoveryBin.Row> rows = shownBin.rows();
        int x = leftPos + LIST_X;
        int width = imageWidth - 2 * LIST_X - 6;
        if (rows.isEmpty()) {
            Component empty = shownBin.keepTicks() <= 0
                    ? Component.translatable(RecoveryTexts.OFF)
                    : Component.translatable(RecoveryTexts.EMPTY, RecoveryBin.duration(shownBin.keepTicks()));
            AccentFrame.text(graphics, font, empty, x, topPos + LIST_Y, width, AccentFrame.LABEL, mouseX, mouseY);
            return;
        }
        long now = minecraft == null || minecraft.level == null ? 0 : minecraft.level.getGameTime();
        int visible = VISIBLE - 1;
        recoveryScroll = Mth.clamp(recoveryScroll, 0, Math.max(0, rows.size() - visible));
        for (int i = 0; i < visible && recoveryScroll + i < rows.size(); i++) {
            RecoveryBin.Row row = rows.get(recoveryScroll + i);
            int y = topPos + LIST_Y + i * ROW_HEIGHT;
            long left = Math.max(0, row.created() + shownBin.keepTicks() - now);
            Component line = KeyDisplay.name(row.key()).copy()
                    .append(" x " + KeyDisplay.amount(row.key(), row.amount())).append(" - ")
                    .append(Component.translatable(RecoveryTexts.LEFT, RecoveryBin.duration(left)));
            AccentFrame.text(graphics, font, line, x, y + 1, width - LOCATE_WIDTH - 4, AccentFrame.TEXT, mouseX,
                    mouseY);
            int bx = x + width - LOCATE_WIDTH;
            Component recover = Component.translatable(RecoveryTexts.RECOVER);
            if (shownBin.mayRecover()) {
                AccentFrame.button(graphics, font, bx, y, LOCATE_WIDTH, ROW_HEIGHT - 1, recover,
                        over(mouseX, mouseY, bx, y, LOCATE_WIDTH, ROW_HEIGHT - 1));
            } else {
                AccentFrame.disabledButton(graphics, font, bx, y, LOCATE_WIDTH, ROW_HEIGHT - 1, recover);
            }
        }
        if (!shownBin.mayRecover()) {
            AccentFrame.text(graphics, font, Component.translatable(RecoveryTexts.NOT_ALLOWED), x,
                    topPos + LIST_Y + visible * ROW_HEIGHT + 1, width, AccentFrame.LABEL, mouseX, mouseY);
        }
        if (rows.size() > visible) {
            float fraction = (float) recoveryScroll / (rows.size() - visible);
            AccentFrame.verticalBar(graphics, leftPos + imageWidth - 8, topPos + LIST_Y, 2, visible * ROW_HEIGHT,
                    1.0F - fraction);
        }
    }

    /** The menu button of the Recover button under the mouse, or -1 (also for a viewer who may not recover). */
    private int recoverAt(double mouseX, double mouseY) {
        RecoveryTabPayload shownBin = menu.recovery();
        int width = imageWidth - 2 * LIST_X - 6;
        double x = mouseX - leftPos - LIST_X - (width - LOCATE_WIDTH);
        double y = mouseY - topPos - LIST_Y;
        if (!shownBin.mayRecover() || x < 0 || x >= LOCATE_WIDTH || y < 0 || y >= (VISIBLE - 1) * ROW_HEIGHT) {
            return -1;
        }
        int index = recoveryScroll + (int) (y / ROW_HEIGHT);
        return index < shownBin.rows().size() ? ControllerMenu.RECOVER_BUTTON + shownBin.rows().get(index).id() : -1;
    }

    /** "Low power: Stored power at or below 15%", with who saw it or that it cleared. */
    private static Component alarmLine(Alarm alarm) {
        String mark = alarm.fresh()
                ? StatusPalette.mark(StatusPalette.Tone.BAD)
                : alarm.active() ? StatusPalette.mark(StatusPalette.Tone.WARN) : "";
        Component line = Component.literal(mark).append(Component.translatable(alarm.alarmKind().translationKey()))
                .append(": ").append(alarm.text());
        if (!alarm.active()) {
            return line.copy().append(" - ").append(Component.translatable(AlarmTexts.CLEARED));
        }
        if (alarm.acknowledged()) {
            return line.copy().append(" - ").append(Component.translatable(AlarmTexts.ACK_BY, alarm.ackBy()));
        }
        return line;
    }

    private static int alarmColour(Alarm alarm) {
        return alarm.fresh() ? StatusPalette.bad() : alarm.active() ? StatusPalette.warn() : AccentFrame.LABEL;
    }

    /** The menu button of the Ack button under the mouse, or -1. */
    private int ackAt(double mouseX, double mouseY) {
        int width = imageWidth - 2 * LIST_X - 6;
        double x = mouseX - leftPos - LIST_X - (width - LOCATE_WIDTH);
        double y = mouseY - topPos - LIST_Y;
        if (x < 0 || x >= LOCATE_WIDTH || y < 0 || y >= VISIBLE * ROW_HEIGHT) {
            return -1;
        }
        int index = alarmScroll + (int) (y / ROW_HEIGHT);
        List<Alarm> alarms = menu.alarms();
        return index < alarms.size() && alarms.get(index).fresh() ? ControllerMenu.ALARM_BUTTON + index : -1;
    }

    private void list(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int width = imageWidth - 2 * LIST_X - 6;
        for (int i = 0; i < VISIBLE && scroll + i < rows.size(); i++) {
            Row row = rows.get(scroll + i);
            int x = leftPos + LIST_X;
            int y = topPos + LIST_Y + i * ROW_HEIGHT;
            // A row with a Locate button leaves it room.
            int room = row.button() >= 0 ? width - LOCATE_WIDTH - 4 : width;
            AccentFrame.text(graphics, font, row.text(), x, y + 1, room, row.colour(), mouseX, mouseY);
            if (row.button() >= 0) {
                int bx = x + width - LOCATE_WIDTH;
                boolean hovered = mouseX >= bx && mouseX < bx + LOCATE_WIDTH && mouseY >= y
                        && mouseY < y + ROW_HEIGHT - 1;
                AccentFrame.button(graphics, font, bx, y, LOCATE_WIDTH, ROW_HEIGHT - 1,
                        Component.translatable(
                                row.button() == RENAME_BUTTON ? NetworkRenamePayload.RENAME_KEY : AnalyzerTexts.LOCATE),
                        hovered);
            }
        }
        if (rows.size() > VISIBLE) {
            float fraction = (float) scroll / (rows.size() - VISIBLE);
            AccentFrame.verticalBar(graphics, leftPos + imageWidth - 8, topPos + LIST_Y, 2, VISIBLE * ROW_HEIGHT,
                    1.0F - fraction);
        }
    }

    /** The Crafting tab: the craft limits with their step buttons, the free CPUs and what the limits apply to. */
    private void crafting(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int x = leftPos + LIST_X;
        int y = topPos + LIST_Y;
        int room = imageWidth - 2 * LIST_X;
        int flags = menu.limitData(ControllerMenu.DATA_FLAGS);
        boolean may = (flags & ControllerMenu.FLAG_MAY_CONFIGURE) != 0;
        AccentFrame.text(graphics, font, Component.translatable(AnalyzerTexts.HEADER_AUTOMATION), x, y, room,
                HEADER_COLOUR, mouseX, mouseY);
        int max = menu.limitData(ControllerMenu.DATA_MAX_PER_JOB);
        Component maxValue = max > 0
                ? Component.literal(InfoFormat.number(max))
                : Component.translatable(AnalyzerTexts.NO_LIMIT);
        limitRow(graphics, y + 14, Component.translatable(AnalyzerTexts.LIMIT_MAX_PER_JOB,
                fromConfig(maxValue, flags, ControllerMenu.FLAG_MAX_FROM_CONFIG)), mouseX, mouseY);
        Component kept = Component.literal(InfoFormat.number(menu.limitData(ControllerMenu.DATA_KEPT_FREE)));
        limitRow(graphics, y + 30, Component.translatable(AnalyzerTexts.LIMIT_KEPT_FREE,
                fromConfig(kept, flags, ControllerMenu.FLAG_KEPT_FROM_CONFIG)), mouseX, mouseY);
        AccentFrame.text(graphics, font,
                Component.translatable(AnalyzerTexts.CPUS_FREE,
                        InfoFormat.number(menu.limitData(ControllerMenu.DATA_FREE_CPUS)),
                        InfoFormat.number(menu.limitData(ControllerMenu.DATA_AUTO_CPUS))),
                x, y + 68, room, AccentFrame.TEXT, mouseX, mouseY);
        AccentFrame.text(graphics, font, Component.translatable(AnalyzerTexts.LIMITS_NOTE), x, y + 84, room,
                AccentFrame.LABEL, mouseX, mouseY);
        AccentFrame.text(graphics, font, Component.translatable(AnalyzerTexts.LIMITS_PLAYERS), x, y + 95, room,
                AccentFrame.LABEL, mouseX, mouseY);
        int keptNow = menu.limitData(ControllerMenu.DATA_KEPT_FREE);
        int line = y + 111;
        if (keptNow > 0 && menu.limitData(ControllerMenu.DATA_CPUS) <= keptNow) {
            AccentFrame.text(graphics, font,
                    Component.translatable(AnalyzerTexts.LIMITS_NO_CPU,
                            InfoFormat.number(menu.limitData(ControllerMenu.DATA_CPUS))),
                    x, line, room, StatusPalette.warn(), mouseX, mouseY);
            line += 11;
        }
        if (!may) {
            AccentFrame.text(graphics, font, Component.translatable(AnalyzerTexts.LIMITS_LOCKED), x, line, room,
                    StatusPalette.warn(), mouseX, mouseY);
        }
    }

    private static Component fromConfig(Component value, int flags, int flag) {
        return (flags & flag) != 0 ? Component.translatable(AnalyzerTexts.FROM_CONFIG, value) : value;
    }

    /** A limit's line; its down and up buttons on the right are widgets ({@link #init}). */
    private void limitRow(GuiGraphicsExtractor graphics, int y, Component text, int mouseX, int mouseY) {
        int x = leftPos + LIST_X;
        int room = imageWidth - 2 * LIST_X;
        AccentFrame.text(graphics, font, text, x, y + 2, room - 2 * STEP_WIDTH - 8, AccentFrame.TEXT, mouseX, mouseY);
    }

    /** The left edge of a limit row's down (0) or up (1) button. */
    private int stepX(int index) {
        return leftPos + imageWidth - LIST_X - (2 - index) * STEP_WIDTH - (1 - index) * 2;
    }

    private static boolean over(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private void rebuild(NetworkOverview overview) {
        rows.clear();
        NetworkOverview.Summary s = overview.summary();
        header(AnalyzerTexts.HEADER_NETWORK);
        if (!overview.name().isEmpty()) {
            rows.add(new Row(Component.translatable(NetworkRenamePayload.ROW_KEY, overview.name()), AccentFrame.TEXT,
                    RENAME_BUTTON));
        }
        text(Component.translatable(AnalyzerTexts.SUMMARY, InfoFormat.number(s.networkId()),
                InfoFormat.number(s.nodes()), InfoFormat.number(s.devices()), InfoFormat.number(s.zoneCount())));
        text(Component.translatable(NetworkInfo.statusKey(s.status())));
        List<Integer> roles = s.roles();
        if (roles.size() >= 5) {
            text(Component.translatable(AnalyzerTexts.ROLES, InfoFormat.number(roles.get(1)),
                    InfoFormat.number(roles.get(2)), InfoFormat.number(roles.get(3)), InfoFormat.number(roles.get(4))));
        }
        if (s.adHoc()) {
            text(Component.translatable(AnalyzerTexts.AD_HOC));
        } else {
            text(Component.translatable(AnalyzerTexts.LIMITS, InfoFormat.number(s.limitedDevices()),
                    s.deviceLimit() < 0 ? "-" : InfoFormat.number(s.deviceLimit()),
                    InfoFormat.number(s.rootLinksUsed()), InfoFormat.number(s.rootLinks())));
            text(Component.translatable(AnalyzerTexts.CLUSTER, InfoFormat.number(s.controllerBlocks()),
                    InfoFormat.number(s.sizeCap()), InfoFormat.number(s.batchPercent())));
        }
        text(Component.translatable(AnalyzerTexts.ACTIVITY, InfoFormat.number(s.operations()),
                InfoFormat.number(s.rebuilds()), InfoFormat.number(s.patches())));
        if (s.teamDevices() >= 0) {
            text(Component.translatable(AnalyzerTexts.CAPS, InfoFormat.number(s.teamDevices()), cap(s.capDevices()),
                    InfoFormat.number(s.teamZones()), cap(s.capZones()), InfoFormat.number(s.teamBytes()),
                    cap(s.capBytes())));
        }

        header(AnalyzerTexts.HEADER_POWER);
        text(Component.translatable(AnalyzerTexts.POWER, InfoFormat.number(s.storedFe()),
                InfoFormat.number(s.capacityFe())));
        text(Component.translatable(AnalyzerTexts.ENERGY_FLOW, InfoFormat.number(s.feIn()),
                InfoFormat.number(s.feOut())));
        if (s.subnetMode() >= 0) {
            text(Component.translatable(AnalyzerTexts.PARENT, mode(s.subnetMode()),
                    s.parentId() < 0 ? "-" : InfoFormat.number(s.parentId()),
                    InfoFormat.number(Math.max(0, s.parentStoredFe())),
                    InfoFormat.number(Math.max(0, s.parentCapacityFe()))));
        }

        header(AnalyzerTexts.HEADER_WARNINGS);
        if (overview.warnings().isEmpty()) {
            text(Component.translatable(AnalyzerTexts.NO_WARNINGS));
        }
        for (int i = 0; i < overview.warnings().size(); i++) {
            NetworkOverview.Warning warning = overview.warnings().get(i);
            rows.add(new Row(
                    Component.translatable(AnalyzerTexts.warning(warning.kind()), InfoFormat.number(warning.count())),
                    StatusPalette.warn(), warning.pos() == Long.MIN_VALUE ? -1 : i));
        }

        if (!overview.clusters().isEmpty()) {
            header(ClusterPreview.HEADER_KEY);
            for (ClusterNeeds.Need need : overview.clusters()) {
                StatusPalette.Tone tone = need.kind().tone();
                // Dim lines are plain facts (a heading, parts still to add), not a status.
                rows.add(tone == StatusPalette.Tone.DIM
                        ? new Row(ClusterPreview.text(need), AccentFrame.TEXT, -1)
                        : new Row(Component.literal(StatusPalette.mark(tone)).append(ClusterPreview.text(need)),
                                StatusPalette.colour(tone), -1));
            }
        }

        header(AnalyzerTexts.HEADER_HOTSPOTS);
        if (overview.hotspots().isEmpty()) {
            text(Component.translatable(AnalyzerTexts.NONE));
        }
        for (int i = 0; i < overview.hotspots().size(); i++) {
            NetworkOverview.Hotspot hotspot = overview.hotspots().get(i);
            rows.add(new Row(
                    Component.translatable(AnalyzerTexts.HOTSPOT, InfoFormat.number(hotspot.load()),
                            InfoFormat.number(hotspot.rate()),
                            Component.translatable(AnalyzerTexts.kind(hotspot.kind())), position(hotspot.pos())),
                    AnalyzerReport.loadColour(hotspot.load()), ControllerMenu.HOTSPOT_BUTTON + i));
        }

        header(AnalyzerTexts.HEADER_ZONES);
        for (NetworkOverview.Zone zone : overview.zones()) {
            rows.add(new Row(
                    Component.translatable(AnalyzerTexts.ZONE, InfoFormat.number(zone.zone()),
                            InfoFormat.number(zone.nodes()), InfoFormat.number(zone.peakLoad())),
                    AnalyzerReport.loadColour(zone.peakLoad()), -1));
        }

        if (!overview.subnets().isEmpty()) {
            header(AnalyzerTexts.HEADER_SUBNETS);
            for (NetworkOverview.Subnet subnet : overview.subnets()) {
                text(subnet.networkId() < 0
                        ? Component.translatable(AnalyzerTexts.SUBNET_UNLINKED, mode(subnet.mode()),
                                position(subnet.pos()))
                        : Component.translatable(AnalyzerTexts.SUBNET, mode(subnet.mode()), position(subnet.pos()),
                                InfoFormat.number(subnet.networkId()), InfoFormat.number(subnet.devices())));
            }
        }
        if (!overview.antennas().isEmpty()) {
            header(AnalyzerTexts.HEADER_ANTENNAS);
            for (NetworkOverview.Antenna antenna : overview.antennas()) {
                text(antenna.load() < 0
                        ? Component.translatable(AnalyzerTexts.ANTENNA_SPARE, position(antenna.pos()),
                                position(antenna.partner()), InfoFormat.number(antenna.rate()))
                        : Component.translatable(AnalyzerTexts.ANTENNA, position(antenna.pos()),
                                position(antenna.partner()), InfoFormat.number(antenna.load()),
                                InfoFormat.number(antenna.rate())));
            }
        }
        if (!overview.hubs().isEmpty()) {
            header(AnalyzerTexts.HEADER_HUBS);
            for (NetworkOverview.Hub hub : overview.hubs()) {
                // Capacity 0: channels have no limit, so the hub shows as in use, never as full.
                boolean infinite = hub.capacity() <= 0;
                int load = infinite ? Math.min(hub.used(), 1) : Channels.loadPercent(hub.used(), hub.capacity());
                rows.add(new Row(
                        Component.translatable(AnalyzerTexts.HUB, position(hub.pos()), InfoFormat.number(hub.used()),
                                NetworkInfo.capacityText(hub.capacity(), infinite)),
                        AnalyzerReport.loadColour(load), -1));
            }
        }
        if (!overview.labels().isEmpty()) {
            header(AnalyzerTexts.HEADER_LABELS);
            for (NetworkLabels.Entry label : overview.labels()) {
                text(Component.translatable(AnalyzerTexts.LABEL_ROW, label.label().component(),
                        InfoFormat.number(label.devices())));
            }
        }
        if (!overview.log().isEmpty()) {
            header(AnalyzerTexts.HEADER_LOG);
            for (NetworkNotices.Notice notice : overview.log()) {
                text(notice.line());
            }
        }
        installedCards();
        production(overview.history().production());
    }

    /** The Network tab's card section: each kind installed, with how many count. */
    private void installedCards() {
        header(CardContent.HEADER_KEY);
        boolean any = false;
        for (CardKind kind : CardKind.values()) {
            int count = menu.installed(kind);
            if (count > 0) {
                any = true;
                Component name = new ItemStack(CardContent.item(kind)).getHoverName();
                int limit = menu.cardLimit(kind);
                rows.add(new Row(Component.translatable(CardContent.ROW_KEY, name, InfoFormat.number(count)),
                        count > limit ? StatusPalette.warn() : AccentFrame.TEXT, -1));
            }
        }
        if (!any) {
            text(Component.translatable(CardContent.NONE_KEY));
        }
    }

    /** The production section: totals, then each CPU, stock keeper and pool, then the top items in and out. */
    private void production(NetworkOverview.Production production) {
        if (production.lines().isEmpty()) {
            return;
        }
        header(ProductionTexts.HEADER);
        text(Component.translatable(ProductionTexts.TOTALS, InfoFormat.number(production.totalIn()),
                InfoFormat.number(production.totalOut())));
        int topKind = -1;
        for (NetworkOverview.ProductionLine line : production.lines()) {
            boolean top = line.kind() >= NetworkOverview.ProductionLine.TOP_IN;
            if (top && line.kind() != topKind) {
                topKind = line.kind();
                text(Component.translatable(topKind == NetworkOverview.ProductionLine.TOP_IN
                        ? ProductionTexts.TOP_IN
                        : ProductionTexts.TOP_OUT));
            }
            Component name = top
                    ? itemName(line.name())
                    : Component.translatable(ProductionTexts.groupKey(line.kind()), line.name());
            Component rate = line.perMinute() > 0
                    ? Component.translatable(ProductionTexts.PER_MINUTE, InfoFormat.number(line.perMinute()))
                    : Component.translatable(ProductionTexts.IDLE);
            text(Component.empty().append(name).append(" ").append(rate));
        }
    }

    private static Component itemName(String id) {
        Identifier key = Identifier.tryParse(id);
        if (key != null) {
            return BuiltInRegistries.ITEM.getOptional(key).map(item -> new ItemStack(item).getHoverName())
                    .orElse(Component.literal(id));
        }
        return Component.literal(id);
    }

    private void header(String key) {
        rows.add(new Row(Component.translatable(key), HEADER_COLOUR, -1));
    }

    private void text(Component text) {
        rows.add(new Row(text, AccentFrame.TEXT, -1));
    }

    private static Component mode(int id) {
        return Component.translatable(SubnetMode.byId(id).translationKey());
    }

    private static String cap(long cap) {
        return cap > 0 ? InfoFormat.number(cap) : "-";
    }

    private static String position(long pos) {
        return BlockPos.getX(pos) + " " + BlockPos.getY(pos) + " " + BlockPos.getZ(pos);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            if (tab == TAB_HISTORY && records.click(event.x(), event.y())) {
                return true;
            }
            int button = switch (tab) {
                case TAB_CRAFTING -> -1;
                case TAB_ALARMS -> ackAt(event.x(), event.y());
                case TAB_RECOVERY -> recoverAt(event.x(), event.y());
                case TAB_CARDS -> -1;
                default -> locateAt(event.x(), event.y());
            };
            NetworkOverview overview = menu.overview();
            if (button == RENAME_BUTTON && minecraft != null && overview != null) {
                minecraft.setScreen(new NetworkNameScreen(this, menu, overview.name()));
                return true;
            }
            if (button >= 0 && minecraft != null && minecraft.gameMode != null) {
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, button);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** The menu button of the Locate button under the mouse, or -1. */
    private int locateAt(double mouseX, double mouseY) {
        if (tab != TAB_NETWORK) {
            return -1;
        }
        int width = imageWidth - 2 * LIST_X - 6;
        double x = mouseX - leftPos - LIST_X - (width - LOCATE_WIDTH);
        double y = mouseY - topPos - LIST_Y;
        if (x < 0 || x >= LOCATE_WIDTH || y < 0 || y >= VISIBLE * ROW_HEIGHT) {
            return -1;
        }
        int index = scroll + (int) (y / ROW_HEIGHT);
        return index < rows.size() ? rows.get(index).button() : -1;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (tab == TAB_HISTORY) {
            return records.scroll(scrollY);
        }
        if (tab == TAB_RECOVERY) {
            int most = Math.max(0, menu.recovery().rows().size() - VISIBLE);
            recoveryScroll = Mth.clamp(recoveryScroll - (int) Math.signum(scrollY), 0, most);
            return true;
        }
        if (tab == TAB_ALARMS) {
            int most = Math.max(0, menu.alarms().size() - VISIBLE);
            alarmScroll = Mth.clamp(alarmScroll - (int) Math.signum(scrollY), 0, most);
            return true;
        }
        int most = Math.max(0, rows.size() - VISIBLE);
        scroll = Mth.clamp(scroll - (int) Math.signum(scrollY), 0, most);
        return true;
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        // The panel draws the title; the inventory's label shows with the Cards tab only.
        if (tab == TAB_CARDS) {
            AccentFrame.text(graphics, font, playerInventoryTitle, ControllerMenu.INVENTORY_X,
                    ControllerMenu.INVENTORY_Y - 11, imageWidth - 2 * ControllerMenu.INVENTORY_X, AccentFrame.LABEL, xm,
                    ym);
        }
    }

    /** Item tooltips as usual; an empty card slot says what goes in, a locked one how to open it. */
    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractTooltip(graphics, mouseX, mouseY);
        if (tab != TAB_CARDS || !menu.getCarried().isEmpty()) {
            return;
        }
        for (int i = 0; i < ClusterStats.Cards.MOST; i++) {
            int sx = leftPos + ControllerMenu.CARD_X + i * 18;
            int sy = topPos + ControllerMenu.CARD_Y;
            if (over(mouseX, mouseY, sx - 1, sy - 1, 18, 18) && menu.slots.get(i).getItem().isEmpty()) {
                Component line = i < menu.cardSlots()
                        ? Component.translatable(CardContent.EMPTY_SLOT_KEY)
                        : Component.translatable(CardContent.LOCKED_SLOT_KEY);
                graphics.setComponentTooltipForNextFrame(font, List.of(line), mouseX, mouseY);
            }
        }
    }

    @Override
    protected void init() {
        super.init();
        records.layout(leftPos + LIST_X, topPos + LIST_Y, imageWidth - 2 * LIST_X);
        for (int i = 0; i < TABS; i++) {
            int index = i;
            Supplier<Component> label = () -> tabLabel(index);
            addRenderableWidget(new AccentButton(leftPos + LIST_X + i * TAB_STEP, topPos + TAB_Y, TAB_WIDTH, TAB_HEIGHT,
                    label.get(), () -> selectTab(index)).label(label).lit(() -> tab == index));
        }
        limitButtons.clear();
        int y = topPos + LIST_Y;
        for (int row = 0; row < 2; row++) {
            for (int i = 0; i < 2; i++) {
                String step = i == 0 ? "-" : "+";
                limitButtons
                        .add(addRenderableWidget(MenuClick
                                .button(stepX(i), y + 14 + 16 * row, STEP_WIDTH, STEP_HEIGHT,
                                        () -> Component.literal(step), menu, ControllerMenu.LIMIT_BUTTON + 2 * row + i)
                                .enabled(this::mayConfigure)));
            }
        }
        limitButtons.add(addRenderableWidget(MenuClick
                .button(leftPos + LIST_X, y + 46, RESET_WIDTH, STEP_HEIGHT + 2,
                        () -> Component.translatable(AnalyzerTexts.LIMITS_DEFAULT), menu, ControllerMenu.LIMITS_DEFAULT)
                .enabled(this::mayConfigure)));
        selectTab(tab);
    }
}
