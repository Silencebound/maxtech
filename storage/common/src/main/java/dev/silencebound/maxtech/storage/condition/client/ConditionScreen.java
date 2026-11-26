package dev.silencebound.maxtech.storage.condition.client;

import dev.silencebound.maxtech.lib.client.gui.accent.AccentButton;
import dev.silencebound.maxtech.lib.client.gui.TextScale;
import dev.silencebound.maxtech.storage.label.client.LabelTab;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.condition.ConditionMenu;
import dev.silencebound.maxtech.storage.condition.ConditionTexts;
import dev.silencebound.maxtech.storage.condition.ConditionType;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.research.ResearchNode;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The condition switch's GUI in the Accent Frame style: the condition type and inversion, the watched item slot with
 * the tag or research node next to it, the side and threshold with step buttons, and the output. Everything it shows
 * comes from the menu's synced data; every button is a menu button click. Client only.
 */
public final class ConditionScreen extends AbstractContainerScreen<ConditionMenu> {
    private static final int ROW_TYPE = 18;
    private static final int ROW_SIDE = 62;
    private static final int ROW_STEPS = 80;
    private static final int HEIGHT = 16;

    private @Nullable AccentButton next;

    public ConditionScreen(ConditionMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, ConditionMenu.WIDTH, ConditionMenu.HEIGHT);
        this.inventoryLabelY = ConditionMenu.INVENTORY_Y - 11;
        this.titleLabelY = -100;
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(LabelTab.of(this, menu, leftPos, topPos, imageWidth, imageHeight));
        // The tooltip explains the type shown; it is built again only when the type changes.
        add(8, ROW_TYPE, 100, ConditionMenu.CYCLE_TYPE, () -> Component.translatable(menu.type().translationKey()))
                .tooltip(() -> menu.type().id(), id -> Component.translatable(ConditionType.byId(id).hintKey()));
        add(112, ROW_TYPE, 56, ConditionMenu.TOGGLE_INVERTED,
                () -> Component.translatable(menu.inverted() ? ConditionTexts.INVERTED : ConditionTexts.NOT_INVERTED));
        next = add(128, ConditionMenu.SLOT_Y, 40, ConditionMenu.NEXT_TAG, () -> Component.literal(">"));
        add(8, ROW_SIDE, 44, ConditionMenu.TOGGLE_SIDE,
                () -> Component.translatable(menu.above() ? ConditionTexts.ABOVE : ConditionTexts.BELOW));
        for (int i = 0; i < ConditionMenu.STEP_COUNT; i++) {
            String label = ConditionMenu.stepLabel(i);
            add(8 + i * 20, ROW_STEPS, 19, ConditionMenu.FIRST_STEP + i, () -> Component.literal(label));
        }
    }

    private AccentButton add(int x, int y, int width, int id, Supplier<Component> label) {
        return addRenderableWidget(
                new AccentButton(leftPos + x, topPos + y, width, HEIGHT, label.get(), () -> click(target(id)))
                        .label(label));
    }

    /** The menu button a press sends; the next-tag button doubles as next-node and next-channel. */
    private int target(int id) {
        if (id != ConditionMenu.NEXT_TAG) {
            return id;
        }
        return menu.type() == ConditionType.RESEARCH
                ? ConditionMenu.NEXT_NODE
                : menu.type().channel() ? ConditionMenu.NEXT_CHANNEL : id;
    }

    private void click(int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        AccentButton button = next;
        if (button != null) {
            button.visible = menu.type() == ConditionType.TAG_COUNT || menu.type() == ConditionType.RESEARCH
                    || menu.type().channel();
        }
        AccentFrame.panel(graphics, font, leftPos, topPos, imageWidth, imageHeight, title, mouseX, mouseY);
        for (Slot slot : menu.slots) {
            if (slot.isActive()) {
                AccentFrame.slot(graphics, leftPos + slot.x, topPos + slot.y);
            }
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        AccentButton button = next;
        int subjectRight = button != null && button.visible ? button.getX() - leftPos - 4 : imageWidth - 8;
        AccentFrame.text(graphics, font, subject(), 30, ConditionMenu.SLOT_Y + 4, subjectRight - 30, AccentFrame.LABEL,
                xm, ym);
        if (menu.type() != ConditionType.LOGIC && !menu.type().channel() && !menu.type().crafting()) {
            String threshold = InfoFormat.number(menu.threshold()) + (menu.type().percent() ? "%" : "");
            AccentFrame.text(graphics, font, Component.translatable(ConditionTexts.THRESHOLD, threshold), 58,
                    ROW_SIDE + 4, imageWidth - 8 - 58, AccentFrame.LABEL, xm, ym);
        }
        // The output sits on the inventory label's line, right-aligned: beside the threshold it had no room.
        Component output = Component.translatable(menu.output() ? ConditionTexts.OUTPUT_ON : ConditionTexts.OUTPUT_OFF);
        int outputX = imageWidth - 8 - font.width(output);
        TextScale.text(graphics, font, output, outputX, inventoryLabelY + 1, AccentFrame.LABEL, false);
        AccentFrame.text(graphics, font, playerInventoryTitle, inventoryLabelX, inventoryLabelY + 1,
                outputX - 6 - inventoryLabelX, AccentFrame.LABEL, xm, ym);
    }

    /** What the condition watches, worked out from the synced indices with the same lists the server uses. */
    private Component subject() {
        ItemStack item = menu.watchedItem();
        return switch (menu.type()) {
            case TAG_COUNT -> tagName(item);
            case RESEARCH -> nodeName();
            case ITEM_COUNT, WHILE_CRAFTING, CRAFT_REQUEST ->
                item.isEmpty() ? Component.translatable(ConditionTexts.NOTHING) : item.getHoverName();
            case LOGIC -> Component.translatable(ConditionTexts.BY_LOGIC);
            case CHANNEL_OUT, CHANNEL_IN -> ConditionTexts.channel(menu.channel());
            case POWER, OVERLOAD -> Component.empty();
        };
    }

    private Component tagName(ItemStack item) {
        List<TagKey<Item>> tags = ConditionMenu.tags(item.getItem());
        int index = menu.tagIndex();
        return item.isEmpty() || index < 0 || index >= tags.size()
                ? Component.translatable(ConditionTexts.NOTHING)
                : Component.literal("#" + tags.get(index).location());
    }

    private Component nodeName() {
        if (minecraft == null || minecraft.level == null) {
            return Component.empty();
        }
        List<Identifier> nodes = ConditionMenu.nodes(minecraft.level.registryAccess());
        int index = menu.nodeIndex();
        return index < 0 || index >= nodes.size()
                ? Component.translatable(ConditionTexts.NOTHING)
                : Component.translatable(ResearchNode.nameKey(nodes.get(index)));
    }
}
