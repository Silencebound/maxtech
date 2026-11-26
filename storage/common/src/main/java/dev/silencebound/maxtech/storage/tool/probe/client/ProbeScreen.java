package dev.silencebound.maxtech.storage.tool.probe.client;

import dev.silencebound.maxtech.lib.info.InfoFormat;
import dev.silencebound.maxtech.storage.machine.client.AccentFrame;
import dev.silencebound.maxtech.storage.tool.probe.ProbeContent;
import dev.silencebound.maxtech.storage.tool.probe.ProbeMenu;
import dev.silencebound.maxtech.storage.tool.probe.ProbeSampler;
import dev.silencebound.maxtech.storage.tool.probe.ProbeSeries;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * The signal probe's graph in the Accent Frame style: the link load over the window, newest sample on the right, a
 * dashed-free 0 to 100 scale, the current load, the link's rate and the peak, and below it the block's MaxTech energy
 * network (delivered of demanded MU per tick, and supply) when it has one. A sample with no signal leaves a gap. Client
 * only.
 */
public final class ProbeScreen extends AbstractContainerScreen<ProbeMenu> {
    private static final int GRAPH_X = 8;
    private static final int GRAPH_Y = 40;
    private static final int GRAPH_HEIGHT = 64;
    private static final int GAP_COLOUR = 0xFF5A3030;

    public ProbeScreen(ProbeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, ProbeMenu.WIDTH, ProbeMenu.HEIGHT);
        this.titleLabelY = -100;
        this.inventoryLabelY = -100;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        AccentFrame.panel(graphics, font, leftPos, topPos, imageWidth, imageHeight, title, mouseX, mouseY);
        ProbeSeries series = menu.series();
        int last = series.last();
        Component load = last == ProbeSeries.NO_SIGNAL
                ? Component.translatable(ProbeContent.NO_SIGNAL_KEY)
                : Component.translatable(ProbeContent.LOAD_KEY, InfoFormat.number(last));
        int room = imageWidth - 2 * GRAPH_X;
        AccentFrame.text(graphics, font, load, leftPos + GRAPH_X, topPos + 10, room, AccentFrame.TEXT, mouseX, mouseY);
        AccentFrame.text(graphics, font, Component.translatable(ProbeContent.RATE_KEY, InfoFormat.number(menu.rate())),
                leftPos + GRAPH_X, topPos + 21, room, AccentFrame.LABEL, mouseX, mouseY);
        AccentFrame.text(graphics, font,
                Component.translatable(ProbeContent.SPAN_KEY,
                        InfoFormat.number((long) series.capacity() * menu.sampleTicks() / 20),
                        InfoFormat.number(Math.max(1, menu.sampleTicks() / 20))),
                leftPos + GRAPH_X, topPos + 31, room, AccentFrame.LABEL, mouseX, mouseY);
        graph(graphics, series);
        AccentFrame.text(graphics, font,
                Component.translatable(ProbeContent.PEAK_KEY, InfoFormat.number(series.peak())), leftPos + GRAPH_X,
                topPos + GRAPH_Y + GRAPH_HEIGHT + 6, room, AccentFrame.LABEL, mouseX, mouseY);
        ProbeSampler.Sample energy = menu.energy();
        if (energy.energy()) {
            AccentFrame.text(graphics, font,
                    Component.translatable(ProbeContent.ENERGY_KEY, InfoFormat.number(energy.received()),
                            InfoFormat.number(energy.demand()), InfoFormat.number(energy.supply())),
                    leftPos + GRAPH_X, topPos + GRAPH_Y + GRAPH_HEIGHT + 18, room,
                    energy.received() < energy.demand() ? AccentFrame.TEXT : AccentFrame.LABEL, mouseX, mouseY);
        }
    }

    /** One column per sample slot, the window's oldest at the left; a filled bar with a bright cap at the value. */
    private void graph(GuiGraphicsExtractor graphics, ProbeSeries series) {
        int x = leftPos + GRAPH_X;
        int y = topPos + GRAPH_Y;
        int width = imageWidth - 2 * GRAPH_X;
        AccentFrame.box(graphics, x, y, width, GRAPH_HEIGHT);
        int inner = width - 4;
        int capacity = series.capacity();
        // Midline at 50 %, so a flat high or low load reads at a glance.
        graphics.fill(x + 2, y + GRAPH_HEIGHT / 2, x + width - 2, y + GRAPH_HEIGHT / 2 + 1, AccentFrame.SLOT_EDGE);
        int missing = capacity - series.size();
        for (int i = 0; i < series.size(); i++) {
            int x0 = x + 2 + (missing + i) * inner / capacity;
            int x1 = Math.max(x0 + 1, x + 2 + (missing + i + 1) * inner / capacity);
            int value = series.get(i);
            int floor = y + GRAPH_HEIGHT - 2;
            if (value == ProbeSeries.NO_SIGNAL) {
                graphics.fill(x0, floor - 2, x1, floor, GAP_COLOUR);
                continue;
            }
            int height = (GRAPH_HEIGHT - 4) * value / 100;
            graphics.fill(x0, floor - height, x1, floor, AccentFrame.ACCENT_DIM);
            graphics.fill(x0, floor - height - 1, x1, floor - height + 1, AccentFrame.BAR_TOP);
        }
    }
}
