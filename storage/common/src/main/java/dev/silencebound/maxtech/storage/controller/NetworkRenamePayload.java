package dev.silencebound.maxtech.storage.controller;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.logic.NetworkRef;
import dev.silencebound.maxtech.storage.network.NetworkAccess;
import dev.silencebound.maxtech.storage.network.NetworkNames;
import dev.silencebound.maxtech.storage.registry.ModContent;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;

/**
 * Client to server: rename the network of the open controller screen (M7L2 row 13). Handled only for the sender's open
 * {@link ControllerMenu} with that container id while it is valid, and only for a player who may configure the core;
 * the answer is a chat line (renamed, or why not). The next Network tab payload shows the new name.
 *
 * @param containerId
 *            the open controller menu
 * @param name
 *            the name as typed; the server cleans it ({@link NetworkNames#clean})
 */
public record NetworkRenamePayload(int containerId, String name) implements CustomPacketPayload {
    public static final Type<NetworkRenamePayload> TYPE = new Type<>(MaxtechStorage.id("network_rename"));
    public static final StreamCodec<ByteBuf, NetworkRenamePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, NetworkRenamePayload::containerId, ByteBufCodecs.stringUtf8(NetworkRef.MAX_NAME * 4),
            NetworkRenamePayload::name, NetworkRenamePayload::new);
    public static final String RENAMED_KEY = "gui.maxtech_storage.network_name.renamed";
    public static final String TAKEN_KEY = "gui.maxtech_storage.network_name.taken";
    public static final String EMPTY_KEY = "gui.maxtech_storage.network_name.empty";
    public static final String DENIED_KEY = "gui.maxtech_storage.network_name.denied";
    /** The controller screen's name row, its button and the rename page. */
    public static final String ROW_KEY = "gui.maxtech_storage.network_name.row";
    public static final String RENAME_KEY = "gui.maxtech_storage.network_name.rename";
    public static final String TITLE_KEY = "gui.maxtech_storage.network_name.title";
    public static final String HELP_KEY = "gui.maxtech_storage.network_name.help";
    public static final String SAVE_KEY = "gui.maxtech_storage.network_name.save";
    public static final String BACK_KEY = "gui.maxtech_storage.network_name.back";

    static {
        ModContent.CONTENT.translation(RENAMED_KEY, "The network is now called %s");
        ModContent.CONTENT.translation(TAKEN_KEY, "Another network is already called %s");
        ModContent.CONTENT.translation(EMPTY_KEY, "A network needs a name");
        ModContent.CONTENT.translation(DENIED_KEY, "Only a player who may configure the controller can rename it");
        ModContent.CONTENT.translation(ROW_KEY, "Name: %s");
        ModContent.CONTENT.translation(RENAME_KEY, "Rename");
        ModContent.CONTENT.translation(TITLE_KEY, "Network name");
        ModContent.CONTENT.translation(HELP_KEY,
                "Names are unique on the server. Logic rules on other networks name this one as @name, "
                        + "for example intake@North Base.");
        ModContent.CONTENT.translation(SAVE_KEY, "Save");
        ModContent.CONTENT.translation(BACK_KEY, "Back");
    }

    @Override
    public Type<NetworkRenamePayload> type() {
        return TYPE;
    }

    /** Registers the payload; during mod construction. */
    static void register() {
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, TYPE, STREAM_CODEC,
                NetworkRenamePayload::handle);
    }

    /**
     * Renames the network of {@code player}'s open controller menu {@code containerId} to {@code name}; the result's
     * chat line is sent to the player. Returns what happened, or null when there was no such menu. Server side.
     */
    public static NetworkNames.@org.jspecify.annotations.Nullable Renamed apply(Player player, int containerId,
            String name) {
        if (!(player.containerMenu instanceof ControllerMenu menu) || menu.containerId != containerId
                || !menu.stillValid(player)) {
            return null;
        }
        ControllerBlockEntity core = menu.core();
        if (core == null || !menu.renameAllowed(player.level().getGameTime())) {
            return null;
        }
        if (!NetworkAccess.canConfigure(player, core)) {
            player.sendSystemMessage(Component.translatable(DENIED_KEY));
            return null;
        }
        NetworkNames.Renamed result = core.rename(name);
        String clean = NetworkNames.clean(name);
        switch (result) {
            case DONE -> player.sendSystemMessage(Component.translatable(RENAMED_KEY, clean));
            case TAKEN -> player.sendSystemMessage(Component.translatable(TAKEN_KEY, clean));
            case EMPTY -> player.sendSystemMessage(Component.translatable(EMPTY_KEY));
            case SAME -> {
            }
        }
        return result;
    }

    private static void handle(NetworkRenamePayload payload, Player player) {
        apply(player, payload.containerId(), payload.name());
    }
}
