package dev.silencebound.maxtech.storage.logic.controller;

import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.logic.GraphText;
import dev.silencebound.maxtech.storage.logic.Parameters;
import dev.silencebound.maxtech.storage.logic.SettingSpec;
import dev.silencebound.maxtech.storage.logic.controller.client.LogicShareClient;
import dev.silencebound.maxtech.storage.logic.template.LogicTemplate;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * The Logic Controller screen's sharing packets (M7L2). {@link Export} asks for graph {@code index} as text for the
 * system clipboard or the in-game clipboard, or written onto a logic card, and gets {@link Exported} back;
 * {@link Import} sends a text (from either clipboard), {@link CardLoad} asks for the rule on the player's logic card
 * and {@link TemplateAdd} for a datapack template, each answered by {@link Imported}. {@link TemplateList} asks for the
 * loaded templates ({@link TemplateListed}). Texts travel as strings of at most {@link GraphText#HARD_MAX_LENGTH}
 * characters; a longer one fails in the packet decoder and disconnects the sender, which the screen never sends.
 * Everything else (the configured length, the format, access, card and template checks, rate limits) is checked in
 * {@link GraphSharing}.
 */
public final class SharePayloads {
    /** Where an export goes: the system clipboard (as text). */
    public static final int TO_CLIPBOARD = 0;
    /** Where an export goes: the in-game clipboard, kept by the client for this session. */
    public static final int TO_GAME = 1;
    /** Where an export goes: a blank logic card in the player's inventory. */
    public static final int TO_CARD = 2;
    /** Most templates one list answer carries. */
    public static final int MAX_TEMPLATES = 256;

    private static final int MAX_KEY = 256;
    private static final int MAX_ARGS = 4;
    private static final int MAX_ARG = 64;
    private static final StreamCodec<ByteBuf, List<String>> ARGS = ByteBufCodecs.stringUtf8(MAX_ARG)
            .apply(ByteBufCodecs.list(MAX_ARGS));
    private static final StreamCodec<ByteBuf, String> TEXT = ByteBufCodecs.stringUtf8(GraphText.HARD_MAX_LENGTH);

    private SharePayloads() {
    }

    /** Client to server: graph {@code index} of the open controller to {@code target}, please. */
    public record Export(int containerId, int index, int target) implements CustomPacketPayload {
        public static final Type<Export> TYPE = new Type<>(MaxtechStorage.id("logic_share_export"));
        public static final StreamCodec<ByteBuf, Export> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                Export::containerId, ByteBufCodecs.VAR_INT, Export::index, ByteBufCodecs.VAR_INT, Export::target,
                Export::new);

        @Override
        public Type<Export> type() {
            return TYPE;
        }
    }

    /**
     * Server to client: the export's outcome for {@code target}: the graph as text (empty when refused or written to a
     * card), whether it worked, and the line to show, a lang key with its arguments.
     */
    public record Exported(int containerId, int target, boolean ok, String text, String message,
            List<String> args) implements CustomPacketPayload {
        public static final Type<Exported> TYPE = new Type<>(MaxtechStorage.id("logic_share_exported"));
        public static final StreamCodec<ByteBuf, Exported> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                Exported::containerId, ByteBufCodecs.VAR_INT, Exported::target, ByteBufCodecs.BOOL, Exported::ok, TEXT,
                Exported::text, ByteBufCodecs.stringUtf8(MAX_KEY), Exported::message, ARGS, Exported::args,
                Exported::new);

        public Exported {
            args = List.copyOf(args);
        }

        @Override
        public Type<Exported> type() {
            return TYPE;
        }
    }

    /** Client to server: add the graph in {@code text} to the open controller (Import and Paste). */
    public record Import(int containerId, String text) implements CustomPacketPayload {
        public static final Type<Import> TYPE = new Type<>(MaxtechStorage.id("logic_share_import"));
        public static final StreamCodec<ByteBuf, Import> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                Import::containerId, TEXT, Import::text, Import::new);

        @Override
        public Type<Import> type() {
            return TYPE;
        }
    }

    /** Client to server: add the rule on the player's logic card to the open controller. */
    public record CardLoad(int containerId) implements CustomPacketPayload {
        public static final Type<CardLoad> TYPE = new Type<>(MaxtechStorage.id("logic_share_card_load"));
        public static final StreamCodec<ByteBuf, CardLoad> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(CardLoad::new,
                CardLoad::containerId);

        @Override
        public Type<CardLoad> type() {
            return TYPE;
        }
    }

    /**
     * Server to client: an add's outcome (Import, Paste, From card, a template). Added as graph {@code index} named
     * {@code name} (switched off), or refused; either way {@code message} with {@code args} is the line to show.
     */
    public record Imported(int containerId, boolean added, int index, String name, String message,
            List<String> args) implements CustomPacketPayload {
        public static final Type<Imported> TYPE = new Type<>(MaxtechStorage.id("logic_share_imported"));
        public static final StreamCodec<ByteBuf, Imported> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT,
                Imported::containerId, ByteBufCodecs.BOOL, Imported::added, ByteBufCodecs.VAR_INT, Imported::index,
                ByteBufCodecs.stringUtf8(GraphSlot.MAX_NAME), Imported::name, ByteBufCodecs.stringUtf8(MAX_KEY),
                Imported::message, ARGS, Imported::args, Imported::new);

        public Imported {
            args = List.copyOf(args);
        }

        @Override
        public Type<Imported> type() {
            return TYPE;
        }
    }

    /** Client to server: the loaded templates, please. */
    public record TemplateList(int containerId) implements CustomPacketPayload {
        public static final Type<TemplateList> TYPE = new Type<>(MaxtechStorage.id("logic_share_template_list"));
        public static final StreamCodec<ByteBuf, TemplateList> STREAM_CODEC = ByteBufCodecs.VAR_INT
                .map(TemplateList::new, TemplateList::containerId);

        @Override
        public Type<TemplateList> type() {
            return TYPE;
        }
    }

    /**
     * One template as the list shows it: its file id, English name and description (translated on the client when a
     * lang file has the keys), category, icon item id, node count and parameters (M7L2 row 19), whose values the screen
     * asks for before adding it.
     */
    public record TemplateInfo(Identifier id, String name, String description, String category,
            Optional<Identifier> icon, int nodes, List<ParamInfo> params) {
        public static final StreamCodec<ByteBuf, TemplateInfo> STREAM_CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, TemplateInfo::id, ByteBufCodecs.stringUtf8(LogicTemplate.MAX_NAME),
                TemplateInfo::name, ByteBufCodecs.stringUtf8(LogicTemplate.MAX_DESCRIPTION), TemplateInfo::description,
                ByteBufCodecs.stringUtf8(LogicTemplate.MAX_CATEGORY), TemplateInfo::category,
                ByteBufCodecs.optional(Identifier.STREAM_CODEC), TemplateInfo::icon, ByteBufCodecs.VAR_INT,
                TemplateInfo::nodes, ParamInfo.STREAM_CODEC.apply(ByteBufCodecs.list(Parameters.MAX)),
                TemplateInfo::params, TemplateInfo::new);

        public TemplateInfo {
            params = List.copyOf(params);
        }
    }

    /**
     * One parameter of a template (M7L2 row 19): its name, kind ({@link Parameters#NUMBER} and so on) and default value
     * as text (a number, "yes" or "no", an item id or {@code #tag}, a label).
     */
    public record ParamInfo(String name, int kind, String value) {
        public static final StreamCodec<ByteBuf, ParamInfo> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(SettingSpec.MAX_TEXT), ParamInfo::name, ByteBufCodecs.VAR_INT, ParamInfo::kind,
                ByteBufCodecs.stringUtf8(SettingSpec.MAX_TEXT), ParamInfo::value, ParamInfo::new);
    }

    /** Server to client: the loaded templates, at most {@link #MAX_TEMPLATES}, by file id. */
    public record TemplateListed(int containerId, List<TemplateInfo> templates) implements CustomPacketPayload {
        public static final Type<TemplateListed> TYPE = new Type<>(MaxtechStorage.id("logic_share_template_listed"));
        public static final StreamCodec<ByteBuf, TemplateListed> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, TemplateListed::containerId,
                TemplateInfo.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_TEMPLATES)), TemplateListed::templates,
                TemplateListed::new);

        public TemplateListed {
            templates = List.copyOf(templates);
        }

        @Override
        public Type<TemplateListed> type() {
            return TYPE;
        }
    }

    /**
     * Client to server: add template {@code id} to the open controller, with the values for its parameters in the order
     * {@link TemplateInfo#params} lists them, as text; an empty text keeps the default (M7L2 row 19).
     */
    public record TemplateAdd(int containerId, Identifier id, List<String> values) implements CustomPacketPayload {
        public static final Type<TemplateAdd> TYPE = new Type<>(MaxtechStorage.id("logic_share_template_add"));
        public static final StreamCodec<ByteBuf, TemplateAdd> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, TemplateAdd::containerId, Identifier.STREAM_CODEC, TemplateAdd::id,
                ByteBufCodecs.stringUtf8(SettingSpec.MAX_TEXT).apply(ByteBufCodecs.list(Parameters.MAX)),
                TemplateAdd::values, TemplateAdd::new);

        public TemplateAdd {
            values = List.copyOf(values);
        }

        @Override
        public Type<TemplateAdd> type() {
            return TYPE;
        }
    }

    /** Registers the payloads; during mod construction. */
    static void register() {
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, Export.TYPE, Export.STREAM_CODEC,
                (payload, player) -> {
                    if (player instanceof ServerPlayer server) {
                        answer(server,
                                GraphSharing.export(server, payload.containerId(), payload.index(), payload.target()));
                    }
                });
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, Import.TYPE, Import.STREAM_CODEC,
                (payload, player) -> {
                    if (player instanceof ServerPlayer server) {
                        answer(server, GraphSharing.importGraph(server, payload.containerId(), payload.text()));
                    }
                });
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, CardLoad.TYPE, CardLoad.STREAM_CODEC,
                (payload, player) -> {
                    if (player instanceof ServerPlayer server) {
                        answer(server, GraphSharing.loadCard(server, payload.containerId()));
                    }
                });
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, TemplateList.TYPE, TemplateList.STREAM_CODEC,
                (payload, player) -> {
                    if (player instanceof ServerPlayer server) {
                        answer(server, GraphSharing.templates(server, payload.containerId()));
                    }
                });
        Platform.get().network().registerToServer(MaxtechStorage.MOD_ID, TemplateAdd.TYPE, TemplateAdd.STREAM_CODEC,
                (payload, player) -> {
                    if (player instanceof ServerPlayer server) {
                        answer(server, GraphSharing.addTemplate(server, payload.containerId(), payload.id(),
                                payload.values()));
                    }
                });
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, Exported.TYPE, Exported.STREAM_CODEC,
                (payload, player) -> LogicShareClient.exported(payload));
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, Imported.TYPE, Imported.STREAM_CODEC,
                (payload, player) -> LogicShareClient.imported(payload));
        Platform.get().network().registerToClient(MaxtechStorage.MOD_ID, TemplateListed.TYPE,
                TemplateListed.STREAM_CODEC, (payload, player) -> LogicShareClient.templates(payload));
    }

    private static void answer(ServerPlayer player, @org.jspecify.annotations.Nullable CustomPacketPayload answer) {
        if (answer != null) {
            Platform.get().network().sendToPlayer(player, answer);
        }
    }
}
