package dev.silencebound.maxtech.storage.tool.carried;

import dev.silencebound.maxtech.lib.api.carried.CarriedItems;
import dev.silencebound.maxtech.lib.api.carried.CarriedSlot;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.tool.carried.client.GadgetPickerScreen;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * The carried-gadget key's server side (owner decision 2026-10-09: one key and a picker instead of a key per gadget).
 * The client sends {@link Pick} when the key is pressed with no screen open; the server looks for each kind of
 * {@link CarriedGadget} the player carries ({@link CarriedItems}: inventory, then accessory slots). None: it says so.
 * One kind: it opens that gadget as using it does, with the same reach, power and access checks. Several: it sends
 * {@link Choices} and the client shows the picker, whose choice comes back as {@link Request} and opens the first
 * gadget of that kind. A request while another menu is open, from a spectator or for an unknown gadget is ignored;
 * opening needs the player's inventory menu, so a held key cannot open menus faster than the player can close them.
 * Server thread.
 */
public final class CarriedOpen {
    /** Client to server: open the first carried gadget of kind {@code gadget} ({@link CarriedGadget} index). */
    public record Request(int gadget) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(MaxtechStorage.id("open_carried"));
        public static final StreamCodec<ByteBuf, Request> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                Request::gadget, Request::new);

        @Override
        public Type<Request> type() {
            return TYPE;
        }
    }

    /** Client to server: the gadget key was pressed; open the one carried gadget or offer the carried ones. */
    public record Pick() implements CustomPacketPayload {
        public static final Type<Pick> TYPE = new Type<>(MaxtechStorage.id("pick_carried"));
        public static final StreamCodec<ByteBuf, Pick> STREAM_CODEC = StreamCodec.unit(new Pick());

        @Override
        public Type<Pick> type() {
            return TYPE;
        }
    }

    /** Server to client: the kinds of gadget the player carries ({@link CarriedGadget} indices), for the picker. */
    public record Choices(List<Integer> gadgets) implements CustomPacketPayload {
        public static final Type<Choices> TYPE = new Type<>(MaxtechStorage.id("carried_choices"));
        public static final StreamCodec<ByteBuf, Choices> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(GadgetPick.MAX_ROWS)), Choices::gadgets, Choices::new);

        @Override
        public Type<Choices> type() {
            return TYPE;
        }
    }

    private CarriedOpen() {
    }

    /** Registers the payloads; called once during mod construction. */
    static void register() {
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, Request.TYPE, Request.STREAM_CODEC,
                (payload, player) -> {
                    CarriedGadget gadget = CarriedGadget.byIndex(payload.gadget());
                    if (player instanceof ServerPlayer server && gadget != null) {
                        open(server, gadget);
                    }
                });
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, Pick.TYPE, Pick.STREAM_CODEC,
                (payload, player) -> {
                    if (player instanceof ServerPlayer server) {
                        pick(server);
                    }
                });
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, Choices.TYPE, Choices.STREAM_CODEC,
                (payload, player) -> GadgetPickerScreen.show(payload.gadgets()));
    }

    /** The kinds of gadget {@code player} carries, in {@link CarriedGadget} order. */
    public static List<CarriedGadget> carried(ServerPlayer player) {
        List<CarriedGadget> found = new ArrayList<>(CarriedGadget.values().length);
        for (CarriedGadget gadget : CarriedGadget.values()) {
            if (CarriedItems.locate(player, gadget.item()).isPresent()) {
                found.add(gadget);
            }
        }
        return found;
    }

    /**
     * One press of the gadget key: says that nothing is carried, opens the one kind carried, or sends the picker its
     * choices. Returns what it did; empty when it did nothing (a spectator, or another menu open).
     */
    @SuppressWarnings("ReferenceEquality") // menus are compared by identity
    public static Optional<GadgetPick.Outcome> pick(ServerPlayer player) {
        if (player.isSpectator() || player.containerMenu != player.inventoryMenu) {
            return Optional.empty();
        }
        List<CarriedGadget> found = carried(player);
        GadgetPick.Outcome outcome = GadgetPick.outcome(found.size());
        switch (outcome) {
            case NONE -> {
                // A GameTest player has no connection to send the message to.
                if (player.connection != null) {
                    player.sendOverlayMessage(Component.translatable(CarriedContent.NONE_KEY));
                }
            }
            case OPEN -> open(player, found.getFirst());
            case CHOOSE -> {
                List<Integer> indices = new ArrayList<>(found.size());
                for (CarriedGadget gadget : found) {
                    indices.add(gadget.index());
                }
                if (player.connection != null) {
                    Platform.get().network().sendToPlayer(player, new Choices(indices));
                }
            }
        }
        return Optional.of(outcome);
    }

    /**
     * Opens the first {@code gadget} {@code player} carries; returns whether one was found (it may still refuse to
     * open, and then says why). Tells the player when none is carried. Does nothing while another menu is open.
     */
    @SuppressWarnings("ReferenceEquality") // menus are compared by identity
    public static boolean open(ServerPlayer player, CarriedGadget gadget) {
        if (player.isSpectator() || player.containerMenu != player.inventoryMenu) {
            return false;
        }
        Optional<CarriedSlot> slot = CarriedItems.locate(player, gadget.item());
        if (slot.isEmpty()) {
            // A GameTest player has no connection to send the message to.
            if (player.connection != null) {
                player.sendOverlayMessage(Component.translatable(CarriedContent.noneKey(gadget)));
            }
            return false;
        }
        gadget.open(player, slot.get());
        return true;
    }
}
