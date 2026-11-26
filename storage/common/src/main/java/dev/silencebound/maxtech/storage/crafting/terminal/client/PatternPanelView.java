package dev.silencebound.maxtech.storage.crafting.terminal.client;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.client.gui.ScreenComponents;
import dev.silencebound.maxtech.lib.client.gui.TextScale;
import dev.silencebound.maxtech.lib.client.gui.accent.AccentButton;
import dev.silencebound.maxtech.lib.client.gui.accent.Icon;
import dev.silencebound.maxtech.lib.client.gui.accent.IconButton;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternCodecs;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternContent;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternDraft;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternEncoder;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternPanel;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternPanel.Action;
import dev.silencebound.maxtech.storage.crafting.terminal.PatternTerminalContent;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.terminal.AmountFormat;
import dev.silencebound.maxtech.storage.terminal.TerminalLayout;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * The pattern terminal's panel inside the terminal screen (B09 item 30), in the place of the item grid's last three
 * rows ({@link TerminalLayout}): the mode tabs (crafting, processing, stonecutting, smithing) two by two with
 * Substitutions and Advanced under them, the nine inputs, Clear over the encode arrow with x2 and /2 under it for
 * processing, the outputs, and while Advanced edits a processing pattern the face, returned and tag buttons of the
 * selected input. An empty-handed click selects a slot; a click on the item grid then sets it without moving any item
 * ({@link PatternPanel.Ghost}), and dragging an entry from the grid onto a slot does the same. The last encode result
 * shows in the status line. Every edit is a terminal button. Client only.
 */
public final class PatternPanelView {
    private static final String FACES = "DUNSWE";
    private static final int SELECTED = 0x6046B2BD;
    private static final Icon ARROW = Icon.glyph("....................#....", "....................##...",
            "....................###..", "....................####.", "#########################",
            "#########################", "....................####.", "....................###..",
            "....................##...", "....................#....");
    private static final Icon CLEAR = Icon.glyph("#....#", ".#..#.", "..##..", "..##..", ".#..#.", "#....#");
    /** Substitutions: a tag's hash. */
    private static final Icon SUBSTITUTIONS = Icon.glyph("..#..#..", "########", "..#..#..", "..#..#..", "########",
            "..#..#..");
    /** Advanced: three sliders. */
    private static final Icon ADVANCED = Icon.glyph("##.#####", "........", "#####.##", "........", "###.####");

    /** Whether the advanced panel is open; kept between openings. */
    private static boolean advanced;

    private final TerminalMenu menu;
    private final PatternPanel panel;
    private TerminalLayout layout;
    private Font font = Minecraft.getInstance().font;
    /** The slot a grid click sets and the advanced panel edits, -1 for none. */
    private int selected = -1;
    private int left;
    private int top;
    private final List<AbstractWidget> advancedButtons = new ArrayList<>(3);
    private final List<AbstractWidget> scaleButtons = new ArrayList<>(2);

    public PatternPanelView(TerminalMenu menu, PatternPanel panel) {
        this.menu = menu;
        this.panel = panel;
        this.layout = menu.layout();
    }

    /** Adds the panel's widgets for a screen whose panel's top left is ({@code left}, {@code top}). */
    public void init(int left, int top, Consumer<AbstractWidget> add) {
        this.left = left;
        // The screen may have moved the menu to another size since this view was made.
        this.layout = menu.layout();
        this.font = Minecraft.getInstance().font;
        this.top = top;
        advancedButtons.clear();
        scaleButtons.clear();
        modeTab(add, 0, Items.CRAFTING_TABLE, PatternDraft.Mode.CRAFTING, PatternTerminalContent.CRAFTING_KEY,
                Action.MODE_CRAFTING);
        modeTab(add, 1, Items.FURNACE, PatternDraft.Mode.PROCESSING, PatternTerminalContent.PROCESSING_KEY,
                Action.MODE_PROCESSING);
        modeTab(add, 2, Items.STONECUTTER, PatternDraft.Mode.STONECUTTING, PatternTerminalContent.STONECUTTING_KEY,
                Action.MODE_STONECUTTING);
        modeTab(add, 3, Items.SMITHING_TABLE, PatternDraft.Mode.SMITHING, PatternTerminalContent.SMITHING_KEY,
                Action.MODE_SMITHING);
        add.accept(IconButton
                .toggle(left + layout.toggleX(0), top + layout.toggleY(), SUBSTITUTIONS,
                        () -> panel.draft().substitutions(),
                        () -> lines(
                                panel.draft().substitutions()
                                        ? PatternTerminalContent.SUBSTITUTIONS_ON_KEY
                                        : PatternTerminalContent.SUBSTITUTIONS_OFF_KEY,
                                PatternTerminalContent.SUBSTITUTIONS_HINT_KEY),
                        () -> send(Action.SUBSTITUTE, 0))
                .enabled(() -> panel.draft().hasTags()));
        add.accept(IconButton.toggle(left + layout.toggleX(1), top + layout.toggleY(), ADVANCED, () -> advanced,
                () -> lines(advanced ? PatternTerminalContent.ADVANCED_ON_KEY : PatternTerminalContent.ADVANCED_OFF_KEY,
                        PatternTerminalContent.ADVANCED_CRAFTING_KEY, PatternTerminalContent.ADVANCED_HINT_KEY),
                () -> {
                    advanced = !advanced;
                    updateVisibility();
                }));
        add.accept(IconButton.of(left + layout.patternClearX(), top + layout.patternClearY(), CLEAR,
                Component.translatable(PatternTerminalContent.CLEAR_KEY), () -> send(Action.CLEAR, 0)));
        IconButton encode = IconButton.of(left + TerminalLayout.ENCODE_X, top + layout.encodeY(), ARROW,
                Component.translatable(PatternTerminalContent.ENCODE_KEY), () -> send(Action.ENCODE, 0));
        encode.setWidth(TerminalLayout.ENCODE_WIDTH);
        add.accept(encode);
        scaleButton(add, true);
        scaleButton(add, false);
        advancedButtons.add(
                editButton(add, 0, this::faceLabel, this::editable, Action.FACE, this::faceKey, this::faceTooltip));
        advancedButtons.add(editButton(add, 1, () -> Component.literal("R"), this::editable, Action.RETURNED,
                () -> editable() && selectedInput().returned() ? 1 : 0, key -> Component.translatable(
                        key == 1 ? PatternTerminalContent.RETURNED_ON_KEY : PatternTerminalContent.RETURNED_OFF_KEY)));
        advancedButtons.add(
                editButton(add, 2, () -> Component.literal("#"), () -> editable() && selectedInput().tag().isPresent(),
                        Action.EXACT, this::tagKey, key -> Component.translatable(switch (key) {
                            case 1 -> PatternTerminalContent.TAG_LOCKED_KEY;
                            case 2 -> PatternTerminalContent.TAG_ANY_KEY;
                            default -> PatternTerminalContent.TAG_NONE_KEY;
                        })));
        updateVisibility();
    }

    /** Lang keys as lines of one tooltip. */
    private static Component lines(String... keys) {
        MutableComponent text = Component.translatable(keys[0]);
        for (int i = 1; i < keys.length; i++) {
            text.append("\n").append(Component.translatable(keys[i]));
        }
        return text;
    }

    /** A mode tab showing a block's item, named in its tooltip; the current mode is lit. */
    private void modeTab(Consumer<AbstractWidget> add, int tab, Item item, PatternDraft.Mode mode, String key,
            Action action) {
        add.accept(IconButton.toggle(left + layout.modeX(tab), top + layout.modeY(tab), Icon.item(new ItemStack(item)),
                () -> panel.draft().mode() == mode, () -> Component.translatable(key), () -> {
                    if (panel.draft().mode() != mode) {
                        send(action, 0);
                    }
                }));
    }

    /**
     * x2 ({@code multiply}) or /2: scales every amount of a processing draft, by 8 with shift held; greyed out with the
     * reason in its tooltip while the draft refuses.
     */
    private void scaleButton(Consumer<AbstractWidget> add, boolean multiply) {
        AccentButton button = AccentButton.dynamic(left + layout.scaleX(multiply), top + layout.scaleY(),
                TerminalLayout.SCALE_WIDTH, TerminalLayout.SCALE_HEIGHT,
                () -> Component.translatable(
                        multiply ? PatternTerminalContent.SCALE_UP_KEY : PatternTerminalContent.SCALE_DOWN_KEY,
                        scaleFactor()),
                () -> panel.draft().scaleBlock(scaleFactor(), multiply) == null,
                input -> send(multiply ? Action.MULTIPLY : Action.DIVIDE, input.hasShiftDown() ? 1 : 0));
        button.tooltip(() -> scaleKey(multiply), key -> scaleTooltip(key, multiply));
        scaleButtons.add(button);
        add.accept(button);
    }

    /** One of the advanced panel's buttons, in row {@code row} right of the outputs, acting on the selected input. */
    private AccentButton editButton(Consumer<AbstractWidget> add, int row, Supplier<Component> label,
            BooleanSupplier enabled, Action action, IntSupplier tooltipKey, IntFunction<Component> tooltip) {
        AccentButton button = AccentButton
                .dynamic(left + TerminalLayout.EDIT_X, top + layout.editY(row), TerminalLayout.BUTTON,
                        TerminalLayout.BUTTON, label, enabled, input -> send(action, selected))
                .lit(() -> (row == 1 && editable() && selectedInput().returned())
                        || (row == 2 && editable() && selectedInput().tag().isPresent() && !selectedInput().exact()));
        button.tooltip(tooltipKey, tooltip);
        add.accept(button);
        return button;
    }

    /** 8 while shift is held, else 2 ({@link PatternPanel#scaleFactor}). */
    private static int scaleFactor() {
        return PatternPanel.scaleFactor(Minecraft.getInstance().hasShiftDown() ? 1 : 0);
    }

    /** The scale tooltip's state: why it is refused (0 for not) and whether shift is held. */
    private int scaleKey(boolean multiply) {
        int factor = scaleFactor();
        PatternDraft.ScaleBlock block = panel.draft().scaleBlock(factor, multiply);
        int reason = block == null ? 0 : switch (block) {
            case NOT_WHOLE -> 1;
            case TOO_LARGE -> 2;
            case EMPTY, NOT_PROCESSING -> 3;
        };
        return reason * 16 + factor;
    }

    private static Component scaleTooltip(int key, boolean multiply) {
        int factor = key % 16;
        int reason = key / 16;
        return switch (reason) {
            case 0 -> Component.translatable(
                    multiply ? PatternTerminalContent.SCALE_UP_HINT_KEY : PatternTerminalContent.SCALE_DOWN_HINT_KEY);
            case 1 -> Component.translatable(PatternTerminalContent.SCALE_NOT_WHOLE_KEY, factor);
            case 2 -> Component.translatable(PatternTerminalContent.SCALE_TOO_LARGE_KEY, PatternCodecs.MAX_AMOUNT);
            default -> Component.translatable(PatternTerminalContent.SCALE_EMPTY_KEY);
        };
    }

    private void send(Action action, int slot) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, action.button(slot));
        }
    }

    /** Shows the advanced buttons only while they edit a processing pattern, and x2 and /2 likewise. */
    private void updateVisibility() {
        for (AbstractWidget button : advancedButtons) {
            button.visible = advancedShown();
        }
        for (AbstractWidget button : scaleButtons) {
            button.visible = panel.draft().mode() == PatternDraft.Mode.PROCESSING;
        }
    }

    /** Drops a selection the current mode does not use; called every client tick. */
    public void tick() {
        if (selected >= 0 && !used(panel.draft().mode(), selected)) {
            selected = -1;
        }
        updateVisibility();
    }

    /** Whether the advanced panel edits a processing pattern now. */
    private boolean advancedShown() {
        return advanced && panel.draft().mode() == PatternDraft.Mode.PROCESSING;
    }

    private boolean editable() {
        return advancedShown() && selected >= 0 && selected < PatternDraft.INPUTS
                && panel.draft().inputs().get(selected).isPresent();
    }

    private PatternDraft.Input selectedInput() {
        return panel.draft().inputs().get(selected).orElseThrow();
    }

    private Component faceLabel() {
        if (!editable() || selectedInput().face().isEmpty()) {
            return Component.literal("P");
        }
        return Component.literal(String.valueOf(FACES.charAt(selectedInput().face().get().get3DDataValue())));
    }

    /** The face button's tooltip state: 0 for no input selected, else 1 plus the face (0 for the provider's). */
    private int faceKey() {
        if (!editable()) {
            return 0;
        }
        return 1 + selectedInput().face().map(face -> face.get3DDataValue() + 1).orElse(0);
    }

    private Component faceTooltip(int key) {
        if (key == 0) {
            return Component.translatable(PatternTerminalContent.ADVANCED_SELECT_KEY);
        }
        Component face = key == 1
                ? Component.translatable(PatternTerminalContent.FACE_PROVIDER_KEY)
                : Component.translatable(PatternContent.faceKey(Direction.from3DDataValue(key - 2)));
        return Component.translatable(PatternTerminalContent.ADVANCED_SLOT_KEY, selected + 1).append("\n")
                .append(Component.translatable(PatternTerminalContent.FACE_KEY, face));
    }

    /** The tag button's tooltip state: 0 no tag, 1 locked, 2 any. */
    private int tagKey() {
        if (!editable() || selectedInput().tag().isEmpty()) {
            return 0;
        }
        return selectedInput().exact() ? 1 : 2;
    }

    /** Whether {@code mode} uses draft slot {@code slot}: its first inputs and outputs only. */
    private static boolean used(PatternDraft.Mode mode, int slot) {
        return slot < PatternDraft.INPUTS ? slot < mode.inputs() : slot - PatternDraft.INPUTS < mode.outputs();
    }

    /** The draft slot under the mouse, or -1. */
    public int slotAt(double mouseX, double mouseY) {
        PatternDraft.Mode mode = panel.draft().mode();
        for (int slot = 0; slot < PatternPanel.SLOTS; slot++) {
            if (used(mode, slot) && ScreenComponents.isHovering(left + layout.patternX(slot),
                    top + layout.patternY(slot), 16, 16, (int) mouseX, (int) mouseY)) {
                return slot;
            }
        }
        return -1;
    }

    /** Where draft slot {@code slot} is on the screen, or null while the mode does not use it (recipe viewer drops). */
    public @Nullable Rect2i slotArea(int slot) {
        if (slot < 0 || slot >= PatternPanel.SLOTS || !used(panel.draft().mode(), slot)) {
            return null;
        }
        return new Rect2i(left + layout.patternX(slot), top + layout.patternY(slot), 16, 16);
    }

    /** The selected slot, -1 for none. */
    public int selected() {
        return selected;
    }

    /**
     * Selects {@code slot} (-1 for none), as a rebuilt terminal screen does to keep the selection; a slot the draft's
     * mode does not use is dropped on the next tick.
     */
    public void select(int slot) {
        selected = slot >= -1 && slot < PatternPanel.SLOTS ? slot : -1;
    }

    /**
     * Sets the selected slot to {@code key} ({@link #ghost(int, StackKey)}) and moves the selection to the next slot of
     * the same kind the mode uses, so a few clicks on the grid fill a row of inputs.
     */
    public void ghostSelected(StackKey key) {
        int slot = selected;
        if (slot < 0) {
            return;
        }
        ghost(slot, key);
        int next = slot + 1;
        if (next != PatternDraft.INPUTS && used(panel.draft().mode(), next)) {
            selected = next;
        }
    }

    /** Sets draft slot {@code slot} to one of {@code key} on the server; no item moves. */
    public void ghost(int slot, StackKey key) {
        Platform.get().network().sendToServer(new PatternPanel.Ghost(menu.containerId, slot, key));
    }

    /** Draws the used slots' frames and the selection, in screen coordinates. */
    public void background(GuiGraphicsExtractor graphics) {
        PatternDraft.Mode mode = panel.draft().mode();
        for (int slot = 0; slot < PatternPanel.SLOTS; slot++) {
            if (used(mode, slot)) {
                AccentFrame.slot(graphics, left + layout.patternX(slot), top + layout.patternY(slot));
            }
        }
        if (selected >= 0) {
            int x = left + layout.patternX(selected);
            int y = top + layout.patternY(selected);
            graphics.fill(x, y, x + 16, y + 16, SELECTED);
        }
    }

    /** Draws the draft's keys and, in the advanced panel, their marks, in panel coordinates. */
    public void labels(GuiGraphicsExtractor graphics) {
        PatternDraft draft = panel.draft();
        boolean marks = advancedShown();
        for (int i = 0; i < draft.mode().inputs(); i++) {
            Optional<PatternDraft.Input> input = draft.inputs().get(i);
            if (input.isPresent()) {
                PatternDraft.Input value = input.get();
                int x = layout.patternX(i);
                int y = layout.patternY(i);
                drawKey(graphics, value.key(), draft.mode().table() ? 1 : value.amount(), x, y);
                if (marks) {
                    String face = value.face().isPresent()
                            ? String.valueOf(FACES.charAt(value.face().get().get3DDataValue()))
                            : "";
                    TextScale.text(graphics, font, face, x, y, AccentFrame.BAR_TOP, true);
                    if (value.returned()) {
                        TextScale.text(graphics, font, "R", x + 11, y, AccentFrame.BAR_TOP, true);
                    }
                    if (value.tag().isPresent() && !value.exact()) {
                        TextScale.text(graphics, font, "#", x, y + 9, AccentFrame.BAR_TOP, true);
                    }
                }
            }
        }
        for (int i = 0; i < draft.mode().outputs(); i++) {
            Optional<PatternDraft.Output> output = draft.outputs().get(i);
            if (output.isPresent()) {
                int slot = PatternDraft.INPUTS + i;
                drawKey(graphics, output.get().key(), output.get().amount(), layout.patternX(slot),
                        layout.patternY(slot));
            }
        }
    }

    /** The last encode result for the status line, or null before the first encode. */
    public @Nullable Component result() {
        PatternEncoder.Result result = panel.result();
        return result == null ? null : Component.translatable(PatternTerminalContent.resultKey(result));
    }

    /** Whether the last encode worked (the status line's colour). */
    public boolean encoded() {
        return panel.result() == PatternEncoder.Result.ENCODED;
    }

    /** An item with its count, or a fluid as its bucket with its amount. */
    private void drawKey(GuiGraphicsExtractor graphics, StackKey key, long amount, int x, int y) {
        ItemStack icon = switch (key) {
            case ItemKey item -> item.toStack(1);
            case FluidKey fluid -> new ItemStack(fluid.fluid().getBucket());
        };
        graphics.item(icon, x, y);
        if (amount > 1 || key instanceof FluidKey) {
            String label = key instanceof FluidKey ? AmountFormat.fluid(amount) : AmountFormat.compact(amount);
            TextScale.text(graphics, font, label, x + 17 - font.width(label), y + 9, 0xFFFFFFFF, true);
        }
    }

    /**
     * A click on a draft slot: an empty-handed left click selects it, or clears the selected slot (and drops the
     * selection when it is empty already); other clicks are the pattern buttons of the hint lines. False when the mouse
     * is over no slot.
     */
    public boolean mouseClicked(MouseButtonEvent event) {
        int slot = slotAt(event.x(), event.y());
        if (slot < 0) {
            return false;
        }
        if (event.button() == 0 && menu.getCarried().isEmpty() && !event.hasControlDown() && !event.hasAltDown()
                && !event.hasShiftDown()) {
            if (selected != slot) {
                selected = slot;
            } else if (filled(slot)) {
                send(Action.SET, slot);
            } else {
                selected = -1;
            }
            return true;
        }
        Action action = action(slot, event);
        if (action != null) {
            send(action, slot);
        }
        return true;
    }

    private boolean filled(int slot) {
        PatternDraft draft = panel.draft();
        return slot < PatternDraft.INPUTS
                ? draft.inputs().get(slot).isPresent()
                : draft.outputs().get(slot - PatternDraft.INPUTS).isPresent();
    }

    /** What a click other than the selecting one does on {@code slot}; see the hint lines. */
    private @Nullable Action action(int slot, MouseButtonEvent event) {
        boolean input = slot < PatternDraft.INPUTS;
        ItemStack carried = menu.getCarried();
        if (event.button() == 2) {
            return input ? Action.EXACT : null;
        }
        if (event.button() == 1) {
            return carried.isEmpty() ? Action.LESS : Action.MORE;
        }
        if (input && event.hasControlDown()) {
            return Action.FACE;
        }
        if (input && event.hasAltDown()) {
            return Action.RETURNED;
        }
        if (PatternItem.pattern(carried) != null) {
            return Action.LOAD;
        }
        return event.hasShiftDown() ? Action.SET_FLUID : Action.SET;
    }

    /** The tooltip lines over a draft slot, or null when the mouse is over none or a stack is carried. */
    public @Nullable List<Component> tooltip(int mouseX, int mouseY) {
        int slot = slotAt(mouseX, mouseY);
        if (slot < 0 || !menu.getCarried().isEmpty()) {
            return null;
        }
        List<Component> lines = new ArrayList<>();
        PatternDraft draft = panel.draft();
        if (slot < PatternDraft.INPUTS) {
            draft.inputs().get(slot).ifPresent(input -> {
                lines.add(PatternItem.name(input.key()));
                if (input.tag().isPresent()) {
                    lines.add(input.exact()
                            ? Component.translatable(PatternTerminalContent.EXACT_KEY)
                            : Component.translatable(PatternTerminalContent.TAG_KEY, tagName(input.tag().get())));
                }
                if (!draft.mode().table()) {
                    lines.add(Component.translatable(PatternTerminalContent.FACE_KEY,
                            input.face().isPresent()
                                    ? Component.translatable(PatternContent.faceKey(input.face().get()))
                                    : Component.translatable(PatternTerminalContent.FACE_PROVIDER_KEY)));
                }
                if (input.returned()) {
                    lines.add(Component.translatable(PatternTerminalContent.RETURNED_KEY));
                }
            });
        } else {
            draft.outputs().get(slot - PatternDraft.INPUTS)
                    .ifPresent(output -> lines.add(PatternItem.name(output.key())));
        }
        if (slot == selected) {
            lines.add(Component.translatable(PatternTerminalContent.SELECTED_KEY).withColor(AccentFrame.BAR_TOP));
        }
        lines.add(Component.translatable(PatternTerminalContent.SELECT_HINT_KEY));
        lines.add(Component.translatable(draft.mode() == PatternDraft.Mode.STONECUTTING && slot >= PatternDraft.INPUTS
                ? PatternTerminalContent.STONECUTTING_HINT_KEY
                : PatternTerminalContent.HINT_KEY));
        if (slot < PatternDraft.INPUTS) {
            lines.add(Component.translatable(PatternTerminalContent.INPUT_HINT_KEY));
            if (advancedShown()) {
                lines.add(Component.translatable(PatternTerminalContent.ADVANCED_HINT_KEY));
            }
        }
        lines.add(Component.translatable(PatternTerminalContent.LOAD_HINT_KEY));
        return lines;
    }

    private static String tagName(PatternIngredient tag) {
        return switch (tag) {
            case PatternIngredient.ItemTag item -> item.tag().location().toString();
            case PatternIngredient.FluidTag fluid -> fluid.tag().location().toString();
            case PatternIngredient.Exact exact -> "";
        };
    }
}
