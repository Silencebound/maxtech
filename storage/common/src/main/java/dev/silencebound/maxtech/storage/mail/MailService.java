package dev.silencebound.maxtech.storage.mail;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.energy.FeBuffer;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.module.DimensionBridge;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * Sending and collecting mail (M6e area G). A mail port sends the stacks of its outbox to a player's inbox
 * ({@link MailData}), paying {@code services.mail.fePerStack} FE per stack from its network; the recipient collects
 * their inbox at any mail port, into that port's network as far as they may deposit there and the rest into their
 * inventory. Server thread.
 */
public final class MailService {
    public static final String UNKNOWN_KEY = "message." + MaxtechStorage.MOD_ID + ".mail.unknown";
    public static final String SENT_KEY = "message." + MaxtechStorage.MOD_ID + ".mail.sent";
    public static final String PARTLY_KEY = "message." + MaxtechStorage.MOD_ID + ".mail.partly";
    public static final String NO_POWER_KEY = "message." + MaxtechStorage.MOD_ID + ".mail.no_power";
    public static final String OFFLINE_KEY = "message." + MaxtechStorage.MOD_ID + ".mail.offline";
    public static final String FULL_KEY = "message." + MaxtechStorage.MOD_ID + ".mail.full";
    public static final String DENIED_KEY = "message." + MaxtechStorage.MOD_ID + ".mail.denied";
    public static final String EMPTY_KEY = "message." + MaxtechStorage.MOD_ID + ".mail.empty";
    public static final String RECEIVED_KEY = "message." + MaxtechStorage.MOD_ID + ".mail.received";
    public static final String COLLECTED_KEY = "message." + MaxtechStorage.MOD_ID + ".mail.collected";
    public static final String NO_BRIDGE_KEY = "message." + MaxtechStorage.MOD_ID + ".mail.no_bridge";

    /** What a send did. */
    public enum Result {
        /** Every stack went. */
        SENT,
        /** Some stacks went; the inbox filled or the FE ran out. */
        PARTLY,
        /** No player with that name used a mail port, or the name is the sender's own. */
        UNKNOWN,
        /** The recipient's inbox is full. */
        FULL,
        /** The network cannot pay for one stack. */
        NO_POWER,
        /** The port is in no working network. */
        OFFLINE,
        /** The sender may not use the network's FE (deposit access is needed). */
        DENIED,
        /** The outbox is empty. */
        EMPTY,
        /** The recipient is in another dimension and the port's network has no dimension bridge. */
        NO_BRIDGE;

        /** The message the sender sees. */
        public String translationKey() {
            return switch (this) {
                case SENT -> SENT_KEY;
                case PARTLY -> PARTLY_KEY;
                case UNKNOWN -> UNKNOWN_KEY;
                case FULL -> FULL_KEY;
                case NO_POWER -> NO_POWER_KEY;
                case OFFLINE -> OFFLINE_KEY;
                case DENIED -> DENIED_KEY;
                case EMPTY -> EMPTY_KEY;
                case NO_BRIDGE -> NO_BRIDGE_KEY;
            };
        }
    }

    private MailService() {
    }

    /**
     * Remembers the player's name for addressing, and their dimension as their home; every mail port and request board
     * use calls this.
     */
    public static void remember(ServerPlayer player) {
        MailData.get(player.level().getServer()).remember(player.getUUID(), player.getGameProfile().name(),
                player.level().dimension());
    }

    /**
     * Whether mail sent from {@code from} to {@code recipient} crosses dimensions (M6f): the recipient is online in
     * another dimension, or offline and last used a mail port or request board in another one. A recipient never seen
     * anywhere counts as the same dimension.
     */
    public static boolean crosses(MinecraftServer server, UUID recipient, ResourceKey<Level> from) {
        ServerPlayer online = server.getPlayerList().getPlayer(recipient);
        ResourceKey<Level> home = online != null ? online.level().dimension() : MailData.get(server).home(recipient);
        return home != null && !home.equals(from);
    }

    /**
     * The player {@code name} addresses: an online player first (remembered on the way), else the last player seen with
     * that name at a mail port or request board; null for neither.
     */
    public static @Nullable UUID resolve(MinecraftServer server, String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            remember(online);
            return online.getUUID();
        }
        return MailData.get(server).find(name);
    }

    /**
     * Sends {@code outbox}'s stacks from {@code sender} to the player called {@code name}: slot by slot, each stack
     * paid for and delivered whole, until the inbox is full or the FE runs out. Sent stacks leave the outbox. Mail to a
     * player in another dimension ({@link #crosses}) needs a dimension bridge on the port's network and pays its
     * surcharge per stack on top.
     */
    public static Result send(ServerPlayer sender, @Nullable NetworkState network, String name, Container outbox) {
        if (outbox.isEmpty()) {
            return Result.EMPTY;
        }
        if (network == null || network.status() != StorageNetwork.Status.ONLINE) {
            return Result.OFFLINE;
        }
        if (!network.accessLevel(sender).atLeast(AccessLevel.DEPOSIT)) {
            return Result.DENIED;
        }
        MinecraftServer server = sender.level().getServer();
        UUID recipient = resolve(server, name);
        if (recipient == null || recipient.equals(sender.getUUID())) {
            return Result.UNKNOWN;
        }
        boolean crossing = crosses(server, recipient, network.level().dimension());
        if (crossing && !DimensionBridge.bridged(network)) {
            return Result.NO_BRIDGE;
        }
        MailData data = MailData.get(server);
        int capacity = StorageServerConfig.MAIL_INBOX_STACKS.getAsInt();
        long cost = StorageServerConfig.MAIL_MU_PER_STACK.getAsLong();
        if (crossing) {
            long surcharge = DimensionBridge.surcharge();
            cost = cost > Long.MAX_VALUE - surcharge ? Long.MAX_VALUE : cost + surcharge;
        }
        FeBuffer buffer = network.energyBuffer();
        int sent = 0;
        Result stop = null;
        for (int slot = 0; slot < outbox.getContainerSize(); slot++) {
            ItemStack stack = outbox.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (data.room(recipient, capacity) <= 0) {
                stop = Result.FULL;
                break;
            }
            if (cost > 0 && (buffer == null || !buffer.tryConsume(cost))) {
                stop = Result.NO_POWER;
                break;
            }
            data.deliver(recipient, stack, capacity);
            outbox.setItem(slot, ItemStack.EMPTY);
            sent++;
        }
        if (sent > 0) {
            ServerPlayer online = server.getPlayerList().getPlayer(recipient);
            if (online != null && online.connection != null) {
                online.sendSystemMessage(
                        Component.translatable(RECEIVED_KEY, Integer.toString(sent), sender.getGameProfile().name()));
            }
        }
        if (stop == null) {
            return Result.SENT;
        }
        return sent > 0 ? Result.PARTLY : stop;
    }

    /**
     * Collects {@code player}'s inbox: each stack into {@code network} as far as it takes it and the player may deposit
     * there, the rest into the player's inventory; what fits nowhere stays in the inbox. Returns the stacks that left
     * the inbox.
     */
    public static int collect(ServerPlayer player, @Nullable NetworkState network) {
        boolean store = network != null && network.status() == StorageNetwork.Status.ONLINE
                && network.accessLevel(player).atLeast(AccessLevel.DEPOSIT);
        NetworkState target = store ? network : null;
        return MailData.get(player.level().getServer()).collect(player.getUUID(), stack -> {
            if (target != null) {
                try (Transaction transaction = Transaction.openRoot()) {
                    long stored = target.insert(ItemKey.of(stack), stack.getCount(), transaction, player);
                    transaction.commit();
                    stack.shrink((int) stored);
                }
            }
            if (!stack.isEmpty()) {
                player.getInventory().add(stack);
            }
        });
    }
}
