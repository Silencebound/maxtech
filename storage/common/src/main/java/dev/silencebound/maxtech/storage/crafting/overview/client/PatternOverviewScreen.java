package dev.silencebound.maxtech.storage.crafting.overview.client;

import dev.silencebound.maxtech.storage.palette.StatusPalette;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.crafting.client.KeyDisplay;
import dev.silencebound.maxtech.storage.crafting.overview.CrafterManager;
import dev.silencebound.maxtech.storage.crafting.overview.PatternConflicts;
import dev.silencebound.maxtech.storage.crafting.overview.PatternOverview;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;
import dev.silencebound.maxtech.storage.crafting.request.RequestContent;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.lib.client.gui.accent.AccentButton;
import dev.silencebound.maxtech.storage.terminal.client.TerminalScreen;
import dev.silencebound.maxtech.storage.terminal.client.TerminalSubScreen;
import dev.silencebound.maxtech.storage.terminal.client.TerminalTab;
import dev.silencebound.maxtech.storage.terminal.client.TerminalTabs;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The terminal's pattern overview tab (M7C items 1 and 2): every pattern in the network's pattern providers, searched
 * by output name or provider label, with the provider holding it. The picked pattern shows its provider, slot and
 * priority, a warning when several patterns make its item, priority buttons and "Open provider", which opens that
 * provider's screen. "Manage providers" opens the crafter manager (B09 item 23) with every provider's pattern slots.
 * Changing needs full access; others see the list only. The server sends the list when the tab opens and after each
 * change. Client only.
 */
public final class PatternOverviewScreen extends TerminalSubScreen {
    public static final int WIDTH = 300;
    public static final int HEIGHT = 222;
    private static final int MARGIN = 8;
    private static final int LIST_TOP = 36;
    private static final int ROW = 18;
    private static final int LIST_WIDTH = 168;
    private static final int ROWS = (HEIGHT - LIST_TOP - MARGIN - 22) / ROW;
    private static final int RIGHT = MARGIN + LIST_WIDTH + 8;
    private static final int RIGHT_WIDTH = WIDTH - RIGHT - MARGIN;
    private static final int[] STEPS = {-10, -1, 1, 10};

    private String search = "";
    private int scroll;
    private PatternOverview.@Nullable Row picked;
    /** The list the filter was built from; rebuilt when another arrives. */
    private PatternOverview.@Nullable Patterns shownFrom;
    private String shownSearch = "";
    private List<PatternOverview.Row> shown = List.of();
    private List<Integer> shownConflicts = List.of();

    public PatternOverviewScreen(TerminalMenu menu, @Nullable TerminalScreen parent, Component terminalTitle) {
        super(Component.translatable(PatternOverview.TITLE_KEY), menu, parent, terminalTitle, WIDTH, HEIGHT);
    }

    /** Adds the tab to network terminals; called once from client setup. */
    public static void registerTab() {
        TerminalTabs.register(new TerminalTab(MaxtechStorage.id("pattern_overview"), TerminalTab.sprite("tab_patterns"),
                Component.translatable(PatternOverview.TITLE_KEY), TerminalMenu::networked,
                (parent, menu) -> new PatternOverviewScreen(menu, parent, parent.getTitle())));
    }

    @Override
    protected void init() {
        super.init();
        EditBox box = new EditBox(font, left + MARGIN, top + 20, LIST_WIDTH, 12,
                Component.translatable(PatternOverview.SEARCH_KEY));
        box.setHint(Component.translatable(PatternOverview.SEARCH_KEY));
        box.setMaxLength(50);
        box.setValue(search);
        box.setResponder(text -> {
            search = text;
            scroll = 0;
        });
        box.setTextColor(AccentFrame.TEXT);
        addRenderableWidget(box);
        int x = left + RIGHT;
        int stepWidth = (RIGHT_WIDTH - 6) / STEPS.length;
        for (int i = 0; i < STEPS.length; i++) {
            int step = STEPS[i];
            AccentButton button = new AccentButton(x + i * (stepWidth + 2), top + 110, stepWidth, 16,
                    Component.literal(step > 0 ? "+" + step : Integer.toString(step)), this::editable,
                    () -> changePriority(step));
            button.setTooltip(Tooltip.create(Component.translatable(PatternOverview.PRIORITY_HINT_KEY)));
            addRenderableWidget(button);
        }
        addRenderableWidget(new AccentButton(x, top + 130, RIGHT_WIDTH, 16,
                Component.translatable(PatternOverview.OPEN_KEY), this::editable, this::openProvider));
        addRenderableWidget(new AccentButton(left + MARGIN, top + HEIGHT - MARGIN - 16, 50, 16,
                Component.translatable(RequestContent.BACK_KEY), () -> true, this::back));
        // The crafter manager (B09 item 23): every provider's slots; the server checks access again.
        addRenderableWidget(new AccentButton(left + MARGIN + 54, top + HEIGHT - MARGIN - 16, LIST_WIDTH - 54, 16,
                Component.translatable(CrafterManager.OPEN_KEY), () -> data() != null,
                () -> Platform.get().network().sendToServer(new CrafterManager.Open(menu.containerId)))
                .tooltip(Component.translatable(CrafterManager.OPEN_HINT_KEY)));
        Platform.get().network().sendToServer(new PatternOverview.Watch(menu.containerId));
    }

    private PatternOverview.@Nullable Patterns data() {
        return PatternOverview.received(menu.containerId);
    }

    private boolean editable() {
        PatternOverview.Patterns data = data();
        return data != null && data.canEdit() && current() != null;
    }

    /** The picked row as in the latest list, matched by provider and slot; null when it is gone. */
    private PatternOverview.@Nullable Row current() {
        PatternOverview.Patterns data = data();
        PatternOverview.Row choice = picked;
        if (data == null || choice == null) {
            return null;
        }
        for (PatternOverview.Row row : data.rows()) {
            if (row.pos().equals(choice.pos()) && row.facing() == choice.facing() && row.slot() == choice.slot()) {
                return row;
            }
        }
        return null;
    }

    private void changePriority(int step) {
        PatternOverview.Row row = current();
        if (row != null && editable()) {
            Platform.get().network().sendToServer(new PatternOverview.SetPriority(menu.containerId, row.pos(),
                    row.facing(), row.slot(), PatternOverview.clampPriority(row.priority() + step)));
        }
    }

    private void openProvider() {
        PatternOverview.Row row = current();
        if (row != null && editable()) {
            Platform.get().network().sendToServer(new PatternOverview.Open(menu.containerId, row.pos(), row.facing()));
        }
    }

    /** Rebuilds the filtered rows and their conflict counts when the list or the search changed. */
    // Each list arrives as a new object; identity is the cheap per-frame check for a new one.
    @SuppressWarnings("ReferenceEquality")
    private void refilter() {
        PatternOverview.Patterns data = data();
        if (data == shownFrom && search.equals(shownSearch)) {
            return;
        }
        shownFrom = data;
        shownSearch = search;
        if (data == null) {
            shown = List.of();
            shownConflicts = List.of();
            return;
        }
        int[] conflicts = conflicts(data);
        String needle = search.trim().toLowerCase(Locale.ROOT);
        List<PatternOverview.Row> rows = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (int i = 0; i < data.rows().size(); i++) {
            PatternOverview.Row row = data.rows().get(i);
            if (needle.isEmpty() || matches(row, needle)) {
                rows.add(row);
                counts.add(conflicts[i]);
            }
        }
        shown = rows;
        shownConflicts = counts;
        scroll = Math.clamp(scroll, 0, Math.max(0, shown.size() - ROWS));
    }

    /** How many different patterns make each row's item. */
    private static int[] conflicts(PatternOverview.Patterns data) {
        List<CraftingPattern> patterns = new ArrayList<>(data.rows().size());
        for (PatternOverview.Row row : data.rows()) {
            patterns.add(row.pattern());
        }
        return PatternConflicts.counts(patterns, pattern -> pattern.mainOutput().key());
    }

    private static boolean matches(PatternOverview.Row row, String needle) {
        for (PatternOutput output : row.pattern().outputs()) {
            if (KeyDisplay.name(output.key()).getString().toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return row.label().text().toLowerCase(Locale.ROOT).contains(needle);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        refilter();
        scroll = Math.clamp(scroll - (scrollY > 0 ? 1 : scrollY < 0 ? -1 : 0), 0, Math.max(0, shown.size() - ROWS));
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        refilter();
        if (event.x() >= left + MARGIN && event.x() < left + MARGIN + LIST_WIDTH && event.y() >= top + LIST_TOP) {
            int index = scroll + (int) ((event.y() - top - LIST_TOP) / ROW);
            if (index < shown.size() && index < scroll + ROWS) {
                picked = shown.get(index);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        refilter();
        PatternOverview.Patterns data = data();
        if (data == null || data.rows().isEmpty()) {
            AccentFrame.text(graphics, font, Component.translatable(PatternOverview.EMPTY_KEY), left + MARGIN,
                    top + LIST_TOP, LIST_WIDTH, AccentFrame.LABEL, mouseX, mouseY);
            return;
        }
        PatternOverview.Row choice = current();
        for (int i = 0; i < ROWS && scroll + i < shown.size(); i++) {
            PatternOverview.Row row = shown.get(scroll + i);
            int y = top + LIST_TOP + i * ROW;
            if (row.equals(choice)) {
                graphics.fill(left + MARGIN, y, left + MARGIN + LIST_WIDTH, y + ROW, AccentFrame.SLOT);
            }
            PatternOutput main = row.pattern().mainOutput();
            graphics.item(KeyDisplay.icon(main.key()), left + MARGIN + 1, y + 1);
            int conflicts = shownConflicts.get(scroll + i);
            int textWidth = LIST_WIDTH - 22 - (conflicts > 1 ? 10 : 0);
            Component text = Component.literal(KeyDisplay.amount(main.key(), main.amount()) + " ")
                    .append(KeyDisplay.name(main.key()));
            AccentFrame.text(graphics, font, text, left + MARGIN + 20, y + 5, textWidth, AccentFrame.TEXT, mouseX,
                    mouseY);
            if (conflicts > 1) {
                AccentFrame.text(graphics, font, Component.literal(StatusPalette.Tone.WARN.glyph()),
                        left + MARGIN + LIST_WIDTH - 8, y + 5, 6, StatusPalette.warn(), mouseX, mouseY);
            }
        }
        AccentFrame.text(graphics, font,
                data.hidden() > 0
                        ? Component.translatable(PatternOverview.HIDDEN_KEY, data.hidden())
                        : Component.translatable(PatternOverview.COUNT_KEY, data.rows().size()),
                left + RIGHT, top + 22, RIGHT_WIDTH, AccentFrame.LABEL, mouseX, mouseY);
        if (choice != null) {
            details(graphics, choice, mouseX, mouseY);
        }
        if (!data.canEdit()) {
            AccentFrame.text(graphics, font, Component.translatable(PatternOverview.READ_ONLY_KEY), left + RIGHT,
                    top + HEIGHT - MARGIN - 12, RIGHT_WIDTH, AccentFrame.LABEL, mouseX, mouseY);
        }
    }

    private void details(GuiGraphicsExtractor graphics, PatternOverview.Row row, int mouseX, int mouseY) {
        int x = left + RIGHT;
        PatternOutput main = row.pattern().mainOutput();
        graphics.item(KeyDisplay.icon(main.key()), x, top + 36);
        AccentFrame.text(graphics, font, KeyDisplay.name(main.key()), x + 20, top + 40, RIGHT_WIDTH - 20,
                AccentFrame.TEXT, mouseX, mouseY);
        Component name = row.part()
                ? ProviderContent.PROVIDER_PART_ITEM.get()
                        .getName(new ItemStack(ProviderContent.PROVIDER_PART_ITEM.get()))
                : ProviderContent.PROVIDER.get().getName();
        Component provider = row.label().isEmpty()
                ? Component.translatable(PatternOverview.PROVIDER_KEY, name, row.pos().getX(), row.pos().getY(),
                        row.pos().getZ())
                : Component.translatable(PatternOverview.PROVIDER_LABEL_KEY, name, row.label().component(),
                        row.pos().getX(), row.pos().getY(), row.pos().getZ());
        AccentFrame.text(graphics, font, provider, x, top + 58, RIGHT_WIDTH, AccentFrame.LABEL, mouseX, mouseY);
        AccentFrame.text(graphics, font, Component.translatable(PatternOverview.SLOT_KEY, row.slot() + 1), x, top + 70,
                RIGHT_WIDTH, AccentFrame.LABEL, mouseX, mouseY);
        AccentFrame.text(graphics, font, Component.translatable(PatternOverview.PRIORITY_KEY, row.priority()), x,
                top + 98, RIGHT_WIDTH, AccentFrame.TEXT, mouseX, mouseY);
        int count = conflictsOf(row);
        if (count > 1) {
            AccentFrame.text(graphics, font,
                    Component.literal(StatusPalette.mark(StatusPalette.Tone.WARN))
                            .append(Component.translatable(PatternOverview.CONFLICT_KEY, count)),
                    x, top + 84, RIGHT_WIDTH, StatusPalette.warn(), mouseX, mouseY);
            if (mouseX >= x && mouseX < x + RIGHT_WIDTH && mouseY >= top + 84 && mouseY < top + 94) {
                graphics.setTooltipForNextFrame(font, Component.translatable(PatternOverview.CONFLICT_HINT_KEY), mouseX,
                        mouseY);
            }
        }
    }

    private int conflictsOf(PatternOverview.Row row) {
        for (int i = 0; i < shown.size(); i++) {
            if (shown.get(i).equals(row)) {
                return shownConflicts.get(i);
            }
        }
        PatternOverview.Patterns data = data();
        int index = data == null ? -1 : data.rows().indexOf(row);
        return data == null || index < 0 ? 1 : conflicts(data)[index];
    }
}
