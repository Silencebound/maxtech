package dev.silencebound.maxtech.lib.api.client.gui;

import dev.silencebound.maxtech.lib.api.client.resource.ResourceLook;
import dev.silencebound.maxtech.lib.api.client.resource.ResourceLooks;
import dev.silencebound.maxtech.lib.api.resource.AmountUnit;
import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.KindKey;
import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import dev.silencebound.maxtech.lib.api.style.AccentTheme;
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
 * A tank in the Accent look: an outlined well that fills from the bottom with the resource's own sprite, tiled and
 * tinted by its kind's {@link ResourceLook} ({@link FluidLook} for fluids), with tick marks at each quarter on the
 * left. The tooltip and narration give the resource's name and "1,250 / 8,000 mB", or "Empty" and "0 / 8,000 mB"; the
 * amount is in the resource's {@linkplain ResourceKind#unit() unit} ("1,250 / 8,000" for kinds counted one by one). A
 * kind without a registered look draws only the well, named by its kind id; unknown contents
 * ({@link KindKey#isUnknown()}) draw the same and read "Unknown", never "Empty". The values are read every frame from
 * the suppliers. The size includes the one-pixel outline; {@link #WIDTH} fits one 16 pixel sprite column. Client thread
 * only.
 */
@ApiStatus.Experimental
public final class AccentTank extends AccentBar {
    /** The usual width: a 16 pixel sprite column inside the outline. */
    public static final int WIDTH = 18;
    /** Lang key of the empty tank's first tooltip line. */
    public static final String EMPTY_KEY = "maxtech_lib.gui.tank.empty";
    /** Lang key of the amount line in mB, "%s / %s mB". */
    public static final String AMOUNT_KEY = "maxtech_lib.gui.tank.amount";
    /** Lang key of the amount line in other units, "%s / %s", the capacity formatted by its unit. */
    public static final String UNIT_AMOUNT_KEY = "maxtech_lib.gui.tank.amount_in";
    /** Lang key of the first tooltip line of unknown contents. */
    public static final String UNKNOWN_KEY = "maxtech_lib.gui.tank.unknown";
    private static final int TILE = 16;
    /** Tick lengths: the half mark is longer than the quarter marks. */
    private static final int QUARTER_TICK = 2;
    private static final int HALF_TICK = 4;

    private final Supplier<Optional<KindKey<?>>> resource;
    private final AmountUnit emptyUnit;
    private final LongSupplier amount;
    private final LongSupplier capacity;
    /** The tick rows for {@link #ticksHeight}; recomputed only when the well's height changes. */
    private int[] ticks = new int[0];
    private int ticksHeight = -1;

    /** A fluid tank showing {@code fluid}, {@code amount} and {@code capacity} in mB, read every frame. */
    public AccentTank(int x, int y, int width, int height, AccentTheme theme, Supplier<Optional<FluidKey>> fluid,
            LongSupplier amount, LongSupplier capacity) {
        this(x, y, width, height, theme, AmountUnit.MILLIBUCKETS, new FluidAsKind(fluid), amount, capacity);
    }

    /**
     * A tank of any kind showing {@code resource}, {@code amount} and {@code capacity}, read every frame; the amounts
     * are in the resource's unit, and in {@code emptyUnit} while the tank is empty or holds unknown contents of a kind
     * that is not loaded. {@code resource} should return a kept {@code Optional}, as menu values do.
     */
    public AccentTank(int x, int y, int width, int height, AccentTheme theme, AmountUnit emptyUnit,
            Supplier<Optional<KindKey<?>>> resource, LongSupplier amount, LongSupplier capacity) {
        super(x, y, width, height, theme);
        this.resource = resource;
        this.emptyUnit = emptyUnit;
        this.amount = amount;
        this.capacity = capacity;
    }

    @Override
    public List<Component> tooltip() {
        Optional<KindKey<?>> key = resource.get();
        long held = amount.getAsLong();
        long total = capacity.getAsLong();
        if (key.isEmpty()) {
            return tooltip(null, held, total, emptyUnit);
        }
        KindKey<?> present = key.get();
        if (present.isUnknown()) {
            return unknownTooltip(held, total,
                    ResourceKinds.byId(present.kindId()).map(ResourceKind::unit).orElse(emptyUnit));
        }
        return tooltip(name(present), held, total, present.kind().unit());
    }

    /**
     * A fluid tank's tooltip lines: {@code name} and the amount in mB, or "Empty" and 0 when {@code name} is null or
     * {@code amount} is 0 or less.
     */
    public static List<Component> tooltip(@Nullable Component name, long amount, long capacity) {
        return tooltip(name, amount, capacity, AmountUnit.MILLIBUCKETS);
    }

    /**
     * A tank's tooltip lines: {@code name} and the amount in {@code unit}, or "Empty" and 0 when {@code name} is null
     * or {@code amount} is 0 or less.
     */
    public static List<Component> tooltip(@Nullable Component name, long amount, long capacity, AmountUnit unit) {
        if (name == null || amount <= 0) {
            return List.of(Component.translatable(EMPTY_KEY), amountLine(0, capacity, unit));
        }
        return List.of(name, amountLine(amount, capacity, unit));
    }

    /** Unknown contents' tooltip lines: "Unknown" and the amount in {@code unit}, even when it is 0. */
    static List<Component> unknownTooltip(long amount, long capacity, AmountUnit unit) {
        return List.of(Component.translatable(UNKNOWN_KEY), amountLine(amount, capacity, unit));
    }

    /**
     * The amount line: "1,250 / 8,000 mB" in mB, otherwise the amount over the capacity as {@code unit} formats it
     * ("1,250 / 8,000" for items, "1,250 / 8,000 MU"). Negative numbers show as 0.
     */
    public static Component amountLine(long amount, long capacity, AmountUnit unit) {
        long held = Math.max(0, amount);
        long total = Math.max(0, capacity);
        if (unit.equals(AmountUnit.MILLIBUCKETS)) {
            return Component.translatable(AMOUNT_KEY, grouped(held), grouped(total));
        }
        return Component.translatable(UNIT_AMOUNT_KEY, grouped(held), unit.format(total));
    }

    /** The resource's name from its kind's look, or its kind id when the kind has no look. */
    private static <K> Component name(KindKey<K> key) {
        ResourceLook<K> look = ResourceLooks.getOrNull(key.kind());
        return look != null ? look.name(key.key()) : Component.literal(key.kindId().toString());
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
    void draw(GuiGraphicsExtractor graphics, boolean lit) {
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
        Optional<KindKey<?>> key = resource.get();
        int filled = fill(amount.getAsLong(), capacity.getAsLong(), bottom - top);
        if (key.isPresent() && filled > 0) {
            drawFill(graphics, key.get(), left, right, bottom, filled);
        }
        if (ticksHeight != bottom - top) {
            ticksHeight = bottom - top;
            ticks = tickRows(ticksHeight);
        }
        int[] rows = ticks;
        for (int i = 0; i < rows.length; i++) {
            int length = i == 1 ? HALF_TICK : QUARTER_TICK;
            graphics.fill(left, top + rows[i], left + length, top + rows[i] + 1, edge(lit));
        }
    }

    /** Tiles the resource's sprite over the filled part; nothing for a kind without a look (unknown ones included). */
    private static <K> void drawFill(GuiGraphicsExtractor graphics, KindKey<K> key, int left, int right, int bottom,
            int filled) {
        ResourceLook<K> look = ResourceLooks.getOrNull(key.kind());
        if (look == null) {
            return;
        }
        TextureAtlasSprite sprite = look.sprite(key.key());
        int tint = look.tint(key.key());
        graphics.enableScissor(left, bottom - filled, right, bottom);
        try {
            for (int tileY = bottom - TILE; tileY > bottom - filled - TILE; tileY -= TILE) {
                for (int tileX = left; tileX < right; tileX += TILE) {
                    graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, tileX, tileY, TILE, TILE, tint);
                }
            }
        } finally {
            graphics.disableScissor();
        }
    }

    /**
     * A fluid supplier read as a supplier of kind keys; wraps a fluid only when it changed, so a supplier returning a
     * kept {@code Optional} costs nothing per frame.
     */
    private static final class FluidAsKind implements Supplier<Optional<KindKey<?>>> {
        private final Supplier<Optional<FluidKey>> fluid;
        private Optional<FluidKey> last = Optional.empty();
        private Optional<KindKey<?>> wrapped = Optional.empty();

        FluidAsKind(Supplier<Optional<FluidKey>> fluid) {
            this.fluid = fluid;
        }

        @Override
        public Optional<KindKey<?>> get() {
            Optional<FluidKey> current = fluid.get();
            if (!current.equals(last)) {
                last = current;
                wrapped = current.map(key -> new KindKey<>(ResourceKinds.FLUID, key));
            }
            return wrapped;
        }
    }
}
