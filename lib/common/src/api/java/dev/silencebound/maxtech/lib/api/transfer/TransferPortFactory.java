package dev.silencebound.maxtech.lib.api.transfer;

import dev.silencebound.maxtech.lib.api.resource.ResourceKind;
import dev.silencebound.maxtech.lib.api.schedule.TaskGroup;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * Where {@link TransferPorts.Builder#build} gets Lib's port implementation. Lib installs its factory once during its
 * construction; a second install throws {@link IllegalStateException}, and so does building a port before the first.
 */
@ApiStatus.Internal
public final class TransferPortFactory {
    private static volatile @Nullable Factory factory;

    private TransferPortFactory() {
    }

    /** Sets Lib's factory; only the first call counts, a second throws {@link IllegalStateException}. */
    @ApiStatus.Internal
    public static synchronized void install(Factory source) {
        if (factory != null) {
            throw new IllegalStateException("A transfer port factory is already installed");
        }
        factory = source;
    }

    static Factory get() {
        Factory source = factory;
        if (source == null) {
            throw new IllegalStateException("MaxTech Lib has not set up transfer ports yet");
        }
        return source;
    }

    /** Lib's side of the port api. */
    @ApiStatus.Internal
    public interface Factory {
        /** A port over what a checked builder collected; see {@link TransferPorts.Builder}. */
        TransferPort create(Spec spec);
    }

    /**
     * A checked builder's contents.
     *
     * @param tasks
     *            where the port gets its group on start; null for Lib's transfer group
     * @param buffers
     *            the buffers in the order they were added
     * @param rates
     *            the owner's cap per operation and kind
     * @param defaults
     *            the settings a new port starts with
     * @param onSettingsChanged
     *            the settings listener
     */
    @ApiStatus.Internal
    public record Spec(@Nullable Function<ServerLevel, TaskGroup> tasks, List<Buffer> buffers,
            Map<ResourceKind<?>, Long> rates, PortSettings defaults, Runnable onSettingsChanged) {
        /** Copies the lists, which the builder keeps changing. */
        public Spec {
            buffers = List.copyOf(buffers);
            rates = Map.copyOf(rates);
        }
    }

    /**
     * One buffer of a port.
     *
     * @param kind
     *            its resource kind
     * @param buffer
     *            the buffer as the builder took it
     * @param modes
     *            the modes it takes part in, without {@code OFF}
     */
    @ApiStatus.Internal
    public record Buffer(ResourceKind<?> kind, Object buffer, Set<PortFaceMode> modes) {
    }
}
