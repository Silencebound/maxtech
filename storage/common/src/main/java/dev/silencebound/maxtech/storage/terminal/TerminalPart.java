package dev.silencebound.maxtech.storage.terminal;

import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.item.ItemStack;
import java.util.List;
import dev.silencebound.maxtech.storage.terminal.filter.ViewFilterSlots;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.part.CablePart;
import dev.silencebound.maxtech.storage.part.CableParts;
import dev.silencebound.maxtech.storage.part.PartType;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
// PLATFORM-LEAK: player transfers run in NeoForge transactions, like every Storage transfer (PRD 7.2).
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * The item terminal as a part on a storage cable's face: the same terminal as the block ({@link NetworkTerminal}), on
 * the cable's node, opening the same GUI. Holds nothing and never ticks.
 */
public final class TerminalPart extends CablePart implements TerminalSource {
    private final NetworkTerminal terminal = new NetworkTerminal(this,
            player -> !isRemoved() && player.isWithinBlockInteractionRange(pos(), TerminalBlockEntity.RANGE_BUFFER));
    private final ViewFilterSlots viewFilters = new ViewFilterSlots(host()::changed);

    public TerminalPart(PartType type, CableParts host, Direction face) {
        super(type, host, face);
    }

    @Override
    public Role role() {
        return Role.ACCESS;
    }

    @Override
    public void networkChanged(@Nullable StorageNetwork network) {
        terminal.networkChanged();
    }

    /** Opens the terminal GUI, or says why not; both sides report success so the client swings the arm. */
    @Override
    public InteractionResult use(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            if (canAccess(serverPlayer)) {
                TerminalMenu.open(serverPlayer, this, pos(), type().item().getName(toItem()));
            } else {
                serverPlayer.sendOverlayMessage(Component.translatable(TerminalContent.DENIED_KEY));
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public boolean stillValid(Player player) {
        return terminal.stillValid(player);
    }

    @Override
    public @Nullable NetworkState network() {
        return terminal.network();
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

    /** Menus open on this terminal; tests use it. */
    public int openMenus() {
        return terminal.openMenus();
    }

    @Override
    public int status(int field) {
        return terminal.status(field);
    }

    @Override
    public ViewFilterSlots viewFilters() {
        return viewFilters;
    }

    /** The part comes off: its view filters drop with it. */
    @Override
    public List<ItemStack> takeContents() {
        return viewFilters.takeAll();
    }

    @Override
    protected void save(ValueOutput output) {
        super.save(output);
        viewFilters.save(output);
    }

    @Override
    protected void load(ValueInput input) {
        super.load(input);
        viewFilters.load(input);
    }
}
