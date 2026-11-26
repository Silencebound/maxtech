package dev.silencebound.maxtech.storage.smartlabel;

import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * The smart label's entity: a storage device that links only through its back, to the block it sits on, so a label on a
 * cable, a drive or any other device is in that network and a label on a plain chest or furnace is in none. Holds the
 * settings and the {@link LabelWorker}; clients get the settings with the block entity data and the values by payload
 * ({@link LabelUpdate}). Never ticks.
 */
public final class SmartLabelBlockEntity extends StorageDeviceBlockEntity {
    private static final String SETTINGS = "smart_label";

    private LabelSettings settings = LabelSettings.DEFAULT;
    private final LabelWorker worker = new LabelWorker(this);
    private final LabelView view = new LabelView();

    public SmartLabelBlockEntity(BlockPos pos, BlockState state) {
        super(SmartLabelContent.BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Role role() {
        return Role.OTHER;
    }

    /** The way the label's front faces; the block it sits on is behind it. */
    public Direction facing() {
        BlockState state = getBlockState();
        return state.hasProperty(SmartLabelBlock.FACING) ? state.getValue(SmartLabelBlock.FACING) : Direction.NORTH;
    }

    /** The block the label sits on. */
    public BlockPos targetPos() {
        return worldPosition.relative(facing().getOpposite());
    }

    /** Links only to the block it sits on. */
    @Override
    public boolean linksOn(Direction side) {
        return side == facing().getOpposite();
    }

    public LabelSettings settings() {
        return settings;
    }

    public LabelWorker worker() {
        return worker;
    }

    /** What the client knows of the label's values; client side. */
    public LabelView view() {
        return view;
    }

    /** Sets the settings, saves them and sends them to clients; server side. */
    public void setSettings(LabelSettings settings) {
        if (this.settings.equals(settings)) {
            return;
        }
        this.settings = settings;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
        worker.settingsChanged();
    }

    @Override
    public void networkChanged(@Nullable StorageNetwork network) {
        worker.networkChanged();
    }

    // PLATFORM-LEAK: NeoForge's IBlockEntityExtension hook, through the base class.
    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) {
            worker.start(server);
        }
    }

    @Override
    public void setRemoved() {
        worker.stop();
        super.setRemoved();
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (!settings.equals(LabelSettings.DEFAULT)) {
            components.set(SmartLabelContent.SETTINGS.get(), settings);
        }
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        LabelSettings read = components.get(SmartLabelContent.SETTINGS.get());
        settings = read == null ? LabelSettings.DEFAULT : read;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store(SETTINGS, LabelSettings.CODEC, settings);
    }

    @Override
    protected void loadSaved(ValueInput input) {
        super.loadSaved(input);
        settings = input.read(SETTINGS, LabelSettings.CODEC).orElse(LabelSettings.DEFAULT);
    }

    /** The settings only; the owner stays on the server. */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        LabelSettings.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), settings)
                .ifSuccess(encoded -> tag.put(SETTINGS, encoded));
        return tag;
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
