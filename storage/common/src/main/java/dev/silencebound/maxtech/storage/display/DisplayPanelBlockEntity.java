package dev.silencebound.maxtech.storage.display;

import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.network.PowerPriority;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * One display panel: its own settings (kept in its item), the wall screen it belongs to (the anchor's offset from this
 * panel and, on the anchor, the wall's size in panels) and, on the anchor only, the screen's {@link ScreenWorker}.
 * Clients get settings and wall only, when they change. Never ticks.
 */
public final class DisplayPanelBlockEntity extends StorageDeviceBlockEntity
        implements
            ScreenWorker.Host,
            DisplayDevice {
    private static final String SETTINGS = "display";
    private static final String ANCHOR = "anchor";
    private static final String WIDTH = "width";
    private static final String HEIGHT = "height";
    /** Extra reach for an open GUI, as vanilla containers allow. */
    static final double REACH_BUFFER = 4.0;

    private DisplaySettings settings = DisplaySettings.DEFAULT;
    /** The anchor's position minus this panel's; zero on the anchor. */
    private BlockPos anchorOffset = BlockPos.ZERO;
    private int width = 1;
    private int height = 1;
    private final ScreenWorker worker = new ScreenWorker(this);
    private @Nullable DisplayView view;

    public DisplayPanelBlockEntity(BlockPos pos, BlockState state) {
        super(DisplayContent.PANEL_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Role role() {
        return Role.ACCESS;
    }

    /** Load shedding pauses displays first (M7L2): their screens stop updating until the power is back. */
    @Override
    public boolean sheddable() {
        return true;
    }

    @Override
    public int defaultPowerPriority() {
        return PowerPriority.OPTIONAL;
    }

    /** Links on every side but its screen. */
    @Override
    public boolean linksOn(Direction side) {
        return side != facing();
    }

    public Direction facing() {
        return getBlockState().getValue(DisplayPanelBlock.FACING);
    }

    @Override
    public DisplaySettings settings() {
        return settings;
    }

    @Override
    public BlockPos pos() {
        return worldPosition;
    }

    @Override
    public int side() {
        return -1;
    }

    /** Whether this panel shows its wall (its bottom-left panel). */
    public boolean isAnchor() {
        return anchorOffset.equals(BlockPos.ZERO);
    }

    /** The wall's anchor panel's position. */
    public BlockPos anchor() {
        return worldPosition.offset(anchorOffset);
    }

    /** The wall's width in panels (along {@link DisplayWalls#right}); 1 on panels other than the anchor. */
    public int wallWidth() {
        return width;
    }

    /** The wall's height in panels (along {@link DisplayWalls#up}); 1 on panels other than the anchor. */
    public int wallHeight() {
        return height;
    }

    /** The anchor's worker, for diagnostics and tests; idle on other panels. */
    public ScreenWorker worker() {
        return worker;
    }

    /** What the client knows of this screen; client side, on the anchor. */
    public DisplayView view() {
        DisplayView current = view;
        if (current == null) {
            current = new DisplayView();
            view = current;
        }
        return current;
    }

    /**
     * {@code player} right-clicked this panel without sneaking: when the wall shows a list ({@link ScreenRows}) and the
     * player looks at one of its rows within reach, the anchor's feed handles the click. Returns whether it did. Server
     * side.
     */
    public boolean clickRow(ServerPlayer player) {
        if (!settings.source().rows() || level == null || !player.isWithinBlockInteractionRange(worldPosition, 1.0)
                || !(level.getBlockEntity(anchor()) instanceof DisplayPanelBlockEntity first)) {
            return false;
        }
        int row = ScreenGeometry.wall(first.worldPosition, first.facing(), first.width, first.height).rowAt(player);
        return row >= 0 && first.worker.clickRow(player, row);
    }

    /** Sets this panel's own settings (server); {@link #applyToWall} sets a whole wall's. */
    void setSettings(DisplaySettings settings) {
        if (this.settings.equals(settings)) {
            return;
        }
        this.settings = settings;
        changed();
        worker.settingsChanged();
    }

    @Override
    public void applySettings(DisplaySettings settings) {
        applyToWall(settings);
    }

    @Override
    public boolean stillValid(Player player) {
        return !isRemoved() && player.isWithinBlockInteractionRange(worldPosition, REACH_BUFFER);
    }

    /** Gives every panel of this panel's wall {@code settings} (the settings GUI); server side. */
    public void applyToWall(DisplaySettings settings) {
        if (level == null) {
            return;
        }
        BlockPos anchor = anchor();
        if (!(level.getBlockEntity(anchor) instanceof DisplayPanelBlockEntity first)) {
            setSettings(settings);
            return;
        }
        Direction right = DisplayWalls.right(facing());
        Direction up = DisplayWalls.up(facing());
        for (int dv = 0; dv < first.height; dv++) {
            for (int du = 0; du < first.width; du++) {
                BlockPos pos = anchor.relative(right, du).relative(up, dv);
                DisplayPanelBlockEntity panel = DisplayWalls.panel(level, pos, facing());
                if (panel != null) {
                    panel.setSettings(settings);
                }
            }
        }
    }

    /**
     * Makes this panel part of the wall anchored at {@code anchor}, {@code width} x {@code height} panels, with the
     * frame on the edges in {@code frame} ({@link WallLayout#frame}). The frame lives in the block state, so the model
     * draws it and clients get it with the chunk; setting it notifies clients only, never neighbours.
     */
    void setWall(BlockPos anchor, int width, int height, int frame) {
        BlockState state = getBlockState();
        if (level != null && DisplayPanelBlock.frame(state) != frame) {
            level.setBlock(worldPosition, DisplayPanelBlock.withFrame(state, frame), Block.UPDATE_CLIENTS);
        }
        BlockPos offset = anchor.subtract(worldPosition);
        boolean anchored = offset.equals(BlockPos.ZERO);
        int w = anchored ? width : 1;
        int h = anchored ? height : 1;
        if (offset.equals(anchorOffset) && w == this.width && h == this.height) {
            if (anchored && level instanceof ServerLevel serverLevel) {
                worker.start(serverLevel);
            }
            return;
        }
        anchorOffset = offset;
        this.width = w;
        this.height = h;
        changed();
        if (level instanceof ServerLevel serverLevel) {
            if (anchored) {
                worker.start(serverLevel);
                worker.settingsChanged();
            } else {
                worker.stop();
                worker.chart().clear();
            }
        }
    }

    private void changed() {
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    // PLATFORM-LEAK: NeoForge's IBlockEntityExtension hook, through the base class.
    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel && isAnchor()) {
            worker.start(serverLevel);
        }
    }

    @Override
    public void setRemoved() {
        worker.stop();
        super.setRemoved();
    }

    @Override
    public void networkChanged(@Nullable StorageNetwork network) {
        worker.networkChanged(network);
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (!settings.equals(DisplaySettings.DEFAULT)) {
            components.set(ModDataComponents.DISPLAY_SETTINGS.get(), settings);
        }
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        DisplaySettings read = DisplayItems.read(components);
        settings = read == null ? DisplaySettings.DEFAULT : read;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store(SETTINGS, DisplaySettings.CODEC, settings);
        output.store(ANCHOR, BlockPos.CODEC, anchorOffset);
        output.putInt(WIDTH, width);
        output.putInt(HEIGHT, height);
        if (isAnchor()) {
            worker.chart().save(output);
        }
    }

    @Override
    protected void loadSaved(ValueInput input) {
        super.loadSaved(input);
        settings = input.read(SETTINGS, DisplaySettings.CODEC).orElse(DisplaySettings.DEFAULT);
        anchorOffset = input.read(ANCHOR, BlockPos.CODEC).orElse(BlockPos.ZERO);
        width = Math.max(1, input.getIntOr(WIDTH, 1));
        height = Math.max(1, input.getIntOr(HEIGHT, 1));
        worker.chart().load(input);
    }

    /**
     * The wall's recorded chart changed; it is saved with the anchor, and clients get it by payload only. Only marks
     * the chunk unsaved: {@code setChanged()} would also update the neighbours' comparators on every sample.
     */
    @Override
    public void recordChanged() {
        if (level != null) {
            level.blockEntityChanged(worldPosition);
        }
    }

    /** Settings and wall only; the owner stays on the server. */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        DisplaySettings.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), settings)
                .ifSuccess(encoded -> tag.put(SETTINGS, encoded));
        BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, anchorOffset).ifSuccess(encoded -> tag.put(ANCHOR, encoded));
        tag.putInt(WIDTH, width);
        tag.putInt(HEIGHT, height);
        return tag;
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
