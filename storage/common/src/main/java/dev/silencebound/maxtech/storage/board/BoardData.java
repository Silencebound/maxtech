package dev.silencebound.maxtech.storage.board;

import dev.silencebound.maxtech.storage.save.StorageSaveFormats;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * Every team's request board (M6e area G), one per server: the open item requests players of a team posted, in the
 * order they were posted. A request names one kind of item and how many are wanted; fulfilling it delivers into the
 * poster's mail inbox, and the request goes once all are delivered or the poster cancels it. Server thread.
 */
public final class BoardData extends SavedData {
    /** Request ids stay below this, so a menu button id can carry one. */
    public static final int MAX_ID = 1 << 20;

    private static final Codec<Request> REQUEST_CODEC = RecordCodecBuilder.create(instance -> instance
            .group(Codec.INT.fieldOf("id").forGetter(Request::id),
                    UUIDUtil.CODEC.fieldOf("team").forGetter(Request::team),
                    UUIDUtil.CODEC.fieldOf("poster").forGetter(Request::poster),
                    Codec.STRING.fieldOf("poster_name").forGetter(Request::posterName),
                    ItemStack.CODEC.fieldOf("item").forGetter(Request::item),
                    Codec.INT.fieldOf("wanted").forGetter(Request::wanted),
                    Codec.INT.optionalFieldOf("delivered", 0).forGetter(Request::delivered))
            .apply(instance, Request::new));
    private static final Codec<Saved> SAVED_CODEC = RecordCodecBuilder.create(instance -> instance
            .group(Codec.INT.optionalFieldOf("next_id", 0).forGetter(Saved::nextId),
                    REQUEST_CODEC.listOf().optionalFieldOf("requests", List.of()).forGetter(Saved::requests))
            .apply(instance, Saved::new));
    public static final Codec<BoardData> CODEC = SAVED_CODEC.xmap(BoardData::new, BoardData::saved);
    public static final SavedDataType<BoardData> TYPE = StorageSaveFormats.savedData(MaxtechStorage.id("request_board"),
            BoardData::new, CODEC);

    private final Map<UUID, List<Request>> teams = new Object2ObjectOpenHashMap<>();
    private int nextId;
    /** Bumped by every change; open screens resend their list when it moved. */
    private long version;

    /**
     * One open request.
     *
     * @param item
     *            one of the wanted item, with its components; matched exactly
     */
    public record Request(int id, UUID team, UUID poster, String posterName, ItemStack item, int wanted,
            int delivered) {
        /** How many are still wanted. */
        public int remaining() {
            return Math.max(0, wanted - delivered);
        }

        Request withDelivered(int more) {
            return new Request(id, team, poster, posterName, item, wanted, delivered + more);
        }
    }

    private record Saved(int nextId, List<Request> requests) {
    }

    private BoardData() {
    }

    private BoardData(Saved saved) {
        nextId = Math.floorMod(saved.nextId(), MAX_ID);
        for (Request request : saved.requests()) {
            if (!request.item().isEmpty() && request.remaining() > 0) {
                teams.computeIfAbsent(request.team(), team -> new ArrayList<>()).add(request);
            }
        }
    }

    private Saved saved() {
        List<Request> all = new ArrayList<>();
        for (List<Request> requests : teams.values()) {
            all.addAll(requests);
        }
        return new Saved(nextId, all);
    }

    /** The server's request boards. */
    public static BoardData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** {@code team}'s open requests, oldest first; read-only. */
    public List<Request> requests(UUID team) {
        List<Request> requests = teams.get(team);
        return requests == null ? List.of() : Collections.unmodifiableList(requests);
    }

    /** The request with {@code id} of {@code team}, or null. */
    public @Nullable Request request(UUID team, int id) {
        List<Request> requests = teams.get(team);
        if (requests != null) {
            for (Request request : requests) {
                if (request.id() == id) {
                    return request;
                }
            }
        }
        return null;
    }

    /**
     * Posts a request for {@code wanted} of {@code item} (its count is ignored) by {@code poster}; null when the item
     * is empty, {@code wanted} is not positive or {@code team} already has {@code max} requests.
     */
    public @Nullable Request post(UUID team, UUID poster, String posterName, ItemStack item, int wanted, int max) {
        List<Request> requests = teams.computeIfAbsent(team, key -> new ArrayList<>());
        if (item.isEmpty() || wanted <= 0 || requests.size() >= max) {
            return null;
        }
        Request request = new Request(nextId, team, poster, posterName, item.copyWithCount(1), wanted, 0);
        nextId = (nextId + 1) % MAX_ID;
        requests.add(request);
        changed();
        return request;
    }

    /** Counts {@code amount} more as delivered for request {@code id}; the request goes once it is complete. */
    public void delivered(UUID team, int id, int amount) {
        List<Request> requests = teams.get(team);
        if (requests == null || amount <= 0) {
            return;
        }
        for (int i = 0; i < requests.size(); i++) {
            Request request = requests.get(i);
            if (request.id() == id) {
                Request updated = request.withDelivered(amount);
                if (updated.remaining() == 0) {
                    requests.remove(i);
                } else {
                    requests.set(i, updated);
                }
                changed();
                return;
            }
        }
    }

    /** Takes request {@code id} off {@code team}'s board; false when there was none. */
    public boolean cancel(UUID team, int id) {
        List<Request> requests = teams.get(team);
        if (requests != null && requests.removeIf(request -> request.id() == id)) {
            changed();
            return true;
        }
        return false;
    }

    /** Bumped by every change. */
    public long version() {
        return version;
    }

    private void changed() {
        version++;
        setDirty();
    }
}
