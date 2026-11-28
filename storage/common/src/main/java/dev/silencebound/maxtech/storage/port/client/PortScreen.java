package dev.silencebound.maxtech.storage.port.client;

import dev.silencebound.maxtech.lib.client.gui.ScreenComponents;
import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.lib.api.client.gui.Accent;
import dev.silencebound.maxtech.lib.api.client.gui.AccentButton;
import dev.silencebound.maxtech.lib.client.gui.accent.SideTabs;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.devicetab.DeviceTabTexts;
import dev.silencebound.maxtech.storage.devicetab.UpgradeSlot;
import dev.silencebound.maxtech.storage.devicetab.client.DeviceScreen;
import dev.silencebound.maxtech.storage.devicetab.client.SettingsFlyout;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.machine.client.MenuClick;
import dev.silencebound.maxtech.storage.network.NetworkInfo;
import dev.silencebound.maxtech.storage.palette.StatusPalette;
import dev.silencebound.maxtech.storage.port.BusAccess;
import dev.silencebound.maxtech.storage.port.BusStatus;
import dev.silencebound.maxtech.storage.port.MatchMode;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortFilter;
import dev.silencebound.maxtech.storage.port.PortItems;
import dev.silencebound.maxtech.storage.port.PortMenu;
import dev.silencebound.maxtech.storage.port.PortMode;
import dev.silencebound.maxtech.storage.port.PortOrder;
import dev.silencebound.maxtech.storage.port.PortPayloads;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The port's GUI in the Accent Frame style: the 3 x 3 ghost filter in the panel with the mode's name beside it, and the
 * side tabs (B09 item 21b): mode, export order (export mode) and redstone, priority and access (storage mode only),
 * upgrades (the card slots; in storage mode greyed out with a note, as they do nothing there) and label. A filter slot
 * shows how it matches (B09 item 18: {@code ~} fuzzy, {@code #} tag, {@code @} mod; right-click steps it) and in export
 * mode its keep amount (B09 item 22): scrolling over a slot changes it, and the field under the mode name types it for
 * the slot last clicked or scrolled. Problems (a land claim, a loop refused, nothing to mount, the tier's research) are
 * the mode tab's badge and, in place of the inventory label, a warning line. Everything it shows comes from the menu.
 * Client only.
 */
public final class PortScreen extends DeviceScreen<PortMenu> {
    private static final int MODE_X = 8;
    private static final int MODE_Y = 24;
    private static final int KEEP_Y = 48;
    private static final int KEEP_WIDTH = PortMenu.FILTER_X - 6 - MODE_X;
    /** Digits of the largest keep amount. */
    private static final int KEEP_CHARS = 10;
    /** Greys out a card slot that does nothing (storage mode). */
    private static final int IDLE_SLOT = 0xA01C2023;

    /** The filter slot the keep field edits. */
    private int selected;
    private @Nullable EditBox keepBox;
    /** The value last sent for the selected slot, so a re-sync does not resend. */
    private int sentKeep = -1;

    public PortScreen(PortMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, PortMenu.WIDTH, PortMenu.HEIGHT);
        this.inventoryLabelY = PortMenu.INVENTORY_Y - 11;
        this.titleLabelY = -100;
        tabs.mode().setting(Component.translatable(DeviceTabTexts.MODE_CAPTION_KEY),
                () -> Component.translatable(menu.mode().translationKey()),
                () -> MenuClick.send(menu, PortMenu.CYCLE_MODE), null, () -> menu.mode().id(),
                id -> Component.translatable(PortContent.MODE_KEY,
                        Component.translatable(PortMode.byId(id).translationKey())));
        tabs.mode().setting(Component.translatable(PortContent.ORDER_CAPTION_KEY),
                () -> Component.translatable(menu.order().translationKey()),
                () -> MenuClick.send(menu, PortMenu.CYCLE_ORDER), () -> MenuClick.send(menu, PortMenu.CYCLE_ORDER),
                () -> menu.order().id(),
                id -> CommonComponents.joinLines(Component.translatable(PortOrder.byId(id).translationKey()),
                        Component.translatable(PortOrder.byId(id).hintKey()).withColor(Accent.LABEL)),
                () -> true, this::export);
        tabs.modeChanged(() -> menu.mode() != PortMode.IMPORT || menu.order() != PortOrder.FILTER);
        tabs.redstone(menu::redstone, PortMenu.CYCLE_REDSTONE, PortMenu.REDSTONE_BACK);
        tabs.priority(menu, this::storage);
        tabs.upgrades(menu.slots.subList(PortMenu.FIRST_CARD_SLOT, PortMenu.FIRST_CARD_SLOT + menu.cardSlots()),
                menu.cardsShown(), () -> storage() ? Component.translatable(DeviceTabTexts.CARDS_IDLE_KEY) : null,
                () -> Accent.AMBER);
        tabs.access(
                new SettingsFlyout().setting(Component.translatable(DeviceTabTexts.ACCESS_CAPTION_KEY),
                        () -> Component.translatable(menu.access().translationKey()),
                        () -> MenuClick.send(menu, PortMenu.CYCLE_ACCESS), null, () -> menu.access().id(),
                        id -> Component.translatable(PortContent.ACCESS_KEY,
                                Component.translatable(BusAccess.byId(id).translationKey()))),
                this::storage, () -> menu.access() != BusAccess.BOTH);
        tabs.badge(this::badgeState, this::badge);
    }

    private boolean storage() {
        return menu.mode() == PortMode.STORAGE;
    }

    private boolean export() {
        return menu.mode() == PortMode.EXPORT;
    }

    @Override
    protected void init() {
        super.init();
        EditBox box = new EditBox(font, leftPos + MODE_X, topPos + KEEP_Y + 10, KEEP_WIDTH, AccentButton.COMPACT_HEIGHT,
                Component.translatable(PortContent.KEEP_LABEL_KEY));
        box.setMaxLength(KEEP_CHARS);
        box.setFilter(PortScreen::digits);
        box.setTextColor(Accent.TEXT);
        box.setResponder(this::typed);
        keepBox = box;
        sentKeep = -1;
        showKeep();
        addRenderableWidget(box);
    }

    private static boolean digits(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Shows the field in export mode with the selected slot's amount, unless the player is typing in it. */
    private void showKeep() {
        EditBox box = keepBox;
        if (box == null) {
            return;
        }
        boolean shown = export() && !menu.slots.get(selected).getItem().isEmpty();
        box.visible = shown;
        box.active = shown;
        int keep = menu.keep(selected);
        if (shown && !box.isFocused() && keep != sentKeep) {
            sentKeep = keep;
            box.setValue(keep == 0 ? "" : Integer.toString(keep));
        }
    }

    private void typed(String value) {
        int keep;
        try {
            keep = value.isEmpty() ? 0 : (int) Math.min(Integer.MAX_VALUE, Long.parseLong(value));
        } catch (NumberFormatException e) {
            return;
        }
        send(selected, keep);
    }

    private void send(int slot, int keep) {
        if (slot == selected) {
            sentKeep = keep;
        }
        Platform.get().network().sendToServer(new PortPayloads.Keep(menu.containerId, slot, keep));
    }

    /** Selects {@code slot} for the keep field. */
    private void select(int slot) {
        if (slot != selected) {
            selected = slot;
            sentKeep = -1;
            EditBox box = keepBox;
            if (box != null) {
                box.setFocused(false);
            }
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        showKeep();
    }

    /** A click on a filter slot selects it for the keep field; the click itself goes on as usual. */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int slot = filterSlotAt(event.x(), event.y());
        if (slot >= 0) {
            select(slot);
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** In export mode, scrolling over a named filter slot changes its keep amount by 1 (with Shift by 64). */
    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        int slot = filterSlotAt(x, y);
        if (slot < 0 || !export() || menu.slots.get(slot).getItem().isEmpty() || scrollY == 0) {
            return super.mouseScrolled(x, y, scrollX, scrollY);
        }
        select(slot);
        int step = Minecraft.getInstance().hasShiftDown() ? 64 : 1;
        long next = (long) menu.keep(slot) + (scrollY > 0 ? step : -step);
        send(slot, (int) Math.clamp(next, 0, Integer.MAX_VALUE));
        sentKeep = -1;
        return true;
    }

    /** While the keep field has the focus it takes every key but Escape, so typing never closes the screen. */
    @Override
    public boolean keyPressed(KeyEvent event) {
        EditBox box = keepBox;
        if (box != null && box.isFocused() && box.visible && !event.isEscape()) {
            box.keyPressed(event);
            return true;
        }
        return super.keyPressed(event);
    }

    /** Where filter slot {@code slot} is on screen, for a recipe viewer's ghost drag. */
    public Rect2i filterSlotArea(int slot) {
        Slot at = menu.slots.get(slot);
        return new Rect2i(leftPos + at.x, topPos + at.y, 16, 16);
    }

    /** A recipe viewer dropped {@code stack} on filter slot {@code slot}: the server names it there. */
    public void dropOnFilter(int slot, ItemStack stack) {
        if (slot >= 0 && slot < PortFilter.SIZE && !stack.isEmpty()) {
            select(slot);
            Platform.get().network()
                    .sendToServer(new PortPayloads.Ghost(menu.containerId, slot, stack.copyWithCount(1)));
        }
    }

    private int filterSlotAt(double x, double y) {
        for (int i = 0; i < PortFilter.SIZE; i++) {
            Slot slot = menu.slots.get(i);
            if (ScreenComponents.isHovering(leftPos + slot.x, topPos + slot.y, 16, 16, (int) x, (int) y)) {
                return i;
            }
        }
        return -1;
    }

    /** What the port's badge depends on, as bits; the badge is built again only when they change. */
    private int badgeState() {
        return (menu.claimBlocked() ? 1 : 0) | (menu.loopRefused() ? 2 : 0) | (menu.nothingMounted() ? 4 : 0)
                | (menu.waitsForResearch() ? 8 : 0);
    }

    /** The port's own problem: a land claim or a loop (red), nothing to mount or the tier's research (amber). */
    private SideTabs.@Nullable Badge badge() {
        Component warning = warning();
        if (warning == null) {
            return null;
        }
        return new SideTabs.Badge(
                menu.claimBlocked() || menu.loopRefused() ? SideTabs.Level.ERROR : SideTabs.Level.WARN, warning);
    }

    private @Nullable Component warning() {
        Identifier research = menu.tier().research();
        if (menu.claimBlocked()) {
            return Component.translatable(NetworkInfo.CLAIM_BLOCKED_KEY);
        }
        if (menu.loopRefused()) {
            return Component.translatable(BusStatus.LOOP.translationKey());
        }
        if (menu.nothingMounted()) {
            return Component.translatable(BusStatus.NOTHING.translationKey());
        }
        if (menu.waitsForResearch() && research != null) {
            return PortItems.researchLine(research);
        }
        return null;
    }

    @Override
    protected void extractPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        AccentFrame.panel(graphics, font, leftPos, topPos, imageWidth, imageHeight, title, mouseX, mouseY);
        for (Slot slot : menu.slots) {
            if (slot.isActive()) {
                AccentFrame.slot(graphics, leftPos + slot.x, topPos + slot.y);
            }
        }
        if (export() && !menu.slots.get(selected).getItem().isEmpty()) {
            Slot slot = menu.slots.get(selected);
            int x = leftPos + slot.x;
            int y = topPos + slot.y;
            int colour = Accent.ACCENT;
            graphics.fill(x - 1, y - 1, x + 17, y, colour);
            graphics.fill(x - 1, y + 16, x + 17, y + 17, colour);
            graphics.fill(x - 1, y, x, y + 16, colour);
            graphics.fill(x + 16, y, x + 17, y + 16, colour);
        }
    }

    /** In storage mode the card slots are greyed out over their cards. */
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        if (!storage()) {
            return;
        }
        for (Slot slot : menu.slots) {
            if (slot instanceof UpgradeSlot && slot.isActive()) {
                int x = leftPos + slot.x;
                int y = topPos + slot.y;
                graphics.fill(x, y, x + 16, y + 16, IDLE_SLOT);
            }
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        TextScale.text(graphics, font, Component.translatable(PortContent.FILTER_KEY), PortMenu.FILTER_X,
                PortMenu.FILTER_Y - 11, AccentFrame.LABEL, false);
        AccentFrame.text(graphics, font, Component.translatable(menu.mode().translationKey()), MODE_X, MODE_Y,
                PortMenu.FILTER_X - 4 - MODE_X, AccentFrame.TEXT, xm, ym);
        if (storage()) {
            AccentFrame.text(graphics, font, Component.translatable(menu.access().translationKey()), MODE_X,
                    MODE_Y + 12, PortMenu.FILTER_X - 4 - MODE_X, AccentFrame.LABEL, xm, ym);
        }
        EditBox box = keepBox;
        if (box != null && box.visible) {
            TextScale.text(graphics, font, Component.translatable(PortContent.KEEP_LABEL_KEY), MODE_X, KEEP_Y,
                    AccentFrame.LABEL, false);
        }
        for (int i = 0; i < PortFilter.SIZE; i++) {
            Slot slot = menu.slots.get(i);
            if (slot.getItem().isEmpty()) {
                continue;
            }
            String mark = mark(menu.match(i));
            if (!mark.isEmpty()) {
                TextScale.text(graphics, font, mark, slot.x + 11, slot.y - 1, AccentFrame.TEXT, true);
            }
            int keep = menu.keep(i);
            if (keep > 0 && export()) {
                String amount = compact(keep);
                TextScale.text(graphics, font, amount, slot.x + 17 - font.width(amount), slot.y + 9, AccentFrame.TEXT,
                        true);
            }
        }
        Component warning = warning();
        if (warning != null) {
            AccentFrame.text(graphics, font,
                    Component.literal(StatusPalette.mark(StatusPalette.Tone.WARN)).append(warning), inventoryLabelX,
                    inventoryLabelY + 1, imageWidth - 2 * inventoryLabelX, StatusPalette.warn(), xm, ym);
        } else {
            TextScale.text(graphics, font, playerInventoryTitle, inventoryLabelX, inventoryLabelY + 1,
                    AccentFrame.LABEL, false);
        }
    }

    /** The corner mark of a match mode: none for exact. */
    private static String mark(MatchMode mode) {
        return switch (mode) {
            case EXACT -> "";
            case FUZZY -> "~";
            case TAG -> "#";
            case MOD -> "@";
        };
    }

    /** A keep amount short enough for a slot corner: 64, 1k, 12k, 3M. */
    private static String compact(int amount) {
        if (amount < 1000) {
            return Integer.toString(amount);
        }
        if (amount < 1_000_000) {
            return amount / 1000 + "k";
        }
        return amount / 1_000_000 + "M";
    }

    /**
     * Over a named filter slot: the item, how the slot matches and its keep amount with how to change them; over a
     * blank filter, what a blank filter does; over a card slot in storage mode, that cards do nothing.
     */
    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (!menu.getCarried().isEmpty()) {
            super.extractTooltip(graphics, mouseX, mouseY);
            return;
        }
        int at = filterSlotAt(mouseX, mouseY);
        if (at >= 0 && !menu.slots.get(at).getItem().isEmpty()) {
            ScreenComponents.tooltip(graphics, font, filterLines(at), mouseX, mouseY);
            return;
        }
        super.extractTooltip(graphics, mouseX, mouseY);
        Slot hovered = hoveredSlot;
        if (hovered instanceof UpgradeSlot && storage() && !hovered.hasItem()) {
            ScreenComponents.tooltip(graphics, font, List.of(Component.translatable(DeviceTabTexts.CARDS_IDLE_KEY)),
                    mouseX, mouseY);
            return;
        }
        if (!ScreenComponents.isHovering(leftPos + PortMenu.FILTER_X, topPos + PortMenu.FILTER_Y, 54, 54, mouseX,
                mouseY)) {
            return;
        }
        for (int i = 0; i < PortFilter.SIZE; i++) {
            if (!menu.slots.get(i).getItem().isEmpty()) {
                return;
            }
        }
        ScreenComponents.tooltip(graphics, font, List.of(Component.translatable(PortContent.BLANK_FILTER_KEY)), mouseX,
                mouseY);
    }

    private List<Component> filterLines(int slot) {
        ItemStack stack = menu.slots.get(slot).getItem();
        List<Component> lines = new ArrayList<>();
        lines.add(stack.getHoverName());
        MatchMode mode = menu.match(slot);
        Component how = switch (mode) {
            case TAG -> {
                Optional<Identifier> tag = menu.matchTag(slot);
                yield tag.isPresent()
                        ? Component.literal("#" + tag.get())
                        : Component.translatable(mode.translationKey());
            }
            case MOD -> Component.literal("@" + PortFilter.modOf(stack));
            default -> Component.translatable(mode.translationKey());
        };
        lines.add(Component.translatable(PortContent.MATCH_LINE_KEY, how).withColor(Accent.TEXT));
        if (export() && menu.keep(slot) > 0) {
            lines.add(Component.translatable(PortContent.KEEP_LINE_KEY, Integer.toString(menu.keep(slot)))
                    .withColor(Accent.TEXT));
        }
        lines.add(Component.translatable(PortContent.MATCH_HINT_KEY).withColor(Accent.LABEL));
        if (export()) {
            lines.add(Component.translatable(PortContent.KEEP_HINT_KEY).withColor(Accent.LABEL));
        }
        return lines;
    }
}
