package dev.silencebound.maxtech.lib.neoforge.compat.jade;

import dev.silencebound.maxtech.lib.api.info.InfoStack;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.info.InfoBars;
import dev.silencebound.maxtech.lib.info.InfoEntry;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.network.chat.Component;
import snownee.jade.api.fluid.JadeFluidObject;
import snownee.jade.api.ui.BoxStyle;
import snownee.jade.api.ui.Element;
import snownee.jade.api.ui.JadeUI;
import snownee.jade.api.view.ProgressView;

/**
 * Turns {@link InfoEntry} values into Jade elements: a status as its sprite or a framed dot, a bar as Jade's progress
 * element with the label inside, pips as a row of small squares, stacks as item and fluid icons with compact amounts,
 * an icon as its sprite and text. Client only.
 */
final class InfoElements {
    /** Most pips drawn; the rest show as "+n". */
    static final int MAX_PIPS = 32;
    /** Most stacks drawn in a row. */
    static final int MAX_STACKS = 5;
    private static final int ICON = 8;
    private static final int GAP = 2;
    private static final int FRAME = 0xFF1A1D21;
    private static final int PIP_FREE = 0xFF353A42;

    private InfoElements() {
    }

    /** The elements of one entry, left to right; {@code details} adds a status's word. */
    static List<LayoutElement> of(InfoEntry entry, boolean details) {
        List<LayoutElement> out = new ArrayList<>();
        switch (entry.kind()) {
            case TEXT -> out.add(JadeUI.text(entry.text()));
            case STATUS -> {
                out.add(entry.sprite().<Element>map(sprite -> JadeUI.sprite(sprite, ICON, ICON))
                        .orElseGet(() -> new Dot(0xFF000000 | entry.rgb())).alignSelfCenter());
                if (details) {
                    out.add(JadeUI.spacer(GAP, 0));
                    out.add(JadeUI.text(entry.text().copy().withColor(entry.rgb())));
                }
            }
            case PROGRESS -> {
                entry.sprite().ifPresent(sprite -> {
                    out.add(JadeUI.sprite(sprite, ICON, ICON).alignSelfCenter());
                    out.add(JadeUI.spacer(GAP, 0));
                });
                out.add(JadeUI
                        .progress(new ProgressView(ProgressView.Part.of(entry.fraction(), 0xFF000000 | entry.rgb()),
                                entry.text(), JadeUI.progressStyle(), BoxStyle.nestedBox())));
            }
            case PIPS -> {
                int capacity = (int) entry.max();
                int used = (int) entry.value();
                out.add(new Pips(used, capacity).alignSelfCenter());
                if (capacity > MAX_PIPS) {
                    out.add(JadeUI.spacer(GAP, 0));
                    out.add(JadeUI.text(Component.literal("+" + (capacity - MAX_PIPS))).scale(0.75f));
                }
            }
            case STACKS -> {
                int shown = Math.min(MAX_STACKS, entry.stacks().size());
                for (int i = 0; i < shown; i++) {
                    InfoStack stack = entry.stacks().get(i);
                    if (stack.key() instanceof ItemKey item) {
                        out.add(JadeUI.item(item.toStack(1), 0.75f,
                                stack.amount() > 1 ? InfoFormat.compact(stack.amount()) : ""));
                    } else if (stack.key() instanceof FluidKey fluid) {
                        out.add(JadeUI.fluid(JadeFluidObject.of(fluid.fluid(), stack.amount(), fluid.components()))
                                .size(12, 12));
                    }
                }
            }
            case ICON -> {
                entry.sprite().ifPresent(sprite -> {
                    out.add(JadeUI.sprite(sprite, ICON, ICON).alignSelfCenter());
                    out.add(JadeUI.spacer(GAP, 0));
                });
                out.add(JadeUI.text(entry.text()));
            }
        }
        return out;
    }

    /** A spacer to put between two entries on one row. */
    static LayoutElement gap() {
        return JadeUI.spacer(GAP * 2, 0);
    }

    /** A framed dot in the status colour, for a status without a sprite. */
    private static final class Dot extends Element {
        private final int argb;

        Dot(int argb) {
            this.argb = argb;
            this.width = ICON;
            this.height = ICON;
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY();
            graphics.fill(x + 1, y, x + ICON - 1, y + ICON, FRAME);
            graphics.fill(x, y + 1, x + ICON, y + ICON - 1, FRAME);
            graphics.fill(x + 1, y + 1, x + ICON - 1, y + ICON - 1, argb);
        }

        @Override
        public Component getNarration() {
            return Component.empty();
        }
    }

    /**
     * A row of channel pips: {@code used} of {@code capacity} filled in the bar colours ({@link InfoBars#fill}), at
     * most {@link #MAX_PIPS} drawn.
     */
    private static final class Pips extends Element {
        private static final int PIP = 3;
        private static final int STEP = 4;
        private final int used;
        private final int count;
        private final int fill;

        Pips(int used, int capacity) {
            this.used = used;
            this.count = Math.min(capacity, MAX_PIPS);
            this.fill = 0xFF000000 | InfoBars.fill(used, capacity);
            this.width = Math.max(1, count * STEP);
            this.height = PIP + 2;
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY() + 1;
            for (int i = 0; i < count; i++) {
                graphics.fill(x + i * STEP, y, x + i * STEP + PIP, y + PIP, i < used ? fill : PIP_FREE);
            }
        }

        @Override
        public Component getNarration() {
            return Component.literal(used + "/" + count);
        }
    }
}
