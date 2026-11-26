package dev.silencebound.maxtech.storage.logic.remote;

import dev.silencebound.maxtech.storage.energy.ItemEnergy;
import dev.silencebound.maxtech.lib.api.carried.CarriedSlot;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlock;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicWorker;
import dev.silencebound.maxtech.storage.logic.controller.RuleHistory;
import dev.silencebound.maxtech.storage.logic.LogicLang;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalItem.Reach;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The logic remote's screen (M7L2 chunk 12): the network's notices and the rules of its logic controllers. It holds no
 * slots. The server builds what the screen shows ({@link RemotePayloads.View}) at most every {@link #INTERVAL} ticks,
 * and sends it only when it differs from the last one: the network's notices, its logic controllers' rules and their
 * states, the remote's reach, charge and the player's logic access. While the remote reaches its network the newest
 * notices are also kept on the item, which the screen shows read only out of reach. Pausing and resuming a rule needs
 * reach, charge and the logic access level on that controller, as its own screen does ({@link #setRule}). The menu
 * stays open while the same bound remote stays in the hand (or the slot it was opened from by key); out of reach it
 * just says so.
 */
public final class LogicRemoteMenu extends AbstractContainerMenu {
    /** Ticks between two looks at what the screen shows. */
    public static final int INTERVAL = 10;

    // Server side.
    private final @Nullable ServerPlayer player;
    private final CarriedSlot slot;
    private final @Nullable GlobalPos link;
    private RemotePayloads.@Nullable View sent;
    private long lookedAt = Long.MIN_VALUE / 2;
    /** The logic controllers of {@link #listed}, by position; found again when its devices change. */
    private final List<LogicControllerBlockEntity> controllers = new ArrayList<>();
    private @Nullable NetworkState listed;
    private int listedDevices = -1;
    private int listedRebuilds = -1;

    // Client side: the last view the server sent, and the answer to the last pause or resume.
    private RemotePayloads.View view;
    private @Nullable RemoteResult answer;
    private long answers;

    private LogicRemoteMenu(int containerId, @Nullable ServerPlayer player, CarriedSlot slot, @Nullable GlobalPos link,
            RemotePayloads.View view) {
        super(RemoteContent.MENU.get(), containerId);
        this.player = player;
        this.slot = slot;
        this.link = link;
        this.view = view;
    }

    /**
     * The client's menu: the reach, charge and kept notices from the opening packet; rules come with the first view.
     */
    public static LogicRemoteMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        Reach reach = RemotePayloads.REACH_CODEC.decode(buffer);
        int energy = buffer.readVarInt();
        int count = Math.min(buffer.readVarInt(), ModDataComponents.REMOTE_NOTICE_LIMIT);
        List<NetworkNotices.Notice> notices = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            notices.add(NetworkNotices.Notice.STREAM_CODEC.decode(buffer));
        }
        return new LogicRemoteMenu(containerId, null, CarriedSlot.EMPTY, null,
                new RemotePayloads.View(containerId, reach, energy, false, notices, List.of()));
    }

    /** The server's menu for {@code player}'s remote in {@code hand}; tests make it directly. */
    public static LogicRemoteMenu server(int containerId, ServerPlayer player, InteractionHand hand) {
        return server(containerId, player, CarriedSlot.hand(player, hand));
    }

    /** The server's menu for {@code player}'s remote in {@code slot}; tests make it directly. */
    public static LogicRemoteMenu server(int containerId, ServerPlayer player, CarriedSlot slot) {
        ItemStack stack = slot.get();
        return new LogicRemoteMenu(containerId, player, slot, stack.get(ModDataComponents.WIRELESS_LINK.get()),
                new RemotePayloads.View(containerId, Reach.NO_NETWORK, 0, false, List.of(), List.of()));
    }

    /** Opens the screen of the remote in {@code slot}; the caller checked it is bound and the player may use it. */
    static void open(ServerPlayer player, CarriedSlot slot, Reach reach, Component title) {
        ItemStack stack = slot.get();
        List<NetworkNotices.Notice> kept = LogicRemoteItem.notices(stack);
        int energy = LogicRemoteItem.energy(stack);
        Platform.get().menus().open(player,
                new SimpleMenuProvider((id, inventory, opener) -> server(id, player, slot), title), buffer -> {
                    RemotePayloads.REACH_CODEC.encode(buffer, reach);
                    buffer.writeVarInt(energy);
                    buffer.writeVarInt(kept.size());
                    for (NetworkNotices.Notice notice : kept) {
                        NetworkNotices.Notice.STREAM_CODEC.encode(buffer, notice);
                    }
                });
    }

    /** The remote in its slot while it is still bound to the same network; empty once it was moved away. */
    private ItemStack held() {
        ServerPlayer owner = player;
        if (owner == null || link == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.get();
        return stack.getItem() instanceof LogicRemoteItem
                && link.equals(stack.get(ModDataComponents.WIRELESS_LINK.get())) ? stack : ItemStack.EMPTY;
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        ServerPlayer owner = player;
        if (owner == null) {
            return;
        }
        RemotePayloads.View update = update(owner.level().getGameTime());
        // GameTest players have no connection.
        if (update != null && owner.connection != null) {
            Platform.get().network().sendToPlayer(owner, update);
        }
    }

    /**
     * The view for the screen at {@code gameTime} when it differs from the last one sent, at most every
     * {@link #INTERVAL} ticks; null otherwise. Keeps the network's newest notices on the remote while it reaches the
     * network. Server side.
     */
    public RemotePayloads.@Nullable View update(long gameTime) {
        if (gameTime - lookedAt < INTERVAL) {
            return null;
        }
        lookedAt = gameTime;
        RemotePayloads.View next = build();
        if (next.equals(sent)) {
            return null;
        }
        sent = next;
        return next;
    }

    /** Makes the next {@link #update} look again at once (after a pause or resume). */
    private void lookAgain() {
        lookedAt = Long.MIN_VALUE / 2;
    }

    /** What the screen shows now. Server side; allocates. */
    RemotePayloads.View build() {
        ServerPlayer owner = player;
        ItemStack stack = held();
        if (owner == null || stack.isEmpty()) {
            return new RemotePayloads.View(containerId, Reach.UNBOUND, 0, false, List.of(), List.of());
        }
        Reach reach = LogicRemoteItem.reach(owner, stack);
        NetworkState network = reach == Reach.OK ? LogicRemoteItem.network(owner, stack) : null;
        if (network == null || !network.canAccess(owner)) {
            return new RemotePayloads.View(containerId, reach == Reach.OK ? Reach.NO_NETWORK : reach,
                    LogicRemoteItem.energy(stack), false, LogicRemoteItem.notices(stack), List.of());
        }
        List<NetworkNotices.Notice> notices = network.notices().newest(ModDataComponents.REMOTE_NOTICE_LIMIT);
        LogicRemoteItem.remember(stack, notices);
        List<RemotePayloads.Rule> rules = new ArrayList<>();
        boolean logic = false;
        for (LogicControllerBlockEntity controller : controllers(network)) {
            if (!LogicControllerBlock.canEdit(owner, controller)) {
                continue;
            }
            logic = true;
            LogicWorker worker = controller.worker();
            for (int g = 0; g < worker.size() && rules.size() < RemotePayloads.MAX_RULES; g++) {
                rules.add(new RemotePayloads.Rule(controller.getBlockPos(), g, controller.label().text(),
                        worker.name(g), worker.status(g).id(), enabled(worker, g)));
            }
        }
        return new RemotePayloads.View(containerId, Reach.OK, LogicRemoteItem.energy(stack), logic, notices, rules);
    }

    /**
     * Whether rule {@code graph} is switched on. Every status but PAUSED says so; a rule paused by errors keeps its
     * switch in its saved slot, read only then since that copies the memory of every rule.
     */
    private static boolean enabled(LogicWorker worker, int graph) {
        LogicWorker.Status status = worker.status(graph);
        if (status != LogicWorker.Status.PAUSED) {
            return status != LogicWorker.Status.DISABLED;
        }
        return worker.slots().get(graph).enabled();
    }

    /**
     * The logic controllers of {@code network}, ordered by position. The list is found again only when the network was
     * rebuilt or its device count changed, so an open screen does not walk every device each look.
     */
    @SuppressWarnings("ReferenceEquality") // the network is compared by identity
    private List<LogicControllerBlockEntity> controllers(NetworkState network) {
        if (network != listed || network.deviceCount() != listedDevices || network.rebuilds() != listedRebuilds) {
            listed = network;
            listedDevices = network.deviceCount();
            listedRebuilds = network.rebuilds();
            controllers.clear();
            for (NetworkDevice device : network.devices()) {
                if (device instanceof LogicControllerBlockEntity controller) {
                    controllers.add(controller);
                }
            }
            controllers.sort(Comparator.comparingLong(controller -> controller.getBlockPos().asLong()));
        }
        // A controller broken since the list was made is skipped until the next rebuild.
        List<LogicControllerBlockEntity> live = new ArrayList<>(controllers.size());
        for (LogicControllerBlockEntity controller : controllers) {
            if (!controller.isRemoved() && controller.network() == network) {
                live.add(controller);
            }
        }
        return live;
    }

    /**
     * Switches rule {@code graph} of the logic controller at {@code pos} on ({@code enabled}) or off for {@code actor},
     * through the remote in this menu's hand: it must reach its network with one operation of charge, the controller
     * must be a loaded member of that network, and the player needs the logic access level on it, as at the
     * controller's own screen. A change pays {@code logic.remote.energyPerOperation}; asking for the state the rule is
     * already in pays nothing. Server side.
     */
    @SuppressWarnings("ReferenceEquality") // the network is compared by identity
    public RemoteResult setRule(ServerPlayer actor, BlockPos pos, int graph, boolean enabled) {
        ItemStack stack = held();
        if (player == null || !actor.equals(player) || stack.isEmpty()) {
            return RemoteResult.OUT_OF_REACH;
        }
        Reach reach = LogicRemoteItem.reach(actor, stack);
        if (reach == Reach.NO_POWER) {
            return RemoteResult.NO_POWER;
        }
        NetworkState network = LogicRemoteItem.network(actor, stack);
        if (reach != Reach.OK || network == null) {
            return RemoteResult.OUT_OF_REACH;
        }
        ServerLevel level = network.level();
        if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof LogicControllerBlockEntity controller)
                || controller.network() != network) {
            return RemoteResult.NO_SUCH_RULE;
        }
        if (!LogicControllerBlock.canEdit(actor, controller)) {
            return RemoteResult.DENIED;
        }
        LogicWorker worker = controller.worker();
        if (graph < 0 || graph >= worker.size()) {
            return RemoteResult.NO_SUCH_RULE;
        }
        lookAgain();
        if (enabled(worker, graph) == enabled) {
            return RemoteResult.DONE;
        }
        int cost = LogicRemoteItem.energyPerOperation();
        int energy = LogicRemoteItem.energy(stack);
        if (energy < cost) {
            return RemoteResult.NO_POWER;
        }
        if (cost > 0) {
            ItemEnergy.setInt(stack, ModDataComponents.WIRELESS_ENERGY.get(), energy - cost);
        }
        worker.setEnabled(graph, enabled);
        RuleHistory.record(worker, graph, actor, enabled ? LogicLang.HISTORY_REMOTE_ON : LogicLang.HISTORY_REMOTE_OFF);
        return RemoteResult.DONE;
    }

    /** Client side: keeps the view the server sent. */
    void accept(RemotePayloads.View update) {
        view = update;
    }

    /** Client side: keeps the answer to the last pause or resume. */
    void answered(RemoteResult result) {
        answer = result;
        answers++;
    }

    /** The last view the server sent, or the opening one. Client side. */
    public RemotePayloads.View view() {
        return view;
    }

    /** The answer to the last pause or resume; empty before the first. Client side. */
    public Optional<RemoteResult> answer() {
        return Optional.ofNullable(answer);
    }

    /** How many answers arrived, so the screen shows each once. Client side. */
    public long answers() {
        return answers;
    }

    @Override
    public ItemStack quickMoveStack(Player viewer, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player viewer) {
        return player == null || (viewer.equals(player) && player.isAlive() && !held().isEmpty());
    }
}
