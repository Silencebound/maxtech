package dev.silencebound.maxtech.lib.neoforge.gametest.perf;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.network.NetworkGraph;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.lib.schedule.LevelTasks;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;

/**
 * Turns network and task scheduler tick timing on for a perf batch, and in teardown turns it off and removes what the
 * tests built. The game test server neither clears finished tests nor stops their networks, so a perf scenario left
 * standing would keep delivering (and, once the batch's config is restored, overloading) while later batches run. The
 * runner tears an environment down however its batch ends, so this also covers failed and timed-out tests.
 */
record PerfEnvironment() implements TestEnvironmentDefinition<Unit> {
    // A new instance per decode: registries hold values by identity, so a client joining a dev world would refuse the
    // synced perf environments as one value added three times ("Network Protocol Error").
    static final MapCodec<PerfEnvironment> CODEC = MapCodec.unit(PerfEnvironment::new);

    private static final ObjectArrayList<Runnable> CLEANUPS = new ObjectArrayList<>();
    private static final RegistryHelper.Registrar<MapCodec<? extends TestEnvironmentDefinition<?>>> TYPES = Platform
            .get().registry().registrar(MaxtechLib.MOD_ID, Registries.TEST_ENVIRONMENT_DEFINITION_TYPE);

    static {
        TYPES.register("perf", () -> CODEC);
    }

    /** Loads the class during mod construction so the codec is registered in time. */
    static void init() {
    }

    /** Runs {@code cleanup} when the current batch ends. Server thread only. */
    static void onTeardown(Runnable cleanup) {
        CLEANUPS.add(cleanup);
    }

    @Override
    public Unit setup(ServerLevel level) {
        NetworkGraph.acquireProfiling();
        LevelTasks.acquireProfiling();
        return Unit.INSTANCE;
    }

    @Override
    public void teardown(ServerLevel level, Unit unused) {
        NetworkGraph.releaseProfiling();
        LevelTasks.releaseProfiling();
        for (int i = 0; i < CLEANUPS.size(); i++) {
            CLEANUPS.get(i).run();
        }
        CLEANUPS.clear();
    }

    @Override
    public MapCodec<PerfEnvironment> codec() {
        return CODEC;
    }
}
