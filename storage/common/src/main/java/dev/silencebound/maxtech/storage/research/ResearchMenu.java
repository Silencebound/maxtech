package dev.silencebound.maxtech.storage.research;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.registry.ModMenus;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import dev.silencebound.maxtech.storage.label.LabelMenu;
import dev.silencebound.maxtech.storage.network.NetworkDevice;
import org.jspecify.annotations.Nullable;

/**
 * The research screen's menu, slotless. On the server it belongs to one player and their team: once the screen asked
 * for it ({@link ResearchAction.Kind#REFRESH}), it sends a {@link ResearchView} and then a new one whenever the team's
 * progress changed, checked every {@link #CHECK_TICKS} ticks while open and never after. Clicks arrive as container
 * button ids ({@link ResearchAction}). On the client it only holds the last view.
 */
public final class ResearchMenu extends AbstractContainerMenu implements LabelMenu {
    /** How often the open menu looks at its team's revision; a bound on traffic, not a balance value. */
    public static final int CHECK_TICKS = 10;

    private final @Nullable ResearchStation terminal;
    private final @Nullable ServerPlayer player;
    private final @Nullable Consumer<ResearchView> sender;
    private @Nullable ResearchView view;
    private boolean requested;
    private int sentRevision = -1;
    private long nextCheck;

    private ResearchMenu(int containerId, @Nullable ResearchStation terminal, @Nullable ServerPlayer player,
            @Nullable Consumer<ResearchView> sender) {
        super(ModMenus.RESEARCH.get(), containerId);
        this.terminal = terminal;
        this.player = player;
        this.sender = sender;
    }

    /** The client's menu; the terminal's position travels for nothing but symmetry with other menus. */
    public static ResearchMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        buffer.readBlockPos();
        return new ResearchMenu(containerId, null, null, null);
    }

    /** The server's menu for {@code player} at {@code terminal}, sending its views to {@code sender}. */
    public static ResearchMenu server(int containerId, ServerPlayer player, ResearchStation terminal,
            Consumer<ResearchView> sender) {
        return new ResearchMenu(containerId, terminal, player, sender);
    }

    /** Opens the research screen for {@code player}; their team membership is checked again first. */
    public static void open(ServerPlayer player, ResearchStation terminal, Component title) {
        Research.team(player, true);
        Platform.get().menus().open(player,
                new SimpleMenuProvider((id, inventory, opener) -> server(id, player, terminal,
                        view -> Platform.get().network().sendToPlayer(player, view)), title),
                buffer -> buffer.writeBlockPos(terminal.stationPos()));
    }

    /** The last view the server sent; null until the first arrives. Client side. */
    public @Nullable ResearchView view() {
        return view;
    }

    /** Takes a view the server sent for this menu. Client side. */
    public void accept(ResearchView received) {
        view = received;
    }

    @Override
    public boolean clickMenuButton(Player clicker, int id) {
        ServerPlayer owner = player;
        if (owner == null || id < 0) {
            return false;
        }
        ResearchAction action = ResearchAction.decode(id);
        MinecraftServer server = owner.level().getServer();
        if (action.kind() == ResearchAction.Kind.REFRESH) {
            requested = true;
            sentRevision = -1;
            nextCheck = 0;
            return true;
        }
        List<ResearchTree.Entry> entries = Research.tree(server).entries();
        if (action.node() >= entries.size()) {
            return false;
        }
        ResearchTree.Entry entry = entries.get(action.node());
        ResearchTeam team = Research.team(owner, false);
        Research.Result result = switch (action.kind()) {
            case SPEND_POINTS -> Research.spendPoints(server, team, entry.id());
            case USE_TOKEN -> Research.useToken(server, team, entry.id());
            case PUZZLE -> Research.startPuzzle(owner, entry.id(), action.option());
            case REFRESH -> Research.Result.DONE;
        };
        if (result != Research.Result.DONE) {
            return false;
        }
        // Show the result at once rather than at the next check.
        nextCheck = 0;
        return true;
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        ServerPlayer owner = player;
        Consumer<ResearchView> to = sender;
        if (owner == null || to == null || !requested) {
            return;
        }
        long now = owner.level().getGameTime();
        if (now < nextCheck) {
            return;
        }
        nextCheck = now + CHECK_TICKS;
        ResearchTeam team = Research.team(owner, false);
        if (team.revision() != sentRevision) {
            sentRevision = team.revision();
            to.accept(ResearchView.of(owner.level().getServer(), team, containerId));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player clicker, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player viewer) {
        ResearchStation at = terminal;
        return at == null || at.usable(viewer);
    }

    @Override
    public @Nullable NetworkDevice labelDevice() {
        return LabelMenu.device(terminal);
    }
}
