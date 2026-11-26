package dev.silencebound.maxtech.storage.crafting.request;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.job.CraftDelivery;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.NetworkLabels;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;

/**
 * Where a craft request can send its finished items (M7C item 5), as the request screen's "deliver to" button cycles
 * them: storage only (choice 0), the requester's inventory (1), or the ports of one label (2 and up, in the order of
 * the last {@link Destinations} list). The session sends the labels with every plan; the start request carries the
 * choice in its otherwise unused {@code request} number, and the server checks it against the list it sent. No new
 * screen: one button on the plan step. Client state is kept per container id like the label tab's.
 */
public final class CraftDestinations {
    /** Labels listed at most; a bound on the payload, not a balance value. */
    public static final int MAX_LABELS = 16;
    /** The start request's choice for storage only. */
    public static final int STORAGE = 0;
    /** The start request's choice for the requester's inventory. */
    public static final int PLAYER = 1;
    /** The start request's choice for the first listed label; the n-th label is {@code LABEL + n}. */
    public static final int LABEL = 2;

    public static final String STORAGE_KEY = gui("storage");
    public static final String PLAYER_KEY = gui("player");
    public static final String LABEL_KEY = gui("label");
    public static final String HINT_KEY = gui("hint");
    public static final String DENIED_KEY = "message." + MaxtechStorage.MOD_ID + ".craft.deliver_denied";
    public static final String NO_PORT_KEY = "message." + MaxtechStorage.MOD_ID + ".craft.deliver_no_port";

    static {
        ModContent.CONTENT.translation(STORAGE_KEY, "To storage");
        ModContent.CONTENT.translation(PLAYER_KEY, "To me");
        ModContent.CONTENT.translation(LABEL_KEY, "To %s");
        ModContent.CONTENT.translation(HINT_KEY,
                "Where the finished items go: storage, your inventory (when you are near and have full access; "
                        + "what does not fit stays in storage) or what the ports with a label face. Shift goes back.");
        ModContent.CONTENT.translation(DENIED_KEY, "Delivering crafts out of the network needs full access");
        ModContent.CONTENT.translation(NO_PORT_KEY, "No port with that label faces a container any more");
    }

    /** The container the last {@link Destinations} was for; -1 for none. Client thread only. */
    private static int shownContainer = -1;
    private static List<DeviceLabel> shown = List.of();

    private CraftDestinations() {
    }

    /** Server to client: the labels of the network's ports that a craft can be delivered through. */
    public record Destinations(int containerId, List<DeviceLabel> labels) implements CustomPacketPayload {
        public static final Type<Destinations> TYPE = new Type<>(MaxtechStorage.id("craft_destinations"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Destinations> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Destinations::containerId,
                DeviceLabel.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_LABELS)), Destinations::labels,
                Destinations::new);

        public Destinations {
            labels = List.copyOf(labels);
        }

        @Override
        public Type<Destinations> type() {
            return TYPE;
        }
    }

    /** Registers the payload; from {@link RequestContent#init}. */
    static void register() {
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, Destinations.TYPE, Destinations.CODEC,
                CraftDestinations::show);
    }

    /**
     * The labels of {@code network}'s ports that face a block, sorted by text, at most {@link #MAX_LABELS}; allocates,
     * for a plan answer only.
     */
    public static List<DeviceLabel> labels(NetworkState network) {
        List<DeviceLabel> out = new ArrayList<>();
        for (NetworkLabels.Entry entry : network.labels().entries(Integer.MAX_VALUE)) {
            if (out.size() >= MAX_LABELS) {
                break;
            }
            if (!CraftDelivery.ports(network, entry.label().key()).isEmpty()) {
                out.add(entry.label());
            }
        }
        return out;
    }

    private static void show(Destinations payload, Player player) {
        if (player.containerMenu.containerId == payload.containerId()) {
            shownContainer = payload.containerId();
            shown = payload.labels();
        }
    }

    /** Client side: the labels the server last listed for the menu {@code containerId}; empty for another menu. */
    public static List<DeviceLabel> shown(int containerId) {
        return shownContainer == containerId ? shown : List.of();
    }

    private static String gui(String name) {
        return "gui." + MaxtechStorage.MOD_ID + ".craft.deliver." + name;
    }
}
