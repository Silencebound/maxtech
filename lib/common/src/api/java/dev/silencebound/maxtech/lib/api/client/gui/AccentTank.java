package dev.silencebound.maxtech.lib.api.client.gui;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * A fluid tank in the Accent look: an outlined well that fills from the bottom with the fluid's own sprite, tiled and
 * tinted ({@link FluidLook}), with tick marks at each quarter on the left. The tooltip and narration give the fluid's
 * name and "1,250 / 8,000 mB", or "Empty" and "0 / 8,000 mB". The values are read every frame from the suppliers
 * (amounts in mB). The size includes the one-pixel outline; {@link #WIDTH} fits one 16 pixel sprite column. Client
 * thread only.
 */
@ApiStatus.Experimental
public final class AccentTank extends AccentBar {
    /** The usual width: a 16 pixel sprite column inside the outline. */
    public static final int WIDTH = 18;
    /** Lang key of the empty tank's first tooltip line. */
    public static final String EMPTY_KEY = "maxtech_lib.gui.tank.empty";
    /** Lang key of the amount line, "%s / %s mB". */
    public static final String AMOUNT_KEY = "maxtech_lib.gui.tank.amount";
    private static final int TILE = 16;
    /** Tick lengths: the half mark is longer than the quarter marks. */
    private static final int QUARTER_TICK = 2;
    private static final int HALF_TICK = 4;

    private final Supplier<Optional<FluidKey>> fluid;
    private final LongSupplier amount;
    private final LongSupplier capacity;

    /** A tank showing {@code fluid}, {@code amount} and {@code capacity}, read every frame. */
    public AccentTank(int x, int y, int width, int height, AccentTheme theme, Supplier<Optional<FluidKey>> fluid,
            LongSupplier amount, LongSupplier capacity) {
        super(x, y, width, height, theme);
        this.fluid = fluid;
        this.amount = amount;
        this.capacity = capacity;
    }

    @Override
    public List<Component> tooltip() {
        Optional<FluidKey> key = fluid.get();
        long held = amount.getAsLong();
        return tooltip(key.isPresent() && held > 0 ? FluidLook.get().name(key.get()) : null, held,
                capacity.getAsLong());
    }

    /**
     * A tank's tooltip lines: {@code name} and the amount, or "Empty" and 0 when {@code name} is null or {@code amount}
     * is 0 or less.
     */
    public static List<Component> tooltip(@Nullable Component name, long amount, long capacity) {
        if (name == null || amount <= 0) {
            return List.of(Component.translatable(EMPTY_KEY),
                    Component.translatable(AMOUNT_KEY, grouped(0), grouped(Math.max(0, capacity))));
        }
        return List.of(name, Component.translatable(AMOUNT_KEY, grouped(amount), grouped(Math.max(0, capacity))));
    }

    /**
     * The rows of the quarter tick marks, from the top of a well {@code height} pixels tall: the three-quarter mark
     * first, then the half and the quarter, each on the pixel row where that much fluid ends.
     */
    public static int[] tickRows(int height) {
        int[] rows = new int[3];
        for (int i = 0; i < 3; i++) {
            int quarters = 3 - i;
            rows[i] = height - fill(quarters, 4, height);
        }
        return rows;
    }

    @Override
    protected void draw(GuiGraphicsExtractor graphics, boolean lit) {
        int x = getX();
        int y = getY();
        int width = getWidth();
        int height = getHeight();
        graphics.fill(x, y, x + width, y + height, edge(lit));
        int left = x + 1;
        int top = y + 1;
        int right = x + width - 1;
        int bottom = y + height - 1;
        graphics.fill(left, top, right, bottom, Accent.SLOT);
        Optional<FluidKey> key = fluid.get();
        int filled = fill(amount.getAsLong(), capacity.getAsLong(), bottom - top);
        if (key.isPresent() && filled > 0) {
            FluidLook look = FluidLook.get();
            TextureAtlasSprite sprite = look.sprite(key.get());
            int tint = look.tint(key.get());
            graphics.enableScissor(left, bottom - filled, right, bottom);
            for (int tileY = bottom - TILE; tileY > bottom - filled - TILE; tileY -= TILE) {
                for (int tileX = left; tileX < right; tileX += TILE) {
                    graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, tileX, tileY, TILE, TILE, tint);
                }
            }
            graphics.disableScissor();
        }
        int[] rows = tickRows(bottom - top);
        for (int i = 0; i < rows.length; i++) {
            int length = i == 1 ? HALF_TICK : QUARTER_TICK;
            graphics.fill(left, top + rows[i], left + length, top + rows[i] + 1, edge(lit));
        }
    }
}
