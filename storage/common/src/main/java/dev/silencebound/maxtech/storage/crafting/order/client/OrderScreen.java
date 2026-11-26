package dev.silencebound.maxtech.storage.crafting.order.client;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.client.CraftRequestScreen;
import dev.silencebound.maxtech.storage.crafting.client.KeyDisplay;
import dev.silencebound.maxtech.storage.crafting.order.ListCode;
import dev.silencebound.maxtech.storage.crafting.order.OrderList;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.crafting.request.RequestContent;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.lib.client.gui.accent.AccentButton;
import dev.silencebound.maxtech.storage.terminal.client.TerminalScreen;
import dev.silencebound.maxtech.storage.terminal.client.TerminalSubScreen;
import dev.silencebound.maxtech.storage.terminal.client.TerminalTab;
import dev.silencebound.maxtech.storage.terminal.client.TerminalTabs;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The terminal's order list tab (M7C item 6): a typed list or a pasted {@code mtlist1:} code, read as it is typed into
 * the items it names, and planned as one job. "Copy code" puts the list's code on the clipboard. Client only.
 */
public final class OrderScreen extends TerminalSubScreen {
    public static final int WIDTH = 256;
    public static final int HEIGHT = 222;
    private static final int MARGIN = 8;
    private static final int LIST_TOP = 52;
    private static final int ROW = 18;
    private static final int ROWS = (HEIGHT - LIST_TOP - MARGIN - 34) / ROW;

    private String text;
    private OrderList.Resolved resolved;
    private int scroll;
    private boolean copied;

    public OrderScreen(TerminalMenu menu, @Nullable TerminalScreen parent, Component terminalTitle, String text) {
        super(Component.translatable(OrderList.TITLE_KEY), menu, parent, terminalTitle, WIDTH, HEIGHT);
        this.text = text;
        this.resolved = OrderList.resolve(text);
    }

    /** Adds the tab to network terminals; called once from client setup. */
    public static void registerTab() {
        TerminalTabs.register(new TerminalTab(MaxtechStorage.id("order_list"), TerminalTab.sprite("tab_order"),
                Component.translatable(OrderList.TITLE_KEY), TerminalMenu::networked,
                (parent, menu) -> new OrderScreen(menu, parent, parent.getTitle(), "")));
    }

    @Override
    protected void init() {
        super.init();
        EditBox box = new EditBox(font, left + MARGIN, top + 32, WIDTH - 2 * MARGIN, 12,
                Component.translatable(OrderList.HINT_KEY));
        box.setMaxLength(ListCode.MAX_LENGTH);
        box.setValue(text);
        box.setResponder(value -> {
            text = value;
            resolved = OrderList.resolve(value);
            scroll = 0;
            copied = false;
        });
        box.setTextColor(AccentFrame.TEXT);
        addRenderableWidget(box);
        setInitialFocus(box);
        int buttonY = top + HEIGHT - MARGIN - 16;
        addRenderableWidget(new AccentButton(left + MARGIN, buttonY, 50, 16,
                Component.translatable(RequestContent.BACK_KEY), () -> true, this::back));
        addRenderableWidget(new AccentButton(left + MARGIN + 54, buttonY, 50, 16,
                Component.translatable(OrderList.CLEAR_KEY), () -> !text.isEmpty(), () -> box.setValue("")));
        addRenderableWidget(new AccentButton(left + MARGIN + 108, buttonY, 66, 16,
                Component.translatable(OrderList.COPY_KEY), () -> !resolved.roots().isEmpty(), this::copy));
        addRenderableWidget(new AccentButton(left + WIDTH - MARGIN - 60, buttonY, 60, 16,
                Component.translatable(OrderList.PLAN_KEY), () -> !resolved.roots().isEmpty(), this::plan));
    }

    private void copy() {
        if (minecraft != null) {
            minecraft.keyboardHandler.setClipboard(OrderList.encode(resolved.roots()));
            copied = true;
        }
    }

    private void plan() {
        if (minecraft != null && !resolved.roots().isEmpty()) {
            minecraft.setScreen(
                    CraftRequestScreen.order(menu, parentTerminal(), terminalTitle(), resolved.roots(), text));
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.clamp(scroll - (scrollY > 0 ? 1 : scrollY < 0 ? -1 : 0), 0,
                Math.max(0, resolved.roots().size() - ROWS));
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        AccentFrame.text(graphics, font, Component.translatable(OrderList.HINT_KEY), left + MARGIN, top + 20,
                WIDTH - 2 * MARGIN, AccentFrame.LABEL, mouseX, mouseY);
        if (resolved.roots().isEmpty()) {
            AccentFrame.text(graphics, font, Component.translatable(OrderList.EMPTY_KEY), left + MARGIN, top + LIST_TOP,
                    WIDTH - 2 * MARGIN, AccentFrame.LABEL, mouseX, mouseY);
        }
        for (int i = 0; i < ROWS && scroll + i < resolved.roots().size(); i++) {
            CraftingPlanner.Root root = resolved.roots().get(scroll + i);
            int y = top + LIST_TOP + i * ROW;
            graphics.item(KeyDisplay.icon(root.key()), left + MARGIN, y);
            boolean craftable = menu.craftingData().isCraftable(root.key());
            Component line = Component.literal(KeyDisplay.amount(root.key(), root.amount()) + " x ")
                    .append(KeyDisplay.name(root.key()));
            AccentFrame.text(graphics, font, line, left + MARGIN + 20, y + 4, WIDTH - 2 * MARGIN - 20,
                    craftable ? AccentFrame.TEXT : AccentFrame.LABEL, mouseX, mouseY);
        }
        int infoY = top + HEIGHT - MARGIN - 30;
        Component info = null;
        if (!resolved.errors().isEmpty()) {
            info = Component.translatable(OrderList.UNKNOWN_KEY, String.join(", ", resolved.errors()));
        } else if (copied) {
            info = Component.translatable(OrderList.COPIED_KEY);
        }
        if (info != null) {
            AccentFrame.text(graphics, font, info, left + MARGIN, infoY, WIDTH - 2 * MARGIN,
                    copied && resolved.errors().isEmpty() ? AccentFrame.LABEL : 0xFFE05A4C, mouseX, mouseY);
        }
        String notice = menu.craftingData().notice();
        if (notice != null) {
            AccentFrame.text(graphics, font, Component.translatable(notice), left + MARGIN, infoY - 11,
                    WIDTH - 2 * MARGIN, 0xFFE05A4C, mouseX, mouseY);
        }
    }
}
