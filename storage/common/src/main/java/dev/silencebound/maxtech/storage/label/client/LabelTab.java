package dev.silencebound.maxtech.storage.label.client;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.label.LabelContent;
import dev.silencebound.maxtech.storage.label.LabelPayloads;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.ChannelShortage;
import dev.silencebound.maxtech.storage.network.NetworkInfo;
import dev.silencebound.maxtech.storage.network.PowerPriority;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.DyeColor;

/**
 * The label tab every network device screen carries (M7L area C): a small Accent Frame tab sticking out of the panel's
 * right edge near its bottom corner, so it never meets the priority tab at the top. It asks the server for the label
 * when made and stays hidden and inert unless the server answers, which it does only for a menu with a device. Clicking
 * it opens the {@link LabelScreen} over the same menu; its tooltip names the label in its colour.
 * <p>
 * For a device logic can turn off ({@code NetworkDevice#switchable}) or load shedding can pause
 * ({@code NetworkDevice#sheddable}) it also draws the status tab right above it: a power sign, green while the device
 * runs, red while a graph has turned it off and amber while low power paused it; its tooltip says which graph on which
 * controller, and the device's power priority. Clicking the status tab steps the power priority (M7L2). The status is
 * asked again once a second while the screen is open, so it follows the graphs and the energy. Client only.
 */
public final class LabelTab extends AbstractButton {
    public static final int SIZE = 20;
    /** The power sign of a device turned off by logic: a plain warning red. */
    private static final int OFF_COLOUR = 0xFFFF5555;
    /** The power sign of a running device: a calm green. */
    private static final int ON_COLOUR = 0xFF55C46A;
    /** The power sign of a device load shedding paused (M7L2): amber, a wait rather than an error. */
    private static final int PAUSED_COLOUR = 0xFFFFAA00;
    /** How far above the panel's bottom edge the tab ends. */
    private static final int OFFSET_BOTTOM = 6;
    /** Space between the status tab and the label tab below it. */
    private static final int STATUS_GAP = 2;
    /** How often an open screen asks for the status again, in milliseconds. */
    private static final long STATUS_REFRESH_MS = 1000;
    /** The power sign, 10 by 9 pixels: a ring open at the top with a bar through the gap. */
    private static final String[] POWER = {"....##....", "..#.##.#..", ".#..##..#.", "#...##...#", "#...##...#",
            "#........#", "#........#", ".#......#.", "..######.."};

    private final Screen screen;
    private final AbstractContainerMenu menu;
    private long lastAsk;

    private LabelTab(Screen screen, AbstractContainerMenu menu, int x, int y) {
        super(x, y, SIZE, SIZE, Component.translatable(LabelContent.TITLE_KEY));
        this.screen = screen;
        this.menu = menu;
        this.lastAsk = Util.getMillis();
    }

    /**
     * The tab for {@code screen}'s panel at ({@code left}, {@code top}), {@code width} by {@code height}; add it with
     * {@code addRenderableWidget} in {@code init}. Asks the server for the menu's label.
     */
    public static LabelTab of(Screen screen, AbstractContainerMenu menu, int left, int top, int width, int height) {
        Rect2i area = area(left, top, width, height);
        LabelPayloads.forget();
        Platform.get().network().sendToServer(new LabelPayloads.Ask(menu.containerId));
        return new LabelTab(screen, menu, area.getX(), area.getY());
    }

    /** Where the tab sits for a panel, for recipe viewers to keep clear of. */
    public static Rect2i area(int left, int top, int width, int height) {
        return new Rect2i(left + width - 1, top + height - OFFSET_BOTTOM - SIZE, SIZE, SIZE);
    }

    /**
     * Where the status tab sits: right above the label tab on the same edge. Every panel with a status tab is taller
     * than the priority tab's 26 px at the top plus the two tabs, so they never meet.
     */
    public static Rect2i statusArea(int left, int top, int width, int height) {
        Rect2i label = area(left, top, width, height);
        return new Rect2i(label.getX(), label.getY() - STATUS_GAP - SIZE, SIZE, SIZE);
    }

    /**
     * The areas of the shown label and status tabs among {@code children}, for recipe viewers; empty when there are
     * none.
     */
    public static List<Rect2i> areas(List<? extends GuiEventListener> children) {
        List<Rect2i> out = new ArrayList<>(2);
        for (GuiEventListener child : children) {
            if (child instanceof LabelTab tab && tab.shown()) {
                out.add(new Rect2i(tab.getX(), tab.getY(), tab.getWidth(), tab.getHeight()));
                if (LabelPayloads.shownStatus()) {
                    out.add(new Rect2i(tab.getX(), tab.statusY(), SIZE, SIZE));
                }
            }
        }
        return out;
    }

    /** The status tab's top edge; see {@link #statusArea}. */
    private int statusY() {
        return getY() - STATUS_GAP - SIZE;
    }

    /** Whether the server said the menu has a device to label. */
    private boolean shown() {
        return LabelPayloads.shownFor(menu.containerId);
    }

    @Override
    public boolean isActive() {
        return super.isActive() && shown();
    }

    @Override
    public void onPress(InputWithModifiers input) {
        if (shown()) {
            Minecraft.getInstance().setScreen(new LabelScreen(screen, menu, LabelPayloads.shown()));
        }
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        if (!shown()) {
            return;
        }
        if (LabelPayloads.shownStatus()) {
            extractStatus(graphics, mouseX, mouseY);
        }
        int x = getX();
        int y = getY();
        AccentFrame.box(graphics, x, y, width, height);
        // The tab opens into the panel: no border on its left side.
        graphics.fill(x, y + 1, x + 2, y + height - 1, AccentFrame.GRAPHITE);
        DeviceLabel label = LabelPayloads.shown();
        DyeColor dye = label.colour();
        int colour = isHovered() ? AccentFrame.TEXT : dye != null ? dye.getTextureDiffuseColor() : AccentFrame.ACCENT;
        // A luggage tag: a body with a pointed end and a hole.
        graphics.fill(x + 7, y + 6, x + 16, y + 14, colour);
        graphics.fill(x + 5, y + 7, x + 7, y + 13, colour);
        graphics.fill(x + 3, y + 9, x + 5, y + 11, colour);
        graphics.fill(x + 7, y + 9, x + 9, y + 11, AccentFrame.GRAPHITE);
        if (isHovered()) {
            graphics.setComponentTooltipForNextFrame(Minecraft.getInstance().font, List.of(LabelContent.line(label)),
                    mouseX, mouseY);
        }
    }

    /** Draws the status tab above the label tab, with its tooltip, and asks for the status again once a second. */
    private void extractStatus(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        long now = Util.getMillis();
        if (now - lastAsk >= STATUS_REFRESH_MS) {
            lastAsk = now;
            Platform.get().network().sendToServer(new LabelPayloads.Ask(menu.containerId));
        }
        int x = getX();
        int y = statusY();
        AccentFrame.box(graphics, x, y, SIZE, SIZE);
        graphics.fill(x, y + 1, x + 2, y + SIZE - 1, AccentFrame.GRAPHITE);
        boolean off = LabelPayloads.shownOff();
        boolean paused = LabelPayloads.shownPowerPaused();
        ChannelShortage offline = LabelPayloads.shownOffline().orElse(null);
        boolean hovered = inStatus(mouseX, mouseY);
        int colour = hovered
                ? AccentFrame.TEXT
                : off || offline != null ? OFF_COLOUR : paused ? PAUSED_COLOUR : ON_COLOUR;
        for (int row = 0; row < POWER.length; row++) {
            String line = POWER[row];
            int start = -1;
            for (int col = 0; col <= line.length(); col++) {
                boolean set = col < line.length() && line.charAt(col) == '#';
                if (set && start < 0) {
                    start = col;
                } else if (!set && start >= 0) {
                    graphics.fill(x + 6 + start, y + 6 + row, x + 6 + col, y + 7 + row, colour);
                    start = -1;
                }
            }
        }
        if (hovered) {
            List<Component> lines = new ArrayList<>(4);
            int priority = LabelPayloads.shownPowerPriority();
            if (off) {
                lines.add(Component.translatable(NetworkInfo.SWITCHED_OFF_KEY).withStyle(ChatFormatting.RED));
                LabelPayloads.shownOffBy().ifPresent(by -> lines.add(by.describe().withStyle(ChatFormatting.GRAY)));
            }
            if (paused) {
                lines.add(Component.translatable(PowerPriority.PAUSED_KEY, priority).withStyle(ChatFormatting.GOLD));
            }
            if (offline != null) {
                lines.add(NetworkInfo.offlineLine(offline).withStyle(ChatFormatting.RED));
            }
            if (!off && !paused && offline == null) {
                lines.add(Component.translatable(NetworkInfo.RUNNING_KEY));
            }
            if (priority > 0) {
                lines.add(PowerPriority.line(priority));
                lines.add(Component.translatable(PowerPriority.CHANGE_KEY).withStyle(ChatFormatting.GRAY));
            }
            graphics.setComponentTooltipForNextFrame(Minecraft.getInstance().font, lines, mouseX, mouseY);
        }
    }

    /** Whether ({@code x}, {@code y}) is on the status tab. */
    private boolean inStatus(double x, double y) {
        int top = statusY();
        return x >= getX() && x < getX() + SIZE && y >= top && y < top + SIZE;
    }

    /** The status tab takes clicks too, while it shows. */
    @Override
    public boolean isMouseOver(double x, double y) {
        return super.isMouseOver(x, y) || (shown() && LabelPayloads.shownStatus() && inStatus(x, y));
    }

    /**
     * A click on the status tab of a device load shedding can pause steps its power priority: left towards optional,
     * right towards essential, wrapping around. The server checks access and answers with the new status.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int priority = LabelPayloads.shownPowerPriority();
        if (shown() && priority > 0 && inStatus(event.x(), event.y()) && (event.button() == 0 || event.button() == 1)) {
            int next = event.button() == 0
                    ? (priority >= PowerPriority.MAX ? PowerPriority.MIN : priority + 1)
                    : (priority <= PowerPriority.MIN ? PowerPriority.MAX : priority - 1);
            Platform.get().network().sendToServer(new LabelPayloads.SetPowerPriority(menu.containerId, next));
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
