package dev.silencebound.maxtech.storage.board.client;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.client.gui.ScreenComponents;
import dev.silencebound.maxtech.lib.client.gui.TextScale;
import dev.silencebound.maxtech.lib.client.gui.VirtualGrid;
import dev.silencebound.maxtech.lib.client.gui.accent.AccentButton;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.sync.SyncedListIndex;
import dev.silencebound.maxtech.storage.board.BoardContent;
import dev.silencebound.maxtech.storage.board.BoardMenu;
import dev.silencebound.maxtech.storage.board.BoardView;
import dev.silencebound.maxtech.storage.config.StorageClientConfig;
import dev.silencebound.maxtech.storage.devicetab.client.DeviceScreen;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.terminal.AmountFormat;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import dev.silencebound.maxtech.storage.terminal.TerminalView;
import dev.silencebound.maxtech.storage.terminal.client.TerminalTooltips;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * A request board's screen in the Accent Frame style (B09 item 30): left, a search box and a read-only grid of the
 * items the board's network holds; right, the request slot with the Post buttons, then one row per open request with
 * its item (marked with how many the network holds), how many are still wanted and by whom, Fulfil, and a take-down
 * button on the viewer's own requests. A click on a grid item, or an item dragged from a recipe viewer, picks what the
 * Post buttons ask for; with nothing picked they ask for the held item, as before. Picking never takes anything. The
 * rows come from {@link BoardView}, the grid from the terminal's synced list. The mouse wheel scrolls the list or the
 * grid under it. Client only.
 */
public final class BoardScreen extends DeviceScreen<BoardMenu> {
    private static final int ROW_POST = 18;
    private static final int GRID_X = 8;
    private static final int GRID_COLUMNS = 5;
    private static final int CELL = 18;
    private static final int LIST_X = GRID_X + GRID_COLUMNS * CELL + VirtualGrid.SCROLLBAR_WIDTH + 8;
    private static final int LIST_Y = 38;
    private static final int ROW_HEIGHT = 18;
    private static final int VISIBLE = 8;
    private static final int BUTTON_HEIGHT = 14;
    private static final int POST_WIDTH = 22;
    private static final int POST_X = BoardMenu.WIDTH - 8 - BoardMenu.POST_COUNT * (POST_WIDTH + 2) + 2;
    private static final int FULFIL_WIDTH = 36;
    private static final int CANCEL_WIDTH = 14;
    private static final int SEARCH_WIDTH = GRID_COLUMNS * CELL + VirtualGrid.SCROLLBAR_WIDTH + 2;

    private int scroll;
    /** Each visible row's Fulfil and Cancel buttons, shown while the row has an entry (Cancel: one of yours). */
    private final AccentButton[] fulfil = new AccentButton[VISIBLE];
    private final AccentButton[] cancel = new AccentButton[VISIBLE];
    private final SyncedListIndex<StackKey> index;
    private @Nullable VirtualGrid grid;
    private @Nullable EditBox search;
    /** The item the Post buttons ask for; null for the held item. */
    private @Nullable ItemKey picked;
    /** How many of each key the network holds, rebuilt when the synced list changes. */
    private final Object2LongOpenHashMap<StackKey> held = new Object2LongOpenHashMap<>();
    private int heldVersion = -1;

    public BoardScreen(BoardMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, BoardMenu.WIDTH, BoardMenu.HEIGHT);
        this.titleLabelY = -100;
        this.inventoryLabelY = -100;
        this.index = new SyncedListIndex<>(menu.clientList(), TerminalTooltips.INSTANCE);
        // Requests are for items only.
        index.setFilter(TerminalView.ITEMS.filter());
        index.setSort(StorageClientConfig.sort(), StorageClientConfig.ascending());
    }

    private int fulfilX() {
        return imageWidth - 8 - CANCEL_WIDTH - 2 - FULFIL_WIDTH;
    }

    private int cancelX() {
        return imageWidth - 8 - CANCEL_WIDTH;
    }

    /** The request slot (an item's top left, panel coordinates). */
    private static int slotX() {
        return LIST_X + 1;
    }

    private static int slotY() {
        return ROW_POST - 1;
    }

    /** Where the request slot is on the screen, for a recipe viewer's drag. */
    public Rect2i requestSlotArea() {
        return new Rect2i(leftPos + slotX(), topPos + slotY(), 16, 16);
    }

    /** Picks {@code item} for the Post buttons; nothing is taken. */
    public void pick(ItemKey item) {
        picked = item;
    }

    /** How many of {@code key} the network holds as the client knows it; 0 without a network or view access. */
    private long held(StackKey key) {
        if (heldVersion != menu.clientList().version()) {
            heldVersion = menu.clientList().version();
            held.clear();
            for (IntIterator it = menu.clientList().ids().iterator(); it.hasNext();) {
                int id = it.nextInt();
                StackKey entry = menu.clientList().entry(id);
                if (entry != null) {
                    held.put(entry, menu.clientList().count(id));
                }
            }
        }
        return held.getLong(key);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        EditBox box = search;
        if (box != null) {
            index.setSearch(box.getValue());
        }
    }

    @Override
    protected void extractPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        AccentFrame.panel(graphics, font, leftPos, topPos, imageWidth, imageHeight, title, mouseX, mouseY);
        graphics.fill(leftPos + GRID_X, topPos + ROW_POST - 1, leftPos + GRID_X + SEARCH_WIDTH, topPos + ROW_POST + 13,
                AccentFrame.SLOT_EDGE);
        graphics.fill(leftPos + GRID_X + 1, topPos + ROW_POST, leftPos + GRID_X + SEARCH_WIDTH - 1,
                topPos + ROW_POST + 12, AccentFrame.SLOT);
        for (int row = 0; row < VISIBLE; row++) {
            for (int column = 0; column < GRID_COLUMNS; column++) {
                AccentFrame.slot(graphics, leftPos + GRID_X + column * CELL + 1, topPos + LIST_Y + row * CELL + 1);
            }
        }
        AccentFrame.box(graphics, leftPos + LIST_X - 2, topPos + LIST_Y - 2, imageWidth - LIST_X - 8 + 4,
                VISIBLE * ROW_HEIGHT + 4);
        AccentFrame.slot(graphics, leftPos + slotX(), topPos + slotY());
        ItemKey item = picked;
        if (item != null) {
            graphics.item(item.toStack(1), leftPos + slotX(), topPos + slotY());
        } else if (minecraft != null && minecraft.player != null && !minecraft.player.getMainHandItem().isEmpty()) {
            // The held item, dimmed: what the Post buttons ask for with nothing picked.
            graphics.item(minecraft.player.getMainHandItem(), leftPos + slotX(), topPos + slotY());
            graphics.fill(leftPos + slotX(), topPos + slotY(), leftPos + slotX() + 16, topPos + slotY() + 16,
                    0x9015181B);
        }
        List<BoardView.Entry> entries = menu.entries();
        for (int i = 0; i < VISIBLE && scroll + i < entries.size(); i++) {
            BoardView.Entry entry = entries.get(scroll + i);
            int y = topPos + LIST_Y + i * ROW_HEIGHT;
            graphics.item(entry.item(), leftPos + LIST_X, y);
        }
        for (int i = 0; i < VISIBLE; i++) {
            BoardView.Entry entry = entry(i);
            fulfil[i].visible = entry != null;
            cancel[i].visible = entry != null && entry.mine();
        }
        VirtualGrid current = grid;
        if (current != null) {
            current.setEntries(index.view());
        }
    }

    /** The entry on visible row {@code row}, or null past the end of the list. */
    private BoardView.@Nullable Entry entry(int row) {
        List<BoardView.Entry> entries = menu.entries();
        return scroll + row < entries.size() ? entries.get(scroll + row) : null;
    }

    /** Sends {@code base} plus the id of the entry on visible row {@code row}, if there is one. */
    private void clickRow(int base, int row) {
        BoardView.Entry entry = entry(row);
        if (entry != null) {
            click(base + entry.id());
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        AccentFrame.text(graphics, font, Component.translatable(BoardContent.POST_KEY), slotX() + 20, ROW_POST + 3,
                POST_X - slotX() - 24, AccentFrame.LABEL, xm, ym);
        List<BoardView.Entry> entries = menu.entries();
        if (entries.isEmpty()) {
            TextScale.text(graphics, font, Component.translatable(BoardContent.EMPTY_KEY), LIST_X, LIST_Y + 4,
                    AccentFrame.LABEL, false);
        }
        for (int i = 0; i < VISIBLE && scroll + i < entries.size(); i++) {
            BoardView.Entry entry = entries.get(scroll + i);
            int y = LIST_Y + i * ROW_HEIGHT;
            long stored = held(ItemKey.of(entry.item()));
            if (stored > 0) {
                // How many the network holds, in half-size cyan text under the item.
                String label = AmountFormat.compact(stored);
                graphics.pose().pushMatrix();
                graphics.pose().translate(LIST_X + 17 - font.width(label) / 2.0F, y + 13);
                graphics.pose().scale(0.5F, 0.5F);
                TextScale.text(graphics, font, label, 0, 0, AccentFrame.BAR_TOP, true);
                graphics.pose().popMatrix();
            }
            Component text = Component.translatable(BoardContent.ROW_KEY,
                    Integer.toString(Math.max(0, entry.wanted() - entry.delivered())), entry.item().getHoverName(),
                    entry.poster());
            AccentFrame.text(graphics, font, text, LIST_X + 20, y + 5, fulfilX() - (LIST_X + 20) - 4, AccentFrame.TEXT,
                    xm, ym);
        }
    }

    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractTooltip(graphics, mouseX, mouseY);
        if (minecraft == null) {
            return;
        }
        VirtualGrid current = grid;
        int id = current == null ? -1 : current.entryAt(mouseX, mouseY);
        StackKey key = id < 0 ? null : menu.clientList().entry(id);
        if (key instanceof ItemKey item) {
            List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(minecraft, item.toStack(1)));
            lines.add(Component.translatable(TerminalContent.STORED_KEY,
                    String.format("%,d", menu.clientList().count(id))));
            lines.add(Component.translatable(BoardContent.PICK_HINT_KEY));
            ScreenComponents.tooltip(graphics, font, lines, mouseX, mouseY);
            return;
        }
        if (ScreenComponents.isHovering(leftPos + slotX(), topPos + slotY(), 16, 16, mouseX, mouseY)) {
            List<Component> lines = new ArrayList<>(2);
            ItemKey item = picked;
            if (item != null) {
                lines.add(item.toStack(1).getHoverName());
            }
            lines.add(Component.translatable(BoardContent.SLOT_HINT_KEY));
            ScreenComponents.tooltip(graphics, font, lines, mouseX, mouseY);
            return;
        }
        for (int i = 0; i < VISIBLE; i++) {
            BoardView.Entry entry = entry(i);
            if (entry != null && ScreenComponents.isHovering(leftPos + LIST_X, topPos + LIST_Y + i * ROW_HEIGHT, 16, 16,
                    mouseX, mouseY)) {
                List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(minecraft, entry.item()));
                lines.add(Component.translatable(BoardContent.HELD_KEY,
                        String.format("%,d", held(ItemKey.of(entry.item())))));
                ScreenComponents.tooltip(graphics, font, lines, mouseX, mouseY);
                return;
            }
        }
    }

    private void click(int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    /** Post button {@code i}: the picked item through a {@link BoardMenu.Post}, else the held item. */
    private void post(int i) {
        ItemKey item = picked;
        if (item != null) {
            Platform.get().network().sendToServer(new BoardMenu.Post(menu.containerId, item, i));
        } else {
            click(BoardMenu.POST + i);
        }
    }

    private boolean overList(double x, double y) {
        return ScreenComponents.isHovering(leftPos + LIST_X, topPos + LIST_Y, imageWidth - LIST_X - 8,
                VISIBLE * ROW_HEIGHT, (int) x, (int) y);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (!overList(x, y)) {
            return super.mouseScrolled(x, y, scrollX, scrollY);
        }
        int max = Math.max(0, menu.entries().size() - VISIBLE);
        scroll = Math.clamp(scroll - (int) Math.signum(scrollY), 0, max);
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (ScreenComponents.isHovering(leftPos + slotX(), topPos + slotY(), 16, 16, (int) event.x(),
                (int) event.y())) {
            // A click on the request slot drops the pick: the Post buttons ask for the held item again.
            picked = null;
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        EditBox box = search;
        if (box != null && box.isFocused() && !event.isEscape()) {
            // Printable keys arrive through charTyped; this keeps "e" from closing the screen.
            box.keyPressed(event);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    protected void init() {
        super.init();
        EditBox box = new EditBox(font, leftPos + GRID_X + 4, topPos + ROW_POST + 2, SEARCH_WIDTH - 8, 10,
                Component.translatable(TerminalContent.SEARCH_KEY));
        box.setBordered(false);
        box.setMaxLength(100);
        box.setTextColor(AccentFrame.TEXT);
        box.setHint(Component.translatable(TerminalContent.SEARCH_KEY));
        EditBox previous = search;
        if (previous != null) {
            box.setValue(previous.getValue());
        }
        search = addRenderableWidget(box);
        grid = addRenderableWidget(new VirtualGrid(leftPos + GRID_X, topPos + LIST_Y, GRID_COLUMNS, VISIBLE, CELL,
                Component.translatable(TerminalContent.SEARCH_KEY), this::renderCell, (id, event) -> {
                    if (menu.clientList().entry(id) instanceof ItemKey item) {
                        picked = item;
                    }
                }));
        for (int i = 0; i < BoardMenu.POST_COUNT; i++) {
            int amount = i;
            addRenderableWidget(new AccentButton(leftPos + POST_X + i * (POST_WIDTH + 2), topPos + ROW_POST, POST_WIDTH,
                    BUTTON_HEIGHT, Component.literal(Integer.toString(BoardMenu.amount(i))), () -> post(amount)));
        }
        for (int i = 0; i < VISIBLE; i++) {
            int row = i;
            int y = topPos + LIST_Y + i * ROW_HEIGHT + 1;
            fulfil[i] = addRenderableWidget(new AccentButton(leftPos + fulfilX(), y, FULFIL_WIDTH, BUTTON_HEIGHT,
                    Component.translatable(BoardContent.FULFIL_KEY), () -> clickRow(BoardMenu.FULFIL, row)));
            cancel[i] = addRenderableWidget(new AccentButton(leftPos + cancelX(), y, CANCEL_WIDTH, BUTTON_HEIGHT,
                    Component.translatable(BoardContent.CANCEL_KEY), () -> clickRow(BoardMenu.CANCEL, row)));
            fulfil[i].visible = false;
            cancel[i].visible = false;
        }
    }

    private void renderCell(GuiGraphicsExtractor graphics, int id, int x, int y, boolean hovered) {
        if (!(menu.clientList().entry(id) instanceof ItemKey item)) {
            return;
        }
        ItemStack stack = item.toStack(1);
        graphics.item(stack, x + 1, y + 1);
        String label = AmountFormat.compact(menu.clientList().count(id));
        graphics.pose().pushMatrix();
        graphics.pose().translate(x + 17 - font.width(label) / 2.0F, y + 13);
        graphics.pose().scale(0.5F, 0.5F);
        TextScale.text(graphics, font, label, 0, 0, 0xFFFFFFFF, true);
        graphics.pose().popMatrix();
        if (picked != null && item.equals(picked)) {
            graphics.fill(x + 1, y + 1, x + 17, y + 17, 0x6046B2BD);
        }
    }
}
