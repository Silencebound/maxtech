package dev.silencebound.maxtech.storage.crafting.provider;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** The pattern provider block's block entity: a {@link ProviderLogic} shared with the part form. Never ticks. */
public final class PatternProviderBlockEntity extends StorageDeviceBlockEntity implements ProviderDevice {
    /** How far beyond block reach the menu stays usable, as for vanilla containers. */
    static final double RANGE_BUFFER = 4.0;

    private final ProviderLogic logic = new ProviderLogic(this, this::getBlockPos, this::facing, this::setChanged);

    public PatternProviderBlockEntity(BlockPos pos, BlockState state) {
        super(ProviderContent.BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Role role() {
        return Role.TRANSFER;
    }

    /** Logic can turn it off (M7L): its work pauses until it is turned on again. */
    @Override
    public boolean switchable() {
        return true;
    }

    /** One operation a second at rest; outputs come in bursts when a batch finishes. */
    @Override
    public int operationsPerSecond() {
        return 1;
    }

    /** The side the machine is on. */
    public Direction facing() {
        return getBlockState().getValue(PatternProviderBlock.FACING);
    }

    /** Every side but the machine's links to the network. */
    @Override
    public boolean linksOn(Direction side) {
        return side != facing();
    }

    @Override
    public ProviderLogic logic() {
        return logic;
    }

    @Override
    public boolean stillValid(Player player) {
        return !isRemoved() && player.isWithinBlockInteractionRange(getBlockPos(), RANGE_BUFFER);
    }

    @Override
    public void addGlance(BlockInfoRequest request, BlockInfoSink sink) {
        ProviderDevice.glance(logic, sink);
    }

    @Override
    public void addDetails(BlockInfoRequest request, BlockInfoSink sink) {
        ProviderDevice.details(logic, sink);
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
        logic.write(components);
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        logic.read(components);
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
