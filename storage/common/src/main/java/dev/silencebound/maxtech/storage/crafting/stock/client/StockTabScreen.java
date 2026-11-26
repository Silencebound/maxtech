package dev.silencebound.maxtech.storage.crafting.stock.client;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.crafting.client.KeyDisplay;
import dev.silencebound.maxtech.storage.crafting.request.RequestContent;
import dev.silencebound.maxtech.storage.crafting.stock.StockKeeperMenu;
import dev.silencebound.maxtech.storage.crafting.stock.StockPayloads;
import dev.silencebound.maxtech.storage.crafting.stock.StockRule;
import dev.silencebound.maxtech.storage.crafting.stock.StockTexts;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.lib.client.gui.accent.AccentButton;
import dev.silencebound.maxtech.storage.terminal.client.TerminalScreen;
import dev.silencebound.maxtech.storage.terminal.client.TerminalSubScreen;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The terminal's stock tab (M7 C9): the stock keepers of the terminal's network, one at a time, with their rules listed
 * on the left and the picked rule's settings on the right, the same edits as the keeper's own screen. A rule's item is
 * set from the item in the main hand. Editing needs full access on the network; others see the rules only. The server
 * sends the keepers when the tab opens and after every edit. Items only: a fluid rule shows but its fluid cannot be set
 * here. Client only.
 */
public final class StockTabScreen extends TerminalSubScreen {
    public static final int WIDTH = 300;
    public static final int HEIGHT = 222;
    private static final int MARGIN = 8;
    private static final int LIST_TOP = 40;
    private static final int ROW = 10;
    private static final int LIST_WIDTH = 140;
    private static final int RIGHT = MARGIN + LIST_WIDTH + 8;
    private static final int RIGHT_WIDTH = WIDTH - RIGHT - MARGIN;

    private int keeper;
    private int rule;
    private StockKeeperMenu.Field field = StockKeeperMenu.Field.KEEP;

    public StockTabScreen(TerminalMenu menu, @Nullable TerminalScreen parent, Component terminalTitle) {
        super(Component.translatable(StockTexts.TAB_TITLE), menu, parent, terminalTitle, WIDTH, HEIGHT);
    }

    private StockPayloads.@Nullable Keepers data() {
        return StockPayloads.received(menu.containerId);
    }

    private StockPayloads.@Nullable KeeperView shownKeeper() {
        StockPayloads.Keepers data = data();
        if (data == null || data.keepers().isEmpty()) {
            return null;
        }
        return data.keepers().get(Math.min(keeper, data.keepers().size() - 1));
    }

    private @Nullable StockRule shownRule() {
        StockPayloads.KeeperView view = shownKeeper();
        if (view == null || view.rules().isEmpty()) {
            return null;
        }
        return view.rules().get(Math.min(rule, view.rules().size() - 1));
    }

    private boolean editable() {
        StockPayloads.Keepers data = data();
        return data != null && data.canEdit() && shownRule() != null;
    }

    @Override
    protected void init() {
        super.init();
        int y = top + 20;
        button(MARGIN, y, 16, () -> Component.literal("<"), () -> shownKeeper() != null, () -> pickKeeper(-1));
        button(MARGIN + LIST_WIDTH - 16, y, 16, () -> Component.literal(">"), () -> shownKeeper() != null,
                () -> pickKeeper(1));
        int x = RIGHT;
        y = top + 64;
        button(x, y, RIGHT_WIDTH, () -> Component.translatable(field.translationKey()), this::editable,
                () -> field = field.next());
        y += 18;
        for (int i = 0; i < StockKeeperMenu.STEP_COUNT; i++) {
            int step = i;
            int column = i % 4;
            int row = i / 4;
            int width = (RIGHT_WIDTH - 6) / 4;
            String label = StockKeeperMenu.stepLabel(i);
            button(x + column * (width + 2), y + row * 18, width, () -> Component.literal(label), this::editable,
                    () -> edit(current -> StockPayloads.stepped(current, field, step)));
        }
        y += 40;
        button(x, y, 14, () -> Component.literal("-"), this::editable,
                () -> edit(current -> current.withPriority(current.priority() - 1)));
        button(x + RIGHT_WIDTH - 14, y, 14, () -> Component.literal("+"), this::editable,
                () -> edit(current -> current.withPriority(current.priority() + 1)));
        y += 18;
        button(x, y, RIGHT_WIDTH / 2 - 1, this::cpuLabel, this::editable,
                () -> edit(current -> current.withCpuTier((current.cpuTier() + 1) % 4)));
        button(x + RIGHT_WIDTH / 2 + 1, y, RIGHT_WIDTH / 2 - 1, this::redstoneLabel, this::editable,
                () -> edit(current -> current.withRedstone(current.redstone().next())));
        y += 18;
        button(x, y, RIGHT_WIDTH / 2 - 1, () -> Component.translatable(StockTexts.SET_HELD), this::editable,
                this::setHeld);
        button(x + RIGHT_WIDTH / 2 + 1, y, RIGHT_WIDTH / 2 - 1, () -> Component.translatable(StockTexts.CLEAR),
                this::editable, () -> edit(current -> current.withKey(Optional.empty())));
        addRenderableWidget(new AccentButton(left + MARGIN, top + HEIGHT - MARGIN - 16, 50, 16,
                Component.translatable(RequestContent.BACK_KEY), () -> true, this::back));
        Platform.get().network().sendToServer(new StockPayloads.Watch(menu.containerId));
    }

    private void button(int x, int y, int width, Supplier<Component> label, BooleanSupplier enabled, Runnable action) {
        addRenderableWidget(
                AccentButton.dynamic(left + x, y, width, AccentButton.HEIGHT, label, enabled, input -> action.run()));
    }

    private Component cpuLabel() {
        StockRule current = shownRule();
        int tier = current == null ? 0 : current.cpuTier();
        return tier == 0
                ? Component.translatable(StockTexts.CPU_ANY)
                : Component.translatable(StockTexts.CPU_TIER, tier);
    }

    private Component redstoneLabel() {
        StockRule current = shownRule();
        return Component
                .translatable((current == null ? StockRule.Redstone.ALWAYS : current.redstone()).translationKey());
    }

    private void pickKeeper(int delta) {
        StockPayloads.Keepers data = data();
        if (data != null && !data.keepers().isEmpty()) {
            int size = data.keepers().size();
            keeper = (Math.min(keeper, size - 1) + delta + size) % size;
        }
    }

    private void setHeld() {
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        ItemStack held = minecraft.player.getMainHandItem();
        if (held.isEmpty()) {
            return;
        }
        StackKey key = ItemKey.of(held);
        long keep = held.getMaxStackSize();
        edit(current -> StockPayloads.withKey(current, Optional.of(key), keep));
    }

    private void edit(UnaryOperator<StockRule> change) {
        StockPayloads.KeeperView view = shownKeeper();
        StockRule current = shownRule();
        if (view == null || current == null || !editable()) {
            return;
        }
        int index = Math.min(rule, view.rules().size() - 1);
        Platform.get().network()
                .sendToServer(new StockPayloads.Edit(menu.containerId, view.node(), index, change.apply(current)));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        StockPayloads.KeeperView view = shownKeeper();
        if (view != null && event.x() >= left + MARGIN && event.x() < left + MARGIN + LIST_WIDTH
                && event.y() >= top + LIST_TOP) {
            int row = (int) ((event.y() - top - LIST_TOP) / ROW);
            if (row < view.rules().size()) {
                rule = row;
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        StockPayloads.Keepers data = data();
        StockPayloads.KeeperView view = shownKeeper();
        if (data == null || view == null) {
            AccentFrame.text(graphics, font, Component.translatable(StockTexts.NO_KEEPERS), left + MARGIN,
                    top + LIST_TOP, WIDTH - 2 * MARGIN, AccentFrame.LABEL, mouseX, mouseY);
            return;
        }
        int shown = Math.min(keeper, data.keepers().size() - 1);
        AccentFrame.text(graphics, font, Component.translatable(StockTexts.KEEPER, shown + 1, data.keepers().size()),
                left + MARGIN + 22, top + 24, LIST_WIDTH - 44, AccentFrame.LABEL, mouseX, mouseY);
        int picked = Math.min(rule, view.rules().size() - 1);
        for (int i = 0; i < view.rules().size(); i++) {
            StockRule line = view.rules().get(i);
            int y = top + LIST_TOP + i * ROW;
            if (i == picked) {
                graphics.fill(left + MARGIN, y - 1, left + MARGIN + LIST_WIDTH, y + ROW - 1, AccentFrame.SLOT);
            }
            boolean filling = (view.filling() & (1 << i)) != 0;
            Component text = line.key().isPresent()
                    ? Component.literal((i + 1) + ". " + InfoFormat.number(line.keep()) + " ")
                            .append(KeyDisplay.name(line.key().get()))
                    : Component.literal((i + 1) + ". ").append(Component.translatable(StockTexts.NOTHING));
            AccentFrame.text(graphics, font, text, left + MARGIN + 2, y, LIST_WIDTH - 4,
                    filling ? AccentFrame.BAR_TOP : AccentFrame.TEXT, mouseX, mouseY);
        }
        StockRule current = view.rules().get(picked);
        int x = left + RIGHT;
        if (current.key().isPresent()) {
            graphics.item(KeyDisplay.icon(current.key().get()), x, top + 20);
        }
        AccentFrame.text(graphics, font, Component.translatable(StockTexts.RULE, picked + 1, view.rules().size()),
                x + 20, top + 20, RIGHT_WIDTH - 20, AccentFrame.LABEL, mouseX, mouseY);
        AccentFrame.text(graphics, font,
                Component.translatable(StockTexts.state(current, (view.filling() & (1 << picked)) != 0)), x + 20,
                top + 30, RIGHT_WIDTH - 20, AccentFrame.LABEL, mouseX, mouseY);
        String batch = current.batch() == 0
                ? Component.translatable(StockTexts.NO_LIMIT).getString()
                : InfoFormat.number(current.batch());
        AccentFrame.text(
                graphics, font, Component.literal(InfoFormat.number(current.keep()) + " / "
                        + InfoFormat.number(current.restart()) + " / " + batch),
                x, top + 50, RIGHT_WIDTH, AccentFrame.LABEL, mouseX, mouseY);
        // Between the priority's - and + buttons.
        AccentFrame.text(graphics, font, Component.translatable(StockTexts.PRIORITY, current.priority()), x + 20,
                top + 126, RIGHT_WIDTH - 40, AccentFrame.LABEL, mouseX, mouseY);
        if (!data.canEdit()) {
            AccentFrame.text(graphics, font, Component.translatable(StockTexts.READ_ONLY), x,
                    top + HEIGHT - MARGIN - 12, RIGHT_WIDTH, AccentFrame.LABEL, mouseX, mouseY);
        }
    }
}
