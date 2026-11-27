package dev.silencebound.maxtech.farms.lab;

import dev.silencebound.maxtech.farms.registry.FarmsContent;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The lab fluid port: where other mods' pipes and pumps push nutrient into a lab. It holds nothing and never ticks or
 * pulls; its fluid capability ({@link #fluidHandler()}) passes what is pushed in straight to the lab hub's tank of the
 * lab network beside it. Server thread only.
 */
public final class FluidPortBlockEntity extends BlockEntity {
    private final PortFluidHandler handler = new PortFluidHandler(this);
    private @Nullable LabNetwork lab;

    public FluidPortBlockEntity(BlockPos pos, BlockState state) {
        super(FarmsContent.FLUID_PORT_ENTITY.get(), pos, state);
    }

    /** The fluid capability on every side: insert only, into the lab hub's tank. */
    public PortFluidHandler fluidHandler() {
        return handler;
    }

    /** The lab network the port feeds, or empty while no lab tube is beside it. */
    public Optional<LabNetwork> lab() {
        LabNetwork current = lab;
        return current == null || !current.isAlive() ? Optional.empty() : Optional.of(current);
    }

    /** The lab the port feeds, for the handler; null when there is none. */
    @Nullable
    LabNetwork labOrNull() {
        LabNetwork current = lab;
        return current == null || !current.isAlive() ? null : current;
    }

    /** {@code network} found the port beside its tubes ({@code joined}) or no longer does. */
    void setLab(LabNetwork network, boolean joined) {
        if (joined) {
            lab = network;
        } else if (lab == network) {
            lab = null;
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        lab = null;
    }
}
