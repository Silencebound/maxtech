package dev.silencebound.maxtech.storage.tool.tablet;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.client.CraftingScreens;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.registry.ModContent;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import dev.silencebound.maxtech.storage.terminal.TerminalSource;
import dev.silencebound.maxtech.storage.terminal.TerminalStatus;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongMaps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
// PLATFORM-LEAK: TerminalSource's insert and extract take NeoForge transactions, like every Storage transfer (PRD 7.2).
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * The crafting status screen from the crafting tablet (B09 item 23): the tablet's "Jobs" button opens a terminal menu
 * whose source shows no contents and moves nothing ({@link Source}), only so the terminal's crafting session (job list,
 * the watched job, cancel and resume) works over the tablet's network; the server then tells the client to show the
 * jobs screen at once, and leaving it closes the menu. The wireless terminal needs none of this: its menu is a network
 * terminal, so the jobs tab is there.
 */
public final class TabletJobs {
    public static final String JOBS_KEY = "gui." + MaxtechStorage.MOD_ID + ".crafting_tablet.jobs";
    public static final String JOBS_TOOLTIP_KEY = "gui." + MaxtechStorage.MOD_ID + ".crafting_tablet.jobs_tooltip";

    static {
        ModContent.CONTENT.translation(JOBS_KEY, "Jobs");
        ModContent.CONTENT.translation(JOBS_TOOLTIP_KEY, "Shows the network's crafting jobs");
    }

    private TabletJobs() {
    }

    /** Server to client: show the jobs screen over the just opened terminal menu {@code containerId}. */
    public record Show(int containerId) implements CustomPacketPayload {
        public static final Type<Show> TYPE = new Type<>(MaxtechStorage.id("tablet_jobs"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Show> CODEC = StreamCodec
                .composite(ByteBufCodecs.VAR_INT, Show::containerId, Show::new);

        @Override
        public Type<Show> type() {
            return TYPE;
        }
    }

    /** Registers the payload; called once during mod construction. */
    static void init() {
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, Show.TYPE, Show.CODEC,
                (payload, player) -> CraftingScreens.openJobs(payload.containerId()));
    }

    /**
     * Opens the jobs view over {@code access}'s network for {@code player}: false (nothing opens) without craft access,
     * which the job list needs. Server thread.
     */
    static boolean open(ServerPlayer player, TabletAccess access, Component title) {
        if (!access.canCraft()) {
            return false;
        }
        TerminalMenu.open(player, new Source(access), access.corePos(), title);
        if (player.containerMenu instanceof TerminalMenu menu) {
            Platform.get().network().sendToPlayer(player, new Show(menu.containerId));
        }
        return true;
    }

    /**
     * A terminal source over the tablet's network for the job list alone: no contents, nothing goes in or out, valid
     * while the tablet still reaches the network and the player may see it. Server thread.
     */
    public static final class Source implements TerminalSource {
        private final TabletAccess access;
        private final Charge charge = new Charge();

        public Source(TabletAccess access) {
            this.access = access;
        }

        /**
         * One operation's FE from the tablet (taken when the transaction commits), and the dimension bridge's surcharge
         * from another dimension, as for the tablet's grid transfers: a cancel or resume from the jobs view.
         */
        @Override
        public boolean payAction(TransactionContext transaction) {
            NetworkState network = access.network();
            if (network == null || access.energy() < CraftingTabletItem.energyPerOperation()
                    || !access.bridgePaid(network, transaction)) {
                return false;
            }
            charge.add(transaction);
            return true;
        }

        @Override
        public @Nullable NetworkState network() {
            return access.network();
        }

        @Override
        public boolean stillValid(Player player) {
            return access.canView();
        }

        @Override
        public boolean canAccess(Player player) {
            return access.canView();
        }

        @Override
        public boolean canDeposit(Player player) {
            return false;
        }

        @Override
        public boolean canTake(Player player) {
            return false;
        }

        @Override
        public Int2LongMap contents() {
            return Int2LongMaps.EMPTY_MAP;
        }

        @Override
        public @Nullable StackKey key(int id) {
            return null;
        }

        @Override
        public long amount(int id) {
            return 0;
        }

        @Override
        public long insert(StackKey key, long amount, TransactionContext transaction) {
            return 0;
        }

        @Override
        public long extract(StackKey key, long amount, TransactionContext transaction) {
            return 0;
        }

        @Override
        public void watch(TerminalMenu menu) {
        }

        @Override
        public void unwatch(TerminalMenu menu) {
        }

        @Override
        public int status(int field) {
            NetworkState network = network();
            if (field != TerminalMenu.STATUS) {
                return 0;
            }
            if (network == null) {
                return TerminalStatus.NO_NETWORK.index();
            }
            if (network.status() != StorageNetwork.Status.ONLINE) {
                return TerminalStatus.STOPPED.index();
            }
            return (network.isPowered() ? TerminalStatus.ONLINE : TerminalStatus.NO_POWER).index();
        }

        /** Operations paid in the open transaction; the root commit takes them off the tablet. */
        private final class Charge extends SnapshotJournal<Integer> {
            private int operations;

            void add(TransactionContext transaction) {
                updateSnapshots(transaction);
                operations++;
            }

            @Override
            protected Integer createSnapshot() {
                return operations;
            }

            @Override
            protected void revertToSnapshot(Integer snapshot) {
                operations = snapshot;
            }

            @Override
            protected void onRootCommit(Integer originalState) {
                for (int i = 0; i < operations; i++) {
                    access.pay();
                }
                operations = 0;
            }
        }
    }
}
