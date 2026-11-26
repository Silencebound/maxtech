package dev.silencebound.maxtech.storage.display.alarm;

import dev.silencebound.maxtech.storage.controller.alarm.Alarm;
import dev.silencebound.maxtech.storage.controller.alarm.AlarmCenter;
import dev.silencebound.maxtech.storage.controller.alarm.AlarmTexts;
import dev.silencebound.maxtech.storage.display.RowFeed;
import dev.silencebound.maxtech.storage.display.ScreenRows;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * An alarm panel screen's feed (M7C item 16): the alarms of its network's controller, fresh ones first, and a
 * right-click on a fresh alarm's row acknowledges it. The feed listens to the controller's {@link AlarmCenter}, which
 * tells it after every change, and to the network's devices, since the controller can come or go without the screen
 * moving. Nothing polls. Server thread.
 */
public final class AlarmFeed implements RowFeed {
    private final RowFeed.Screen screen;
    private final Runnable changed = this::changed;
    private final Runnable devices = this::devicesChanged;
    private @Nullable NetworkState bound;
    private @Nullable AlarmCenter center;

    public AlarmFeed(RowFeed.Screen screen) {
        this.screen = screen;
    }

    /** The alarm panel the feed shows; null without exactly one controller. For tests. */
    public @Nullable AlarmCenter center() {
        return center;
    }

    @Override
    public void bind(@Nullable NetworkState network) {
        unbind();
        bound = network;
        if (network == null) {
            return;
        }
        network.watchDevices(devices);
        follow(network);
        screen.rowsChanged();
    }

    @Override
    public void unbind() {
        NetworkState network = bound;
        if (network != null) {
            network.unwatchDevices(devices);
        }
        AlarmCenter current = center;
        if (current != null) {
            current.unlisten(changed);
        }
        bound = null;
        center = null;
    }

    /** The network's alarm panel now, listening to it. */
    @SuppressWarnings("ReferenceEquality") // a center is one object, the controller's
    private @Nullable AlarmCenter follow(NetworkState network) {
        AlarmCenter found = AlarmCenter.of(network);
        AlarmCenter current = center;
        if (found == current) {
            return current;
        }
        if (current != null) {
            current.unlisten(changed);
        }
        center = found;
        if (found != null) {
            found.listen(changed);
        }
        return found;
    }

    private void changed() {
        screen.rowsChanged();
    }

    private void devicesChanged() {
        NetworkState network = bound;
        if (network != null) {
            AlarmCenter before = center;
            if (follow(network) != before) {
                screen.rowsChanged();
            }
        }
    }

    @Override
    public ScreenRows rows(NetworkState network, long gameTime) {
        Component title = Component.translatable(AlarmTexts.TITLE);
        AlarmCenter current = follow(network);
        if (current == null) {
            return new ScreenRows(title, Component.empty(), ScreenRows.NO_FILL, List.of(ScreenRows.Row
                    .of(ScreenRows.BAD, Component.translatable(AlarmTexts.NO_CONTROLLER), Component.empty())));
        }
        List<Alarm> alarms = current.shown();
        Component status = Component.translatable(AlarmTexts.STATUS, current.freshCount(), current.activeCount());
        if (alarms.isEmpty()) {
            return new ScreenRows(title, status, ScreenRows.NO_FILL, List.of(ScreenRows.Row.of(ScreenRows.GOOD,
                    Component.translatable(AlarmTexts.ALL_CLEAR), Component.empty())));
        }
        int count = Math.min(alarms.size(), ScreenRows.MAX_ROWS);
        List<ScreenRows.Row> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(row(alarms.get(i)));
        }
        return new ScreenRows(title, status, ScreenRows.NO_FILL, rows);
    }

    /**
     * One alarm's row: a fresh one in red on a band with its kind and when it was raised; an acknowledged one that
     * still holds in amber with who saw it and when; a cleared one dimmed with when it cleared.
     */
    private static ScreenRows.Row row(Alarm alarm) {
        Component kind = Component.translatable(alarm.alarmKind().translationKey());
        if (alarm.fresh()) {
            return ScreenRows.Row.of(ScreenRows.BAD | ScreenRows.BAND, alarm.text(), kind).withTime(alarm.raised());
        }
        if (alarm.active()) {
            return ScreenRows.Row
                    .of(ScreenRows.WARN, alarm.text(), Component.translatable(AlarmTexts.ACK_BY, alarm.ackBy()))
                    .withTime(alarm.ackAt());
        }
        return ScreenRows.Row
                .of(ScreenRows.DIM | ScreenRows.STRIKE, alarm.text(), Component.translatable(AlarmTexts.CLEARED))
                .withTime(alarm.cleared());
    }

    /**
     * Shown row {@code row} is clicked: a fresh alarm is acknowledged by a player who may put items into the network
     * ({@link AccessLevel#DEPOSIT}). A click on any other alarm's row is taken and changes nothing, so a missed aim
     * does not open the settings.
     */
    @Override
    public boolean click(ServerPlayer player, NetworkState network, int row) {
        AlarmCenter current = follow(network);
        if (current == null || !network.accessLevel(player).atLeast(AccessLevel.DEPOSIT)) {
            return false;
        }
        List<Alarm> alarms = current.shown();
        if (row >= alarms.size()) {
            return false;
        }
        Alarm alarm = alarms.get(row);
        if (alarm.fresh()) {
            current.acknowledge(alarm.key(), player.getGameProfile().name(), network.level().getGameTime());
        }
        return true;
    }
}
