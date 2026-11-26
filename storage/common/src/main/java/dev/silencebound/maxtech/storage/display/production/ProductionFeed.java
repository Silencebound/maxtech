package dev.silencebound.maxtech.storage.display.production;

import dev.silencebound.maxtech.storage.display.RowFeed;
import dev.silencebound.maxtech.storage.display.ScreenRows;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.terminal.AmountFormat;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * A production screen's feed (M7C item 18): the rows of the network's {@link ProductionStats}, one per CPU, stock
 * keeper and machine pool with its last minutes as a small chart, then the items coming in and going out most. The
 * stats count on the network's own move stream, so the feed subscribes to nothing; rates change with time alone, so
 * while someone sees the screen the rows are read again every {@link #REFRESH_TICKS} ticks. Server thread.
 */
public final class ProductionFeed implements RowFeed {
    /** Ticks between two reads while someone sees the screen (5 seconds; the rates move by the minute). */
    public static final int REFRESH_TICKS = 100;

    private final RowFeed.Screen screen;

    public ProductionFeed(RowFeed.Screen screen) {
        this.screen = screen;
    }

    @Override
    public void bind(@Nullable NetworkState network) {
        if (network != null) {
            // Start counting now, so the history exists when the screen is looked at.
            ProductionStats.of(network);
            screen.rowsChanged();
        }
    }

    @Override
    public void unbind() {
    }

    @Override
    public int refreshTicks() {
        return REFRESH_TICKS;
    }

    @Override
    public ScreenRows rows(NetworkState network, long gameTime) {
        return rows(ProductionStats.of(network), gameTime);
    }

    /** The rows for {@code stats} at {@code now}; for tests as well. */
    public static ScreenRows rows(ProductionStats stats, long now) {
        List<ProductionStats.Line> groups = stats.groups(now);
        List<ProductionStats.Top> in = stats.topIn(now);
        List<ProductionStats.Top> out = stats.topOut(now);
        Component title = Component.translatable(ProductionTexts.TITLE);
        Component status = Component.translatable(ProductionTexts.STATUS, AmountFormat.compact(stats.totalIn(now)),
                AmountFormat.compact(stats.totalOut(now)));
        if (groups.isEmpty() && in.isEmpty() && out.isEmpty()) {
            return new ScreenRows(title, status, ScreenRows.NO_FILL, List.of(ScreenRows.Row.of(ScreenRows.DIM,
                    Component.translatable(ProductionTexts.EMPTY), Component.empty())));
        }
        List<ScreenRows.Row> rows = new ArrayList<>();
        // The top lists keep their rows when there are many groups.
        int room = ScreenRows.MAX_ROWS - (in.isEmpty() ? 0 : in.size() + 1) - (out.isEmpty() ? 0 : out.size() + 1);
        for (int i = 0; i < groups.size() && i < room; i++) {
            rows.add(groupRow(groups.get(i)));
        }
        topRows(rows, ProductionTexts.TOP_IN, in);
        topRows(rows, ProductionTexts.TOP_OUT, out);
        return new ScreenRows(title, status, ScreenRows.NO_FILL, rows);
    }

    private static ScreenRows.Row groupRow(ProductionStats.Line line) {
        Component name = Component.translatable(ProductionTexts.groupKey(line.kind()), subject(line));
        List<Long> spark = new ArrayList<>(line.history().size());
        for (long value : line.history()) {
            spark.add(value);
        }
        boolean working = line.perMinute() > 0;
        Component note = working
                ? Component.translatable(ProductionTexts.PER_MINUTE, AmountFormat.compact(line.perMinute()))
                : Component.translatable(ProductionTexts.IDLE);
        return ScreenRows.Row.of(working ? ScreenRows.GOOD : ScreenRows.DIM, name, note).withSpark(spark);
    }

    /** A group's name part: its label, else where the device is. */
    public static String subject(ProductionStats.Line line) {
        if (!line.label().isEmpty()) {
            return line.label();
        }
        long pos = line.pos();
        return BlockPos.getX(pos) + " " + BlockPos.getY(pos) + " " + BlockPos.getZ(pos);
    }

    private static void topRows(List<ScreenRows.Row> rows, String header, List<ProductionStats.Top> tops) {
        if (tops.isEmpty()) {
            return;
        }
        rows.add(
                ScreenRows.Row.of(ScreenRows.DIM | ScreenRows.BAND, Component.translatable(header), Component.empty()));
        for (ProductionStats.Top top : tops) {
            rows.add(ScreenRows.Row
                    .of(ScreenRows.NORMAL, new ItemStack(top.item().item()).getHoverName(),
                            Component.translatable(ProductionTexts.PER_MINUTE, AmountFormat.compact(top.perMinute())))
                    .withIcon(top.item().item()));
        }
    }
}
