package dev.silencebound.maxtech.storage.logic.controller;

import com.mojang.serialization.Dynamic;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.logic.GraphChange;
import dev.silencebound.maxtech.storage.logic.GraphText;
import dev.silencebound.maxtech.storage.logic.LogicLang;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.NetworkRef;
import dev.silencebound.maxtech.storage.logic.Parameters;
import dev.silencebound.maxtech.storage.logic.SettingValue;
import dev.silencebound.maxtech.storage.logic.SettingSpec;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import dev.silencebound.maxtech.storage.logic.template.LogicTemplate;
import dev.silencebound.maxtech.storage.logic.template.LogicTemplates;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The server side of sharing graphs (M7L2): as text ({@link GraphText}) for the system clipboard or the client's
 * in-game clipboard, on a logic card ({@link LogicCard}), and from datapack templates ({@link LogicTemplates}). Export
 * reads graph {@code index} of the sender's open controller; every add (Import, Paste, From card, a template) puts the
 * graph switched off in the next free row. All of them need the open menu with that container id to be still valid
 * (reach and the logic access level, as for every click on the controller). Every request, refused or not, counts
 * against a per-player rate limit across all controllers ({@link ShareLimits}): adds {@link #IMPORT_INTERVAL}, exports
 * and card writes {@link #EXPORT_INTERVAL}, template lists {@link #LIST_INTERVAL} ticks of server time. Texts and card
 * data are untrusted and go through the same checks ({@link GraphText#read}, {@link GraphText#fromData}); every problem
 * comes back as a line for the screen, never an exception. Server thread only.
 */
public final class GraphSharing {
    /** Server ticks between two adds of one player. */
    public static final int IMPORT_INTERVAL = 20;
    /** Server ticks between two exports of one player. */
    public static final int EXPORT_INTERVAL = 5;
    /** Server ticks between two template lists of one player. */
    public static final int LIST_INTERVAL = 10;
    private static final ShareLimits LIMITS = new ShareLimits(IMPORT_INTERVAL);

    private GraphSharing() {
    }

    /** Registers the packets; during mod construction. */
    static void init() {
        SharePayloads.register();
    }

    /** The open, still valid controller menu {@code containerId} of a player with logic access; else null. */
    private static @Nullable LogicControllerBlockEntity controller(ServerPlayer player, int containerId) {
        if (!(player.containerMenu instanceof LogicControllerMenu menu) || menu.containerId != containerId) {
            return null;
        }
        LogicControllerBlockEntity controller = menu.controller();
        if (controller == null || !menu.stillValid(player) || !LogicControllerBlock.canEdit(player, controller)) {
            return null;
        }
        return controller;
    }

    /**
     * Graph {@code index} of {@code player}'s open controller menu {@code containerId} as text for {@code target}
     * ({@link SharePayloads#TO_CLIPBOARD} or {@link SharePayloads#TO_GAME}), or written onto a blank logic card
     * ({@link SharePayloads#TO_CARD}), with the line to show. Null when the menu, reach, logic access or target is
     * wrong (no answer, as for a stale screen).
     */
    public static SharePayloads.@Nullable Exported export(ServerPlayer player, int containerId, int index, int target) {
        LogicControllerBlockEntity controller = controller(player, containerId);
        if (controller == null || target < SharePayloads.TO_CLIPBOARD || target > SharePayloads.TO_CARD) {
            return null;
        }
        if (!allow(player, ShareLimits.EXPORT, EXPORT_INTERVAL)) {
            return exportRefused(containerId, target, ControllerTexts.SHARE_TOO_FAST);
        }
        LogicWorker worker = controller.worker();
        if (index < 0 || index >= worker.size()) {
            return exportRefused(containerId, target, ControllerTexts.SHARE_NO_GRAPH);
        }
        String name = worker.name(index);
        if (target == SharePayloads.TO_CARD) {
            return toCard(player, containerId, name, index, worker.graph(index));
        }
        Optional<String> text = GraphText.write(LogicKey.GRAPH_CODEC,
                player.registryAccess().createSerializationContext(NbtOps.INSTANCE), name, worker.graph(index));
        if (text.isEmpty() || text.get().length() > GraphText.HARD_MAX_LENGTH) {
            return exportRefused(containerId, target, ControllerTexts.SHARE_EXPORT_FAILED);
        }
        int length = text.get().length();
        int max = StorageServerConfig.LOGIC_MAX_IMPORT_LENGTH.getAsInt();
        if (length > max) {
            return new SharePayloads.Exported(containerId, target, true, text.get(), ControllerTexts.SHARE_COPIED_LONG,
                    List.of(String.valueOf(length), String.valueOf(max)));
        }
        return new SharePayloads.Exported(containerId, target, true, text.get(),
                target == SharePayloads.TO_GAME ? ControllerTexts.SHARE_COPIED_GAME : ControllerTexts.SHARE_COPIED,
                List.of(shown(name, index)));
    }

    /**
     * Writes the graph onto a blank logic card of {@code player}: the one in the main hand, else the off hand, else the
     * first in the inventory. One card of a stack is written and goes back to the inventory (or is dropped when it is
     * full).
     */
    private static SharePayloads.Exported toCard(ServerPlayer player, int containerId, String name, int index,
            LogicGraph<LogicKey> graph) {
        Optional<CompoundTag> data = GraphText.encode(LogicKey.GRAPH_CODEC,
                player.registryAccess().createSerializationContext(NbtOps.INSTANCE), graph);
        if (data.isEmpty()) {
            return exportRefused(containerId, SharePayloads.TO_CARD, ControllerTexts.SHARE_EXPORT_FAILED);
        }
        GraphText.Read<LogicKey> tooBig = cardLimits(name, data.get());
        if (!tooBig.error().isEmpty()) {
            return new SharePayloads.Exported(containerId, SharePayloads.TO_CARD, false, "", tooBig.error(),
                    tooBig.args());
        }
        ItemStack blank = findCard(player, false);
        if (blank.isEmpty()) {
            return exportRefused(containerId, SharePayloads.TO_CARD, ControllerTexts.SHARE_NO_BLANK_CARD);
        }
        LogicCard card = new LogicCard(name, data.get());
        if (blank.getCount() == 1) {
            LogicCardItem.write(blank, card);
        } else {
            ItemStack written = blank.split(1);
            LogicCardItem.write(written, card);
            if (!player.getInventory().add(written)) {
                player.drop(written, false);
            }
        }
        syncInventory(player);
        return new SharePayloads.Exported(containerId, SharePayloads.TO_CARD, true, "",
                ControllerTexts.SHARE_CARD_WRITTEN, List.of(shown(name, index)));
    }

    /**
     * Adds the graph in {@code text} to {@code player}'s open controller menu {@code containerId} as the next graph,
     * switched off, so it runs only when the player switches it on (Import from the system clipboard, Paste from the
     * in-game one). Refused when the player added less than {@link #IMPORT_INTERVAL} ticks ago, when every row is used,
     * or when {@link GraphText#read} refuses the text (too long for {@code logic.maxImportLength}, not a graph, newer,
     * damaged, more nodes than {@code logic.maxNodes}, an unknown node type or item). Null when the menu or access is
     * wrong.
     */
    public static SharePayloads.@Nullable Imported importGraph(ServerPlayer player, int containerId, String text) {
        LogicControllerBlockEntity controller = controller(player, containerId);
        if (controller == null) {
            return null;
        }
        SharePayloads.Imported refused = checkAdd(player, containerId, controller);
        if (refused != null) {
            return refused;
        }
        GraphText.Read<LogicKey> read = GraphText.read(text, StorageServerConfig.LOGIC_MAX_IMPORT_LENGTH.getAsInt(),
                StorageServerConfig.LOGIC_MAX_NODES.getAsInt(), LogicKey.GRAPH_CODEC,
                player.registryAccess().createSerializationContext(NbtOps.INSTANCE));
        return add(player, containerId, controller, read, ControllerTexts.SHARE_IMPORTED,
                GraphChange.of(LogicLang.HISTORY_IMPORTED));
    }

    /**
     * Adds the rule on {@code player}'s written logic card (the main hand's, else the off hand's, else the first in the
     * inventory) like an import: the same rate limit, free row and checks ({@link GraphText#fromData}). The card keeps
     * its rule. Null when the menu or access is wrong.
     */
    public static SharePayloads.@Nullable Imported loadCard(ServerPlayer player, int containerId) {
        LogicControllerBlockEntity controller = controller(player, containerId);
        if (controller == null) {
            return null;
        }
        SharePayloads.Imported refused = checkAdd(player, containerId, controller);
        if (refused != null) {
            return refused;
        }
        LogicCard card = LogicCardItem.card(findCard(player, true));
        if (card == null) {
            return importRefused(containerId, ControllerTexts.SHARE_NO_CARD, List.of());
        }
        GraphText.Read<LogicKey> tooBig = cardLimits(card.name(), card.graph());
        if (!tooBig.error().isEmpty()) {
            return importRefused(containerId, tooBig.error(), tooBig.args());
        }
        GraphText.Read<LogicKey> read = GraphText.fromData(
                new Dynamic<Tag>(player.registryAccess().createSerializationContext(NbtOps.INSTANCE), card.graph()),
                card.name(), StorageServerConfig.LOGIC_MAX_NODES.getAsInt(), LogicKey.GRAPH_CODEC);
        return add(player, containerId, controller, read, ControllerTexts.SHARE_CARD_LOADED,
                GraphChange.of(LogicLang.HISTORY_FROM_CARD));
    }

    /**
     * The loaded templates for the list (at most {@link SharePayloads#MAX_TEMPLATES}), or null when the menu or access
     * is wrong or the player asked less than {@link #LIST_INTERVAL} ticks ago.
     */
    public static SharePayloads.@Nullable TemplateListed templates(ServerPlayer player, int containerId) {
        if (controller(player, containerId) == null || !allow(player, ShareLimits.LIST, LIST_INTERVAL)) {
            return null;
        }
        List<LogicTemplates.Entry> all = LogicTemplates.all();
        List<SharePayloads.TemplateInfo> infos = new ArrayList<>(Math.min(all.size(), SharePayloads.MAX_TEMPLATES));
        for (int i = 0; i < all.size() && i < SharePayloads.MAX_TEMPLATES; i++) {
            LogicTemplates.Entry entry = all.get(i);
            LogicTemplate<LogicKey> template = entry.template();
            List<SharePayloads.ParamInfo> params = new ArrayList<>();
            for (Parameters.Param<LogicKey> param : Parameters.of(template.graph())) {
                if (params.size() < Parameters.MAX) {
                    params.add(new SharePayloads.ParamInfo(GraphText.shown(param.name()), param.kind(),
                            param.value().map(GraphSharing::valueText).orElse("")));
                }
            }
            infos.add(
                    new SharePayloads.TemplateInfo(entry.id(), GraphText.shown(template.name()), template.description(),
                            template.category(), template.icon(), template.graph().nodes().size(), params));
        }
        return new SharePayloads.TemplateListed(containerId, infos);
    }

    /**
     * Adds template {@code id} like an import: the same rate limit and free row, refused when no pack has it (any more)
     * or it has more nodes than {@code logic.maxNodes}. The rule takes the template's English name. Null when the menu
     * or access is wrong.
     */
    public static SharePayloads.@Nullable Imported addTemplate(ServerPlayer player, int containerId, Identifier id) {
        return addTemplate(player, containerId, id, List.of());
    }

    /**
     * Adds template {@code id} with {@code values} for its parameters (M7L2 row 19), in the order the template declares
     * them, as text; an empty text keeps the template's default. The values become the defaults of the added rule's
     * parameters. A value that does not read as its parameter's kind is refused with
     * {@link ControllerTexts#SHARE_BAD_VALUE}. Otherwise as {@link #addTemplate(ServerPlayer, int, Identifier)}.
     */
    public static SharePayloads.@Nullable Imported addTemplate(ServerPlayer player, int containerId, Identifier id,
            List<String> values) {
        LogicControllerBlockEntity controller = controller(player, containerId);
        if (controller == null) {
            return null;
        }
        SharePayloads.Imported refused = checkAdd(player, containerId, controller);
        if (refused != null) {
            return refused;
        }
        Optional<LogicTemplate<LogicKey>> template = LogicTemplates.get(id);
        if (template.isEmpty()) {
            return importRefused(containerId, ControllerTexts.SHARE_NO_TEMPLATE, List.of());
        }
        LogicGraph<LogicKey> graph = template.get().graph();
        List<Parameters.Param<LogicKey>> params = Parameters.of(graph);
        Map<String, SettingValue<LogicKey>> given = new HashMap<>();
        for (int i = 0; i < values.size() && i < params.size(); i++) {
            String text = values.get(i).strip();
            if (text.isEmpty()) {
                continue;
            }
            Parameters.Param<LogicKey> param = params.get(i);
            Optional<SettingValue<LogicKey>> value = parseValue(param.kind(), text);
            if (value.isEmpty()) {
                return importRefused(containerId, ControllerTexts.SHARE_BAD_VALUE,
                        List.of(GraphText.shown(param.name()), GraphText.shown(text)));
            }
            given.put(param.key(), value.get());
        }
        if (!given.isEmpty()) {
            graph = Parameters.apply(graph, given).graph();
        }
        int max = StorageServerConfig.LOGIC_MAX_NODES.getAsInt();
        if (graph.nodes().size() > max) {
            return importRefused(containerId, GraphText.TOO_MANY_NODES,
                    List.of(String.valueOf(graph.nodes().size()), String.valueOf(max)));
        }
        GraphText.Read<LogicKey> read = new GraphText.Read<>(Optional.of(graph), GraphText.shown(template.get().name()),
                "", List.of());
        return add(player, containerId, controller, read, ControllerTexts.SHARE_TEMPLATE_ADDED,
                GraphChange.of(LogicLang.HISTORY_FROM_TEMPLATE, read.name()));
    }

    /**
     * A parameter value as the template form shows it: a number, "yes" or "no", an item id or {@code #tag}, a label.
     */
    static String valueText(SettingValue<LogicKey> value) {
        return switch (value) {
            case SettingValue.Num<LogicKey>(long number) -> Long.toString(number);
            case SettingValue.Flag<LogicKey>(boolean flag) -> flag ? "yes" : "no";
            case SettingValue.Text<LogicKey>(String text) -> text;
            case SettingValue.Item<LogicKey>(LogicKey key) -> switch (key) {
                case LogicKey.Tag(TagKey<Item> tag) -> "#" + tag.location();
                case LogicKey.FluidTag fluid -> "";
                case LogicKey.Stack(StackKey stack) ->
                    stack instanceof ItemKey item ? BuiltInRegistries.ITEM.getKey(item.item()).toString() : "";
            };
        };
    }

    /**
     * {@code text} as a value of a parameter of kind {@code kind}: digits for a number (commas and spaces ignored),
     * yes/no (also true/false, on/off, 1/0), an item id or {@code #tag} for an item (not air), any text up to a label's
     * length for a label. Empty when it does not read.
     */
    static Optional<SettingValue<LogicKey>> parseValue(int kind, String text) {
        String clean = text.strip();
        switch (kind) {
            case Parameters.NUMBER -> {
                try {
                    return Optional.of(new SettingValue.Num<>(Long.parseLong(clean.replace(",", "").replace(" ", ""))));
                } catch (NumberFormatException e) {
                    return Optional.empty();
                }
            }
            case Parameters.YES_NO -> {
                return switch (clean.toLowerCase(Locale.ROOT)) {
                    case "yes", "true", "on", "1" -> Optional.of(new SettingValue.Flag<>(true));
                    case "no", "false", "off", "0" -> Optional.of(new SettingValue.Flag<>(false));
                    default -> Optional.empty();
                };
            }
            case Parameters.ITEM -> {
                if (clean.startsWith("#")) {
                    Identifier tag = Identifier.tryParse(clean.substring(1));
                    return tag == null
                            ? Optional.empty()
                            : Optional.of(new SettingValue.Item<>(LogicKey.tag(TagKey.create(Registries.ITEM, tag))));
                }
                Identifier item = Identifier.tryParse(clean);
                Optional<Item> found = item == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(item);
                return found.filter(value -> !Items.AIR.equals(value))
                        .map(value -> new SettingValue.Item<>(LogicKey.of(ItemKey.of(value))));
            }
            default -> {
                String label = clean.length() > NetworkRef.MAX_REF ? clean.substring(0, NetworkRef.MAX_REF) : clean;
                return Optional.of(new SettingValue.Text<>(label));
            }
        }
    }

    /**
     * The size limits a card's data must meet, the same as a text import's: at most {@code logic.maxNodes} nodes, and
     * no longer as text ({@link GraphText#writeData}) than {@code logic.maxImportLength} (and the hard cap). Answers a
     * refusal with {@link GraphText#TOO_MANY_NODES} or {@link GraphText#TOO_LONG}; an empty error when it fits.
     */
    private static GraphText.Read<LogicKey> cardLimits(String name, CompoundTag data) {
        int nodes = data.getListOrEmpty("nodes").size();
        int maxNodes = StorageServerConfig.LOGIC_MAX_NODES.getAsInt();
        if (nodes > maxNodes) {
            return new GraphText.Read<>(Optional.empty(), "", GraphText.TOO_MANY_NODES,
                    List.of(String.valueOf(nodes), String.valueOf(maxNodes)));
        }
        int maxLength = Math.min(StorageServerConfig.LOGIC_MAX_IMPORT_LENGTH.getAsInt(), GraphText.HARD_MAX_LENGTH);
        int length = GraphText.writeData(name, data).map(String::length).orElse(Integer.MAX_VALUE);
        if (length > maxLength) {
            return new GraphText.Read<>(Optional.empty(), "", GraphText.TOO_LONG,
                    List.of(length == Integer.MAX_VALUE ? "?" : String.valueOf(length), String.valueOf(maxLength)));
        }
        return new GraphText.Read<>(Optional.empty(), "", "", List.of());
    }

    /** The rate limit and the free row every add checks first; null when both pass. */
    private static SharePayloads.@Nullable Imported checkAdd(ServerPlayer player, int containerId,
            LogicControllerBlockEntity controller) {
        if (!allow(player, ShareLimits.IMPORT, IMPORT_INTERVAL)) {
            return importRefused(containerId, ControllerTexts.SHARE_TOO_FAST, List.of());
        }
        if (controller.worker().size() >= LogicControllerMenu.ROWS) {
            return importRefused(containerId, ControllerTexts.SHARE_NO_ROW,
                    List.of(String.valueOf(LogicControllerMenu.ROWS)));
        }
        return null;
    }

    /** Adds a read graph switched off in the next free row, or answers why not. */
    private static SharePayloads.Imported add(ServerPlayer player, int containerId,
            LogicControllerBlockEntity controller, GraphText.Read<LogicKey> read, String message, GraphChange how) {
        Optional<LogicGraph<LogicKey>> graph = read.graph();
        if (graph.isEmpty()) {
            return importRefused(containerId, read.error(), read.args());
        }
        LogicWorker worker = controller.worker();
        int index = worker.size();
        String name = DeviceLabel.clean(read.name());
        if (index >= LogicControllerMenu.ROWS || worker.setGraph(index, name, graph.get()).isEmpty()) {
            return importRefused(containerId, ControllerTexts.SHARE_NO_ROW,
                    List.of(String.valueOf(LogicControllerMenu.ROWS)));
        }
        // setGraph only schedules a run, so switching it off here comes before any run.
        worker.setEnabled(index, false);
        RuleHistory.record(worker, index, player, List.of(how));
        return new SharePayloads.Imported(containerId, true, index, name, message, List.of(shown(name, index)));
    }

    /**
     * A logic card of {@code player}, written or blank: the main hand's, else the off hand's, else the first in the
     * inventory; the empty stack when there is none.
     */
    public static ItemStack findCard(ServerPlayer player, boolean written) {
        Inventory inventory = player.getInventory();
        ItemStack main = inventory.getSelectedItem();
        if (isCard(main, written)) {
            return main;
        }
        ItemStack off = inventory.getItem(Inventory.SLOT_OFFHAND);
        if (isCard(off, written)) {
            return off;
        }
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isCard(stack, written)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static boolean isCard(ItemStack stack, boolean written) {
        return stack.is(LogicContent.CARD.get()) && (LogicCardItem.card(stack) != null) == written;
    }

    /** Sends the player's changed inventory slots now; the controller's menu has none of its own. */
    @SuppressWarnings("ConstantValue") // GameTest players have no connection.
    private static void syncInventory(ServerPlayer player) {
        if (player.connection != null) {
            player.inventoryMenu.broadcastChanges();
        }
    }

    /** A graph's name as the list shows it: its name, or its row number when it has none. */
    private static String shown(String name, int index) {
        return name.isEmpty() ? "#" + (index + 1) : name;
    }

    private static boolean allow(ServerPlayer player, int kind, int interval) {
        MinecraftServer server = player.level().getServer();
        long now = server == null ? 0 : server.getTickCount();
        return LIMITS.allow(player.getUUID(), kind, interval, now);
    }

    private static SharePayloads.Exported exportRefused(int containerId, int target, String message) {
        return new SharePayloads.Exported(containerId, target, false, "", message, List.of());
    }

    private static SharePayloads.Imported importRefused(int containerId, String message, List<String> args) {
        return new SharePayloads.Imported(containerId, false, -1, "", message, args);
    }
}
