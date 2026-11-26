package dev.silencebound.maxtech.storage.crafting.client;

import dev.silencebound.maxtech.lib.client.gui.TextScale;
import dev.silencebound.maxtech.storage.palette.StatusPalette;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.crafting.order.OrderList;
import dev.silencebound.maxtech.storage.crafting.order.client.OrderScreen;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.crafting.request.CraftDestinations;
import dev.silencebound.maxtech.storage.crafting.request.CraftingClientData;
import dev.silencebound.maxtech.storage.crafting.request.CraftingSession;
import dev.silencebound.maxtech.storage.crafting.request.PlanView;
import dev.silencebound.maxtech.storage.crafting.request.RequestContent;
import dev.silencebound.maxtech.storage.crafting.request.RequestPayloads;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.lib.client.gui.accent.AccentButton;
import dev.silencebound.maxtech.storage.terminal.client.TerminalScreen;
import dev.silencebound.maxtech.storage.terminal.client.TerminalSubScreen;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Requests a craft in two steps, laid out like AE2's: the amount (a number field between rows of +1 to +1000 and -1 to
 * -1000, shift for ten times the step), then the server's plan as a grid of tiles, one per item, with what comes from
 * storage, what is crafted and what is missing. Extended mode adds the tree ({@link PlanTreeWidget}) as a second view,
 * a CPU tier choice and "Start partial" for a plan with missing items; classic mode shows the tiles and starts only a
 * complete plan. A "deliver to" button beside the view switch picks where the finished items go
 * ({@link CraftDestinations}). Start queues the job and switches to the jobs screen. Client only.
 */
public final class CraftRequestScreen extends TerminalSubScreen {
    public static final int WIDTH = 256;
    public static final int HEIGHT = 232;
    private static final int MARGIN = 8;
    private static final int GRID_Y = 28;
    private static final int GRID_HEIGHT = GridPanel.MAX_ROWS * TileGridWidget.TILE_HEIGHT + 2;
    /** Everything but the grid: its height stays when a short window takes grid rows away. */
    static final int FIXED_HEIGHT = HEIGHT - GRID_HEIGHT;
    private static final int STEP_WIDTH = 44;
    private static final int[] STEPS = {1, 10, 100, 1000};
    private static final int DELIVER_WIDTH = 64;

    private enum Step {
        AMOUNT, PLAN
    }

    private final StackKey key;
    private final long initialAmount;
    /** An order list's requests (M7C item 6), empty for a single request. */
    private final List<CraftingPlanner.Root> order;
    /** The order list's text, for going back to it. */
    private final String orderText;
    private Step step;
    private @Nullable EditBox amount;
    private String amountText;
    private @Nullable TileGridWidget tiles;
    private @Nullable PlanTreeWidget tree;
    private boolean treeView;
    private int cpuTier;
    /** Where the finished items go, as {@link CraftDestinations} numbers the choices. */
    private int deliverTo = CraftDestinations.STORAGE;
    private int shownVersion = -1;
    private @Nullable UUID jobBefore;
    private boolean started;

    /**
     * @param autoPlan
     *            plan {@code initialAmount} at once, skipping the amount step (a recipe viewer asked for exactly that
     *            much)
     */
    public CraftRequestScreen(TerminalMenu menu, @Nullable TerminalScreen parent, Component terminalTitle, StackKey key,
            long initialAmount, boolean autoPlan) {
        this(menu, parent, terminalTitle, key, initialAmount, autoPlan, List.of(), "");
    }

    private CraftRequestScreen(TerminalMenu menu, @Nullable TerminalScreen parent, Component terminalTitle,
            StackKey key, long initialAmount, boolean autoPlan, List<CraftingPlanner.Root> order, String orderText) {
        super(order.isEmpty()
                ? Component.translatable(RequestContent.REQUEST_TITLE_KEY, KeyDisplay.name(key))
                : Component.translatable(OrderList.ORDER_TITLE_KEY, order.size()), menu, parent, terminalTitle, WIDTH,
                HEIGHT);
        this.order = List.copyOf(order);
        this.orderText = orderText;
        this.key = key;
        this.initialAmount = Math.clamp(initialAmount, 1, CraftingSession.MAX_AMOUNT);
        this.amountText = Long.toString(this.initialAmount);
        this.step = autoPlan ? Step.PLAN : Step.AMOUNT;
        if (autoPlan) {
            plan();
        }
    }

    /**
     * Plans the order list {@code roots} (M7C item 6) at once and shows its plan; Back returns to the order list with
     * {@code text}.
     */
    public static CraftRequestScreen order(TerminalMenu menu, @Nullable TerminalScreen parent, Component terminalTitle,
            List<CraftingPlanner.Root> roots, String text) {
        CraftingPlanner.Root first = roots.getFirst();
        return new CraftRequestScreen(menu, parent, terminalTitle, first.key(), first.amount(), true, roots, text);
    }

    private CraftingClientData data() {
        return menu.craftingData();
    }

    @Override
    protected void init() {
        super.init();
        amount = null;
        tiles = null;
        tree = null;
        shownVersion = -1;
        if (step == Step.AMOUNT) {
            initAmount();
        } else {
            initPlan();
        }
    }

    private void initAmount() {
        int rowWidth = STEPS.length * STEP_WIDTH + (STEPS.length - 1) * 4;
        int rowX = left + (WIDTH - rowWidth) / 2;
        for (int i = 0; i < STEPS.length; i++) {
            int by = STEPS[i];
            int x = rowX + i * (STEP_WIDTH + 4);
            addRenderableWidget(AccentButton.dynamic(x, top + 44, STEP_WIDTH, 16, () -> Component.literal("+" + by),
                    () -> true, input -> add(by, input)));
            addRenderableWidget(AccentButton.dynamic(x, top + 92, STEP_WIDTH, 16, () -> Component.literal("-" + by),
                    () -> true, input -> add(-by, input)));
        }
        EditBox box = new EditBox(font, left + (WIDTH - 90) / 2, top + 70, 90, 12,
                Component.translatable(RequestContent.AMOUNT_KEY));
        box.setMaxLength(10);
        box.setFilter(text -> text.isEmpty() || text.chars().allMatch(Character::isDigit));
        box.setValue(amountText);
        box.setResponder(text -> amountText = text);
        box.setTextColor(AccentFrame.TEXT);
        amount = addRenderableWidget(box);
        setInitialFocus(box);
        addRenderableWidget(new AccentButton(left + MARGIN, top + panelHeight - MARGIN - 16, 50, 16,
                Component.translatable(RequestContent.BACK_KEY), () -> true, this::back));
        addRenderableWidget(new AccentButton(left + WIDTH - MARGIN - 60, top + panelHeight - MARGIN - 16, 60, 16,
                Component.translatable(RequestContent.NEXT_KEY), () -> amountValue() > 0 && !data().planning(),
                this::next));
    }

    private void initPlan() {
        int gridY = top + GRID_Y;
        int gridHeight = gridHeight();
        TileGridWidget grid = new TileGridWidget(font, left + MARGIN, gridY, WIDTH - 2 * MARGIN, gridHeight, 2);
        tiles = addRenderableWidget(grid);
        PlanTreeWidget widget = new PlanTreeWidget(font, left + MARGIN, gridY, WIDTH - 2 * MARGIN,
                gridHeight / PlanTreeWidget.ROW * PlanTreeWidget.ROW);
        tree = addRenderableWidget(widget);
        showView();
        addRenderableWidget(AccentButton.dynamic(left + WIDTH - MARGIN - 40, top + 8, 40, 16,
                () -> Component.translatable(treeView ? RequestContent.VIEW_TILES_KEY : RequestContent.VIEW_TREE_KEY),
                this::treeAvailable, input -> {
                    treeView = !treeView;
                    showView();
                }));
        AccentButton deliver = AccentButton.dynamic(left + WIDTH - MARGIN - 40 - 4 - DELIVER_WIDTH, top + 8,
                DELIVER_WIDTH, 16, this::deliverLabel, () -> true, input -> {
                    int choices = CraftDestinations.LABEL + destinations().size();
                    deliverTo = Math.floorMod(deliverTo + (input.hasShiftDown() ? -1 : 1), choices);
                });
        deliver.setTooltip(Tooltip.create(Component.translatable(CraftDestinations.HINT_KEY)));
        addRenderableWidget(deliver);
        int buttonY = top + panelHeight - MARGIN - 16;
        addRenderableWidget(new AccentButton(left + MARGIN, buttonY, 50, 16,
                Component.translatable(RequestContent.BACK_KEY), () -> true, this::toAmount));
        AccentButton cpu = AccentButton.dynamic(left + MARGIN + 56, buttonY, 84, 16, this::cpuLabel, this::extended,
                input -> cpuTier = Math.floorMod(cpuTier + (input.hasShiftDown() ? -1 : 1), 4));
        cpu.setTooltip(Tooltip.create(Component.translatable(RequestContent.CPU_HINT_KEY)));
        addRenderableWidget(cpu);
        addRenderableWidget(AccentButton.dynamic(left + WIDTH - MARGIN - 80, buttonY, 80, 16, this::startLabel,
                this::canStart, input -> start()));
    }

    @Override
    protected int panelHeight(int screenHeight) {
        return GridPanel.panelHeight(screenHeight, FIXED_HEIGHT);
    }

    private int gridHeight() {
        return panelHeight - FIXED_HEIGHT;
    }

    private boolean extended() {
        PlanView plan = data().plan();
        return plan != null && plan.extended();
    }

    private boolean treeAvailable() {
        PlanView plan = data().plan();
        return plan != null && plan.extended() && !plan.nodes().isEmpty();
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

    private List<DeviceLabel> destinations() {
        return CraftDestinations.shown(menu.containerId);
    }

    /** The delivery choice, back to storage when the label list it pointed at got shorter. */
    private int deliverTo() {
        if (deliverTo >= CraftDestinations.LABEL + destinations().size()) {
            deliverTo = CraftDestinations.STORAGE;
        }
        return deliverTo;
    }

    private Component deliverLabel() {
        int choice = deliverTo();
        if (choice == CraftDestinations.STORAGE) {
            return Component.translatable(CraftDestinations.STORAGE_KEY);
        }
        if (choice == CraftDestinations.PLAYER) {
            return Component.translatable(CraftDestinations.PLAYER_KEY);
        }
        return Component.translatable(CraftDestinations.LABEL_KEY,
                destinations().get(choice - CraftDestinations.LABEL).component());
    }

    private Component cpuLabel() {
        return cpuTier == 0 || !extended()
                ? Component.translatable(RequestContent.CPU_AUTO_KEY)
                : Component.translatable(RequestContent.CPU_TIER_KEY, cpuTier);
    }

    private boolean partial(PlanView plan) {
        return plan.status() == PlanView.Status.MISSING && plan.extended();
    }

    private Component startLabel() {
        PlanView plan = data().plan();
        return Component.translatable(
                plan != null && partial(plan) ? RequestContent.START_PARTIAL_KEY : RequestContent.START_KEY);
    }

    private void add(int by, InputWithModifiers input) {
        long step = input.hasShiftDown() ? by * 10L : by;
        long next = Math.clamp(Math.max(0, amountValue()) + step, 1, CraftingSession.MAX_AMOUNT);
        amountText = Long.toString(next);
        EditBox box = amount;
        if (box != null) {
            box.setValue(amountText);
        }
    }

    private long amountValue() {
        if (amountText.isEmpty()) {
            return 0;
        }
        try {
            return Math.min(CraftingSession.MAX_AMOUNT, Long.parseLong(amountText));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void next() {
        if (amountValue() <= 0) {
            return;
        }
        plan();
        step = Step.PLAN;
        rebuildWidgets();
    }

    private void toAmount() {
        if (!order.isEmpty()) {
            if (minecraft != null) {
                minecraft.setScreen(new OrderScreen(menu, parentTerminal(), terminalTitle(), orderText));
            }
            return;
        }
        step = Step.AMOUNT;
        started = false;
        rebuildWidgets();
    }

    private void plan() {
        long value = amountValue();
        if (value <= 0) {
            return;
        }
        int request = data().newRequest();
        if (!order.isEmpty()) {
            Platform.get().network().sendToServer(new OrderList.Plan(menu.containerId, request, order));
            return;
        }
        Platform.get().network().sendToServer(new RequestPayloads.Request(menu.containerId, RequestPayloads.Action.PLAN,
                request, Optional.of(key), value, Optional.empty()));
    }

    private boolean canStart() {
        PlanView plan = data().plan();
        return plan != null && (plan.complete() || partial(plan)) && !data().planning() && !started;
    }

    private void start() {
        jobBefore = data().job();
        started = true;
        data().clearNotice();
        int tier = extended() ? cpuTier : 0;
        Platform.get().network().sendToServer(new RequestPayloads.Request(menu.containerId,
                RequestPayloads.Action.START, deliverTo(), Optional.empty(), tier, Optional.empty()));
    }

    @Override
    public void tick() {
        super.tick();
        CraftingClientData data = data();
        if (started && data.job() != null && !data.job().equals(jobBefore) && minecraft != null) {
            minecraft.setScreen(new CraftingJobsScreen(menu, parentTerminal(), terminalTitle()));
            return;
        }
        if (started && data.notice() != null) {
            // The start was refused; the notice says why.
            started = false;
        }
        if (shownVersion != data.version()) {
            shownVersion = data.version();
            PlanView plan = data.plan();
            if (tiles != null) {
                tiles.setTiles(plan == null ? List.of() : plan.tiles(), TileGridWidget.Kind.PLAN);
            }
            if (tree != null) {
                tree.setView(plan);
            }
            showView();
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (step == Step.AMOUNT && event.isConfirmation()) {
            next();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.item(KeyDisplay.icon(key), left + MARGIN, top + 8);
        if (step == Step.AMOUNT) {
            AccentFrame.text(graphics, font, KeyDisplay.name(key), left + MARGIN + 20, top + 12,
                    WIDTH - 2 * MARGIN - 20, AccentFrame.TEXT, mouseX, mouseY);
            Component hint = Component.translatable(RequestContent.AMOUNT_HINT_KEY);
            AccentFrame.text(graphics, font, hint, left + Math.max(MARGIN, (WIDTH - font.width(hint)) / 2), top + 118,
                    WIDTH - 2 * MARGIN, AccentFrame.LABEL, mouseX, mouseY);
            extractNotice(graphics, mouseX, mouseY);
            return;
        }
        Component header = order.isEmpty()
                ? Component.literal(KeyDisplay.amount(key, amountValue()) + " x " + KeyDisplay.name(key).getString())
                : OrderList.summary(order, order.size());
        AccentFrame.text(graphics, font, header, left + MARGIN + 20, top + 12, WIDTH - 2 * MARGIN - 70 - DELIVER_WIDTH,
                AccentFrame.TEXT, mouseX, mouseY);
        CraftingClientData data = data();
        PlanView plan = data.plan();
        int statusY = top + GRID_Y + gridHeight() + 4;
        int infoY = statusY + 11;
        if (data.planning()) {
            TextScale.text(graphics, font, Component.translatable(RequestContent.PLANNING_KEY), left + MARGIN, statusY,
                    AccentFrame.LABEL, false);
        } else if (plan != null) {
            Component status = plan.status() == PlanView.Status.MISSING
                    ? Component.translatable(RequestContent.MISSING_WARNING_KEY, plan.missing().size())
                    : Component.translatable(statusKey(plan.status()));
            int colour = plan.complete() ? StatusPalette.good() : StatusPalette.bad();
            status = Component
                    .literal(StatusPalette.mark(plan.complete() ? StatusPalette.Tone.GOOD : StatusPalette.Tone.BAD))
                    .append(status);
            Component estimate = plan.ticks() >= 0
                    ? Component.translatable(RequestContent.ESTIMATE_KEY, PlanLayout.duration(plan.ticks()))
                    : plan.extended() && !plan.nodes().isEmpty()
                            ? Component.translatable(RequestContent.ESTIMATE_UNKNOWN_KEY)
                            : Component.empty();
            int estimateX = left + WIDTH - MARGIN - font.width(estimate);
            TextScale.text(graphics, font, estimate, estimateX, statusY, AccentFrame.LABEL, false);
            AccentFrame.text(graphics, font, status, left + MARGIN, statusY, estimateX - 6 - left - MARGIN, colour,
                    mouseX, mouseY);
        }
        if (!extractNotice(graphics, mouseX, mouseY) && plan != null) {
            Component info = null;
            if (plan.stale()) {
                info = Component.translatable(RequestContent.STALE_KEY);
            } else if (partial(plan)) {
                info = Component.translatable(RequestContent.PARTIAL_HINT_KEY);
            } else if (treeView && plan.hidden() > 0 && !plan.nodes().isEmpty()) {
                info = Component.translatable(RequestContent.HIDDEN_KEY, plan.hidden());
            }
            if (info != null) {
                AccentFrame.text(graphics, font, info, left + MARGIN, infoY, WIDTH - 2 * MARGIN, AccentFrame.LABEL,
                        mouseX, mouseY);
            }
        }
        if (tiles != null && tiles.visible) {
            tiles.extractTooltip(graphics, mouseX, mouseY);
        }
        if (tree != null && tree.visible) {
            tree.extractTooltip(graphics, mouseX, mouseY);
        }
    }

    /** Draws the last notice above the buttons; false when there is none. */
    private boolean extractNotice(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        String notice = data().notice();
        if (notice == null) {
            return false;
        }
        Component text = Component.literal(StatusPalette.mark(StatusPalette.Tone.BAD))
                .append(Component.translatable(notice));
        AccentFrame.text(graphics, font, text, left + MARGIN, top + panelHeight - MARGIN - 29, WIDTH - 2 * MARGIN,
                StatusPalette.bad(), mouseX, mouseY);
        return true;
    }

    static String statusKey(PlanView.Status status) {
        return switch (status) {
            case COMPLETE -> RequestContent.STATUS_COMPLETE_KEY;
            case MISSING -> RequestContent.STATUS_MISSING_KEY;
            case TOO_MANY_NODES -> RequestContent.STATUS_TOO_MANY_KEY;
            case TIME_LIMIT -> RequestContent.STATUS_TIME_KEY;
            case ERROR -> RequestContent.STATUS_ERROR_KEY;
        };
    }
}
