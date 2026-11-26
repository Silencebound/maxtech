package dev.silencebound.maxtech.storage.display;

import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayDeque;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Merges panel blocks into wall screens (PRD 7.2, Devices): panels that touch in one plane and face the same way form a
 * group, which {@link WallLayout} cuts into rectangles of at most {@code display.maxWallSize} panels a side. Each
 * rectangle is one screen whose bottom-left panel (as the viewer sees it) is the anchor: it shows the wall and owns its
 * {@link ScreenWorker}. Each panel's block state carries the edges of its screen it lies on, so the model draws the
 * frame around the screen only, not between its panels. Recomputed only when a panel is placed or broken, one group at
 * a time, never per tick.
 */
public final class DisplayWalls {
    /** A group larger than this is cut off; a performance bound on one reform, not a balance value. */
    static final int MAX_GROUP = 4096;

    private DisplayWalls() {
    }

    /** The viewer's right on a screen facing {@code facing} (for up and down, north is the screen's top). */
    public static Direction right(Direction facing) {
        return switch (facing) {
            case NORTH -> Direction.WEST;
            case SOUTH -> Direction.EAST;
            case WEST -> Direction.SOUTH;
            case EAST -> Direction.NORTH;
            case UP -> Direction.EAST;
            case DOWN -> Direction.WEST;
        };
    }

    /** The viewer's up on a screen facing {@code facing}. */
    public static Direction up(Direction facing) {
        return facing.getAxis() == Direction.Axis.Y ? Direction.NORTH : Direction.UP;
    }

    static int along(BlockPos pos, Direction direction) {
        return pos.getX() * direction.getStepX() + pos.getY() * direction.getStepY()
                + pos.getZ() * direction.getStepZ();
    }

    /** Re-forms the walls of the group {@code start} belongs to. */
    static void reform(ServerLevel level, BlockPos start) {
        if (!(level.getBlockEntity(start) instanceof DisplayPanelBlockEntity first)) {
            return;
        }
        Direction facing = first.facing();
        Direction right = right(facing);
        Direction up = up(facing);
        Long2ObjectOpenHashMap<DisplayPanelBlockEntity> group = new Long2ObjectOpenHashMap<>();
        ArrayDeque<DisplayPanelBlockEntity> open = new ArrayDeque<>();
        group.put(WallLayout.pack(along(start, right), along(start, up)), first);
        open.add(first);
        Direction[] steps = {right, right.getOpposite(), up, up.getOpposite()};
        while (!open.isEmpty() && group.size() < MAX_GROUP) {
            BlockPos pos = open.poll().getBlockPos();
            for (Direction step : steps) {
                BlockPos next = pos.relative(step);
                long cell = WallLayout.pack(along(next, right), along(next, up));
                DisplayPanelBlockEntity panel = group.containsKey(cell) ? null : panel(level, next, facing);
                if (panel != null) {
                    group.put(cell, panel);
                    open.add(panel);
                }
            }
        }
        int max = StorageServerConfig.DISPLAY_MAX_WALL_SIZE.getAsInt();
        List<WallLayout.Rect> rects = WallLayout.partition(group.keySet(), max, max);
        for (WallLayout.Rect rect : rects) {
            DisplayPanelBlockEntity anchor = group.get(WallLayout.pack(rect.u(), rect.v()));
            DisplaySettings settings = wallSettings(group, rect, anchor);
            for (int dv = 0; dv < rect.height(); dv++) {
                for (int du = 0; du < rect.width(); du++) {
                    DisplayPanelBlockEntity panel = group.get(WallLayout.pack(rect.u() + du, rect.v() + dv));
                    panel.setSettings(settings);
                    panel.setWall(anchor.getBlockPos(), rect.width(), rect.height(),
                            WallLayout.frame(du, dv, rect.width(), rect.height()));
                }
            }
        }
    }

    /**
     * The settings a re-formed wall shows, which every panel of it takes so the anchor never shows something other than
     * what was set on any of its panels: the anchor's own, unless they are the defaults and another panel's are not
     * (settings applied to a panel before its wall formed, or to a panel that was not the anchor of a wall that since
     * merged), then the first such panel's, bottom row first, left to right.
     */
    private static DisplaySettings wallSettings(Long2ObjectOpenHashMap<DisplayPanelBlockEntity> group,
            WallLayout.Rect rect, DisplayPanelBlockEntity anchor) {
        if (!anchor.settings().equals(DisplaySettings.DEFAULT)) {
            return anchor.settings();
        }
        for (int dv = 0; dv < rect.height(); dv++) {
            for (int du = 0; du < rect.width(); du++) {
                DisplaySettings settings = group.get(WallLayout.pack(rect.u() + du, rect.v() + dv)).settings();
                if (!settings.equals(DisplaySettings.DEFAULT)) {
                    return settings;
                }
            }
        }
        return DisplaySettings.DEFAULT;
    }

    /** A panel at {@code pos} facing {@code facing}, if its chunk is loaded; never loads a chunk. */
    static @Nullable DisplayPanelBlockEntity panel(Level level, BlockPos pos, Direction facing) {
        return level.isLoaded(pos) && level.getBlockEntity(pos) instanceof DisplayPanelBlockEntity panel
                && panel.facing() == facing && !panel.isRemoved() ? panel : null;
    }

    /** The panels a removed panel at {@code pos} touched in its plane re-form, on their next block tick. */
    static void removed(ServerLevel level, BlockPos pos, Direction facing) {
        for (Direction step : new Direction[]{right(facing), right(facing).getOpposite(), up(facing),
                up(facing).getOpposite()}) {
            BlockPos next = pos.relative(step);
            DisplayPanelBlockEntity panel = panel(level, next, facing);
            if (panel != null) {
                level.scheduleTick(next, panel.getBlockState().getBlock(), 1);
            }
        }
    }
}
