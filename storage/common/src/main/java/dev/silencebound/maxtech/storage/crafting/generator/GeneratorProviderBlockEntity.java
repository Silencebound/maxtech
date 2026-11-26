package dev.silencebound.maxtech.storage.crafting.generator;

import dev.silencebound.maxtech.storage.api.crafting.PatternProvider;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternHost;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import dev.silencebound.maxtech.storage.priority.Prioritized;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** The generator provider's block entity: a {@link GeneratorLogic} and the signal it gives. Never ticks. */
public final class GeneratorProviderBlockEntity extends StorageDeviceBlockEntity implements PatternHost, Prioritized {
    /** How far beyond block reach the menu stays usable, as for vanilla containers. */
    static final double RANGE_BUFFER = 4.0;

    private final GeneratorLogic logic = new GeneratorLogic(this, this::getBlockPos, this::facing, this::setChanged,
            new GeneratorLogic.Signal() {
                @Override
                public void apply(boolean on) {
                    applySignal(on);
                }

                @Override
                public void refresh() {
                    if (level != null && !isRemoved()) {
                        level.updateNeighborsAt(worldPosition, getBlockState().getBlock());
                    }
                }
            });

    public GeneratorProviderBlockEntity(BlockPos pos, BlockState state) {
        super(GeneratorContent.BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Role role() {
        return Role.TRANSFER;
    }

    /** Logic can turn it off (M7L): it takes no new orders until it is on again. */
    @Override
    public boolean switchable() {
        return true;
    }

    @Override
    public int operationsPerSecond() {
        return 1;
    }

    /** The side the machine is on. */
    public Direction facing() {
        return getBlockState().getValue(GeneratorProviderBlock.FACING);
    }

    /** Every side but the machine's links to the network. */
    @Override
    public boolean linksOn(Direction side) {
        return side != facing();
    }

    public GeneratorLogic logic() {
        return logic;
    }

    /** Where the planner tries it among patterns for the same output; read live by the next plan. */
    @Override
    public int priority() {
        return logic.settings().priority();
    }

    @Override
    public void setPriority(int priority) {
        logic.setSettings(logic.settings().withPriority(priority));
    }

    @Override
    public PatternProvider patternProvider() {
        return logic;
    }

    @Override
    public boolean claimBlocked() {
        return logic.claimBlocked();
    }

    public boolean stillValid(Player player) {
        return !isRemoved() && player.isWithinBlockInteractionRange(getBlockPos(), RANGE_BUFFER);
    }

    /** Sets {@link GeneratorProviderBlock#POWERED} when it differs; the block update tells the neighbours. */
    private void applySignal(boolean on) {
        BlockState state = getBlockState();
        if (level == null || isRemoved() || !state.hasProperty(GeneratorProviderBlock.POWERED)) {
            return;
        }
        if (state.getValue(GeneratorProviderBlock.POWERED) != on) {
            level.setBlock(worldPosition, state.setValue(GeneratorProviderBlock.POWERED, on), Block.UPDATE_ALL);
        }
    }

    /**
     * Turned off by logic or paused by load shedding: the signal goes off too, and comes back with the next pull once
     * the device runs again.
     */
    @Override
    public void stoppedChanged(boolean stopped) {
        logic.stoppedChanged(stopped);
    }

    /** Broken or unloaded: job ends no longer reach it; the signal goes with the block. */
    @Override
    public void setRemoved() {
        logic.stop();
        super.setRemoved();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) {
            logic.start(server);
        }
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (!logic.settings().equals(GeneratorSettings.DEFAULT)) {
            components.set(GeneratorContent.settingsType(), logic.settings());
        }
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        logic.read(components.getOrDefault(GeneratorContent.settingsType(), GeneratorSettings.DEFAULT));
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        logic.save(output);
    }

    @Override
    protected void loadSaved(ValueInput input) {
        super.loadSaved(input);
        logic.load(input);
    }
}
