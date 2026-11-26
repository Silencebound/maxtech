package dev.silencebound.maxtech.storage.tool.tablet;

import dev.silencebound.maxtech.storage.energy.ItemEnergy;
import dev.silencebound.maxtech.lib.api.carried.CarriedSlot;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.gui.transfer.IngredientSource;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.module.DimensionBridge;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
// PLATFORM-LEAK: the tablet moves items in NeoForge transactions, like every Storage transfer (PRD 7.2).
import net.neoforged.neoforge.transfer.transaction.Transaction;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * What an open crafting tablet may do with its network, checked again on every call: the bound controller is loaded and
 * in a network, the player is in the wireless coverage (or the network has a dimension bridge), and the same tablet is
 * still in the hand (or the carried slot it was opened from, {@link CarriedSlot}). Each move of items through the
 * network pays {@code crafting.tablet.energyPerOperation} FE from the tablet's charge, and from another dimension the
 * bridge's surcharge from the network's, in the same transaction; a move that cannot pay is rolled back. Server thread,
 * one per open menu.
 */
public final class TabletAccess {
    private final ServerPlayer player;
    private final CarriedSlot slot;
    private final GlobalPos link;
    private final ControllerBlockEntity core;

    TabletAccess(ServerPlayer player, CarriedSlot slot, GlobalPos link, ControllerBlockEntity core) {
        this.player = player;
        this.slot = slot;
        this.link = link;
        this.core = core;
    }

    /** The tablet in its slot while it is still bound to this network; empty once it was moved away. */
    private ItemStack held() {
        ItemStack stack = slot.get();
        return stack.getItem() instanceof CraftingTabletItem
                && link.equals(stack.get(ModDataComponents.WIRELESS_LINK.get())) ? stack : ItemStack.EMPTY;
    }

    /** The network while the tablet reaches it (power aside), else null. */
    public @Nullable NetworkState network() {
        ItemStack stack = held();
        if (stack.isEmpty() || !player.isAlive()) {
            return null;
        }
        WirelessTerminalItem.Reach reach = CraftingTabletItem.reach(player, stack);
        return reach == WirelessTerminalItem.Reach.OK || reach == WirelessTerminalItem.Reach.NO_POWER
                ? core.network()
                : null;
    }

    /** Where the bound controller is. */
    BlockPos corePos() {
        return core.getBlockPos();
    }

    /** The level of the bound controller, or null while it is not loaded. */
    public @Nullable ServerLevel level() {
        return core.getLevel() instanceof ServerLevel server ? server : null;
    }

    /** FE the tablet holds now. */
    public int energy() {
        ItemStack stack = held();
        return stack.isEmpty() ? 0 : CraftingTabletItem.energy(stack);
    }

    /** Whether the player may see the network's contents, and so use the tablet at all. */
    public boolean canView() {
        NetworkState network = network();
        return network != null && network.canAccess(player);
    }

    /** Whether the player may take items out: full access, as at a terminal. */
    public boolean canTake() {
        NetworkState network = network();
        return network != null && network.accessLevel(player) == AccessLevel.FULL;
    }

    /** Whether the player may put items in: deposit access or more. */
    public boolean canDeposit() {
        NetworkState network = network();
        return network != null && network.accessLevel(player).atLeast(AccessLevel.DEPOSIT);
    }

    /** Whether the player may order crafting jobs: the craft access level (M7 C17). */
    public boolean canCraft() {
        NetworkState network = network();
        return network != null && network.accessLevel(player).atLeast(AccessLevel.CRAFT);
    }

    /** Takes the FE of one operation off the tablet; false (nothing taken) when it holds less. */
    boolean pay() {
        long cost = CraftingTabletItem.energyPerOperation();
        ItemStack stack = held();
        if (cost <= 0) {
            return !stack.isEmpty();
        }
        if (stack.isEmpty() || CraftingTabletItem.energy(stack) < cost) {
            return false;
        }
        ItemEnergy.setInt(stack, ModDataComponents.WIRELESS_ENERGY.get(),
                CraftingTabletItem.energy(stack) - (int) cost);
        return true;
    }

    /**
     * Takes up to {@code amount} of the item {@code like} out of the network; returns what came out, empty when the
     * player may not take, the tablet cannot pay or the network has none. Nothing is lost: the stack is built from what
     * the committed transaction moved.
     */
    public ItemStack take(ItemStack like, int amount) {
        NetworkState network = network();
        if (network == null || amount <= 0 || like.isEmpty() || !canTake() || energy() < cost()) {
            return ItemStack.EMPTY;
        }
        ItemKey key = ItemKey.of(like);
        long moved;
        try (Transaction transaction = Transaction.openRoot()) {
            moved = network.extract(key, amount, transaction, player);
            if (moved <= 0 || !bridgePaid(network, transaction)) {
                return ItemStack.EMPTY;
            }
            transaction.commit();
        }
        pay();
        return key.toStack((int) moved);
    }

    /**
     * Stores {@code stack} in the network; returns what did not go in (all of it when the player may not deposit or the
     * tablet cannot pay). Does not change {@code stack}.
     */
    public ItemStack store(ItemStack stack) {
        NetworkState network = network();
        if (network == null || stack.isEmpty() || !canDeposit() || energy() < cost()) {
            return stack;
        }
        long moved;
        try (Transaction transaction = Transaction.openRoot()) {
            moved = network.insert(ItemKey.of(stack), stack.getCount(), transaction, player);
            if (moved <= 0 || !bridgePaid(network, transaction)) {
                return stack;
            }
            transaction.commit();
        }
        pay();
        return stack.copyWithCount(stack.getCount() - (int) moved);
    }

    private static long cost() {
        return CraftingTabletItem.energyPerOperation();
    }

    @SuppressWarnings("ReferenceEquality") // levels are compared by identity
    boolean bridgePaid(NetworkState network, TransactionContext transaction) {
        return core.getLevel() == player.level() || DimensionBridge.pay(network, transaction);
    }

    /** The network as a recipe transfer's ingredient source: items come out of it and cleared grid items go into it. */
    public IngredientSource ingredients() {
        return new IngredientSource() {
            @Override
            public ItemStack extract(ItemStack like, int amount) {
                return take(like, amount);
            }

            @Override
            public ItemStack insert(ItemStack stack) {
                return store(stack);
            }
        };
    }
}
