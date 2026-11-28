package dev.silencebound.maxtech.lib.client.menu;

import dev.silencebound.maxtech.lib.api.client.gui.Accent;
import dev.silencebound.maxtech.lib.api.client.gui.AccentEnergyBar;
import dev.silencebound.maxtech.lib.api.client.gui.AccentPanel;
import dev.silencebound.maxtech.lib.api.client.gui.AccentProgress;
import dev.silencebound.maxtech.lib.api.client.gui.AccentStatus;
import dev.silencebound.maxtech.lib.api.client.gui.AccentTank;
import dev.silencebound.maxtech.lib.api.menu.EnumValue;
import dev.silencebound.maxtech.lib.api.menu.Layout;
import dev.silencebound.maxtech.lib.api.menu.LayoutPlan;
import dev.silencebound.maxtech.lib.api.menu.MenuDefinition;
import dev.silencebound.maxtech.lib.api.menu.MenuStatus;
import dev.silencebound.maxtech.lib.api.menu.MenuValueView;
import dev.silencebound.maxtech.lib.api.style.AccentTheme;
import dev.silencebound.maxtech.lib.menu.DefinedMenu;
import dev.silencebound.maxtech.lib.platform.Platform;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import org.jspecify.annotations.Nullable;

/**
 * The screen of a {@link MenuDefinition}: the Accent panel with the definition's title tab and theme, a frame behind
 * every slot, and its layout's boxes as Accent widgets (tank, energy and progress bars, which show their numbers as
 * tooltips) or text (labels and status lines, fitted into their boxes with the whole text as a tooltip). The panel's
 * size and every box come from {@link MenuDefinition#plan()}, so nothing here moves with the text size. A click on a
 * tank sends its bucket click to the server. Lib binds it to each registered definition. Client only.
 */
public final class ToolkitScreen<H> extends AbstractContainerScreen<DefinedMenu<H>> {
    /** Room between a status dot and its text. */
    private static final int DOT_GAP = 3;

    private final MenuDefinition<H> definition;
    private final AccentTheme theme;
    private final List<TankBox> tanks = new ArrayList<>();
    private @Nullable AccentPanel panel;

    public ToolkitScreen(DefinedMenu<H> menu, Inventory inventory, Component title) {
        super(menu, inventory, title, menu.definition().plan().panelWidth(), menu.definition().plan().panelHeight());
        this.definition = menu.definition();
        this.theme = definition.theme();
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
                    AccentTank widget = addRenderableWidget(
                            new AccentTank(x, y, box.width(), box.height(), theme, () -> values.fluid(tank.value()),
                                    () -> values.amount(tank.value()), () -> values.capacity(tank.value())));
                    tanks.add(new TankBox(widget, menu.tankButton(tank.value())));
                }
                case Layout.Energy energy -> addRenderableWidget(new AccentEnergyBar(x, y, box.width(), box.height(),
                        theme, () -> values.stored(energy.value()), () -> values.capacity(energy.value())));
                case Layout.Progress progress -> addRenderableWidget(new AccentProgress(x, y, shape(progress.shape()),
                        theme, () -> values.current(progress.value()), () -> values.total(progress.value())));
                default -> {
                }
            }
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
    }

    /** Labels and status lines, relative to the panel; vanilla's title and inventory labels are not drawn. */
    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        MenuValueView values = menu.values();
        for (LayoutPlan.Box box : definition.plan().boxes()) {
            if (box.node() instanceof Layout.Label label) {
                Accent.text(graphics, font, Component.translatable(label.key()), box.x(), textY(box), box.width(),
                        theme.text(), mouseX, mouseY);
            } else if (box.node() instanceof Layout.Status line) {
                MenuStatus state = current(values, line.value());
                int y = textY(box);
                AccentStatus.dot(graphics, box.x(), AccentStatus.dotY(y, Accent.textHeight(font)),
                        status(state.tone()).colour());
                int textX = box.x() + AccentStatus.SIZE + DOT_GAP;
                Accent.text(graphics, font, Component.translatable(state.translationKey()), textX, y,
                        box.width() - (textX - box.x()), theme.text(), mouseX, mouseY);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"}) // Layout.status takes only enums that implement MenuStatus
    private static MenuStatus current(MenuValueView values, EnumValue<?> value) {
        return (MenuStatus) values.get((EnumValue) value);
    }

    /** Text sits one pixel down in its box, like Accent's other text rows. */
    private static int textY(LayoutPlan.Box box) {
        return box.y() + 1;
    }

    /** A click on a tank moves fluid between the carried container and the tank, on the server. */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && minecraft != null && minecraft.gameMode != null) {
            for (TankBox tank : tanks) {
                if (tank.button() >= 0 && tank.widget().isMouseOver(event.x(), event.y())
                        && !menu.getCarried().isEmpty()) {
                    minecraft.gameMode.handleInventoryButtonClick(menu.containerId, tank.button());
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private record TankBox(AccentTank widget, int button) {
    }
}
