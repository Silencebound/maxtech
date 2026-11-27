package dev.silencebound.maxtech.storage.logic.controller.client;

import dev.silencebound.maxtech.lib.api.client.gui.TextScale;
import dev.silencebound.maxtech.storage.palette.StatusPalette;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.client.gui.ScreenComponents;
import dev.silencebound.maxtech.storage.logic.LogicLang;
import dev.silencebound.maxtech.storage.logic.Parameters;
import dev.silencebound.maxtech.storage.logic.SettingSpec;
import dev.silencebound.maxtech.storage.logic.controller.ControllerTexts;
import dev.silencebound.maxtech.storage.logic.controller.SharePayloads;
import dev.silencebound.maxtech.storage.logic.template.BuiltInTemplates;
import dev.silencebound.maxtech.storage.logic.template.LogicTemplate;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.lib.api.client.gui.AccentButton;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The Logic Controller's template list (M7L2 sharing), over the controller screen: the templates the server loaded from
 * datapacks, with a search over names and descriptions, a category switch, and the picked template's description. Add
 * (or a double click) asks the server to add it switched off; on success the controller screen comes back with the
 * line, on a refusal the line shows here. Names, descriptions and categories are translated when a lang file has their
 * keys, else shown as the pack wrote them. A template with parameters (M7L2 row 19) asks for their values first: Add
 * shows a small form in place of the list, filled with the defaults, and a second Add sends them; Back leaves the form.
 * Client only.
 */
public final class LogicTemplateScreen extends Screen {
    public static final int WIDTH = 260;
    public static final int HEIGHT = 204;
    private static final int PAD = 8;
    private static final int TOP_ROW = 8;
    private static final int SEARCH_WIDTH = 132;
    private static final int BOX_HEIGHT = 14;
    private static final int LIST_Y = 26;
    private static final int ROWS = 6;
    private static final int ROW = 18;
    private static final int DESCRIPTION_Y = LIST_Y + ROWS * ROW + 4;
    private static final int DESCRIPTION_LINES = 3;
    private static final int BUTTON_Y = HEIGHT - 24;
    private static final int BUTTON_WIDTH = 44;
    private static final int BUTTON_HEIGHT = 16;
    private static final int SELECTED = 0x30FFFFFF;
    private static final int ASK_AGAIN_TICKS = 20;
    /** The parameter form: where its rows start below the list's top, their height and the name column's width. */
    private static final int FORM_Y = 12;
    private static final int FORM_ROW = 12;
    private static final int FORM_LABEL = 96;

    private final LogicControllerScreen parent;
    private @Nullable List<SharePayloads.TemplateInfo> templates;
    private List<SharePayloads.TemplateInfo> shown = List.of();
    private final List<String> categories = new ArrayList<>();
    /** Index into {@link #categories}; -1 is every category. */
    private int category = -1;
    private String query = "";
    private int scroll;
    private SharePayloads.@Nullable TemplateInfo selected;
    private @Nullable Component status;
    private int left;
    private int top;
    private int waited;
    /** The template whose parameter values the form asks for; null while the list shows. */
    private SharePayloads.@Nullable TemplateInfo asking;
    /** The form's values as text, one per parameter. */
    private final List<String> answers = new ArrayList<>();

    public LogicTemplateScreen(LogicControllerScreen parent) {
        super(Component.translatable(ControllerTexts.TEMPLATE_TITLE));
        this.parent = parent;
    }

    /** The controller screen this list opened over. */
    LogicControllerScreen parent() {
        return parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        left = (width - WIDTH) / 2;
        top = AccentFrame.top(height, HEIGHT);
        EditBox search = new EditBox(font, left + PAD, top + TOP_ROW, SEARCH_WIDTH, BOX_HEIGHT,
                Component.translatable(ControllerTexts.TEMPLATE_SEARCH));
        search.setHint(Component.translatable(ControllerTexts.TEMPLATE_SEARCH));
        search.setTextColor(AccentFrame.TEXT);
        search.setValue(query);
        search.setResponder(value -> {
            query = value;
            filter();
        });
        addRenderableWidget(search);
        int categoryX = left + PAD + SEARCH_WIDTH + 4;
        addRenderableWidget(AccentButton.dynamic(categoryX, top + TOP_ROW - 1, left + WIDTH - PAD - categoryX,
                BUTTON_HEIGHT, () -> Component.translatable(ControllerTexts.TEMPLATE_CATEGORY, categoryName()),
                () -> !categories.isEmpty(), input -> nextCategory()));
        addRenderableWidget(new AccentButton(left + WIDTH - PAD - BUTTON_WIDTH, top + BUTTON_Y, BUTTON_WIDTH,
                BUTTON_HEIGHT, Component.translatable(ControllerTexts.TEMPLATE_BACK), () -> true, this::back));
        addRenderableWidget(
                new AccentButton(left + WIDTH - PAD - BUTTON_WIDTH * 2 - 4, top + BUTTON_Y, BUTTON_WIDTH, BUTTON_HEIGHT,
                        Component.translatable(ControllerTexts.TEMPLATE_ADD), () -> selected != null, this::add));
        SharePayloads.TemplateInfo form = asking;
        if (form != null) {
            int fieldX = left + PAD + FORM_LABEL;
            int fieldWidth = WIDTH - PAD * 2 - FORM_LABEL;
            for (int i = 0; i < form.params().size() && i < answers.size(); i++) {
                int at = i;
                int y = top + LIST_Y + FORM_Y + i * FORM_ROW;
                if (form.params().get(i).kind() == Parameters.YES_NO) {
                    addRenderableWidget(AccentButton.dynamic(fieldX, y, fieldWidth, FORM_ROW - 1,
                            () -> Component.translatable(yes(answers.get(at)) ? LogicLang.WORD_YES : LogicLang.WORD_NO),
                            () -> true, input -> answers.set(at, yes(answers.get(at)) ? "no" : "yes")));
                    continue;
                }
                EditBox box = new EditBox(font, fieldX, y, fieldWidth, FORM_ROW - 1,
                        Component.literal(form.params().get(i).name()));
                box.setMaxLength(SettingSpec.MAX_TEXT);
                box.setTextColor(AccentFrame.TEXT);
                box.setValue(answers.get(i));
                box.setResponder(value -> answers.set(at, value));
                addRenderableWidget(box);
            }
        }
        if (templates == null) {
            Platform.get().network().sendToServer(new SharePayloads.TemplateList(parent.getMenu().containerId));
        }
    }

    /** The server's list arrived. */
    void listed(List<SharePayloads.TemplateInfo> list) {
        templates = List.copyOf(list);
        categories.clear();
        for (String known : BuiltInTemplates.CATEGORIES.keySet()) {
            for (SharePayloads.TemplateInfo info : list) {
                if (info.category().equals(known)) {
                    categories.add(known);
                    break;
                }
            }
        }
        List<String> others = new ArrayList<>();
        for (SharePayloads.TemplateInfo info : list) {
            if (!categories.contains(info.category()) && !others.contains(info.category())) {
                others.add(info.category());
            }
        }
        others.sort(null);
        categories.addAll(others);
        category = -1;
        filter();
    }

    /** The server answered the add: back to the controller on success, else the line here. */
    void answered(boolean added, String message, List<String> args) {
        if (added) {
            minecraft.setScreen(parent);
        } else {
            status = Component.translatable(message, args.toArray());
        }
    }

    /**
     * Adds the picked template; one with parameters first shows the form, filled with the defaults, and the next Add
     * sends the form's values.
     */
    private void add() {
        SharePayloads.TemplateInfo form = asking;
        if (form != null) {
            Platform.get().network().sendToServer(
                    new SharePayloads.TemplateAdd(parent.getMenu().containerId, form.id(), List.copyOf(answers)));
            return;
        }
        SharePayloads.TemplateInfo pick = selected;
        if (pick == null) {
            return;
        }
        if (!pick.params().isEmpty()) {
            asking = pick;
            answers.clear();
            for (SharePayloads.ParamInfo param : pick.params()) {
                answers.add(param.value());
            }
            status = null;
            rebuildWidgets();
            return;
        }
        Platform.get().network()
                .sendToServer(new SharePayloads.TemplateAdd(parent.getMenu().containerId, pick.id(), List.of()));
    }

    /** Back: from the form to the list, from the list to the controller screen. */
    private void back() {
        if (asking != null) {
            asking = null;
            status = null;
            rebuildWidgets();
        } else {
            onClose();
        }
    }

    private static boolean yes(String text) {
        return !text.strip().equalsIgnoreCase("no") && !text.strip().equalsIgnoreCase("false")
                && !text.strip().equals("0");
    }

    private void nextCategory() {
        category = category + 1 >= categories.size() ? -1 : category + 1;
        filter();
    }

    private Component categoryName() {
        return category < 0 ? Component.translatable(ControllerTexts.TEMPLATE_ALL) : category(categories.get(category));
    }

    private static Component category(String id) {
        return Component.translatableWithFallback(LogicTemplate.categoryKey(id),
                id.isEmpty() ? id : Character.toUpperCase(id.charAt(0)) + id.substring(1).replace('_', ' '));
    }

    private static String name(SharePayloads.TemplateInfo info) {
        return I18n.exists(LogicTemplate.nameKey(info.id())) ? I18n.get(LogicTemplate.nameKey(info.id())) : info.name();
    }

    private static String description(SharePayloads.TemplateInfo info) {
        return I18n.exists(LogicTemplate.descriptionKey(info.id()))
                ? I18n.get(LogicTemplate.descriptionKey(info.id()))
                : info.description();
    }

    /** Applies the search and the category to the list; keeps the pick when it still shows. */
    private void filter() {
        List<SharePayloads.TemplateInfo> all = templates;
        if (all == null) {
            return;
        }
        String wanted = query.trim().toLowerCase(Locale.ROOT);
        String only = category < 0 ? null : categories.get(category);
        List<SharePayloads.TemplateInfo> result = new ArrayList<>();
        for (SharePayloads.TemplateInfo info : all) {
            if (only != null && !info.category().equals(only)) {
                continue;
            }
            if (!wanted.isEmpty() && !name(info).toLowerCase(Locale.ROOT).contains(wanted)
                    && !description(info).toLowerCase(Locale.ROOT).contains(wanted)) {
                continue;
            }
            result.add(info);
        }
        shown = result;
        if (selected != null && !shown.contains(selected)) {
            selected = null;
        }
        scroll = Math.max(0, Math.min(scroll, shown.size() - ROWS));
    }

    @Override
    public void tick() {
        super.tick();
        if (minecraft.player == null || minecraft.player.containerMenu.containerId != parent.getMenu().containerId) {
            minecraft.setScreen(null);
            return;
        }
        // The server leaves a list asked too soon after the last unanswered (its rate limit): ask again.
        if (templates == null && ++waited % ASK_AGAIN_TICKS == 0) {
            Platform.get().network().sendToServer(new SharePayloads.TemplateList(parent.getMenu().containerId));
        }
    }

    /** Escape and Back return to the controller screen. */
    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (asking == null && shown.size() > ROWS) {
            scroll = Math.max(0, Math.min(shown.size() - ROWS, scroll - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick) || asking != null) {
            return true;
        }
        int mx = (int) event.x() - left - PAD;
        int my = (int) event.y() - top - LIST_Y;
        if (mx >= 0 && mx < WIDTH - PAD * 2 && my >= 0 && my < ROWS * ROW) {
            int row = scroll + my / ROW;
            if (row < shown.size()) {
                selected = shown.get(row);
                status = null;
                if (doubleClick) {
                    add();
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        AccentFrame.panel(graphics, font, left, top, WIDTH, HEIGHT, title, mouseX, mouseY);
        graphics.fill(left + PAD - 1, top + LIST_Y - 1, left + WIDTH - PAD + 1, top + LIST_Y + ROWS * ROW + 1,
                AccentFrame.SLOT);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        int x = left + PAD;
        int listWidth = WIDTH - PAD * 2;
        SharePayloads.TemplateInfo form = asking;
        if (form != null) {
            // The parameter form in place of the list: a heading, then each parameter's name beside its field.
            AccentFrame.text(graphics, font, Component.translatable(ControllerTexts.TEMPLATE_VALUES, name(form)), x + 2,
                    top + LIST_Y + 2, listWidth - 4, AccentFrame.LABEL, mouseX, mouseY);
            for (int i = 0; i < form.params().size(); i++) {
                AccentFrame.text(graphics, font, Component.literal(form.params().get(i).name()), x + 2,
                        top + LIST_Y + FORM_Y + i * FORM_ROW + 2, FORM_LABEL - 6, AccentFrame.TEXT, mouseX, mouseY);
            }
            ScreenComponents.fittedLines(graphics, font, Component.translatable(ControllerTexts.TEMPLATE_VALUES_HINT),
                    x, top + DESCRIPTION_Y + 11, listWidth, DESCRIPTION_LINES, 10, AccentFrame.LABEL, mouseX, mouseY);
            Component line = status;
            if (line != null) {
                AccentFrame.text(graphics, font,
                        Component.literal(StatusPalette.mark(StatusPalette.Tone.BAD)).append(line), x,
                        top + BUTTON_Y + 4, WIDTH - PAD * 3 - BUTTON_WIDTH * 2 - 4, StatusPalette.bad(), mouseX,
                        mouseY);
            }
            return;
        }
        if (templates == null) {
            TextScale.text(graphics, font, Component.translatable(ControllerTexts.TEMPLATE_LOADING), x + 4,
                    top + LIST_Y + 5, AccentFrame.LABEL, false);
        } else if (shown.isEmpty()) {
            TextScale.text(graphics, font, Component.translatable(ControllerTexts.TEMPLATE_NONE), x + 4,
                    top + LIST_Y + 5, AccentFrame.LABEL, false);
        }
        for (int row = 0; row < ROWS && scroll + row < shown.size(); row++) {
            SharePayloads.TemplateInfo info = shown.get(scroll + row);
            int y = top + LIST_Y + row * ROW;
            if (info.equals(selected)) {
                graphics.fill(x, y, x + listWidth, y + ROW, SELECTED);
            }
            info.icon().flatMap(BuiltInRegistries.ITEM::getOptional)
                    .ifPresent(item -> graphics.item(new ItemStack(item), x + 1, y + 1));
            String nodes = I18n.get(ControllerTexts.TEMPLATE_NODES, info.nodes());
            int nodesWidth = font.width(nodes);
            TextScale.text(graphics, font, nodes, x + listWidth - 4 - nodesWidth, y + 5, AccentFrame.LABEL, false);
            AccentFrame.text(graphics, font, Component.literal(name(info)), x + 20, y + 5, listWidth - 30 - nodesWidth,
                    AccentFrame.TEXT, mouseX, mouseY);
        }
        SharePayloads.TemplateInfo pick = selected;
        if (pick != null) {
            Component heading = category(pick.category());
            TextScale.text(graphics, font, heading, x, top + DESCRIPTION_Y, AccentFrame.LABEL, false);
            ScreenComponents.fittedLines(graphics, font, Component.literal(description(pick)), x,
                    top + DESCRIPTION_Y + 11, listWidth, DESCRIPTION_LINES, 10, AccentFrame.TEXT, mouseX, mouseY);
        }
        Component line = status;
        if (line != null) {
            AccentFrame.text(graphics, font, Component.literal(StatusPalette.mark(StatusPalette.Tone.BAD)).append(line),
                    x, top + BUTTON_Y + 4, WIDTH - PAD * 3 - BUTTON_WIDTH * 2 - 4, StatusPalette.bad(), mouseX, mouseY);

        }
    }
}
