package dev.silencebound.maxtech.storage.crafting.terminal;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.terminal.TerminalAction;
import dev.silencebound.maxtech.storage.terminal.TerminalMenu;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The pattern terminal's panel in a {@link TerminalMenu} (B09 item 30): the draft's nine inputs and three outputs,
 * drawn by the screen from the synced draft, below the terminal's item grid. The draft has no slots; every edit is a
 * terminal button of kind {@link TerminalAction.Kind#PATTERN} whose key id packs an {@link Action} and a slot
 * ({@code action * 16 + slot}; inputs 0 to 8, outputs 9 to 11), read with the server's own copy of the carried stack. A
 * click on the item grid or a recipe viewer's drag sets a slot to a key without any item ({@link Ghost}); recipe
 * viewers fill the whole draft through Lib's crafting transfer or {@link PatternTransfer}. Every edit checks the craft
 * access level; taking and storing through the item grid follow the terminal's own rules.
 */
public final class PatternPanel {
    public static final int SLOTS = PatternDraft.INPUTS + PatternDraft.OUTPUTS;

    /** What a pattern button does. */
    public enum Action {
        /** Set the slot from the carried stack, or clear it with an empty hand. */
        SET(0),
        /** Set the slot to a bucket of the carried bucket's fluid. */
        SET_FLUID(1),
        /** One more (a bucket more of fluid). */
        MORE(2),
        /** One less; the last one clears. */
        LESS(3),
        /** Next face for an input. */
        FACE(4),
        /** Toggle an input's "returned" mark. */
        RETURNED(5),
        /** Lock a tag input to its shown key, or unlock it. */
        EXACT(6),
        /** Crafting or processing. */
        MODE(7),
        /** Encode onto a blank pattern from the inventory. */
        ENCODE(8),
        /** Empty the draft. */
        CLEAR(9),
        /** Load the carried encoded pattern into the draft. */
        LOAD(10),
        /** Substitutions for the whole pattern: lock every tag input, or unlock them all. */
        SUBSTITUTE(11),
        /** Crafting mode (the tab; no change when already in it). */
        MODE_CRAFTING(12),
        /** Processing mode (the tab; no change when already in it). */
        MODE_PROCESSING(13),
        /** Multiply every amount of a processing draft: slot 0 by 2, slot 1 by 8. */
        MULTIPLY(14),
        /** Divide every amount of a processing draft, if all stay whole: slot 0 by 2, slot 1 by 8. */
        DIVIDE(15),
        /** Stonecutting mode (the tab). */
        MODE_STONECUTTING(16),
        /** Smithing mode (the tab). */
        MODE_SMITHING(17);

        private final int id;

        Action(int id) {
            this.id = id;
        }

        /** This action on {@code slot}, as the key id of a {@link TerminalAction.Kind#PATTERN} button. */
        public int code(int slot) {
            return id * 16 + slot;
        }

        /** The terminal button id of this action on {@code slot}. */
        public int button(int slot) {
            return new TerminalAction(TerminalAction.Kind.PATTERN, code(slot)).buttonId();
        }

        /** The action with id {@code id}, or null. */
        static @Nullable Action byId(int id) {
            for (Action action : values()) {
                if (action.id == id) {
                    return action;
                }
            }
            return null;
        }
    }

    private final @Nullable PatternTerminal terminal;
    private PatternDraft draft = PatternDraft.EMPTY;
    private PatternEncoder.@Nullable Result result;
    private int sentVersion = -1;
    private long lastTransfer = Long.MIN_VALUE;
    private PatternEncoder.@Nullable Result sentResult;

    /** The server's panel over {@code terminal}, or the client's ({@code null}), filled by {@link DraftSync}. */
    public PatternPanel(@Nullable PatternTerminal terminal) {
        this.terminal = terminal;
    }

    /** Opens {@code terminal}'s GUI for {@code player}, or says why not: it needs the craft access level. */
    public static void open(ServerPlayer player, PatternTerminal terminal, BlockPos pos, Component title) {
        if (!terminal.canUse(player)) {
            player.sendOverlayMessage(Component.translatable(PatternTerminalContent.DENIED_KEY));
            return;
        }
        TerminalMenu.open(player, terminal, pos, title);
    }

    /** The draft as the screen shows it; on the server, the terminal's. */
    public PatternDraft draft() {
        PatternTerminal current = terminal;
        return current != null ? current.encoder().draft() : draft;
    }

    /** The outcome of the last encode, for the screen's status line; null before the first. */
    public PatternEncoder.@Nullable Result result() {
        return result;
    }

    /** Whether {@code player} may keep the panel open: the craft access level; always on the client. */
    public boolean usable(Player player) {
        PatternTerminal current = terminal;
        return current == null || current.canUse(player);
    }

    /**
     * A pattern button with {@code code} ({@link Action#code}) for {@code player} carrying {@code carried}; false for
     * an unknown action, a slot out of range or a player without craft access.
     */
    public boolean click(ServerPlayer player, int code, ItemStack carried) {
        PatternTerminal current = terminal;
        Action kind = Action.byId(code / 16);
        int slot = code % 16;
        if (current == null || !current.canUse(player) || code < 0 || kind == null || slot >= SLOTS) {
            return false;
        }
        PatternEncoder encoder = current.encoder();
        boolean input = slot < PatternDraft.INPUTS;
        ServerLevel level = player.level();
        switch (kind) {
            case SET -> encoder.set(slot, carried, false);
            case SET_FLUID -> encoder.set(slot, carried, true);
            case MORE -> encoder.adjust(level, slot, true);
            case LESS -> encoder.adjust(level, slot, false);
            case FACE -> {
                if (input) {
                    encoder.cycleFace(slot);
                }
            }
            case RETURNED -> {
                if (input) {
                    encoder.toggleReturned(slot);
                }
            }
            case EXACT -> {
                if (input) {
                    encoder.toggleExact(slot);
                }
            }
            case MODE -> encoder.toggleMode();
            case ENCODE -> result = encoder.encode(player);
            case CLEAR -> encoder.clear();
            case LOAD -> encoder.load(level, carried);
            case SUBSTITUTE -> encoder.toggleSubstitutions();
            case MODE_CRAFTING -> encoder.setMode(PatternDraft.Mode.CRAFTING);
            case MODE_PROCESSING -> encoder.setMode(PatternDraft.Mode.PROCESSING);
            case MODE_STONECUTTING -> encoder.setMode(PatternDraft.Mode.STONECUTTING);
            case MODE_SMITHING -> encoder.setMode(PatternDraft.Mode.SMITHING);
            case MULTIPLY, DIVIDE -> encoder.scale(scaleFactor(slot), kind == Action.MULTIPLY);
        }
        // A stonecutting or smithing grid shows the output its recipe gives after every change.
        encoder.refreshTable(level);
        return true;
    }

    /**
     * Sets slot {@code slot} to one of {@code key} without any item: a click on the item grid with the slot selected,
     * or a recipe viewer's drag ({@link Ghost}). False for a slot out of range or a player without craft access.
     */
    public boolean ghost(ServerPlayer player, int slot, StackKey key) {
        PatternTerminal current = terminal;
        if (current == null || !current.canUse(player) || slot < 0 || slot >= SLOTS) {
            return false;
        }
        current.encoder().setKey(slot, key);
        current.encoder().refreshTable(player.level());
        return true;
    }

    /** The factor of a {@link Action#MULTIPLY} or {@link Action#DIVIDE} button: slot 1 (shift-click) is 8, else 2. */
    public static int scaleFactor(int slot) {
        return slot == 1 ? 8 : 2;
    }

    /** Lib's crafting transfer: the recipe goes into the draft as a crafting draft instead of real items. */
    public void receiveCrafting(ServerPlayer player, List<List<ItemStack>> cells) {
        PatternTerminal current = terminal;
        if (current != null && current.canUse(player)) {
            current.encoder().receiveCrafting(player.level(), cells);
        }
    }

    /** A recipe viewer's recipe of another type, from {@link PatternTransfer}. */
    public void receiveProcessing(ServerPlayer player, PatternTransfer transfer) {
        PatternTerminal current = terminal;
        long now = player.level().getGameTime();
        // At most one transfer per tick, as Lib's crafting transfer.
        if (current != null && current.canUse(player) && now != lastTransfer) {
            lastTransfer = now;
            current.encoder().receiveProcessing(player.level(), transfer);
        }
    }

    /** Sends the draft to {@code player} when it changed, and the last encode result with it. */
    public void broadcast(ServerPlayer player, int containerId) {
        PatternTerminal current = terminal;
        if (current == null || player.connection == null) {
            return;
        }
        int version = current.encoder().version();
        if (version != sentVersion || result != sentResult) {
            sentVersion = version;
            sentResult = result;
            Platform.get().network().sendToPlayer(player,
                    new DraftSync(containerId, current.encoder().draft(), result == null ? -1 : result.id()));
        }
    }

    /** Server to client: the draft of open menu {@code containerId} and the last encode result (-1 for none). */
    public record DraftSync(int containerId, PatternDraft draft, int result) implements CustomPacketPayload {
        public static final Type<DraftSync> TYPE = new Type<>(MaxtechStorage.id("pattern_draft"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DraftSync> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, DraftSync::containerId, PatternDraft.STREAM_CODEC, DraftSync::draft,
                ByteBufCodecs.VAR_INT, DraftSync::result, DraftSync::new);

        @Override
        public Type<DraftSync> type() {
            return TYPE;
        }
    }

    /**
     * Client to server: set draft slot {@code slot} of open pattern terminal {@code containerId} to one of {@code key}
     * (a bucket of a fluid). Nothing but the draft changes; the key is the client's, like a recipe viewer's.
     */
    public record Ghost(int containerId, int slot, StackKey key) implements CustomPacketPayload {
        public static final Type<Ghost> TYPE = new Type<>(MaxtechStorage.id("pattern_ghost"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Ghost> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Ghost::containerId, ByteBufCodecs.VAR_INT, Ghost::slot, StackKey.STREAM_CODEC,
                Ghost::key, Ghost::new);

        @Override
        public Type<Ghost> type() {
            return TYPE;
        }
    }

    /** Client: applies a {@link DraftSync} for this panel's menu. */
    void apply(DraftSync sync) {
        draft = sync.draft();
        result = PatternEncoder.Result.byId(sync.result());
    }

    /** The open pattern terminal menu {@code containerId} of {@code player}, still valid; null for any other. */
    private static @Nullable PatternPanel openPanel(Player player, int containerId) {
        if (player.containerMenu instanceof TerminalMenu menu && menu.containerId == containerId
                && menu.stillValid(player)) {
            return menu.patternPanel();
        }
        return null;
    }

    /** Registers the panel's payloads; called once during mod construction. */
    static void registerPayloads() {
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, DraftSync.TYPE, DraftSync.CODEC,
                (payload, player) -> {
                    PatternPanel panel = openPanel(player, payload.containerId());
                    if (panel != null) {
                        panel.apply(payload);
                    }
                });
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, PatternTransfer.TYPE, PatternTransfer.CODEC,
                (payload, player) -> {
                    PatternPanel panel = openPanel(player, payload.containerId());
                    if (panel != null && player instanceof ServerPlayer serverPlayer) {
                        panel.receiveProcessing(serverPlayer, payload);
                    }
                });
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, Ghost.TYPE, Ghost.CODEC, (payload, player) -> {
            PatternPanel panel = openPanel(player, payload.containerId());
            if (panel != null && player instanceof ServerPlayer serverPlayer) {
                panel.ghost(serverPlayer, payload.slot(), payload.key());
            }
        });
    }
}
