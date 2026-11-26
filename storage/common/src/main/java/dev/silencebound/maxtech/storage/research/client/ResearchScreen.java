package dev.silencebound.maxtech.storage.research.client;

import dev.silencebound.maxtech.storage.devicetab.client.DeviceScreen;
import dev.silencebound.maxtech.lib.client.gui.TextScale;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.research.ResearchAction;
import dev.silencebound.maxtech.storage.research.ResearchMenu;
import dev.silencebound.maxtech.storage.research.ResearchNode;
import dev.silencebound.maxtech.storage.research.ResearchOption;
import dev.silencebound.maxtech.storage.research.ResearchTexts;
import dev.silencebound.maxtech.storage.research.ResearchTree;
import dev.silencebound.maxtech.storage.research.ResearchView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The research screen (Accent Frame): the branches as a scrolling list with a row per node (icon, name, progress), the
 * selected node's options with their progress and buttons for points, puzzles and a skip token, and a progression panel
 * with the next tier's goal. Names, icons and branches come from the client's copy of the synced tree; state and
 * progress from the server's {@link ResearchView}. Clicks go back as container button ids.
 */
public final class ResearchScreen extends DeviceScreen<ResearchMenu> {
    public static final int WIDTH = 282;
    public static final int HEIGHT = 206;
    private static final int LIST_X = 7;
    private static final int LIST_Y = 34;
    private static final int LIST_WIDTH = 124;
    private static final int ROW = 18;
    private static final int ROWS = 7;
    private static final int DETAIL_X = 138;
    private static final int DETAIL_WIDTH = 137;
    private static final int TIER_Y = 166;
    private static final int BUTTON_HEIGHT = 12;
    private static final int LOCKED_TEXT = 0xFF5E676C;

    private final List<Row> rows = new ArrayList<>();
    private final Map<Identifier, ItemStack> icons = new HashMap<>();
    /** Buttons drawn this frame, for clicks: position and the action they send. */
    private final List<Hit> hits = new ArrayList<>();
    private ResearchTree tree = ResearchTree.of(RegistryAccess.EMPTY);
    private int scroll;
    private int selected = -1;
    /** The mouse in screen coordinates while the labels draw, for the tooltips of shortened lines. */
    private int screenMouseX;
    private int screenMouseY;

    /** A list row: a branch header ({@code entry} -1) or a node by its index in the tree. */
    private record Row(String branch, int entry) {
    }

    private record Hit(int x, int y, int width, int height, ResearchAction action) {
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }

    public ResearchScreen(ResearchMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        this.titleLabelY = -100;
        this.inventoryLabelY = -100;
    }

    @Override
    protected void init() {
        super.init();
        ClientPacketListener connection = minecraft == null ? null : minecraft.getConnection();
        if (connection != null) {
            tree = ResearchTree.of(connection.registryAccess());
        }
        rows.clear();
        String branch = null;
        List<ResearchTree.Entry> entries = tree.entries();
        for (int i = 0; i < entries.size(); i++) {
            String current = entries.get(i).node().branch();
            if (!current.equals(branch)) {
                branch = current;
                rows.add(new Row(current, -1));
            }
            rows.add(new Row(current, i));
        }
        if (selected < 0 && !entries.isEmpty()) {
            selected = 0;
        }
        // The server sends nothing until asked, so the first view cannot arrive before this menu exists.
        send(new ResearchAction(ResearchAction.Kind.REFRESH, 0, 0));
    }

    private void send(ResearchAction action) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, action.encode());
        }
    }

    @Override
    protected void extractPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        AccentFrame.panel(graphics, font, leftPos, topPos, imageWidth, imageHeight, title, mouseX, mouseY);
        inset(graphics, leftPos + LIST_X - 1, topPos + LIST_Y - 1, LIST_WIDTH + 2, ROWS * ROW + 2);
        inset(graphics, leftPos + DETAIL_X - 1, topPos + LIST_Y - 1, DETAIL_WIDTH + 2, ROWS * ROW + 2);
        inset(graphics, leftPos + LIST_X - 1, topPos + TIER_Y - 1, imageWidth - 2 * LIST_X + 2, 34);
    }

    private static void inset(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, AccentFrame.SLOT_EDGE);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, AccentFrame.SLOT);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        hits.clear();
        screenMouseX = xm;
        screenMouseY = ym;
        ResearchView view = menu.view();
        if (view == null) {
            TextScale.text(graphics, font, Component.translatable(ResearchTexts.WAITING), LIST_X, 12, AccentFrame.LABEL,
                    false);
            return;
        }
        if (view.off()) {
            TextScale.text(graphics, font, Component.translatable(ResearchTexts.OFF), LIST_X, 12, AccentFrame.TEXT,
                    false);
            return;
        }
        TextScale.text(graphics, font, Component.translatable(ResearchTexts.POINTS, view.points()), LIST_X, 12,
                AccentFrame.TEXT, false);
        TextScale.text(graphics, font, Component.translatable(ResearchTexts.TOKENS, view.tokens()), LIST_X + 90, 12,
                AccentFrame.TEXT, false);
        TextScale.text(graphics, font,
                Component.translatable(ResearchTexts.NEXT_TOKEN, view.tokenProgress(), view.pointsPerToken()), LIST_X,
                22, AccentFrame.LABEL, false);
        list(graphics, view, xm - leftPos, ym - topPos);
        // Options and long lists of requirements can be taller than the panel: clip them, and only what shows clicks.
        graphics.enableScissor(DETAIL_X, LIST_Y, DETAIL_X + DETAIL_WIDTH, LIST_Y + ROWS * ROW);
        detail(graphics, view, xm - leftPos, ym - topPos);
        graphics.disableScissor();
        tiers(graphics, view);
    }

    private void list(GuiGraphicsExtractor graphics, ResearchView view, int mouseX, int mouseY) {
        scroll = Math.clamp(scroll, 0, Math.max(0, rows.size() - ROWS));
        for (int i = 0; i < ROWS && scroll + i < rows.size(); i++) {
            Row row = rows.get(scroll + i);
            int y = LIST_Y + i * ROW;
            if (row.entry() < 0) {
                TextScale.text(graphics, font, Component.translatable(ResearchNode.branchKey(row.branch())), LIST_X + 3,
                        y + 5, AccentFrame.LABEL, false);
                continue;
            }
            ResearchTree.Entry entry = tree.entries().get(row.entry());
            ResearchView.Node node = node(view, row.entry());
            boolean hovered = mouseX >= LIST_X && mouseX < LIST_X + LIST_WIDTH && mouseY >= y && mouseY < y + ROW;
            if (row.entry() == selected || hovered) {
                graphics.fill(LIST_X, y, LIST_X + LIST_WIDTH, y + ROW,
                        row.entry() == selected ? AccentFrame.ACCENT_DIM : AccentFrame.BEVEL);
            }
            graphics.item(icon(entry), LIST_X + 1, y + 1);
            boolean locked = node == null || node.state() == ResearchView.State.LOCKED;
            fit(graphics, Component.translatable(ResearchNode.nameKey(entry.id())), LIST_X + 20, y + 2, LIST_WIDTH - 22,
                    locked ? LOCKED_TEXT : AccentFrame.TEXT);
            AccentFrame.horizontalBar(graphics, LIST_X + 20, y + 13, LIST_WIDTH - 24, 2, progress(node));
        }
    }

    private void detail(GuiGraphicsExtractor graphics, ResearchView view, int mouseX, int mouseY) {
        if (selected < 0 || selected >= tree.entries().size()) {
            return;
        }
        ResearchTree.Entry entry = tree.entries().get(selected);
        ResearchView.Node node = node(view, selected);
        int x = DETAIL_X + 3;
        int width = DETAIL_WIDTH - 6;
        int y = LIST_Y + 3;
        fit(graphics, Component.translatable(ResearchNode.nameKey(entry.id())), x, y, width, AccentFrame.TEXT);
        y += 10;
        ResearchView.State state = node == null ? ResearchView.State.LOCKED : node.state();
        String stateKey = switch (state) {
            case LOCKED -> ResearchTexts.LOCKED;
            case AVAILABLE -> ResearchTexts.AVAILABLE;
            case COMPLETE -> ResearchTexts.COMPLETE;
        };
        TextScale.text(graphics, font, Component.translatable(stateKey), x, y, AccentFrame.LABEL, false);
        y += 11;
        if (state == ResearchView.State.LOCKED && !entry.node().requires().isEmpty()) {
            y = wrapped(graphics, Component.translatable(ResearchTexts.REQUIRES, names(entry.node().requires(), false)),
                    x, y, width, AccentFrame.LABEL);
        }
        List<ResearchOption> options = entry.node().anyOf();
        if (!options.isEmpty() && node != null) {
            TextScale.text(graphics, font, Component.translatable(ResearchTexts.ANY_OF), x, y, AccentFrame.LABEL,
                    false);
            y += 10;
            boolean actionable = state == ResearchView.State.AVAILABLE;
            for (int i = 0; i < options.size() && i < node.options().size(); i++) {
                y = option(graphics, options.get(i), node.options().get(i), i, actionable, x, y, width, mouseX, mouseY);
            }
            if (actionable && view.tokens() > 0) {
                y = button(graphics, Component.translatable(ResearchTexts.USE_TOKEN), x, y, width, mouseX, mouseY,
                        new ResearchAction(ResearchAction.Kind.USE_TOKEN, selected, 0));
            }
        }
        if (!entry.node().unlocks().isEmpty()) {
            wrapped(graphics, Component.translatable(ResearchTexts.UNLOCKS, names(entry.node().unlocks(), true)), x,
                    y + 1, width, AccentFrame.LABEL);
        }
    }

    private int option(GuiGraphicsExtractor graphics, ResearchOption option, ResearchView.Option progress, int index,
            boolean actionable, int x, int y, int width, int mouseX, int mouseY) {
        return switch (option) {
            case ResearchOption.Task task -> {
                Component line = taskLine(task, progress.target());
                if (!progress.offered()) {
                    line = Component.translatable(ResearchTexts.SKIPPED, line);
                }
                fit(graphics, line, x, y, width, progress.offered() ? AccentFrame.TEXT : LOCKED_TEXT);
                AccentFrame.horizontalBar(graphics, x, y + 10, width - 40, 3, progress.fraction());
                String count = compact(Math.min(progress.progress(), progress.target())) + "/"
                        + compact(progress.target());
                TextScale.text(graphics, font, count, x + width - font.width(count), y + 8, AccentFrame.LABEL, false);
                yield y + 18;
            }
            case ResearchOption.Puzzle puzzle -> {
                Component name = Component
                        .translatable("gui." + MaxtechStorage.MOD_ID + ".puzzle." + puzzle.puzzle().getPath());
                if (!progress.offered()) {
                    fit(graphics, Component.translatable(ResearchTexts.PUZZLE_MISSING, name), x, y, width, LOCKED_TEXT);
                    yield y + 11;
                }
                Component line = Component.translatable(ResearchTexts.PUZZLE, name, Component.translatable(
                        "gui." + MaxtechStorage.MOD_ID + ".puzzle.difficulty." + difficulty(puzzle.difficulty())));
                yield actionButton(graphics, line, x, y, width, mouseX, mouseY, actionable,
                        new ResearchAction(ResearchAction.Kind.PUZZLE, selected, index));
            }
            case ResearchOption.Points points -> {
                Component line = Component.translatable(ResearchTexts.SPEND, progress.target());
                yield actionButton(graphics, line, x, y, width, mouseX, mouseY,
                        actionable && progress.progress() >= progress.target(),
                        new ResearchAction(ResearchAction.Kind.SPEND_POINTS, selected, index));
            }
        };
    }

    private int actionButton(GuiGraphicsExtractor graphics, Component label, int x, int y, int width, int mouseX,
            int mouseY, boolean enabled, ResearchAction action) {
        if (!enabled) {
            fit(graphics, label, x, y + 2, width, LOCKED_TEXT);
            return y + BUTTON_HEIGHT + 2;
        }
        return button(graphics, label, x, y, width, mouseX, mouseY, action);
    }

    private int button(GuiGraphicsExtractor graphics, Component label, int x, int y, int width, int mouseX, int mouseY,
            ResearchAction action) {
        Hit hit = new Hit(x, y, width, BUTTON_HEIGHT, action);
        if (y + BUTTON_HEIGHT <= LIST_Y + ROWS * ROW) {
            hits.add(hit);
        }
        AccentFrame.button(graphics, font, x, y, width, BUTTON_HEIGHT, label, hit.contains(mouseX, mouseY),
                screenMouseX, screenMouseY);
        return y + BUTTON_HEIGHT + 2;
    }

    /** The progression panel: the lowest tier with research left, how far it is, its material and what comes next. */
    private void tiers(GuiGraphicsExtractor graphics, ResearchView view) {
        int tier = -1;
        int done = 0;
        int total = 0;
        ResearchTree.Entry next = null;
        List<ResearchTree.Entry> entries = tree.entries();
        for (int i = 0; i < entries.size(); i++) {
            ResearchView.Node node = node(view, i);
            if (node != null && node.state() != ResearchView.State.COMPLETE
                    && (tier < 0 || entries.get(i).node().tier() < tier)) {
                tier = entries.get(i).node().tier();
            }
        }
        int x = LIST_X + 3;
        if (tier < 0) {
            TextScale.text(graphics, font, Component.translatable(ResearchTexts.ALL_DONE), x, TIER_Y + 3,
                    AccentFrame.TEXT, false);
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            ResearchView.Node node = node(view, i);
            if (node == null || entries.get(i).node().tier() != tier) {
                continue;
            }
            total++;
            if (node.state() == ResearchView.State.COMPLETE) {
                done++;
            } else if (next == null && node.state() == ResearchView.State.AVAILABLE) {
                next = entries.get(i);
            }
        }
        int room = imageWidth - 2 * LIST_X - 6;
        fit(graphics,
                Component.translatable(ResearchTexts.NEXT_TIER, Component.translatable(ResearchNode.tierKey(tier))), x,
                TIER_Y + 3, 136, AccentFrame.TEXT);
        fit(graphics, Component.translatable(ResearchTexts.TIER_PROGRESS, done, total), x + 140, TIER_Y + 3, room - 140,
                AccentFrame.LABEL);
        fit(graphics,
                Component.translatable(ResearchTexts.TIER_MATERIAL,
                        Component.translatable(ResearchNode.tierMaterialKey(tier))),
                x, TIER_Y + 13, room, AccentFrame.LABEL);
        if (next != null) {
            fit(graphics,
                    Component.translatable(ResearchTexts.TIER_NEXT_NODE,
                            Component.translatable(ResearchNode.nameKey(next.id()))),
                    x, TIER_Y + 23, room, AccentFrame.TEXT);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double x = event.x() - leftPos;
        double y = event.y() - topPos;
        if (event.button() == 0) {
            for (Hit hit : hits) {
                if (hit.contains(x, y)) {
                    send(hit.action());
                    return true;
                }
            }
            if (x >= LIST_X && x < LIST_X + LIST_WIDTH && y >= LIST_Y && y < LIST_Y + ROWS * ROW) {
                int index = scroll + (int) ((y - LIST_Y) / ROW);
                if (index < rows.size() && rows.get(index).entry() >= 0) {
                    selected = rows.get(index).entry();
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll -= (int) Math.signum(scrollY);
        return true;
    }

    private static ResearchView.@Nullable Node node(ResearchView view, int index) {
        return index < view.nodes().size() ? view.nodes().get(index) : null;
    }

    private static float progress(ResearchView.@Nullable Node node) {
        if (node == null) {
            return 0;
        }
        if (node.state() == ResearchView.State.COMPLETE) {
            return 1;
        }
        float best = 0;
        for (ResearchView.Option option : node.options()) {
            if (option.offered()) {
                best = Math.max(best, option.fraction());
            }
        }
        return best;
    }

    private ItemStack icon(ResearchTree.Entry entry) {
        return icons.computeIfAbsent(entry.id(), id -> entry.node().icon()
                .map(icon -> new ItemStack(BuiltInRegistries.ITEM.getValue(icon))).orElse(ItemStack.EMPTY));
    }

    private Component taskLine(ResearchOption.Task task, long target) {
        MutableComponent line = Component.translatable(ResearchTexts.task(task.task()), compact(target));
        if (task.item().isPresent()) {
            return Component.translatable(ResearchTexts.filter(), line,
                    new ItemStack(BuiltInRegistries.ITEM.getValue(task.item().get())).getHoverName());
        }
        if (task.tag().isPresent()) {
            return Component.translatable(ResearchTexts.filter(), line, "#" + task.tag().get().location());
        }
        if (task.machine().isPresent()) {
            return Component.translatable(ResearchTexts.filter(), line,
                    Component.translatable("block." + MaxtechStorage.MOD_ID + "." + task.machine().get()));
        }
        return line;
    }

    private static Component names(List<Identifier> ids, boolean unlocks) {
        MutableComponent joined = Component.empty();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                joined.append(", ");
            }
            joined.append(Component
                    .translatable(unlocks ? ResearchNode.unlockKey(ids.get(i)) : ResearchNode.nameKey(ids.get(i))));
        }
        return joined;
    }

    private int wrapped(GuiGraphicsExtractor graphics, Component text, int x, int y, int width, int colour) {
        for (FormattedCharSequence line : font.split(text, width)) {
            TextScale.text(graphics, font, line, x, y, colour, false);
            y += 9;
        }
        return y + 1;
    }

    /** One line cut to {@code width} with an ellipsis; the whole line is its tooltip. */
    private void fit(GuiGraphicsExtractor graphics, Component text, int x, int y, int width, int colour) {
        AccentFrame.text(graphics, font, text, x, y, width, colour, screenMouseX, screenMouseY);
    }

    private static String difficulty(int difficulty) {
        return switch (difficulty) {
            case 0 -> "easy";
            case 1 -> "medium";
            default -> "hard";
        };
    }

    private static String compact(long value) {
        if (value >= 1_000_000) {
            return String.format(java.util.Locale.ROOT, "%.1fM", value / 1_000_000.0);
        }
        if (value >= 10_000) {
            return value / 1000 + "k";
        }
        return Long.toString(value);
    }
}
