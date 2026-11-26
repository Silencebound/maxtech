package dev.silencebound.maxtech.storage.sensor;

/**
 * Told when a value a sensor reads changed; see {@link SensorWorker#watch}. Called on the server thread right after the
 * read, at most once per watched value and read, and only when the value differs from the one last told (or, for a
 * watch added before the sensor's first read, once that read gives a value). A listener only wakes its own work; it
 * must not read other sensors or change the world from inside the call.
 */
@FunctionalInterface
public interface SensorListener {
    /** The value {@code id} of {@code sensor} is now {@code value}. */
    void sensorChanged(SensorWorker sensor, String id, long value);
}
