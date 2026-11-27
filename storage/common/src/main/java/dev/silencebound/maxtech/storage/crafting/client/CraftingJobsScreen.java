package dev.silencebound.maxtech.storage.crafting.client;

import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.storage.palette.StatusPalette;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.client.gui.ScreenComponents;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.sync.ClientSyncedList;
import dev.silencebound.maxtech.storage.crafting.job.JobState;
import dev.silencebound.maxtech.storage.crafting.request.CraftingClientData;
import dev.silencebound.maxtech.storage.crafting.request.PlanView;
import dev.silencebound.maxtech.storage.crafting.request.RequestContent;
import dev.silencebound.maxtech.storage.crafting.request.RequestPayloads;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.lib.api.client.gui.AccentButton;
import dev.silencebound.maxtech.storage.terminal.client.TerminalScreen;
import dev.silencebound.maxtech.storage.terminal.client.TerminalSubScreen;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The crafting status screen, laid out like AE2's: one job at a time, the arrows cycling through the network's jobs
 * (those on a CPU first, then those waiting for one), its CPU, a progress bar and a live tile grid per item: "Crafting"
 * (in machines now), "Scheduled" (still to send), "Waiting" for items storage lacks (amber) and "Stored" (held in the
 * CPU). Extended mode adds the tree ({@link PlanTreeWidget}, counting crafts down) as a second view and Resume for a
 * partial job. The server sends the job list while this screen is open, the watched job's tiles and its tree's progress
 * as deltas. Client only.
 */
public final class CraftingJobsScreen extends TerminalSubScreen {
    public static final int WIDTH = 256;
    public static final int HEIGHT = 246;
    private static final int MARGIN = 8;
    private static final int GRID_Y = 58;
    private static final int GRID_HEIGHT = GridPanel.MAX_ROWS * TileGridWidget.TILE_HEIGHT + 2;
    /** Everything but the grid: its height stays when a short window takes grid rows away. */
    static final int FIXED_HEIGHT = HEIGHT - GRID_HEIGHT;

    private @Nullable TileGridWidget tiles;
    private @Nullable PlanTreeWidget tree;
    private boolean treeView;
    private int shownVersion = -1;
    private List<RequestPayloads.JobSummary> order = List.of();
    private @Nullable List<RequestPayloads.JobSummary> orderFrom;
    /** A view request sent and not answered yet; keeps the screen from asking again every tick. */
    private @Nullable UUID asked;

    /** The job to show first once the job list arrives (B09 item 21d: opened from a toast or a badge). */
    private @Nullable UUID focus;

    /** Opened on its own (the crafting tablet's jobs view): leaving closes the menu instead of showing a terminal. */
    private final boolean standalone;

    public CraftingJobsScreen(TerminalMenu menu, @Nullable TerminalScreen parent, Component terminalTitle) {
        this(menu, parent, terminalTitle, false);
    }

    private CraftingJobsScreen(TerminalMenu menu, @Nullable TerminalScreen parent, Component terminalTitle,
            boolean standalone) {
        super(Component.translatable(RequestContent.JOBS_TITLE_KEY), menu, parent, terminalTitle, WIDTH, HEIGHT);
        this.standalone = standalone;
    }

    /** The jobs screen over {@code menu} with no terminal behind it: Back and Escape close the menu (B09 item 23). */
    public static CraftingJobsScreen standalone(TerminalMenu menu, Component title) {
        return new CraftingJobsScreen(menu, null, title, true);
    }

    /** Shows job {@code job} first when it is listed, else the first job as usual; null changes nothing. */
    public CraftingJobsScreen focus(@Nullable UUID job) {
        this.focus = job;
        return this;
    }

    @Override
    protected void back() {
        if (standalone) {
            if (minecraft != null && minecraft.player != null) {
                minecraft.player.closeContainer();
            }
            return;
        }
        super.back();
    }

    private CraftingClientData data() {
        return menu.craftingData();
    }

    @Override
    protected void init() {
        super.init();
        int gridY = top + GRID_Y;
        int gridHeight = panelHeight - FIXED_HEIGHT;
        TileGridWidget grid = new TileGridWidget(font, left + MARGIN, gridY, WIDTH - 2 * MARGIN, gridHeight, 2);
        tiles = addRenderableWidget(grid);
        PlanTreeWidget widget = new PlanTreeWidget(font, left + MARGIN, gridY, WIDTH - 2 * MARGIN,
                gridHeight / PlanTreeWidget.ROW * PlanTreeWidget.ROW);
        widget.setProgress(this::remaining);
        tree = addRenderableWidget(widget);
        shownVersion = -1;
        addRenderableWidget(new AccentButton(left + MARGIN, top + 8, 16, 16, Component.literal("<"),
                () -> order.size() > 1 || (current() < 0 && !order.isEmpty()), () -> cycle(-1)));
        addRenderableWidget(new AccentButton(left + WIDTH - MARGIN - 62, top + 8, 16, 16, Component.literal(">"),
                () -> order.size() > 1 || (current() < 0 && !order.isEmpty()), () -> cycle(1)));
        addRenderableWidget(AccentButton.dynamic(left + WIDTH - MARGIN - 40, top + 8, 40, 16,
                () -> Component.translatable(treeView ? RequestContent.VIEW_TILES_KEY : RequestContent.VIEW_TREE_KEY),
                this::treeAvailable, input -> {
                    treeView = !treeView;
                    showView();
                }));
        int buttonY = top + panelHeight - MARGIN - 16;
        addRenderableWidget(new AccentButton(left + MARGIN, buttonY, 50, 16,
                Component.translatable(RequestContent.BACK_KEY), () -> true, this::back));
        addRenderableWidget(new AccentButton(left + WIDTH - MARGIN - 60, buttonY, 60, 16,
                Component.translatable(RequestContent.CANCEL_KEY), this::cancellable,
                () -> act(RequestPayloads.Action.CANCEL_JOB)));
        addRenderableWidget(new AccentButton(left + WIDTH - MARGIN - 124, buttonY, 60, 16,
                Component.translatable(RequestContent.RESUME_KEY), this::resumable,
                () -> act(RequestPayloads.Action.RESUME_JOB)));
        showView();
        send(RequestPayloads.Request.of(menu.containerId, RequestPayloads.Action.WATCH_JOBS));
    }

    @Override
    protected int panelHeight(int screenHeight) {
        return GridPanel.panelHeight(screenHeight, FIXED_HEIGHT);
    }

    @Override
    public void removed() {
        super.removed();
        send(RequestPayloads.Request.of(menu.containerId, RequestPayloads.Action.UNWATCH));
        data().clearJob();
    }

    private static void send(RequestPayloads.Request request) {
        Platform.get().network().sendToServer(request);
    }

    private boolean cancellable() {
        return data().job() != null && !data().jobState().finished();
    }

    private boolean resumable() {
        PlanView view = data().jobView();
        return data().job() != null && data().jobState() == JobState.PARTIAL && view != null && view.extended();
    }

    private boolean treeAvailable() {
        PlanView view = data().jobView();
        return view != null && view.extended() && !view.nodes().isEmpty();
    }

    private void showView() {
        boolean showTree = treeView && treeAvailable();
        if (tiles != null) {
            tiles.visible = !showTree;
        }
        if (tree != null) {
            tree.visible = showTree;
        }
    }

    private void act(RequestPayloads.Action action) {
        UUID job = data().job();
        if (job != null) {
            send(new RequestPayloads.Request(menu.containerId, action, 0, Optional.empty(), 0, Optional.of(job)));
        }
    }

    private void view(UUID job) {
        asked = job;
        send(new RequestPayloads.Request(menu.containerId, RequestPayloads.Action.VIEW_JOB, 0, Optional.empty(), 0,
                Optional.of(job)));
    }

    /** Shows the next ({@code by} 1) or previous ({@code by} -1) job of the cycle. */
    private void cycle(int by) {
        if (order.isEmpty()) {
            return;
        }
        int index = current();
        int next = index < 0 ? (by > 0 ? 0 : order.size() - 1) : Math.floorMod(index + by, order.size());
        view(order.get(next).id());
    }

    /** The watched job's place in the cycle, -1 when it is not listed (it ended). */
    private int current() {
        UUID job = data().job();
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).id().equals(job)) {
                return i;
            }
        }
        return -1;
    }

    /** Crafts node {@code node} of the watched job still has to run: 0 once done, -1 before progress arrived. */
    private long remaining(int node) {
        ClientSyncedList<StackKey> progress = data().progress();
        if (progress.size() == 0 && data().jobState() != JobState.DONE) {
            return -1;
        }
        return progress.entry(node) == null ? 0 : progress.count(node);
    }

    /** The jobs in cycle order: one per CPU first, then the ones waiting for a CPU, each in submission order. */
    static List<RequestPayloads.JobSummary> cycleOrder(List<RequestPayloads.JobSummary> jobs) {
        List<RequestPayloads.JobSummary> out = new ArrayList<>(jobs.size());
        for (RequestPayloads.JobSummary job : jobs) {
            if (job.onCpu()) {
                out.add(job);
            }
        }
        for (RequestPayloads.JobSummary job : jobs) {
            if (!job.onCpu()) {
                out.add(job);
            }
        }
        return out;
    }

    @Override
    @SuppressWarnings("ReferenceEquality") // the client data hands out a new list object with every payload
    public void tick() {
        super.tick();
        CraftingClientData data = data();
        if (orderFrom != data.jobs()) {
            orderFrom = data.jobs();
            order = cycleOrder(data.jobs());
        }
        UUID watched = data.job();
        if (watched != null && watched.equals(asked)) {
            asked = null;
        }
        if (watched == null && asked == null && !order.isEmpty()) {
            // Nothing watched yet (opened from the terminal): show the focused job, else the first.
            UUID first = order.getFirst().id();
            UUID wanted = focus;
            focus = null;
            if (wanted != null) {
                for (int i = 0; i < order.size(); i++) {
                    if (order.get(i).id().equals(wanted)) {
                        first = wanted;
                    }
                }
            }
            view(first);
        }
        if (shownVersion != data.version()) {
            shownVersion = data.version();
            if (tiles != null) {
                tiles.setTiles(data.jobTiles(), TileGridWidget.Kind.STATUS);
                tiles.setEmptyText(
                        data.jobView() == null ? null : Component.translatable(RequestContent.TILES_EMPTY_KEY));
            }
            if (tree != null) {
                tree.setView(data.jobView());
            }
            showView();
        }
    }

    private RequestPayloads.@Nullable JobSummary summary() {
        int index = current();
        return index < 0 ? null : order.get(index);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        CraftingClientData data = data();
        PlanView view = data.jobView();
        RequestPayloads.JobSummary summary = summary();
        int textX = left + MARGIN + 20;
        if (view == null) {
            Component empty = Component
                    .translatable(order.isEmpty() ? RequestContent.JOBS_EMPTY_KEY : RequestContent.PLANNING_KEY);
            AccentFrame.text(graphics, font, empty, textX, top + 12, WIDTH - MARGIN - 62 - 4 - textX + left,
                    AccentFrame.LABEL, mouseX, mouseY);
        } else {
            graphics.item(KeyDisplay.icon(view.key()), textX, top + 8);
            String name = KeyDisplay.amount(view.key(), view.amount()) + " x "
                    + KeyDisplay.name(view.key()).getString();
            AccentFrame.text(graphics, font, Component.literal(name), textX + 20, top + 12, WIDTH - 2 * MARGIN - 106,
                    AccentFrame.TEXT, mouseX, mouseY);
            JobState state = data.jobState();
            int stateColour = state == JobState.PARTIAL
                    ? StatusPalette.warn()
                    : state == JobState.DONE ? StatusPalette.good() : AccentFrame.TEXT;
            String stateMark = state == JobState.PARTIAL
                    ? StatusPalette.mark(StatusPalette.Tone.WARN)
                    : state == JobState.DONE ? StatusPalette.mark(StatusPalette.Tone.GOOD) : "";
            int index = current();
            int positionX = left + WIDTH - MARGIN;
            if (index >= 0) {
                Component position = Component.translatable(RequestContent.JOB_INDEX_KEY, index + 1, order.size());
                positionX -= font.width(position);
                TextScale.text(graphics, font, position, positionX, top + 29, AccentFrame.LABEL, false);
            }
            AccentFrame.text(graphics, font,
                    Component.literal(stateMark).append(Component.translatable(RequestContent.stateKey(state))),
                    left + MARGIN, top + 29, positionX - 6 - left - MARGIN, stateColour, mouseX, mouseY);
            long percent = percent(summary, state);
            Component done = Component.translatable(RequestContent.JOB_PROGRESS_KEY, percent);
            int doneX = left + WIDTH - MARGIN - font.width(done);
            TextScale.text(graphics, font, done, doneX, top + 40, AccentFrame.LABEL, false);
            AccentFrame.text(graphics, font, cpuLine(summary), left + MARGIN, top + 40, doneX - 6 - left - MARGIN,
                    AccentFrame.LABEL, mouseX, mouseY);
            int barX = left + MARGIN;
            int barWidth = WIDTH - 2 * MARGIN;
            graphics.fill(barX, top + 51, barX + barWidth, top + 55, AccentFrame.SLOT_EDGE);
            graphics.fill(barX, top + 51, barX + (int) (barWidth * percent / 100), top + 55, AccentFrame.BAR_TOP);
        }
        String notice = data.notice();
        if (notice != null) {
            AccentFrame.text(graphics, font,
                    Component.literal(StatusPalette.mark(StatusPalette.Tone.BAD))
                            .append(Component.translatable(notice)),
                    left + MARGIN, top + panelHeight - MARGIN - 28, WIDTH - 2 * MARGIN, StatusPalette.bad(), mouseX,
                    mouseY);
        } else if (view != null && !data.jobStuck().isEmpty()) {
            extractStuck(graphics, data.jobStuck(), mouseX, mouseY);
        }
        if (tiles != null && tiles.visible) {
            tiles.extractTooltip(graphics, mouseX, mouseY);
        }
        if (tree != null && tree.visible) {
            tree.extractTooltip(graphics, mouseX, mouseY);
        }
    }

    /** The first provider holding the job up, in amber; hovering lists every one (M7C item 9). */
    private void extractStuck(GuiGraphicsExtractor graphics, List<RequestPayloads.Stuck> stuck, int mouseX,
            int mouseY) {
        int y = top + panelHeight - MARGIN - 28;
        List<Component> lines = new ArrayList<>(stuck.size());
        for (RequestPayloads.Stuck line : stuck) {
            lines.add(Component.literal(StatusPalette.mark(StatusPalette.Tone.WARN))
                    .append(Component.translatable(RequestContent.STUCK_KEY, KeyDisplay.name(line.key()),
                            line.pos().getX(), line.pos().getY(), line.pos().getZ(),
                            Component.translatable(line.reason().translationKey()))));
        }
        TextScale.text(graphics, font, lines.getFirst(), left + MARGIN, y, StatusPalette.warn(), false);
        if (mouseX >= left + MARGIN && mouseX < left + WIDTH - MARGIN && mouseY >= y && mouseY < y + 9) {
            ScreenComponents.tooltip(graphics, font, lines, mouseX, mouseY);
        }
    }

    private static Component cpuLine(RequestPayloads.@Nullable JobSummary summary) {
        if (summary == null || !summary.onCpu()) {
            return Component.translatable(RequestContent.JOB_NO_CPU_KEY);
        }
        BlockPos pos = BlockPos.of(summary.cpu());
        return summary.cpuTier() > 0
                ? Component.translatable(RequestContent.JOB_CPU_TIER_KEY, summary.cpuTier(), pos.getX(), pos.getY(),
                        pos.getZ())
                : Component.translatable(RequestContent.JOB_CPU_KEY, pos.getX(), pos.getY(), pos.getZ());
    }

    private static long percent(RequestPayloads.@Nullable JobSummary summary, JobState state) {
        if (state == JobState.DONE) {
            return 100;
        }
        if (summary == null || summary.crafts() == 0) {
            return 0;
        }
        return Math.clamp(100 - summary.remaining() * 100 / summary.crafts(), 0, 100);
    }
}
