package dev.silencebound.maxtech.storage.controller.module.client;

import dev.silencebound.maxtech.storage.devicetab.client.DeviceScreen;
import dev.silencebound.maxtech.lib.api.client.gui.AccentButton;
import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.controller.module.SecurityMenu;
import dev.silencebound.maxtech.storage.controller.module.SecurityPayloads;
import dev.silencebound.maxtech.storage.controller.module.SecurityTexts;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.player.Inventory;
import org.jspecify.annotations.Nullable;

/**
 * The security module's screen in the Accent Frame style: a scrolling list of rows (everyone else, the owner's team,
 * each player, each scoreboard team) with each row's access level on the right; clicking a row cycles its level through
 * a menu button click. Player rows start with a trust mark (filled while trusted) that the owner clicks to trust or
 * distrust the player. Below the list a name box and an add button send {@link SecurityPayloads.AddPlayer} for a player
 * who is not online. Levels and marks come from the menu's data slots. Client only.
 */
public final class SecurityScreen extends DeviceScreen<SecurityMenu> {
    private static final int LIST_X = 8;
    private static final int LIST_Y = 18;
    private static final int ROW_HEIGHT = 13;
    private static final int VISIBLE = 9;
    private static final int LIST_WIDTH = SecurityMenu.WIDTH - 16;
    private static final int MARK = 7;
    private static final int ADD_Y = LIST_Y + VISIBLE * ROW_HEIGHT + 4;
    private static final int ADD_WIDTH = 36;
    private static final int ADD_HEIGHT = 13;

    private int scroll;
    private @Nullable EditBox name;

    public SecurityScreen(SecurityMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, SecurityMenu.WIDTH, SecurityMenu.HEIGHT);
        this.titleLabelY = -100;
        this.inventoryLabelY = -100;
    }

    @Override
    protected void init() {
        super.init();
        EditBox box = new EditBox(font, leftPos + LIST_X + 4, topPos + ADD_Y + 2, LIST_WIDTH - ADD_WIDTH - 10, 10,
                Component.translatable(SecurityTexts.ADD_HINT));
        box.setBordered(false);
        box.setMaxLength(OwnerRecord.MAX_NAME_LENGTH);
        box.setTextColor(AccentFrame.TEXT);
        box.setHint(Component.translatable(SecurityTexts.ADD_HINT));
        box.setFilter(text -> text.isEmpty() || StringUtil.isValidPlayerName(text));
        name = addRenderableWidget(box);
        addRenderableWidget(new AccentButton(leftPos + LIST_X + LIST_WIDTH - ADD_WIDTH, topPos + ADD_Y, ADD_WIDTH,
                ADD_HEIGHT, Component.translatable(SecurityTexts.ADD), this::sendName));
    }

    @Override
    protected void extractPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        AccentFrame.panel(graphics, font, leftPos, topPos, imageWidth, imageHeight, title, mouseX, mouseY);
        int hovered = rowAt(mouseX, mouseY);
        for (int i = 0; i < VISIBLE && scroll + i < menu.rows().size(); i++) {
            int index = scroll + i;
            int x = leftPos + LIST_X;
            int y = topPos + LIST_Y + i * ROW_HEIGHT;
            AccessLevel level = menu.level(index);
            Component value = level == null
                    ? Component.translatable(SecurityTexts.NO_RULE)
                    : Component.translatable(level.translationKey());
            AccentFrame.button(graphics, font, x, y, LIST_WIDTH, ROW_HEIGHT - 1, Component.empty(), index == hovered);
            int textX = x + 4;
            if (menu.rows().get(index).kind() == SecurityMenu.Kind.PLAYER) {
                int markY = y + (ROW_HEIGHT - 1 - MARK) / 2;
                graphics.fill(textX, markY, textX + MARK, markY + MARK, AccentFrame.SLOT_EDGE);
                graphics.fill(textX + 1, markY + 1, textX + MARK - 1, markY + MARK - 1,
                        menu.trusted(index) ? AccentFrame.BAR_TOP : AccentFrame.SLOT);
                textX += MARK + 3;
            }
            int valueX = x + LIST_WIDTH - 4 - font.width(value);
            TextScale.text(graphics, font, value, valueX, y + 2, AccentFrame.LABEL, false);
            AccentFrame.text(graphics, font, label(menu.rows().get(index)), textX, y + 2, valueX - 4 - textX,
                    AccentFrame.TEXT, mouseX, mouseY);
        }
        if (menu.rows().size() > VISIBLE) {
            int track = VISIBLE * ROW_HEIGHT;
            float fraction = (float) scroll / (menu.rows().size() - VISIBLE);
            AccentFrame.verticalBar(graphics, leftPos + imageWidth - 6, topPos + LIST_Y, 2, track, 1.0F - fraction);
        }
        int boxWidth = LIST_WIDTH - ADD_WIDTH - 2;
        graphics.fill(leftPos + LIST_X, topPos + ADD_Y, leftPos + LIST_X + boxWidth, topPos + ADD_Y + ADD_HEIGHT,
                AccentFrame.SLOT_EDGE);
        graphics.fill(leftPos + LIST_X + 1, topPos + ADD_Y + 1, leftPos + LIST_X + boxWidth - 1,
                topPos + ADD_Y + ADD_HEIGHT - 1, AccentFrame.SLOT);
        AccentFrame.text(graphics, font, Component.translatable(SecurityTexts.HINT), leftPos + LIST_X,
                topPos + ADD_Y + 16, LIST_WIDTH, AccentFrame.LABEL, mouseX, mouseY);
        if (hovered >= 0 && overMark(hovered, mouseX)) {
            graphics.setComponentTooltipForNextFrame(font,
                    List.of(Component
                            .translatable(menu.trusted(hovered) ? SecurityTexts.TRUSTED : SecurityTexts.NOT_TRUSTED)),
                    mouseX, mouseY);
        }
    }

    private static Component label(SecurityMenu.Row row) {
        return switch (row.kind()) {
            case EVERYONE -> Component.translatable(SecurityTexts.EVERYONE);
            case OWNER_TEAM -> Component.translatable(SecurityTexts.OWNER_TEAM);
            case PLAYER -> Component.literal(row.name());
            case TEAM -> Component.translatable(SecurityTexts.TEAM, row.name());
        };
    }

    /** The row under the mouse, or -1. */
    private int rowAt(double mouseX, double mouseY) {
        double x = mouseX - leftPos - LIST_X;
        double y = mouseY - topPos - LIST_Y;
        if (x < 0 || x >= LIST_WIDTH || y < 0 || y >= VISIBLE * ROW_HEIGHT) {
            return -1;
        }
        int index = scroll + (int) (y / ROW_HEIGHT);
        return index < menu.rows().size() ? index : -1;
    }

    /** Whether the mouse is over row {@code index}'s trust mark (player rows only). */
    private boolean overMark(int index, double mouseX) {
        double x = mouseX - leftPos - LIST_X - 4;
        return menu.rows().get(index).kind() == SecurityMenu.Kind.PLAYER && x >= -1 && x < MARK + 1;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int index = rowAt(event.x(), event.y());
        if (index >= 0 && event.button() == 0 && minecraft != null && minecraft.gameMode != null) {
            int button = overMark(index, event.x()) ? SecurityMenu.TRUST_BUTTON + index : index;
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, button);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void sendName() {
        EditBox box = name;
        if (box != null && StringUtil.isValidPlayerName(box.getValue())) {
            Platform.get().network().sendToServer(new SecurityPayloads.AddPlayer(menu.containerId, box.getValue()));
            box.setValue("");
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        EditBox box = name;
        if (box != null && box.isFocused() && !event.isEscape()) {
            if (event.isConfirmation()) {
                sendName();
                return true;
            }
            // Printable keys arrive through charTyped; this keeps "e" and the hotbar keys from acting on the screen.
            box.keyPressed(event);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        int most = Math.max(0, menu.rows().size() - VISIBLE);
        scroll = Mth.clamp(scroll - (int) Math.signum(scrollY), 0, most);
        return true;
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        // The panel draws the title; there is no inventory.
    }
}
