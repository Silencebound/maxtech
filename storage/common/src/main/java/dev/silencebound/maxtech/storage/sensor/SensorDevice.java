package dev.silencebound.maxtech.storage.sensor;

import dev.silencebound.maxtech.storage.logic.controller.SensorSource;
import dev.silencebound.maxtech.storage.network.ChannelFree;
import net.minecraft.world.entity.player.Player;

/**
 * A sensor as its network, its screen and the logic layer see it, block or part. The Logic Controller finds sensors
 * among the devices of a label and reads them as a {@link SensorSource}: values by id ({@link SensorRef}) and a
 * listener run after every read. It holds no channel ({@link ChannelFree}). Server thread only.
 */
public interface SensorDevice extends SensorSource, ChannelFree {
    /** The sensor's reader, values and listeners. */
    SensorWorker sensor();

    @Override
    default boolean claimBlocked() {
        return sensor().claimBlocked();
    }

    /** Whether {@code player} may keep the screen open: the sensor is still there and in reach. */
    boolean stillValid(Player player);

    @Override
    default long logicValue(String name) {
        return sensor().value(name);
    }

    @Override
    default boolean logicValueFound(String name) {
        return sensor().found(name);
    }

    @Override
    default long logicTarget() {
        return sensor().targetPos();
    }

    @Override
    default void listenLogic(Runnable listener) {
        sensor().listenReads(listener);
    }

    @Override
    default void unlistenLogic(Runnable listener) {
        sensor().unlistenReads(listener);
    }
}
