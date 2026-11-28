package dev.silencebound.maxtech.lib.client.menu;

import dev.silencebound.maxtech.lib.api.client.gui.Accent;
import dev.silencebound.maxtech.lib.api.client.gui.AccentButton;
import dev.silencebound.maxtech.lib.api.client.gui.AccentEnergyBar;
import dev.silencebound.maxtech.lib.api.client.gui.AccentPanel;
import dev.silencebound.maxtech.lib.api.client.gui.AccentProgress;
import dev.silencebound.maxtech.lib.api.client.gui.AccentStatus;
import dev.silencebound.maxtech.lib.api.client.gui.AccentTank;
import dev.silencebound.maxtech.lib.api.client.gui.Icon;
import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.lib.api.menu.CycleAction;
import dev.silencebound.maxtech.lib.api.menu.EnumValue;
import dev.silencebound.maxtech.lib.api.menu.Layout;
import dev.silencebound.maxtech.lib.api.menu.LayoutPlan;
import dev.silencebound.maxtech.lib.api.menu.MenuChoice;
import dev.silencebound.maxtech.lib.api.menu.MenuDefinition;
import dev.silencebound.maxtech.lib.api.menu.MenuStatus;
import dev.silencebound.maxtech.lib.api.menu.MenuValueView;
import dev.silencebound.maxtech.lib.api.resource.AmountUnit;
import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import dev.silencebound.maxtech.lib.api.style.AccentTheme;
import dev.silencebound.maxtech.lib.client.gui.accent.SideTabs;
import dev.silencebound.maxtech.lib.menu.DefinedMenu;
import dev.silencebound.maxtech.lib.platform.Platform;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import org.jspecify.annotations.Nullable;

/**
 * The screen of a {@link MenuDefinition}: the Accent panel with the definition's title tab and theme, a frame behind
 * every slot, and its layout's boxes as Accent widgets (tank, energy and progress bars, which show their numbers as
 * tooltips) or text (labels, values and status lines, fitted into their boxes with the whole text as a tooltip, and
 * narrated after the title). The panel's size and every box come from {@link MenuDefinition#plan()}, so nothing here
 * moves with the text size: text too large for its box at the client's text size is drawn smaller. A click on a fluid
 * tank sends its bucket click to the server; a cycle button asks the server for the next or previous choice; a faces
 * node ({@link FacesWidget}) asks for a face's next or previous mode. A definition with a side config
 * ({@code MenuDefinition.Builder.sideConfig}) gets one side tab whose flyout holds that faces node. Lib binds it to
 * each registered definition. Client only.
 */
public final class ToolkitScreen<H> extends AbstractContainerScreen<DefinedMenu<H>> {
    /** Room between a status dot and its text. */
    private static final int DOT_GAP = 3;
    /** The side config tab's icon: a block net. */
    private static final Icon SIDE_CONFIG_ICON = Icon.glyph("..##....", "..##....", "########", "########", "..##....",
            "..##....");

    private final MenuDefinition<H> definition;
    private final AccentTheme theme;
    private final List<TankBox> tanks = new ArrayList<>();
    private final @Nullable SideTabs sideTabs;
    private @Nullable AccentPanel panel;

    public ToolkitScreen(DefinedMenu<H> menu, Inventory inventory, Component title) {
        super(menu, inventory, title, menu.definition().plan().panelWidth(), menu.definition().plan().panelHeight());
        this.definition = menu.definition();
        this.theme = definition.theme();
        this.sideTabs = definition.sideConfig().map(this::sideTabs).orElse(null);
    }

    /** The single side config tab over {@code faces}. */
    private SideTabs sideTabs(Layout.Faces faces) {
        SideTabs.Flyout flyout = new SideTabs.Flyout() {
            @Override
            public int width() {
                return faces.width();
            }

            @Override
            public int height() {
                return faces.height();
            }

            @Override
            public void init(SideTabs.Rect content, Consumer<AbstractWidget> add) {
                add.accept(new FacesWidget(content.x(), content.y(), faces, menu, theme));
            }
        };
        return new SideTabs(
                List.of(SideTabs.Tab.of(SIDE_CONFIG_ICON, Component.translatable(FaceTexts.TAB_KEY), flyout)));
    }

    /** Binds {@code definition}'s menu type to this screen; Lib's client entrypoint hands it every registration. */
    @SuppressWarnings("unchecked") // a definition's registered type builds DefinedMenu<H> menus
    public static void bind(MenuDefinition<?> definition) {
        Supplier<MenuType<DefinedMenu<Object>>> type = () -> (MenuType<DefinedMenu<Object>>) definition.type().get();
        Platform.get().menus().<DefinedMenu<Object>, ToolkitScreen<Object>>registerScreen(definition.modId(), type,
                ToolkitScreen::new);
    }

    @Override
    protected void init() {
        super.init();
        // Centred with the title tab kept on the screen.
        topPos = Accent.top(height, imageHeight);
        panel = AccentPanel.of(leftPos, topPos, imageWidth, imageHeight, title).theme(theme);
        tanks.clear();
        MenuValueView values = menu.values();
        for (LayoutPlan.Box box : definition.plan().boxes()) {
            int x = leftPos + box.x();
            int y = topPos + box.y();
            switch (box.node()) {
                case Layout.Tank tank -> {
                    ResourceKind<?> kind = values.kind(tank.value());
                    AmountUnit emptyUnit = kind != null ? kind.unit() : AmountUnit.MILLIBUCKETS;
                    AccentTank widget = addRenderableWidget(new AccentTank(x, y, box.width(), box.height(), theme,
                            emptyUnit, () -> values.resource(tank.value()), () -> values.amount(tank.value()),
                            () -> values.capacity(tank.value())));
                    tanks.add(new TankBox(widget, menu.tankButton(tank.value())));
                }
                case Layout.Energy energy -> addRenderableWidget(new AccentEnergyBar(x, y, box.width(), box.height(),
                        theme, () -> values.stored(energy.value()), () -> values.capacity(energy.value())));
                case Layout.Progress progress -> addRenderableWidget(new AccentProgress(x, y, shape(progress.shape()),
                        theme, () -> values.current(progress.value()), () -> values.total(progress.value())));
                case Layout.CycleButton button -> addRenderableWidget(cycleButton(button, x, y, box));
                case Layout.Faces faces -> addRenderableWidget(new FacesWidget(x, y, faces, menu, theme));
                default -> {
                }
            }
        }
        layoutSideTabs();
    }

    private void layoutSideTabs() {
        SideTabs tabs = sideTabs;
        if (tabs != null) {
            tabs.layout(leftPos, topPos, imageWidth, imageHeight, width, height,
                    SideTabs.host(this::addRenderableWidget, this::removeWidget));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"}) // MenuDefinition.Builder.cycle builds actions over MenuChoice enums
    private CycleButtonWidget cycleButton(Layout.CycleButton node, int x, int y, LayoutPlan.Box box) {
        return new CycleButtonWidget(x, y, box.width(), box.height(), (CycleAction) node.action(), node.key());
    }

    /**
     * A cycle button: the current choice as the label, a left click or Enter asks for the next one, a right click for
     * the previous; the tooltip and the narration name what it sets.
     */
    @SuppressWarnings("EnumOrdinal") // names and tooltip keys index the choices, which are in ordinal order
    private final class CycleButtonWidget<E extends Enum<E> & MenuChoice> extends AccentButton {
        private final CycleAction<E> action;
        private final String key;
        private final Component[] names;

        CycleButtonWidget(int x, int y, int width, int height, CycleAction<E> action, String key) {
            super(x, y, width, height, Component.empty(), () -> {
            });
            this.action = action;
            this.key = key;
            List<E> choices = action.choices();
            names = new Component[choices.size()];
            for (int i = 0; i < names.length; i++) {
                names[i] = Component.translatable(choices.get(i).translationKey());
            }
            label(() -> names[current().ordinal()]);
            tooltip(() -> current().ordinal(), ordinal -> {
                E choice = action.choices().get(ordinal);
                return Component.empty().append(line(choice)).append(CommonComponents.NEW_LINE)
                        .append(Component.translatable(choice.hintKey()));
            });
            onRightClick(() -> ask(action.previous(current())));
            theme(theme);
        }

        private E current() {
            return menu.values().get(action.value());
        }

        private MutableComponent line(E choice) {
            return Component.translatable(key, names[choice.ordinal()]);
        }

        private void ask(E choice) {
            int id = menu.cycleButton(action, choice);
            if (id >= 0 && minecraft != null && minecraft.gameMode != null) {
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
            }
        }

        @Override
        public void onPress(InputWithModifiers input) {
            ask(action.next(current()));
        }

        @Override
        protected MutableComponent createNarrationMessage() {
            return wrapDefaultNarrationMessage(line(current()));
        }
    }

    private static AccentProgress.Shape shape(Layout.ProgressShape shape) {
        return switch (shape) {
            case ARROW -> AccentProgress.Shape.ARROW;
            case FLAME -> AccentProgress.Shape.FLAME;
        };
    }

    private static AccentStatus status(MenuStatus.Tone tone) {
        return switch (tone) {
            case OK -> AccentStatus.OK;
            case WARN -> AccentStatus.WARN;
            case ERROR -> AccentStatus.ERROR;
            case OFF -> AccentStatus.OFF;
        };
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        AccentPanel shown = panel;
        if (shown != null) {
            shown.draw(graphics, font, mouseX, mouseY);
        }
        for (Slot slot : menu.slots) {
            Accent.slot(graphics, leftPos + slot.x, topPos + slot.y);
        }
        SideTabs tabs = sideTabs;
        if (tabs != null) {
            tabs.extractFlyout(graphics, font, mouseX, mouseY);
        }
    }

    /** Escape closes an open side tab flyout before it closes the screen. */
    @Override
    public boolean keyPressed(KeyEvent event) {
        SideTabs tabs = sideTabs;
        if (tabs != null && tabs.keyPressed(event)) {
            return true;
        }
        return super.keyPressed(event);
    }

    /**
     * Labels, values and status lines, relative to the panel; vanilla's title and inventory labels are not drawn.
     */
    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        MenuValueView values = menu.values();
        for (LayoutPlan.Box box : definition.plan().boxes()) {
            switch (box.node()) {
                case Layout.Label label ->
                    boxText(graphics, Component.translatable(label.key()), box.x(), box, mouseX, mouseY);
                case Layout.Value value -> {
                    if (value.shownIn(values)) {
                        boxText(graphics, valueText(values, value), box.x(), box, mouseX, mouseY);
                    }
                }
                case Layout.Status line -> {
                    MenuStatus state = current(values, line.value());
                    float scale = BoxText.factor(font.lineHeight, box.height(), TextScale.factor());
                    int textHeight = (int) Math.ceil(font.lineHeight * scale) - 1;
                    AccentStatus.dot(graphics, box.x(), AccentStatus.dotY(box.y() + BoxText.TOP, textHeight),
                            status(state.tone()).colour());
                    boxText(graphics, Component.translatable(state.translationKey()),
                            box.x() + AccentStatus.SIZE + DOT_GAP, box, mouseX, mouseY);
                }
                default -> {
                }
            }
        }
    }

    private static Component valueText(MenuValueView values, Layout.Value value) {
        return Component.translatable(value.key(), values.get(value.value()));
    }

    /**
     * Draws {@code text} from {@code x} to the right edge of {@code box}: cut with the whole text as a tooltip when too
     * wide, and drawn smaller than the client's text size when that would be taller than the box.
     */
    private void boxText(GuiGraphicsExtractor graphics, Component text, int x, LayoutPlan.Box box, int mouseX,
            int mouseY) {
        int width = box.width() - (x - box.x());
        int y = box.y() + BoxText.TOP;
        float wanted = TextScale.factor();
        float shrink = BoxText.factor(font.lineHeight, box.height(), wanted) / wanted;
        if (shrink >= 1.0F) {
            Accent.text(graphics, font, text, x, y, width, theme.text(), mouseX, mouseY);
            return;
        }
        // Accent.text draws at the client's size inside this pose and maps the mouse through it for the tooltip.
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(shrink, shrink);
        Accent.text(graphics, font, text, 0, 0, (int) Math.floor(width / shrink), theme.text(), mouseX, mouseY);
        graphics.pose().popMatrix();
    }

    /** The title, then every label, value and status line in layout order, then the focused widget. */
    @Override
    protected void updateNarrationState(NarrationElementOutput output) {
        MenuValueView values = menu.values();
        List<Component> parts = new ArrayList<>();
        parts.add(getNarrationMessage());
        for (LayoutPlan.Box box : definition.plan().boxes()) {
            switch (box.node()) {
                case Layout.Label label -> parts.add(Component.translatable(label.key()));
                case Layout.Value value -> {
                    if (value.shownIn(values)) {
                        parts.add(valueText(values, value));
                    }
                }
                case Layout.Status line ->
                    parts.add(Component.translatable(current(values, line.value()).translationKey()));
                default -> {
                }
            }
        }
        output.add(NarratedElementType.TITLE, CommonComponents.joinForNarration(parts.toArray(Component[]::new)));
        updateNarratedWidget(output);
    }

    @SuppressWarnings({"unchecked", "rawtypes"}) // the definition refuses status lines over other enums
    private static MenuStatus current(MenuValueView values, EnumValue<?> value) {
        return (MenuStatus) values.get((EnumValue) value);
    }

    /** A click on a fluid tank moves fluid between the carried container and the tank, on the server. */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        SideTabs tabs = sideTabs;
        if (tabs != null && tabs.mouseClicked(event)) {
            return true;
        }
        if (event.button() == 0 && minecraft != null && minecraft.gameMode != null) {
            for (TankBox tank : tanks) {
                if (tank.button() >= 0 && tank.widget().isMouseOver(event.x(), event.y())
                        && !menu.getCarried().isEmpty()) {
                    minecraft.gameMode.handleInventoryButtonClick(menu.containerId, tank.button());
                    AbstractWidget.playButtonClickSound(minecraft.getSoundManager());
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private record TankBox(AccentTank widget, int button) {
    }
}
