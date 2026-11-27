package dev.silencebound.maxtech.lib.api.client.gui;

import dev.silencebound.maxtech.lib.api.style.AccentTheme;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetworks;
import dev.silencebound.maxtech.lib.api.energy.FeRate;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.ApiStatus;

/**
 * An energy gauge in the Accent look: a narrow outlined bar that fills from the bottom with the theme's gauge gradient,
 * cut into cells by a dark line every {@link #CELL} pixels so it never reads as a tank. The tooltip and narration give
 * "X / Y MU" first and the same amounts in FE at the configured rate below ({@link EnergyNetworks#feRate()} unless a
 * rate source is given). Values are MU, read every frame. The size includes the one-pixel outline. Client thread only.
 */
@ApiStatus.Experimental
public final class AccentEnergyBar extends AccentBar {
    /** The usual width. */
    public static final int WIDTH = 10;
    /** The fill is cut into cells this many pixels tall, the last row of each a dark line. */
    public static final int CELL = 3;
    /** Lang key of the MU line, "%s / %s MU". */
    public static final String MU_KEY = "maxtech_lib.gui.energy.mu";
    /** Lang key of the FE line, "%s / %s FE". */
    public static final String FE_KEY = "maxtech_lib.gui.energy.fe";

    private final LongSupplier stored;
    private final LongSupplier capacity;
    private final Supplier<FeRate> rate;

    /** A gauge showing {@code stored} of {@code capacity} MU, with FE at the configured rate. */
    public AccentEnergyBar(int x, int y, int width, int height, AccentTheme theme, LongSupplier stored,
            LongSupplier capacity) {
        this(x, y, width, height, theme, stored, capacity, EnergyNetworks::feRate);
    }

    /** A gauge showing {@code stored} of {@code capacity} MU, with FE at the rate {@code rate} gives. */
    public AccentEnergyBar(int x, int y, int width, int height, AccentTheme theme, LongSupplier stored,
            LongSupplier capacity, Supplier<FeRate> rate) {
        super(x, y, width, height, theme);
        this.stored = stored;
        this.capacity = capacity;
        this.rate = rate;
    }

    @Override
    public List<Component> tooltip() {
        return tooltip(stored.getAsLong(), capacity.getAsLong(), rate.get());
    }

    /**
     * A gauge's tooltip lines: MU first, then FE at {@code rate}. Negative amounts show as 0; FE amounts too large for
     * a {@code long} show as {@link Long#MAX_VALUE} ({@link FeRate#toFe} saturates).
     */
    public static List<Component> tooltip(long stored, long capacity, FeRate rate) {
        long mu = Math.max(0, stored);
        long max = Math.max(0, capacity);
        return List.of(Component.translatable(MU_KEY, grouped(mu), grouped(max)),
                Component.translatable(FE_KEY, grouped(rate.toFe(mu)), grouped(rate.toFe(max))));
    }

    /** Whether row {@code row} of a fill, counted up from its bottom, is a dark line between cells. */
    public static boolean cellGap(int row) {
        return row % CELL == CELL - 1;
    }

    @Override
    void draw(GuiGraphicsExtractor graphics, boolean lit) {
        int x = getX();
        int y = getY();
        int right = x + getWidth();
        int bottom = y + getHeight();
        graphics.fill(x, y, right, bottom, edge(lit));
        graphics.fill(x + 1, y + 1, right - 1, bottom - 1, Accent.SLOT);
        int inner = getHeight() - 2;
        int filled = fill(stored.getAsLong(), capacity.getAsLong(), inner);
        if (filled > 0) {
            graphics.fillGradient(x + 1, bottom - 1 - filled, right - 1, bottom - 1, theme.barTop(), theme.barBottom());
            for (int row = 0; row < filled; row++) {
                if (cellGap(row)) {
                    graphics.fill(x + 1, bottom - 2 - row, right - 1, bottom - 1 - row, Accent.SLOT);
                }
            }
        }
    }
}
