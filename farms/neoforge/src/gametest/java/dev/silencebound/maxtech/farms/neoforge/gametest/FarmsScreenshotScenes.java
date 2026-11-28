package dev.silencebound.maxtech.farms.neoforge.gametest;

import static dev.silencebound.maxtech.screenshots.ScreenshotScene.FLOOR;

import dev.silencebound.maxtech.farms.MaxtechFarms;
import dev.silencebound.maxtech.farms.config.FarmsServerConfig;
import dev.silencebound.maxtech.farms.hub.LabHubBlockEntity;
import dev.silencebound.maxtech.farms.lab.LabTubes;
import dev.silencebound.maxtech.farms.registry.FarmsContent;
import dev.silencebound.maxtech.lib.api.conduit.Conduits;
import dev.silencebound.maxtech.screenshots.ScreenshotHarness;
import dev.silencebound.maxtech.screenshots.ScreenshotScene;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/** Farms' scenes for {@code ./gradlew :farms:neoforge:runScreenshots -Pscene=<name>} (the client-check skill). */
@EventBusSubscriber(modid = MaxtechFarms.MOD_ID, value = Dist.CLIENT)
public final class FarmsScreenshotScenes {
    private static final BlockPos HUB = new BlockPos(0, FLOOR, 0);
    /**
     * A run east of the hub along z 0 (x 1 to 6) with a T at x 3 whose branch goes south and bends west (3,1 3,2 2,2
     * 1,2), and a cross at x 5 (arms at z -1 and 1).
     */
    private static final int[][] TUBES = {{1, 0}, {2, 0}, {3, 0}, {4, 0}, {5, 0}, {6, 0}, {3, 1}, {3, 2}, {2, 2},
            {1, 2}, {5, -1}, {5, 1}};

    /** Lab tubes holding the hub's water, with a T, a cross and a bend. */
    static final ScreenshotScene LABTUBES = ScreenshotScene.of("labtubes", FarmsScreenshotScenes::labTubes)
            .shot("overview", 3.5, FLOOR + 4.5, 5, 180, 45).shot("side", 3.5, FLOOR + 1.2, -3.5, 0, 10)
            .shot("t_closeup", 3.5, FLOOR + 0.8, -1.3, 0, 15).shot("cross_from_above", 5.5, FLOOR + 1.8, 0.5, 0, 90);

    private FarmsScreenshotScenes() {
    }

    @SubscribeEvent
    static void setup(FMLClientSetupEvent event) {
        ScreenshotHarness.install(List.of(LABTUBES));
    }

    private static void labTubes(ServerLevel level) {
        level.setBlockAndUpdate(HUB, FarmsContent.LAB_HUB.get().defaultBlockState());
        for (int[] at : TUBES) {
            Conduits.place(level, HUB.offset(at[0], 0, at[1]), LabTubes.TUBE.type().get());
        }
        if (level.getBlockEntity(HUB) instanceof LabHubBlockEntity hub) {
            try (Transaction transaction = Transaction.openRoot()) {
                hub.storage().fluidHandler().insert(FluidResource.of(Fluids.WATER),
                        FarmsServerConfig.hubTankCapacity() / 2, transaction);
                transaction.commit();
            }
        }
    }
}
