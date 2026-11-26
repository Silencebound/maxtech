package dev.silencebound.maxtech.storage.terminal.crafting;

import dev.silencebound.maxtech.storage.terminal.filter.ViewFilterSlots;
import dev.silencebound.maxtech.lib.api.info.BlockInfoRequest;
import dev.silencebound.maxtech.lib.api.info.BlockInfoSink;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import dev.silencebound.maxtech.storage.terminal.NetworkTerminal;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
// PLATFORM-LEAK: player transfers run in NeoForge transactions, like every Storage transfer (PRD 7.2).
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * The crafting terminal block's block entity: the item terminal ({@link NetworkTerminal}) plus the
 * {@link TerminalGrid}, which it saves and drops when it is broken. Links on every side but its screen; never ticks.
 */
public final class CraftingTerminalBlockEntity extends StorageDeviceBlockEntity implements CraftingTerminal {
    /** How far beyond block reach the menu stays usable, as for vanilla containers. */
    static final double RANGE_BUFFER = 4.0;

    private final NetworkTerminal terminal = new NetworkTerminal(this,
            player -> !isRemoved() && player.isWithinBlockInteractionRange(getBlockPos(), RANGE_BUFFER));
    private final TerminalGrid grid = new TerminalGrid(this::setChanged);
    private final ViewFilterSlots viewFilters = new ViewFilterSlots(this::setChanged);

    public CraftingTerminalBlockEntity(BlockPos pos, BlockState state) {
        super(CraftingTerminalContent.BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Role role() {
        return Role.ACCESS;
    }

    @Override
    public TerminalGrid grid() {
        return grid;
    }

    /** Every side but the screen links to the network. */
    @Override
    public boolean linksOn(Direction side) {
        return side != getBlockState().getValue(CraftingTerminalBlock.FACING);
    }

    @Override
    public void networkChanged(@Nullable StorageNetwork network) {
        terminal.networkChanged();
    }

    /** Broken or replaced (never on chunk unload): the grid's items drop where the terminal was. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level != null && !level.isClientSide()) {
            for (ItemStack stack : grid.takeAll()) {
                Block.popResource(level, pos, stack);
            }
            for (ItemStack stack : viewFilters.takeAll()) {
                Block.popResource(level, pos, stack);
            }
        }
    }

    /** The grid's stacks as item icons. */
    @Override
    public void addGlance(BlockInfoRequest request, BlockInfoSink sink) {
        CraftingTerminalContent.glance(grid, sink);
    }

    @Override
    public boolean stillValid(Player player) {
        return terminal.stillValid(player);
    }

    @Override
    public boolean canAccess(Player player) {
        return terminal.canAccess(player);
    }

    @Override
    public boolean canDeposit(Player player) {
        return terminal.canDeposit(player);
    }

    @Override
    public boolean canTake(Player player) {
        return terminal.canTake(player);
    }

    @Override
    public Int2LongMap contents() {
        return terminal.contents();
    }

    @Override
    public @Nullable StackKey key(int id) {
        return terminal.key(id);
    }

    @Override
    public long amount(int id) {
        return terminal.amount(id);
    }

    @Override
    public long insert(StackKey key, long amount, TransactionContext transaction) {
        return terminal.insert(key, amount, transaction);
    }

    @Override
    public long extract(StackKey key, long amount, TransactionContext transaction) {
        return terminal.extract(key, amount, transaction);
    }

    @Override
    public void watch(TerminalMenu menu) {
        terminal.watch(menu);
    }

    @Override
    public void unwatch(TerminalMenu menu) {
        terminal.unwatch(menu);
    }

    @Override
    public int status(int field) {
        return terminal.status(field);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        viewFilters.save(output);
        grid.save(output);
    }

    @Override
    protected void loadSaved(ValueInput input) {
        super.loadSaved(input);
        viewFilters.load(input);
        grid.load(input);
    }

    @Override
    public ViewFilterSlots viewFilters() {
        return viewFilters;
    }
}
