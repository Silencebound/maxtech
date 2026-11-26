package dev.silencebound.maxtech.storage.devicetab.client;

import dev.silencebound.maxtech.lib.client.gui.accent.Accent;
import dev.silencebound.maxtech.lib.client.gui.accent.Icon;
import dev.silencebound.maxtech.lib.client.gui.accent.SideTabs;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.devicetab.DeviceTab;
import dev.silencebound.maxtech.storage.devicetab.DeviceTabLayout;
import dev.silencebound.maxtech.storage.devicetab.DeviceTabTexts;
import dev.silencebound.maxtech.storage.devicetab.UpgradeSlot;
import dev.silencebound.maxtech.storage.label.LabelMenu;
import dev.silencebound.maxtech.storage.label.LabelPayloads;
import dev.silencebound.maxtech.storage.logic.controller.LogicContent;
import dev.silencebound.maxtech.storage.machine.client.MenuClick;
import dev.silencebound.maxtech.storage.material.DataCrystalContent;
import dev.silencebound.maxtech.storage.network.ChannelShortage;
import dev.silencebound.maxtech.storage.network.NetworkInfo;
import dev.silencebound.maxtech.storage.network.PowerPriority;
import dev.silencebound.maxtech.storage.network.SwitchSource;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.priority.PriorityMenu;
import dev.silencebound.maxtech.storage.redstone.RedstoneMode;
import dev.silencebound.maxtech.storage.redstone.RedstoneSync;
import dev.silencebound.maxtech.storage.redstone.RedstoneTexts;
import dev.silencebound.maxtech.storage.tool.quarantine.QuarantineContent;
import dev.silencebound.maxtech.storage.tool.tracepen.TracePenContent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * A device screen's side tabs (B09 item 21b, owner decision 2026-10-18), built on Lib's {@link SideTabs}: in the fixed
 * order of {@link DeviceTab}, only the tabs the device has. A screen ({@link DeviceScreen}) declares them in its
 * constructor:
 * <ul>
 * <li>{@link #mode()}: the device's own settings as {@link SettingsFlyout} rows, plus {@link #redstone}; the status
 * (running, turned off by logic, paused by load shedding, offline for channels) and the power priority follow on their
 * own for a {@link LabelMenu}, as the old status tab showed them.</li>
 * <li>{@link #priority}, {@link #upgrades}, {@link #access}: with a visibility check, so a port shows its priority and
 * access only in storage mode.</li>
 * <li>The label tab comes with every {@link LabelMenu}.</li>
 * </ul>
 * Problems show as a badge on the mode tab (else the first tab): offline for channels in red, the device's own
 * ({@link #badge}), logic or load shedding in amber. Tabs whose settings differ from the defaults carry the accent dot.
 * The set of tabs and of the mode flyout's rows is checked every tick ({@link #changed}); the screen lays itself out
 * again when it changes, keeping the open tab. Client only.
 */
public final class DeviceTabs {
    private static final int STATUS_ASK_TICKS = 20;
    private static final int GREEN = 0xFF55C46A;

    private final AbstractContainerMenu menu;
    private final boolean labelled;
    private final SettingsFlyout mode = new SettingsFlyout();
    private final List<Spec> specs = new ArrayList<>(DeviceTab.values().length);
    private BooleanSupplier modeChanged = () -> false;
    private IntSupplier deviceState = () -> 0;
    private Supplier<SideTabs.@Nullable Badge> deviceBuild = () -> null;
    /** The device's own badge, built only when its state changes. */
    private SideTabs.@Nullable Badge deviceBadge;
    private int deviceKey;
    private int statusVersion;
    private boolean badgeBuilt;
    /**
     * The badge shown; rebuilt only when the status or the device's state changes, so ticks and frames allocate
     * nothing.
     */
    private SideTabs.@Nullable Badge shownBadge;
    private boolean statusAdded;
    private @Nullable UpgradesFlyout upgrades;
    private UpgradeSlot.@Nullable Shown upgradesShown;
    private @Nullable SideTabs tabs;
    private List<DeviceTab> shown = List.of();
    private long signature = Long.MIN_VALUE;
    private int ticks;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    /** Every widget the tabs added to the screen (tab buttons and the open flyout's widgets). */
    private final List<AbstractWidget> widgets = new ArrayList<>();
    private int screenWidth;

    /** One declared tab. */
    private record Spec(DeviceTab tab, SideTabs.Flyout flyout, BooleanSupplier shown, BooleanSupplier changed) {
    }

    /** Tabs for {@code menu}'s screen; for a {@link LabelMenu}, asks the server for the label and the status. */
    public DeviceTabs(AbstractContainerMenu menu) {
        this.menu = menu;
        this.labelled = menu instanceof LabelMenu;
        if (labelled) {
            LabelPayloads.forget();
            ask();
            specs.add(new Spec(DeviceTab.LABEL, new LabelFlyout(menu), () -> true,
                    () -> LabelPayloads.shownFor(menu.containerId) && !LabelPayloads.shown().isEmpty()));
        }
    }

    private void ask() {
        Platform.get().network().sendToServer(new LabelPayloads.Ask(menu.containerId));
    }

    /** The mode tab's flyout, for the device's own settings; add rows in the screen's constructor. */
    public SettingsFlyout mode() {
        return mode;
    }

    /** The mode tab's dot shows while {@code changed} is true (or the redstone mode is not the default). */
    public DeviceTabs modeChanged(BooleanSupplier changed) {
        BooleanSupplier before = modeChanged;
        modeChanged = () -> before.getAsBoolean() || changed.getAsBoolean();
        return this;
    }

    /**
     * Adds the redstone mode (B09 item 9) to the mode tab: a button stepping it through menu buttons {@code forwardId}
     * and {@code backId}, with the mode's hint as its tooltip, and a line while the mode keeps the device inactive.
     */
    public DeviceTabs redstone(Supplier<RedstoneSync.State> state, int forwardId, int backId) {
        mode.setting(Component.translatable(RedstoneTexts.CAPTION_KEY),
                () -> Component.translatable(state.get().mode().translationKey()),
                () -> MenuClick.send(menu, forwardId), () -> MenuClick.send(menu, backId),
                () -> state.get().mode().id(),
                id -> CommonComponents.joinLines(Component.translatable(RedstoneMode.byId(id).translationKey()),
                        Component.translatable(RedstoneMode.byId(id).hintKey()).withColor(Accent.LABEL)));
        mode.line(() -> {
            RedstoneSync.State now = state.get();
            if (now.inactive()) {
                return Component.translatable(
                        now.powered() ? RedstoneTexts.HAS_SIGNAL_KEY : RedstoneTexts.WAITS_FOR_SIGNAL_KEY);
            }
            return now.mode() == RedstoneMode.PULSE ? Component.translatable(RedstoneTexts.WAITS_FOR_PULSE_KEY) : null;
        }, () -> Accent.LABEL, () -> true);
        return modeChanged(() -> state.get().mode() != RedstoneMode.IGNORE);
    }

    /** The shared priority tab for a {@link PriorityMenu}, shown while {@code shown} is true. */
    public <M extends AbstractContainerMenu & PriorityMenu> DeviceTabs priority(M priorityMenu, BooleanSupplier shown) {
        specs.add(new Spec(DeviceTab.PRIORITY, new PriorityFlyout<>(priorityMenu), shown,
                () -> priorityMenu.priority() != 0));
        return this;
    }

    /** A priority tab with its own flyout (a stock keeper's rule priority). */
    public DeviceTabs priority(SideTabs.Flyout flyout, BooleanSupplier shown, BooleanSupplier changed) {
        specs.add(new Spec(DeviceTab.PRIORITY, flyout, shown, changed));
        return this;
    }

    /**
     * The upgrades tab over the menu's {@code slots} upgrade slots, which read {@code flag}; {@code note} is the line
     * under them in {@code noteColour} (null for none). Its dot shows while a slot holds a card.
     */
    public DeviceTabs upgrades(List<Slot> slots, UpgradeSlot.Shown flag, @Nullable Supplier<@Nullable Component> note,
            IntSupplier noteColour) {
        UpgradesFlyout flyout = new UpgradesFlyout(slots.size(),
                () -> screenWidth - (panelX + panelWidth + DeviceTabLayout.CONTENT_RIGHT_OF_PANEL) - SideTabs.PAD, note,
                noteColour);
        upgrades = flyout;
        upgradesShown = flag;
        specs.add(new Spec(DeviceTab.UPGRADES, flyout, () -> !slots.isEmpty(), () -> {
            for (Slot slot : slots) {
                if (slot.hasItem()) {
                    return true;
                }
            }
            return false;
        }));
        return this;
    }

    /** The access tab with {@code flyout}, shown while {@code shown} is true. */
    public DeviceTabs access(SettingsFlyout flyout, BooleanSupplier shown, BooleanSupplier changed) {
        specs.add(new Spec(DeviceTab.ACCESS, flyout, () -> shown.getAsBoolean() && flyout.anyShown(), changed));
        return this;
    }

    /**
     * The device's own problem, shown as a badge: {@code build} makes it (null for none) and is called only when
     * {@code state}, a cheap code of what the badge depends on (data slots), changes. Client thread.
     */
    public DeviceTabs badge(IntSupplier state, Supplier<SideTabs.@Nullable Badge> build) {
        this.deviceState = state;
        this.deviceBuild = build;
        this.badgeBuilt = false;
        return this;
    }

    // --- Status ------------------------------------------------------------------------------------------------

    /** The status rows the old status tab showed, for devices the server reports a status for. */
    private void addStatusRows() {
        if (statusAdded) {
            return;
        }
        statusAdded = true;
        if (!labelled) {
            return;
        }
        mode.line(this::statusLine, this::statusColour,
                () -> LabelPayloads.shownFor(menu.containerId) && LabelPayloads.shownStatus());
        mode.line(() -> {
            SwitchSource by = LabelPayloads.shownOffBy().orElse(null);
            return LabelPayloads.shownOff() && by != null ? by.describe() : null;
        }, () -> Accent.LABEL, () -> LabelPayloads.shownFor(menu.containerId));
        mode.setting(Component.translatable(DeviceTabTexts.POWER_CAPTION_KEY), () -> {
            int priority = LabelPayloads.shownPowerPriority();
            return Component.literal(priority + " ").append(PowerPriority.name(priority));
        }, () -> stepPower(true), () -> stepPower(false), () -> 0,
                ignored -> Component.translatable(PowerPriority.CHANGE_KEY), () -> true,
                () -> LabelPayloads.shownFor(menu.containerId) && LabelPayloads.shownPowerPriority() > 0);
    }

    private @Nullable Component statusLine() {
        ChannelShortage offline = LabelPayloads.shownOffline().orElse(null);
        if (offline != null) {
            return NetworkInfo.offlineLine(offline);
        }
        if (LabelPayloads.shownOff()) {
            return Component.translatable(NetworkInfo.SWITCHED_OFF_KEY);
        }
        if (LabelPayloads.shownPowerPaused()) {
            return Component.translatable(PowerPriority.PAUSED_KEY, LabelPayloads.shownPowerPriority());
        }
        return Component.translatable(NetworkInfo.RUNNING_KEY);
    }

    private int statusColour() {
        if (LabelPayloads.shownOffline().isPresent() || LabelPayloads.shownOff()) {
            return Accent.RED;
        }
        return LabelPayloads.shownPowerPaused() ? Accent.AMBER : GREEN;
    }

    /**
     * Steps the power priority: left towards optional, right towards essential, wrapping around; the server checks
     * access and answers with the new status.
     */
    private void stepPower(boolean forward) {
        int priority = LabelPayloads.shownPowerPriority();
        if (priority <= 0) {
            return;
        }
        int next = forward
                ? (priority >= PowerPriority.MAX ? PowerPriority.MIN : priority + 1)
                : (priority <= PowerPriority.MIN ? PowerPriority.MAX : priority - 1);
        Platform.get().network().sendToServer(new LabelPayloads.SetPowerPriority(menu.containerId, next));
    }

    private SideTabs.@Nullable Badge shownBadge() {
        return shownBadge;
    }

    /**
     * The badge on the badge tab: offline in red, then the device's own problem, then a network without power, logic
     * and load shedding in amber.
     */
    private SideTabs.@Nullable Badge badge() {
        boolean status = labelled && LabelPayloads.shownFor(menu.containerId);
        ChannelShortage offline = status ? LabelPayloads.shownOffline().orElse(null) : null;
        if (offline != null) {
            return new SideTabs.Badge(SideTabs.Level.ERROR, NetworkInfo.offlineLine(offline));
        }
        SideTabs.Badge own = deviceBadge;
        if (own != null) {
            return own;
        }
        if (status && LabelPayloads.shownUnpowered()) {
            return new SideTabs.Badge(SideTabs.Level.WARN, Component.translatable(DeviceTabTexts.NO_POWER_KEY));
        }
        if (status && LabelPayloads.shownOff()) {
            return new SideTabs.Badge(SideTabs.Level.WARN, Component.translatable(NetworkInfo.SWITCHED_OFF_KEY));
        }
        if (status && LabelPayloads.shownPowerPaused()) {
            return new SideTabs.Badge(SideTabs.Level.WARN,
                    Component.translatable(PowerPriority.PAUSED_KEY, LabelPayloads.shownPowerPriority()));
        }
        return null;
    }

    // --- Layout ------------------------------------------------------------------------------------------------

    /** The tabs shown now, as a mask of {@link DeviceTab#bit()}s. */
    int mask() {
        int mask = 0;
        if (mode.anyShown()) {
            mask |= DeviceTab.MODE.bit();
        }
        for (Spec spec : specs) {
            if (spec.shown.getAsBoolean()) {
                mask |= spec.tab.bit();
            }
        }
        return mask;
    }

    private long currentSignature() {
        long access = 0;
        for (Spec spec : specs) {
            if (spec.tab == DeviceTab.ACCESS && spec.flyout instanceof SettingsFlyout flyout) {
                access = flyout.signature();
            }
        }
        return mask() | ((long) mode.signature() << 8) | (access << 40);
    }

    /**
     * Lays the tabs out beside the panel at ({@code x}, {@code y}), {@code width} by {@code height}, on a window
     * {@code screenWidth} by {@code screenHeight}; call from the screen's {@code init}. Keeps the open tab open.
     */
    public void layout(int x, int y, int width, int height, int screenWidth, int screenHeight, SideTabs.Host host) {
        addStatusRows();
        DeviceTab open = openTab();
        SideTabs old = tabs;
        if (old != null) {
            old.close();
        }
        panelX = x;
        panelY = y;
        panelWidth = width;
        panelHeight = height;
        this.screenWidth = screenWidth;
        signature = currentSignature();
        int mask = mask();
        shown = DeviceTab.shown(mask);
        DeviceTab badgeTab = DeviceTab.badgeTab(mask);
        List<SideTabs.Tab> list = new ArrayList<>(shown.size());
        for (DeviceTab tab : shown) {
            SideTabs.Tab built = SideTabs.Tab.of(icon(tab), Component.translatable(tab.titleKey()), flyout(tab))
                    .order(tab.order()).changed(changed(tab));
            if (tab == badgeTab) {
                built.badge(this::shownBadge);
            }
            list.add(built);
        }
        SideTabs next = new SideTabs(list);
        tabs = next;
        widgets.clear();
        SideTabs.Host tracking = SideTabs.host(widget -> {
            widgets.add(widget);
            host.add(widget);
        }, widget -> {
            widgets.remove(widget);
            host.remove(widget);
        });
        next.layout(x, y, width, height, screenWidth, screenHeight, tracking);
        if (open != null && shown.contains(open)) {
            next.open(shown.indexOf(open));
        }
    }

    private SideTabs.Flyout flyout(DeviceTab tab) {
        if (tab == DeviceTab.MODE) {
            return mode;
        }
        for (Spec spec : specs) {
            if (spec.tab == tab) {
                return spec.flyout;
            }
        }
        throw new IllegalStateException("no flyout for " + tab);
    }

    private BooleanSupplier changed(DeviceTab tab) {
        if (tab == DeviceTab.MODE) {
            return modeChanged;
        }
        for (Spec spec : specs) {
            if (spec.tab == tab) {
                return spec.changed;
            }
        }
        return () -> false;
    }

    private static Icon icon(DeviceTab tab) {
        ItemLike item = switch (tab) {
            case MODE -> LogicContent.CARD.get();
            case PRIORITY -> DataCrystalContent.DATA_CRYSTAL_WAFER.get();
            case UPGRADES -> PortContent.SPEED_CARD.get();
            case LABEL -> TracePenContent.TRACE_PEN.get();
            case ACCESS -> QuarantineContent.QUARANTINE_TAG.get();
        };
        return Icon.item(new ItemStack(item));
    }

    /** The tab open now, null for none. */
    public @Nullable DeviceTab openTab() {
        SideTabs current = tabs;
        int index = current == null ? -1 : current.openIndex();
        return index >= 0 && index < shown.size() ? shown.get(index) : null;
    }

    /** Opens {@code tab} when the device has it; for tests and screens that open a tab themselves. */
    public void open(DeviceTab tab) {
        SideTabs current = tabs;
        if (current != null && shown.contains(tab) && openTab() != tab) {
            current.open(shown.indexOf(tab));
        }
    }

    // --- Every tick and frame ------------------------------------------------------------------------------------

    /**
     * Called every client tick: asks for the status once a second and returns whether the tabs or the mode flyout's
     * rows changed, so the screen must lay itself out again.
     */
    public boolean tick() {
        if (labelled && ++ticks >= STATUS_ASK_TICKS) {
            ticks = 0;
            ask();
        }
        int key = deviceState.getAsInt();
        int version = LabelPayloads.version();
        if (!badgeBuilt || key != deviceKey || version != statusVersion) {
            badgeBuilt = true;
            deviceKey = key;
            statusVersion = version;
            deviceBadge = deviceBuild.get();
            shownBadge = badge();
        }
        return tabs != null && currentSignature() != signature;
    }

    /** Called every frame before drawing: shows the upgrade slots while their flyout is open where they sit. */
    public void frame() {
        UpgradesFlyout flyout = upgrades;
        UpgradeSlot.Shown flag = upgradesShown;
        if (flyout == null || flag == null) {
            return;
        }
        SideTabs current = tabs;
        SideTabs.Rect box = current == null ? null : current.flyoutBox();
        boolean placed = openTab() == DeviceTab.UPGRADES && box != null
                && box.x() + SideTabs.PAD == panelX + panelWidth + DeviceTabLayout.CONTENT_RIGHT_OF_PANEL
                && box.y() == panelY;
        flyout.place(placed);
        flag.set(placed);
    }

    /**
     * Whether the open flyout overlaps the panel: on a window too small for it beside the panel it is pushed over it,
     * and must then draw above the panel's slots and hide them from the mouse.
     */
    public boolean coversPanel() {
        SideTabs current = tabs;
        SideTabs.Rect box = current == null ? null : current.flyoutBox();
        return box != null && box.x() < panelX + panelWidth && box.right() > panelX && box.y() < panelY + panelHeight
                && box.bottom() > panelY;
    }

    /** Whether ({@code x}, {@code y}) is on the open flyout. */
    public boolean overFlyout(double x, double y) {
        SideTabs current = tabs;
        SideTabs.Rect box = current == null ? null : current.flyoutBox();
        return box != null && box.contains(x, y);
    }

    /** Draws the open flyout's widgets again, for a flyout drawn above the panel's slots ({@link #coversPanel}). */
    public void extractFlyoutWidgets(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        SideTabs current = tabs;
        SideTabs.Rect box = current == null ? null : current.flyoutBox();
        if (box == null) {
            return;
        }
        for (int i = 0; i < widgets.size(); i++) {
            AbstractWidget widget = widgets.get(i);
            if (box.contains(widget.getX(), widget.getY())) {
                widget.extractRenderState(graphics, mouseX, mouseY, a);
            }
        }
    }

    /** Draws the open flyout's box and content; call from the screen's background after the panel. */
    public void extract(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        SideTabs current = tabs;
        if (current != null) {
            current.extractFlyout(graphics, font, mouseX, mouseY);
        }
    }

    /**
     * Escape closes an open flyout; while a text field in the flyout has the focus it takes every other key, so typing
     * never closes the screen or uses the hotbar keys. Returns whether the key was used.
     */
    public boolean keyPressed(KeyEvent event, @Nullable GuiEventListener focused) {
        SideTabs current = tabs;
        if (current == null) {
            return false;
        }
        SideTabs.Rect box = current.flyoutBox();
        if (focused instanceof EditBox field && field.isFocused() && box != null
                && box.contains(field.getX(), field.getY()) && !event.isEscape()) {
            field.keyPressed(event);
            return true;
        }
        return current.keyPressed(event);
    }

    /**
     * A click: closes the flyout when it lands outside it, and swallows one inside the flyout or the tab column that
     * misses their widgets, except on an upgrade slot the flyout shows. Returns whether the screen must stop there.
     */
    public boolean mouseClicked(MouseButtonEvent event, @Nullable Slot hovered) {
        SideTabs current = tabs;
        if (current == null) {
            return false;
        }
        SideTabs.Rect box = current.flyoutBox();
        if (hovered instanceof UpgradeSlot && hovered.isActive() && box != null && box.contains(event.x(), event.y())) {
            return false;
        }
        return current.mouseClicked(event);
    }

    /** Whether ({@code x}, {@code y}) is on the tab column or the open flyout. */
    public boolean contains(double x, double y) {
        SideTabs current = tabs;
        if (current == null) {
            return false;
        }
        for (SideTabs.Rect rect : current.areas()) {
            if (rect.contains(x, y)) {
                return true;
            }
        }
        return false;
    }

    /** The tab column and the open flyout, for recipe viewers to keep clear of. */
    public List<Rect2i> areas() {
        SideTabs current = tabs;
        if (current == null) {
            return List.of();
        }
        List<SideTabs.Rect> rects = current.areas();
        List<Rect2i> out = new ArrayList<>(rects.size());
        for (SideTabs.Rect rect : rects) {
            out.add(new Rect2i(rect.x(), rect.y(), rect.width(), rect.height()));
        }
        return out;
    }
}
