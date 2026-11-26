package dev.silencebound.maxtech.lib.api.security;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Player;

/**
 * Who owns a block or item and who else may use it: the owner's UUID and name (the name resolves vanilla scoreboard
 * teams for an offline owner), a trusted list, and whether the owner's team shares access. {@link #UNOWNED} has the nil
 * UUID and lets everyone in. Immutable; the {@code with...} methods return copies. Saved through {@link #CODEC} (block
 * entities, the {@code maxtech_lib:owner} data component) and synced through {@link #STREAM_CODEC}. Any thread.
 *
 * @param owner
 *            the owner's UUID, or the nil UUID for an unowned record
 * @param ownerName
 *            the owner's name when the record was made or last refreshed; empty when unowned
 * @param trusted
 *            players besides the owner with access, at most {@link #MAX_TRUSTED}, no duplicates
 * @param teamAccess
 *            whether players on the owner's team have access
 */
public record OwnerRecord(UUID owner, String ownerName, List<UUID> trusted, boolean teamAccess) {
    /** Most trusted players one record holds; the codecs refuse more. */
    public static final int MAX_TRUSTED = 64;
    /** Longest owner name stored; vanilla names are at most 16 characters. */
    public static final int MAX_NAME_LENGTH = 16;
    /** Nobody owns it: every player has access. */
    public static final OwnerRecord UNOWNED = new OwnerRecord(Util.NIL_UUID, "", List.of(), true);

    /**
     * Saved form: {@code owner}, and optional {@code owner_name}, {@code trusted} and {@code team_access}. Refuses more
     * than {@link #MAX_TRUSTED} trusted players and names longer than {@link #MAX_NAME_LENGTH}.
     */
    public static final Codec<OwnerRecord> CODEC = RecordCodecBuilder.create(instance -> instance
            .group(UUIDUtil.CODEC.fieldOf("owner").forGetter(OwnerRecord::owner),
                    Codec.string(0, MAX_NAME_LENGTH).optionalFieldOf("owner_name", "")
                            .forGetter(OwnerRecord::ownerName),
                    Codec.list(UUIDUtil.CODEC, 0, MAX_TRUSTED).optionalFieldOf("trusted", List.of())
                            .forGetter(OwnerRecord::trusted),
                    Codec.BOOL.optionalFieldOf("team_access", true).forGetter(OwnerRecord::teamAccess))
            .apply(instance, OwnerRecord::new));

    /** Network form, with the same limits as {@link #CODEC}. */
    public static final StreamCodec<ByteBuf, OwnerRecord> STREAM_CODEC = StreamCodec.composite(UUIDUtil.STREAM_CODEC,
            OwnerRecord::owner, ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH), OwnerRecord::ownerName,
            UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_TRUSTED)), OwnerRecord::trusted, ByteBufCodecs.BOOL,
            OwnerRecord::teamAccess, OwnerRecord::new);

    /**
     * Drops duplicate trusted players and cuts {@code ownerName} to {@link #MAX_NAME_LENGTH}. Throws
     * {@link IllegalArgumentException} for more than {@link #MAX_TRUSTED} trusted players.
     */
    public OwnerRecord {
        if (trusted.size() > MAX_TRUSTED) {
            throw new IllegalArgumentException("At most " + MAX_TRUSTED + " trusted players");
        }
        trusted = List.copyOf(new LinkedHashSet<>(trusted));
        if (ownerName.length() > MAX_NAME_LENGTH) {
            ownerName = ownerName.substring(0, MAX_NAME_LENGTH);
        }
    }

    /** Owned by {@code player}, nobody trusted yet, team access on. */
    public static OwnerRecord of(Player player) {
        return new OwnerRecord(player.getUUID(), player.getGameProfile().name(), List.of(), true);
    }

    /** Whether someone owns it; an unowned record grants everyone access. */
    public boolean isOwned() {
        return !owner.equals(Util.NIL_UUID);
    }

    /** Whether {@code player} is the owner. */
    public boolean isOwner(UUID player) {
        return isOwned() && owner.equals(player);
    }

    /** Whether {@code player} is on the trusted list. */
    public boolean isTrusted(UUID player) {
        return trusted.contains(player);
    }

    /** A copy with {@code player} trusted; unchanged if already trusted, the owner, or the list is full. */
    public OwnerRecord withTrusted(UUID player) {
        if (isTrusted(player) || isOwner(player) || trusted.size() >= MAX_TRUSTED) {
            return this;
        }
        List<UUID> list = new ArrayList<>(trusted);
        list.add(player);
        return new OwnerRecord(owner, ownerName, list, teamAccess);
    }

    /** A copy without {@code player} on the trusted list. */
    public OwnerRecord withoutTrusted(UUID player) {
        if (!isTrusted(player)) {
            return this;
        }
        List<UUID> list = new ArrayList<>(trusted);
        list.remove(player);
        return new OwnerRecord(owner, ownerName, list, teamAccess);
    }

    /** A copy with team access switched. */
    public OwnerRecord withTeamAccess(boolean value) {
        return value == teamAccess ? this : new OwnerRecord(owner, ownerName, trusted, value);
    }
}
