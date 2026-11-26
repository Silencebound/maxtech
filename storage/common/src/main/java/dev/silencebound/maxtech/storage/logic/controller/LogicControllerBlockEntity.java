package dev.silencebound.maxtech.storage.logic.controller;

import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import com.mojang.serialization.Codec;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * The Logic Controller's block entity: a network device holding a {@link LogicWorker}. Its graphs with their memory and
 * its shared variables are saved here and travel in the dropped item ({@code maxtech_storage:logic_graphs},
 * {@code maxtech_storage:logic_variables}). Never ticks.
 */
public final class LogicControllerBlockEntity extends StorageDeviceBlockEntity
        implements
            LogicWorker.Host,
            PinHistory.Host {
    static final double RANGE_BUFFER = 4.0;
    private static final String GRAPHS = "graphs";
    /** Switches a pulse holds on: block position and strength pairs ({@link LogicWorker#pulsedSwitches}). */
    private static final String PULSED = "pulsed";
    /** The controller's shares of the network redstone channels (M7L2), 16 values, when any is above 0. */
    private static final String CHANNELS = "channels";
    /** The last values of watches on other networks (M7L2 row 13), for a network not loaded after a restart. */
    private static final String REMOTE_VALUES = "remote_values";
    private static final Codec<Map<String, Long>> REMOTE_VALUES_CODEC = Codec.unboundedMap(Codec.string(1, 512),
            Codec.LONG);
    /** The graphs' shared variables ({@link LogicWorker#variables}). */
    private static final String VARIABLES = "variables";
    /** The pinned values' recorded history ({@link PinHistory}); not in the item. */
    private static final String PIN_HISTORY = "pin_history";

    private final LogicWorker worker = new LogicWorker(this, this);
    /** Who edits which graph in the node editor; not saved. */
    private final LogicEditing.Locks editors = new LogicEditing.Locks();
    private final PinHistory pinHistory = new PinHistory(this);
    /** The worker revision the pin history was last woken for. */
    private long pinRevision = -1;

    public LogicControllerBlockEntity(BlockPos pos, BlockState state) {
        super(LogicContent.BLOCK_ENTITY.get(), pos, state);
    }

    /** The node editor's one-editor-per-graph lock. */
    public LogicEditing.Locks editors() {
        return editors;
    }

    @Override
    public Role role() {
        return Role.OTHER;
    }

    @Override
    public LogicWorker worker() {
        return worker;
    }

    /** The recorded history of the pinned values, for the screen's sparklines. */
    public PinHistory pinHistory() {
        return pinHistory;
    }

    @Override
    public void historyChanged() {
        // Only the chunk needs saving; setChanged() would also update the neighbours' comparators every sample.
        if (level != null) {
            level.blockEntityChanged(worldPosition);
        }
    }

    /** Whether {@code player} may keep the screen open: the controller is still there and in reach. */
    public boolean stillValid(Player player) {
        return !isRemoved() && player.isWithinBlockInteractionRange(getBlockPos(), RANGE_BUFFER);
    }

    @Override
    public @Nullable ServerLevel serverLevel() {
        return level instanceof ServerLevel server ? server : null;
    }

    @Override
    public int redstoneIn() {
        return level == null || isRemoved() ? 0 : level.getBestNeighborSignal(worldPosition);
    }

    /**
     * Also wakes the pin history when the rules changed (a save, removal, switch or new pins), so a new pinned value is
     * recorded at once rather than at the next sample; a variable change only saves.
     */
    @Override
    public void changed() {
        setChanged();
        long revision = worker.revision();
        if (revision != pinRevision) {
            pinRevision = revision;
            wake(pinHistory);
        }
    }

    /** A new owner: the controller's redstone channel shares count as theirs from now on (M7L2). */
    @Override
    public void setOwner(OwnerRecord owner) {
        super.setOwner(owner);
        worker.ownerChanged();
    }

    @Override
    public void networkChanged(@Nullable StorageNetwork network) {
        worker.networkChanged(network);
    }

    // PLATFORM-LEAK: NeoForge's IBlockEntityExtension hook, through the base class.
    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel) {
            worker.start();
            schedule(pinHistory);
        }
    }

    @Override
    public void addGlance(BlockInfoRequest request, BlockInfoSink sink) {
        ControllerTexts.glance(worker, sink);
    }

    @Override
    public void addDetails(BlockInfoRequest request, BlockInfoSink sink) {
        ControllerTexts.info(worker, level == null ? 0 : level.getGameTime(), sink);
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (worker.size() > 0) {
            components.set(ModDataComponents.LOGIC_GRAPHS.get(), worker.slots());
        }
        List<SharedVariable> variables = worker.variables();
        if (!variables.isEmpty()) {
            components.set(ModDataComponents.LOGIC_VARIABLES.get(), variables);
        }
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        List<GraphSlot> graphs = components.get(ModDataComponents.LOGIC_GRAPHS.get());
        if (graphs != null) {
            worker.load(graphs);
        }
        List<SharedVariable> variables = components.get(ModDataComponents.LOGIC_VARIABLES.get());
        if (variables != null) {
            worker.loadVariables(variables);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (worker.size() > 0) {
            output.store(GRAPHS, GraphSlot.LIST_CODEC, worker.slots());
        }
        List<Long> pulsed = worker.pulsedSwitches();
        if (!pulsed.isEmpty()) {
            output.store(PULSED, Codec.LONG.listOf(), pulsed);
        }
        List<SharedVariable> variables = worker.variables();
        if (!variables.isEmpty()) {
            output.store(VARIABLES, SharedVariable.LIST_CODEC, variables);
        }
        int[] channels = worker.channelShares();
        for (int share : channels) {
            if (share > 0) {
                output.putIntArray(CHANNELS, channels);
                break;
            }
        }
        Map<String, Long> remoteValues = worker.remoteValues();
        if (!remoteValues.isEmpty()) {
            output.store(REMOTE_VALUES, REMOTE_VALUES_CODEC, remoteValues);
        }
        List<PinHistory.Series> history = pinHistory.saved();
        if (!history.isEmpty()) {
            output.store(PIN_HISTORY, PinHistory.LIST_CODEC, history);
        }
    }

    @Override
    protected void loadSaved(ValueInput input) {
        super.loadSaved(input);
        List<GraphSlot> graphs = input.read(GRAPHS, GraphSlot.LIST_CODEC).orElse(List.of());
        if (!graphs.isEmpty() || worker.size() > 0) {
            worker.load(graphs);
        }
        worker.loadPulsedSwitches(input.read(PULSED, Codec.LONG.listOf()).orElse(List.of()));
        worker.loadVariables(input.read(VARIABLES, SharedVariable.LIST_CODEC).orElse(List.of()));
        worker.loadChannelShares(input.getIntArray(CHANNELS).orElse(new int[0]));
        worker.loadRemoteValues(input.read(REMOTE_VALUES, REMOTE_VALUES_CODEC).orElse(Map.of()));
        pinHistory.load(input.read(PIN_HISTORY, PinHistory.LIST_CODEC).orElse(List.of()));
    }
}
