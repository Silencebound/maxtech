package dev.silencebound.maxtech.lib.api.machine;

import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import dev.silencebound.maxtech.lib.api.inventory.ResourceTank;
import dev.silencebound.maxtech.lib.api.recipe.MachineRecipeType;
import dev.silencebound.maxtech.lib.api.schedule.TaskGroup;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * Where {@link MachineProcess.Builder#build} gets Lib's process implementation. Lib installs its factory once during
 * its construction; a second install throws {@link IllegalStateException}, and so does building a process before the
 * first.
 */
@ApiStatus.Internal
public final class MachineProcessFactory {
    private static volatile @Nullable Factory factory;

    private MachineProcessFactory() {
    }

    /** Sets Lib's factory; only the first call counts, a second throws {@link IllegalStateException}. */
    @ApiStatus.Internal
    public static synchronized void install(Factory source) {
        if (factory != null) {
            throw new IllegalStateException("A machine process factory is already installed");
        }
        factory = source;
    }

    static Factory get() {
        Factory source = factory;
        if (source == null) {
            throw new IllegalStateException("MaxTech Lib has not set up machine processes yet");
        }
        return source;
    }

    /** Lib's side of the process api. */
    @ApiStatus.Internal
    @FunctionalInterface
    public interface Factory {
        /** A process over what a checked builder collected; see {@link MachineProcess.Builder}. */
        MachineProcess create(Spec spec);
    }

    /**
     * A checked builder's contents.
     *
     * @param type
     *            the recipe type
     * @param slots
     *            the slots, or null for a shape without slots
     * @param firstInput
     *            the first input slot; catalysts follow the inputs
     * @param firstOutput
     *            the first output slot
     * @param inputTanks
     *            the input tanks in recipe order
     * @param outputTanks
     *            the output tanks
     * @param energy
     *            the consumer endpoint, or null to ignore recipe energy
     * @param redstone
     *            the redstone control, or null to always run
     * @param tasks
     *            where the process gets its group on start; null for the level's machine group
     * @param onStatus
     *            the status listener
     * @param onChange
     *            the save listener
     */
    @ApiStatus.Internal
    public record Spec(MachineRecipeType type, @Nullable ItemSlots slots, int firstInput, int firstOutput,
            List<ResourceTank<?>> inputTanks, List<ResourceTank<?>> outputTanks, @Nullable EnergyEndpoint energy,
            @Nullable RedstoneControl redstone, @Nullable Function<ServerLevel, TaskGroup> tasks,
            Consumer<MachineStatus> onStatus, Runnable onChange) {
    }
}
