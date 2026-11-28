// port-override: base=4a9818efecb5645c
// Jade 15 (1.21.1) has no JadeUI or LayoutElement-based elements: the same elements through IElementHelper, with
// IElement rows; alignSelfCenter becomes the Centred wrapper (Jade 15 draws a row's elements top aligned).
package dev.silencebound.maxtech.lib.neoforge.compat.jade;

import dev.silencebound.maxtech.lib.api.info.InfoStack;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.info.InfoBars;
import dev.silencebound.maxtech.lib.info.InfoEntry;
import dev.silencebound.maxtech.lib.info.InfoFormat;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import snownee.jade.api.fluid.JadeFluidObject;
import snownee.jade.api.ui.BoxStyle;
import snownee.jade.api.ui.Element;
import snownee.jade.api.ui.IElement;
import snownee.jade.api.ui.IElementHelper;

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
    static List<IElement> of(InfoEntry entry, boolean details) {
        IElementHelper ui = IElementHelper.get();
        List<IElement> out = new ArrayList<>();
        switch (entry.kind()) {
            case TEXT -> out.add(ui.text(entry.text()));
            case STATUS -> {
                out.add(new Centred(entry.sprite().map(sprite -> ui.sprite(sprite, ICON, ICON))
                        .orElseGet(() -> new Dot(0xFF000000 | entry.rgb()))));
                if (details) {
                    out.add(ui.spacer(GAP, 0));
                    out.add(ui.text(entry.text().copy().withColor(entry.rgb())));
                }
            }
            case PROGRESS -> {
                entry.sprite().ifPresent(sprite -> {
                    out.add(new Centred(ui.sprite(sprite, ICON, ICON)));
                    out.add(ui.spacer(GAP, 0));
                });
                out.add(ui.progress(entry.fraction(), entry.text(),
                        ui.progressStyle().color(0xFF000000 | entry.rgb()), BoxStyle.getNestedBox(), true));
            }
            case PIPS -> {
                int capacity = (int) entry.max();
                int used = (int) entry.value();
                out.add(new Centred(new Pips(used, capacity)));
                if (capacity > MAX_PIPS) {
                    out.add(ui.spacer(GAP, 0));
                    out.add(ui.text(Component.literal("+" + (capacity - MAX_PIPS))).scale(0.75f));
                }
            }
            case STACKS -> {
                int shown = Math.min(MAX_STACKS, entry.stacks().size());
                for (int i = 0; i < shown; i++) {
                    InfoStack stack = entry.stacks().get(i);
                    if (stack.key() instanceof ItemKey item) {
                        out.add(ui.item(item.toStack(1), 0.75f,
                                stack.amount() > 1 ? InfoFormat.compact(stack.amount()) : ""));
                    } else if (stack.key() instanceof FluidKey fluid) {
                        out.add(ui.fluid(JadeFluidObject.of(fluid.fluid(), stack.amount(), fluid.components()))
                                .size(new Vec2(12, 12)));
                    }
                }
            }
            case ICON -> {
                entry.sprite().ifPresent(sprite -> {
                    out.add(new Centred(ui.sprite(sprite, ICON, ICON)));
                    out.add(ui.spacer(GAP, 0));
                });
                out.add(ui.text(entry.text()));
            }
        }
        return out;
    }

    /** A spacer to put between two entries on one row. */
    static IElement gap() {
        return IElementHelper.get().spacer(GAP * 2, 0);
    }

    /** Draws {@code inner} centred in the height of its row, as 26.1's {@code alignSelfCenter} does. */
    private static final class Centred extends Element {
        private final IElement inner;

        Centred(IElement inner) {
            this.inner = inner;
        }

        @Override
        public Vec2 getSize() {
            return inner.getCachedSize();
        }

        @Override
        public void render(GuiGraphics graphics, float x, float y, float maxX, float maxY) {
            float offset = Math.max(0.0F, (maxY - y - inner.getCachedSize().y) / 2.0F);
            inner.render(graphics, x, y + offset, maxX, maxY);
        }

        @Override
        public String getMessage() {
            return inner.getMessage();
        }
    }

    /** A framed dot in the status colour, for a status without a sprite. */
    private static final class Dot extends Element {
        private final int argb;

        Dot(int argb) {
            this.argb = argb;
        }

        @Override
        public Vec2 getSize() {
            return new Vec2(ICON, ICON);
        }

        @Override
        public void render(GuiGraphics graphics, float x, float y, float maxX, float maxY) {
            graphics.pose().pushPose();
            graphics.pose().translate(x, y, 0.0F);
            graphics.fill(1, 0, ICON - 1, ICON, FRAME);
            graphics.fill(0, 1, ICON, ICON - 1, FRAME);
            graphics.fill(1, 1, ICON - 1, ICON - 1, argb);
            graphics.pose().popPose();
        }

        @Override
        public String getMessage() {
            return "";
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
        }

        @Override
        public Vec2 getSize() {
            return new Vec2(Math.max(1, count * STEP), PIP + 2);
        }

        @Override
        public void render(GuiGraphics graphics, float x, float y, float maxX, float maxY) {
            graphics.pose().pushPose();
            graphics.pose().translate(x, y + 1, 0.0F);
            for (int i = 0; i < count; i++) {
                graphics.fill(i * STEP, 0, i * STEP + PIP, PIP, i < used ? fill : PIP_FREE);
            }
            graphics.pose().popPose();
        }

        @Override
        public String getMessage() {
            return used + "/" + count;
        }
    }
}
