package dev.silencebound.maxtech.storage.energy;

import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.network.EnergyCells;
import dev.silencebound.maxtech.storage.network.NetworkEnergyCell;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
// PLATFORM-LEAK: Storage's MU stores are NeoForge transfer handlers inside transactions; FE views wrap them (MLE).
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * An energy cell's block entity: an MU store of its tier's capacity that joins its network's energy pool while it is a
 * member ({@link NetworkEnergyCell}), and the network's MaxTech energy network as a buffer at leftovers priority, so
 * generators fill it with what the controller does not take. Neighbours charge and discharge it through its FE
 * capability within the tier's transfer limit on every side, converted at Lib's FE rate and under its export settings;
 * operations drain it when the main buffer is short. Other mods' FE blocks next to it join the energy network through
 * edges ({@link CellEdges}). The energy is saved with the block and kept in its item (Lib's
 * {@code maxtech_lib:stored_energy}, in MU since MLE). Never ticks.
 */
public final class EnergyCellBlockEntity extends StorageDeviceBlockEntity implements NetworkEnergyCell {
    private static final String ENERGY = "energy";

    private final EnergyCellTier tier;
    private final EnergyCells.Store store;
    private final EnergyHandler input = new ExternalInput();
    private final CellEdges edges = new CellEdges(this);

    public EnergyCellBlockEntity(BlockPos pos, BlockState state) {
        super(EnergyContent.BLOCK_ENTITY.get(), pos, state);
        tier = state.getBlock() instanceof EnergyCellBlock block ? block.tier() : EnergyCellTier.TIER_1;
        store = new EnergyCells.Store(tier.capacity(), tier.maxTransfer(), tier.maxTransfer(), this::energyChanged);
        store.setTicker(new EnergyCells.Store.Ticker() {
            @Override
            public void flowTick() {
                Level current = level;
                if (current != null) {
                    current.blockEntityChanged(worldPosition);
                }
                updateCharge();
            }

            @Override
            public boolean edgesActive() {
                return edges.active();
            }
        });
    }

    public EnergyCellTier tier() {
        return tier;
    }

    @Override
    public Role role() {
        return Role.OTHER;
    }

    @Override
    public EnergyCells.Store cellStore() {
        return store;
    }

    /** The cell's own store, both ways within the tier's transfer limit, rather than its network's input. */
    @Override
    public EnergyHandler energyInput() {
        return input;
    }

    @Override
    public EnergyEndpoint energyEndpoint() {
        return store.endpoint();
    }

    /** Other mods may also draw from a cell, under Lib's export settings, counted against its energy network. */
    @Override
    protected FeView createFeView(EnergyHandler input) {
        return FeView.of(input, true, true, true, store::endpoint, this::gameTime, this::setChanged);
    }

    /** The edges to other mods' FE blocks around the cell; for tests. */
    public CellEdges edges() {
        return edges;
    }

    @Override
    public void networkChanged(@Nullable StorageNetwork network) {
        super.networkChanged(network);
        edges.networkChanged(network instanceof NetworkState state ? state : null);
    }

    /**
     * The store as neighbours see it: what they insert counts as MU the network received (display panels). The
     * network's own surplus fill goes to the store directly and is not counted again.
     */
    private final class ExternalInput implements EnergyHandler {
        @Override
        public long getAmountAsLong() {
            return store.getAmountAsLong();
        }

        @Override
        public long getCapacityAsLong() {
            return store.getCapacityAsLong();
        }

        @Override
        public int insert(int amount, TransactionContext transaction) {
            int inserted = store.insert(amount, transaction);
            NetworkState current = network();
            if (current != null) {
                current.countReceived(inserted, transaction);
            }
            return inserted;
        }

        @Override
        public int extract(int amount, TransactionContext transaction) {
            return store.extract(amount, transaction);
        }
    }

    private void energyChanged() {
        setChanged();
        updateCharge();
    }

    /** Sets the block's {@link EnergyCellBlock#CHARGE}, only when it changes. */
    private void updateCharge() {
        Level current = level;
        if (current == null || current.isClientSide() || isRemoved()) {
            return;
        }
        BlockState state = getBlockState();
        int charge = EnergyCellBlock.charge(store.stored(), store.capacity());
        if (state.hasProperty(EnergyCellBlock.CHARGE) && state.getValue(EnergyCellBlock.CHARGE) != charge) {
            current.setBlock(worldPosition, state.setValue(EnergyCellBlock.CHARGE, charge), Block.UPDATE_CLIENTS);
        }
    }

    // PLATFORM-LEAK: NeoForge's IBlockEntityExtension hook, through the base class.
    @Override
    public void onLoad() {
        if (level != null && !level.isClientSide()) {
            // The config may have changed since the block entity was made; before the cell joins a network.
            store.setCapacity(tier.capacity());
            store.setTransfer(tier.maxTransfer());
            updateCharge();
        }
        super.onLoad();
        edges.loaded();
    }

    /** A neighbour changed: the cell looks for other mods' FE blocks on its sides again. */
    @Override
    public void onNeighbourChanged() {
        edges.refresh();
    }

    // PLATFORM-LEAK: NeoForge's IBlockEntityExtension hook, through the base class.
    @Override
    public void onChunkUnloaded() {
        // Saved already: what the edges hold went into the save, so they only leave.
        edges.unloaded();
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        // Broken: the edges give back what they hold, and the rest goes into the cell before it drops.
        edges.closed();
        super.setRemoved();
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (store.stored() > 0) {
            components.set(ModDataComponents.STORED_ENERGY.get(), store.stored());
            components.set(dev.silencebound.maxtech.storage.registry.ModDataComponents.ENERGY_IN_MU.get(), true);
        }
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        if (components.get(ModDataComponents.STORED_ENERGY.get()) != null) {
            store.load(ItemEnergy.get(components, ModDataComponents.STORED_ENERGY.get()));
            updateCharge();
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        // What the edges hold counts as the cell's, so an unload right after this save loses none of it.
        output.putLong(ENERGY, store.stored() + edges.heldMu());
    }

    @Override
    protected void loadSaved(ValueInput input) {
        super.loadSaved(input);
        store.load(input.getLongOr(ENERGY, 0L));
    }
}
