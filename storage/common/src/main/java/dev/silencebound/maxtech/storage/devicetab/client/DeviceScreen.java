package dev.silencebound.maxtech.storage.devicetab.client;

import dev.silencebound.maxtech.lib.client.gui.accent.SideTabs;
import dev.silencebound.maxtech.storage.devicetab.UpgradeSlot;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * A device screen with side tabs (B09 item 21b): the subclass declares its tabs on {@link #tabs} in its constructor and
 * keeps only what the device is about in its panel. This class lays the tabs out beside the panel in {@code init},
 * draws the panel ({@link #extractPanel}) and then the open flyout, hands it keys and clicks first (Escape closes it,
 * clicks inside never reach the panel), treats the tabs and the flyout as inside the screen (a carried stack is not
 * thrown there) and lays itself out again when the set of tabs changes. Subclasses draw their panel in
 * {@link #extractPanel} and call {@code super} in {@code init}, {@code keyPressed} and {@code mouseClicked}. Client
 * only.
 */
public abstract class DeviceScreen<M extends AbstractContainerMenu> extends AbstractContainerScreen<M> {
    protected final DeviceTabs tabs;

    protected DeviceScreen(M menu, Inventory inventory, Component title, int width, int height) {
        super(menu, inventory, title, width, height);
        this.tabs = new DeviceTabs(menu);
    }

    @Override
    protected void init() {
        super.init();
        placePanel();
        tabs.layout(leftPos, topPos, panelWidth(), panelHeight(), width, height,
                SideTabs.host(this::addRenderableWidget, this::removeWidget));
    }

    /**
     * Moves the panel before the tabs are laid out beside it: a screen that sizes itself to the window sets
     * {@code leftPos} and {@code topPos} here (vanilla centres the panel alone, which can push the tab column off a
     * small window). Does nothing by default.
     */
    protected void placePanel() {
    }

    /**
     * The panel's width, which the tabs go beside: {@code imageWidth}, or less for a screen that sizes its panel to the
     * window ({@code imageWidth} is final and keeps the size the screen was made with).
     */
    protected int panelWidth() {
        return imageWidth;
    }

    /** The panel's height; see {@link #panelWidth}. */
    protected int panelHeight() {
        return imageHeight;
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (tabs.tick()) {
            rebuildWidgets();
        }
    }

    /**
     * Draws the screen's background, the panel ({@link #extractPanel}) and then the open flyout's box and text, so a
     * flyout pushed over the panel on a small window stays readable; widgets draw over all of it.
     */
    @Override
    public final void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        tabs.frame();
        super.extractBackground(graphics, mouseX, mouseY, a);
        extractPanel(graphics, mouseX, mouseY, a);
        if (!tabs.coversPanel()) {
            tabs.extract(graphics, font, mouseX, mouseY);
        }
    }

    /**
     * The panel, its slots and widgets as usual; then a flyout pushed over the panel (a small window) draws above the
     * slots in a later stratum with its widgets, and a slot under the open flyout is neither highlighted for tooltips
     * nor clicked (clicks there stop at the flyout). Upgrade slots, which sit in their flyout, stay usable.
     */
    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractContents(graphics, mouseX, mouseY, a);
        if (tabs.overFlyout(mouseX, mouseY) && !(hoveredSlot instanceof UpgradeSlot)) {
            hoveredSlot = null;
        }
        if (tabs.coversPanel()) {
            graphics.nextStratum();
            tabs.extract(graphics, font, mouseX, mouseY);
            tabs.extractFlyoutWidgets(graphics, mouseX, mouseY, a);
        }
    }

    /** Draws the device's panel and its slots' frames; called by {@link #extractBackground}. */
    protected abstract void extractPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a);

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (tabs.keyPressed(event, getFocused())) {
            if (event.isEscape()) {
                setFocused(null);
            }
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (tabs.mouseClicked(event, hoveredSlot)) {
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** The tabs and the open flyout count as inside, so a click there never throws the carried stack. */
    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top) {
        return super.hasClickedOutside(mouseX, mouseY, left, top) && !tabs.contains(mouseX, mouseY);
    }

    /** The tab column and the open flyout, for recipe viewers to keep clear of. */
    public List<Rect2i> tabAreas() {
        return tabs.areas();
    }

    /** {@link #tabAreas()} of {@code screen} when it is a device screen; empty otherwise. */
    public static List<Rect2i> areasOf(Screen screen) {
        return screen instanceof DeviceScreen<?> device ? device.tabAreas() : List.of();
    }
}
