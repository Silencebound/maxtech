package dev.silencebound.maxtech.storage.crafting.stock;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.iface.InterfaceCrafting;
import dev.silencebound.maxtech.storage.iface.InterfaceDevice;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.research.ResearchGates;
import dev.silencebound.maxtech.storage.research.ResearchUnlocks;
import java.util.IdentityHashMap;
import java.util.Iterator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * The crafting card in an interface (M7 C21), installed as the interface's {@link InterfaceCrafting.Handler}. When a
 * stock slot waits for a key the network holds none of, the card asks {@link BackgroundCrafts} for the slot's missing
 * amount, with the port card's limits: one request out per interface, at most one per
 * {@code crafting.cardIntervalTicks}, refused ones included, and only with the owner's team's
 * {@link ResearchGates#CRAFTING_CARD}. A request held back by the interval or refused schedules one wake of the
 * interface's stock task once the interval is over (a task of its own on the interface's network scheduler, so nothing
 * polls); a request that ends wakes the stock task too. Server thread only.
 */
public final class InterfaceCardCrafting implements InterfaceCrafting.Handler {
    public static final InterfaceCardCrafting INSTANCE = new InterfaceCardCrafting();

    /** One state per interface; removed interfaces are swept out whenever a new one joins. */
    private final IdentityHashMap<InterfaceDevice, Card> cards = new IdentityHashMap<>();

    private InterfaceCardCrafting() {
    }

    @Override
    public void missing(InterfaceDevice device, NetworkState network, ItemStack card, int slot, StackKey key,
            long missing) {
        if (!card.is(PortContent.CRAFTING_CARD.get()) || !(device instanceof NetworkDevice node) || missing <= 0) {
            return;
        }
        Card state = cards.get(device);
        if (state == null) {
            sweep();
            state = new Card(device, node);
            cards.put(device, state);
        }
        state.request(network, key, missing);
    }

    /** Requests the interface's card had taken since the server started; tests read it. */
    public int requests(InterfaceDevice device) {
        Card state = cards.get(device);
        return state == null ? 0 : state.requests;
    }

    private void sweep() {
        for (Iterator<Card> it = cards.values().iterator(); it.hasNext();) {
            if (it.next().node.isRemoved()) {
                it.remove();
            }
        }
    }

    /** Forgets every interface when the server stops. */
    void clear() {
        cards.clear();
    }

    /** One interface's card: its request out, its interval and its retry wake. */
    private static final class Card implements StorageTask {
        private final InterfaceDevice device;
        private final NetworkDevice node;
        private boolean out;
        private long nextRequest;
        /** Game time of the scheduled retry wake, 0 for none. */
        private long wakeAt;
        private int requests;

        Card(InterfaceDevice device, NetworkDevice node) {
            this.device = device;
            this.node = node;
        }

        void request(NetworkState network, StackKey key, long missing) {
            if (out) {
                return;
            }
            ServerLevel level = network.level();
            long now = level.getGameTime();
            if (now < nextRequest) {
                retryAt(nextRequest);
                return;
            }
            nextRequest = now + StorageServerConfig.CRAFTING_CARD_INTERVAL_TICKS.getAsInt();
            // Without the research the card waits for the next key change; a retry timer would poll until research.
            if (!ResearchUnlocks.isUnlocked(level, node.ownerRecord(), ResearchGates.CRAFTING_CARD)) {
                return;
            }
            out = true;
            BackgroundCrafting.Request request = new BackgroundCrafting.Request(key, missing, 0, 0, node.ownerRecord());
            if (BackgroundCrafts.get().submit(network, request, this::ended)) {
                requests++;
            } else {
                out = false;
                retryAt(nextRequest);
            }
        }

        private void ended(BackgroundCrafting.Outcome outcome) {
            out = false;
            wakeStock();
        }

        private void retryAt(long time) {
            if (wakeAt == 0 || time < wakeAt) {
                wakeAt = time;
                // Scheduling an already scheduled task only wakes it; run() then waits until wakeAt.
                node.deviceLink().schedule(this);
            }
        }

        private void wakeStock() {
            if (!node.isRemoved()) {
                node.deviceLink().wake(device.worker().task());
            }
        }

        @Override
        public int run(StorageTransfer transfer, long gameTime) {
            if (wakeAt == 0) {
                return WAIT;
            }
            if (gameTime < wakeAt) {
                return (int) Math.clamp(wakeAt - gameTime, 2, Integer.MAX_VALUE);
            }
            wakeAt = 0;
            wakeStock();
            return WAIT;
        }
    }
}
