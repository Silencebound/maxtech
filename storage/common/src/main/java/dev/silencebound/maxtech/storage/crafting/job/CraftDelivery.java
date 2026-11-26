package dev.silencebound.maxtech.storage.crafting.job;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.resource.StackKeys;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.network.AccessLevel;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.LogicMover;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
// PLATFORM-LEAK: the delivery moves items into the player's inventory in NeoForge's transfer transaction (PRD 7.2).
import net.neoforged.neoforge.transfer.item.PlayerInventoryWrapper;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * Where a player's finished craft goes besides storage (M7C item 5), chosen when the job starts and kept by job id in
 * {@link CraftDeliveries}: the requester's inventory, or the inventory a port with a given label faces. Each job gets
 * one try, when it completes; partial and cancelled jobs deliver nothing. What does not arrive stays in the network's
 * storage, as without a delivery, so nothing is lost and only what moved counts.
 *
 * <ul>
 * <li>{@link Kind#PLAYER}: from the CPU's buffer, before the rest goes to storage, at most the requested amount of the
 * job's item, into the requester's main inventory in one transaction. Only while the requester is online, alive, in the
 * job's dimension within {@code crafting.deliveryRange} blocks of the job's network node, and has full access to the
 * network.</li>
 * <li>{@link Kind#LABEL}: once everything is back in storage, the requested amount is moved through the network's ports
 * with that label into what they face, by the port's own logic move ({@link LogicMover}): its rate, handler and claim
 * checks apply, and what they do not take stays in storage. The requester must be online with full access to the
 * network then, as at the start; otherwise everything stays in storage.</li>
 * </ul>
 * Server thread.
 *
 * @param label
 *            the label key ({@link DeviceLabel#key()}) for {@link Kind#LABEL}; empty otherwise
 */
public record CraftDelivery(Kind kind, String label) {
    public static final Codec<CraftDelivery> CODEC = RecordCodecBuilder.create(i -> i
            .group(Kind.CODEC.fieldOf("kind").forGetter(CraftDelivery::kind),
                    Codec.STRING.optionalFieldOf("label", "").forGetter(CraftDelivery::label))
            .apply(i, CraftDelivery::new));
    /** A test's requester lookup; null in play. */
    private static @Nullable Function<UUID, @Nullable ServerPlayer> lookup;

    /** Where the craft goes. */
    public enum Kind implements StringRepresentable {
        /** Into the requester's inventory. */
        PLAYER("player"),
        /** Into what the network's ports with the label face. */
        LABEL("label");

        public static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);

        private final String name;

        Kind(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    /** Delivery into the requester's inventory. */
    public static CraftDelivery player() {
        return new CraftDelivery(Kind.PLAYER, "");
    }

    /** Delivery through the ports labelled {@code label}. */
    public static CraftDelivery label(DeviceLabel label) {
        return new CraftDelivery(Kind.LABEL, label.key());
    }

    /**
     * The ports of {@code network} labelled with key {@code label} that face one block (a port, not an interface);
     * read-only, allocates a list.
     */
    public static List<LogicMover> ports(NetworkState network, String label) {
        List<NetworkDevice> devices = network.labels().devicesByKey(label);
        List<LogicMover> out = new ArrayList<>(devices.size());
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i) instanceof LogicMover mover && mover.logicTarget() != Long.MIN_VALUE) {
                out.add(mover);
            }
        }
        return out;
    }

    /**
     * Moves job {@code job}'s item from the CPU buffer into the requester's inventory when it asked for that; called
     * once the job completed, before the buffer goes to storage. Drops the delivery either way, so it runs once.
     * Returns the items moved.
     */
    static long toPlayer(ServerLevel level, CraftingJob job, JobRun run, NetworkState network) {
        CraftDeliveries deliveries = CraftDeliveries.get(level);
        CraftDelivery delivery = deliveries.get(job.id());
        if (delivery == null || delivery.kind() != Kind.PLAYER) {
            return 0;
        }
        deliveries.remove(job.id());
        ServerPlayer player = requester(level, job);
        if (!(job.key() instanceof ItemKey item) || player == null || !inRange(player, run.origin)
                || !network.accessLevel(player).atLeast(AccessLevel.FULL)) {
            return 0;
        }
        long want = Math.min(job.amount(), run.buffer.getLong(item));
        if (want <= 0) {
            return 0;
        }
        int moved;
        try (Transaction transaction = Transaction.openRoot()) {
            moved = PlayerInventoryWrapper.of(player).insert(StackKeys.toResource(item),
                    (int) Math.min(Integer.MAX_VALUE, want), transaction);
            transaction.commit();
        }
        if (moved > 0) {
            JobRun.subtract(run.buffer, item, moved);
            run.changed();
        }
        return moved;
    }

    /**
     * Moves job {@code job}'s item from {@code network}'s storage through the labelled ports when it asked for that;
     * called once the job completed and its buffer is in storage. Must run outside any transaction. Drops the delivery
     * either way, so it runs once. Returns what moved.
     */
    static long toLabel(ServerLevel level, CraftingJob job, NetworkState network, long gameTime) {
        CraftDeliveries deliveries = CraftDeliveries.get(level);
        CraftDelivery delivery = deliveries.get(job.id());
        if (delivery == null || delivery.kind() != Kind.LABEL) {
            return 0;
        }
        deliveries.remove(job.id());
        // Full access again now: it may have been taken away while the job ran. Offline, it cannot be checked.
        ServerPlayer player = requester(level, job);
        if (player == null || !network.accessLevel(player).atLeast(AccessLevel.FULL)) {
            return 0;
        }
        List<LogicMover> ports = ports(network, delivery.label());
        long left = job.amount();
        for (int i = 0; i < ports.size() && left > 0; i++) {
            left -= ports.get(i).logicMove(job.key(), left, true, gameTime);
        }
        return job.amount() - left;
    }

    /** The job's requester when online in {@code level}, alive and no spectator; null otherwise. */
    @SuppressWarnings("ReferenceEquality") // one level object per dimension
    private static @Nullable ServerPlayer requester(ServerLevel level, CraftingJob job) {
        UUID id = job.requester().orElse(null);
        Function<UUID, @Nullable ServerPlayer> installed = lookup;
        ServerPlayer player = id == null
                ? null
                : installed != null ? installed.apply(id) : level.getServer().getPlayerList().getPlayer(id);
        return player != null && player.level() == level && player.isAlive() && !player.isSpectator() ? player : null;
    }

    /**
     * Finds requesters through {@code installed} instead of the server's player list, or through the list again with
     * null. For GameTests: their players have no connection and are on no list, and a listed mock player cannot take
     * the job-finished toast.
     */
    public static void setPlayerLookup(@Nullable Function<UUID, @Nullable ServerPlayer> installed) {
        lookup = installed;
    }

    /** Whether {@code player} is within {@code crafting.deliveryRange} blocks of the job's node {@code origin}. */
    public static boolean inRange(ServerPlayer player, long origin) {
        long range = StorageServerConfig.CRAFTING_DELIVERY_RANGE.getAsInt();
        return player.blockPosition().distSqr(BlockPos.of(origin)) <= range * range;
    }
}
